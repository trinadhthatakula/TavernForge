package com.valhalla.tavernforge.data.network.gemini

import com.valhalla.tavernforge.core.model.generation.*
import com.valhalla.tavernforge.data.security.GeminiCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.*
import java.io.Closeable
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Explicit single-call orchestration; no project persistence, hidden verification or retries. */
class GeminiService(
    private val credentials: GeminiCredentials,
    private val transport: GeminiTransport = KtorGeminiTransport(),
    private val policy: GenerationPolicy = GenerationPolicy(),
    private val imageVerifier: GeneratedImageVerifier = AndroidGeneratedImageVerifier(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val active = ConcurrentHashMap<Job, PreparedRequest>()
    private val models = ConcurrentHashMap<String, GeminiModel>()
    private val requestModels = ConcurrentHashMap<String, GeminiModel>()
    private val pageLock = Any()
    private val seenPageTokens = mutableSetOf<String>()
    private var nextPageToken: String? = null
    private var pageCount = 0
    private val credentialObserver = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        credentials.state.collectLatest { state ->
            models.entries.removeIf { it.value.credentialGeneration != state.generation }
            active.forEach { (job, request) ->
                if (!state.available || state.generation != request.credentialGeneration) job.cancel()
            }
        }
    }

    fun grants(): GenerationGrants = policy.grants()

    fun updateGrants(value: GenerationGrants) {
        policy.updateGrants(value)
        active.forEach { (job, request) -> if (!policy.isCurrent(request)) job.cancel() }
    }

    fun prepareModelList(pageToken: String? = null): PreparedRequest {
        if (pageToken != null) synchronized(pageLock) {
            if (pageToken != nextPageToken || pageToken in seenPageTokens || pageCount >= 10 || pageToken.length > 2048) {
                fail(GenerationError.INVALID_INPUT)
            }
        }
        val query = if (pageToken == null) "" else "&pageToken=" + URLEncoder.encode(pageToken, "UTF-8")
        return policy.prepare(GenerationPurpose.MODEL_LIST, null, GenerationTarget(label = "Model metadata only"),
            keyGeneration(), "/v1beta/models?pageSize=100$query", "GET", byteArrayOf(),
            GenerationLimits(maxResponseBytes = 512 * 1024))
    }

    fun prepareText(
        model: GeminiModel, prompt: String, target: GenerationTarget,
        maxOutputTokens: Int = 1024, jsonSchema: String? = null,
    ): PreparedRequest = prepareGeneration(model, prompt, target, maxOutputTokens, jsonSchema, false)

    fun prepareImage(
        model: GeminiModel, prompt: String, target: GenerationTarget, maxOutputTokens: Int = 4096,
    ): PreparedRequest = prepareGeneration(model, prompt, target, maxOutputTokens, null, true)

    /** Counting is a new disclosure/call. It does not consume or approve the generation preview. */
    fun prepareTokenCount(request: PreparedRequest): PreparedRequest {
        if (request.purpose !in setOf(GenerationPurpose.TEXT, GenerationPurpose.IMAGE) || !policy.isCurrent(request)) fail(GenerationError.STALE_PREVIEW)
        if (request.credentialGeneration != keyGeneration()) fail(GenerationError.KEY_CHANGED)
        val generation = GeminiWire.parseObject(request.bodyBytes(), request.limits.maxInputBytes)
        val body = buildJsonObject {
            put("generateContentRequest", JsonObject(generation + ("model" to JsonPrimitive(request.model))))
        }.toString().toByteArray()
        val model = synchronized(pageLock) { currentModel(request) }
        if (!model.capabilities.tokenCounting) fail(GenerationError.UNSUPPORTED_CAPABILITY)
        val prepared = policy.prepare(if (request.purpose == GenerationPurpose.IMAGE) GenerationPurpose.COUNT_IMAGE else GenerationPurpose.COUNT_TEXT,
            request.model, request.target, request.credentialGeneration,
            "/v1beta/${request.model}:countTokens", "POST", body, request.limits.copy(maxResponseBytes = 512 * 1024))
        requestModels[prepared.id] = model
        return prepared
    }

    fun approve(request: PreparedRequest): RequestApproval {
        if (request.credentialGeneration != keyGeneration()) fail(GenerationError.KEY_CHANGED)
        return synchronized(pageLock) {
            if (request.purpose != GenerationPurpose.MODEL_LIST) currentModel(request)
            policy.approve(request)
        }
    }

    /** Caller must pass the current project revision. Returned output is always an unaccepted draft. */
    suspend fun execute(request: PreparedRequest, approval: RequestApproval, currentTarget: GenerationTarget): GeminiResult {
        var admitted = false
        var executionJob: Job? = null
        return try {
            coroutineScope {
                val job = currentCoroutineContext().job
                executionJob = job
                active[job] = request
                currentCoroutineContext().ensureActive()
                credentials.withCredential(request.credentialGeneration) { key ->
                    currentCoroutineContext().ensureActive()
                    synchronized(pageLock) {
                        if (request.purpose != GenerationPurpose.MODEL_LIST) currentModel(request)
                        policy.admit(request, approval, credentials.state.value.generation.takeIf { credentials.state.value.available }, currentTarget)
                    }
                    admitted = true
                    currentCoroutineContext().ensureActive()
                    val response = transport.execute(request, key)
                    currentCoroutineContext().ensureActive()
                    if (response.status !in 200..299) return@withCredential GeminiResult.Failure(statusCode(response.status))
                    val body = response.bodyBytes()
                    if (body.size > request.limits.maxResponseBytes) fail(GenerationError.LIMIT_EXCEEDED)
                    withContext(Dispatchers.Default) {
                        if (request.purpose == GenerationPurpose.MODEL_LIST) listedModels(request, body)
                        else GeminiWire.response(request, body, imageVerifier)
                    }
                }
            }
        } catch (e: GenerationException) { GeminiResult.Failure(e.code) }
        catch (_: CancellationException) { GeminiResult.Failure(if (admitted) GenerationError.OUTCOME_UNKNOWN else GenerationError.CANCELLED) }
        catch (_: Exception) { GeminiResult.Failure(if (admitted) GenerationError.OUTCOME_UNKNOWN else GenerationError.TRANSPORT_FAILURE) }
        finally { executionJob?.let { active.remove(it) }; requestModels.remove(request.id) }
    }

    private fun prepareGeneration(model: GeminiModel, prompt: String, target: GenerationTarget, maxTokens: Int, schema: String?, image: Boolean): PreparedRequest {
        val current = keyGeneration()
        if (models[model.name] !== model || model.credentialGeneration != current ||
            clockMillis() - model.fetchedAtMillis !in 0..86_400_000) fail(GenerationError.MODEL_UNAVAILABLE)
        if (image && !model.capabilities.imageOutput || !image && !model.capabilities.textOutput ||
            schema != null && !model.capabilities.structuredOutput) fail(GenerationError.UNSUPPORTED_CAPABILITY)
        if (prompt.isBlank() || prompt.length > 256 * 1024 || maxTokens !in 1..32_768 ||
            model.outputTokenLimit?.let { maxTokens > it } == true) fail(GenerationError.INVALID_INPUT)
        val schemaObject = schema?.let {
            if (it.length > 32 * 1024) fail(GenerationError.LIMIT_EXCEEDED)
            GeminiWire.parseObject(it.toByteArray(), 32 * 1024)
        }
        val body = buildJsonObject {
            putJsonArray("contents") { add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) }
            }) }
            putJsonObject("generationConfig") {
                put("candidateCount", 1)
                put("maxOutputTokens", maxTokens)
                putJsonArray("responseModalities") { add("TEXT"); if (image) add("IMAGE") }
                putJsonObject("responseFormat") {
                    if (image) putJsonObject("image") { put("mimeType", "IMAGE_JPEG"); put("delivery", "INLINE"); put("imageSize", "IMAGE_SIZE_ONE_K") }
                    else putJsonObject("text") {
                        put("mimeType", if (schemaObject == null) "TEXT_PLAIN" else "APPLICATION_JSON")
                        schemaObject?.let { put("schema", it) }
                    }
                }
            }
            put("store", false)
        }.toString().toByteArray()
        // Enforce UTF-8, surrogate, depth and byte constraints before the preview exists.
        GeminiWire.parseObject(body, 256 * 1024)
        val prepared = policy.prepare(if (image) GenerationPurpose.IMAGE else GenerationPurpose.TEXT, model.name, target,
            current, "/v1beta/${model.name}:generateContent", "POST", body,
            GenerationLimits(maxOutputTokens = maxTokens, maxResponseBytes = if (image) 24 * 1024 * 1024 else 2 * 1024 * 1024))
        synchronized(pageLock) {
            requestModels.entries.removeIf { models[it.value.name] !== it.value || clockMillis() - it.value.fetchedAtMillis !in 0..86_400_000 }
            if (requestModels.size >= 256) fail(GenerationError.LIMIT_EXCEEDED)
            requestModels[prepared.id] = model
        }
        return prepared
    }

    private fun listedModels(request: PreparedRequest, bytes: ByteArray): GeminiResult.Models {
        val root = GeminiWire.parseObject(bytes, request.limits.maxResponseBytes)
        val list = root["models"] as? JsonArray ?: fail(GenerationError.INVALID_RESPONSE)
        if (list.size > 100) fail(GenerationError.LIMIT_EXCEEDED)
        val found = list.map { entry ->
            val value = entry as? JsonObject ?: fail(GenerationError.INVALID_RESPONSE)
            val name = GeminiWire.string(value, "name") ?: fail(GenerationError.INVALID_RESPONSE)
            if (!name.matches(Regex("models/[a-zA-Z0-9._-]{1,128}"))) fail(GenerationError.INVALID_RESPONSE)
            val methods = (value["supportedGenerationMethods"] as? JsonArray)?.map {
                val method = it as? JsonPrimitive ?: fail(GenerationError.INVALID_RESPONSE)
                if (!method.isString || method.content.length > 100) fail(GenerationError.INVALID_RESPONSE)
                method.content
            }?.toSet() ?: emptySet()
            val display = GeminiWire.string(value, "displayName") ?: name
            if (display.length > 256) fail(GenerationError.INVALID_RESPONSE)
            fun limit(key: String): Int? = GeminiWire.number(value, key)?.let {
                if (it !in 1..Int.MAX_VALUE.toLong()) fail(GenerationError.INVALID_RESPONSE)
                it.toInt()
            }
            GeminiModel(name, display, limit("inputTokenLimit"), limit("outputTokenLimit"),
                GeminiCapabilityRegistry.resolve(name, methods), request.credentialGeneration, clockMillis())
        }
        if (found.map { it.name }.toSet().size != found.size) fail(GenerationError.INVALID_RESPONSE)
        val token = GeminiWire.string(root, "nextPageToken")?.takeIf { it.isNotEmpty() }
        if (token != null && token.length > 2048) fail(GenerationError.LIMIT_EXCEEDED)
        synchronized(pageLock) {
            val first = !request.path.contains("&pageToken=")
            if (first) { seenPageTokens.clear(); pageCount = 0; models.clear() }
            else nextPageToken?.let { seenPageTokens.add(it) }
            if (token != null && token in seenPageTokens || ++pageCount > 10) fail(GenerationError.INVALID_RESPONSE)
            nextPageToken = token
            found.forEach { models[it.name] = it }
        }
        return GeminiResult.Models(found, token)
    }

    private fun keyGeneration(): String = credentials.state.value.let {
        if (!it.available) fail(GenerationError.KEY_UNAVAILABLE)
        it.generation ?: fail(GenerationError.KEY_UNAVAILABLE)
    }

    /** Called with pageLock through approval and admission; metadata refresh invalidates old previews. */
    private fun currentModel(request: PreparedRequest): GeminiModel {
        val expected = requestModels[request.id] ?: fail(GenerationError.STALE_PREVIEW)
        if (models[request.model] !== expected || expected.credentialGeneration != request.credentialGeneration ||
            clockMillis() - expected.fetchedAtMillis !in 0..86_400_000) fail(GenerationError.MODEL_UNAVAILABLE)
        return expected
    }

    private fun statusCode(status: Int): GenerationError = when (status) {
        401, 403 -> GenerationError.AUTH_REJECTED
        404 -> GenerationError.MODEL_UNAVAILABLE
        429 -> GenerationError.QUOTA_OR_RATE_LIMIT
        in 400..499 -> GenerationError.REQUEST_REJECTED
        else -> GenerationError.TRANSPORT_FAILURE
    }

    private fun fail(code: GenerationError): Nothing = throw GenerationException(code)

    override fun close() { credentialObserver.cancel(); scope.cancel(); active.keys.forEach { it.cancel() }; requestModels.clear(); models.clear(); transport.close() }
}

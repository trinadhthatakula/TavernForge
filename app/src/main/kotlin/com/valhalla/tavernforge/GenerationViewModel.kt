package com.valhalla.tavernforge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.tavernforge.core.model.*
import com.valhalla.tavernforge.core.model.generation.*
import com.valhalla.tavernforge.data.ProjectRepository
import com.valhalla.tavernforge.data.ProjectStorageError
import com.valhalla.tavernforge.data.ProjectStorageException
import com.valhalla.tavernforge.data.network.gemini.*
import com.valhalla.tavernforge.data.security.CredentialSnapshot
import com.valhalla.tavernforge.data.security.GeminiCredentials
import com.valhalla.tavernforge.format.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

enum class GenerationMode { NEW_JSON, FIELD, LORE_ENTRY, IMAGE }
data class GeneratedFieldDiff(val path: String, val before: String, val after: String)
data class GenerationState(
    val working: Boolean = false,
    val keyWorking: Boolean = false,
    val credential: CredentialSnapshot = CredentialSnapshot(),
    val grants: GenerationGrants = GenerationGrants(),
    val models: List<GeminiModel> = emptyList(),
    val nextModelsPage: String? = null,
    val modelName: String? = null,
    val mode: GenerationMode = GenerationMode.NEW_JSON,
    val format: SourceFormat = SourceFormat.V3,
    val project: ProjectSummary? = null,
    val projectRevision: Long? = null,
    val field: String = "description",
    val includeContext: Boolean = false,
    val concept: String = "",
    val maxTokens: Int = 2048,
    val preview: PreparedRequest? = null,
    val textDraft: String? = null,
    val imageDraft: GeneratedImage? = null,
    val draftIssues: List<Diagnostic> = emptyList(),
    val draftReview: List<String> = emptyList(),
    val fieldDiff: GeneratedFieldDiff? = null,
    val draftAcceptable: Boolean = false,
    val usage: GenerationUsage? = null,
    val returnedModel: String? = null,
    val error: String? = null,
    val notice: String? = null,
    val acceptedProjectId: String? = null,
    val acceptedArtwork: ArtworkSummary? = null,
) {
    val hasDraft get() = textDraft != null || imageDraft != null
    val editingAllowed get() = !working && preview == null && !hasDraft
}

/** Provider output is staged in memory and never persisted until an explicit acceptance action. */
class GenerationViewModel(
    private val engine: CharacterCardEngine,
    private val repository: ProjectRepository,
    private val credentials: GeminiCredentials,
    private val service: GeminiService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GenerationState())
    val state = mutableState.asStateFlow()
    private var target: StoredProject? = null
    private var stagedDocument: CharacterDocument? = null
    private var stagedPatches: List<JsonPatch>? = null
    private var stagedDiff: GeneratedFieldDiff? = null
    private var listingNextPage = false
    private var requestJob: Job? = null
    init {
        viewModelScope.launch {
            credentials.state.collect { snapshot ->
                val previous = mutableState.value.credential
                mutableState.value = mutableState.value.copy(credential = snapshot,
                    models = if (previous.generation != snapshot.generation) emptyList() else mutableState.value.models,
                    modelName = if (previous.generation != snapshot.generation) null else mutableState.value.modelName,
                    preview = if (previous.generation != snapshot.generation) null else mutableState.value.preview)
            }
        }
        viewModelScope.launch { credentials.refresh() }
    }
    override fun onCleared() { requestJob?.cancel(); service.close() }

    fun open(projectId: String?) {
        if (!mutableState.value.editingAllowed) return
        if (projectId == null) {
            target = null
            mutableState.value = mutableState.value.copy(project = null, projectRevision = null, mode = GenerationMode.NEW_JSON,
                error = null, notice = null, acceptedArtwork = null, acceptedProjectId = null)
        } else localAction {
            val stored = repository.load(projectId)
            target = stored
            mutableState.value = mutableState.value.copy(project = stored.summary, projectRevision = stored.documentRevision,
                format = stored.summary.sourceFormat, mode = if (stored.summary.sourceFormat in CHARACTER_FORMATS) GenerationMode.FIELD else GenerationMode.LORE_ENTRY,
                field = "description", includeContext = false, acceptedArtwork = null, acceptedProjectId = null)
        }
    }
    fun updateKey(value: CharArray) {
        if (mutableState.value.keyWorking) { value.fill('\u0000'); return }
        mutableState.value = mutableState.value.copy(keyWorking = true, preview = null, error = null)
        viewModelScope.launch {
            try { credentials.update(value); mutableState.value = mutableState.value.copy(notice = "Key stored with Android Keystore-backed encryption. No network request was made.")
            } catch (e: GenerationException) { mutableState.value = mutableState.value.copy(error = safeGenerationError(e.code))
            } finally { value.fill('\u0000'); mutableState.value = mutableState.value.copy(keyWorking = false) }
        }
    }
    fun deleteKey() {
        if (mutableState.value.keyWorking) return
        mutableState.value = mutableState.value.copy(keyWorking = true, preview = null, error = null)
        viewModelScope.launch {
            try { credentials.delete(); mutableState.value = mutableState.value.copy(notice = "Stored key deleted. In-flight credential access was revoked.")
            } catch (e: GenerationException) { mutableState.value = mutableState.value.copy(error = safeGenerationError(e.code))
            } finally { mutableState.value = mutableState.value.copy(keyWorking = false) }
        }
    }
    fun setGrants(grants: GenerationGrants) {
        service.updateGrants(grants)
        mutableState.value = mutableState.value.copy(grants = grants, preview = null)
    }
    fun setMode(mode: GenerationMode) = change { it.copy(mode = mode) }
    fun setFormat(format: SourceFormat) = change { if (format == SourceFormat.UNKNOWN) it else it.copy(format = format) }
    fun setModel(name: String) = change { if (it.models.none { model -> model.name == name }) it else it.copy(modelName = name) }
    fun setField(field: String) = change { if (field !in GENERATION_FIELDS) it else it.copy(field = field) }
    fun setContext(include: Boolean) = change { it.copy(includeContext = include) }
    fun setConcept(text: String) {
        if (!mutableState.value.editingAllowed) return
        if (text.length > 64 * 1024) mutableState.value = mutableState.value.copy(error = "The concept exceeds the 64 Ki-character input limit; additional input was rejected.")
        else change { it.copy(concept = text) }
    }
    fun setMaxTokens(tokens: Int) = change { if (tokens !in 1..32768) it else it.copy(maxTokens = tokens) }
    private fun change(update: (GenerationState) -> GenerationState) {
        if (mutableState.value.editingAllowed) mutableState.value = update(mutableState.value).copy(error = null, notice = null)
    }
    fun prepareModels(nextPage: Boolean = false) = prepare {
        listingNextPage = nextPage
        service.prepareModelList(if (nextPage) mutableState.value.nextModelsPage else null)
    }
    fun prepareGeneration() = prepare {
        val state = mutableState.value
        val model = state.models.firstOrNull { it.name == state.modelName } ?: throw GenerationException(GenerationError.MODEL_UNAVAILABLE)
        val prompt = generationPrompt(state)
        if (state.mode == GenerationMode.IMAGE) service.prepareImage(model, prompt, generationTarget(), state.maxTokens)
        else service.prepareText(model, prompt, generationTarget(), state.maxTokens,
            jsonSchema = if (model.capabilities.structuredOutput) structuredSchema(state) else null)
    }
    fun prepareCount() {
        val request = mutableState.value.preview ?: return
        if (mutableState.value.working) return
        try { mutableState.value = mutableState.value.copy(preview = service.prepareTokenCount(request), error = null)
        } catch (e: GenerationException) { mutableState.value = mutableState.value.copy(error = safeGenerationError(e.code)) }
    }
    private fun prepare(build: () -> PreparedRequest) {
        if (!mutableState.value.editingAllowed || mutableState.value.keyWorking) return
        localAction {
            val request = withContext(dispatcher) { build() }
            mutableState.value = mutableState.value.copy(preview = request)
        }
    }
    fun cancelPreview() { if (!mutableState.value.working) mutableState.value = mutableState.value.copy(preview = null) }
    fun cancelRequest() { requestJob?.cancel() }
    fun approveRequest() {
        val request = mutableState.value.preview ?: return
        if (mutableState.value.working || mutableState.value.keyWorking) return
        val stateAtRequest = mutableState.value
        val appendModels = listingNextPage
        mutableState.value = stateAtRequest.copy(working = true, preview = null, error = null, notice = null, usage = null)
        requestJob = viewModelScope.launch {
            try {
                val currentTarget = if (request.purpose == GenerationPurpose.MODEL_LIST) request.target else freshTarget()
                val result = service.execute(request, service.approve(request), currentTarget)
                mutableState.value = mutableState.value.copy(preview = null)
                when (result) {
                    is GeminiResult.Models -> {
                        val freshModels = linkedMapOf<String, GeminiModel>()
                        if (appendModels) mutableState.value.models.forEach { freshModels[it.name] = it }
                        result.models.forEach { freshModels[it.name] = it }
                        mutableState.value = mutableState.value.copy(models = freshModels.values.toList(),
                            nextModelsPage = result.nextPageToken, notice = "Model metadata loaded. No content generation request was made.")
                    }
                    is GeminiResult.TokenCount -> mutableState.value = mutableState.value.copy(usage = result.usage, notice = "Provider input-token count received. Prepare generation again for a separate review and approval.")
                    is GeminiResult.Failure -> mutableState.value = mutableState.value.copy(error = safeGenerationError(result.code), usage = result.usage)
                    is GeminiResult.ImageDraft -> mutableState.value = mutableState.value.copy(imageDraft = result.image, usage = result.usage,
                        returnedModel = result.modelVersion, draftAcceptable = true, draftReview = listOf("Save exact ${result.image.mimeType} image bytes as standalone artwork. No attachment is automatic."))
                    is GeminiResult.TextDraft -> {
                        val draft = withContext(dispatcher) { validateDraft(stateAtRequest, result.text) }
                        mutableState.value = mutableState.value.copy(textDraft = result.text, usage = result.usage, returnedModel = result.modelVersion,
                            draftIssues = draft.first, draftAcceptable = draft.second, draftReview = draft.third, fieldDiff = stagedDiff)
                    }
                }
            } catch (_: CancellationException) { mutableState.value = mutableState.value.copy(preview = null, error = "Request cancelled. If it reached the provider, usage and outcome may be unknown. No automatic retry will occur.")
            } catch (e: GenerationException) { mutableState.value = mutableState.value.copy(preview = null, error = safeGenerationError(e.code))
            } catch (_: Exception) { mutableState.value = mutableState.value.copy(preview = null, error = "The request could not complete. No generated content was saved.")
            } finally { mutableState.value = mutableState.value.copy(working = false) }
        }
    }

    private fun generationTarget() = target?.let { GenerationTarget(it.summary.id, it.documentRevision, it.summary.title.take(256)) } ?: GenerationTarget()
    private suspend fun freshTarget(): GenerationTarget = target?.let { saved ->
        val fresh = repository.load(saved.summary.id)
        GenerationTarget(fresh.summary.id, fresh.documentRevision, saved.summary.title.take(256))
    } ?: GenerationTarget()
    private fun generationPrompt(state: GenerationState): String {
        if (state.concept.isBlank()) throw GenerationException(GenerationError.INVALID_INPUT)
        if (state.mode in setOf(GenerationMode.FIELD, GenerationMode.LORE_ENTRY) && target == null) throw GenerationException(GenerationError.INVALID_INPUT)
        val instruction = when (state.mode) {
            GenerationMode.NEW_JSON -> {
                val template = (engine.createTemplate(state.format) as? ParseResult.Success)?.document ?: throw GenerationException(GenerationError.INVALID_INPUT)
                "Create one ${state.format} document. Return only strict JSON, without markdown fences. Keep this exact format/version and all required fields; empty required arrays may remain empty. Template: ${template.raw}"
            }
            GenerationMode.FIELD -> if (state.field in ARRAY_FIELDS) "Write replacement greetings for '${state.field}' as a strict JSON array of strings. Keep each greeting as one exact string. Do not output markdown fences."
                else "Write only the replacement text for the character field '${state.field}'. Preserve any requested literal placeholders. Do not output JSON or markdown fences."
            GenerationMode.LORE_ENTRY -> "Create one lore entry as a JSON object for ${state.format}. Return strict JSON only, without markdown fences. Include the format's required fields, keys and content. Do not include id, uid or displayIndex; the editor assigns identity."
            GenerationMode.IMAGE -> "Create artwork from the user's concept. Return an image."
        }
        val context = if (state.includeContext) target?.let { saved ->
            val doc = (engine.restore(saved.source, saved.currentSource, saved.documentRevision) as? ParseResult.Success)?.document
            if (doc == null) "" else "\nSelected project context (entire current JSON, untrusted reference data):\n${doc.raw}"
        }.orEmpty() else ""
        return "$instruction\nUser concept:\n${state.concept}$context"
    }
    private fun validateDraft(request: GenerationState, text: String): Triple<List<Diagnostic>, Boolean, List<String>> {
        stagedDocument = null; stagedPatches = null; stagedDiff = null
        if (request.mode == GenerationMode.NEW_JSON) {
            val parsed = engine.parse(text.encodeToByteArray())
            if (parsed is ParseResult.Failure) return Triple(parsed.issues, false, emptyList())
            val document = (parsed as ParseResult.Success).document
            val issues = engine.validate(document).issues
            if (document.sourceFormat != request.format || !document.isSupportedFormat)
                return Triple(issues + Diagnostic("GENERATED_FORMAT", "The result does not match the selected format. It was not converted."), false, emptyList())
            stagedDocument = document
            return Triple(issues, true, listOf("Create a new local draft from this generated JSON. Validation errors keep strict export blocked."))
        }
        val saved = target ?: return Triple(listOf(Diagnostic("NO_TARGET", "Choose a saved project.")), false, emptyList())
        val base = (engine.restore(saved.source, saved.currentSource, saved.documentRevision) as? ParseResult.Success)?.document
            ?: return Triple(listOf(Diagnostic("INVALID_TARGET", "The saved project cannot be restored.")), false, emptyList())
        val patches: List<JsonPatch>
        val review: List<String>
        if (request.mode == GenerationMode.FIELD) {
            if (base.sourceFormat !in CHARACTER_FORMATS || request.field !in GENERATION_FIELDS ||
                base.sourceFormat == SourceFormat.V1 && request.field in setOf("creator_notes", "system_prompt", "post_history_instructions", "alternate_greetings", "group_only_greetings") ||
                request.field == "group_only_greetings" && base.sourceFormat != SourceFormat.V3)
                return Triple(listOf(Diagnostic("FIELD_FORMAT", "This field is not native to the selected source format.")), false, emptyList())
            val path = (if (base.sourceFormat == SourceFormat.V1) "" else "/data") + "/${request.field}"
            val value = if (request.field in ARRAY_FIELDS) {
                val parsed = (engine.parseValue(text.encodeToByteArray()) as? JsonValueResult.Success)?.value
                if (parsed !is JsonArray || parsed.any { it !is JsonPrimitive || !it.isString })
                    return Triple(listOf(Diagnostic("GREETINGS_ARRAY", "Generated greetings must be a JSON string array; no delimiter conversion was performed.")), false, emptyList())
                parsed
            } else JsonPrimitive(text)
            patches = listOf(if (valueAt(base.raw, path) == null) JsonPatch.Add(path, value) else JsonPatch.Replace(path, value))
            review = listOf("Replace $path at saved revision ${saved.documentRevision} with the exact generated text. Other fields and the original source remain intact.")
            stagedDiff = GeneratedFieldDiff(path, valueAt(base.raw, path)?.toString() ?: "Absent", value.toString())
        } else {
            val value = (engine.parseValue(text.encodeToByteArray()) as? JsonValueResult.Success)?.value as? JsonObject
                ?: return Triple(listOf(Diagnostic("ENTRY_JSON", "The generated entry must be a strict JSON object.")), false, emptyList())
            val plan = engine.planLoreEdit(base, LoreCommand.AddEntry())
            if (plan !is LoreEditPlanResult.Success) return Triple((plan as LoreEditPlanResult.Failure).issues, false, emptyList())
            val add = plan.patches.single() as JsonPatch.Add
            val generated = value.toMutableMap().apply {
                remove("id"); remove("uid"); remove("displayIndex")
                if (base.sourceFormat == SourceFormat.WORLD_INFO) {
                    val defaults = add.value as JsonObject
                    put("uid", defaults.getValue("uid")); put("displayIndex", defaults.getValue("displayIndex"))
                }
            }
            patches = listOf(JsonPatch.Add(add.path, JsonObject(generated)))
            review = listOf("Add one entry at ${add.path}. Generated id/uid/displayIndex fields are excluded; a native World Info UID and display placement are assigned by the editor.")
        }
        return when (val patched = engine.patch(base, base.revision, patches)) {
            is PatchResult.Failure -> Triple(patched.issues, false, review)
            is PatchResult.Success -> { stagedPatches = patches; stagedDocument = patched.document; Triple(engine.validate(patched.document).issues, true, review) }
        }
    }
    fun discardDraft() {
        if (mutableState.value.working) return
        stagedDocument = null; stagedPatches = null; stagedDiff = null
        mutableState.value = mutableState.value.copy(textDraft = null, imageDraft = null, draftIssues = emptyList(), draftReview = emptyList(), fieldDiff = null, draftAcceptable = false)
    }
    fun acceptDraft() {
        val state = mutableState.value
        if (!state.hasDraft || !state.draftAcceptable || state.working) return
        localAction {
            val image = state.imageDraft
            if (image != null) {
                val ext = if (image.mimeType == "image/png") "png" else "jpg"
                val saved = repository.saveArtwork("Gemini-artwork.$ext", image.bytes())
                mutableState.value = mutableState.value.copy(acceptedArtwork = saved, notice = "Accepted original image saved. Attachment remains a separate action.")
            } else {
                val document = stagedDocument ?: return@localAction
                val patches = stagedPatches
                val summary = if (patches == null) repository.saveImported("Gemini-${state.format.name.lowercase()}.json", document.source)
                else {
                    val saved = target ?: return@localAction
                    repository.applyEdits(saved.summary.id, saved.documentRevision, patches).summary
                }
                mutableState.value = mutableState.value.copy(acceptedProjectId = summary.id, notice = "Accepted generated content saved through the project validation pipeline.")
                if (target?.summary?.id == summary.id) target = repository.load(summary.id)
            }
            stagedDocument = null; stagedPatches = null
            mutableState.value = mutableState.value.copy(textDraft = null, imageDraft = null, draftAcceptable = false,
                draftIssues = emptyList(), draftReview = emptyList(), fieldDiff = null, projectRevision = target?.documentRevision)
        }
    }
    fun attachAcceptedArtwork() {
        val image = mutableState.value.acceptedArtwork ?: return
        val saved = target ?: return
        localAction {
            val composition = repository.loadComposition(saved.summary.id)
            if (composition.documentRevision != saved.documentRevision) throw ProjectStorageException(ProjectStorageError.REVISION_CONFLICT, "Target changed")
            repository.setArtwork(saved.summary.id, composition.compositionRevision, image.id)
            mutableState.value = mutableState.value.copy(notice = "Accepted artwork attached to this character.")
        }
    }
    private fun localAction(block: suspend () -> Unit) {
        if (mutableState.value.working) return
        mutableState.value = mutableState.value.copy(working = true, error = null, notice = null)
        viewModelScope.launch {
            try { block()
            } catch (_: CancellationException) { throw CancellationException()
            } catch (e: GenerationException) { mutableState.value = mutableState.value.copy(error = safeGenerationError(e.code))
            } catch (e: ProjectStorageException) { mutableState.value = mutableState.value.copy(error = if (e.code == ProjectStorageError.REVISION_CONFLICT)
                "The project changed after generation. The draft remains unaccepted; reopen the current project before trying again." else "The local operation failed. The generated draft remains available for review.")
            } catch (_: Exception) { mutableState.value = mutableState.value.copy(error = "The local operation could not complete.")
            } finally { mutableState.value = mutableState.value.copy(working = false) }
        }
    }
    companion object {
        val CHARACTER_FORMATS = setOf(SourceFormat.V1, SourceFormat.V2, SourceFormat.V3)
        val ARRAY_FIELDS = setOf("alternate_greetings", "group_only_greetings")
        val GENERATION_FIELDS = listOf("description", "personality", "scenario", "first_mes", "mes_example", "alternate_greetings", "group_only_greetings", "creator_notes", "system_prompt", "post_history_instructions")
    }

    /** Structured output assists the provider; the independent format engine remains authoritative. */
    private fun structuredSchema(state: GenerationState): String? {
        if (state.mode == GenerationMode.FIELD) return if (state.field in ARRAY_FIELDS)
            """{"type":"array","items":{"type":"string"}}""" else null
        val template = if (state.mode == GenerationMode.NEW_JSON)
            (engine.createTemplate(state.format) as? ParseResult.Success)?.document?.raw
        else if (state.mode == GenerationMode.LORE_ENTRY) target?.let { saved ->
            val document = (engine.restore(saved.source, saved.currentSource, saved.documentRevision) as? ParseResult.Success)?.document
            val add = document?.let { engine.planLoreEdit(it, LoreCommand.AddEntry()) } as? LoreEditPlanResult.Success
            ((add?.patches?.singleOrNull() as? JsonPatch.Add)?.value as? JsonObject)?.let { JsonObject(it - setOf("id", "uid", "displayIndex")) }
        } else null
        if (template == null) return null
        fun schema(value: JsonElement, key: String = ""): JsonObject = when (value) {
            is JsonObject -> buildJsonObject {
                put("type", "object")
                if (value.isNotEmpty()) {
                    put("properties", JsonObject(value.mapValues { (name, child) -> schema(child, name) }))
                    put("required", JsonArray(value.keys.map(::JsonPrimitive)))
                }
                put("additionalProperties", true)
            }
            is JsonArray -> buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", if (key == "entries") "object" else "string") }) }
            JsonNull -> buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("null"), JsonPrimitive(if (key in setOf("caseSensitive", "matchWholeWords", "useGroupScoring")) "boolean" else "number")))) }
            else -> buildJsonObject {
                val primitive = value as JsonPrimitive
                put("type", if (primitive.isString) "string" else if (primitive.booleanOrNull != null) "boolean" else "number")
                if (key in setOf("spec", "spec_version")) put("const", primitive)
            }
        }
        return schema(template).toString()
    }
}

internal fun safeGenerationError(error: GenerationError): String = when (error) {
    GenerationError.KEY_UNAVAILABLE -> "Add a usable Gemini key in settings. No request was sent."
    GenerationError.KEY_CHANGED -> "The key changed. Prepare and review a new request."
    GenerationError.INVALID_KEY_INPUT -> "The key input is empty or contains unsupported characters."
    GenerationError.KEY_STORAGE_FAILURE -> "The encrypted key could not be stored or removed. Update it again before making requests."
    GenerationError.PERMISSION_REQUIRED -> "Enable the separate permission for this operation before preparing a request."
    GenerationError.STALE_PREVIEW, GenerationError.APPROVAL_EXPIRED, GenerationError.APPROVAL_USED -> "The request preview is stale or already used. Prepare and review a new request."
    GenerationError.MODEL_UNAVAILABLE, GenerationError.UNSUPPORTED_CAPABILITY -> "Load a current model list and choose a model with the required verified capability."
    GenerationError.INVALID_INPUT, GenerationError.LIMIT_EXCEEDED -> "The input, response, or selected limits are unsupported or too large."
    GenerationError.AUTH_REJECTED -> "The provider rejected the key. Check it in settings."
    GenerationError.QUOTA_OR_RATE_LIMIT -> "The provider reported a quota or rate limit. No automatic retry was made."
    GenerationError.PROVIDER_BLOCKED -> "The provider blocked this response. No generated content was saved."
    GenerationError.INCOMPLETE_RESPONSE, GenerationError.INVALID_RESPONSE -> "The provider response was incomplete or invalid. No content was saved."
    GenerationError.OUTCOME_UNKNOWN -> "The request may have reached the provider. Its outcome and usage are unknown; no automatic retry was made."
    GenerationError.CANCELLED -> "The request was cancelled."
    else -> "The provider request failed. No automatic retry or save occurred."
}

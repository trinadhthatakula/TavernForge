package com.valhalla.tavernforge.data.network.gemini

import com.valhalla.tavernforge.core.model.generation.*
import com.valhalla.tavernforge.data.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GeminiServiceTest {
    private val target = GenerationTarget("synthetic-project", 2, "Synthetic field")

    @Test fun listedMethodsIntersectExactEvidenceAndNeverInferImageFromNames() = runBlocking {
        fixture().use { fixture ->
            val models = fixture.list()
            assertTrue(models.first { it.name == "models/gemini-3.8-flash" }.capabilities.textOutput)
            assertTrue(models.first { it.name == "models/gemini-3.1-flash-image" }.capabilities.imageOutput)
            assertFalse(models.first { it.name == "models/unknown-image-model" }.capabilities.imageOutput)
            assertFalse(models.first { it.name == "models/gemini-2.5-flash" }.capabilities.textOutput)
            assertEquals(1, fixture.transport.attempts)
        }
    }

    @Test fun generatedRequestHasExactReviewedCurrentWireFormatAndNoTools() = runBlocking {
        fixture().use { f ->
            val model = f.list().first()
            f.service.updateGrants(GenerationGrants(text = true))
            val request = f.service.prepareText(model, "Synthetic greeting", target, jsonSchema = "{\"type\":\"object\"}")
            val wire = Json.parseToJsonElement(request.bodyPreview).jsonObject
            assertEquals(JsonPrimitive(false), wire["store"])
            assertFalse(wire.containsKey("tools"))
            assertEquals("APPLICATION_JSON", wire["generationConfig"]!!.jsonObject["responseFormat"]!!.jsonObject["text"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content)
            f.transport.body = completeText("{\"greeting\":\"Hello\"}")
            val result = f.service.execute(request, f.service.approve(request), target) as GeminiResult.TextDraft
            assertEquals("{\"greeting\":\"Hello\"}", result.text)
            assertNull(result.usage)
            assertFalse(result.toString().contains("Hello"))
            assertArrayEquals(request.bodyBytes(), f.transport.lastBody)
        }
    }

    @Test fun refreshingModelEvidenceInvalidatesExistingApprovalsAtDispatch() = runBlocking {
        fixture().use { f ->
            val model = f.list().first(); f.service.updateGrants(GenerationGrants(text = true))
            val request = f.service.prepareText(model, "Synthetic", target); val approval = f.service.approve(request)
            f.list()
            val count = f.transport.attempts
            assertEquals(GenerationError.MODEL_UNAVAILABLE, (f.service.execute(request, approval, target) as GeminiResult.Failure).code)
            assertEquals(count, f.transport.attempts)
        }
    }

    @Test fun countTokensRequiresAnotherReviewAndDoesNotGenerate() = runBlocking {
        fixture().use { f ->
            val model = f.list().first(); f.service.updateGrants(GenerationGrants(text = true))
            val generation = f.service.prepareText(model, "Synthetic", target)
            val count = f.service.prepareTokenCount(generation)
            assertEquals(1, f.transport.attempts)
            assertTrue(count.path.endsWith(":countTokens"))
            assertEquals(model.name, Json.parseToJsonElement(count.bodyPreview).jsonObject["generateContentRequest"]!!.jsonObject["model"]!!.jsonPrimitive.content)
            f.transport.body = "{\"totalTokens\":9}".toByteArray()
            val result = f.service.execute(count, f.service.approve(count), target) as GeminiResult.TokenCount
            assertTrue(result.usage.inputCountOnly); assertEquals(9L, result.usage.promptTokens)
            assertEquals(2, f.transport.attempts)
        }
    }

    @Test fun failuresUseSafeCodesAndNeverRetryRawProviderErrors() = runBlocking {
        for ((status, expected) in mapOf(400 to GenerationError.REQUEST_REJECTED, 401 to GenerationError.AUTH_REJECTED,
            403 to GenerationError.AUTH_REJECTED, 404 to GenerationError.MODEL_UNAVAILABLE,
            429 to GenerationError.QUOTA_OR_RATE_LIMIT, 500 to GenerationError.TRANSPORT_FAILURE, 302 to GenerationError.TRANSPORT_FAILURE)) {
            fixture().use { f ->
                val model = f.list().first(); f.service.updateGrants(GenerationGrants(text = true))
                val request = f.service.prepareText(model, "Synthetic", target)
                f.transport.status = status; f.transport.body = "raw-error-must-not-leak".toByteArray()
                val result = f.service.execute(request, f.service.approve(request), target) as GeminiResult.Failure
                assertEquals(expected, result.code); assertEquals(2, f.transport.attempts)
                assertFalse(result.toString().contains("raw-error"))
            }
        }
    }

    @Test fun malformedBlockedTruncatedAndToolResponsesCannotBecomeDrafts() = runBlocking {
        val responses = listOf(
            "{\"candidates\":[],\"candidates\":[]}" to GenerationError.INVALID_RESPONSE,
            "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}" to GenerationError.PROVIDER_BLOCKED,
            "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}]}" to GenerationError.INCOMPLETE_RESPONSE,
            "{\"candidates\":[{\"finishReason\":\"CONTINUATION\",\"continuationToken\":\"synthetic\"}]}" to GenerationError.INCOMPLETE_RESPONSE,
            "{\"candidates\":[{\"finishReason\":\"FUTURE_REASON\"}]}" to GenerationError.INVALID_RESPONSE,
            "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"send_secret\"}}]}}]}" to GenerationError.INVALID_RESPONSE,
            "{\"usageMetadata\":{\"promptTokenCount\":-1}}" to GenerationError.INVALID_RESPONSE,
        )
        for ((body, expected) in responses) fixture().use { f ->
            val model = f.list().first(); f.service.updateGrants(GenerationGrants(text = true))
            val request = f.service.prepareText(model, "Synthetic", target)
            f.transport.body = body.toByteArray()
            assertEquals(expected, (f.service.execute(request, f.service.approve(request), target) as GeminiResult.Failure).code)
            assertEquals(2, f.transport.attempts)
        }
    }

    @Test fun suppliedUsageRemainsNullableAndDoesNotSubtractThinkingOrCache() = runBlocking {
        fixture().use { f ->
            val model = f.list().first(); f.service.updateGrants(GenerationGrants(text = true))
            val request = f.service.prepareText(model, "Synthetic", target)
            f.transport.body = (String(completeText("draft")).dropLast(1) +
                ",\"usageMetadata\":{\"promptTokenCount\":10,\"thoughtsTokenCount\":7,\"cachedContentTokenCount\":5,\"totalTokenCount\":20,\"promptTokensDetails\":[{\"modality\":\"TEXT\",\"tokenCount\":10}]}}").toByteArray()
            val result = f.service.execute(request, f.service.approve(request), target) as GeminiResult.TextDraft
            assertEquals(10L, result.usage!!.promptTokens); assertEquals(7L, result.usage!!.thinkingTokens)
            assertNull(result.usage!!.outputTokens)
            assertEquals(10L, result.usage!!.modalityDetails["promptTokensDetails"]!!.single().tokenCount)
        }
    }

    @Test fun imageGrantAllowsOnlyOneValidatedUnacceptedImageAndNoTextGrantIsInferred() = runBlocking {
        fixture().use { f ->
            val model = f.list().first { it.capabilities.imageOutput }
            f.service.updateGrants(GenerationGrants(image = true))
            val request = f.service.prepareImage(model, "Synthetic artwork", target)
            f.transport.body = "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"AQID\"}}]}}]}".toByteArray()
            val result = f.service.execute(request, f.service.approve(request), target) as GeminiResult.ImageDraft
            assertArrayEquals(byteArrayOf(1, 2, 3), result.image.bytes())
            result.image.bytes().fill(0)
            assertArrayEquals(byteArrayOf(1, 2, 3), result.image.bytes())
            assertFalse(f.service.grants().text)
        }
    }

    @Test fun repeatedPaginationTokenStopsInsteadOfLooping() = runBlocking {
        fixture().use { f ->
            f.transport.body = "{\"models\":[],\"nextPageToken\":\"again\"}".toByteArray()
            val first = f.service.prepareModelList()
            val firstResult = f.service.execute(first, f.service.approve(first), first.target) as GeminiResult.Models
            val next = f.service.prepareModelList(firstResult.nextPageToken)
            assertEquals(GenerationError.INVALID_RESPONSE, (f.service.execute(next, f.service.approve(next), next.target) as GeminiResult.Failure).code)
            assertEquals(2, f.transport.attempts)
        }
    }

    @Test fun grantRevocationCancelsInFlightWorkAndReportsUnknownOutcomeWithoutReplay() = runBlocking {
        fixture().use { f ->
            val model = f.list().first(); f.service.updateGrants(GenerationGrants(text = true))
            val request = f.service.prepareText(model, "Synthetic", target); val approval = f.service.approve(request)
            val entered = CompletableDeferred<Unit>()
            f.transport.waiter = { entered.complete(Unit); awaitCancellation() }
            val result = async { f.service.execute(request, approval, target) }
            withTimeout(5000) { entered.await() }
            f.service.updateGrants(GenerationGrants())
            assertEquals(GenerationError.OUTCOME_UNKNOWN, (withTimeout(5000) { result.await() } as GeminiResult.Failure).code)
            assertEquals(2, f.transport.attempts)
        }
    }

    @Test fun transportExceptionAndOversizedBodyAreSafeAndSingleAttempt() = runBlocking {
        for (oversized in listOf(false, true)) fixture().use { f ->
            val model = f.list().first(); f.service.updateGrants(GenerationGrants(text = true))
            val request = f.service.prepareText(model, "Synthetic", target)
            if (oversized) f.transport.body = ByteArray(2 * 1024 * 1024 + 1)
            else f.transport.waiter = { throw IllegalStateException("raw-exception-must-not-leak") }
            val result = f.service.execute(request, f.service.approve(request), target) as GeminiResult.Failure
            assertEquals(if (oversized) GenerationError.LIMIT_EXCEEDED else GenerationError.OUTCOME_UNKNOWN, result.code)
            assertFalse(result.toString().contains("raw-exception")); assertEquals(2, f.transport.attempts)
        }
    }

    private fun fixture(): Fixture = Fixture()
    private class Fixture : java.io.Closeable {
        val transport = TestTransport()
        val service = GeminiService(TestCredentials(), transport, imageVerifier = GeneratedImageVerifier { bytes, _, _ ->
            check(bytes.contentEquals(byteArrayOf(1, 2, 3))) // Deliberately fake decoder; AVD covers the real one.
            1 to 1
        })
        suspend fun list(): List<GeminiModel> {
            transport.status = 200
            transport.body = """{"models":[
                {"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent","countTokens"],"outputTokenLimit":65536},
                {"name":"models/gemini-3.1-flash-image","supportedGenerationMethods":["generateContent"]},
                {"name":"models/unknown-image-model","supportedGenerationMethods":["generateContent"]},
                {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["embedContent"]}]}
            """.trimIndent().toByteArray()
            val request = service.prepareModelList()
            return (service.execute(request, service.approve(request), request.target) as GeminiResult.Models).models
        }
        override fun close() { service.close() }
    }

    private class TestCredentials : GeminiCredentials {
        override val state: StateFlow<CredentialSnapshot> = MutableStateFlow(CredentialSnapshot(CredentialAvailability.AVAILABLE, "test-generation"))
        override suspend fun refresh() = Unit
        override suspend fun update(value: CharArray) { value.fill('\u0000') }
        override suspend fun delete() = Unit
        override suspend fun <T> withCredential(expectedGeneration: String, action: suspend (CharArray) -> T): T {
            val input = "invalid-test-only-no-real-google-key".toCharArray()
            return try { action(input) } finally { input.fill('\u0000') }
        }
    }

    private class TestTransport : GeminiTransport {
        var body = byteArrayOf(); var status = 200; var attempts = 0
        var lastBody: ByteArray? = null
        var waiter: (suspend () -> Unit)? = null
        override suspend fun execute(request: PreparedRequest, credential: CharArray): GeminiHttpResponse {
            attempts++; lastBody = request.bodyBytes(); waiter?.invoke()
            return GeminiHttpResponse(status, body)
        }
    }

    private fun completeText(text: String): ByteArray = buildJsonObject {
        putJsonArray("candidates") { add(buildJsonObject {
            put("finishReason", "STOP")
            putJsonObject("content") { putJsonArray("parts") { add(buildJsonObject { put("text", text) }) } }
        }) }
    }.toString().toByteArray()
}

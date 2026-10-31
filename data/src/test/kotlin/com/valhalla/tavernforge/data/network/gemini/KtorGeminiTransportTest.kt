package com.valhalla.tavernforge.data.network.gemini

import com.valhalla.tavernforge.core.model.generation.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class KtorGeminiTransportTest {
    private val sentinel = "invalid-test-only-no-real-google-key"

    @Test fun authenticationIsHeaderOnlyAndExactBodyDoesNotContainCredential() = runBlocking {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            assertEquals(sentinel, request.headers["x-goog-api-key"])
            assertFalse(request.url.toString().contains(sentinel))
            assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        KtorGeminiTransport(client(engine)).use { transport ->
            val request = prepared()
            val response = transport.execute(request, sentinel.toCharArray())
            assertEquals(200, response.status); assertEquals(1, calls)
            assertFalse(request.bodyPreview.contains(sentinel))
        }
    }

    @Test fun redirectBodyIsDiscardedAndNoSecondRequestIsMade() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("raw-sensitive-provider-error", HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://unapproved.invalid/steal"))
        }
        KtorGeminiTransport(client(engine)).use {
            val response = it.execute(prepared(), sentinel.toCharArray())
            assertEquals(302, response.status); assertTrue(response.bodyBytes().isEmpty()); assertEquals(1, calls)
        }
    }

    @Test fun actualStreamLengthIsBoundedWithoutContentLength() = runBlocking {
        val engine = MockEngine { respond("x".repeat(17), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        KtorGeminiTransport(client(engine)).use {
            expect(GenerationError.LIMIT_EXCEEDED) { it.execute(prepared(limit = 16), sentinel.toCharArray()) }
        }
    }

    @Test fun declaredOversizeEncodingAndMimeAreRejected() = runBlocking {
        val cases = listOf(
            headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ContentLength to listOf("999")) to GenerationError.LIMIT_EXCEEDED,
            headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ContentEncoding to listOf("gzip")) to GenerationError.INVALID_RESPONSE,
            headersOf(HttpHeaders.ContentType, "text/html") to GenerationError.INVALID_RESPONSE,
        )
        for ((headers, error) in cases) {
            val engine = MockEngine { respond("{}", HttpStatusCode.OK, headers) }
            KtorGeminiTransport(client(engine)).use { transport ->
                expect(error) { transport.execute(prepared(limit = 16), sentinel.toCharArray()) }
            }
        }
    }

    @Test fun invalidDestinationCannotReceiveCredential() = runBlocking {
        var calls = 0
        val engine = MockEngine { calls++; respond("{}") }
        KtorGeminiTransport(client(engine)).use {
            expect(GenerationError.INVALID_INPUT) { it.execute(prepared(path = "/v1beta/models/../../unexpected:generateContent"), sentinel.toCharArray()) }
            assertEquals(0, calls)
        }
    }

    @Test fun unauthorisedErrorBodyIsNotRetained() = runBlocking {
        var calls = 0
        val engine = MockEngine { calls++; respond("raw-input-and-key-echo", HttpStatusCode.Unauthorized) }
        KtorGeminiTransport(client(engine)).use {
            val response = it.execute(prepared(), sentinel.toCharArray())
            assertTrue(response.bodyBytes().isEmpty()); assertFalse(response.toString().contains("raw-input")); assertEquals(1, calls)
        }
    }

    private fun client(engine: MockEngine) = HttpClient(engine) { followRedirects = false; expectSuccess = false }

    private fun prepared(limit: Int = 1024, path: String = "/v1beta/models/synthetic:generateContent"): PreparedRequest =
        GenerationPolicy().apply { updateGrants(GenerationGrants(text = true)) }.prepare(
            GenerationPurpose.TEXT, "models/synthetic", GenerationTarget(), "test-generation", path, "POST", "{}".toByteArray(),
            GenerationLimits(maxResponseBytes = limit))

    private suspend fun expect(code: GenerationError, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (e: GenerationException) { assertEquals(code, e.code) }
    }
}

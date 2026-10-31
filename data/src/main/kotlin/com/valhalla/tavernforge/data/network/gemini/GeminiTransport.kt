package com.valhalla.tavernforge.data.network.gemini

import com.valhalla.tavernforge.core.model.generation.GenerationError
import com.valhalla.tavernforge.core.model.generation.GenerationException
import com.valhalla.tavernforge.core.model.generation.PreparedRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient

class GeminiHttpResponse(val status: Int, body: ByteArray) {
    private val bytes = body.copyOf()
    fun bodyBytes(): ByteArray = bytes.copyOf()
    override fun toString(): String = "GeminiHttpResponse(status=$status, redacted)"
}

interface GeminiTransport : Closeable {
    /** Exactly one attempt. Implementations must not retain credentials or log request/response data. */
    suspend fun execute(request: PreparedRequest, credential: CharArray): GeminiHttpResponse
    override fun close() = Unit
}

class KtorGeminiTransport internal constructor(private val client: HttpClient) : GeminiTransport {
    constructor() : this(HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        engine {
            config {
                singleGeminiAttempt()
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 120_000
        }
    })

    override suspend fun execute(request: PreparedRequest, credential: CharArray): GeminiHttpResponse {
        validateDestination(request)
        if (credential.isEmpty() || credential.size > 4096 || credential.any { it.code !in 33..126 }) {
            throw GenerationException(GenerationError.KEY_UNAVAILABLE)
        }
        // Header strings cannot be reliably zeroed on the JVM; keep their lifetime within this call.
        return client.prepareRequest(request.origin + request.path) {
            method = if (request.method == "GET") HttpMethod.Get else HttpMethod.Post
            header("x-goog-api-key", String(credential))
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.AcceptEncoding, "identity")
            if (request.method == "POST") {
                contentType(ContentType.Application.Json)
                setBody(request.bodyBytes())
            }
        }.execute { response ->
            // Discard error bodies without parsing or logging provider-controlled text.
            if (response.status.value !in 200..299) return@execute GeminiHttpResponse(response.status.value, byteArrayOf())
            val encoding = response.headers[HttpHeaders.ContentEncoding]
            if (encoding != null && !encoding.equals("identity", true)) {
                throw GenerationException(GenerationError.INVALID_RESPONSE)
            }
            if (response.contentType()?.withoutParameters() != ContentType.Application.Json) {
                throw GenerationException(GenerationError.INVALID_RESPONSE)
            }
            val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && (declared < 0 || declared > request.limits.maxResponseBytes)) {
                throw GenerationException(GenerationError.LIMIT_EXCEEDED)
            }
            val output = ByteArrayOutputStream()
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(8192)
            while (true) {
                val count = channel.readAvailable(buffer, 0, buffer.size)
                if (count < 0) break
                if (output.size().toLong() + count > request.limits.maxResponseBytes) {
                    throw GenerationException(GenerationError.LIMIT_EXCEEDED)
                }
                output.write(buffer, 0, count)
            }
            GeminiHttpResponse(response.status.value, output.toByteArray())
        }
    }

    override fun close() { client.close() }

    private fun validateDestination(request: PreparedRequest) {
        if (request.origin != "https://generativelanguage.googleapis.com") invalid()
        val uri = try { URI(request.origin + request.path) } catch (_: Exception) { invalid() }
        if (uri.scheme != "https" || uri.host != "generativelanguage.googleapis.com" || uri.port != -1 ||
            uri.userInfo != null || uri.fragment != null) invalid()
        val valid = if (request.method == "GET") {
            uri.path == "/v1beta/models" && request.purpose == com.valhalla.tavernforge.core.model.generation.GenerationPurpose.MODEL_LIST
        } else request.method == "POST" && uri.rawQuery == null &&
            Regex("/v1beta/models/[a-zA-Z0-9._-]+:(generateContent|countTokens)").matches(uri.path)
        if (!valid) invalid()
    }

    private fun invalid(): Nothing = throw GenerationException(GenerationError.INVALID_INPUT)
}

/** Guard physical sends as well as retry configuration (including explicit Retry-After follow-ups). */
internal fun OkHttpClient.Builder.singleGeminiAttempt(): OkHttpClient.Builder = apply {
    retryOnConnectionFailure(false)
    followRedirects(false)
    followSslRedirects(false)
    addInterceptor { chain ->
        val request = chain.request().newBuilder().tag(GeminiAttempt::class.java, GeminiAttempt()).build()
        chain.proceed(request)
    }
    addNetworkInterceptor { chain ->
        val attempt = chain.request().tag(GeminiAttempt::class.java)
        if (attempt == null || !attempt.sent.compareAndSet(false, true)) {
            throw IOException("Repeated provider attempt blocked")
        }
        chain.proceed(chain.request())
    }
}

private class GeminiAttempt { val sent = AtomicBoolean(false) }

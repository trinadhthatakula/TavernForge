package com.valhalla.tavernforge.data.network.gemini

import com.valhalla.tavernforge.core.model.generation.GenerationError
import com.valhalla.tavernforge.core.model.generation.GenerationUsage
import java.util.Collections

data class GeminiCapabilities(
    val textOutput: Boolean = false,
    val imageOutput: Boolean = false,
    val structuredOutput: Boolean = false,
    val evidenceUrl: String? = null,
    val reviewedOn: String = "2026-11-01",
    val tokenCounting: Boolean = false,
)

/** Only a credential-scoped list response can construct a selectable model. */
class GeminiModel internal constructor(
    val name: String,
    val displayName: String,
    val inputTokenLimit: Int?,
    val outputTokenLimit: Int?,
    val capabilities: GeminiCapabilities,
    internal val credentialGeneration: String,
    internal val fetchedAtMillis: Long,
) {
    override fun toString(): String = "GeminiModel($name)"
}

internal object GeminiCapabilityRegistry {
    const val VERSION = "official-model-pages-2026-11-01-v1"
    private val records = mapOf(
        "models/gemini-3.8-flash" to GeminiCapabilities(textOutput = true, structuredOutput = true,
            evidenceUrl = "https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash"),
        "models/gemini-2.5-flash" to GeminiCapabilities(textOutput = true, structuredOutput = true,
            evidenceUrl = "https://ai.google.dev/gemini-api/docs/models/gemini-2.5-flash"),
        "models/gemini-3.1-flash-image" to GeminiCapabilities(imageOutput = true,
            evidenceUrl = "https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-image"),
    )

    fun resolve(name: String, methods: Set<String>): GeminiCapabilities =
        if ("generateContent" in methods) (records[name] ?: GeminiCapabilities()).copy(tokenCounting = "countTokens" in methods)
        else GeminiCapabilities()
}

/** Returned bytes are unaccepted staging content, never a managed/project asset reference. */
class GeneratedImage internal constructor(
    bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
) {
    private val content = bytes.copyOf()
    val sizeBytes get() = content.size
    fun bytes(): ByteArray = content.copyOf()
    override fun toString(): String = "GeneratedImage(redacted, ${content.size} bytes)"
}

sealed interface GeminiResult {
    class Models(models: List<GeminiModel>, val nextPageToken: String?) : GeminiResult {
        val models: List<GeminiModel> = Collections.unmodifiableList(models.toList())
        override fun toString(): String = "Models(${models.size})"
    }
    class TextDraft(val text: String, val usage: GenerationUsage?, val modelVersion: String?) : GeminiResult {
        override fun toString(): String = "TextDraft(redacted)"
    }
    class ImageDraft(
        val image: GeneratedImage, val caption: String, val usage: GenerationUsage?, val modelVersion: String?,
    ) : GeminiResult {
        override fun toString(): String = "ImageDraft(redacted)"
    }
    data class TokenCount(val usage: GenerationUsage) : GeminiResult
    data class Failure(val code: GenerationError, val usage: GenerationUsage? = null) : GeminiResult
}

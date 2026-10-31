package com.valhalla.tavernforge.data.network.gemini

import com.valhalla.tavernforge.core.model.JsonLimits
import com.valhalla.tavernforge.core.model.generation.*
import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.JsonValueResult
import kotlinx.serialization.json.*
import java.util.Base64

internal object GeminiWire {
    fun parseObject(bytes: ByteArray, maxBytes: Int): JsonObject {
        if (bytes.size > maxBytes) fail(GenerationError.LIMIT_EXCEEDED)
        val engine = CharacterCardEngine(JsonLimits(maxBytes = maxBytes, maxDepth = 32,
            maxStringChars = maxBytes, maxNodes = 100_000, maxCollectionEntries = 1000))
        val parsed = engine.parseValue(bytes)
        return (parsed as? JsonValueResult.Success)?.value as? JsonObject ?: fail(GenerationError.INVALID_RESPONSE)
    }

    fun response(request: PreparedRequest, bytes: ByteArray, images: GeneratedImageVerifier): GeminiResult {
        val root = parseObject(bytes, request.limits.maxResponseBytes)
        if (request.purpose == GenerationPurpose.COUNT_TEXT || request.purpose == GenerationPurpose.COUNT_IMAGE) {
            val total = number(root, "totalTokens") ?: fail(GenerationError.INVALID_RESPONSE)
            return GeminiResult.TokenCount(GenerationUsage(promptTokens = total,
                cachedTokens = number(root, "cachedContentTokenCount"), inputCountOnly = true, modalityDetails = modalityDetails(root)))
        }
        val usage = root["usageMetadata"]?.let { usage(it as? JsonObject ?: fail(GenerationError.INVALID_RESPONSE)) }
        val prompt = root["promptFeedback"] as? JsonObject
        if (prompt?.get("blockReason") != null && string(prompt, "blockReason") != "BLOCK_REASON_UNSPECIFIED") {
            return GeminiResult.Failure(GenerationError.PROVIDER_BLOCKED, usage)
        }
        val candidates = root["candidates"] as? JsonArray ?: fail(GenerationError.INVALID_RESPONSE)
        if (candidates.size != 1) fail(GenerationError.INVALID_RESPONSE)
        val candidate = candidates.single() as? JsonObject ?: fail(GenerationError.INVALID_RESPONSE)
        when (string(candidate, "finishReason")) {
            "STOP" -> Unit
            "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT", "IMAGE_RECITATION" ->
                return GeminiResult.Failure(GenerationError.PROVIDER_BLOCKED, usage)
            "MAX_TOKENS", "CONTINUATION", "NO_IMAGE" -> return GeminiResult.Failure(GenerationError.INCOMPLETE_RESPONSE, usage)
            else -> return GeminiResult.Failure(GenerationError.INVALID_RESPONSE, usage)
        }
        val ratings = candidate["safetyRatings"] as? JsonArray
        if (ratings?.any { (it as? JsonObject)?.get("blocked") == JsonPrimitive(true) } == true) {
            return GeminiResult.Failure(GenerationError.PROVIDER_BLOCKED, usage)
        }
        val content = candidate["content"] as? JsonObject ?: fail(GenerationError.INVALID_RESPONSE)
        val parts = content["parts"] as? JsonArray ?: fail(GenerationError.INVALID_RESPONSE)
        if (parts.isEmpty() || parts.size > 100) fail(GenerationError.INVALID_RESPONSE)
        val text = StringBuilder()
        var image: GeneratedImage? = null
        for (item in parts) {
            val part = item as? JsonObject ?: fail(GenerationError.INVALID_RESPONSE)
            if (part.keys.any { it !in setOf("text", "inlineData", "thought", "thoughtSignature", "partMetadata") }) {
                fail(GenerationError.INVALID_RESPONSE)
            }
            if (part["thought"] != null && part["thought"] !in listOf(JsonPrimitive(true), JsonPrimitive(false))) fail(GenerationError.INVALID_RESPONSE)
            if (part["thought"] == JsonPrimitive(true)) continue
            if ((part["text"] != null) == (part["inlineData"] != null)) fail(GenerationError.INVALID_RESPONSE)
            part["text"]?.let {
                val value = it as? JsonPrimitive ?: fail(GenerationError.INVALID_RESPONSE)
                if (!value.isString) fail(GenerationError.INVALID_RESPONSE)
                text.append(value.content)
            }
            part["inlineData"]?.let {
                if (request.purpose != GenerationPurpose.IMAGE || image != null) fail(GenerationError.INVALID_RESPONSE)
                val blob = it as? JsonObject ?: fail(GenerationError.INVALID_RESPONSE)
                val mime = string(blob, "mimeType") ?: fail(GenerationError.INVALID_RESPONSE)
                if (mime !in setOf("image/png", "image/jpeg")) fail(GenerationError.INVALID_RESPONSE)
                val encoded = string(blob, "data") ?: fail(GenerationError.INVALID_RESPONSE)
                if (encoded.length.toLong() > ((request.limits.maxImageBytes.toLong() + 2) / 3) * 4) fail(GenerationError.LIMIT_EXCEEDED)
                val decoded = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { fail(GenerationError.INVALID_RESPONSE) }
                if (decoded.size > request.limits.maxImageBytes) fail(GenerationError.LIMIT_EXCEEDED)
                val (width, height) = images.verify(decoded, mime, request.limits)
                if (width <= 0 || height <= 0 || width.toLong() * height > request.limits.maxImagePixels) fail(GenerationError.LIMIT_EXCEEDED)
                image = GeneratedImage(decoded, mime, width, height)
            }
        }
        val modelVersion = string(root, "modelVersion")?.takeIf { it.matches(Regex("gemini-[a-zA-Z0-9._-]{1,128}")) }
        return if (request.purpose == GenerationPurpose.IMAGE) {
            GeminiResult.ImageDraft(image ?: fail(GenerationError.INCOMPLETE_RESPONSE), text.toString(), usage, modelVersion)
        } else {
            if (text.isBlank()) fail(GenerationError.INVALID_RESPONSE)
            GeminiResult.TextDraft(text.toString(), usage, modelVersion)
        }
    }

    private fun usage(value: JsonObject): GenerationUsage = GenerationUsage(
        promptTokens = number(value, "promptTokenCount"), outputTokens = number(value, "candidatesTokenCount"),
        thinkingTokens = number(value, "thoughtsTokenCount"), cachedTokens = number(value, "cachedContentTokenCount"),
        toolPromptTokens = number(value, "toolUsePromptTokenCount"), totalTokens = number(value, "totalTokenCount"),
        serviceTier = string(value, "serviceTier")?.let { if (it in setOf("STANDARD", "FLEX", "PRIORITY", "SERVICE_TIER_UNSPECIFIED")) it else "UNKNOWN" },
        modalityDetails = modalityDetails(value),
    )

    private fun modalityDetails(value: JsonObject): Map<String, List<ModalityTokenCount>> {
        val result = linkedMapOf<String, List<ModalityTokenCount>>()
        for (key in listOf("promptTokensDetails", "cacheTokensDetails", "candidatesTokensDetails", "toolUsePromptTokensDetails")) {
            val raw = value[key] ?: continue
            val array = raw as? JsonArray ?: fail(GenerationError.INVALID_RESPONSE)
            if (array.size > 16) fail(GenerationError.INVALID_RESPONSE)
            result[key] = java.util.Collections.unmodifiableList(array.map {
                val item = it as? JsonObject ?: fail(GenerationError.INVALID_RESPONSE)
                val modality = string(item, "modality") ?: fail(GenerationError.INVALID_RESPONSE)
                val safeModality = if (modality in setOf("TEXT", "IMAGE", "AUDIO", "VIDEO", "DOCUMENT", "MODALITY_UNSPECIFIED")) modality else "UNKNOWN"
                ModalityTokenCount(safeModality, number(item, "tokenCount") ?: fail(GenerationError.INVALID_RESPONSE))
            })
        }
        return java.util.Collections.unmodifiableMap(result)
    }

    internal fun number(value: JsonObject, key: String): Long? {
        val raw = value[key] ?: return null
        val primitive = raw as? JsonPrimitive ?: fail(GenerationError.INVALID_RESPONSE)
        if (primitive.isString) fail(GenerationError.INVALID_RESPONSE)
        return primitive.content.toLongOrNull()?.takeIf { it >= 0 } ?: fail(GenerationError.INVALID_RESPONSE)
    }

    internal fun string(value: JsonObject, key: String): String? {
        val raw = value[key] ?: return null
        val primitive = raw as? JsonPrimitive ?: fail(GenerationError.INVALID_RESPONSE)
        if (!primitive.isString) fail(GenerationError.INVALID_RESPONSE)
        return primitive.content
    }

    internal fun fail(code: GenerationError): Nothing = throw GenerationException(code)
}

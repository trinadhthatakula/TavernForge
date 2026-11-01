package com.valhalla.tavernforge.core.model.generation

import java.security.MessageDigest
import java.util.UUID

data class GenerationGrants(
    val text: Boolean = false,
    val image: Boolean = false,
    val automaticFollowups: Boolean = false,
)

/** A revision is supplied for existing projects; new drafts have no project ID. */
data class GenerationTarget(
    val projectId: String? = null,
    val revision: Long? = null,
    val label: String = "New draft",
) {
    init {
        require((projectId == null) == (revision == null))
        require(revision == null || revision >= 0)
        require(label.length <= 256)
    }

    override fun toString(): String = "GenerationTarget(redacted)"
}

enum class GenerationPurpose { MODEL_LIST, TEXT, IMAGE, COUNT_TEXT, COUNT_IMAGE }

enum class GenerationError {
    KEY_UNAVAILABLE, KEY_CHANGED, INVALID_KEY_INPUT, KEY_STORAGE_FAILURE,
    PERMISSION_REQUIRED, STALE_PREVIEW, APPROVAL_EXPIRED, APPROVAL_USED,
    MODEL_UNAVAILABLE, UNSUPPORTED_CAPABILITY, INVALID_INPUT, LIMIT_EXCEEDED,
    AUTH_REJECTED, QUOTA_OR_RATE_LIMIT, REQUEST_REJECTED, PROVIDER_BLOCKED,
    INCOMPLETE_RESPONSE, INVALID_RESPONSE, TRANSPORT_FAILURE, CANCELLED, OUTCOME_UNKNOWN,
}

/** Safe code only: provider exceptions, response bodies and credentials are never causes. */
class GenerationException(val code: GenerationError) : Exception(code.name)

data class GenerationLimits(
    val maxInputBytes: Int = 256 * 1024,
    val maxResponseBytes: Int = 2 * 1024 * 1024,
    val maxOutputTokens: Int = 1024,
    val maxImageBytes: Int = 16 * 1024 * 1024,
    val maxImagePixels: Long = 16_000_000,
) {
    init {
        require(maxInputBytes in 1..256 * 1024)
        require(maxResponseBytes in 1..24 * 1024 * 1024)
        require(maxOutputTokens in 1..32_768)
        require(maxImageBytes in 1..16 * 1024 * 1024)
        require(maxImagePixels in 1..16_000_000)
    }
}

/** Owned bytes: editing the caller's byte array or a returned copy cannot change approval. */
class PreparedRequest internal constructor(
    internal val owner: String,
    val purpose: GenerationPurpose,
    val model: String?,
    val target: GenerationTarget,
    val credentialGeneration: String,
    val grantGeneration: Long,
    val path: String,
    val method: String,
    bytes: ByteArray,
    val limits: GenerationLimits,
    val automaticFollowup: Boolean,
) {
    internal var admitted: Boolean = false
    internal var discarded: Boolean = false
    val id: String = UUID.randomUUID().toString()
    val provider: String = "Google Gemini"
    val origin: String = "https://generativelanguage.googleapis.com"
    val adapterVersion: String = "generate-content-v1beta-20261030-v1"
    val callCount: Int = 1
    private val body = bytes.copyOf()
    val bodySha256: String = MessageDigest.getInstance("SHA-256").digest(body)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val bodyPreview: String get() = body.toString(Charsets.UTF_8)
    fun bodyBytes(): ByteArray = body.copyOf()
    override fun toString(): String = "PreparedRequest(redacted)"
}

class RequestApproval internal constructor(
    internal val owner: String,
    internal val requestId: String,
    internal val bodySha256: String,
    internal val expiresAt: Long,
) {
    internal var consumed: Boolean = false
    override fun toString(): String = "RequestApproval(redacted)"
}

/** Nullable values mean not supplied, never zero. Counts are provider observations, not cost. */
data class GenerationUsage(
    val promptTokens: Long? = null,
    val outputTokens: Long? = null,
    val thinkingTokens: Long? = null,
    val cachedTokens: Long? = null,
    val toolPromptTokens: Long? = null,
    val totalTokens: Long? = null,
    val inputCountOnly: Boolean = false,
    val serviceTier: String? = null,
    val modalityDetails: Map<String, List<ModalityTokenCount>> = emptyMap(),
)

data class ModalityTokenCount(val modality: String, val tokenCount: Long)

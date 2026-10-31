package com.valhalla.tavernforge.core.model.generation

import java.util.UUID

/** In-memory policy. No approval, prompt, or pending send survives process death. */
class GenerationPolicy(private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val owner = UUID.randomUUID().toString()
    private var permissions = GenerationGrants()
    private var generation = 0L

    @Synchronized fun grants(): GenerationGrants = permissions

    @Synchronized fun updateGrants(value: GenerationGrants) {
        if (permissions != value) {
            permissions = value
            generation++
        }
    }

    @Synchronized fun prepare(
        purpose: GenerationPurpose,
        model: String?,
        target: GenerationTarget,
        credentialGeneration: String,
        path: String,
        method: String,
        bytes: ByteArray,
        limits: GenerationLimits = GenerationLimits(),
        automaticFollowup: Boolean = false,
    ): PreparedRequest {
        if (credentialGeneration.isBlank()) fail(GenerationError.KEY_UNAVAILABLE)
        if (bytes.size > limits.maxInputBytes) fail(GenerationError.LIMIT_EXCEEDED)
        checkPermission(purpose, automaticFollowup)
        return PreparedRequest(owner, purpose, model, target, credentialGeneration, generation, path, method,
            bytes, limits, automaticFollowup)
    }

    /** Call only from the explicit review action, never as part of preparing a preview. */
    @Synchronized fun approve(request: PreparedRequest): RequestApproval {
        if (request.owner != owner) fail(GenerationError.STALE_PREVIEW)
        if (request.admitted) fail(GenerationError.APPROVAL_USED)
        checkCurrent(request)
        return RequestApproval(owner, request.id, request.bodySha256, clockMillis() + 300_000)
    }

    /** Linearization point for dispatch. One admitted attempt consumes its approval permanently. */
    @Synchronized fun admit(
        request: PreparedRequest,
        approval: RequestApproval,
        credentialGeneration: String?,
        currentTarget: GenerationTarget,
    ) {
        if (request.owner != owner || approval.owner != owner || approval.requestId != request.id ||
            approval.bodySha256 != request.bodySha256) fail(GenerationError.STALE_PREVIEW)
        if (approval.consumed || request.admitted) fail(GenerationError.APPROVAL_USED)
        if (clockMillis() >= approval.expiresAt) fail(GenerationError.APPROVAL_EXPIRED)
        if (credentialGeneration == null) fail(GenerationError.KEY_UNAVAILABLE)
        if (credentialGeneration != request.credentialGeneration) fail(GenerationError.KEY_CHANGED)
        if (currentTarget != request.target) fail(GenerationError.STALE_PREVIEW)
        checkCurrent(request)
        approval.consumed = true
        request.admitted = true
    }

    @Synchronized fun isCurrent(request: PreparedRequest): Boolean =
        request.grantGeneration == generation && permitted(request.purpose, request.automaticFollowup)

    private fun checkCurrent(request: PreparedRequest) {
        if (request.grantGeneration != generation) fail(GenerationError.STALE_PREVIEW)
        checkPermission(request.purpose, request.automaticFollowup)
    }

    private fun checkPermission(purpose: GenerationPurpose, followup: Boolean) {
        if (!permitted(purpose, followup)) fail(GenerationError.PERMISSION_REQUIRED)
    }

    private fun permitted(purpose: GenerationPurpose, followup: Boolean): Boolean =
        (!followup || permissions.automaticFollowups) && when (purpose) {
            GenerationPurpose.MODEL_LIST -> true
            GenerationPurpose.TEXT, GenerationPurpose.COUNT_TEXT -> permissions.text
            GenerationPurpose.IMAGE, GenerationPurpose.COUNT_IMAGE -> permissions.image
        }

    private fun fail(code: GenerationError): Nothing = throw GenerationException(code)
}

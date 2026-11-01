package com.valhalla.tavernforge.core.model.generation

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class GenerationPolicyTest {
    private val target = GenerationTarget("project", 4, "Field draft")

    @Test fun allEightGrantCombinationsAreIndependentIncludingMetadata() {
        for (mask in 0..7) {
            val policy = GenerationPolicy()
            val grants = GenerationGrants(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0)
            policy.updateGrants(grants)
            for (purpose in GenerationPurpose.entries) for (followup in listOf(false, true)) {
                val allowed = (!followup || grants.automaticFollowups) && when (purpose) {
                    GenerationPurpose.MODEL_LIST -> true
                    GenerationPurpose.TEXT, GenerationPurpose.COUNT_TEXT -> grants.text
                    GenerationPurpose.IMAGE, GenerationPurpose.COUNT_IMAGE -> grants.image
                }
                val result = runCatching { request(policy, purpose, followup = followup) }
                assertEquals("mask=$mask purpose=$purpose followup=$followup", allowed, result.isSuccess)
                if (!allowed) assertEquals(GenerationError.PERMISSION_REQUIRED, (result.exceptionOrNull() as GenerationException).code)
            }
        }
    }

    @Test fun approvalsAreBoundToExactRequestAndPolicy() {
        val policy = enabled()
        val first = request(policy)
        val other = request(policy)
        val approval = policy.approve(first)
        error(GenerationError.STALE_PREVIEW) { policy.admit(other, approval, "key-1", target) }
        error(GenerationError.STALE_PREVIEW) { enabled().admit(first, approval, "key-1", target) }
        policy.admit(first, approval, "key-1", target)
    }

    @Test fun samePreparedRequestCannotDispatchTwiceEvenWithTwoApprovals() {
        val policy = enabled(); val prepared = request(policy)
        val first = policy.approve(prepared); val second = policy.approve(prepared)
        policy.admit(prepared, first, "key-1", target)
        error(GenerationError.APPROVAL_USED) { policy.admit(prepared, second, "key-1", target) }
        error(GenerationError.APPROVAL_USED) { policy.approve(prepared) }
    }

    @Test fun concurrentAdmissionHasExactlyOneWinner() {
        val policy = enabled(); val prepared = request(policy)
        val approvals = (1..16).map { policy.approve(prepared) }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll(approvals.map { approval -> Callable {
                runCatching { policy.admit(prepared, approval, "key-1", target) }.isSuccess
            } })
            assertEquals(1, results.count { it.get() })
        } finally { pool.shutdownNow() }
    }

    @Test fun discardedPreviewInvalidatesExistingApprovalAndCannotBeReapproved() {
        val policy = enabled(); val prepared = request(policy); val approval = policy.approve(prepared)
        policy.discard(prepared)
        assertFalse(policy.isCurrent(prepared))
        error(GenerationError.STALE_PREVIEW) { policy.approve(prepared) }
        error(GenerationError.STALE_PREVIEW) { policy.admit(prepared, approval, "key-1", target) }
    }

    @Test fun keyRevisionAndGrantChangesInvalidateApproval() {
        val policy = enabled(); val prepared = request(policy); val approval = policy.approve(prepared)
        error(GenerationError.KEY_CHANGED) { policy.admit(prepared, approval, "key-2", target) }
        error(GenerationError.KEY_UNAVAILABLE) { policy.admit(prepared, approval, null, target) }
        error(GenerationError.STALE_PREVIEW) { policy.admit(prepared, approval, "key-1", target.copy(revision = 5)) }
        policy.updateGrants(GenerationGrants())
        policy.updateGrants(GenerationGrants(text = true, image = true))
        error(GenerationError.STALE_PREVIEW) { policy.admit(prepared, approval, "key-1", target) }
    }

    @Test fun approvalsExpireWithoutBeingRestoredByClockBoundary() {
        var now = 0L
        val policy = GenerationPolicy { now }.apply { updateGrants(GenerationGrants(text = true)) }
        val prepared = request(policy); val approval = policy.approve(prepared)
        now = 300_000
        error(GenerationError.APPROVAL_EXPIRED) { policy.admit(prepared, approval, "key-1", target) }
    }

    @Test fun bodyOwnershipPreservesExactlyReviewedBytes() {
        val body = "{\"selected\":\"original\"}".toByteArray()
        val prepared = request(enabled(), body = body)
        val hash = prepared.bodySha256
        body.fill(0); prepared.bodyBytes().fill(0)
        assertEquals("{\"selected\":\"original\"}", prepared.bodyPreview)
        assertEquals(hash, prepared.bodySha256)
        assertFalse(prepared.toString().contains("original"))
    }

    @Test fun defaultsAndOversizedBodyNeverAdmitGeneration() {
        assertEquals(GenerationGrants(), GenerationPolicy().grants())
        error(GenerationError.PERMISSION_REQUIRED) { request(GenerationPolicy()) }
        error(GenerationError.LIMIT_EXCEEDED) { request(enabled(), body = ByteArray(256 * 1024 + 1)) }
    }

    private fun enabled() = GenerationPolicy().apply { updateGrants(GenerationGrants(text = true, image = true)) }
    private fun request(policy: GenerationPolicy, purpose: GenerationPurpose = GenerationPurpose.TEXT,
        followup: Boolean = false, body: ByteArray = "{}".toByteArray()) = policy.prepare(
        purpose, "models/synthetic", target, "key-1", "/v1beta/models/synthetic:generateContent", "POST", body,
        automaticFollowup = followup)

    private fun error(code: GenerationError, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (e: GenerationException) { assertEquals(code, e.code) }
    }
}

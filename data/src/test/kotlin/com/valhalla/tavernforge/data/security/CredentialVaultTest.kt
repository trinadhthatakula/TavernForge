package com.valhalla.tavernforge.data.security

import com.valhalla.tavernforge.core.model.generation.GenerationError
import com.valhalla.tavernforge.core.model.generation.GenerationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Visibly invalid synthetic input only. Never a real or syntactically plausible Google key. */
class CredentialVaultTest {
    private val sentinel = "invalid-test-only-not-a-google-credential"

    @Test fun encryptedEnvelopeSurvivesNewVaultAndNeverContainsPlaintext() = runBlocking {
        val cipher = TestCipher(); val files = MemoryFiles(); val vault = CredentialVault(cipher, files)
        val input = sentinel.toCharArray(); vault.update(input)
        assertTrue(input.all { it == '\u0000' })
        assertFalse(files.bytes!!.toString(Charsets.UTF_8).contains(sentinel))
        val generation = vault.state.value.generation!!
        val restored = CredentialVault(cipher, files); restored.refresh()
        assertEquals(generation, restored.state.value.generation)
        var held: CharArray? = null
        restored.withCredential(generation) { clear -> assertEquals(sentinel, String(clear)); held = clear }
        assertTrue(held!!.all { it == '\u0000' })
    }

    @Test fun updateRotatesGenerationAndDeletesOnlySupersededKeys() = runBlocking {
        val cipher = TestCipher(); val vault = CredentialVault(cipher, MemoryFiles())
        vault.update(sentinel.toCharArray()); val first = vault.state.value.generation!!
        vault.update("invalid-test-only-replacement".toCharArray()); val second = vault.state.value.generation!!
        assertNotEquals(first, second); assertEquals(setOf(second), cipher.ids())
        expect(GenerationError.KEY_CHANGED) { vault.withCredential(first) { fail("Must not decrypt stale key") } }
    }

    @Test fun distinctWritesUseDistinctIvAndCiphertext() = runBlocking {
        val cipher = TestCipher(); val files = MemoryFiles(); val vault = CredentialVault(cipher, files)
        vault.update(sentinel.toCharArray()); val first = files.bytes!!.copyOf()
        vault.update(sentinel.toCharArray())
        assertFalse(first.contentEquals(files.bytes!!))
        assertFalse(cipher.ivs[0].contentEquals(cipher.ivs[1]))
    }

    @Test fun tamperOrMissingAliasMakesCredentialUnavailable() = runBlocking {
        for (missingAlias in listOf(false, true)) {
            val cipher = TestCipher(); val files = MemoryFiles(); val vault = CredentialVault(cipher, files)
            vault.update(sentinel.toCharArray())
            val id = vault.state.value.generation!!
            if (missingAlias) cipher.delete(id) else files.bytes!![files.bytes!!.lastIndex] = (files.bytes!!.last().toInt() xor 1).toByte()
            val restored = CredentialVault(cipher, files); restored.refresh()
            assertEquals(CredentialAvailability.UNAVAILABLE, restored.state.value.availability)
            expect(GenerationError.KEY_UNAVAILABLE) { restored.withCredential(id) { fail("Must not expose corrupted credential") } }
        }
    }

    @Test fun deletionTombstoneRemainsSafeAfterCleanupInterruption() = runBlocking {
        val cipher = TestCipher(); val files = MemoryFiles(); val vault = CredentialVault(cipher, files)
        vault.update(sentinel.toCharArray()); cipher.failDelete = true
        expect(GenerationError.KEY_STORAGE_FAILURE) { vault.delete() }
        assertFalse(vault.state.value.available)
        cipher.failDelete = false
        val restored = CredentialVault(cipher, files); restored.refresh()
        assertEquals(CredentialAvailability.ABSENT, restored.state.value.availability)
        assertTrue(cipher.ids().isEmpty())
    }

    @Test fun failedCommitRetainsAliasesUntilRecoveryAndNeverClaimsSuccess() = runBlocking {
        val cipher = TestCipher(); val files = MemoryFiles(); val vault = CredentialVault(cipher, files)
        vault.update(sentinel.toCharArray()); val original = files.bytes!!.copyOf()
        files.failWrite = true
        expect(GenerationError.KEY_STORAGE_FAILURE) { vault.update("invalid-replacement-test".toCharArray()) }
        assertFalse(vault.state.value.available); assertArrayEquals(original, files.bytes)
        files.failWrite = false; vault.refresh()
        assertTrue(vault.state.value.available)
        vault.withCredential(vault.state.value.generation!!) { assertEquals(sentinel, String(it)) }
    }

    @Test fun invalidInputIsClearedAndCannotChangeTheActiveCredential() = runBlocking {
        val vault = CredentialVault(TestCipher(), MemoryFiles()); vault.update(sentinel.toCharArray())
        val original = vault.state.value
        for (value in listOf("", "has\nnewline", "contains space", "x".repeat(4097))) {
            val input = value.toCharArray()
            expect(GenerationError.INVALID_KEY_INPUT) { vault.update(input) }
            assertTrue(input.all { it == '\u0000' }); assertEquals(original, vault.state.value)
        }
    }

    @Test fun actionFailureStillClearsDecryptedBuffer() = runBlocking {
        val vault = CredentialVault(TestCipher(), MemoryFiles()); vault.update(sentinel.toCharArray())
        var held: CharArray? = null
        try {
            vault.withCredential(vault.state.value.generation!!) { held = it; throw IOException("synthetic failure") }
            fail("Expected action failure")
        } catch (_: IOException) { }
        assertTrue(held!!.all { it == '\u0000' })
    }

    @Test fun updateAndDeleteSynchronouslyRevokeAWaitingCredentialAction() = runBlocking {
        for (delete in listOf(false, true)) {
            val vault = CredentialVault(TestCipher(), MemoryFiles()); vault.update(sentinel.toCharArray())
            val entered = CompletableDeferred<Unit>()
            var held: CharArray? = null
            val execution = async {
                runCatching {
                    vault.withCredential(vault.state.value.generation!!) {
                        held = it; entered.complete(Unit); awaitCancellation()
                    }
                }
            }
            withTimeout(5000) { entered.await() }
            withTimeout(5000) {
                if (delete) vault.delete() else vault.update("invalid-new-test-value".toCharArray())
                assertTrue(execution.await().isFailure)
            }
            assertTrue(held!!.all { it == '\u0000' })
            assertEquals(!delete, vault.state.value.available)
        }
    }

    private suspend fun expect(code: GenerationError, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (e: GenerationException) { assertEquals(code, e.code); assertNull(e.cause) }
    }

    private class MemoryFiles : CredentialFiles {
        var bytes: ByteArray? = null
        var failWrite = false
        override fun read() = bytes?.copyOf()
        override fun replace(bytes: ByteArray) { if (failWrite) throw IOException("test-only"); this.bytes = bytes.copyOf() }
    }

    private class TestCipher : CredentialCipher {
        private val keys = mutableMapOf<String, SecretKey>()
        val ivs = mutableListOf<ByteArray>()
        var failDelete = false
        override fun generate(id: String) { keys[id] = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        override fun encrypt(id: String, clear: ByteArray, aad: ByteArray): EncryptedCredential {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keys.getValue(id)); updateAAD(aad) }
            return EncryptedCredential(cipher.iv.copyOf().also { ivs.add(it) }, cipher.doFinal(clear))
        }
        override fun decrypt(id: String, encrypted: EncryptedCredential, aad: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, keys.getValue(id), GCMParameterSpec(128, encrypted.iv)); updateAAD(aad); doFinal(encrypted.ciphertext) }
        override fun delete(id: String) { if (failDelete) throw IOException("test-only"); keys.remove(id) }
        override fun ids() = keys.keys.toSet()
    }
}

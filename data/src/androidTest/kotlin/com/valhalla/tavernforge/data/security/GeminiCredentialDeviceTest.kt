package com.valhalla.tavernforge.data.security

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.tavernforge.core.model.generation.GenerationError
import com.valhalla.tavernforge.core.model.generation.GenerationException
import com.valhalla.tavernforge.core.model.generation.GenerationLimits
import com.valhalla.tavernforge.data.network.gemini.AndroidGeneratedImageVerifier
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Uses a unique test-only Keystore namespace and visibly invalid synthetic credential text. */
@RunWith(AndroidJUnit4::class)
class GeminiCredentialDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val sentinel = "invalid-instrumentation-only-not-a-google-key"

    @Test fun encryptReloadRotateAndDeleteUseAndroidKeystore() = runBlocking {
        isolated { namespace, vault ->
            vault.update(sentinel.toCharArray()); val first = vault.state.value.generation!!
            val restored = AndroidGeminiCredentials(context, namespace); restored.refresh()
            restored.withCredential(first) { assertEquals(sentinel, String(it)) }
            restored.update("invalid-instrumentation-replacement".toCharArray())
            assertNotEquals(first, restored.state.value.generation)
            assertEquals(1, AndroidCredentialCipher(prefix(namespace)).ids().size)
            restored.delete()
            val afterDelete = AndroidGeminiCredentials(context, namespace); afterDelete.refresh()
            assertFalse(afterDelete.state.value.available)
            assertTrue(AndroidCredentialCipher(prefix(namespace)).ids().isEmpty())
        }
    }

    @Test fun envelopeIsOnlyInNoBackupAndContainsNoPlaintext() = runBlocking {
        isolated { namespace, vault ->
            vault.update(sentinel.toCharArray())
            val directory = directory(namespace)
            assertEquals(context.noBackupFilesDir.canonicalFile, directory.parentFile!!.canonicalFile)
            val bytes = File(directory, "credential.bin").readBytes()
            assertFalse(bytes.toString(Charsets.UTF_8).contains(sentinel))
            assertFalse(File(context.filesDir, "gemini-credentials-$namespace").exists())
        }
    }

    @Test fun ciphertextTamperingAndMissingAliasAreUnavailableNotRecreated() = runBlocking {
        for (missing in listOf(false, true)) isolated { namespace, vault ->
            vault.update(sentinel.toCharArray())
            if (missing) AndroidCredentialCipher(prefix(namespace)).delete(vault.state.value.generation!!)
            else {
                val file = File(directory(namespace), "credential.bin")
                val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            }
            val restored = AndroidGeminiCredentials(context, namespace); restored.refresh()
            assertEquals(CredentialAvailability.UNAVAILABLE, restored.state.value.availability)
        }
    }

    @Test fun revokedLeaseIsCancelledAndItsMutableBufferIsWipedBeforeMutationCompletes() = runBlocking {
        isolated { _, vault ->
            vault.update(sentinel.toCharArray())
            val entered = CompletableDeferred<Unit>(); var buffer: CharArray? = null
            val task = async {
                runCatching { vault.withCredential(vault.state.value.generation!!) { buffer = it; entered.complete(Unit); awaitCancellation() } }
            }
            withTimeout(10_000) { entered.await() }
            withTimeout(10_000) { vault.delete(); assertTrue(task.await().isFailure) }
            assertTrue(buffer!!.all { it == '\u0000' })
        }
    }

    @Test fun realDecoderVerifiesSyntheticImageAndRejectsMismatchedMimeOrPixelBudget() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        try { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) } finally { bitmap.recycle() }
        val bytes = output.toByteArray(); val verifier = AndroidGeneratedImageVerifier()
        assertEquals(2 to 2, verifier.verify(bytes, "image/png", GenerationLimits()))
        try { verifier.verify(bytes, "image/jpeg", GenerationLimits()); fail("MIME mismatch") }
        catch (e: GenerationException) { assertEquals(GenerationError.INVALID_RESPONSE, e.code) }
        try { verifier.verify(bytes, "image/png", GenerationLimits(maxImagePixels = 1)); fail("Pixel limit") }
        catch (e: GenerationException) { assertEquals(GenerationError.LIMIT_EXCEEDED, e.code) }
    }

    private suspend fun isolated(action: suspend (String, AndroidGeminiCredentials) -> Unit) {
        val namespace = "instrumentation-${UUID.randomUUID()}"
        val vault = AndroidGeminiCredentials(context, namespace)
        try { action(namespace, vault) }
        finally { vault.delete(); directory(namespace).deleteRecursively() }
    }

    private fun directory(namespace: String) = File(context.noBackupFilesDir, "gemini-credentials-$namespace")
    private fun prefix(namespace: String) = "com.valhalla.tavernforge.gemini.$namespace."
}

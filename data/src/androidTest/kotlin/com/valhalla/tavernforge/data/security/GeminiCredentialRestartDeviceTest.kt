package com.valhalla.tavernforge.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/** Run each method in a separate instrumentation invocation to prove process restart persistence. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class GeminiCredentialRestartDeviceTest {
    private val namespace = "instrumentation-restart-only"
    private val sentinel = "invalid-restart-test-only-not-a-google-key"
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun phase1StoreSyntheticCredential() = runBlocking {
        val vault = AndroidGeminiCredentials(context, namespace)
        vault.delete()
        vault.update(sentinel.toCharArray())
        assertTrue(vault.state.value.available)
    }

    @Test fun phase2VerifyAndRemoveSyntheticCredential() = runBlocking {
        val vault = AndroidGeminiCredentials(context, namespace)
        try {
            vault.refresh(); assertTrue(vault.state.value.available)
            vault.withCredential(vault.state.value.generation!!) { assertEquals(sentinel, String(it)) }
        } finally {
            vault.delete()
            File(context.noBackupFilesDir, "gemini-credentials-$namespace").deleteRecursively()
        }
    }
}

package com.valhalla.tavernforge.data.security

import com.valhalla.tavernforge.core.model.generation.GenerationError
import com.valhalla.tavernforge.core.model.generation.GenerationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

enum class CredentialAvailability { ABSENT, AVAILABLE, UNAVAILABLE, DELETING }
data class CredentialSnapshot(
    val availability: CredentialAvailability = CredentialAvailability.ABSENT,
    val generation: String? = null,
) {
    val available: Boolean get() = availability == CredentialAvailability.AVAILABLE && generation != null
}

interface GeminiCredentials {
    val state: StateFlow<CredentialSnapshot>
    suspend fun refresh()
    /** The supplied mutable buffer is cleared, including on failure. No network request occurs. */
    suspend fun update(value: CharArray)
    suspend fun delete()
    /** Transport boundary only. Never expose this value to view state or a prompt. */
    suspend fun <T> withCredential(expectedGeneration: String, action: suspend (CharArray) -> T): T
}

internal data class EncryptedCredential(val iv: ByteArray, val ciphertext: ByteArray)
internal interface CredentialCipher {
    fun generate(id: String)
    fun encrypt(id: String, clear: ByteArray, aad: ByteArray): EncryptedCredential
    fun decrypt(id: String, encrypted: EncryptedCredential, aad: ByteArray): ByteArray
    fun delete(id: String)
    fun ids(): Set<String>
}

internal interface CredentialFiles {
    fun read(): ByteArray?
    fun replace(bytes: ByteArray)
}

/** Binary, authenticated, versioned envelope. No JSON logging or plaintext persistence. */
internal class CredentialVault(
    private val cipher: CredentialCipher,
    private val files: CredentialFiles,
) : GeminiCredentials {
    private val mutableState = MutableStateFlow(CredentialSnapshot())
    override val state: StateFlow<CredentialSnapshot> = mutableState.asStateFlow()
    private val lock = Mutex()
    private val admission = Any()
    private val leases = mutableSetOf<Job>()
    private var mutationsInProgress = 0

    override suspend fun refresh() = mutate {
            try {
                val record = readRecord()
                if (record == null) {
                    mutableState.value = CredentialSnapshot()
                    cleanup(null)
                } else {
                    val clear = cipher.decrypt(record.id, record.encrypted, aad(record.id))
                    try { validateBytes(clear) } finally { clear.fill(0) }
                    mutableState.value = CredentialSnapshot(CredentialAvailability.AVAILABLE, record.id)
                    cleanup(record.id)
                }
            } catch (_: Exception) {
                mutableState.value = CredentialSnapshot(CredentialAvailability.UNAVAILABLE)
            }
    }

    override suspend fun update(value: CharArray) {
        try {
            if (value.isEmpty() || value.size > MAX_KEY_BYTES || value.any { it.code !in 33..126 }) {
                throw GenerationException(GenerationError.INVALID_KEY_INPUT)
            }
            val clear = ByteArray(value.size) { value[it].code.toByte() }
            try {
                mutate {
                        val id = UUID.randomUUID().toString()
                        var attemptedCommit = false
                        try {
                            cipher.generate(id)
                            val encrypted = cipher.encrypt(id, clear, aad(id))
                            val check = cipher.decrypt(id, encrypted, aad(id))
                            try {
                                if (!java.security.MessageDigest.isEqual(clear, check)) error("Encryption verification failed")
                            } finally { check.fill(0) }
                            val encoded = encode(Record(id, encrypted))
                            attemptedCommit = true
                            files.replace(encoded)
                            mutableState.value = CredentialSnapshot(CredentialAvailability.AVAILABLE, id)
                            // Cleanup is safe to retry after restart. It does not roll back an active key.
                            runCatching { cleanup(id) }
                        } catch (_: Exception) {
                            if (attemptedCommit) {
                                // A rename/fsync failure can have an ambiguous commit. Keep both aliases.
                                mutableState.value = CredentialSnapshot(CredentialAvailability.UNAVAILABLE)
                            } else runCatching { cipher.delete(id) }
                            throw GenerationException(GenerationError.KEY_STORAGE_FAILURE)
                        }
                }
            } finally { clear.fill(0) }
        } finally { value.fill('\u0000') }
    }

    override suspend fun delete() = mutate {
            mutableState.value = CredentialSnapshot(CredentialAvailability.DELETING)
            try {
                // A durable tombstone prevents an interrupted alias cleanup resurrecting credentials.
                files.replace(byteArrayOf(0))
                cleanup(null)
                mutableState.value = CredentialSnapshot()
            } catch (_: Exception) {
                // Also attempt revocation of all owned aliases if the filesystem is failing.
                runCatching { cleanup(null) }
                mutableState.value = CredentialSnapshot(CredentialAvailability.UNAVAILABLE)
                throw GenerationException(GenerationError.KEY_STORAGE_FAILURE)
            }
    }

    override suspend fun <T> withCredential(expectedGeneration: String, action: suspend (CharArray) -> T): T =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val job = currentCoroutineContext().job
                synchronized(admission) {
                    if (mutationsInProgress != 0 || !state.value.available) throw GenerationException(GenerationError.KEY_UNAVAILABLE)
                    if (state.value.generation != expectedGeneration) throw GenerationException(GenerationError.KEY_CHANGED)
                    leases.add(job)
                }
                var clear: ByteArray? = null
                var chars: CharArray? = null
                try {
                    val decrypted = try {
                        val record = readRecord() ?: throw GenerationException(GenerationError.KEY_UNAVAILABLE)
                        if (record.id != expectedGeneration) throw GenerationException(GenerationError.KEY_CHANGED)
                        cipher.decrypt(record.id, record.encrypted, aad(record.id)).also {
                            clear = it
                            validateBytes(it)
                        }
                    } catch (e: GenerationException) {
                        if (e.code == GenerationError.KEY_UNAVAILABLE) mutableState.value = CredentialSnapshot(CredentialAvailability.UNAVAILABLE)
                        throw e
                    }
                    catch (_: Exception) {
                        mutableState.value = CredentialSnapshot(CredentialAvailability.UNAVAILABLE)
                        throw GenerationException(GenerationError.KEY_UNAVAILABLE)
                    }
                    val credentialChars = CharArray(decrypted.size) { (decrypted[it].toInt() and 255).toChar() }
                    chars = credentialChars
                    decrypted.fill(0)
                    currentCoroutineContext().ensureActive()
                    // Keep the mutex until this cancellable action ends: mutation waits for wiping.
                    action(credentialChars)
                } finally {
                    chars?.fill('\u0000')
                    clear?.fill(0)
                    synchronized(admission) { leases.remove(job) }
                }
            }
        }

    /** Revocation is synchronous and shared with lease admission, not a StateFlow side effect. */
    private suspend fun <T> mutate(action: suspend () -> T): T {
        synchronized(admission) {
            mutationsInProgress++
            leases.toList().forEach { it.cancel(CancellationException("Credential access revoked")) }
        }
        return try { withContext(Dispatchers.IO) { lock.withLock { action() } } }
        finally { synchronized(admission) { mutationsInProgress-- } }
    }

    private fun cleanup(retainId: String?) {
        cipher.ids().filter { it != retainId }.forEach(cipher::delete)
    }

    private fun readRecord(): Record? {
        val bytes = files.read() ?: return null
        if (bytes.contentEquals(byteArrayOf(0))) return null
        if (bytes.size !in 1..8192) error("Invalid envelope")
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            check(input.readInt() == MAGIC && input.readInt() == 1)
            val id = input.readUTF()
            check(UUID.fromString(id).toString() == id)
            val ivSize = input.readInt()
            check(ivSize == 12)
            val iv = ByteArray(ivSize).also(input::readFully)
            val size = input.readInt()
            check(size in 17..MAX_KEY_BYTES + 16)
            val ciphertext = ByteArray(size).also(input::readFully)
            check(input.read() == -1)
            Record(id, EncryptedCredential(iv, ciphertext))
        }
    }

    private fun encode(record: Record): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use {
            check(record.encrypted.iv.size == 12)
            it.writeInt(MAGIC); it.writeInt(1); it.writeUTF(record.id)
            it.writeInt(record.encrypted.iv.size); it.write(record.encrypted.iv)
            it.writeInt(record.encrypted.ciphertext.size); it.write(record.encrypted.ciphertext)
        }
    }.toByteArray()

    private fun validateBytes(value: ByteArray) {
        check(value.size in 1..MAX_KEY_BYTES && value.all { (it.toInt() and 255) in 33..126 })
    }

    private fun aad(id: String) = "TavernForge|Gemini|credential-envelope|1|$id".toByteArray(Charsets.US_ASCII)
    private data class Record(val id: String, val encrypted: EncryptedCredential)
    private companion object {
        const val MAX_KEY_BYTES = 4096
        const val MAGIC = 0x5446474b
    }
}

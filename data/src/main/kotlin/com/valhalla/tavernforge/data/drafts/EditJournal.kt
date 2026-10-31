package com.valhalla.tavernforge.data.drafts

import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.StoredProject
import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.JsonPatch
import com.valhalla.tavernforge.format.ParseResult
import com.valhalla.tavernforge.format.PatchResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** This journal is only for user-edited saved JSON, never credentials or provider requests. */
data class RecoveryInput(val path: String, val text: String, val mode: String, val action: String, val pathEditable: Boolean) {
    init {
        require(path.length <= 4096 && text.length <= 1024 * 1024)
        require(mode in setOf("TEXT", "JSON") && action in setOf("SET", "REMOVE"))
    }
    override fun toString() = "RecoveryInput(redacted)"
}

class EditRecovery(
    val projectId: String,
    val baseRevision: Long,
    val originalSha256: String,
    val baseCurrentSha256: String,
    val preview: SourceEnvelope,
    val hasAppliedChanges: Boolean,
    val input: RecoveryInput? = null,
) {
    init {
        require(projectId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && baseRevision >= 0)
        require(originalSha256.matches(HASH) && baseCurrentSha256.matches(HASH))
        require(preview.sizeBytes in 1..MAX_PREVIEW_BYTES)
    }
    /** Recovery is a single undoable command against an unchanged saved base. Never rebase silently. */
    fun patchesFor(stored: StoredProject, engine: CharacterCardEngine): List<JsonPatch> {
        if (stored.summary.id != projectId || stored.documentRevision != baseRevision ||
            stored.source.sha256 != originalSha256 || stored.currentSource.sha256 != baseCurrentSha256)
            throw EditJournalException(EditJournalError.STALE_BASE)
        val base = (engine.restore(stored.source, stored.currentSource, baseRevision) as? ParseResult.Success)?.document
            ?: throw EditJournalException(EditJournalError.INVALID_DRAFT)
        val recovered = (engine.restore(stored.source, preview, if (hasAppliedChanges) {
            if (baseRevision == Long.MAX_VALUE) throw EditJournalException(EditJournalError.INVALID_DRAFT)
            baseRevision + 1
        } else baseRevision) as? ParseResult.Success)?.document
            ?: throw EditJournalException(EditJournalError.INVALID_DRAFT)
        if (!hasAppliedChanges) {
            if (!preview.bytes().contentEquals(stored.currentSource.bytes())) throw EditJournalException(EditJournalError.INVALID_DRAFT)
            return emptyList()
        }
        val patches = listOf(JsonPatch.Replace("", recovered.raw))
        if (engine.patch(base, baseRevision, patches) !is PatchResult.Success) throw EditJournalException(EditJournalError.INVALID_DRAFT)
        return patches
    }
    override fun toString() = "EditRecovery(redacted)"
    private companion object {
        val HASH = Regex("[a-f0-9]{64}")
        const val MAX_PREVIEW_BYTES = 4 * 1024 * 1024
    }
}

enum class EditJournalError { CORRUPT_JOURNAL, STORAGE_FAILURE, STALE_BASE, INVALID_DRAFT }
class EditJournalException(val code: EditJournalError) : Exception(code.name)

interface EditJournal {
    suspend fun load(): EditRecovery?
    suspend fun startSession(): Long
    /** False means a save/discard/new session already invalidated this writer. */
    suspend fun save(session: Long, recovery: EditRecovery): Boolean
    suspend fun clear()
}

/** One bounded app-private atomic file. Epochs prevent delayed autosaves resurrecting discarded work. */
class FileEditJournal(root: File) : EditJournal {
    private val directory = root.toPath().toAbsolutePath().normalize()
    private val active = directory.resolve("edit-recovery.bin")
    private val mutex = Mutex()
    private var epoch = 0L

    override suspend fun startSession(): Long = withContext(Dispatchers.IO) { mutex.withLock { ++epoch } }

    override suspend fun load(): EditRecovery? = withContext(Dispatchers.IO) { mutex.withLock {
        if (!Files.exists(active, LinkOption.NOFOLLOW_LINKS)) return@withLock null
        try {
            check(!Files.isSymbolicLink(directory) && Files.isRegularFile(active, LinkOption.NOFOLLOW_LINKS))
            FileChannel.open(active, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                check(channel.size() in 33..MAX_JOURNAL_BYTES.toLong())
                val bytes = ByteArray(channel.size().toInt())
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) check(channel.read(buffer) >= 0)
                check(channel.read(ByteBuffer.allocate(1)) == -1)
                decode(bytes)
            }
        } catch (_: Exception) { throw EditJournalException(EditJournalError.CORRUPT_JOURNAL) }
    } }

    override suspend fun save(session: Long, recovery: EditRecovery): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        if (session != epoch) return@withLock false
        try {
            val bytes = encode(recovery)
            ensureDirectory()
            val temporary = Files.createTempFile(directory, ".edit-pending-", ".tmp")
            try {
                FileOutputStream(temporary.toFile()).use { it.write(bytes); it.fd.sync() }
                Files.move(temporary, active, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                syncDirectory()
            } finally { Files.deleteIfExists(temporary) }
            true
        } catch (_: Exception) { throw EditJournalException(EditJournalError.STORAGE_FAILURE) }
    } }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        ++epoch
        try {
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return@withLock
            check(!Files.isSymbolicLink(directory))
            Files.deleteIfExists(active)
            Files.newDirectoryStream(directory, ".edit-pending-*.tmp").use { pending ->
                pending.forEach { if (!Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(it) }
            }
            syncDirectory()
        } catch (_: Exception) { throw EditJournalException(EditJournalError.STORAGE_FAILURE) }
    } }

    private fun ensureDirectory() {
        check(!Files.isSymbolicLink(directory))
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(directory)
            directory.parent?.let { FileChannel.open(it, StandardOpenOption.READ).use { c -> c.force(true) } }
        }
        check(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
    }
    private fun syncDirectory() { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }

    private fun encode(value: EditRecovery): ByteArray {
        val payload = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(MAGIC); data.writeInt(1)
            data.writeUTF(value.projectId); data.writeLong(value.baseRevision)
            data.writeUTF(value.originalSha256); data.writeUTF(value.baseCurrentSha256)
            data.writeBoolean(value.hasAppliedChanges)
            val bytes = value.preview.bytes(); data.writeInt(bytes.size); data.write(bytes)
            data.writeBoolean(value.input != null)
            value.input?.let { input ->
                data.writeUTF(input.path); data.writeUTF(input.mode); data.writeUTF(input.action); data.writeBoolean(input.pathEditable)
                // Preserve even unfinished UTF-16 input exactly; applying it later validates JSON.
                data.writeInt(input.text.length)
                input.text.forEach { data.writeChar(it.code) }
            }
        } }.toByteArray()
        check(payload.size + 32 <= MAX_JOURNAL_BYTES)
        return MessageDigest.getInstance("SHA-256").digest(payload) + payload
    }
    private fun decode(bytes: ByteArray): EditRecovery {
        val payload = bytes.copyOfRange(32, bytes.size)
        check(MessageDigest.isEqual(bytes.copyOfRange(0, 32), MessageDigest.getInstance("SHA-256").digest(payload)))
        return DataInputStream(ByteArrayInputStream(payload)).use { data ->
            check(data.readInt() == MAGIC && data.readInt() == 1)
            val id = data.readUTF(); val revision = data.readLong(); val original = data.readUTF(); val current = data.readUTF()
            val changed = data.readBoolean()
            val size = data.readInt(); check(size in 1..4 * 1024 * 1024 && size <= data.available())
            val preview = SourceEnvelope(ByteArray(size).also(data::readFully))
            val input = if (!data.readBoolean()) null else {
                val path = data.readUTF(); val mode = data.readUTF(); val action = data.readUTF(); val editable = data.readBoolean()
                val textSize = data.readInt(); check(textSize in 0..1024 * 1024 && textSize.toLong() * 2 <= data.available())
                val text = String(CharArray(textSize) { data.readChar() })
                RecoveryInput(path, text, mode, action, editable)
            }
            check(data.read() == -1)
            EditRecovery(id, revision, original, current, preview, changed, input)
        }
    }
    private companion object {
        const val MAGIC = 0x5446454a
        const val MAX_JOURNAL_BYTES = 9 * 1024 * 1024
    }
}

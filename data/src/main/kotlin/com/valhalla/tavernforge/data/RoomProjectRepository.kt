package com.valhalla.tavernforge.data

import com.valhalla.tavernforge.core.model.ProjectSummary
import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.StoredProject
import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.CharacterDocument
import com.valhalla.tavernforge.format.ExportResult
import com.valhalla.tavernforge.format.JsonPatch
import com.valhalla.tavernforge.format.ParseResult
import com.valhalla.tavernforge.format.PatchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Coordinates immutable managed source files with an atomic metadata index.
 * The production factory provides a single writer. Test/reopen instances sharing live stores
 * must also share this mutex; multiple application processes are not supported.
 */
class RoomProjectRepository(
    private val index: ProjectIndex,
    private val files: ProjectFileStore,
    private val engine: CharacterCardEngine = CharacterCardEngine(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mutex: Mutex = Mutex(),
) : ProjectRepository {
    private var recovered = false

    override fun observeProjects(): Flow<List<ProjectSummary>> = flow {
        recover()
        emitAll(index.observe().map { records -> records.map { it.summary } }.catch { failure ->
            throw databaseFailure(failure)
        })
    }.flowOn(dispatcher)

    /** Recovery never infers references from a partial or failed database read. */
    suspend fun recover() = locked {
        cleanup(strict = true)
        recovered = true
    }

    override suspend fun saveImported(fileName: String, source: SourceEnvelope): ProjectSummary = locked {
        if (source.mediaType.length > MAX_MEDIA_TYPE_CHARS) {
            throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, "The source media type exceeds the configured metadata limit.")
        }
        if (source.sizeBytes > engine.limits.maxBytes) {
            throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, "The source exceeds the configured JSON limit.")
        }
        val parsed = engine.parse(source.bytes()) as? ParseResult.Success
            ?: throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, "Only bounded, parseable JSON objects can be saved.")
        val document = parsed.document
        val safeFileName = fileName.substringAfterLast('/').substringAfterLast('\\').trim().take(MAX_FILE_NAME_CHARS)
            .ifBlank { "import.json" }
        val title = document.displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: safeFileName.removeSuffix(".json").ifBlank { "Untitled project" }
        val now = clock().coerceAtLeast(0)
        val summary = ProjectSummary(
            id = allocateId(), title = title.take(MAX_TITLE_CHARS), sourceFileName = safeFileName,
            sourceFormat = document.sourceFormat, specVersion = document.specVersion,
            isDraft = !engine.validate(document).isValid, createdAtEpochMillis = now, updatedAtEpochMillis = now,
            sourceSha256 = source.sha256, sourceSizeBytes = source.sizeBytes,
        )
        ensureRecovered()
        currentCoroutineContext().ensureActive()
        // Once this critical section begins, cancellation may report after a successful commit.
        // Completing or rolling back the bounded save takes precedence over abandoning references.
        withContext(NonCancellable) {
            try {
                val key = fileOperation { files.ensureSource(source) }
                if (key != "${source.sha256}.json") corrupt("The managed source reference does not match its imported bytes.")
                val record = ProjectRecord(summary, key, source.mediaType)
                databaseOperation { index.insert(record) }
                summary
            } catch (failure: Exception) {
                // insert() may have committed and then thrown. Re-read ALL references before cleanup.
                cleanup(strict = false)
                throw failure
            }
        }
    }

    override suspend fun load(id: String): StoredProject = locked {
        ensureRecovered()
        loadRecord(find(id))
    }

    override suspend fun applyEdits(id: String, expectedRevision: Long, operations: List<JsonPatch>): StoredProject = locked {
        ensureRecovered()
        val old = find(id)
        if (old.documentRevision != expectedRevision) revisionConflict()
        val (stored, document) = readRecord(old)
        if (!document.isSupportedFormat) {
            throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, "Editing this source format is not supported. Its original has been retained.")
        }
        if (operations.isEmpty()) return@locked stored
        val patched = (engine.patch(document, expectedRevision, operations) as? PatchResult.Success)?.document
            ?: throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, "The changes could not be applied within the JSON and source-format rules.")
        val snapshot = engine.snapshot(patched) as? ExportResult.Success
            ?: throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, "The edited draft could not be stored within the JSON limits.")
        val current = SourceEnvelope(snapshot.bytes, old.mediaType)
        val summary = old.summary.copy(isDraft = !engine.validate(patched).isValid,
            updatedAtEpochMillis = maxOf(clock().coerceAtLeast(0), old.summary.updatedAtEpochMillis))
        currentCoroutineContext().ensureActive()
        // Finalize bytes before the conditional DB mutation. A late failure may be ambiguous,
        // so cleanup must re-read every original and current reference before pruning anything.
        withContext(NonCancellable) {
            try {
                val key = fileOperation { files.ensureSource(current) }
                if (key != "${current.sha256}.json") corrupt("The edited snapshot reference does not match its bytes.")
                val count = databaseOperation {
                    index.updateContent(id, expectedRevision, key, current.sha256, current.sizeBytes,
                        summary.isDraft, summary.updatedAtEpochMillis)
                }
                if (count != 1) revisionConflict()
                cleanup(strict = false)
                StoredProject(summary, stored.source, current, patched.revision)
            } catch (failure: Exception) {
                cleanup(strict = false)
                throw failure
            }
        }
    }

    override suspend fun rename(id: String, title: String) = locked {
        val normalized = title.trim()
        if (normalized.isBlank() || normalized.length > MAX_TITLE_CHARS) {
            throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, "Enter a project title of 1 to $MAX_TITLE_CHARS characters.")
        }
        ensureRecovered()
        val old = find(id)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            val count = databaseOperation { index.rename(id, normalized, maxOf(clock(), old.summary.updatedAtEpochMillis)) }
            if (count != 1) notFound()
        }
    }

    override suspend fun duplicate(id: String): ProjectSummary = locked {
        ensureRecovered()
        val original = find(id)
        loadRecord(original) // Never create another visible row pointing at missing/corrupt bytes.
        val now = clock().coerceAtLeast(0)
        val summary = original.summary.copy(
            id = allocateId(), title = original.summary.title.take(MAX_TITLE_CHARS - COPY_SUFFIX.length) + COPY_SUFFIX,
            createdAtEpochMillis = now, updatedAtEpochMillis = now,
        )
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            databaseOperation { index.insert(original.copy(summary = summary)) }
            summary
        }
    }

    override suspend fun delete(id: String) = locked {
        ensureRecovered()
        find(id)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            try {
                if (databaseOperation { index.delete(id) } != 1) notFound()
            } finally {
                // A shared source remains while any row references it. Cleanup failure leaves orphans.
                cleanup(strict = false)
            }
        }
    }

    private suspend fun loadRecord(record: ProjectRecord): StoredProject = readRecord(record).first

    private suspend fun readRecord(record: ProjectRecord): Pair<StoredProject, CharacterDocument> {
        if (record.documentRevision < 0L || record.mediaType.length > MAX_MEDIA_TYPE_CHARS) {
            corrupt("The stored revision or source metadata is invalid. Its files have been retained.")
        }
        val originalBytes = fileOperation {
            files.read(record.sourceFileKey, record.summary.sourceSha256, record.summary.sourceSizeBytes)
        }
        val source = if (originalBytes.mediaType == record.mediaType) originalBytes
            else SourceEnvelope(originalBytes.bytes(), record.mediaType)
        val current = if (record.currentFileKey == record.sourceFileKey &&
            record.currentSha256 == record.summary.sourceSha256 && record.currentSizeBytes == record.summary.sourceSizeBytes) source
        else {
            val bytes = fileOperation { files.read(record.currentFileKey, record.currentSha256, record.currentSizeBytes) }
            if (bytes.mediaType == record.mediaType) bytes else SourceEnvelope(bytes.bytes(), record.mediaType)
        }
        val restored = engine.restore(source, current, record.documentRevision) as? ParseResult.Success
            ?: corrupt("The stored JSON snapshots cannot be restored safely. Their files have been retained.")
        if (restored.document.sourceFormat != record.summary.sourceFormat || restored.document.specVersion != record.summary.specVersion) {
            corrupt("The stored source and project format metadata do not match. Its source has been retained.")
        }
        return StoredProject(record.summary, source, current, record.documentRevision) to restored.document
    }

    private suspend fun allocateId(): String {
        val id = newId()
        if (!PROJECT_ID.matches(id)) throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, "A valid project identifier could not be generated.")
        if (databaseOperation { index.find(id) } != null) {
            throw ProjectStorageException(ProjectStorageError.DATABASE_FAILURE, "A unique project identifier could not be generated.")
        }
        return id
    }

    private suspend fun find(id: String): ProjectRecord = databaseOperation { index.find(id) } ?: notFound()

    private suspend fun ensureRecovered() {
        if (!recovered) {
            cleanup(strict = true)
            recovered = true
        }
    }

    private suspend fun cleanup(strict: Boolean) {
        val referenced = try {
            databaseOperation { index.all() }.flatMapTo(mutableSetOf()) { listOf(it.sourceFileKey, it.currentFileKey) }
        } catch (failure: Exception) {
            if (failure is CancellationException || strict) throw failure
            // Never infer a reference set from an incomplete or failed database read.
            return
        }
        try {
            fileOperation { files.prune(referenced) }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // Cleanup is best-effort even at startup: an undeletable orphan or bad reference must
            // not block access to healthy projects. Specific referenced corruption fails on load.
        }
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(dispatcher) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            block()
        }
    }

    private suspend fun <T> databaseOperation(block: suspend () -> T): T = try { block() } catch (failure: Exception) {
        throw databaseFailure(failure)
    }

    private fun databaseFailure(failure: Throwable): Throwable = when (failure) {
        is CancellationException, is ProjectStorageException -> failure
        else -> ProjectStorageException(ProjectStorageError.DATABASE_FAILURE, "The project library could not be read or updated. Please try again.", failure)
    }

    private fun <T> fileOperation(block: () -> T): T = try { block() } catch (failure: Exception) {
        when (failure) {
            is CancellationException, is ProjectStorageException -> throw failure
            else -> throw ProjectStorageException(ProjectStorageError.IO_FAILURE, "Project storage could not be read or written. Check available device storage and try again.", failure)
        }
    }

    private fun notFound(): Nothing = throw ProjectStorageException(ProjectStorageError.NOT_FOUND, "This saved project is no longer available.")
    private fun revisionConflict(): Nothing = throw ProjectStorageException(ProjectStorageError.REVISION_CONFLICT,
        "This project changed since editing began. Reopen its latest revision before saving changes.")
    private fun corrupt(message: String): Nothing = throw ProjectStorageException(ProjectStorageError.CORRUPT_SOURCE, message)

    companion object {
        const val MAX_TITLE_CHARS = 256
        private const val MAX_FILE_NAME_CHARS = 255
        private const val MAX_MEDIA_TYPE_CHARS = 255
        private const val COPY_SUFFIX = " (copy)"
        private val PROJECT_ID = Regex("[A-Za-z0-9_-]{1,80}")
    }
}

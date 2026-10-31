package com.valhalla.tavernforge.data

import com.valhalla.tavernforge.core.model.ProjectSummary
import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.StoredProject
import com.valhalla.tavernforge.format.JsonPatch
import com.valhalla.tavernforge.core.model.LibraryQuery
import com.valhalla.tavernforge.core.model.LibraryEntry
import com.valhalla.tavernforge.core.model.ProjectMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface ProjectRepository : CompositionRepository {
    fun observeProjects(): Flow<List<ProjectSummary>>
    fun observeLibrary(query: LibraryQuery = LibraryQuery()): Flow<List<LibraryEntry>> = observeProjects().map { filterLibrary(it, emptyList(), query) }
    suspend fun projectMetadata(id: String): ProjectMetadata = ProjectMetadata(id, 0, emptyList())
    suspend fun setProjectTags(id: String, expectedMetadataRevision: Long, tags: List<String>): ProjectMetadata =
        throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, "Local tags are unavailable in this repository.")
    suspend fun saveImported(fileName: String, source: SourceEnvelope): ProjectSummary
    suspend fun load(id: String): StoredProject
    suspend fun applyEdits(id: String, expectedRevision: Long, operations: List<JsonPatch>): StoredProject
    suspend fun rename(id: String, title: String)
    suspend fun duplicate(id: String): ProjectSummary
    suspend fun delete(id: String)
}

enum class ProjectStorageError { NOT_FOUND, INVALID_INPUT, CORRUPT_SOURCE, IO_FAILURE, DATABASE_FAILURE, REVISION_CONFLICT, IN_USE }

/** Messages are safe UI descriptions, never paths, JSON payloads or provider exception text. */
class ProjectStorageException(
    val code: ProjectStorageError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Persisted references and the import revision are distinct from library display metadata. */
data class ProjectRecord(
    val summary: ProjectSummary,
    val sourceFileKey: String,
    val mediaType: String = "application/json",
    val documentRevision: Long = 0,
    val currentFileKey: String = sourceFileKey,
    val currentSha256: String = summary.sourceSha256,
    val currentSizeBytes: Int = summary.sourceSizeBytes,
)

/** Every mutation must commit atomically; implementations must never silently replace an ID. */
interface ProjectIndex {
    fun observe(): Flow<List<ProjectRecord>>
    suspend fun all(): List<ProjectRecord>
    suspend fun find(id: String): ProjectRecord?
    suspend fun insert(record: ProjectRecord)
    /** Atomically replace current-content metadata and increment only the matching revision. */
    suspend fun updateContent(
        id: String,
        expectedRevision: Long,
        currentFileKey: String,
        currentSha256: String,
        currentSizeBytes: Int,
        isDraft: Boolean,
        updatedAtEpochMillis: Long,
    ): Int
    suspend fun rename(id: String, title: String, updatedAtEpochMillis: Long): Int
    suspend fun delete(id: String): Int
}

/** Synchronous file operations run on the repository IO dispatcher under its single-writer lock. */
interface ProjectFileStore {
    /** Returns a stable key only after verified bytes and the final directory entry are synced. */
    fun ensureSource(source: SourceEnvelope): String
    fun read(fileKey: String, expectedSha256: String, expectedSizeBytes: Int): SourceEnvelope
    /** Only called with the references from a successful, complete index read. */
    fun prune(referenced: Set<String>)
}

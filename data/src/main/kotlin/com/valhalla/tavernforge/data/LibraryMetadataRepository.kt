package com.valhalla.tavernforge.data

import com.valhalla.tavernforge.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import java.util.Locale

/** Labels belong to the local library, never implicitly to a character's native JSON tags field. */
interface LibraryMetadataIndex {
    fun observe(): Flow<List<ProjectMetadata>>
    suspend fun find(id: String): ProjectMetadata?
    suspend fun setTags(id: String, expectedRevision: Long, tags: List<String>)
}
internal fun normalizeProjectTags(tags: List<String>): List<String> {
    if (tags.size > 32) invalidTags()
    val normalized = tags.map { it.trim() }
    if (normalized.any { it.isEmpty() || it.length > 64 || it.any(Char::isISOControl) } || normalized.map { it.lowercase(Locale.ROOT) }.distinct().size != normalized.size) invalidTags()
    return normalized
}
private fun invalidTags(): Nothing = throw ProjectStorageException(ProjectStorageError.INVALID_INPUT,
    "Use at most 32 distinct local tags, each 1–64 characters without control characters. Tags are compared without letter case.")
internal fun encodeTags(tags: List<String>) = JsonArray(tags.map(::JsonPrimitive)).toString()
internal fun decodeTags(json: String): List<String> = try {
    if (json.length > 16384) throw IllegalArgumentException()
    val array = Json.parseToJsonElement(json) as? JsonArray ?: throw IllegalArgumentException()
    normalizeProjectTags(array.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: throw IllegalArgumentException() })
} catch (_: Exception) { throw ProjectStorageException(ProjectStorageError.CORRUPT_SOURCE, "Saved local tags are invalid; their record has been retained.") }
internal fun filterLibrary(projects: List<ProjectSummary>, metadata: List<ProjectMetadata>, query: LibraryQuery): List<LibraryEntry> {
    val byId = metadata.associateBy { it.projectId }
    val search = query.search.trim().take(256)
    val tags = query.tags.map { it.trim().lowercase(Locale.ROOT) }.toSet()
    val entries = projects.map { LibraryEntry(it, byId[it.id] ?: ProjectMetadata(it.id, 0, emptyList())) }.filter { entry ->
        val summary = entry.summary
        (query.formats.isEmpty() || summary.sourceFormat in query.formats) &&
            (query.draftFilter == LibraryDraftFilter.ALL || summary.isDraft == (query.draftFilter == LibraryDraftFilter.DRAFTS)) &&
            (search.isEmpty() || summary.title.contains(search, true) || summary.sourceFileName.contains(search, true) || entry.metadata.tags.any { it.contains(search, true) }) &&
            entry.metadata.tags.map { it.lowercase(Locale.ROOT) }.toSet().containsAll(tags)
    }
    return when (query.sort) {
        LibrarySort.RECENT -> entries.sortedWith(compareByDescending<LibraryEntry> { it.summary.updatedAtEpochMillis }.thenBy { it.summary.id })
        LibrarySort.OLDEST -> entries.sortedWith(compareBy<LibraryEntry> { it.summary.createdAtEpochMillis }.thenBy { it.summary.id })
        LibrarySort.TITLE -> entries.sortedWith(compareBy<LibraryEntry> { it.summary.title.lowercase(Locale.ROOT) }.thenBy { it.summary.id })
    }
}

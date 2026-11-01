package com.valhalla.tavernforge.core.model

enum class LibraryDraftFilter { ALL, DRAFTS, VALID }
enum class LibrarySort { RECENT, TITLE, OLDEST }
data class LibraryQuery(val search: String = "", val formats: Set<SourceFormat> = emptySet(),
    val draftFilter: LibraryDraftFilter = LibraryDraftFilter.ALL, val tags: Set<String> = emptySet(),
    val sort: LibrarySort = LibrarySort.RECENT)
class ProjectMetadata(val projectId: String, val revision: Long, tags: List<String>) {
    private val labels = tags.toList()
    val tags get() = labels.toList()
}
data class LibraryEntry(val summary: ProjectSummary, val metadata: ProjectMetadata)

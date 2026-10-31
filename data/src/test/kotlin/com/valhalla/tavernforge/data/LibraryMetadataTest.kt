package com.valhalla.tavernforge.data

import com.valhalla.tavernforge.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryMetadataTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun localTagsNeverMutateJsonAndStaleChangesAreRejected() = runBlocking<Unit> {
        val index = FaultIndex(); val metadata = MetadataMemoryIndex()
        val repo = RoomProjectRepository(index, ManagedJsonFiles(temp.newFolder()), dispatcher = Dispatchers.Unconfined, metadataIndex = metadata)
        val saved = repo.saveImported("character.json", source())
        assertEquals(0L, repo.projectMetadata(saved.id).revision)
        val tags = repo.setProjectTags(saved.id, 0, listOf(" Archive ", "Offline"))
        assertEquals(listOf("Archive", "Offline"), tags.tags)
        assertEquals(1L, tags.revision)
        assertArrayEquals(preservedV1, repo.load(saved.id).currentSource.bytes())
        assertEquals(0L, repo.load(saved.id).documentRevision)
        assertEquals(1L, repo.setProjectTags(saved.id, 1, tags.tags).revision)
        try { repo.setProjectTags(saved.id, 0, listOf("Stale")); fail() } catch (e: ProjectStorageException) { assertEquals(ProjectStorageError.REVISION_CONFLICT, e.code) }
        assertEquals(saved.id, repo.observeLibrary(LibraryQuery(tags = setOf("ARCHIVE"))).first().single().summary.id)
        val exposed = tags.tags as MutableList<String>; exposed.add("Mutation")
        assertEquals(2, repo.projectMetadata(saved.id).tags.size)
    }
    @Test fun tagsEnforceDistinctBoundedValuesAndDecodeRejectsCorruption() {
        for (tags in listOf(listOf(""), listOf("   "), listOf("A", "a"), listOf("x".repeat(65)), List(33) { "$it" }, listOf("line\nfeed"))) {
            try { normalizeProjectTags(tags); fail("Expected tag failure") } catch (e: ProjectStorageException) { assertEquals(ProjectStorageError.INVALID_INPUT, e.code) }
        }
        assertEquals(32, normalizeProjectTags(List(32) { "Tag $it" }).size)
        assertEquals(64, normalizeProjectTags(listOf("x".repeat(64))).single().length)
        for (json in listOf("{}", "[null]", "[1]", "[\"x\",\"X\"]")) {
            try { decodeTags(json); fail() } catch (e: ProjectStorageException) { assertEquals(ProjectStorageError.CORRUPT_SOURCE, e.code) }
        }
    }
    @Test fun typeDraftSearchAndAllTagsFiltersComposeWithDeterministicSorts() {
        val a = summary("a", "Zoo", SourceFormat.V1, false, 1, 9)
        val b = summary("b", "Alpha", SourceFormat.LOREBOOK_V2, true, 2, 8)
        val c = summary("c", "Beta", SourceFormat.V3, true, 3, 7)
        val metadata = listOf(ProjectMetadata("a", 0, listOf("Archive", "Offline")), ProjectMetadata("b", 0, listOf("Archive")))
        val all = listOf(a, b, c)
        assertEquals(listOf("a"), filterLibrary(all, metadata, LibraryQuery(tags = setOf("archive", "OFFLINE"))).map { it.summary.id })
        assertEquals(listOf("b"), filterLibrary(all, metadata, LibraryQuery(formats = setOf(SourceFormat.LOREBOOK_V2), draftFilter = LibraryDraftFilter.DRAFTS)).map { it.summary.id })
        assertEquals(listOf("a"), filterLibrary(all, metadata, LibraryQuery(draftFilter = LibraryDraftFilter.VALID)).map { it.summary.id })
        assertEquals(listOf("a", "b"), filterLibrary(all, metadata, LibraryQuery(search = "archive")).map { it.summary.id })
        assertEquals(listOf("b", "c", "a"), filterLibrary(all, metadata, LibraryQuery(sort = LibrarySort.TITLE)).map { it.summary.id })
        assertEquals(listOf("a", "b", "c"), filterLibrary(all, metadata, LibraryQuery(sort = LibrarySort.OLDEST)).map { it.summary.id })
        assertTrue(filterLibrary(all, metadata, LibraryQuery(formats = setOf(SourceFormat.UNKNOWN))).isEmpty())
    }
    @Test fun emptyMetadataAndEscapedTagsRoundTripWithoutTouchingDocumentFields() {
        val tags = listOf("quote\"", "日本語", "back\\slash")
        assertEquals(tags, decodeTags(encodeTags(tags)))
        val entry = filterLibrary(listOf(summary("x", "X", SourceFormat.WORLD_INFO, false, 1, 1)), emptyList(), LibraryQuery()).single()
        assertEquals(0L, entry.metadata.revision); assertTrue(entry.metadata.tags.isEmpty())
    }
    private fun summary(id: String, title: String, format: SourceFormat, draft: Boolean, created: Long, updated: Long) =
        ProjectSummary(id, title, "$id.json", format, null, draft, created, updated, "0".repeat(64), 2)
}
private class MetadataMemoryIndex : LibraryMetadataIndex {
    private val records = linkedMapOf<String, ProjectMetadata>()
    private val changes = MutableStateFlow<List<ProjectMetadata>>(emptyList())
    override fun observe() = changes
    override suspend fun find(id: String) = records[id]
    override suspend fun setTags(id: String, expectedRevision: Long, tags: List<String>) {
        val old = records[id] ?: ProjectMetadata(id, 0, emptyList())
        if (old.revision != expectedRevision) throw ProjectStorageException(ProjectStorageError.REVISION_CONFLICT, "Stale")
        records[id] = ProjectMetadata(id, old.revision + 1, tags); changes.value = records.values.toList()
    }
}

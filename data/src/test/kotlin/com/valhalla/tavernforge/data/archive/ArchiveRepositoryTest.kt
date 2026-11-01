package com.valhalla.tavernforge.data.archive

import com.valhalla.tavernforge.core.model.*
import com.valhalla.tavernforge.data.*
import com.valhalla.tavernforge.format.JsonPatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Independent repository/file tests. Atomic Room rollback is exercised in ArchiveRoomDeviceTest. */
class ArchiveRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun additiveRestoresKeepExactOriginalCurrentRevisionsAndUnknownDrafts() = runBlocking {
        val source = store("source")
        val first = source.repository.saveImported("original.json", SourceEnvelope(V1))
        source.repository.applyEdits(first.id, 0, listOf(JsonPatch.Replace("/name", JsonPrimitive("Edited"))))
        source.repository.setProjectTags(first.id, 0, listOf("Local", "Preserved"))
        source.repository.saveImported("future.json", SourceEnvelope(FUTURE))
        val captured = snapshot(source.repository)
        val bytes = export(source.repository)
        val target = store("target")
        val existing = target.repository.saveImported("existing.json", SourceEnvelope(V1))
        val service = LibraryBackupService(target.repository, temporary.newFolder())
        val firstRestore = service.restore(service.prepareRestore(ByteArrayInputStream(bytes)))
        val secondRestore = service.restore(service.prepareRestore(ByteArrayInputStream(bytes)))
        assertEquals(5, target.index.records.size)
        assertTrue(firstRestore.projectIds.values.toSet().intersect(secondRestore.projectIds.values.toSet()).isEmpty())
        assertArrayEquals(V1, target.repository.load(existing.id).source.bytes())
        for ((oldId, newId) in firstRestore.projectIds) {
            val expected = source.repository.load(oldId); val actual = target.repository.load(newId)
            assertEquals(expected.summary.copy(id = newId), actual.summary)
            assertArrayEquals(expected.source.bytes(), actual.source.bytes())
            assertArrayEquals(expected.currentSource.bytes(), actual.currentSource.bytes())
            assertEquals(expected.documentRevision, actual.documentRevision)
        }
        val tags = target.repository.projectMetadata(firstRestore.projectIds.getValue(first.id))
        assertEquals(1L, tags.revision); assertEquals(listOf("Local", "Preserved"), tags.tags)
        assertEquals(SourceFormat.UNKNOWN, captured.manifest.projects.first { it.summary.id != first.id }.summary.sourceFormat)
    }

    @Test fun snapshotBlobReaderCannotEscapeItsProtectedCallback() = runBlocking {
        val source = store("snapshot"); source.repository.saveImported("source.json", SourceEnvelope(V1))
        val escaped = source.repository.withArchiveSnapshot { it }
        expectArchive(ArchiveError.CLOSED) { escaped.readBlob(escaped.manifest.projects.single().original) }
    }

    @Test fun compositionAndLoreRolesAreRejectedBeforeAnyFileStagingOrRows() = runBlocking {
        val source = store("role-source")
        val character = source.repository.saveImported("source.json", SourceEnvelope(V1))
        val lore = source.repository.saveImported("lore.json", SourceEnvelope(LORE))
        val captured = snapshot(source.repository)
        val cases = listOf(
            captured.copyManifest(compositions = listOf(CompositionRecord(lore.id))),
            captured.copyManifest(links = listOf(LoreLinkRecord(lore.id, character.id))),
            captured.copyManifest(links = listOf(LoreLinkRecord(character.id, character.id))),
        )
        for ((caseIndex, manifest) in cases.withIndex()) {
            val target = store("role-target-$caseIndex")
            val before = target.files.ensured
            val candidate = captured.verified(manifest)
            try { expectArchive(ArchiveError.INVALID_MANIFEST) { target.repository.restoreArchive(candidate) } }
            finally { candidate.close() }
            assertEquals(before, target.files.ensured)
            assertTrue(target.index.records.isEmpty()); assertEquals(0, target.compositions.restoreCalls)
        }
    }

    @Test fun semanticDraftMismatchRetainsExistingRowsAndSharedOriginalFiles() = runBlocking {
        val source = store("semantic-source"); source.repository.saveImported("source.json", SourceEnvelope(V1))
        val captured = snapshot(source.repository)
        val bad = captured.copyManifest(projects = captured.manifest.projects.map { it.copy(summary = it.summary.copy(isDraft = !it.summary.isDraft)) })
        val target = store("semantic-target")
        val existing = target.repository.saveImported("existing.json", SourceEnvelope(V1))
        val before = target.json.listFiles()!!.map { it.name }.toSet()
        val candidate = captured.verified(bad)
        try { expectArchive(ArchiveError.INVALID_MANIFEST) { target.repository.restoreArchive(candidate) } }
        finally { candidate.close() }
        assertEquals(setOf(existing.id), target.index.records.keys)
        assertEquals(before, target.json.listFiles()!!.map { it.name }.toSet())
        assertArrayEquals(V1, target.repository.load(existing.id).source.bytes())
    }

    @Test fun consumedReviewHandleCannotInsertTheSameRestoreTwice() = runBlocking {
        val source = store("consume-source"); source.repository.saveImported("source.json", SourceEnvelope(V1))
        val candidate = snapshot(source.repository).verified()
        val target = store("consume-target")
        try {
            target.repository.restoreArchive(candidate)
            expectArchive(ArchiveError.ALREADY_RESTORED) { target.repository.restoreArchive(candidate) }
            assertEquals(1, target.index.records.size); assertEquals(1, target.compositions.restoreCalls)
        } finally { candidate.close() }
    }

    @Test fun cancellationAfterStagingNewCurrentBytesKeepsExistingSharedSources() = runBlocking {
        val source = store("cancel-source")
        val first = source.repository.saveImported("source.json", SourceEnvelope(V1))
        source.repository.applyEdits(first.id, 0, listOf(JsonPatch.Replace("/name", JsonPrimitive("New current"))))
        source.repository.saveImported("second.json", SourceEnvelope(LORE))
        val captured = snapshot(source.repository)
        val manifest = captured.copyManifest(projects = captured.manifest.projects.sortedByDescending { it.documentRevision })
        val target = store("cancel-target")
        val existing = target.repository.saveImported("existing.json", SourceEnvelope(V1))
        val previousFiles = target.json.listFiles()!!.map { it.name }.toSet()
        val stopAt = target.files.ensured + 3
        target.files.beforeEnsure = { if (target.files.ensured == stopAt) throw CancellationException("synthetic precommit cancellation") }
        val candidate = captured.verified(manifest)
        try {
            try { target.repository.restoreArchive(candidate); fail("Expected cancellation") } catch (_: CancellationException) { }
        } finally { target.files.beforeEnsure = {}; candidate.close() }
        assertEquals(setOf(existing.id), target.index.records.keys)
        assertEquals(0, target.compositions.restoreCalls)
        assertEquals(previousFiles, target.json.listFiles()!!.map { it.name }.toSet())
        assertArrayEquals(V1, target.repository.load(existing.id).source.bytes())
    }

    private fun store(name: String): Store {
        val root = temporary.newFolder(name)
        val index = FaultIndex(); val metadata = MemoryMetadata(); val compositions = MemoryCompositions(index, metadata)
        val json = File(root, "json"); val files = FaultFiles(ManagedJsonFiles(json)); var next = 0
        val repository = RoomProjectRepository(index, files, newId = { "$name-${++next}" }, dispatcher = Dispatchers.Unconfined,
            compositionIndex = compositions, assetFiles = ManagedArtworkFiles(File(root, "assets")), metadataIndex = metadata)
        return Store(repository, index, compositions, files, json)
    }
    private data class Store(val repository: RoomProjectRepository, val index: FaultIndex,
        val compositions: MemoryCompositions, val files: FaultFiles, val json: File)
    private suspend fun export(repository: RoomProjectRepository) = ByteArrayOutputStream().also {
        LibraryBackupService(repository, temporary.newFolder()).export(it)
    }.toByteArray()
    private suspend fun snapshot(repository: RoomProjectRepository): Captured = repository.withArchiveSnapshot { snapshot ->
        val refs = ArchiveManifestCodec.validate(snapshot.manifest, ArchiveLimits())
        Captured(snapshot.manifest, refs.mapValues { snapshot.readBlob(it.value) })
    }
    private data class Captured(val manifest: LibraryArchiveManifest, val bytes: Map<String, ByteArray>) {
        fun verified(value: LibraryArchiveManifest = manifest) = VerifiedLibraryArchive(value, { ref -> bytes.getValue(ref.sha256).copyOf() }, {})
        fun copyManifest(projects: List<ArchiveProject> = manifest.projects, compositions: List<CompositionRecord> = manifest.compositions,
            links: List<LoreLinkRecord> = manifest.links) = LibraryArchiveManifest(projects, manifest.artwork, compositions, links, manifest.metadata, manifest.createdAtEpochMillis)
    }
    private suspend fun expectArchive(code: ArchiveError, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (e: ArchiveException) { assertEquals(code, e.code) }
    }
    private class MemoryMetadata : LibraryMetadataIndex {
        val rows = mutableMapOf<String, ProjectMetadata>()
        override fun observe() = MutableStateFlow(rows.values.toList())
        override suspend fun find(id: String) = rows[id]
        override suspend fun setTags(id: String, expectedRevision: Long, tags: List<String>) { rows[id] = ProjectMetadata(id, expectedRevision + 1, tags) }
    }
    private class MemoryCompositions(private val projects: FaultIndex, private val metadata: MemoryMetadata) : CompositionIndex {
        var restoreCalls = 0
        val artwork = mutableMapOf<String, ArtworkRecord>()
        val compositions = mutableMapOf<String, CompositionRecord>()
        val loreLinks = mutableListOf<LoreLinkRecord>()
        override suspend fun restoreArchiveBatch(batch: LibraryRestoreBatch) {
            restoreCalls++
            batch.projects.forEach { projects.insert(it) }
            batch.artwork.forEach { artwork[it.summary.id] = it }
            batch.compositions.forEach { compositions[it.characterId] = it }
            loreLinks.addAll(batch.links); batch.metadata.forEach { metadata.rows[it.projectId] = it }
        }
        override fun observeArtwork() = MutableStateFlow(artwork.values.toList())
        override suspend fun allArtwork() = artwork.values.toList()
        override suspend fun findArtwork(id: String) = artwork[id]
        override suspend fun insertArtwork(record: ArtworkRecord) { artwork[record.summary.id] = record }
        override suspend fun deleteArtwork(id: String) = if (artwork.remove(id) != null) 1 else 0
        override suspend fun artworkInUse(id: String) = false
        override suspend fun lorebookInUse(id: String) = false
        override suspend fun composition(id: String) = compositions[id]
        override suspend fun links(id: String) = loreLinks.filter { it.characterId == id }
        override suspend fun insertPng(project: ProjectRecord, artwork: ArtworkRecord, composition: CompositionRecord) = unsupported()
        override suspend fun setArtwork(id: String, expectedRevision: Long, artworkId: String?) = unsupported()
        override suspend fun linkLorebook(id: String, expectedRevision: Long, lorebookId: String, remove: Boolean) = unsupported()
        override suspend fun duplicate(project: ProjectRecord, originalId: String) = unsupported()
        override suspend fun commitEmbedding(project: ProjectRecord, expectedRevision: Long, expectedCompositionRevision: Long, lorebookId: String, expectedLoreRevision: Long, loreSha256: String) = unsupported()
        private fun unsupported(): Nothing = throw UnsupportedOperationException("Not used by archive tests")
    }
    private companion object {
        val V1 = """{ "name":"Keeper", "description":"", "personality":"", "scenario":"", "first_mes":"", "mes_example":"", "unknown":{"number":1.2300e+09,"null":null,"negativeZero":-0} }""".toByteArray()
        val LORE = """{"entries":[],"extensions":{},"unknown":1.2300e+09}""".toByteArray()
        val FUTURE = """{"spec":"future_character","spec_version":"99","unknown":{"number":-0.00,"typed":false}}""".toByteArray()
    }
}

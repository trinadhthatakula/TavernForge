package com.valhalla.tavernforge.data.archive

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.tavernforge.core.model.*
import com.valhalla.tavernforge.data.*
import com.valhalla.tavernforge.format.JsonPatch
import com.valhalla.tavernforge.format.combine.EmbeddingPlanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Actual Room transactions/FKs and managed files, isolated from the user's app database and assets. */
@RunWith(AndroidJUnit4::class)
class ArchiveRoomDeviceTest {
    private lateinit var context: Context
    private val stores = mutableListOf<Store>()
    @Before fun initialize() { context = InstrumentationRegistry.getInstrumentation().targetContext }
    @After fun cleanup() {
        stores.forEach { it.database.close(); context.deleteDatabase(it.name); it.root.deleteRecursively() }
    }

    @Test fun richArchiveRemapsAllForeignKeysAndPreservesExactContentAcrossReopenAndRepeatedRestore() = runBlocking<Unit> {
        val source = store("source"); val rich = populateRich(source)
        val archive = export(source)
        val target = store("target")
        val existing = target.repository.saveImported("existing.json", SourceEnvelope(V1))
        val service = target.service()
        val first = service.restore(service.prepareRestore(ByteArrayInputStream(archive)))
        target.reopen()
        assertEquals(source.database.projects().all().size + 1, target.database.projects().all().size)
        assertArrayEquals(V1, target.repository.load(existing.id).source.bytes())
        assertTrue(first.projectIds.values.toSet().intersect(first.projectIds.keys).isEmpty())
        assertTrue(first.artworkIds.values.toSet().intersect(first.artworkIds.keys).isEmpty())
        for ((oldId, newId) in first.projectIds) {
            val expected = source.repository.load(oldId); val actual = target.repository.load(newId)
            assertEquals(expected.summary.copy(id = newId), actual.summary)
            assertArrayEquals(expected.source.bytes(), actual.source.bytes())
            assertArrayEquals(expected.currentSource.bytes(), actual.currentSource.bytes())
            assertEquals(expected.documentRevision, actual.documentRevision)
            val oldTags = source.repository.projectMetadata(oldId); val newTags = target.repository.projectMetadata(newId)
            assertEquals(oldTags.revision, newTags.revision); assertEquals(oldTags.tags, newTags.tags)
        }
        for ((oldId, newId) in first.artworkIds) {
            val expected = source.repository.loadArtwork(oldId); val actual = target.repository.loadArtwork(newId)
            assertArrayEquals(expected.bytes(), actual.bytes())
            assertEquals(expected.summary.copy(id = newId,
                sourceArtworkId = expected.summary.sourceArtworkId?.let(first.artworkIds::getValue)), actual.summary)
        }
        for (oldCharacterId in listOf(rich.characterId, rich.copyId)) {
            val old = source.repository.loadComposition(oldCharacterId)
            val restored = target.repository.loadComposition(first.projectIds.getValue(oldCharacterId))
            assertEquals(old.documentRevision, restored.documentRevision)
            assertEquals(old.compositionRevision, restored.compositionRevision)
            assertEquals(first.artworkIds.getValue(old.artwork!!.id), restored.artwork!!.id)
            assertEquals(first.artworkIds.getValue(old.sourcePng!!.id), restored.sourcePng!!.id)
            assertEquals(old.sourceCandidateId, restored.sourceCandidateId)
            val link = restored.lorebooks.single(); val oldLink = old.lorebooks.single()
            assertEquals(first.projectIds.getValue(oldLink.project.id), link.project.id)
            assertEquals(oldLink.embeddedRevision, link.embeddedRevision)
            assertEquals(oldLink.embeddedSha256, link.embeddedSha256)
        }
        val derived = target.repository.loadArtwork(first.artworkIds.getValue(rich.derivedId)).summary
        assertEquals(first.artworkIds.getValue(rich.jpegId), derived.sourceArtworkId)
        expectStorage(ProjectStorageError.IN_USE) { target.repository.deleteArtwork(derived.sourceArtworkId!!) }
        val secondService = target.service()
        val second = secondService.restore(secondService.prepareRestore(ByteArrayInputStream(archive)))
        assertTrue(first.projectIds.values.toSet().intersect(second.projectIds.values.toSet()).isEmpty())
        assertTrue(first.artworkIds.values.toSet().intersect(second.artworkIds.values.toSet()).isEmpty())
        assertEquals(source.database.projects().all().size * 2 + 1, target.database.projects().all().size)
        assertManagedFilesMatchReferences(target)
    }

    @Test fun lateMetadataAbortRollsBackEveryInsertedTableAndPreservesExistingSharedFiles() = runBlocking<Unit> {
        val source = store("abort-source"); populateRich(source)
        val archive = export(source)
        val target = store("abort-target")
        val existing = target.repository.saveImported("existing.json", SourceEnvelope(V2))
        target.repository.setProjectTags(existing.id, 0, listOf("Keep existing"))
        val oldRows = target.database.projects().all().map { it.summary.id }.toSet()
        val oldFiles = target.jsonRoot.listFiles().orEmpty().map { it.name }.toSet()
        val candidate = target.service().prepareRestore(ByteArrayInputStream(archive))
        target.lateDuplicateMetadataId = existing.id
        try {
            expectStorage(ProjectStorageError.DATABASE_FAILURE) { target.repository.restoreArchive(candidate) }
            val attempted = target.lastBatch ?: error("Expected a database transaction attempt")
            assertTrue(attempted.projects.isNotEmpty()); assertTrue(attempted.artwork.isNotEmpty())
            assertTrue(attempted.compositions.isNotEmpty()); assertTrue(attempted.links.isNotEmpty())
            assertTrue(attempted.metadata.isNotEmpty())
            assertEquals(oldRows, target.database.projects().all().map { it.summary.id }.toSet())
            assertTrue(target.database.compositions().allArtwork().isEmpty())
            attempted.projects.forEach { project ->
                assertNull(target.database.compositions().composition(project.summary.id))
                assertTrue(target.database.compositions().links(project.summary.id).isEmpty())
                assertNull(target.database.libraryMetadata().find(project.summary.id))
            }
            assertEquals(listOf("Keep existing"), target.repository.projectMetadata(existing.id).tags)
            assertEquals(oldFiles, target.jsonRoot.listFiles().orEmpty().map { it.name }.toSet())
            assertArrayEquals(V2, target.repository.load(existing.id).source.bytes())
            expectArchive(ArchiveError.ALREADY_RESTORED) { target.repository.restoreArchive(candidate) }
        } finally { candidate.close() }
        target.reopen()
        assertEquals(oldRows, target.database.projects().all().map { it.summary.id }.toSet())
        assertManagedFilesMatchReferences(target)
    }

    @Test fun changedStagedHashAndInvalidDraftSemanticsCreateNoRows() = runBlocking<Unit> {
        val source = store("invalid-source")
        source.repository.saveImported("source.json", SourceEnvelope(V1))
        val good = export(source)
        val target = store("invalid-target")
        val existing = target.repository.saveImported("existing.json", SourceEnvelope(V1))
        val service = target.service()
        val changed = service.prepareRestore(ByteArrayInputStream(good))
        try {
            val staged = target.stageRoot.listFiles()!!.single().resolve("candidate.zip")
            val entry = StoredZipArchive.inspect(staged, ArchiveLimits()).values.first { it.name.startsWith("blobs/") }
            val bytes = staged.readBytes(); bytes[entry.dataOffset.toInt()] = 0; staged.writeBytes(bytes)
            expectArchive(ArchiveError.HASH_MISMATCH) { target.repository.restoreArchive(changed) }
        } finally { changed.close() }
        val semantic = alteredArchive(source) { manifest ->
            LibraryArchiveManifest(manifest.projects.map { it.copy(summary = it.summary.copy(isDraft = !it.summary.isDraft)) },
                manifest.artwork, manifest.compositions, manifest.links, manifest.metadata, manifest.createdAtEpochMillis)
        }
        val invalid = service.prepareRestore(ByteArrayInputStream(semantic))
        try { expectArchive(ArchiveError.INVALID_MANIFEST) { target.repository.restoreArchive(invalid) } }
        finally { invalid.close() }
        assertEquals(listOf(existing.id), target.database.projects().all().map { it.summary.id })
        assertTrue(target.database.compositions().allArtwork().isEmpty())
        assertArrayEquals(V1, target.repository.load(existing.id).source.bytes())
        assertManagedFilesMatchReferences(target)
    }

    @Test fun cancelledPrecommitStagesArePrunedWithoutDeletingAnExistingSharedOriginal() = runBlocking<Unit> {
        val source = store("cancel-source")
        val character = source.repository.saveImported("source.json", SourceEnvelope(V1))
        source.repository.applyEdits(character.id, 0, listOf(JsonPatch.Replace("/name", JsonPrimitive("Edited current"))))
        source.repository.saveImported("lore.json", SourceEnvelope(LORE))
        val ordered = alteredArchive(source) { manifest -> LibraryArchiveManifest(manifest.projects.sortedByDescending { it.documentRevision },
            manifest.artwork, manifest.compositions, manifest.links, manifest.metadata, manifest.createdAtEpochMillis) }
        val target = store("cancel-target")
        val existing = target.repository.saveImported("existing.json", SourceEnvelope(V1))
        val candidate = target.service().prepareRestore(ByteArrayInputStream(ordered))
        target.files.stopAtEnsure = target.files.ensured + 3
        try {
            try { target.repository.restoreArchive(candidate); fail("Expected precommit cancellation") } catch (_: CancellationException) { }
        } finally { target.files.stopAtEnsure = null; candidate.close() }
        assertNull(target.lastBatch)
        assertEquals(listOf(existing.id), target.database.projects().all().map { it.summary.id })
        assertArrayEquals(V1, target.repository.load(existing.id).source.bytes())
        assertManagedFilesMatchReferences(target)
    }

    private suspend fun populateRich(store: Store): Rich {
        val character = store.repository.savePngCard("original-card.png", png(V2))
        store.repository.applyEdits(character.summary.id, 0, listOf(JsonPatch.Replace("/data/name", JsonPrimitive("Edited keeper"))))
        val lore = store.repository.saveImported("lore.json", SourceEnvelope(LORE))
        val plan = (store.repository.prepareEmbedding(character.summary.id, lore.id) as EmbeddingPlanResult.Ready).plan
        store.repository.applyEmbedding(character.summary.id, 0, lore.id, plan, plan.reviewItems.toSet())
        val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        val jpegBytes = try {
            ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }.toByteArray()
        } finally { bitmap.recycle() }
        val jpeg = store.repository.saveArtwork("immutable-original.jpg", jpegBytes)
        val conversion = store.repository.prepareArtworkPngConversion(jpeg.id)
        val derived = store.repository.acceptArtworkPngConversion(conversion, conversion.reviewItems.toSet())
        val composition = store.repository.loadComposition(character.summary.id)
        store.repository.setArtwork(character.summary.id, composition.compositionRevision, derived.id)
        store.repository.setProjectTags(character.summary.id, 0, listOf("Archive", "Preserved"))
        store.repository.setProjectTags(lore.id, 0, listOf("Lore"))
        val duplicate = store.repository.duplicate(character.summary.id)
        store.repository.saveImported("future.json", SourceEnvelope(FUTURE))
        return Rich(character.summary.id, duplicate.id, jpeg.id, derived.id)
    }

    private suspend fun export(store: Store): ByteArray = ByteArrayOutputStream().also { store.service().export(it) }.toByteArray()
    private suspend fun alteredArchive(store: Store, change: (LibraryArchiveManifest) -> LibraryArchiveManifest): ByteArray =
        store.repository.withArchiveSnapshot { snapshot ->
            val manifest = change(snapshot.manifest)
            val output = ByteArrayOutputStream(); val writer = StoredZipArchive.Writer(output, ArchiveLimits())
            writer.add("manifest.json", ArchiveManifestCodec.encode(manifest, ArchiveLimits()))
            ArchiveManifestCodec.validate(manifest, ArchiveLimits()).forEach { (hash, ref) -> writer.add("blobs/$hash", snapshot.readBlob(ref)) }
            writer.finish(); output.toByteArray()
        }
    private suspend fun assertManagedFilesMatchReferences(store: Store) {
        val jsonRefs = store.database.projects().all().flatMap { listOf(it.sourceFileKey, it.currentFileKey) }.toSet()
        val assetRefs = store.database.compositions().allArtwork().map { it.fileKey }.toSet()
        assertEquals(jsonRefs, store.jsonRoot.listFiles().orEmpty().map { it.name }.toSet())
        assertEquals(assetRefs, store.assetRoot.listFiles().orEmpty().map { it.name }.toSet())
    }
    private suspend fun expectStorage(code: ProjectStorageError, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (e: ProjectStorageException) { assertEquals(code, e.code) }
    }
    private suspend fun expectArchive(code: ArchiveError, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (e: ArchiveException) { assertEquals(code, e.code) }
    }
    private fun store(label: String): Store = Store(label).also(stores::add)
    private data class Rich(val characterId: String, val copyId: String, val jpegId: String, val derivedId: String)

    private inner class Store(label: String) {
        val name = "archive-$label-${UUID.randomUUID()}.db"
        val root = File(context.filesDir, name.removeSuffix(".db"))
        val jsonRoot = File(root, "json"); val assetRoot = File(root, "assets"); val stageRoot = File(root, "staging")
        val files = InterruptibleFiles(ManagedJsonFiles(jsonRoot))
        var database = createProjectDatabase(context, name)
        var lateDuplicateMetadataId: String? = null
        var lastBatch: LibraryRestoreBatch? = null
        var repository = repository()
        private fun repository(): RoomProjectRepository {
            val base = RoomCompositionIndex(database.compositions())
            val composition = object : CompositionIndex by base {
                override suspend fun restoreArchiveBatch(batch: LibraryRestoreBatch) {
                    lastBatch = batch
                    val badId = lateDuplicateMetadataId
                    val submitted = if (badId == null) batch else batch.copy(metadata = batch.metadata + ProjectMetadata(badId, 0, listOf("Must roll back")))
                    base.restoreArchiveBatch(submitted)
                }
            }
            return RoomProjectRepository(RoomProjectIndex(database.projects()), files,
                compositionIndex = composition, assetFiles = ManagedArtworkFiles(assetRoot), metadataIndex = RoomLibraryMetadataIndex(database.libraryMetadata()))
        }
        fun service() = LibraryBackupService(repository, stageRoot)
        fun reopen() { database.close(); database = createProjectDatabase(context, name); lateDuplicateMetadataId = null; repository = repository() }
    }
    private class InterruptibleFiles(private val delegate: ProjectFileStore) : ProjectFileStore {
        var ensured = 0; var stopAtEnsure: Int? = null
        override fun ensureSource(source: SourceEnvelope): String {
            ensured++; if (ensured == stopAtEnsure) throw CancellationException("Synthetic precommit cancellation")
            return delegate.ensureSource(source)
        }
        override fun read(fileKey: String, expectedSha256: String, expectedSizeBytes: Int) = delegate.read(fileKey, expectedSha256, expectedSizeBytes)
        override fun prune(referenced: Set<String>) = delegate.prune(referenced)
    }
    private companion object {
        val V1 = """{ "name":"Keeper", "description":"", "personality":"", "scenario":"", "first_mes":"", "mes_example":"", "unknown":{"number":1.2300e+09,"null":null} }""".toByteArray()
        val V2 = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"Keeper","description":"","personality":"","scenario":"","first_mes":"","mes_example":"","creator_notes":"","system_prompt":"","post_history_instructions":"","alternate_greetings":[],"tags":[],"creator":"","character_version":"","extensions":{"number":1.2300e+09,"null":null}}}""".toByteArray()
        val LORE = """{"entries":[],"extensions":{},"unknown":1.2300e+09}""".toByteArray()
        val FUTURE = """{"spec":"future_character","spec_version":"99","opaque":{"number":-0.00,"typed":false}}""".toByteArray()
        fun png(json: ByteArray): ByteArray {
            fun chunk(type: String, bytes: ByteArray): ByteArray {
                val kind = type.toByteArray(Charsets.US_ASCII)
                return java.nio.ByteBuffer.allocate(bytes.size + 12).putInt(bytes.size).put(kind).put(bytes)
                    .putInt(CRC32().apply { update(kind); update(bytes) }.value.toInt()).array()
            }
            val pixels = ByteArrayOutputStream().also { stream ->
                DeflaterOutputStream(stream).use { it.write(byteArrayOf(0, 1, 2, 3, -1)) }
            }.toByteArray()
            return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) +
                chunk("IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0)) +
                chunk("tEXt", "chara\u0000".toByteArray() + Base64.getEncoder().encode(json)) +
                chunk("IDAT", pixels) + chunk("IEND", byteArrayOf())
        }
    }
}

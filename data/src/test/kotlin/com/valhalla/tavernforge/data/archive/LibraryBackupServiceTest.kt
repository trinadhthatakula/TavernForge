package com.valhalla.tavernforge.data.archive

import com.valhalla.tavernforge.core.model.*
import com.valhalla.tavernforge.data.CompositionRecord
import com.valhalla.tavernforge.data.LoreLinkRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Synthetic opaque codec fixtures; real PNG/JPEG validity is covered by ArchiveRoomDeviceTest. */
class LibraryBackupServiceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exactOriginalCurrentUnknownValuesAssetsLinksAndTagsRoundTrip() = runBlocking<Unit> {
        val fixture = Fixture()
        val bytes = export(fixture)
        val service = LibraryBackupService(fixture, temporary.newFolder())
        service.prepareRestore(ByteArrayInputStream(bytes)).use { restored ->
            assertEquals(fixture.manifest.projects, restored.manifest.projects)
            assertEquals(fixture.manifest.artwork, restored.manifest.artwork)
            assertEquals(fixture.manifest.compositions, restored.manifest.compositions)
            assertEquals(fixture.manifest.links, restored.manifest.links)
            val project = restored.manifest.projects.first()
            assertEquals(7L, project.documentRevision)
            assertArrayEquals(fixture.original, restored.readBlob(project.original))
            assertArrayEquals(fixture.current, restored.readBlob(project.current))
            assertTrue(restored.readBlob(project.original).toString(Charsets.UTF_8).contains("1.2300e+09"))
            assertEquals("source-art", restored.manifest.artwork.last().summary.sourceArtworkId)
            assertEquals("native-candidate-provenance", restored.manifest.compositions.single().sourceCandidateId)
            assertEquals(listOf("Local tag"), restored.manifest.metadata.first().tags)
            ArchiveManifestCodec.validate(restored.manifest, ArchiveLimits()).forEach { (hash, ref) ->
                assertArrayEquals(fixture.blobs.getValue(hash), restored.readBlob(ref))
            }
        }
    }

    @Test fun sharedBlobsAreWrittenOnceAndNoPrivateStateEntryExists() = runBlocking<Unit> {
        val fixture = Fixture()
        val output = ByteArrayOutputStream()
        val summary = LibraryBackupService(fixture, temporary.newFolder()).export(output)
        val file = temporary.newFile().apply { writeBytes(output.toByteArray()) }
        val names = StoredZipArchive.inspect(file, ArchiveLimits()).keys.toList()
        assertEquals(fixture.blobs.size, summary.blobCount)
        assertEquals(fixture.blobs.size + 1, names.size)
        assertEquals("manifest.json", names.first())
        assertEquals(output.size().toLong(), summary.archiveBytes)
        assertTrue(names.all { it == "manifest.json" || it.matches(Regex("blobs/[a-f0-9]{64}")) })
        val manifestText = ArchiveManifestCodec.encode(fixture.manifest, ArchiveLimits()).toString(Charsets.UTF_8)
        listOf("credentials", "apiKey", "preferences", "grants", "journal").forEach { assertFalse(manifestText.contains("\"$it\"")) }
    }

    @Test fun restoreIsExplicitConsumesOnceAndAlwaysClosesStaging() = runBlocking<Unit> {
        val fixture = Fixture(); val bytes = export(fixture); val work = temporary.newFolder()
        val service = LibraryBackupService(fixture, work)
        var closed = false
        val input = object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } }
        val candidate = service.prepareRestore(input)
        assertFalse(closed); assertEquals(0, fixture.restoreCalls)
        assertEquals(1, work.listFiles()!!.size)
        val result = service.restore(candidate)
        assertEquals(fixture.manifest.projects.size, result.projectIds.size)
        assertEquals(1, fixture.restoreCalls)
        assertTrue(work.listFiles()!!.isEmpty())
        expect(ArchiveError.CLOSED) { candidate.readBlob(candidate.manifest.projects.first().original) }
        expect(ArchiveError.CLOSED) { service.restore(candidate) }
        assertEquals(1, fixture.restoreCalls)
        assertFalse(closed)
    }

    @Test fun repositoryFailureClosesStagingWithoutPretendingRestoreSucceeded() = runBlocking<Unit> {
        val fixture = Fixture(); val bytes = export(fixture); val work = temporary.newFolder()
        val service = LibraryBackupService(fixture, work)
        val candidate = service.prepareRestore(ByteArrayInputStream(bytes))
        fixture.restoreFailure = true
        expect(ArchiveError.IO_FAILURE) { service.restore(candidate) }
        assertTrue(work.listFiles()!!.isEmpty())
        expect(ArchiveError.CLOSED) { candidate.readBlob(candidate.manifest.projects.first().original) }
    }

    @Test fun corruptedOrTruncatedInputDoesNotReachRepositoryAndCleansStaging() = runBlocking<Unit> {
        val fixture = Fixture(); val bytes = export(fixture)
        val badInputs = listOf(bytes.copyOf(bytes.size - 3), bytes + byteArrayOf(1), bytes.copyOf().apply { this[0] = 0 })
        for (bad in badInputs) {
            val work = temporary.newFolder()
            try { LibraryBackupService(fixture, work).prepareRestore(ByteArrayInputStream(bad)); fail("Expected rejection") }
            catch (_: ArchiveException) { }
            assertTrue(work.listFiles()!!.isEmpty())
        }
        assertEquals(0, fixture.restoreCalls)
    }

    @Test fun blobMutationFailsCrcOrHashBeforeReview() = runBlocking<Unit> {
        val fixture = Fixture(); val bytes = export(fixture)
        val file = temporary.newFile().apply { writeBytes(bytes) }
        val entry = StoredZipArchive.inspect(file, ArchiveLimits()).values.first { it.name.startsWith("blobs/") }
        val corrupted = bytes.copyOf().apply { this[entry.dataOffset.toInt()] = (this[entry.dataOffset.toInt()].toInt() xor 1).toByte() }
        val work = temporary.newFolder()
        expect(ArchiveError.HASH_MISMATCH) { LibraryBackupService(fixture, work).prepareRestore(ByteArrayInputStream(corrupted)) }
        assertEquals(0, fixture.restoreCalls); assertTrue(work.listFiles()!!.isEmpty())
    }

    @Test fun stagedBytesAreRecheckedAtReadAndForeignReferencesAreRejected() = runBlocking<Unit> {
        val fixture = Fixture(); val bytes = export(fixture); val work = temporary.newFolder()
        val service = LibraryBackupService(fixture, work)
        service.prepareRestore(ByteArrayInputStream(bytes)).use { verified ->
            expect(ArchiveError.INVALID_MANIFEST) { verified.readBlob(ArchiveBlobRef("0".repeat(64), 1)) }
            val file = work.listFiles()!!.single().resolve("candidate.zip")
            val ref = verified.manifest.projects.first().original
            val entry = StoredZipArchive.inspect(file, ArchiveLimits()).getValue("blobs/${ref.sha256}")
            val changed = file.readBytes(); changed[entry.dataOffset.toInt()] = 0; file.writeBytes(changed)
            expect(ArchiveError.HASH_MISMATCH) { verified.readBlob(ref) }
        }
        assertTrue(work.listFiles()!!.isEmpty())
    }

    @Test fun inputOutputAndManifestLimitsFailBeforeAnyRestore() = runBlocking<Unit> {
        val fixture = Fixture(); val bytes = export(fixture)
        val inputWork = temporary.newFolder()
        expect(ArchiveError.LIMIT_EXCEEDED) {
            LibraryBackupService(fixture, inputWork, ArchiveLimits(maxArchiveBytes = bytes.size.toLong() - 1))
                .prepareRestore(ByteArrayInputStream(bytes))
        }
        assertTrue(inputWork.listFiles()!!.isEmpty())
        expect(ArchiveError.LIMIT_EXCEEDED) {
            LibraryBackupService(fixture, temporary.newFolder(), ArchiveLimits(maxArchiveBytes = 22)).export(ByteArrayOutputStream())
        }
        expect(ArchiveError.LIMIT_EXCEEDED) {
            LibraryBackupService(fixture, temporary.newFolder(), ArchiveLimits(maxManifestBytes = 100)).prepareRestore(ByteArrayInputStream(bytes))
        }
        assertEquals(0, fixture.restoreCalls)
    }

    @Test fun cancellationWhileStagingDeletesOnlyOwnedTemporaryFiles() = runBlocking<Unit> {
        val fixture = Fixture(); val archiveBytes = export(fixture); val work = temporary.newFolder()
        val unrelated = File(work, "keep.bin").apply { writeText("unrelated") }
        val input = object : ByteArrayInputStream(archiveBytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { throw CancellationException("synthetic cancellation") }
        }
        try { LibraryBackupService(fixture, work).prepareRestore(input); fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(setOf(unrelated.name), work.listFiles()!!.map { it.name }.toSet())
        assertEquals("unrelated", unrelated.readText()); assertEquals(0, fixture.restoreCalls)
    }

    @Test fun outputFailureDoesNotMutateLibraryOrCloseCallersStream() = runBlocking<Unit> {
        val fixture = Fixture(); var closed = false; var written = 0
        val output = object : OutputStream() {
            override fun write(value: Int) { if (++written > 90) throw IOException("synthetic provider failure") }
            override fun close() { closed = true }
        }
        expect(ArchiveError.IO_FAILURE) { LibraryBackupService(fixture, temporary.newFolder()).export(output) }
        assertFalse(closed); assertEquals(0, fixture.restoreCalls)
        var successfulClosed = false
        val successful = object : ByteArrayOutputStream() { override fun close() { successfulClosed = true; super.close() } }
        LibraryBackupService(fixture, temporary.newFolder()).export(successful)
        assertFalse(successfulClosed)
    }

    @Test fun incoherentSnapshotBytesFailBeforeCompletingAnArchive() = runBlocking<Unit> {
        val fixture = Fixture(); fixture.corruptSnapshot = true
        expect(ArchiveError.HASH_MISMATCH) { LibraryBackupService(fixture, temporary.newFolder()).export(ByteArrayOutputStream()) }
        assertEquals(0, fixture.restoreCalls)
    }

    @Test fun emptyLibraryStillProducesAValidExplicitEmptyBackup() = runBlocking<Unit> {
        val fixture = Fixture(empty = true); val bytes = ByteArrayOutputStream()
        val service = LibraryBackupService(fixture, temporary.newFolder())
        val summary = service.export(bytes)
        assertEquals(0, summary.projectCount); assertEquals(0, summary.artworkCount); assertEquals(0, summary.blobCount)
        service.prepareRestore(ByteArrayInputStream(bytes.toByteArray())).use { assertTrue(it.manifest.projects.isEmpty()) }
    }

    @Test fun abandonedStageCleanupPreservesActiveHandlesAndUnrelatedDirectories() = runBlocking<Unit> {
        val fixture = Fixture(); val bytes = export(fixture); val work = temporary.newFolder()
        val service = LibraryBackupService(fixture, work)
        val active = service.prepareRestore(ByteArrayInputStream(bytes))
        val abandoned = File(work, "restore-abandoned").apply { mkdir(); resolve("candidate.zip").writeBytes(byteArrayOf(0)) }
        val unexpected = File(work, "restore-unrelated").apply { mkdir(); resolve("keep.bin").writeText("keep") }
        val ordinary = File(work, "ordinary-directory").apply { mkdir(); resolve("candidate.zip").writeText("keep") }
        try {
            service.cleanupAbandonedStaging()
            assertFalse(abandoned.exists()); assertTrue(unexpected.exists()); assertTrue(ordinary.exists())
            assertArrayEquals(fixture.original, active.readBlob(active.manifest.projects.first().original))
            service.prepareRestore(ByteArrayInputStream(bytes)).use { second ->
                assertArrayEquals(fixture.original, active.readBlob(active.manifest.projects.first().original))
                assertArrayEquals(fixture.original, second.readBlob(second.manifest.projects.first().original))
            }
        } finally { active.close() }
        assertEquals(setOf(unexpected.name, ordinary.name), work.listFiles()!!.map { it.name }.toSet())
    }

    private suspend fun export(fixture: Fixture): ByteArray = ByteArrayOutputStream().also {
        LibraryBackupService(fixture, temporary.newFolder()).export(it)
    }.toByteArray()
    private suspend fun expect(code: ArchiveError, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (e: ArchiveException) { assertEquals(code, e.code) }
    }

    internal class Fixture(empty: Boolean = false) : LibraryArchiveGateway {
        val original = """{ "name":"Keeper", "unknown":{"number":1.2300e+09,"negativeZero":-0.00,"null":null,"typed":false} }""".toByteArray()
        val current = original.toString(Charsets.UTF_8).replace("Keeper", "Edited keeper").toByteArray()
        private val lore = """{"entries":[],"extensions":{"unchanged":true}}""".toByteArray()
        private val png = byteArrayOf(-119, 80, 78, 71, 1, 2, 3)
        private val jpeg = byteArrayOf(-1, -40, 7, 8, -1, -39)
        private val derived = byteArrayOf(-119, 80, 78, 71, 4, 5, 6)
        val blobs = if (empty) emptyMap() else listOf(original, current, lore, png, jpeg, derived).associateBy(StoredZipArchive::sha256)
        private fun ref(bytes: ByteArray) = ArchiveBlobRef(StoredZipArchive.sha256(bytes), bytes.size)
        private fun project(id: String, bytes: ByteArray, format: SourceFormat, edited: ByteArray = bytes, revision: Long = 0) =
            ArchiveProject(ProjectSummary(id, "Local $id", "$id.json", format, null, true, 100, 200,
                StoredZipArchive.sha256(bytes), bytes.size), "application/json", revision, ref(bytes), ref(edited))
        private fun art(id: String, bytes: ByteArray, media: String, parent: String? = null) = ArchiveArtwork(
            ArtworkSummary(id, "$id.${if (media == "image/jpeg") "jpg" else "png"}", StoredZipArchive.sha256(bytes), bytes.size,
                2, 3, false, 100, media, parent, if (parent == null) null else "android-jpeg-png-1"), ref(bytes))
        val manifest = if (empty) LibraryArchiveManifest(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 100)
        else LibraryArchiveManifest(
            listOf(project("character", original, SourceFormat.V1, current, 7), project("shared", original, SourceFormat.V1), project("lore", lore, SourceFormat.LOREBOOK_V2)),
            listOf(art("original-png", png, "image/png"), art("source-art", jpeg, "image/jpeg"), art("derived-art", derived, "image/png", "source-art")),
            listOf(CompositionRecord("character", 5, "derived-art", "original-png", "native-candidate-provenance")),
            listOf(LoreLinkRecord("character", "lore", 3, StoredZipArchive.sha256(lore))),
            listOf(ProjectMetadata("character", 4, listOf("Local tag"))), 100)
        var restoreCalls = 0
        var restoreFailure = false
        var corruptSnapshot = false
        override suspend fun <T> withArchiveSnapshot(action: suspend (LibraryArchiveSnapshot) -> T): T =
            action(LibraryArchiveSnapshot(manifest) { if (corruptSnapshot) byteArrayOf(0) else blobs.getValue(it.sha256).copyOf() })
        override suspend fun restoreArchive(archive: VerifiedLibraryArchive): ArchiveRestoreResult {
            archive.consume(); restoreCalls++
            if (restoreFailure) throw ArchiveException(ArchiveError.IO_FAILURE)
            ArchiveManifestCodec.validate(archive.manifest, ArchiveLimits()).values.forEach { archive.readBlob(it) }
            return ArchiveRestoreResult(archive.manifest.projects.associate { it.summary.id to "new-project-${it.summary.id}" },
                archive.manifest.artwork.associate { it.summary.id to "new-artwork-${it.summary.id}" })
        }
    }
}

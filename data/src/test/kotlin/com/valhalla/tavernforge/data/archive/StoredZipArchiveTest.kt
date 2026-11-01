package com.valhalla.tavernforge.data.archive

import com.valhalla.tavernforge.core.model.*
import com.valhalla.tavernforge.data.CompositionRecord
import com.valhalla.tavernforge.data.LoreLinkRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Independent byte-level ZIP corruption and strict manifest graph/bounds checks. */
class StoredZipArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun compressionEncryptionZip64DescriptorsAndExtraFieldsAreRejected() = runBlocking<Unit> {
        val fixture = LibraryBackupServiceTest.Fixture(); val bytes = packed(fixture)
        val central = u32(bytes, bytes.size - 22 + 16).toInt()
        val cases = listOf(
            bytes.copyOf().apply { put16(this, 8, 8); put16(this, central + 10, 8) },
            bytes.copyOf().apply { put16(this, 6, 0x801); put16(this, central + 8, 0x801) },
            bytes.copyOf().apply { put16(this, 6, 0x808); put16(this, central + 8, 0x808) },
            bytes.copyOf().apply { put16(this, 4, 45); put16(this, central + 6, 45) },
            bytes.copyOf().apply { put16(this, 28, 1); put16(this, central + 30, 1) },
            bytes.copyOf().apply { put16(this, size - 2, 1) },
            bytes.copyOf().apply { put16(this, central + 36, 1) },
        )
        cases.forEach { rejected(fixture, it) }
    }

    @Test fun localHeaderMismatchAndCentralOffsetGapsAreRejected() = runBlocking<Unit> {
        val fixture = LibraryBackupServiceTest.Fixture(); val bytes = packed(fixture)
        val central = u32(bytes, bytes.size - 22 + 16).toInt()
        rejected(fixture, bytes.copyOf().apply { this[14] = (this[14].toInt() xor 1).toByte() })
        rejected(fixture, bytes.copyOf().apply { put32(this, central + 42, 1) })
        rejected(fixture, bytes.copyOf().apply { put32(this, size - 22 + 16, central.toLong() + 1) })
        rejected(fixture, bytes.copyOf(bytes.size - 1))
        rejected(fixture, bytes + byteArrayOf(0, 0))
    }

    @Test fun traversalAbsoluteBackslashAndUnknownEntryNamesAreRejectedWithoutExtraction() = runBlocking<Unit> {
        val fixture = LibraryBackupServiceTest.Fixture(); val bytes = packed(fixture)
        val name = "blobs/${fixture.blobs.keys.first()}"
        val names = listOf("../" + "x".repeat(67), "/" + "x".repeat(69), "blobs\\" + "a".repeat(64),
            "other/" + "a".repeat(64), "x".repeat(69) + "/")
        names.forEach { replacement ->
            assertEquals(name.length, replacement.length)
            rejected(fixture, replacedName(bytes, name, replacement))
        }
    }

    @Test fun duplicateNamesMissingAndUnlistedBlobsAreRejected() = runBlocking<Unit> {
        val fixture = LibraryBackupServiceTest.Fixture(); val bytes = packed(fixture)
        val hashes = fixture.blobs.keys.toList()
        rejected(fixture, replacedName(bytes, "blobs/${hashes[1]}", "blobs/${hashes[0]}"))
        rejected(fixture, packed(fixture, omittedHash = hashes.first()))
        rejected(fixture, packed(fixture, unlisted = true))
    }

    @Test fun duplicateJsonNamesUnknownManifestFieldsAndFutureVersionsFailClosed() = runBlocking<Unit> {
        val fixture = LibraryBackupServiceTest.Fixture()
        val manifest = ArchiveManifestCodec.encode(fixture.manifest, ArchiveLimits()).toString(Charsets.UTF_8)
        rejected(fixture, packed(fixture, manifestBytes = manifest.replace("\"version\":1", "\"version\":1,\"version\":1").toByteArray()))
        val root = Json.parseToJsonElement(manifest).jsonObject
        rejected(fixture, packed(fixture, manifestBytes = JsonObject(root + ("credentials" to JsonObject(emptyMap()))).toString().toByteArray()))
        rejected(fixture, packed(fixture, manifestBytes = JsonObject(root + ("version" to JsonPrimitive("1"))).toString().toByteArray()))
        val future = packed(fixture, manifestBytes = JsonObject(root + ("version" to JsonPrimitive(2))).toString().toByteArray())
        rejected(fixture, future, ArchiveError.UNSUPPORTED_VERSION)
    }

    @Test fun brokenReferencesDuplicateIdsAndInvalidDerivationsFailBeforeExport() {
        val m = LibraryBackupServiceTest.Fixture().manifest
        val derived = m.artwork.last()
        val cases = listOf(
            copy(m, projects = m.projects + m.projects.first()),
            copy(m, artwork = m.artwork + m.artwork.first()),
            copy(m, compositions = listOf(CompositionRecord("missing"))),
            copy(m, compositions = listOf(m.compositions.first().copy(artworkId = "missing"))),
            copy(m, compositions = listOf(m.compositions.first().copy(sourceCandidateId = null))),
            copy(m, links = listOf(LoreLinkRecord("character", "missing"))),
            copy(m, links = m.links + m.links.first()),
            copy(m, metadata = listOf(ProjectMetadata("missing", 0, listOf("Tag")))),
            copy(m, metadata = listOf(ProjectMetadata("character", 0, listOf("Tag", "tag")))),
            copy(m, artwork = m.artwork.dropLast(1) + derived.copy(summary = derived.summary.copy(sourceArtworkId = "missing"))),
            copy(m, artwork = m.artwork.dropLast(1) + derived.copy(summary = derived.summary.copy(sourceArtworkId = derived.summary.id))),
            copy(m, artwork = m.artwork.dropLast(1) + derived.copy(summary = derived.summary.copy(sourceArtworkId = "original-png"))),
            copy(m, artwork = m.artwork.dropLast(1) + derived.copy(summary = derived.summary.copy(transformation = "invented-policy"))),
        )
        cases.forEach { candidate ->
            try { ArchiveManifestCodec.encode(candidate, ArchiveLimits()); fail("Expected invalid graph") }
            catch (e: ArchiveException) { assertEquals(ArchiveError.INVALID_MANIFEST, e.code) }
        }
    }

    @Test fun aggregateCountsAndJsonAssetSizeLimitsAreCheckedFromManifest() {
        val fixture = LibraryBackupServiceTest.Fixture(); val manifest = fixture.manifest
        val limits = listOf(ArchiveLimits(maxProjects = 1), ArchiveLimits(maxArtwork = 1), ArchiveLimits(maxEntries = 1),
            ArchiveLimits(maxJsonBytes = 1), ArchiveLimits(maxArtworkBytes = 1), ArchiveLimits(maxTotalBlobBytes = 1))
        limits.forEach { limit ->
            try { ArchiveManifestCodec.validate(manifest, limit); fail("Expected configured limit") }
            catch (e: ArchiveException) { assertEquals(ArchiveError.LIMIT_EXCEEDED, e.code) }
        }
        val first = manifest.projects.first()
        val sizeConflict = first.copy(current = ArchiveBlobRef(first.original.sha256, first.original.sizeBytes + 1))
        try { ArchiveManifestCodec.validate(copy(manifest, projects = listOf(sizeConflict) + manifest.projects.drop(1)), ArchiveLimits()); fail("Expected inconsistent digest size rejection") }
        catch (e: ArchiveException) { assertEquals(ArchiveError.INVALID_MANIFEST, e.code) }
    }

    @Test fun displayFilenamesNeverBecomeArchivePathsAndIdNamespacesRemainSeparate() {
        val m = LibraryBackupServiceTest.Fixture().manifest
        val projects = m.projects.map { p -> p.copy(summary = p.summary.copy(sourceFileName = "../../private/original.json")) }
        // A project and artwork may share an old ID: restore must allocate through separate maps.
        val artwork = m.artwork.map { a -> if (a.summary.id == "original-png") a.copy(summary = a.summary.copy(id = "character", fileName = "/outside.png")) else a }
        val compositions = m.compositions.map { it.copy(sourcePngId = "character") }
        val manifest = copy(m, projects = projects, artwork = artwork, compositions = compositions)
        val refs = ArchiveManifestCodec.validate(manifest, ArchiveLimits())
        assertTrue(refs.keys.all { it.matches(Regex("[a-f0-9]{64}")) })
        val decoded = ArchiveManifestCodec.decode(ArchiveManifestCodec.encode(manifest, ArchiveLimits()), ArchiveLimits())
        assertEquals("../../private/original.json", decoded.projects.first().summary.sourceFileName)
        assertEquals("character", decoded.artwork.first().summary.id)
        assertEquals("/outside.png", decoded.artwork.first().summary.fileName)
        assertEquals("character", decoded.compositions.first().sourcePngId)
    }

    private suspend fun rejected(fixture: LibraryBackupServiceTest.Fixture, bytes: ByteArray, code: ArchiveError? = null) {
        val work = temporary.newFolder()
        try { LibraryBackupService(fixture, work).prepareRestore(ByteArrayInputStream(bytes)); fail("Expected invalid archive") }
        catch (e: ArchiveException) { code?.let { assertEquals(it, e.code) } }
        assertEquals(0, fixture.restoreCalls)
        assertTrue(work.listFiles()!!.isEmpty())
    }
    private fun packed(fixture: LibraryBackupServiceTest.Fixture, manifestBytes: ByteArray? = null,
        omittedHash: String? = null, unlisted: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream(); val writer = StoredZipArchive.Writer(output, ArchiveLimits())
        writer.add("manifest.json", manifestBytes ?: ArchiveManifestCodec.encode(fixture.manifest, ArchiveLimits()))
        fixture.blobs.forEach { (hash, bytes) -> if (hash != omittedHash) writer.add("blobs/$hash", bytes) }
        if (unlisted) writer.add("blobs/${"f".repeat(64)}", byteArrayOf(1))
        writer.finish(); return output.toByteArray()
    }
    private fun copy(m: LibraryArchiveManifest, projects: List<ArchiveProject> = m.projects, artwork: List<ArchiveArtwork> = m.artwork,
        compositions: List<CompositionRecord> = m.compositions, links: List<LoreLinkRecord> = m.links,
        metadata: List<ProjectMetadata> = m.metadata) = LibraryArchiveManifest(projects, artwork, compositions, links, metadata, m.createdAtEpochMillis)
    private fun replacedName(bytes: ByteArray, name: String, replacement: String): ByteArray {
        val from = name.toByteArray(Charsets.US_ASCII); val to = replacement.toByteArray(Charsets.US_ASCII)
        require(from.size == to.size)
        val result = bytes.copyOf(); var replaced = 0
        for (offset in 0..result.size - from.size) {
            if (from.indices.all { result[offset + it] == from[it] }) {
                to.copyInto(result, offset); replaced++
            }
        }
        assertEquals("Must mutate one local and one central name", 2, replaced)
        return result
    }
    private fun u32(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { value, i -> value or ((bytes[offset + i].toLong() and 255) shl (i * 8)) }
    private fun put16(bytes: ByteArray, offset: Int, value: Int) { bytes[offset] = value.toByte(); bytes[offset + 1] = (value ushr 8).toByte() }
    private fun put32(bytes: ByteArray, offset: Int, value: Long) { repeat(4) { bytes[offset + it] = (value ushr (it * 8)).toByte() } }
}

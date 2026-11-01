package com.valhalla.tavernforge.data.archive

import com.valhalla.tavernforge.core.model.ArtworkSummary
import com.valhalla.tavernforge.core.model.ProjectMetadata
import com.valhalla.tavernforge.core.model.ProjectSummary
import com.valhalla.tavernforge.data.CompositionRecord
import com.valhalla.tavernforge.data.LoreLinkRecord
import java.io.Closeable
import java.util.Collections

data class ArchiveLimits(
    val maxArchiveBytes: Long = 256L * 1024 * 1024,
    val maxTotalBlobBytes: Long = 256L * 1024 * 1024,
    val maxEntries: Int = 4096,
    val maxProjects: Int = 1000,
    val maxArtwork: Int = 1000,
    val maxJsonBytes: Int = 4 * 1024 * 1024,
    val maxArtworkBytes: Int = 32 * 1024 * 1024,
    val maxManifestBytes: Int = 2 * 1024 * 1024,
) {
    init {
        require(maxArchiveBytes in 22..256L * 1024 * 1024)
        require(maxTotalBlobBytes in 1..256L * 1024 * 1024)
        require(maxEntries in 1..4096 && maxProjects in 1..1000 && maxArtwork in 1..1000)
        require(maxJsonBytes in 1..4 * 1024 * 1024 && maxArtworkBytes in 1..32 * 1024 * 1024)
        require(maxManifestBytes in 1..2 * 1024 * 1024)
    }
}

enum class ArchiveError { INVALID_ARCHIVE, UNSUPPORTED_VERSION, LIMIT_EXCEEDED, HASH_MISMATCH, INVALID_MANIFEST, IO_FAILURE, CLOSED, ALREADY_RESTORED }
class ArchiveException(val code: ArchiveError) : Exception(code.name)
data class ArchiveBlobRef(val sha256: String, val sizeBytes: Int)
data class ArchiveProject(val summary: ProjectSummary, val mediaType: String, val documentRevision: Long,
    val original: ArchiveBlobRef, val current: ArchiveBlobRef)
data class ArchiveArtwork(val summary: ArtworkSummary, val blob: ArchiveBlobRef)

class LibraryArchiveManifest(projects: List<ArchiveProject>, artwork: List<ArchiveArtwork>,
    compositions: List<CompositionRecord>, links: List<LoreLinkRecord>, metadata: List<ProjectMetadata>,
    val createdAtEpochMillis: Long) {
    val projects: List<ArchiveProject> = Collections.unmodifiableList(projects.toList())
    val artwork: List<ArchiveArtwork> = Collections.unmodifiableList(artwork.toList())
    val compositions: List<CompositionRecord> = Collections.unmodifiableList(compositions.toList())
    val links: List<LoreLinkRecord> = Collections.unmodifiableList(links.toList())
    val metadata: List<ProjectMetadata> = Collections.unmodifiableList(metadata.toList())
    override fun toString(): String = "LibraryArchiveManifest(${projects.size} projects, ${artwork.size} artwork)"
}

class LibraryArchiveSnapshot(val manifest: LibraryArchiveManifest, private val reader: suspend (ArchiveBlobRef) -> ByteArray) {
    suspend fun readBlob(reference: ArchiveBlobRef): ByteArray = reader(reference)
}
data class ArchiveExportSummary(val projectCount: Int, val artworkCount: Int, val blobCount: Int, val archiveBytes: Long)
data class ArchiveRestoreResult(val projectIds: Map<String, String>, val artworkIds: Map<String, String>)

interface LibraryArchiveGateway {
    suspend fun <T> withArchiveSnapshot(action: suspend (LibraryArchiveSnapshot) -> T): T
    suspend fun restoreArchive(archive: VerifiedLibraryArchive): ArchiveRestoreResult
}

/** Owns verified private staging; closing never touches library data. */
class VerifiedLibraryArchive internal constructor(val manifest: LibraryArchiveManifest,
    private val reader: (ArchiveBlobRef) -> ByteArray, private val cleanup: () -> Unit) : Closeable {
    private var closed = false
    private var consumed = false
    @Synchronized fun readBlob(reference: ArchiveBlobRef): ByteArray {
        if (closed) throw ArchiveException(ArchiveError.CLOSED)
        return reader(reference)
    }
    @Synchronized internal fun consume() {
        if (closed) throw ArchiveException(ArchiveError.CLOSED)
        if (consumed) throw ArchiveException(ArchiveError.ALREADY_RESTORED)
        consumed = true
    }
    @Synchronized override fun close() {
        if (!closed) { closed = true; cleanup() }
    }
    override fun toString(): String = "VerifiedLibraryArchive(redacted)"
}

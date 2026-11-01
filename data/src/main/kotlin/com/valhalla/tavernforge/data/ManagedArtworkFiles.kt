package com.valhalla.tavernforge.data

import com.valhalla.tavernforge.core.model.JsonLimits
import com.valhalla.tavernforge.core.model.SourceEnvelope
import java.io.File

/** One immutable asset directory; MIME determines extension, never a provider name. */
class ManagedArtworkFiles(root: File) : ProjectFileStore {
    private val png = ManagedJsonFiles(root, JsonLimits(maxBytes = 32 * 1024 * 1024), "png", "image/png")
    private val jpeg = ManagedJsonFiles(root, JsonLimits(maxBytes = 32 * 1024 * 1024), "jpg", "image/jpeg")
    override fun ensureSource(source: SourceEnvelope): String = when (source.mediaType) {
        "image/png" -> png.ensureSource(source)
        "image/jpeg" -> jpeg.ensureSource(source)
        else -> invalid()
    }
    override fun read(fileKey: String, expectedSha256: String, expectedSizeBytes: Int): SourceEnvelope = when {
        fileKey.endsWith(".png") -> png.read(fileKey, expectedSha256, expectedSizeBytes)
        fileKey.endsWith(".jpg") -> jpeg.read(fileKey, expectedSha256, expectedSizeBytes)
        else -> invalid()
    }
    override fun prune(referenced: Set<String>) {
        if (referenced.any { !KEY.matches(it) }) invalid()
        png.prune(referenced.filterTo(mutableSetOf()) { it.endsWith(".png") })
        jpeg.prune(referenced.filterTo(mutableSetOf()) { it.endsWith(".jpg") })
    }
    private fun invalid(): Nothing = throw ProjectStorageException(ProjectStorageError.CORRUPT_SOURCE, "The artwork type or private reference is invalid. Its files have been retained.")
    private companion object { val KEY = Regex("[a-f0-9]{64}\\.(png|jpg)") }
}

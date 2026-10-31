package com.valhalla.tavernforge.data

import com.valhalla.tavernforge.core.model.JsonLimits
import com.valhalla.tavernforge.core.model.SourceEnvelope
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Immutable, content-addressed originals and current snapshots in one app-private directory. All callers must use the
 * repository writer lock. Names supplied by document providers are never used as filesystem paths.
 */
class ManagedJsonFiles(root: File, private val limits: JsonLimits = JsonLimits()) : ProjectFileStore {
    private val directory = root.toPath().toAbsolutePath().normalize()

    override fun ensureSource(source: SourceEnvelope): String {
        if (source.sizeBytes !in 1..limits.maxBytes) invalid("The source exceeds the configured storage limit.")
        ensureDirectory()
        val key = "${source.sha256}.json"
        val target = path(key)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            read(key, source.sha256, source.sizeBytes)
            // This may be an orphan from an interrupted attempt whose directory sync failed.
            FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
            syncDirectory(directory)
            return key
        }
        val temporary = Files.createTempFile(directory, ".pending-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(source.bytes())
                output.fd.sync()
            }
            // Verify the actual bytes on disk before establishing a durable reference.
            readPath(temporary, source.sha256, source.sizeBytes)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(directory)
            return key
        } finally {
            // If finalization succeeded but directory sync failed, recovery sees an unreferenced file.
            // A best-effort temp cleanup must not hide the original write/verification error.
            try { Files.deleteIfExists(temporary) } catch (_: Exception) { }
        }
    }

    override fun read(fileKey: String, expectedSha256: String, expectedSizeBytes: Int): SourceEnvelope {
        if (!HASH.matches(expectedSha256) || fileKey != "$expectedSha256.json") corrupt("The saved source reference is invalid.")
        ensureDirectory()
        return readPath(path(fileKey), expectedSha256, expectedSizeBytes)
    }

    override fun prune(referenced: Set<String>) {
        // Validate every reference before deleting anything, even when the invalid one sorts last.
        if (referenced.any { !SOURCE_KEY.matches(it) }) corrupt("The library contains an invalid source reference. No files were removed.")
        ensureDirectory()
        Files.newDirectoryStream(directory).use { children ->
            children.forEach { child ->
                val name = child.fileName.toString()
                val orphanSource = SOURCE_KEY.matches(name) && name !in referenced
                val temporary = name.startsWith(".pending-") && name.endsWith(".tmp")
                // Never recurse or touch unknown files/directories. Deleting a symlink deletes only it.
                if ((orphanSource || temporary) && !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(child)
                }
            }
        }
    }

    private fun readPath(target: Path, expectedSha256: String, expectedSizeBytes: Int): SourceEnvelope {
        if (expectedSizeBytes !in 1..limits.maxBytes) corrupt("The saved source size is outside the configured limit.")
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) corrupt("The saved source is missing or is not a regular file. Its library record has been retained.")
        FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (channel.size() != expectedSizeBytes.toLong()) corrupt("The saved source size does not match its library record.")
            val bytes = ByteArray(expectedSizeBytes)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) corrupt("The saved source ended before its recorded size.")
            }
            if (channel.read(ByteBuffer.allocate(1)) != -1) corrupt("The saved source grew beyond its recorded size.")
            val source = SourceEnvelope(bytes)
            if (source.sha256 != expectedSha256) corrupt("The saved source failed its integrity check. Its bytes have been retained.")
            return source
        }
    }

    private fun path(key: String): Path {
        if (!SOURCE_KEY.matches(key)) corrupt("The saved source reference is invalid.")
        return directory.resolve(key)
    }

    private fun ensureDirectory() {
        if (Files.isSymbolicLink(directory)) corrupt("The managed source directory is not a safe directory.")
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(directory)
            // Persist creation of the managed directory before a database row can refer into it.
            directory.parent?.let(::syncDirectory)
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) corrupt("The managed source location is not a directory.")
    }

    private fun syncDirectory(path: Path) {
        // Android's Linux filesystem supports fsync on a read-only directory descriptor. If this
        // platform/filesystem cannot sync it, fail before DB commit instead of claiming durability.
        FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    }

    private fun corrupt(message: String): Nothing = throw ProjectStorageException(ProjectStorageError.CORRUPT_SOURCE, message)
    private fun invalid(message: String): Nothing = throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, message)

    private companion object {
        val HASH = Regex("[a-f0-9]{64}")
        val SOURCE_KEY = Regex("[a-f0-9]{64}\\.json")
    }
}

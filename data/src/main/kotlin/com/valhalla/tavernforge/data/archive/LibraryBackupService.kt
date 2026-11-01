package com.valhalla.tavernforge.data.archive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Picker streams remain caller-owned. Only uniquely-created staging directories are cleaned. */
class LibraryBackupService(private val gateway: LibraryArchiveGateway, private val workDirectory: File,
    private val limits: ArchiveLimits = ArchiveLimits()) {
    suspend fun export(output: OutputStream): ArchiveExportSummary = withContext(Dispatchers.IO) {
        safeIo {
            gateway.withArchiveSnapshot { snapshot ->
                val blobs = ArchiveManifestCodec.validate(snapshot.manifest, limits)
                val manifest = ArchiveManifestCodec.encode(snapshot.manifest, limits)
                val writer = StoredZipArchive.Writer(output, limits)
                currentCoroutineContext().ensureActive()
                writer.add("manifest.json", manifest)
                for ((hash, reference) in blobs.toSortedMap()) {
                    currentCoroutineContext().ensureActive()
                    val bytes = snapshot.readBlob(reference)
                    if (bytes.size != reference.sizeBytes || StoredZipArchive.sha256(bytes) != hash) throw ArchiveException(ArchiveError.HASH_MISMATCH)
                    writer.add("blobs/$hash", bytes)
                }
                currentCoroutineContext().ensureActive()
                val length = writer.finish()
                ArchiveExportSummary(snapshot.manifest.projects.size, snapshot.manifest.artwork.size, blobs.size, length)
            }
        }
    }

    suspend fun prepareRestore(input: InputStream): VerifiedLibraryArchive {
        var stage: File? = null
        try {
            return withContext(Dispatchers.IO) { safeIo {
                val directory = createStage()
                stage = directory
                val archive = File(directory, "candidate.zip")
                FileOutputStream(archive).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > limits.maxArchiveBytes) throw ArchiveException(ArchiveError.LIMIT_EXCEEDED)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
                val entries = StoredZipArchive.inspect(archive, limits)
                val manifestEntry = entries["manifest.json"] ?: throw ArchiveException(ArchiveError.INVALID_ARCHIVE)
                if (manifestEntry.size > limits.maxManifestBytes) throw ArchiveException(ArchiveError.LIMIT_EXCEEDED)
                val manifest = ArchiveManifestCodec.decode(StoredZipArchive.read(archive, manifestEntry), limits)
                val blobs = ArchiveManifestCodec.validate(manifest, limits)
                if (entries.keys != blobs.keys.map { "blobs/$it" }.toSet() + "manifest.json") throw ArchiveException(ArchiveError.INVALID_ARCHIVE)
                for ((hash, ref) in blobs) {
                    currentCoroutineContext().ensureActive()
                    val entry = entries.getValue("blobs/$hash")
                    if (entry.size != ref.sizeBytes) throw ArchiveException(ArchiveError.HASH_MISMATCH)
                    StoredZipArchive.read(archive, entry, hash)
                }
                currentCoroutineContext().ensureActive()
                VerifiedLibraryArchive(manifest, reader = { reference ->
                    if (blobs[reference.sha256] != reference) throw ArchiveException(ArchiveError.INVALID_MANIFEST)
                    try { StoredZipArchive.read(archive, entries.getValue("blobs/${reference.sha256}"), reference.sha256) }
                    catch (e: ArchiveException) { throw e }
                    catch (_: Exception) { throw ArchiveException(ArchiveError.IO_FAILURE) }
                }, cleanup = { cleanupStage(directory) })
            } }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { stage?.let(::cleanupStage) }
            throw failure
        }
    }

    suspend fun restore(archive: VerifiedLibraryArchive): ArchiveRestoreResult = try { gateway.restoreArchive(archive) }
    finally { archive.close() }

    suspend fun cleanupAbandonedStaging() = withContext(Dispatchers.IO) {
        safeIo { synchronized(stagingLock) { ensureWorkDirectory(); cleanupAbandonedLocked() } }
    }
    private fun createStage(): File = synchronized(stagingLock) {
        ensureWorkDirectory(); cleanupAbandonedLocked()
        Files.createTempDirectory(workDirectory.toPath(), "restore-").toFile().also { activeStages.add(it.toPath().toAbsolutePath().normalize()) }
    }
    private fun ensureWorkDirectory() {
        Files.createDirectories(workDirectory.toPath())
        if (Files.isSymbolicLink(workDirectory.toPath()) || !Files.isDirectory(workDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)) throw ArchiveException(ArchiveError.IO_FAILURE)
    }
    private fun cleanupAbandonedLocked() {
        Files.newDirectoryStream(workDirectory.toPath()).use { paths ->
            for (path in paths) {
                if (path.fileName.toString().matches(Regex("restore-[A-Za-z0-9_-]+")) &&
                    path.toAbsolutePath().normalize() !in activeStages && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) deleteOwnedStage(path)
            }
        }
    }
    private fun cleanupStage(directory: File) = synchronized(stagingLock) {
        val path = directory.toPath().toAbsolutePath().normalize()
        activeStages.remove(path)
        runCatching { deleteOwnedStage(path) }
        Unit
    }
    private fun deleteOwnedStage(path: Path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return
        val contents = Files.newDirectoryStream(path).use { it.toList() }
        if (contents.any { it.fileName.toString() != "candidate.zip" || !Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }) return
        contents.forEach { Files.deleteIfExists(it) }
        Files.deleteIfExists(path)
    }
    private companion object {
        val stagingLock = Any()
        val activeStages = mutableSetOf<Path>()
    }
    private suspend fun <T> safeIo(action: suspend () -> T): T = try { action() }
    catch (e: ArchiveException) { throw e }
    catch (e: CancellationException) { throw e }
    catch (_: java.io.EOFException) { throw ArchiveException(ArchiveError.INVALID_ARCHIVE) }
    catch (_: java.io.IOException) { throw ArchiveException(ArchiveError.IO_FAILURE) }
    catch (_: SecurityException) { throw ArchiveException(ArchiveError.IO_FAILURE) }
}

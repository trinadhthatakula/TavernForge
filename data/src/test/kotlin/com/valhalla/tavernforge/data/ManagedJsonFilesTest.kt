package com.valhalla.tavernforge.data

import com.valhalla.tavernforge.core.model.JsonLimits
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ManagedJsonFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun failure(code: ProjectStorageError, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: ProjectStorageException) {
            assertEquals(code, error.code)
            assertTrue(error.message.orEmpty().isNotBlank())
        }
    }

    @Test fun finalFileHasExactBytesAndRepeatedSaveDoesNotCreateAnotherCopy() {
        val root = temporary.newFolder("managed")
        val store = ManagedJsonFiles(root)
        val original = source()
        val key = store.ensureSource(original)
        assertEquals(key, store.ensureSource(original))
        assertEquals(listOf(key), root.listFiles().orEmpty().map { it.name })
        assertArrayEquals(preservedV1, File(root, key).readBytes())
        assertArrayEquals(preservedV1, store.read(key, original.sha256, original.sizeBytes).bytes())
    }

    @Test fun writeAndReadLimitsAreEnforcedBeforeLargeAllocation() {
        val root = temporary.newFolder("managed")
        val bytes = "{}\n".encodeToByteArray()
        val original = source(bytes)
        val exact = ManagedJsonFiles(root, JsonLimits(maxBytes = bytes.size))
        val key = exact.ensureSource(original)
        assertArrayEquals(bytes, exact.read(key, original.sha256, bytes.size).bytes())
        val small = ManagedJsonFiles(root, JsonLimits(maxBytes = bytes.size - 1))
        failure(ProjectStorageError.INVALID_INPUT) { small.ensureSource(original) }
        failure(ProjectStorageError.CORRUPT_SOURCE) { small.read(key, original.sha256, bytes.size) }
        failure(ProjectStorageError.CORRUPT_SOURCE) { exact.read(key, original.sha256, Int.MAX_VALUE) }
    }

    @Test fun equalLengthCorruptionFailsHashCheckAndIsNeverOverwrittenSilently() {
        val root = temporary.newFolder("managed")
        val store = ManagedJsonFiles(root)
        val original = source()
        val key = store.ensureSource(original)
        val changed = original.bytes().also { it[0] = '['.code.toByte() }
        File(root, key).writeBytes(changed)
        failure(ProjectStorageError.CORRUPT_SOURCE) { store.read(key, original.sha256, original.sizeBytes) }
        failure(ProjectStorageError.CORRUPT_SOURCE) { store.ensureSource(original) }
        assertArrayEquals(changed, File(root, key).readBytes())
    }

    @Test fun traversalKeysAndInvalidReferenceSetsCannotReadOrDeleteExternalFiles() {
        val root = temporary.newFolder("managed")
        val outside = temporary.newFile("outside.json").also { it.writeBytes(preservedV1) }
        val store = ManagedJsonFiles(root)
        val original = source()
        val key = store.ensureSource(original)
        listOf("../outside.json", outside.absolutePath, "$key/../outside.json").forEach {
            failure(ProjectStorageError.CORRUPT_SOURCE) { store.read(it, original.sha256, original.sizeBytes) }
        }
        failure(ProjectStorageError.CORRUPT_SOURCE) { store.prune(linkedSetOf(key, "../outside.json")) }
        assertArrayEquals(preservedV1, outside.readBytes())
        assertArrayEquals(preservedV1, File(root, key).readBytes())
    }

    @Test fun recoveryRemovesOnlyOrphanSourcesAndPendingFilesWithoutRecursing() {
        val root = temporary.newFolder("managed")
        val store = ManagedJsonFiles(root)
        val kept = store.ensureSource(source())
        val orphan = store.ensureSource(source("{}".encodeToByteArray()))
        val pending = File(root, ".pending-interrupted.tmp").also { it.writeText("partial") }
        val unknown = File(root, "notes.txt").also { it.writeText("not managed") }
        val nested = File(root, ".pending-directory.tmp").also { it.mkdir() }
        File(nested, "child").writeText("retained")
        store.prune(setOf(kept))
        assertTrue(File(root, kept).isFile)
        assertFalse(File(root, orphan).exists())
        assertFalse(pending.exists())
        assertEquals("not managed", unknown.readText())
        assertEquals("retained", File(nested, "child").readText())
    }

    @Test fun symlinkSourcesAndRootAreRejectedWithoutFollowingTheirTargets() {
        val root = temporary.newFolder("managed")
        val outside = temporary.newFile("outside.json").also { it.writeBytes(preservedV1) }
        val original = source()
        val key = "${original.sha256}.json"
        Files.createSymbolicLink(File(root, key).toPath(), outside.toPath())
        val store = ManagedJsonFiles(root)
        failure(ProjectStorageError.CORRUPT_SOURCE) { store.read(key, original.sha256, original.sizeBytes) }
        failure(ProjectStorageError.CORRUPT_SOURCE) { store.ensureSource(original) }
        store.prune(emptySet())
        assertArrayEquals(preservedV1, outside.readBytes())
        val rootLink = File(temporary.root, "root-link")
        Files.createSymbolicLink(rootLink.toPath(), root.toPath())
        failure(ProjectStorageError.CORRUPT_SOURCE) { ManagedJsonFiles(rootLink).ensureSource(original) }
    }
}

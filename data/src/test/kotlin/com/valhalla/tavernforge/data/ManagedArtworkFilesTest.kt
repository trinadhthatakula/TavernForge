package com.valhalla.tavernforge.data

import com.valhalla.tavernforge.core.model.SourceEnvelope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Storage preserves exact bytes; Android decoder validation is exercised separately on device. */
class ManagedArtworkFilesTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun mixedPngAndJpegShareOneStoreAndCleanupRetainsBothReferenceKinds() {
        val root = temp.newFolder(); val store = ManagedArtworkFiles(root)
        val png = SourceEnvelope(byteArrayOf(1, 2), "image/png")
        val jpeg = SourceEnvelope(byteArrayOf(3, 4), "image/jpeg")
        val pngKey = store.ensureSource(png); val jpegKey = store.ensureSource(jpeg)
        assertTrue(pngKey.endsWith(".png")); assertTrue(jpegKey.endsWith(".jpg"))
        store.prune(setOf(pngKey, jpegKey))
        assertArrayEquals(png.bytes(), store.read(pngKey, png.sha256, png.sizeBytes).bytes())
        assertEquals("image/jpeg", store.read(jpegKey, jpeg.sha256, jpeg.sizeBytes).mediaType)
        assertArrayEquals(jpeg.bytes(), store.read(jpegKey, jpeg.sha256, jpeg.sizeBytes).bytes())
        store.prune(setOf(jpegKey)); assertFalse(File(root, pngKey).exists()); assertTrue(File(root, jpegKey).exists())
    }
    @Test fun invalidReferencePreventsPruningEitherAssetKind() {
        val root = temp.newFolder(); val store = ManagedArtworkFiles(root)
        val source = SourceEnvelope(byteArrayOf(1), "image/jpeg"); val key = store.ensureSource(source)
        try { store.prune(setOf("bad.png")); fail() } catch (e: ProjectStorageException) { assertEquals(ProjectStorageError.CORRUPT_SOURCE, e.code) }
        assertTrue(File(root, key).exists())
        try { store.ensureSource(SourceEnvelope(byteArrayOf(1), "image/webp")); fail() } catch (e: ProjectStorageException) { assertEquals(ProjectStorageError.CORRUPT_SOURCE, e.code) }
    }
}

package com.valhalla.tavernforge.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.tavernforge.core.model.*
import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.ParseResult
import com.valhalla.tavernforge.format.png.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class JpegMetadataDeviceTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var root: File
    private var database: ProjectDatabase? = null
    private lateinit var repository: RoomProjectRepository
    @Before fun allocate() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "jpeg-metadata-${UUID.randomUUID()}.db"
        root = File(context.filesDir, name.removeSuffix(".db"))
    }
    private fun open() {
        database = createProjectDatabase(context, name)
        val db = database!!
        repository = RoomProjectRepository(RoomProjectIndex(db.projects()), ManagedJsonFiles(File(root, "json")),
            compositionIndex = RoomCompositionIndex(db.compositions()), assetFiles = ManagedArtworkFiles(File(root, "assets")),
            metadataIndex = RoomLibraryMetadataIndex(db.libraryMetadata()))
    }
    private fun reopen() { database?.close(); open() }
    @After fun clean() { database?.close(); if (::context.isInitialized && ::name.isInitialized) context.deleteDatabase(name); if (::root.isInitialized) root.deleteRecursively() }

    @Test fun jpegOriginalAndExifSurviveReviewedOrientedPngConversionAndReopen() = runBlocking<Unit> {
        open()
        val jpeg = jpegWithExif()
        val original = repository.saveArtwork("portrait.jpg", jpeg)
        assertEquals("image/jpeg", original.mediaType)
        assertArrayEquals(jpeg, repository.loadArtwork(original.id).bytes())
        val plan = repository.prepareArtworkPngConversion(original.id)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, plan.orientation)
        assertEquals(3, plan.width); assertEquals(2, plan.height)
        assertTrue(plan.reviewItems.single().contains("omitted"))
        expect(ProjectStorageError.INVALID_INPUT) { repository.acceptArtworkPngConversion(plan, emptySet()) }
        val derived = repository.acceptArtworkPngConversion(plan, plan.reviewItems.toSet())
        assertEquals("image/png", derived.mediaType); assertEquals(original.id, derived.sourceArtworkId)
        assertEquals("android-jpeg-png-1", derived.transformation)
        reopen()
        assertArrayEquals(jpeg, repository.loadArtwork(original.id).bytes())
        val originalExif = ExifInterface(repository.loadArtwork(original.id).bytes().inputStream())
        assertEquals("Preserve synthetic original metadata", originalExif.getAttribute(ExifInterface.TAG_USER_COMMENT))
        val png = repository.loadArtwork(derived.id).bytes()
        assertTrue(PngCardEngine().inspect(png) is PngInspectionResult.Success)
        val decodedJpeg = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        val decodedPng = BitmapFactory.decodeByteArray(png, 0, png.size)
        try { assertEquals(decodedJpeg.getPixel(0, 0), decodedPng.getPixel(2, 0)) }
        finally { decodedJpeg.recycle(); decodedPng.recycle() }
        assertNull(ExifInterface(png.inputStream()).getAttribute(ExifInterface.TAG_USER_COMMENT))
        expect(ProjectStorageError.IN_USE) { repository.deleteArtwork(original.id) }
        repository.deleteArtwork(derived.id); repository.deleteArtwork(original.id)
    }
    @Test fun malformedAndTruncatedJpegNeverCreatesAssetOrDerivedRecord() = runBlocking<Unit> {
        open()
        val jpeg = jpegWithExif()
        expect(ProjectStorageError.INVALID_INPUT) { repository.saveArtwork("truncated.jpg", jpeg.copyOf(jpeg.size - 2)) }
        expect(ProjectStorageError.INVALID_INPUT) { repository.saveArtwork("corrupt.jpg", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())) }
        assertTrue(database!!.compositions().allArtwork().isEmpty())
    }
    @Test fun frozenV3PngAndLinksMigrateThenLocalTagsFilterAndDuplicateIndependently() = runBlocking<Unit> {
        val source = SourceEnvelope(V1.encodeToByteArray())
        val jsonKey = ManagedJsonFiles(File(root, "json")).ensureSource(source)
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val png = try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() } finally { bitmap.recycle() }
        val pngSource = SourceEnvelope(png, "image/png")
        val pngKey = ManagedArtworkFiles(File(root, "assets")).ensureSource(pngSource)
        val path = context.getDatabasePath(name); path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            FROZEN_V3_DDL.forEach(old::execSQL)
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            old.execSQL("INSERT INTO room_master_table VALUES(42, 'ad77ad95b82a302b78855c5e7fabd4e4')")
            old.insertOrThrow("projects", null, ContentValues().apply {
                put("sourceFileKey", jsonKey); put("mediaType", "application/json"); put("documentRevision", 0L)
                put("currentFileKey", jsonKey); put("currentSha256", source.sha256); put("currentSizeBytes", source.sizeBytes)
                put("id", "character"); put("title", "Original title"); put("sourceFileName", "keeper.json")
                put("sourceFormat", "V1"); putNull("specVersion"); put("isDraft", 0); put("createdAtEpochMillis", 10L)
                put("updatedAtEpochMillis", 20L); put("sourceSha256", source.sha256); put("sourceSizeBytes", source.sizeBytes)
            })
            old.insertOrThrow("artwork_assets", null, ContentValues().apply {
                put("fileKey", pngKey); put("id", "artwork"); put("fileName", "original.png"); put("sha256", pngSource.sha256)
                put("sizeBytes", png.size); put("width", 1); put("height", 1); put("isAnimated", 0); put("createdAtEpochMillis", 10L)
            })
            old.execSQL("INSERT INTO project_compositions VALUES('character', 2, 'artwork', 'artwork', 'preserved-source-candidate')")
            old.version = 3
        }
        open()
        val asset = repository.loadArtwork("artwork")
        assertEquals("image/png", asset.summary.mediaType); assertNull(asset.summary.sourceArtworkId)
        assertArrayEquals(png, asset.bytes()); assertArrayEquals(source.bytes(), repository.load("character").source.bytes())
        assertEquals(2L, repository.loadComposition("character").compositionRevision)
        repository.setProjectTags("character", 0, listOf("Archive", "Offline"))
        val copy = repository.duplicate("character")
        assertEquals(listOf("Archive", "Offline"), repository.projectMetadata(copy.id).tags)
        assertEquals(0L, repository.projectMetadata(copy.id).revision)
        repository.setProjectTags(copy.id, 0, listOf("Copy only"))
        assertEquals(listOf("Archive", "Offline"), repository.projectMetadata("character").tags)
        reopen()
        val filtered = repository.observeLibrary(LibraryQuery(formats = setOf(SourceFormat.V1), draftFilter = LibraryDraftFilter.VALID, tags = setOf("ARCHIVE", "offline"))).first()
        assertEquals(listOf("character"), filtered.map { it.summary.id })
        assertArrayEquals(source.bytes(), repository.load("character").currentSource.bytes())
        expect(ProjectStorageError.REVISION_CONFLICT) { repository.setProjectTags("character", 0, listOf("Stale")) }
        repository.delete(copy.id)
        assertNull(database!!.libraryMetadata().find(copy.id))
    }
    private fun jpegWithExif(): ByteArray {
        root.mkdirs()
        val file = File(root, "synthetic-input.jpg")
        val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff334455.toInt()); bitmap.setPixel(0, 0, 0xffdd3311.toInt())
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        } finally { bitmap.recycle() }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_USER_COMMENT, "Preserve synthetic original metadata")
            saveAttributes()
        }
        return file.readBytes().also { file.delete() }
    }
    private suspend fun expect(code: ProjectStorageError, block: suspend () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: ProjectStorageException) { assertEquals(code, e.code) }
    }
    companion object {
        private const val V1 = """{ "name":"Keeper", "description":"", "personality":"", "scenario":"", "first_mes":"", "mes_example":"", "unknown":1.2300e+09 }"""
        // Frozen schema3 SQL is inserted below from the checked-in schema, then remains literal.
        private val FROZEN_V3_DDL = listOf(
            """CREATE TABLE IF NOT EXISTS `projects` (`sourceFileKey` TEXT NOT NULL, `mediaType` TEXT NOT NULL, `documentRevision` INTEGER NOT NULL, `currentFileKey` TEXT NOT NULL, `currentSha256` TEXT NOT NULL, `currentSizeBytes` INTEGER NOT NULL, `id` TEXT NOT NULL, `title` TEXT NOT NULL, `sourceFileName` TEXT NOT NULL, `sourceFormat` TEXT NOT NULL, `specVersion` TEXT, `isDraft` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, `sourceSha256` TEXT NOT NULL, `sourceSizeBytes` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
            """CREATE TABLE IF NOT EXISTS `artwork_assets` (`fileKey` TEXT NOT NULL, `id` TEXT NOT NULL, `fileName` TEXT NOT NULL, `sha256` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `isAnimated` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
            """CREATE TABLE IF NOT EXISTS `project_compositions` (`characterId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `artworkId` TEXT, `sourcePngId` TEXT, `sourceCandidateId` TEXT, PRIMARY KEY(`characterId`), FOREIGN KEY(`characterId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`artworkId`) REFERENCES `artwork_assets`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`sourcePngId`) REFERENCES `artwork_assets`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )""",
            """CREATE INDEX IF NOT EXISTS `index_project_compositions_artworkId` ON `project_compositions` (`artworkId`)""",
            """CREATE INDEX IF NOT EXISTS `index_project_compositions_sourcePngId` ON `project_compositions` (`sourcePngId`)""",
            """CREATE TABLE IF NOT EXISTS `lore_links` (`characterId` TEXT NOT NULL, `lorebookId` TEXT NOT NULL, `embeddedRevision` INTEGER, `embeddedSha256` TEXT, PRIMARY KEY(`characterId`, `lorebookId`), FOREIGN KEY(`characterId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`lorebookId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )""",
            """CREATE INDEX IF NOT EXISTS `index_lore_links_lorebookId` ON `lore_links` (`lorebookId`)"""
        )
    }
}

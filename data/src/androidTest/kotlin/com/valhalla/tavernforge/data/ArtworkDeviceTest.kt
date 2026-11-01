package com.valhalla.tavernforge.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.tavernforge.core.model.JsonLimits
import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.ParseResult
import com.valhalla.tavernforge.format.combine.EmbeddingPlanResult
import com.valhalla.tavernforge.format.png.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Actual Room v3, foreign keys and private-file operations; every artifact uses an isolated UUID. */
@RunWith(AndroidJUnit4::class)
class ArtworkDeviceTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var root: File
    private var database: ProjectDatabase? = null
    private lateinit var repository: RoomProjectRepository
    @Before fun allocate() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "artwork-test-${UUID.randomUUID()}.db"
        root = File(context.filesDir, name.removeSuffix(".db"))
    }
    private fun open() {
        database = createProjectDatabase(context, name)
        val db = database!!
        repository = RoomProjectRepository(RoomProjectIndex(db.projects()), ManagedJsonFiles(File(root, "json")),
            compositionIndex = RoomCompositionIndex(db.compositions()),
            assetFiles = ManagedJsonFiles(File(root, "assets"), JsonLimits(maxBytes = 32 * 1024 * 1024), "png", "image/png"))
    }
    private fun reopen() { database?.close(); open() }
    @After fun clean() { database?.close(); if (::context.isInitialized && ::name.isInitialized) context.deleteDatabase(name); if (::root.isInitialized) root.deleteRecursively() }

    @Test fun versionTwoSavedEditsMigrateWithoutAnyOriginalOrCurrentChanges() = runBlocking<Unit> {
        val original = SourceEnvelope(V1.encodeToByteArray())
        val current = SourceEnvelope(V1.replace("Keeper", "Edited keeper").encodeToByteArray())
        val files = ManagedJsonFiles(File(root, "json"))
        val originalKey = files.ensureSource(original)
        val currentKey = files.ensureSource(current)
        val path = context.getDatabasePath(name); path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            // Frozen schema v2 DDL/identity, independent of the current Room entity generator.
            old.execSQL("""CREATE TABLE projects (sourceFileKey TEXT NOT NULL, mediaType TEXT NOT NULL,
                documentRevision INTEGER NOT NULL, currentFileKey TEXT NOT NULL, currentSha256 TEXT NOT NULL,
                currentSizeBytes INTEGER NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, sourceFileName TEXT NOT NULL,
                sourceFormat TEXT NOT NULL, specVersion TEXT, isDraft INTEGER NOT NULL, createdAtEpochMillis INTEGER NOT NULL,
                updatedAtEpochMillis INTEGER NOT NULL, sourceSha256 TEXT NOT NULL, sourceSizeBytes INTEGER NOT NULL, PRIMARY KEY(id))""")
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            old.execSQL("INSERT INTO room_master_table VALUES(42, 'aecc4bcc203e3f18d3c832154b434a22')")
            old.insertOrThrow("projects", null, ContentValues().apply {
                put("sourceFileKey", originalKey); put("mediaType", "application/json"); put("documentRevision", 1L)
                put("currentFileKey", currentKey); put("currentSha256", current.sha256); put("currentSizeBytes", current.sizeBytes)
                put("id", "migrated"); put("title", "Preserved title"); put("sourceFileName", "original.json")
                put("sourceFormat", "V1"); putNull("specVersion"); put("isDraft", 0); put("createdAtEpochMillis", 10L)
                put("updatedAtEpochMillis", 20L); put("sourceSha256", original.sha256); put("sourceSizeBytes", original.sizeBytes)
            })
            old.version = 2
        }
        open()
        val loaded = repository.load("migrated")
        assertArrayEquals(original.bytes(), loaded.source.bytes()); assertArrayEquals(current.bytes(), loaded.currentSource.bytes())
        assertEquals(1L, loaded.documentRevision); assertEquals("Preserved title", loaded.summary.title)
        assertEquals(0L, repository.loadComposition("migrated").compositionRevision)
        val artwork = repository.saveArtwork("art.png", png())
        repository.setArtwork("migrated", 0, artwork.id)
        reopen()
        assertEquals(artwork.id, repository.loadComposition("migrated").artwork?.id)
        assertArrayEquals(original.bytes(), repository.load("migrated").source.bytes())
        assertArrayEquals(current.bytes(), repository.load("migrated").currentSource.bytes())
    }
    @Test fun atomicPngProvenanceAndSharedForeignKeysSurviveCloseReopen() = runBlocking<Unit> {
        open()
        val engine = PngCardEngine()
        val container = (engine.inspect(png()) as PngInspectionResult.Success).container
        val character = (CharacterCardEngine().parse(V1.encodeToByteArray()) as ParseResult.Success).document
        val plan = (engine.planExport(container, character) as PngExportPlanResult.Ready).plan
        val bytes = (engine.export(plan, plan.reviewItems.toSet()) as PngExportResult.Success).bytes
        val imported = repository.savePngCard("card.png", bytes)
        val lore = repository.saveImported("lore.json", SourceEnvelope(LORE.encodeToByteArray()))
        repository.linkLorebook(imported.summary.id, 0, lore.id)
        val copy = repository.duplicate(imported.summary.id)
        reopen()
        val composition = repository.loadComposition(copy.id)
        assertEquals(1, composition.lorebooks.size)
        assertEquals(composition.artwork?.id, composition.sourcePng?.id)
        assertArrayEquals(bytes, repository.loadArtwork(composition.sourcePng!!.id).bytes())
        expect(ProjectStorageError.IN_USE) { repository.delete(lore.id) }
        expect(ProjectStorageError.IN_USE) { repository.deleteArtwork(composition.sourcePng!!.id) }
        repository.delete(imported.summary.id)
        assertArrayEquals(V1.encodeToByteArray(), repository.load(copy.id).source.bytes())
        repository.unlinkLorebook(copy.id, composition.compositionRevision, lore.id)
        repository.delete(lore.id)
        repository.delete(copy.id)
        repository.deleteArtwork(composition.sourcePng!!.id)
        assertTrue(database!!.compositions().allArtwork().isEmpty())
    }
    @Test fun embeddingAndLinkProvenanceCommitTogetherAndStaleCompositionRollsBack() = runBlocking<Unit> {
        open()
        val character = repository.saveImported("v2.json", SourceEnvelope(V2.encodeToByteArray()))
        val lore = repository.saveImported("lore.json", SourceEnvelope(LORE.encodeToByteArray()))
        val first = (repository.prepareEmbedding(character.id, lore.id) as EmbeddingPlanResult.Ready).plan
        val result = repository.applyEmbedding(character.id, 0, lore.id, first, first.reviewItems.toSet())
        reopen()
        assertArrayEquals(V2.encodeToByteArray(), repository.load(character.id).source.bytes())
        assertArrayEquals(result.currentSource.bytes(), repository.load(character.id).currentSource.bytes())
        val link = repository.loadComposition(character.id).lorebooks.single()
        assertEquals(0L, link.embeddedRevision); assertEquals(lore.sourceSha256, link.embeddedSha256)
        val next = (repository.prepareEmbedding(character.id, lore.id) as EmbeddingPlanResult.Ready).plan
        expect(ProjectStorageError.REVISION_CONFLICT) { repository.applyEmbedding(character.id, 0, lore.id, next, next.reviewItems.toSet()) }
        reopen()
        assertEquals(1L, repository.load(character.id).documentRevision)
        assertEquals(1L, repository.loadComposition(character.id).compositionRevision)
        assertEquals(1, database!!.compositions().links(character.id).size)
    }
    private suspend fun expect(code: ProjectStorageError, block: suspend () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: ProjectStorageException) { assertEquals(code, e.code) }
    }
    companion object {
        private const val V1 = """{ "name":"Keeper", "description":"", "personality":"", "scenario":"", "first_mes":"", "mes_example":"", "unknown":{"number":1.2300e+09,"null":null} }"""
        private const val LORE = """{"entries":[],"extensions":{},"unknown":1.2300e+09}"""
        private const val V2 = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"Keeper","description":"","personality":"","scenario":"","first_mes":"","mes_example":"","creator_notes":"","system_prompt":"","post_history_instructions":"","alternate_greetings":[],"tags":[],"creator":"","character_version":"","extensions":{}}}"""
        private fun png(): ByteArray {
            fun chunk(type: String, data: ByteArray): ByteArray {
                val kind = type.encodeToByteArray()
                return java.nio.ByteBuffer.allocate(data.size + 12).putInt(data.size).put(kind).put(data).putInt(CRC32().apply { update(kind); update(data) }.value.toInt()).array()
            }
            val compressed = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(byteArrayOf(0,1,2,3,0xff.toByte())) } }.toByteArray()
            return byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10) + chunk("IHDR", byteArrayOf(0,0,0,1,0,0,0,1,8,6,0,0,0)) + chunk("IDAT", compressed) + chunk("IEND", byteArrayOf())
        }
    }
}

package com.valhalla.tavernforge.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.ExportResult
import com.valhalla.tavernforge.format.JsonPatch
import com.valhalla.tavernforge.format.ParseResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Production Room migration and file operations, isolated from every user project. */
@RunWith(AndroidJUnit4::class)
class ProjectEditingDeviceTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var root: File
    private var database: ProjectDatabase? = null
    private lateinit var repository: RoomProjectRepository

    @Before fun allocateIsolatedNames() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "project-edit-test-$suffix.db"
        root = File(context.filesDir, "project-edit-test-$suffix")
    }

    private fun openStore() {
        val db = createProjectDatabase(context, databaseName)
        database = db
        repository = RoomProjectRepository(RoomProjectIndex(db.projects()), ManagedJsonFiles(root))
    }

    private fun reopen() { database?.close(); openStore() }

    @After fun removeOnlyIsolatedResources() {
        database?.close()
        if (::context.isInitialized && ::databaseName.isInitialized) context.deleteDatabase(databaseName)
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test fun versionOneLibraryMigratesWithoutChangingOriginalRowsThenEditsAndReopens() = runBlocking<Unit> {
        val original = SourceEnvelope(RICH_V1.encodeToByteArray(), "application/example+json")
        val draft = SourceEnvelope(MISSING_GROUP_V3.encodeToByteArray())
        val files = ManagedJsonFiles(root)
        val originalKey = files.ensureSource(original)
        val draftKey = files.ensureSource(draft)
        val path = context.getDatabasePath(databaseName)
        path.parentFile?.mkdirs()
        // Frozen v1 DDL and identity from data/schemas/...ProjectDatabase/1.json.
        // This deliberately does not ask the current Room entity to create an old schema.
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL(V1_CREATE_SQL)
            old.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            old.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'ca0b8b1e2b288a250623925ce0cafb1c')")
            insertV1(old, "legacy-valid", "Local legacy title", original, originalKey, "V1", null, false)
            insertV1(old, "legacy-draft", "Saved draft", draft, draftKey, "V3", "3.0", true)
            old.version = 1
        }

        openStore()
        val migrated = repository.load("legacy-valid")
        assertEquals("legacy-valid", migrated.summary.id)
        assertEquals("Local legacy title", migrated.summary.title)
        assertEquals("legacy.json", migrated.summary.sourceFileName)
        assertEquals(1234L, migrated.summary.createdAtEpochMillis)
        assertEquals(5678L, migrated.summary.updatedAtEpochMillis)
        assertEquals(SourceFormat.V1, migrated.summary.sourceFormat)
        assertEquals(original.sha256, migrated.summary.sourceSha256)
        assertEquals(original.mediaType, migrated.source.mediaType)
        assertEquals(0L, migrated.documentRevision)
        assertArrayEquals(original.bytes(), migrated.source.bytes())
        assertArrayEquals(original.bytes(), migrated.currentSource.bytes())
        val migratedDraft = repository.load("legacy-draft")
        assertTrue(migratedDraft.summary.isDraft)
        assertEquals("3.0", migratedDraft.summary.specVersion)
        assertArrayEquals(draft.bytes(), migratedDraft.currentSource.bytes())
        val row = requireNotNull(database!!.projects().find("legacy-valid"))
        assertEquals(originalKey, row.currentFileKey)
        assertEquals(original.sha256, row.currentSha256)
        assertEquals(original.sizeBytes, row.currentSizeBytes)

        val changed = repository.applyEdits("legacy-valid", 0,
            listOf(JsonPatch.Replace("/name", JsonPrimitive("Edited after migration"))))
        val repaired = repository.applyEdits("legacy-draft", 0,
            listOf(JsonPatch.Add("/data/group_only_greetings", JsonArray(emptyList()))))
        assertFalse(repaired.summary.isDraft)
        try {
            repository.applyEdits("legacy-valid", 0, listOf(JsonPatch.Replace("/name", JsonPrimitive("Stale"))))
            fail("A stale edit must not replace migrated content")
        } catch (error: ProjectStorageException) { assertEquals(ProjectStorageError.REVISION_CONFLICT, error.code) }
        // Exercise the actual conditional SQL update independently of the repository's early guard.
        assertEquals(0, RoomProjectIndex(database!!.projects()).updateContent("legacy-valid", 0,
            originalKey, original.sha256, original.sizeBytes, false, 9999))

        reopen()
        val loaded = repository.load("legacy-valid")
        assertEquals(1L, loaded.documentRevision)
        assertEquals("Local legacy title", loaded.summary.title)
        assertArrayEquals(original.bytes(), loaded.source.bytes())
        assertArrayEquals(changed.currentSource.bytes(), loaded.currentSource.bytes())
        assertArrayEquals(draft.bytes(), repository.load("legacy-draft").source.bytes())
        assertFalse(repository.load("legacy-draft").summary.isDraft)
        val engine = CharacterCardEngine()
        val restored = (engine.restore(loaded.source, loaded.currentSource, loaded.documentRevision) as ParseResult.Success).document
        assertEquals("Edited after migration", restored.fields.name)
        assertTrue(engine.export(restored) is ExportResult.Success)
        assertTrue(loaded.currentSource.bytes().decodeToString().contains("1.2300e+09"))
        assertEquals(2, database!!.projects().all().size)
    }

    @Test fun editedDuplicateSurvivesOriginalDeletionAndSharesBothSnapshotReferences() = runBlocking<Unit> {
        openStore()
        val original = SourceEnvelope(RICH_V1.encodeToByteArray())
        val saved = repository.saveImported("keeper.json", original)
        val edited = repository.applyEdits(saved.id, 0, listOf(JsonPatch.Replace("/name", JsonPrimitive("Edited"))))
        val copy = repository.duplicate(saved.id)
        val originalRow = requireNotNull(database!!.projects().find(saved.id))
        val copyRow = requireNotNull(database!!.projects().find(copy.id))
        assertEquals(originalRow.sourceFileKey, copyRow.sourceFileKey)
        assertEquals(originalRow.currentFileKey, copyRow.currentFileKey)
        assertEquals(1L, copyRow.documentRevision)
        repository.delete(saved.id)
        reopen()
        val loaded = repository.load(copy.id)
        assertArrayEquals(original.bytes(), loaded.source.bytes())
        assertArrayEquals(edited.currentSource.bytes(), loaded.currentSource.bytes())
        assertTrue(File(root, copyRow.sourceFileKey).isFile)
        assertTrue(File(root, copyRow.currentFileKey).isFile)
        repository.delete(copy.id)
        reopen()
        repository.recover()
        assertTrue(database!!.projects().all().isEmpty())
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun invalidV1DraftKeepsItsSourceKindAcrossRealRoomReopenAndRepair() = runBlocking<Unit> {
        openStore()
        val original = SourceEnvelope(RICH_V1.encodeToByteArray())
        val saved = repository.saveImported("keeper.json", original)
        repository.applyEdits(saved.id, 0, listOf(JsonPatch.Remove("/name")))
        reopen()
        val draft = repository.load(saved.id)
        assertTrue(draft.summary.isDraft)
        assertEquals(SourceFormat.V1, draft.summary.sourceFormat)
        assertEquals(1L, draft.documentRevision)
        assertArrayEquals(original.bytes(), draft.source.bytes())
        val repaired = repository.applyEdits(saved.id, 1, listOf(JsonPatch.Add("/name", JsonPrimitive("Repaired"))))
        assertEquals(2L, repaired.documentRevision)
        assertFalse(repaired.summary.isDraft)
        reopen()
        assertArrayEquals(repaired.currentSource.bytes(), repository.load(saved.id).currentSource.bytes())
        assertArrayEquals(original.bytes(), repository.load(saved.id).source.bytes())
    }

    private fun insertV1(db: SQLiteDatabase, id: String, title: String, source: SourceEnvelope,
                         key: String, format: String, version: String?, draft: Boolean) {
        val values = ContentValues().apply {
            put("id", id); put("title", title); put("sourceFileName", "legacy.json")
            put("sourceFileKey", key); put("mediaType", source.mediaType); put("documentRevision", 0L)
            put("sourceFormat", format)
            if (version == null) putNull("specVersion") else put("specVersion", version)
            put("isDraft", if (draft) 1 else 0); put("createdAtEpochMillis", 1234L); put("updatedAtEpochMillis", 5678L)
            put("sourceSha256", source.sha256); put("sourceSizeBytes", source.sizeBytes)
        }
        assertTrue(db.insertOrThrow("projects", null, values) >= 0)
    }

    companion object {
        private const val V1_CREATE_SQL = "CREATE TABLE IF NOT EXISTS projects (sourceFileKey TEXT NOT NULL, mediaType TEXT NOT NULL, documentRevision INTEGER NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, sourceFileName TEXT NOT NULL, sourceFormat TEXT NOT NULL, specVersion TEXT, isDraft INTEGER NOT NULL, createdAtEpochMillis INTEGER NOT NULL, updatedAtEpochMillis INTEGER NOT NULL, sourceSha256 TEXT NOT NULL, sourceSizeBytes INTEGER NOT NULL, PRIMARY KEY(id))"
        private const val RICH_V1 = """{ "name":"Keeper", "description":"", "personality":"", "scenario":"", "first_mes":"", "mes_example":"", "opaque":{"null":null,"flag":false,"number":1.2300e+09,"huge":123456789012345678901234567890,"zero":-0} }"""
        private const val MISSING_GROUP_V3 = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Draft","description":"","personality":"","scenario":"","first_mes":"","mes_example":"","creator_notes":"","system_prompt":"","post_history_instructions":"","alternate_greetings":[],"tags":[],"creator":"","character_version":"","extensions":{"number":1.2300e+09,"null":null}}}"""
    }
}

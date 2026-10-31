package com.valhalla.tavernforge.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.SourceFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Room + app-private disk. Every test uses its own database and directory, never the user's library. */
@RunWith(AndroidJUnit4::class)
class ProjectLibraryDeviceTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var root: File
    private lateinit var database: ProjectDatabase
    private lateinit var repository: RoomProjectRepository

    @Before fun createIsolatedStore() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "project-library-test-$suffix.db"
        root = File(context.filesDir, "project-library-test-$suffix")
        openStore()
    }

    private fun openStore() {
        database = createProjectDatabase(context, databaseName)
        repository = RoomProjectRepository(RoomProjectIndex(database.projects()), ManagedJsonFiles(root))
    }

    private fun reopen() { database.close(); openStore() }

    @After fun removeOnlyIsolatedStore() {
        if (::database.isInitialized) database.close()
        if (::context.isInitialized && ::databaseName.isInitialized) context.deleteDatabase(databaseName)
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test fun actualRoomCloseReopenKeepsExactOriginalAndIndependentLibraryTitle() = runBlocking<Unit> {
        val original = SourceEnvelope(RICH_V1.encodeToByteArray(), "application/example+json")
        val saved = repository.saveImported("keeper.json", original)
        repository.rename(saved.id, "Local archive")
        reopen()
        val loaded = repository.load(saved.id)
        assertEquals("Local archive", loaded.summary.title)
        assertEquals("keeper.json", loaded.summary.sourceFileName)
        assertEquals(SourceFormat.V1, loaded.summary.sourceFormat)
        assertEquals(saved.createdAtEpochMillis, loaded.summary.createdAtEpochMillis)
        assertEquals(original.sha256, loaded.summary.sourceSha256)
        assertEquals(original.sha256, loaded.source.sha256)
        assertEquals(original.mediaType, loaded.source.mediaType)
        assertArrayEquals(original.bytes(), loaded.source.bytes())
        assertEquals(listOf(loaded.summary), repository.observeProjects().first())
        assertTrue(loaded.source.bytes().decodeToString().contains("Archive Keeper"))
    }

    @Test fun realRoomDuplicateAndDeleteKeepSharedBytesUntilLastReferenceIsRemoved() = runBlocking<Unit> {
        val original = SourceEnvelope(RICH_V1.encodeToByteArray())
        val saved = repository.saveImported("keeper.json", original)
        val duplicate = repository.duplicate(saved.id)
        repository.rename(duplicate.id, "Separate library title")
        val firstRecord = requireNotNull(database.projects().find(saved.id))
        val copyRecord = requireNotNull(database.projects().find(duplicate.id))
        assertEquals(firstRecord.sourceFileKey, copyRecord.sourceFileKey)
        assertNotEquals(saved.id, duplicate.id)
        repository.delete(saved.id)
        reopen()
        assertEquals("Separate library title", repository.load(duplicate.id).summary.title)
        assertArrayEquals(original.bytes(), repository.load(duplicate.id).source.bytes())
        repository.delete(duplicate.id)
        reopen()
        assertTrue(repository.observeProjects().first().isEmpty())
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun schemaInvalidV3AndFutureFormatsPersistAsUnchangedDraftsButMalformedJsonDoesNot() = runBlocking<Unit> {
        val drafts = listOf(MISSING_GROUP_V3 to SourceFormat.V3, FUTURE_JSON to SourceFormat.UNKNOWN)
        val savedDrafts = drafts.map { (json, format) ->
            val saved = repository.saveImported("draft.json", SourceEnvelope(json.encodeToByteArray()))
            assertTrue(saved.isDraft)
            assertEquals(format, saved.sourceFormat)
            saved to json
        }
        try {
            repository.saveImported("broken.json", SourceEnvelope("{".encodeToByteArray()))
            fail("Malformed JSON must not enter the library")
        } catch (error: ProjectStorageException) { assertEquals(ProjectStorageError.INVALID_INPUT, error.code) }
        reopen()
        assertEquals(2, repository.observeProjects().first().size)
        savedDrafts.forEach { (saved, json) ->
            val loaded = repository.load(saved.id)
            assertTrue(loaded.summary.isDraft)
            assertEquals(saved.sourceSha256, loaded.source.sha256)
            assertArrayEquals(json.encodeToByteArray(), loaded.source.bytes())
            assertFalse(loaded.source.bytes().decodeToString().contains("group_only_greetings"))
        }
    }

    @Test fun restartRecoveryPrunesSimulatedInterruptedFilesAndKeepsReferencedOriginal() = runBlocking<Unit> {
        val original = SourceEnvelope(RICH_V1.encodeToByteArray())
        val saved = repository.saveImported("keeper.json", original)
        val orphanKey = ManagedJsonFiles(root).ensureSource(SourceEnvelope("{}".encodeToByteArray()))
        val pending = File(root, ".pending-test-interrupted.tmp").also { it.writeText("partial") }
        val unrelated = File(root, "unrelated.txt").also { it.writeText("retained") }
        reopen()
        repository.recover()
        assertArrayEquals(original.bytes(), repository.load(saved.id).source.bytes())
        assertFalse(File(root, orphanKey).exists())
        assertFalse(pending.exists())
        assertEquals("retained", unrelated.readText())
    }

    @Test fun missingSourceKeepsRoomRowAndDuplicateFailureDoesNotCreateAnotherRow() = runBlocking<Unit> {
        val saved = repository.saveImported("keeper.json", SourceEnvelope(RICH_V1.encodeToByteArray()))
        val row = requireNotNull(database.projects().find(saved.id))
        assertTrue(File(root, row.sourceFileKey).delete())
        reopen()
        try { repository.load(saved.id); fail("Missing source must be reported") }
        catch (error: ProjectStorageException) { assertEquals(ProjectStorageError.CORRUPT_SOURCE, error.code) }
        try { repository.duplicate(saved.id); fail("Missing source must not be duplicated") }
        catch (error: ProjectStorageException) { assertEquals(ProjectStorageError.CORRUPT_SOURCE, error.code) }
        assertEquals(1, database.projects().all().size)
        assertNotNull(database.projects().find(saved.id))
    }

    @Test fun roomPrimaryKeyConflictAbortsInsteadOfReplacingProjectMetadata() = runBlocking<Unit> {
        val saved = repository.saveImported("keeper.json", SourceEnvelope(RICH_V1.encodeToByteArray()))
        val row = requireNotNull(database.projects().find(saved.id))
        var rejected = false
        try { database.projects().insert(row.copy(summary = row.summary.copy(title = "Unexpected replacement"))) }
        catch (_: Exception) { rejected = true }
        assertTrue("Room must reject a conflicting primary key", rejected)
        reopen()
        assertEquals(saved.title, repository.load(saved.id).summary.title)
        assertArrayEquals(RICH_V1.encodeToByteArray(), repository.load(saved.id).source.bytes())
        assertEquals(1, database.projects().all().size)
    }

    companion object {
        // Original synthetic data, deliberately not a captured user or upstream export.
        private val RICH_V1 = """
            {
              "name":"Archive Keeper","description":"","personality":"","scenario":"","first_mes":"","mes_example":"",
              "unknown":{"null":null,"flag":false,"decimal":1.2300e+09,"huge":123456789012345678901234567890,"negativeZero":-0}
            }
        """.trimIndent() + "\n"
        private const val MISSING_GROUP_V3 = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Draft","description":"","personality":"","scenario":"","first_mes":"","mes_example":"","creator_notes":"","system_prompt":"","post_history_instructions":"","alternate_greetings":[],"tags":[],"creator":"","character_version":"","extensions":{"number":1.2300e+09,"null":null}}}"""
        private const val FUTURE_JSON = """{ "spec":"chara_card_v4", "data":{"name":"Future","unknown":[null,1e-8]} }"""
    }
}

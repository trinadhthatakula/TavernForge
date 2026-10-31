package com.valhalla.tavernforge.data

import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.ExportResult
import com.valhalla.tavernforge.format.JsonPatch
import com.valhalla.tavernforge.format.ParseResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ProjectEditingRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val index = FaultIndex()
    private var sequence = 0
    private var time = 1000L
    private val directory by lazy { temporary.newFolder("managed-edits") }
    private val disk by lazy { ManagedJsonFiles(directory) }
    private val files by lazy { FaultFiles(disk) }
    private fun repository(mutex: Mutex = Mutex()) = RoomProjectRepository(index, files,
        clock = { ++time }, newId = { "edit-project-${++sequence}" }, dispatcher = Dispatchers.Unconfined, mutex = mutex)

    private fun name(value: String) = listOf(JsonPatch.Replace("/name", JsonPrimitive(value)))
    private suspend fun failure(code: ProjectStorageError, action: suspend () -> Unit): ProjectStorageException {
        try { action(); fail("Expected $code") } catch (error: ProjectStorageException) {
            assertEquals(code, error.code)
            assertTrue(error.message.orEmpty().isNotBlank())
            return error
        }
        error("Unreachable")
    }

    @Test fun editAndNewRepositoryReloadKeepOriginalMetadataAndCurrentUnknownValues() = runBlocking<Unit> {
        val repo = repository()
        val original = SourceEnvelope(preservedV1, "application/example+json")
        val saved = repo.saveImported("keeper.json", original)
        repo.rename(saved.id, "Local title")
        val edited = repo.applyEdits(saved.id, 0, name("Changed character"))
        assertEquals(1L, edited.documentRevision)
        assertEquals("Local title", edited.summary.title)
        assertEquals(saved.sourceSha256, edited.summary.sourceSha256)
        assertEquals(saved.sourceSizeBytes, edited.summary.sourceSizeBytes)
        assertEquals(saved.createdAtEpochMillis, edited.summary.createdAtEpochMillis)
        assertArrayEquals(preservedV1, edited.source.bytes())
        assertEquals(original.mediaType, edited.source.mediaType)
        assertEquals(original.mediaType, edited.currentSource.mediaType)
        assertNotEquals(edited.source.sha256, edited.currentSource.sha256)
        val reopened = repository().load(saved.id)
        assertEquals(1L, reopened.documentRevision)
        assertArrayEquals(edited.currentSource.bytes(), reopened.currentSource.bytes())
        assertEquals(original.mediaType, reopened.currentSource.mediaType)
        val engine = CharacterCardEngine()
        val current = (engine.restore(reopened.source, reopened.currentSource, reopened.documentRevision) as ParseResult.Success).document
        val originalDocument = (engine.parse(preservedV1) as ParseResult.Success).document
        assertEquals("Changed character", current.fields.name)
        assertEquals(originalDocument.raw["unknown"], current.raw["unknown"])
        val exported = (engine.export(current) as ExportResult.Success).bytes.decodeToString()
        listOf("1.2300e+09", "123456789012345678901234567890", "-0").forEach { assertTrue(exported.contains(it)) }
    }

    @Test fun staleEditAndStaleEmptyBatchCannotOverwriteOrCreateFiles() = runBlocking<Unit> {
        val repo = repository()
        val saved = repo.saveImported("keeper.json", source())
        val first = repo.applyEdits(saved.id, 0, name("First"))
        val writes = files.ensured
        failure(ProjectStorageError.REVISION_CONFLICT) { repo.applyEdits(saved.id, 0, name("Stale")) }
        failure(ProjectStorageError.REVISION_CONFLICT) { repo.applyEdits(saved.id, 0, emptyList()) }
        assertEquals(writes, files.ensured)
        assertEquals(1, index.updateContentCalls)
        val unchanged = repo.applyEdits(saved.id, 1, emptyList())
        assertEquals(first.summary, unchanged.summary)
        assertEquals(first.documentRevision, unchanged.documentRevision)
        assertArrayEquals(first.currentSource.bytes(), unchanged.currentSource.bytes())
        assertEquals(writes, files.ensured)
    }

    @Test fun invalidV1EditRetainsOriginalKindAcrossRestartAndExplicitRepairWorks() = runBlocking<Unit> {
        val saved = repository().saveImported("keeper.json", source())
        val missing = repository().applyEdits(saved.id, 0, listOf(JsonPatch.Remove("/name")))
        assertTrue(missing.summary.isDraft)
        assertEquals(SourceFormat.V1, missing.summary.sourceFormat)
        val reopened = repository().load(saved.id)
        assertEquals(SourceFormat.V1, reopened.summary.sourceFormat)
        assertArrayEquals(preservedV1, reopened.source.bytes())
        val engine = CharacterCardEngine()
        val document = (engine.restore(reopened.source, reopened.currentSource, 1) as ParseResult.Success).document
        assertTrue(engine.export(document) is ExportResult.Failure)
        assertFalse(document.raw.containsKey("name"))
        val repaired = repository().applyEdits(saved.id, 1, listOf(JsonPatch.Add("/name", JsonPrimitive("Repaired"))))
        assertFalse(repaired.summary.isDraft)
        assertEquals(2L, repaired.documentRevision)
        assertArrayEquals(preservedV1, repaired.source.bytes())
    }

    @Test fun explicitMissingV3GroupRepairKeepsOriginalDraftBytesAndPreventsVersionChanges() = runBlocking<Unit> {
        val json = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Draft","description":"","personality":"","scenario":"","first_mes":"","mes_example":"","creator_notes":"","system_prompt":"","post_history_instructions":"","alternate_greetings":[],"tags":[],"creator":"","character_version":"","extensions":{"n":1.2300e+09}}}"""
        val original = SourceEnvelope(json.encodeToByteArray())
        val repo = repository()
        val saved = repo.saveImported("draft.json", original)
        assertTrue(saved.isDraft)
        val repaired = repo.applyEdits(saved.id, 0, listOf(JsonPatch.Add("/data/group_only_greetings", JsonArray(emptyList()))))
        assertFalse(repaired.summary.isDraft)
        assertArrayEquals(original.bytes(), repaired.source.bytes())
        failure(ProjectStorageError.INVALID_INPUT) {
            repo.applyEdits(saved.id, 1, listOf(JsonPatch.Replace("/spec_version", JsonPrimitive("2.0"))))
        }
        assertArrayEquals(repaired.currentSource.bytes(), repo.load(saved.id).currentSource.bytes())
        assertEquals(1L, repo.load(saved.id).documentRevision)
    }

    @Test fun unknownAndFutureSourcesStayUnchangedAndCannotBeEdited() = runBlocking<Unit> {
        val repo = repository()
        listOf("{\"unknown\":null}", "{\"spec\":\"chara_card_v3\",\"spec_version\":\"4.0\",\"data\":{}}").forEach { text ->
            val original = SourceEnvelope(text.encodeToByteArray())
            val saved = repo.saveImported("unknown.json", original)
            failure(ProjectStorageError.INVALID_INPUT) {
                repo.applyEdits(saved.id, 0, listOf(JsonPatch.Add("/extra", JsonPrimitive("edit"))))
            }
            val loaded = repo.load(saved.id)
            assertEquals(0L, loaded.documentRevision)
            assertArrayEquals(original.bytes(), loaded.source.bytes())
            assertArrayEquals(original.bytes(), loaded.currentSource.bytes())
        }
        assertEquals(0, index.updateContentCalls)
    }

    @Test fun editedDuplicateSharesBothSnapshotsAndLaterChangesAreIndependent() = runBlocking<Unit> {
        val repo = repository()
        val saved = repo.saveImported("keeper.json", source())
        val first = repo.applyEdits(saved.id, 0, name("First"))
        val copy = repo.duplicate(saved.id)
        val copyLoaded = repo.load(copy.id)
        assertEquals(first.documentRevision, copyLoaded.documentRevision)
        assertArrayEquals(first.currentSource.bytes(), copyLoaded.currentSource.bytes())
        val second = repo.applyEdits(saved.id, 1, name("Second"))
        assertArrayEquals(first.currentSource.bytes(), repo.load(copy.id).currentSource.bytes())
        repo.delete(saved.id)
        repository().recover()
        assertArrayEquals(preservedV1, repository().load(copy.id).source.bytes())
        assertArrayEquals(first.currentSource.bytes(), repository().load(copy.id).currentSource.bytes())
        assertFalse(File(directory, "${second.currentSource.sha256}.json").exists())
        repo.delete(copy.id)
        assertTrue(index.records.isEmpty())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun failedCurrentWriteRetainsPreviouslySavedOriginalCurrentAndRevision() = runBlocking<Unit> {
        val repo = repository()
        val saved = repo.saveImported("keeper.json", source())
        val before = repo.applyEdits(saved.id, 0, name("Saved"))
        files.beforeEnsure = { throw IOException("private /provider/secret/current.json") }
        val error = failure(ProjectStorageError.IO_FAILURE) { repo.applyEdits(saved.id, 1, name("Rejected")) }
        assertFalse(error.message.orEmpty().contains("/provider/secret"))
        val loaded = repo.load(saved.id)
        assertEquals(before.summary, loaded.summary)
        assertEquals(1L, loaded.documentRevision)
        assertArrayEquals(before.currentSource.bytes(), loaded.currentSource.bytes())
        assertArrayEquals(preservedV1, loaded.source.bytes())
    }

    @Test fun failedConditionalUpdatePrunesOnlyUnreferencedCandidate() = runBlocking<Unit> {
        val repo = repository()
        val saved = repo.saveImported("keeper.json", source())
        val before = repo.applyEdits(saved.id, 0, name("Saved"))
        index.beforeUpdateContent = { throw IllegalStateException("update unavailable") }
        failure(ProjectStorageError.DATABASE_FAILURE) { repo.applyEdits(saved.id, 1, name("Rejected")) }
        assertArrayEquals(before.currentSource.bytes(), repo.load(saved.id).currentSource.bytes())
        assertEquals(setOf("${before.source.sha256}.json", "${before.currentSource.sha256}.json"),
            directory.listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test fun compareAndSetFailureCannotOverwriteConcurrentRevision() = runBlocking<Unit> {
        val repo = repository()
        val saved = repo.saveImported("keeper.json", source())
        index.beforeUpdateContent = {
            val old = index.records.getValue(saved.id)
            index.records[saved.id] = old.copy(documentRevision = 1,
                summary = old.summary.copy(title = "Concurrent metadata"))
        }
        failure(ProjectStorageError.REVISION_CONFLICT) { repo.applyEdits(saved.id, 0, name("Rejected")) }
        val loaded = repo.load(saved.id)
        assertEquals("Concurrent metadata", loaded.summary.title)
        assertEquals(1L, loaded.documentRevision)
        assertArrayEquals(preservedV1, loaded.currentSource.bytes())
        assertEquals(1, directory.listFiles().orEmpty().size)
    }

    @Test fun ambiguousCommittedEditAndFailedReferenceReadNeverDeleteCurrentOrOriginal() = runBlocking<Unit> {
        val repo = repository()
        val saved = repo.saveImported("keeper.json", source())
        index.afterUpdateContent = {
            index.beforeAll = { throw IllegalStateException("reference scan unavailable") }
            throw IllegalStateException("commit result unavailable")
        }
        failure(ProjectStorageError.DATABASE_FAILURE) { repo.applyEdits(saved.id, 0, name("Committed")) }
        val record = index.records.getValue(saved.id)
        assertEquals(1L, record.documentRevision)
        assertTrue(File(directory, record.sourceFileKey).isFile)
        assertTrue(File(directory, record.currentFileKey).isFile)
        index.beforeAll = {}
        val loaded = repository().load(saved.id)
        assertArrayEquals(preservedV1, loaded.source.bytes())
        assertTrue(loaded.currentSource.bytes().decodeToString().contains("Committed"))
    }

    @Test fun missingOrCorruptCurrentSnapshotKeepsOriginalAndSavedRow() = runBlocking<Unit> {
        val repo = repository()
        val saved = repo.saveImported("keeper.json", source())
        repo.applyEdits(saved.id, 0, name("Edited"))
        val record = index.records.getValue(saved.id)
        val current = File(directory, record.currentFileKey)
        current.writeText("corrupt current")
        failure(ProjectStorageError.CORRUPT_SOURCE) { repository().load(saved.id) }
        failure(ProjectStorageError.CORRUPT_SOURCE) { repository().duplicate(saved.id) }
        assertArrayEquals(preservedV1, File(directory, record.sourceFileKey).readBytes())
        assertTrue(current.delete())
        failure(ProjectStorageError.CORRUPT_SOURCE) { repository().load(saved.id) }
        assertEquals(1, index.records.size)
        assertTrue(File(directory, record.sourceFileKey).exists())
    }

    @Test fun cancelledEditWaitingForLockDoesNotWriteOrIncrement() = runBlocking<Unit> {
        val saved = repository().saveImported("keeper.json", source())
        val writes = files.ensured
        val mutex = Mutex(locked = true)
        val task = launch(start = CoroutineStart.UNDISPATCHED) { repository(mutex).applyEdits(saved.id, 0, name("Cancelled")) }
        task.cancelAndJoin()
        mutex.unlock()
        assertEquals(writes, files.ensured)
        assertEquals(0, index.updateContentCalls)
        assertEquals(0L, index.records.getValue(saved.id).documentRevision)
    }

    @Test fun cancellationAfterEditCommitStartsKeepsCommittedSnapshotsConsistent() = runBlocking<Unit> {
        val repo = repository()
        val saved = repo.saveImported("keeper.json", source())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        index.beforeUpdateContent = { entered.complete(Unit); release.await() }
        val task = launch { repo.applyEdits(saved.id, 0, name("Committed")) }
        withTimeout(5000) { entered.await() }
        task.cancel()
        release.complete(Unit)
        withTimeout(5000) { task.join() }
        assertTrue(task.isCancelled)
        val loaded = repository().load(saved.id)
        assertEquals(1L, loaded.documentRevision)
        assertArrayEquals(preservedV1, loaded.source.bytes())
        assertTrue(loaded.currentSource.bytes().decodeToString().contains("Committed"))
    }
}

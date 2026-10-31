package com.valhalla.tavernforge.data

import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.core.model.JsonLimits
import com.valhalla.tavernforge.format.CharacterCardEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RoomProjectRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val index = FaultIndex()
    private var sequence = 0
    private var time = 1000L
    private val directory by lazy { temporary.newFolder("managed") }
    private val disk by lazy { ManagedJsonFiles(directory) }
    private val files by lazy { FaultFiles(disk) }
    private fun repository(mutex: Mutex = Mutex()) = RoomProjectRepository(index, files,
        clock = { ++time }, newId = { "project-${++sequence}" }, dispatcher = Dispatchers.Unconfined, mutex = mutex)

    private suspend fun failure(code: ProjectStorageError, action: suspend () -> Unit): ProjectStorageException {
        try { action(); fail("Expected $code") } catch (error: ProjectStorageException) {
            assertEquals(code, error.code)
            assertTrue("An actionable message is required", error.message.orEmpty().isNotBlank())
            return error
        }
        error("Unreachable")
    }

    @Test fun saveAndNewRepositoryReloadPreserveOriginalBytesAndMetadata() = runBlocking<Unit> {
        val imported = SourceEnvelope(preservedV1, "application/example+json")
        val saved = repository().saveImported("keeper.json", imported)
        val loaded = repository().load(saved.id)
        assertEquals(saved, loaded.summary)
        assertEquals("Archive Keeper", saved.title)
        assertEquals("keeper.json", saved.sourceFileName)
        assertEquals(SourceFormat.V1, saved.sourceFormat)
        assertFalse(saved.isDraft)
        assertEquals(imported.sha256, saved.sourceSha256)
        assertEquals(imported.sizeBytes, saved.sourceSizeBytes)
        assertEquals(imported.mediaType, loaded.source.mediaType)
        assertEquals(0L, loaded.documentRevision)
        assertArrayEquals(preservedV1, loaded.source.bytes())
        assertEquals(listOf(saved), repository().observeProjects().first())

        val longestMediaType = "application/" + "x".repeat(243)
        val exact = repository().saveImported("exact.json", SourceEnvelope(preservedV1, longestMediaType))
        assertEquals(longestMediaType, repository().load(exact.id).source.mediaType)
        failure(ProjectStorageError.INVALID_INPUT) {
            repository().saveImported("overlong.json", SourceEnvelope(preservedV1, longestMediaType + "x"))
        }
        assertEquals("Overlong metadata must not create a truncated saved record", 2, index.records.size)
    }

    @Test fun titleRenameChangesOnlyLibraryMetadataAndSurvivesReload() = runBlocking<Unit> {
        val repo = repository()
        val saved = repo.saveImported("keeper.json", source())
        val before = index.records.getValue(saved.id)
        repo.rename(saved.id, "My local title")
        val after = repository().load(saved.id)
        assertEquals("My local title", after.summary.title)
        assertEquals(saved.createdAtEpochMillis, after.summary.createdAtEpochMillis)
        assertTrue(after.summary.updatedAtEpochMillis >= saved.updatedAtEpochMillis)
        assertEquals(before.sourceFileKey, index.records.getValue(saved.id).sourceFileKey)
        assertEquals(saved.sourceSha256, after.summary.sourceSha256)
        assertArrayEquals(preservedV1, after.source.bytes())
        assertTrue(after.source.bytes().decodeToString().contains("Archive Keeper"))
    }

    @Test fun duplicateSharesImmutableSourceAndDeletingEitherProjectCannotBreakTheOther() = runBlocking<Unit> {
        val repo = repository()
        val first = repo.saveImported("keeper.json", source())
        val copy = repo.duplicate(first.id)
        assertNotEquals(first.id, copy.id)
        assertEquals(first.sourceSha256, copy.sourceSha256)
        assertEquals(index.records.getValue(first.id).sourceFileKey, index.records.getValue(copy.id).sourceFileKey)
        repo.rename(copy.id, "Independent title")
        assertEquals(first.title, repo.load(first.id).summary.title)
        repo.delete(first.id)
        assertArrayEquals(preservedV1, repository().load(copy.id).source.bytes())
        repo.delete(copy.id)
        assertTrue(index.records.isEmpty())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun malformedAndDuplicateKeyJsonNeverReachFileOrRowCommit() = runBlocking<Unit> {
        listOf("{", "[]", "{\"name\":1,\"name\":2}").forEach { json ->
            failure(ProjectStorageError.INVALID_INPUT) { repository().saveImported("bad.json", source(json.encodeToByteArray())) }
        }
        val bounded = RoomProjectRepository(index, files, engine = CharacterCardEngine(JsonLimits(maxBytes = 16)), dispatcher = Dispatchers.Unconfined)
        failure(ProjectStorageError.INVALID_INPUT) { bounded.saveImported("oversized.json", source()) }
        assertTrue(index.records.isEmpty())
        assertEquals(0, index.insertCalls)
        assertEquals(0, files.ensured)
    }

    @Test fun invalidV3AndUnknownFutureJsonRemainExactSavedDrafts() = runBlocking<Unit> {
        val v3 = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Draft","description":"","personality":"","scenario":"","first_mes":"","mes_example":"","creator_notes":"","system_prompt":"","post_history_instructions":"","alternate_greetings":[],"tags":[],"creator":"","character_version":"","extensions":{"null":null,"number":1.2300e+09}}}"""
        val future = """{ "spec":"chara_card_v4", "data":{"name":"Future","unknown":[null,1e-8]} }"""
        listOf(v3 to SourceFormat.V3, future to SourceFormat.UNKNOWN).forEach { (json, format) ->
            val saved = repository().saveImported("draft.json", source(json.encodeToByteArray()))
            assertTrue(saved.isDraft)
            assertEquals(format, saved.sourceFormat)
            val loaded = repository().load(saved.id)
            assertArrayEquals(json.encodeToByteArray(), loaded.source.bytes())
            assertEquals(source(json.encodeToByteArray()).sha256, loaded.source.sha256)
            assertFalse(loaded.source.bytes().decodeToString().contains("group_only_greetings"))
            val duplicated = repository().duplicate(saved.id)
            assertTrue(duplicated.isDraft)
            assertArrayEquals(json.encodeToByteArray(), repository().load(duplicated.id).source.bytes())
        }
    }

    @Test fun sourceWriteFailureProducesNoSavedRowAndDoesNotLeakProviderText() = runBlocking<Unit> {
        files.beforeEnsure = { throw IOException("private /provider/path with JSON content") }
        val error = failure(ProjectStorageError.IO_FAILURE) { repository().saveImported("keeper.json", source()) }
        assertFalse(error.message.orEmpty().contains("/provider/path"))
        assertTrue(index.records.isEmpty())
    }

    @Test fun rejectedDatabaseInsertCanReclaimOnlyUnreferencedSource() = runBlocking<Unit> {
        val repo = repository()
        val original = repo.saveImported("keeper.json", source())
        index.beforeInsert = { throw IllegalStateException("database rejected insert") }
        failure(ProjectStorageError.DATABASE_FAILURE) { repo.saveImported("same.json", source()) }
        assertEquals(1, index.records.size)
        assertArrayEquals(preservedV1, repo.load(original.id).source.bytes())
        failure(ProjectStorageError.DATABASE_FAILURE) { repo.saveImported("other.json", source("{\"other\":true}".encodeToByteArray())) }
        assertEquals(1, directory.listFiles().orEmpty().size)
    }

    @Test fun committedInsertThatReportsFailureRetainsItsReferencedSource() = runBlocking<Unit> {
        index.afterInsert = { throw IllegalStateException("failure after commit") }
        failure(ProjectStorageError.DATABASE_FAILURE) { repository().saveImported("keeper.json", source()) }
        assertEquals(1, index.records.size)
        val record = index.records.values.single()
        assertArrayEquals(preservedV1, repository().load(record.summary.id).source.bytes())
    }

    @Test fun failedReferenceRequeryNeverPrunesAnAmbiguousCommittedSave() = runBlocking<Unit> {
        index.afterInsert = {
            index.beforeAll = { throw IllegalStateException("requery unavailable") }
            throw IllegalStateException("uncertain commit result")
        }
        failure(ProjectStorageError.DATABASE_FAILURE) { repository().saveImported("keeper.json", source()) }
        val record = index.records.values.single()
        assertTrue(File(directory, record.sourceFileKey).isFile)
        assertEquals(1, files.prunedReferences.size) // Successful initial recovery only.
        index.beforeAll = {}
        assertArrayEquals(preservedV1, repository().load(record.summary.id).source.bytes())
    }

    @Test fun recoveryRequiresSuccessfulCompleteIndexReadBeforePruning() = runBlocking<Unit> {
        val sourceKey = disk.ensureSource(source())
        index.beforeAll = { throw IllegalStateException("index unavailable") }
        failure(ProjectStorageError.DATABASE_FAILURE) { repository().recover() }
        assertTrue(File(directory, sourceKey).isFile)
        assertTrue(files.prunedReferences.isEmpty())
        index.beforeAll = {}
        repository().recover()
        assertFalse(File(directory, sourceKey).exists())
    }

    @Test fun pruneFailureDoesNotBlockHealthyReopenOrDeleteAndLaterRecoveryReclaimsOrphan() = runBlocking<Unit> {
        val saved = repository().saveImported("keeper.json", source())
        val sourceKey = index.records.getValue(saved.id).sourceFileKey
        files.beforePrune = { throw IOException("orphan cleanup unavailable") }
        val reopened = repository()
        assertArrayEquals(preservedV1, reopened.load(saved.id).source.bytes())
        assertEquals(listOf(saved), reopened.observeProjects().first())
        reopened.delete(saved.id)
        assertTrue(index.records.isEmpty())
        assertTrue(File(directory, sourceKey).exists())
        files.beforePrune = {}
        repository().recover()
        assertFalse(File(directory, sourceKey).exists())
    }

    @Test fun missingCorruptAndUnsupportedRevisionSourcesCannotLoadOrDuplicate() = runBlocking<Unit> {
        val repo = repository()
        val saved = repo.saveImported("keeper.json", source())
        val record = index.records.getValue(saved.id)
        val path = File(directory, record.sourceFileKey)
        path.writeText("private corrupt payload")
        val corrupt = failure(ProjectStorageError.CORRUPT_SOURCE) { repo.load(saved.id) }
        assertFalse(corrupt.message.orEmpty().contains("private corrupt payload"))
        failure(ProjectStorageError.CORRUPT_SOURCE) { repo.duplicate(saved.id) }
        assertEquals(1, index.records.size)
        path.delete()
        failure(ProjectStorageError.CORRUPT_SOURCE) { repo.load(saved.id) }
        disk.ensureSource(source())
        index.records[saved.id] = record.copy(documentRevision = 1)
        failure(ProjectStorageError.CORRUPT_SOURCE) { repo.load(saved.id) }
        index.records[saved.id] = record.copy(summary = record.summary.copy(sourceFormat = SourceFormat.V3))
        failure(ProjectStorageError.CORRUPT_SOURCE) { repo.load(saved.id) }
    }

    @Test fun failedRenameAndDeleteKeepSavedSourceAndMetadata() = runBlocking<Unit> {
        val repo = repository()
        val saved = repo.saveImported("keeper.json", source())
        index.beforeRename = { throw IllegalStateException("rename unavailable") }
        failure(ProjectStorageError.DATABASE_FAILURE) { repo.rename(saved.id, "Changed") }
        index.beforeDelete = { throw IllegalStateException("delete unavailable") }
        failure(ProjectStorageError.DATABASE_FAILURE) { repo.delete(saved.id) }
        assertEquals(saved, repo.load(saved.id).summary)
        assertArrayEquals(preservedV1, repo.load(saved.id).source.bytes())
    }

    @Test fun cancelledSaveWaitingForWriterLockCreatesNothing() = runBlocking<Unit> {
        val mutex = Mutex(locked = true)
        val repo = repository(mutex)
        val task = launch(start = CoroutineStart.UNDISPATCHED) { repo.saveImported("keeper.json", source()) }
        task.cancelAndJoin()
        mutex.unlock()
        assertTrue(index.records.isEmpty())
        assertEquals(0, files.ensured)
    }

    @Test fun cancellationAfterCommitStartsFinishesConsistentlyAndKeepsSource() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        index.beforeInsert = { entered.complete(Unit); release.await() }
        val task = launch { repository().saveImported("keeper.json", source()) }
        withTimeout(5000) { entered.await() }
        task.cancel()
        release.complete(Unit)
        withTimeout(5000) { task.join() }
        assertTrue(task.isCancelled)
        val record = index.records.values.single()
        assertArrayEquals(preservedV1, repository().load(record.summary.id).source.bytes())
    }
}

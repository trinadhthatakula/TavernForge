package com.valhalla.tavernforge

import androidx.lifecycle.ViewModelStore
import com.valhalla.tavernforge.core.model.ProjectSummary
import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.StoredProject
import com.valhalla.tavernforge.data.ProjectRepository
import com.valhalla.tavernforge.data.ProjectStorageError
import com.valhalla.tavernforge.data.ProjectStorageException
import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.ExportResult
import com.valhalla.tavernforge.format.JsonPatch
import com.valhalla.tavernforge.format.ParseResult
import com.valhalla.tavernforge.format.PatchResult
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JsonEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val engine = CharacterCardEngine()
    private val repository = EditorRepository(engine)
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { stores.forEach(ViewModelStore::clear); Dispatchers.resetMain() }
    private fun model() = InspectionViewModel(engine, null, repository, object : LibraryPreferences {
        override val sort = MutableStateFlow(ProjectSort.RECENT)
        override suspend fun setSort(sort: ProjectSort) { this.sort.value = sort }
    }, dispatcher).also { vm -> stores += ViewModelStore().also { it.put("editor", vm) } }
    private fun card() = (engine.createV2("Original").source.bytes().decodeToString().dropLast(1) +
        ",\"unknown\":{\"number\":1e+02,\"nothing\":null,\"enabled\":false}}").encodeToByteArray()
    private suspend fun save(bytes: ByteArray = card()) = repository.saveImported("source.json", SourceEnvelope(bytes))

    @Test fun namedEditIsExplicitAndSavedRevisionPreservesOriginalAndUnknownValues() = runTest(dispatcher) {
        val original = card(); val saved = save(original); val vm = model()
        vm.openProject(saved.id); advanceUntilIdle()
        assertTrue(vm.beginEditing()); advanceUntilIdle()
        vm.editField("/data/name"); advanceUntilIdle()
        vm.setEditText("Revised")
        assertEquals(0, repository.edits)
        assertTrue(vm.state.value.editor!!.hasUnappliedInput)
        vm.saveEdits(); advanceUntilIdle(); assertEquals(0, repository.edits)
        vm.applyEditInput(); advanceUntilIdle()
        assertEquals(listOf("/data/name"), vm.state.value.editor!!.pendingPaths)
        assertEquals("Original", vm.state.value.summary!!.documentName)
        vm.saveEdits(); advanceUntilIdle()
        val stored = repository.load(saved.id)
        assertArrayEquals(original, stored.source.bytes())
        assertEquals(1L, stored.documentRevision)
        assertTrue(stored.currentSource.bytes().decodeToString().contains("1e+02"))
        assertTrue(stored.currentSource.bytes().decodeToString().contains("\"nothing\":null"))
        assertEquals("Revised", vm.state.value.summary!!.documentName)
        assertNull(vm.state.value.editor)
        val reopened = model(); reopened.openProject(saved.id); advanceUntilIdle()
        assertEquals("Revised", reopened.state.value.summary!!.documentName)
        assertEquals(1L, reopened.state.value.documentRevision)
    }

    @Test fun missingGroupGreetingsAreAddedOnlyAfterExplicitApplyAndSave() = runTest(dispatcher) {
        val source = engine.createV2("V3").source.bytes().decodeToString()
            .replace("chara_card_v2", "chara_card_v3").replace("2.0", "3.0").encodeToByteArray()
        val saved = save(source); val vm = model(); vm.openProject(saved.id); advanceUntilIdle()
        assertEquals(InspectionStatus.NEEDS_REVIEW, vm.state.value.summary!!.status)
        vm.beginEditing(); advanceUntilIdle(); vm.editField("/data/group_only_greetings"); advanceUntilIdle()
        assertEquals(EditValueMode.JSON, vm.state.value.editor!!.input!!.mode)
        assertEquals("[]", vm.state.value.editor!!.input!!.text)
        assertFalse(repository.load(saved.id).currentSource.bytes().decodeToString().contains("group_only_greetings"))
        vm.applyEditInput(); advanceUntilIdle(); vm.saveEdits(); advanceUntilIdle()
        assertEquals(InspectionStatus.VALID, vm.state.value.summary!!.status)
        assertArrayEquals(source, repository.load(saved.id).source.bytes())
    }

    @Test fun wrongTypeDoesNotBecomeTextFromOpeningTheNamedField() = runTest(dispatcher) {
        val source = card().decodeToString().replace("\"name\":\"Original\"", "\"name\":17").encodeToByteArray()
        val saved = save(source); val vm = model(); vm.openProject(saved.id); advanceUntilIdle()
        vm.beginEditing(); advanceUntilIdle(); vm.editField("/data/name"); advanceUntilIdle()
        assertEquals(EditValueMode.JSON, vm.state.value.editor!!.input!!.mode)
        assertEquals("17", vm.state.value.editor!!.input!!.text)
        assertFalse(vm.state.value.editor!!.hasChanges)
        vm.discardEdits()
        assertArrayEquals(source, repository.load(saved.id).currentSource.bytes())
    }

    @Test fun invalidAdvancedJsonAndFormatChangesAreRejectedWithoutTouchingStoredBytes() = runTest(dispatcher) {
        val saved = save(); val vm = model(); vm.openProject(saved.id); advanceUntilIdle(); vm.beginEditing(); advanceUntilIdle()
        vm.editAdvanced(); vm.setEditPath("/unknown"); vm.setEditText("{\"n\":1,\"n\":2}")
        vm.applyEditInput(); advanceUntilIdle()
        assertTrue(vm.state.value.editor!!.editIssues.isNotEmpty())
        assertTrue(vm.state.value.editor!!.pendingPaths.isEmpty())
        vm.cancelEditInput(); vm.editAdvanced(); vm.setEditPath("/spec_version"); vm.setEditText("\"3.0\"")
        vm.applyEditInput(); advanceUntilIdle()
        assertEquals("CONVERSION_REQUIRED", vm.state.value.editor!!.editIssues.single().code)
        assertEquals(0, repository.edits)
    }

    @Test fun revisionConflictRetainsPendingInputAndSavedSource() = runTest(dispatcher) {
        val saved = save(); val vm = model(); vm.openProject(saved.id); advanceUntilIdle()
        vm.beginEditing(); advanceUntilIdle(); vm.editField("/data/name"); advanceUntilIdle(); vm.setEditText("Pending")
        vm.applyEditInput(); advanceUntilIdle()
        repository.failure = ProjectStorageError.REVISION_CONFLICT
        vm.saveEdits(); advanceUntilIdle()
        assertEquals(ProjectStorageError.REVISION_CONFLICT, vm.state.value.storageFailure)
        assertEquals(listOf("/data/name"), vm.state.value.editor!!.pendingPaths)
        assertEquals(0L, repository.load(saved.id).documentRevision)
        assertFalse(vm.state.value.busy)
    }

    @Test fun advancedSetReplacesExistingArrayItemsIncludingNullWithoutInsertion() = runTest(dispatcher) {
        val source = card().decodeToString().replace("\"unknown\":{", "\"values\":[null,\"keep\",17],\"unknown\":{").encodeToByteArray()
        val saved = save(source); val vm = model(); vm.openProject(saved.id); advanceUntilIdle()
        vm.beginEditing(); advanceUntilIdle()
        vm.editAdvanced(); vm.setEditPath("/values/0"); vm.setEditText("\"changed\"")
        vm.applyEditInput(); advanceUntilIdle()
        vm.editAdvanced(); vm.setEditPath("/values/2"); vm.setEditText("18")
        vm.applyEditInput(); advanceUntilIdle(); vm.saveEdits(); advanceUntilIdle()
        val doc = (engine.parse(repository.load(saved.id).currentSource.bytes()) as ParseResult.Success).document
        assertEquals("[\"changed\",\"keep\",18]", doc.raw["values"].toString())
        assertArrayEquals(source, repository.load(saved.id).source.bytes())
    }

    @Test fun pendingInputPreventsReplacementAndCanBeExplicitlyDiscarded() = runTest(dispatcher) {
        val first = save(); val second = save(engine.createV2("Other").source.bytes()); val vm = model()
        vm.openProject(first.id); advanceUntilIdle(); vm.beginEditing(); advanceUntilIdle(); vm.editField("/data/name"); advanceUntilIdle()
        vm.setEditText("Typing")
        vm.openProject(second.id); vm.clear(); advanceUntilIdle()
        assertEquals(first.id, vm.state.value.selectedProject!!.id)
        assertEquals("Typing", vm.state.value.editor!!.input!!.text)
        vm.discardEdits(); vm.openProject(second.id); advanceUntilIdle()
        assertEquals(second.id, vm.state.value.selectedProject!!.id)
    }

    @Test fun repeatedSaveTapsCommitOnce() = runTest(dispatcher) {
        val saved = save(); val vm = model(); vm.openProject(saved.id); advanceUntilIdle()
        vm.beginEditing(); advanceUntilIdle(); vm.editField("/data/name"); advanceUntilIdle(); vm.setEditText("Once")
        vm.applyEditInput(); advanceUntilIdle(); repository.gate = CompletableDeferred()
        vm.saveEdits(); vm.saveEdits(); runCurrent(); assertEquals(1, repository.edits)
        repository.gate!!.complete(Unit); advanceUntilIdle(); assertEquals(1L, vm.state.value.documentRevision)
    }

    @Test fun requiredFieldRemovalSavesDraftAndBlocksStrictExport() = runTest(dispatcher) {
        val saved = save(); val vm = model(); vm.openProject(saved.id); advanceUntilIdle()
        vm.beginEditing(); advanceUntilIdle(); vm.editField("/data/name"); advanceUntilIdle(); vm.setEditAction(EditAction.REMOVE)
        vm.applyEditInput(); advanceUntilIdle(); vm.saveEdits(); advanceUntilIdle()
        assertEquals(InspectionStatus.NEEDS_REVIEW, vm.state.value.summary!!.status)
        vm.prepareExport(); advanceUntilIdle(); assertNull(vm.state.value.exportPreview)
        assertFalse(vm.state.value.canExport)
    }

    @Test fun unsupportedSavedJsonRemainsInspectableButCannotEditOrExport() = runTest(dispatcher) {
        val saved = save("{\"spec\":\"future\",\"data\":null}".encodeToByteArray())
        val vm = model(); vm.openProject(saved.id); advanceUntilIdle()
        assertFalse(vm.beginEditing()); vm.prepareExport(); advanceUntilIdle()
        assertNull(vm.state.value.editor); assertNull(vm.state.value.exportPreview)
    }

    @Test fun oversizedInputIsNotSilentlyTruncatedIntoAnAppliedValue() = runTest(dispatcher) {
        val saved = save(); val vm = model(); vm.openProject(saved.id); advanceUntilIdle()
        vm.beginEditing(); advanceUntilIdle(); vm.editField("/data/name"); advanceUntilIdle()
        vm.setEditText("x".repeat(MAX_EDITOR_CHARS + 1)); vm.applyEditInput(); advanceUntilIdle()
        assertTrue(vm.state.value.editor!!.input!!.limitExceeded)
        assertEquals("Original", vm.state.value.editor!!.input!!.text)
        assertTrue(vm.state.value.editor!!.pendingPaths.isEmpty())
    }

    @Test fun exportCancellationAndPreviewCancellationNeverWrite() = runTest(dispatcher) {
        val saved = save(); val vm = model(); vm.openProject(saved.id); advanceUntilIdle()
        vm.prepareExport(); advanceUntilIdle(); assertNotNull(vm.state.value.exportPreview)
        vm.cancelExportPreview(); assertNull(vm.state.value.exportPreview)
        vm.prepareExport(); advanceUntilIdle(); vm.confirmExport()
        var writes = 0; vm.completeExportTo(false) { writes++ }; advanceUntilIdle()
        assertEquals(0, writes); assertNull(vm.state.value.exportPreview); assertNotEquals(LibraryNotice.EXPORTED, vm.state.value.notice)
    }

    @Test fun exportUsesFrozenBytesAndRejectsDuplicateDestinationCallbacks() = runTest(dispatcher) {
        val original = card(); val saved = save(original); val other = save(engine.createV2("Other").source.bytes())
        val vm = model(); vm.openProject(saved.id); advanceUntilIdle(); vm.prepareExport(); advanceUntilIdle()
        assertEquals(0L, vm.state.value.exportPreview!!.revision)
        assertFalse(vm.state.value.exportPreview!!.formattingChanged)
        assertNotNull(vm.confirmExport()); assertNull(vm.confirmExport())
        vm.openProject(other.id); vm.clear(); advanceUntilIdle()
        assertEquals(saved.id, vm.state.value.selectedProject!!.id)
        var bytes: ByteArray? = null; var writes = 0
        vm.completeExportTo(true) { writes++; bytes = it }; vm.completeExportTo(true) { writes++ }; advanceUntilIdle()
        assertEquals(1, writes); assertArrayEquals(original, bytes); assertEquals(LibraryNotice.EXPORTED, vm.state.value.notice)
    }

    @Test fun failedOutputDoesNotReportSuccessOrChangeTheProject() = runTest(dispatcher) {
        val original = card(); val saved = save(original); val vm = model(); vm.openProject(saved.id); advanceUntilIdle()
        vm.prepareExport(); advanceUntilIdle(); vm.confirmExport()
        vm.completeExportTo(true) { throw IOException("private provider details") }; advanceUntilIdle()
        assertEquals(ExportFailure.WRITE, vm.state.value.exportFailure)
        assertNull(vm.state.value.notice); assertFalse(vm.state.value.busy)
        assertArrayEquals(original, repository.load(saved.id).currentSource.bytes())
    }

    @Test fun dirtyEditorAndStaleSavedRevisionCannotStartExport() = runTest(dispatcher) {
        val saved = save(); val vm = model(); vm.openProject(saved.id); advanceUntilIdle()
        vm.beginEditing(); advanceUntilIdle(); vm.editField("/data/name"); advanceUntilIdle(); vm.setEditText("Unsaved")
        vm.prepareExport(); advanceUntilIdle(); assertNull(vm.state.value.exportPreview)
        vm.discardEdits()
        repository.applyEdits(saved.id, 0, listOf(JsonPatch.Add("/data/name", kotlinx.serialization.json.JsonPrimitive("External"))))
        vm.prepareExport(); advanceUntilIdle()
        assertNull(vm.state.value.exportPreview); assertEquals(ProjectStorageError.REVISION_CONFLICT, vm.state.value.storageFailure)
    }

    private class EditorRepository(private val engine: CharacterCardEngine) : ProjectRepository {
        private val docs = linkedMapOf<String, StoredProject>()
        private val flow = MutableStateFlow<List<ProjectSummary>>(emptyList())
        var edits = 0
        var gate: CompletableDeferred<Unit>? = null
        var failure: ProjectStorageError? = null
        override fun observeProjects() = flow
        override suspend fun saveImported(fileName: String, source: SourceEnvelope): ProjectSummary {
            val doc = (engine.parse(source.bytes()) as ParseResult.Success).document
            val id = "saved-${docs.size}"
            val summary = ProjectSummary(id, doc.displayName ?: fileName, fileName, doc.sourceFormat, doc.specVersion,
                !engine.validate(doc).isValid, 1, 1, source.sha256, source.sizeBytes)
            docs[id] = StoredProject(summary, source); publish(); return summary
        }
        override suspend fun load(id: String) = docs[id] ?: throw ProjectStorageException(ProjectStorageError.NOT_FOUND, "Missing")
        override suspend fun applyEdits(id: String, expectedRevision: Long, operations: List<JsonPatch>): StoredProject {
            edits++; gate?.await(); failure?.let { throw ProjectStorageException(it, "Conflict") }
            val old = load(id)
            if (old.documentRevision != expectedRevision) throw ProjectStorageException(ProjectStorageError.REVISION_CONFLICT, "Conflict")
            val base = (engine.restore(old.source, old.currentSource, old.documentRevision) as ParseResult.Success).document
            val current = (engine.patch(base, base.revision, operations) as PatchResult.Success).document
            val bytes = (engine.snapshot(current) as ExportResult.Success).bytes
            val updated = old.copy(currentSource = SourceEnvelope(bytes), documentRevision = current.revision,
                summary = old.summary.copy(isDraft = !engine.validate(current).isValid))
            docs[id] = updated; publish(); return updated
        }
        override suspend fun rename(id: String, title: String) { docs[id] = load(id).let { it.copy(summary = it.summary.copy(title = title)) }; publish() }
        override suspend fun duplicate(id: String): ProjectSummary {
            val old = load(id); val summary = old.summary.copy(id = "copy-${docs.size}")
            docs[summary.id] = old.copy(summary = summary); publish(); return summary
        }
        override suspend fun delete(id: String) { docs.remove(id); publish() }
        private fun publish() { flow.value = docs.values.map { it.summary } }
    }
}

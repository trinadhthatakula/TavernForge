package com.valhalla.tavernforge

import androidx.lifecycle.ViewModelStore
import com.valhalla.tavernforge.core.model.*
import com.valhalla.tavernforge.data.ProjectRepository
import com.valhalla.tavernforge.data.drafts.*
import com.valhalla.tavernforge.format.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditingRecoveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val engine = CharacterCardEngine()
    private val repository = RecoveryRepository(engine)
    private val journal = MemoryJournal()
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { stores.forEach(ViewModelStore::clear); Dispatchers.resetMain() }
    private fun model() = InspectionViewModel(engine, null, repository, object : LibraryPreferences {
        override val sort = MutableStateFlow(ProjectSort.RECENT)
        override suspend fun setSort(sort: ProjectSort) { this.sort.value = sort }
    }, dispatcher, journal = journal).also { vm -> stores += ViewModelStore().also { it.put("recovery", vm) } }
    private fun TestScope.editing(vm: InspectionViewModel) {
        advanceUntilIdle(); vm.openProject("saved"); advanceUntilIdle(); vm.beginEditing(); advanceUntilIdle()
        vm.editField("/data/name"); advanceUntilIdle()
    }

    @Test fun appliedPreviewAndPendingInputRecoverOnlyAfterChoiceWithAccuratePreviousValue() = runTest(dispatcher) {
        val vm = model(); editing(vm); vm.setEditText("Recovered name"); vm.applyEditInput(); advanceUntilIdle()
        vm.editField("/data/name"); advanceUntilIdle(); vm.setEditText("Unapplied proposal"); advanceUntilIdle()
        assertTrue(journal.record!!.hasAppliedChanges); assertEquals(0L, repository.stored.documentRevision)
        val restarted = model(); advanceUntilIdle()
        assertTrue(restarted.state.value.recoveryAvailable); assertNull(restarted.state.value.editor)
        restarted.openProject("saved"); advanceUntilIdle(); assertNull(restarted.state.value.selectedProject)
        restarted.recoverEdits(); advanceUntilIdle()
        assertTrue(restarted.state.value.recoveryOpened); assertEquals(listOf(""), restarted.state.value.editor!!.pendingPaths)
        assertTrue(restarted.state.value.editor!!.canUndo)
        assertEquals("Recovered name", restarted.state.value.editor!!.input!!.initialText)
        assertEquals("Unapplied proposal", restarted.state.value.editor!!.input!!.text)
        assertEquals(true, restarted.state.value.editor!!.input!!.field?.required)
        restarted.cancelEditInput(); restarted.undoEdit(); advanceUntilIdle()
        assertTrue(restarted.state.value.editor!!.pendingPaths.isEmpty())
    }

    @Test fun unfinishedFieldInputStaysUnappliedAndNeedsReviewAfterRecovery() = runTest(dispatcher) {
        val vm = model(); editing(vm); vm.setEditText("Unfinished input"); advanceUntilIdle()
        assertFalse(journal.record!!.hasAppliedChanges); assertEquals("Unfinished input", journal.record!!.input!!.text)
        val restarted = model(); advanceUntilIdle(); restarted.recoverEdits(); advanceUntilIdle()
        assertTrue(restarted.state.value.editor!!.hasUnappliedInput)
        assertEquals("Unfinished input", restarted.state.value.editor!!.input!!.text)
        assertEquals("Original", restarted.state.value.editor!!.input!!.initialText)
        assertEquals(true, restarted.state.value.editor!!.input!!.field?.required)
        restarted.saveEdits(); advanceUntilIdle(); assertEquals(0L, repository.stored.documentRevision)
        restarted.applyEditInput(); advanceUntilIdle(); restarted.saveEdits(); advanceUntilIdle()
        assertEquals(1L, repository.stored.documentRevision); assertNull(journal.record)
        assertEquals("Original", (engine.parse(repository.stored.source.bytes()) as ParseResult.Success).document.fields.name)
    }

    @Test fun staleRecoveryNeverOverwritesChangedProjectAndRequiresExplicitDiscard() = runTest(dispatcher) {
        val vm = model(); editing(vm); vm.setEditText("Stale"); vm.applyEditInput(); advanceUntilIdle()
        repository.stored = repository.stored.copy(documentRevision = 1)
        val restarted = model(); advanceUntilIdle(); restarted.recoverEdits(); advanceUntilIdle()
        assertTrue(restarted.state.value.recoveryAvailable); assertNotNull(restarted.state.value.recoveryError)
        assertNull(restarted.state.value.editor); assertNotNull(journal.record)
        restarted.discardRecovery(); advanceUntilIdle()
        assertFalse(restarted.state.value.recoveryAvailable); assertNull(journal.record)
        assertEquals(1L, repository.stored.documentRevision)
    }

    @Test fun delayedOldClearCompletesBeforeNewSessionCanStart() = runTest(dispatcher) {
        val vm = model(); editing(vm); vm.setEditText("Old"); vm.applyEditInput(); advanceUntilIdle()
        journal.clearGate = CompletableDeferred()
        vm.discardEdits(); vm.beginEditing(); runCurrent()
        assertEquals(1, journal.starts)
        journal.clearGate!!.complete(Unit); advanceUntilIdle(); assertEquals(2, journal.starts)
        journal.clearGate = null
        vm.editField("/data/name"); advanceUntilIdle(); vm.setEditText("New session"); advanceUntilIdle()
        assertEquals("New session", journal.record!!.input!!.text)
    }

    @Test fun undoToEmptyDoesNotOfferMeaninglessRecoveryOnRestart() = runTest(dispatcher) {
        val vm = model(); editing(vm); vm.setEditText("Transient"); vm.applyEditInput(); advanceUntilIdle()
        vm.undoEdit(); advanceUntilIdle()
        assertFalse(journal.record!!.hasAppliedChanges); assertNull(journal.record!!.input)
        val restarted = model(); advanceUntilIdle()
        assertFalse(restarted.state.value.recoveryAvailable); assertNull(journal.record)
    }

    @Test fun corruptJournalBlocksReplacementUntilExplicitDiscard() = runTest(dispatcher) {
        journal.failLoad = true
        val vm = model(); advanceUntilIdle()
        assertTrue(vm.state.value.recoveryAvailable); assertFalse(vm.state.value.recoveryCanRestore)
        vm.createManual(SourceFormat.V3, "Blocked"); advanceUntilIdle(); assertEquals(0, repository.creates)
        vm.discardRecovery(); advanceUntilIdle(); assertFalse(vm.state.value.recoveryAvailable)
    }

    private class MemoryJournal : EditJournal {
        var record: EditRecovery? = null
        var epoch = 0L; var starts = 0; var failLoad = false
        var clearGate: CompletableDeferred<Unit>? = null
        override suspend fun load(): EditRecovery? { if (failLoad) throw EditJournalException(EditJournalError.CORRUPT_JOURNAL); return record }
        override suspend fun startSession(): Long { starts++; return ++epoch }
        override suspend fun save(session: Long, recovery: EditRecovery): Boolean {
            if (session != epoch) return false
            record = recovery; return true
        }
        override suspend fun clear() { clearGate?.await(); epoch++; record = null; failLoad = false }
    }
    private class RecoveryRepository(private val engine: CharacterCardEngine) : ProjectRepository {
        private val original = engine.createV2("Original").source
        var stored = StoredProject(ProjectSummary("saved", "Project", "source.json", SourceFormat.V2, "2.0", false, 1, 1, original.sha256, original.sizeBytes), original)
        var creates = 0
        override fun observeProjects() = MutableStateFlow(listOf(stored.summary))
        override suspend fun load(id: String) = stored
        override suspend fun saveImported(fileName: String, source: SourceEnvelope): ProjectSummary { creates++; return stored.summary }
        override suspend fun applyEdits(id: String, expectedRevision: Long, operations: List<JsonPatch>): StoredProject {
            check(expectedRevision == stored.documentRevision)
            val base = (engine.restore(stored.source, stored.currentSource, stored.documentRevision) as ParseResult.Success).document
            val edited = (engine.patch(base, base.revision, operations) as PatchResult.Success).document
            stored = stored.copy(currentSource = SourceEnvelope((engine.snapshot(edited) as ExportResult.Success).bytes), documentRevision = edited.revision)
            return stored
        }
        override suspend fun rename(id: String, title: String) = Unit
        override suspend fun duplicate(id: String) = stored.summary
        override suspend fun delete(id: String) = Unit
    }
}

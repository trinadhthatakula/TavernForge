package com.valhalla.tavernforge

import androidx.lifecycle.ViewModelStore
import com.valhalla.tavernforge.core.model.ProjectSummary
import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.core.model.StoredProject
import com.valhalla.tavernforge.data.ProjectRepository
import com.valhalla.tavernforge.data.ProjectStorageError
import com.valhalla.tavernforge.data.ProjectStorageException
import com.valhalla.tavernforge.format.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualAuthoringViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val engine = CharacterCardEngine()
    private val repository = ManualRepository(engine)
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { stores.forEach(ViewModelStore::clear); Dispatchers.resetMain() }
    private fun model() = InspectionViewModel(engine, null, repository, object : LibraryPreferences {
        override val sort = MutableStateFlow(ProjectSort.RECENT)
        override suspend fun setSort(sort: ProjectSort) { this.sort.value = sort }
    }, dispatcher).also { vm -> stores += ViewModelStore().also { it.put("manual", vm) } }

    @Test fun allTemplatesCreateOneCommonRepositoryProjectAndRetainCreationOriginal() = runTest(dispatcher) {
        for (format in SourceFormat.entries.filter { it != SourceFormat.UNKNOWN }) {
            val vm = model()
            vm.createManual(format, "Local project")
            vm.createManual(format, "Repeated tap")
            advanceUntilIdle()
            assertTrue(vm.state.value.manualCreated)
            val saved = repository.load(vm.state.value.selectedProject!!.id)
            assertEquals(format, saved.summary.sourceFormat)
            assertArrayEquals(saved.source.bytes(), saved.currentSource.bytes())
            assertEquals(0L, saved.documentRevision)
            vm.consumeManualCreated(); assertFalse(vm.state.value.manualCreated)
            assertTrue(vm.beginEditing()); advanceUntilIdle()
            val path = if (format in setOf(SourceFormat.V2, SourceFormat.V3, SourceFormat.LOREBOOK_V3)) "/data/name" else "/name"
            vm.editField(path); advanceUntilIdle(); vm.setEditText("Created by hand"); vm.applyEditInput(); advanceUntilIdle()
            vm.saveEdits(); advanceUntilIdle()
            val edited = repository.load(saved.summary.id)
            assertArrayEquals(saved.source.bytes(), edited.source.bytes()); assertEquals(1L, edited.documentRevision)
            vm.prepareExport(); advanceUntilIdle(); assertNotNull(vm.state.value.exportPreview)
        }
        assertEquals(6, repository.created)
    }

    @Test fun typedArrayRetainsCommasNewlinesEmptyItemsAndExplicitEmptyRepair() = runTest(dispatcher) {
        val vm = model(); vm.createManual(SourceFormat.V3, "Arrays"); advanceUntilIdle(); vm.beginEditing(); advanceUntilIdle()
        vm.editField("/data/group_only_greetings"); advanceUntilIdle()
        assertEquals(emptyList<String>(), vm.state.value.editor!!.input!!.stringItems)
        vm.addStringItem(); vm.setStringItem(0, "hello, there\nsecond line"); vm.addStringItem()
        vm.applyEditInput(); advanceUntilIdle()
        vm.editField("/data/group_only_greetings"); advanceUntilIdle()
        assertEquals(listOf("hello, there\nsecond line", ""), vm.state.value.editor!!.input!!.stringItems)
        vm.cancelEditInput(); vm.saveEdits(); advanceUntilIdle()
        val bytes = repository.load(vm.state.value.selectedProject!!.id).currentSource.bytes().decodeToString()
        assertTrue(bytes.contains("hello, there\\nsecond line")); assertTrue(bytes.contains(",\"\"]"))
    }

    @Test fun optionalAbsentFalseAndInheritedNullRemainDistinctUntilApply() = runTest(dispatcher) {
        val saved = repository.saveImported("native.json", SourceEnvelope("{\"entries\":{\"0\":{\"uid\":0}}}".encodeToByteArray()))
        val vm = model(); vm.openProject(saved.id); advanceUntilIdle(); vm.beginEditing(); advanceUntilIdle()
        vm.selectLoreEntry(EntryLocator.NativeKey("0")); advanceUntilIdle(); vm.setAuthoringMode(AuthoringMode.ADVANCED); advanceUntilIdle()
        vm.editField("/entries/0/caseSensitive"); advanceUntilIdle()
        assertFalse(vm.state.value.editor!!.input!!.field!!.exists); assertFalse(vm.state.value.editor!!.hasChanges)
        vm.setTypedValue("null"); vm.applyEditInput(); advanceUntilIdle()
        vm.editField("/entries/0/caseSensitive"); advanceUntilIdle(); assertEquals("null", vm.state.value.editor!!.input!!.text)
        vm.setTypedValue("false"); vm.applyEditInput(); advanceUntilIdle()
        vm.undoEdit(); advanceUntilIdle()
        vm.selectLoreEntry(EntryLocator.NativeKey("0")); advanceUntilIdle(); vm.editField("/entries/0/caseSensitive"); advanceUntilIdle()
        assertEquals("null", vm.state.value.editor!!.input!!.text)
        vm.cancelEditInput(); vm.undoEdit(); advanceUntilIdle()
        assertTrue(vm.state.value.editor!!.pendingPaths.isEmpty())
        assertArrayEquals("{\"entries\":{\"0\":{\"uid\":0}}}".encodeToByteArray(), repository.load(saved.id).source.bytes())
    }

    @Test fun structuralCommandsResetStaleSelectionAndUndoRestoresDeletedEntry() = runTest(dispatcher) {
        val vm = model(); vm.createManual(SourceFormat.LOREBOOK_V3, "Book"); advanceUntilIdle(); vm.beginEditing(); advanceUntilIdle()
        vm.applyLoreCommand(LoreCommand.AddEntry()); advanceUntilIdle()
        assertEquals(EntryLocator.ArrayIndex(0), vm.state.value.editor!!.selectedEntry)
        vm.editField("/data/entries/0/content"); advanceUntilIdle(); vm.setEditText("original entry"); vm.applyEditInput(); advanceUntilIdle()
        vm.applyLoreCommand(LoreCommand.DuplicateEntry(EntryLocator.ArrayIndex(0))); advanceUntilIdle()
        assertEquals(2, vm.state.value.editor!!.entries.size)
        vm.applyLoreCommand(LoreCommand.DeleteEntry(EntryLocator.ArrayIndex(0))); advanceUntilIdle()
        assertNull(vm.state.value.editor!!.selectedEntry); assertEquals(1, vm.state.value.editor!!.entries.size)
        vm.undoEdit(); advanceUntilIdle(); assertEquals(2, vm.state.value.editor!!.entries.size)
        assertNull(vm.state.value.editor!!.selectedEntry)
        vm.selectLoreEntry(EntryLocator.ArrayIndex(0)); advanceUntilIdle(); vm.editField("/data/entries/0/content"); advanceUntilIdle()
        assertEquals("original entry", vm.state.value.editor!!.input!!.text)
    }

    @Test fun dirtyFieldBlocksStructuralHistoryAndGuidanceWaitsForExplicitApply() = runTest(dispatcher) {
        val vm = model(); vm.createManual(SourceFormat.V2, "Guidance"); advanceUntilIdle(); vm.beginEditing(); advanceUntilIdle()
        vm.prepareGuidance("description", "Appearance: ")
        assertTrue(vm.state.value.editor!!.hasUnappliedInput); assertEquals("/data/description", vm.state.value.editor!!.input!!.path)
        vm.applyLoreCommand(LoreCommand.CreateEmbeddedBook); vm.undoEdit(); advanceUntilIdle()
        assertTrue(vm.state.value.editor!!.pendingPaths.isEmpty()); assertEquals(0, repository.edits)
        vm.setEditText("Appearance: Silver coat"); vm.applyEditInput(); advanceUntilIdle()
        assertEquals(listOf("/data/description"), vm.state.value.editor!!.pendingPaths)
        vm.saveEdits(); advanceUntilIdle(); assertEquals(1, repository.edits)
        val doc = (engine.parse(repository.load(vm.state.value.selectedProject!!.id).currentSource.bytes()) as ParseResult.Success).document
        assertEquals("Appearance: Silver coat", doc.fields.description)
        assertFalse(doc.raw.toString().contains("appearance"))
    }

    @Test fun saveConflictKeepsHistoryAndUndoCanRecoverPendingCommands() = runTest(dispatcher) {
        val vm = model(); vm.createManual(SourceFormat.LOREBOOK_V2, "Conflict"); advanceUntilIdle(); vm.beginEditing(); advanceUntilIdle()
        vm.applyLoreCommand(LoreCommand.AddEntry()); advanceUntilIdle()
        repository.conflict = true; vm.saveEdits(); advanceUntilIdle()
        assertEquals(ProjectStorageError.REVISION_CONFLICT, vm.state.value.storageFailure)
        assertTrue(vm.state.value.editor!!.canUndo)
        vm.undoEdit(); advanceUntilIdle(); assertTrue(vm.state.value.editor!!.pendingPaths.isEmpty())
    }

    private class ManualRepository(private val engine: CharacterCardEngine) : ProjectRepository {
        private val docs = linkedMapOf<String, StoredProject>()
        private val projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
        var created = 0; var edits = 0; var conflict = false
        override fun observeProjects() = projects
        override suspend fun saveImported(fileName: String, source: SourceEnvelope): ProjectSummary {
            val doc = (engine.parse(source.bytes()) as ParseResult.Success).document
            val summary = ProjectSummary("manual-${created++}", fileName.removeSuffix(".json"), fileName, doc.sourceFormat, doc.specVersion,
                !engine.validate(doc).isValid, 1, 1, source.sha256, source.sizeBytes)
            docs[summary.id] = StoredProject(summary, source); publish(); return summary
        }
        override suspend fun load(id: String) = docs.getValue(id)
        override suspend fun applyEdits(id: String, expectedRevision: Long, operations: List<JsonPatch>): StoredProject {
            val old = load(id)
            if (conflict || old.documentRevision != expectedRevision) throw ProjectStorageException(ProjectStorageError.REVISION_CONFLICT, "Conflict")
            val original = (engine.restore(old.source, old.currentSource, old.documentRevision) as ParseResult.Success).document
            val edited = (engine.patch(original, expectedRevision, operations) as PatchResult.Success).document
            val saved = old.copy(currentSource = SourceEnvelope((engine.snapshot(edited) as ExportResult.Success).bytes), documentRevision = edited.revision)
            docs[id] = saved; edits++; publish(); return saved
        }
        override suspend fun rename(id: String, title: String) { docs[id] = load(id).let { it.copy(summary = it.summary.copy(title = title)) }; publish() }
        override suspend fun duplicate(id: String): ProjectSummary = error("Not part of this test")
        override suspend fun delete(id: String) { docs.remove(id); publish() }
        private fun publish() { projects.value = docs.values.map { it.summary } }
    }
}

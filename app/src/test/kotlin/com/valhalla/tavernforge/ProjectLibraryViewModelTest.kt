package com.valhalla.tavernforge

import androidx.lifecycle.ViewModelStore
import com.valhalla.tavernforge.core.model.ProjectSummary
import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.core.model.StoredProject
import com.valhalla.tavernforge.data.ProjectRepository
import com.valhalla.tavernforge.data.ProjectStorageError
import com.valhalla.tavernforge.data.ProjectStorageException
import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.JsonPatch
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectLibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val engine = CharacterCardEngine()
    private val repository = FakeRepository()
    private val preferences = FakePreferences()

    @Before fun mainDispatcher() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { stores.forEach(ViewModelStore::clear); Dispatchers.resetMain() }
    private fun model() = InspectionViewModel(engine, null, repository, preferences, dispatcher).also { model ->
        stores += ViewModelStore().also { it.put("library", model) }
    }
    private fun card(name: String) = engine.createV2(name).source.bytes()

    @Test fun savingRequiresExplicitActionAndKeepsOriginalSourceBytes() = runTest(dispatcher) {
        val vm = model()
        val bytes = card("Keeper")
        val original = bytes.copyOf()
        vm.inspectSource { ImportedBytes("original.json", bytes) }
        advanceUntilIdle()
        assertTrue(vm.state.value.hasUnsavedDraft)
        assertEquals(0, repository.saves)
        bytes.fill(0)
        vm.saveDraft()
        advanceUntilIdle()
        assertEquals(1, repository.saves)
        assertArrayEquals(original, repository.documents.values.single().source.bytes())
        assertFalse(vm.state.value.hasUnsavedDraft)
        assertEquals(LibraryNotice.SAVED, vm.state.value.notice)
    }

    @Test fun invalidAndUnknownParsedDocumentsCanSaveWithoutDefaults() = runTest(dispatcher) {
        val vm = model()
        listOf("""{"spec":"chara_card_v3","spec_version":"3.0","data":{}}""", """{"future":null}""").forEach { json ->
            vm.inspectSource { ImportedBytes("draft.json", json.encodeToByteArray()) }
            advanceUntilIdle()
            assertTrue(vm.state.value.hasUnsavedDraft)
            assertNotEquals(InspectionStatus.VALID, vm.state.value.summary?.status)
            vm.saveDraft()
            advanceUntilIdle()
            assertArrayEquals(json.encodeToByteArray(), repository.documents.getValue(vm.state.value.selectedProject!!.id).source.bytes())
        }
    }

    @Test fun malformedJsonCannotBeSaved() = runTest(dispatcher) {
        val vm = model()
        vm.inspectSource { ImportedBytes("bad.json", "{broken".encodeToByteArray()) }
        advanceUntilIdle()
        assertFalse(vm.state.value.hasUnsavedDraft)
        assertFalse(vm.state.value.summary!!.canSaveDraft)
        vm.saveDraft()
        advanceUntilIdle()
        assertEquals(0, repository.saves)
    }

    @Test fun repeatedSaveAndDuplicateTapsAreIgnoredWhilePending() = runTest(dispatcher) {
        val vm = model()
        vm.inspectSource { ImportedBytes("card.json", card("Keeper")) }
        advanceUntilIdle()
        repository.gate = CompletableDeferred()
        vm.saveDraft(); vm.saveDraft()
        runCurrent()
        assertEquals(1, repository.saves)
        assertEquals(LibraryOperation.SAVING, vm.state.value.operation)
        repository.gate!!.complete(Unit)
        advanceUntilIdle()
        repository.gate = CompletableDeferred()
        vm.duplicateProject(); vm.duplicateProject()
        runCurrent()
        assertEquals(1, repository.duplicates)
        repository.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, repository.documents.size)
    }

    @Test fun freshViewModelReopensSavedSourceThroughSameInspector() = runTest(dispatcher) {
        val first = model()
        val bytes = card("Original card name")
        first.inspectSource { ImportedBytes("card.json", bytes) }
        advanceUntilIdle(); first.saveDraft(); advanceUntilIdle()
        val project = first.state.value.selectedProject!!
        val second = model()
        advanceUntilIdle()
        assertEquals(project.id, second.state.value.projects.single().id)
        second.openProject(project.id)
        advanceUntilIdle()
        assertEquals(inspectJson(engine, "card.json", bytes), second.state.value.summary)
        assertEquals(project.id, second.state.value.selectedProject?.id)
        assertFalse(second.state.value.hasUnsavedDraft)
    }

    @Test fun renameOnlyUpdatesLibraryMetadataAndDuplicateGetsNewIdentity() = runTest(dispatcher) {
        val vm = model()
        val bytes = card("Character name")
        vm.inspectSource { ImportedBytes("card.json", bytes) }
        advanceUntilIdle(); vm.saveDraft(); advanceUntilIdle()
        val originalId = vm.state.value.selectedProject!!.id
        vm.renameProject("Library title")
        advanceUntilIdle()
        assertEquals("Library title", vm.state.value.selectedProject?.title)
        assertEquals("Character name", vm.state.value.summary?.documentName)
        assertArrayEquals(bytes, repository.documents.getValue(originalId).source.bytes())
        vm.duplicateProject()
        advanceUntilIdle()
        assertNotEquals(originalId, vm.state.value.selectedProject?.id)
        assertArrayEquals(bytes, repository.documents.getValue(vm.state.value.selectedProject!!.id).source.bytes())
        vm.deleteProject()
        advanceUntilIdle()
        assertNull(vm.state.value.summary)
        assertNull(vm.state.value.selectedProject)
        assertFalse(vm.state.value.hasUnsavedDraft)
        assertEquals(listOf(originalId), repository.documents.keys.toList())
        assertEquals(LibraryNotice.DELETED, vm.state.value.notice)
    }

    @Test fun staleNonCooperativeImportCannotReplaceNewerSelection() = runTest(dispatcher) {
        val vm = model()
        val old = CompletableDeferred<ImportedBytes>()
        vm.inspectSource { withContext(NonCancellable) { old.await() } }
        runCurrent()
        vm.inspectSource { ImportedBytes("new.json", card("New")) }
        advanceUntilIdle()
        old.complete(ImportedBytes("old.json", card("Old")))
        advanceUntilIdle()
        assertEquals("New", vm.state.value.summary?.documentName)
        assertFalse(vm.state.value.loading)
        vm.saveDraft(); advanceUntilIdle()
        assertEquals("new.json", repository.documents.values.single().summary.sourceFileName)
    }

    @Test fun clearCancelsPendingImportAndCannotSavePreviousSource() = runTest(dispatcher) {
        val vm = model()
        val pending = CompletableDeferred<ImportedBytes>()
        vm.inspectSource { withContext(NonCancellable) { pending.await() } }
        runCurrent(); vm.clear()
        pending.complete(ImportedBytes("late.json", card("Late")))
        advanceUntilIdle(); vm.saveDraft(); advanceUntilIdle()
        assertNull(vm.state.value.summary)
        assertFalse(vm.state.value.loading)
        assertEquals(0, repository.saves)
    }

    @Test fun failedSaveRetainsUnsavedSourceForRetry() = runTest(dispatcher) {
        val vm = model()
        vm.inspectSource { ImportedBytes("card.json", card("Retry")) }
        advanceUntilIdle()
        repository.failure = ProjectStorageError.IO_FAILURE
        vm.saveDraft(); advanceUntilIdle()
        assertTrue(vm.state.value.hasUnsavedDraft)
        assertEquals(ProjectStorageError.IO_FAILURE, vm.state.value.storageFailure)
        assertFalse(vm.state.value.busy)
        repository.failure = null
        vm.saveDraft(); advanceUntilIdle()
        assertFalse(vm.state.value.hasUnsavedDraft)
        assertNull(vm.state.value.storageFailure)
    }

    @Test fun failedReplacementKeepsPreviousInspectionAvailable() = runTest(dispatcher) {
        val vm = model()
        vm.inspectSource { ImportedBytes("kept.json", card("Kept")) }
        advanceUntilIdle()
        vm.inspectSource { throw IOException("Provider failed") }
        advanceUntilIdle()
        assertEquals("Kept", vm.state.value.summary?.documentName)
        assertEquals(ImportFailure.READ, vm.state.value.failure)
        assertTrue(vm.state.value.hasUnsavedDraft)
        vm.openProject("missing")
        advanceUntilIdle()
        assertEquals("Kept", vm.state.value.summary?.documentName)
        assertEquals(ProjectStorageError.NOT_FOUND, vm.state.value.storageFailure)
    }

    @Test fun sortPreferenceIsRestoredAndSearchUsesLibraryMetadata() = runTest(dispatcher) {
        repository.saveImported("zebra.json", SourceEnvelope(card("Zebra")))
        repository.saveImported("alpha.json", SourceEnvelope(card("Alpha")))
        preferences.sort.value = ProjectSort.TITLE
        val vm = model()
        advanceUntilIdle()
        assertEquals(listOf("Alpha", "Zebra"), vm.state.value.visibleProjects.map { it.title })
        vm.setQuery("ZEBRA.JSON")
        assertEquals(listOf("Zebra"), vm.state.value.visibleProjects.map { it.title })
        vm.setSort(ProjectSort.RECENT)
        advanceUntilIdle()
        assertEquals(ProjectSort.RECENT, preferences.sort.value)
        val second = model(); advanceUntilIdle()
        assertEquals(ProjectSort.RECENT, second.state.value.sort)
    }

    private class FakePreferences : LibraryPreferences {
        override val sort = MutableStateFlow(ProjectSort.RECENT)
        override suspend fun setSort(sort: ProjectSort) { this.sort.value = sort }
    }

    private class FakeRepository : ProjectRepository {
        val documents = linkedMapOf<String, StoredProject>()
        private val projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
        var saves = 0
        var duplicates = 0
        var gate: CompletableDeferred<Unit>? = null
        var failure: ProjectStorageError? = null
        override fun observeProjects() = projects
        private fun publish() { projects.value = documents.values.map { it.summary } }
        private suspend fun checkFailure() {
            gate?.await()
            failure?.let { throw ProjectStorageException(it, "Storage unavailable") }
        }
        override suspend fun saveImported(fileName: String, source: SourceEnvelope): ProjectSummary {
            saves++
            checkFailure()
            val inspection = inspectJson(CharacterCardEngine(), fileName, source.bytes())
            val id = "project-$saves"
            val summary = ProjectSummary(id, inspection.documentName ?: fileName, fileName, inspection.sourceFormat,
                inspection.specVersion, true, saves.toLong(), saves.toLong(), source.sha256, source.sizeBytes)
            documents[id] = StoredProject(summary, SourceEnvelope(source.bytes()))
            publish()
            return summary
        }
        override suspend fun load(id: String) = documents[id]
            ?: throw ProjectStorageException(ProjectStorageError.NOT_FOUND, "Not found")
        override suspend fun rename(id: String, title: String) {
            checkFailure()
            val original = load(id)
            documents[id] = original.copy(summary = original.summary.copy(title = title.trim()))
            publish()
        }
        override suspend fun duplicate(id: String): ProjectSummary {
            duplicates++
            checkFailure()
            val original = load(id)
            val summary = original.summary.copy(id = "copy-$duplicates", title = original.summary.title + " copy")
            documents[summary.id] = StoredProject(summary, SourceEnvelope(original.source.bytes()))
            publish()
            return summary
        }
        override suspend fun applyEdits(id: String, expectedRevision: Long, operations: List<JsonPatch>): StoredProject =
            throw UnsupportedOperationException("Editing is covered by the editor repository fake")
        override suspend fun delete(id: String) { checkFailure(); documents.remove(id); publish() }
    }
}

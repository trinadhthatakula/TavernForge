package com.valhalla.tavernforge

import androidx.lifecycle.ViewModelStore
import com.valhalla.tavernforge.core.model.*
import com.valhalla.tavernforge.core.model.generation.*
import com.valhalla.tavernforge.data.ProjectRepository
import com.valhalla.tavernforge.data.ProjectStorageError
import com.valhalla.tavernforge.data.ProjectStorageException
import com.valhalla.tavernforge.data.network.gemini.*
import com.valhalla.tavernforge.data.security.*
import com.valhalla.tavernforge.format.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GenerationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val engine = CharacterCardEngine()
    private val credentials = SyntheticCredentials()
    private val transport = SyntheticTransport()
    private val repository = GeneratedRepository(engine)
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { stores.forEach(ViewModelStore::clear); Dispatchers.resetMain() }
    private fun model() = GenerationViewModel(engine, repository, credentials, GeminiService(credentials, transport,
        imageVerifier = GeneratedImageVerifier { _, _, _ -> 1 to 1 }), dispatcher).also { vm ->
        stores += ViewModelStore().also { it.put("generation", vm) }
    }
    private suspend fun TestScope.loadModel(vm: GenerationViewModel) {
        vm.prepareModels(); advanceUntilIdle()
        assertNotNull(vm.state.value.preview)
        vm.approveRequest(); settle(vm)
        vm.setModel("models/gemini-3.8-flash")
    }

    private suspend fun TestScope.settle(vm: GenerationViewModel) {
        advanceUntilIdle()
        vm.state.first { !it.working && !it.keyWorking }
        advanceUntilIdle()
    }

    @Test fun storageRefreshAndPreparingModelListDoNotSendUntilExplicitApproval() = runTest(dispatcher) {
        val vm = model(); advanceUntilIdle()
        assertEquals(1, credentials.refreshes); assertEquals(0, transport.attempts)
        assertEquals(GenerationGrants(), vm.state.value.grants)
        vm.prepareModels(); advanceUntilIdle(); assertEquals(0, transport.attempts)
        vm.approveRequest(); vm.approveRequest(); settle(vm)
        assertEquals(1, transport.attempts); assertEquals(1, vm.state.value.models.size)
        assertTrue(repository.docs.isEmpty())
    }

    @Test fun textPermissionIsSeparateAndOutputRemainsUnacceptedUntilSave() = runTest(dispatcher) {
        val vm = model(); loadModel(vm)
        vm.setConcept("An archivist"); vm.prepareGeneration(); advanceUntilIdle()
        assertNull(vm.state.value.preview); assertEquals(1, transport.attempts)
        vm.setGrants(GenerationGrants(text = true)); vm.prepareGeneration(); advanceUntilIdle()
        assertNotNull(vm.state.value.preview); assertFalse(vm.state.value.grants.image)
        transport.generatedText = (engine.createTemplate(SourceFormat.V3) as ParseResult.Success).document.source.bytes().decodeToString()
        vm.approveRequest(); settle(vm)
        assertTrue(vm.state.value.hasDraft); assertTrue(vm.state.value.draftAcceptable); assertTrue(repository.docs.isEmpty())
        vm.acceptDraft(); vm.acceptDraft(); advanceUntilIdle()
        assertEquals(1, repository.docs.size); assertFalse(vm.state.value.hasDraft)
    }

    @Test fun malformedOrDifferentVersionOutputIsNeverSilentlyConvertedOrSaved() = runTest(dispatcher) {
        val vm = model(); loadModel(vm); vm.setGrants(GenerationGrants(text = true)); vm.setConcept("Synthetic card")
        transport.generatedText = "{broken"
        vm.prepareGeneration(); advanceUntilIdle(); vm.approveRequest(); settle(vm)
        assertFalse(vm.state.value.draftAcceptable); vm.acceptDraft(); advanceUntilIdle(); assertTrue(repository.docs.isEmpty())
        vm.discardDraft()
        transport.generatedText = engine.createV2("Different version").source.bytes().decodeToString()
        vm.prepareGeneration(); advanceUntilIdle(); vm.approveRequest(); settle(vm)
        assertFalse(vm.state.value.draftAcceptable); assertTrue(vm.state.value.draftIssues.any { it.code == "GENERATED_FORMAT" })
        assertTrue(repository.docs.isEmpty())
    }

    @Test fun contextIsOptInAndFieldAcceptanceUsesPersistedRevision() = runTest(dispatcher) {
        val saved = repository.saveImported("source.json", SourceEnvelope((engine.createV2("Context sentinel").source.bytes().decodeToString().dropLast(1) +
            ",\"opaque\":\"unknown context sentinel\"}").encodeToByteArray()))
        val vm = model(); vm.open(saved.id); advanceUntilIdle(); loadModel(vm); vm.setGrants(GenerationGrants(text = true)); vm.setConcept("Improve style")
        vm.prepareGeneration(); advanceUntilIdle(); assertFalse(vm.state.value.preview!!.bodyPreview.contains("unknown context sentinel"))
        vm.cancelPreview(); vm.setContext(true); vm.prepareGeneration(); advanceUntilIdle()
        assertTrue(vm.state.value.preview!!.bodyPreview.contains("unknown context sentinel"))
        transport.generatedText = "New description"
        vm.approveRequest(); settle(vm); assertTrue(vm.state.value.draftAcceptable); assertEquals(0, repository.edits)
        repository.advance(saved.id)
        vm.acceptDraft(); advanceUntilIdle()
        assertTrue(vm.state.value.hasDraft); assertTrue(vm.state.value.error!!.contains("changed")); assertEquals(0, repository.edits)
    }

    @Test fun changedTargetBeforeApprovalNeverReachesTransport() = runTest(dispatcher) {
        val saved = repository.saveImported("source.json", engine.createV2("Original").source)
        val vm = model(); vm.open(saved.id); advanceUntilIdle(); loadModel(vm); vm.setGrants(GenerationGrants(text = true)); vm.setConcept("Rewrite")
        vm.prepareGeneration(); advanceUntilIdle(); repository.advance(saved.id)
        vm.approveRequest(); settle(vm)
        assertEquals(1, transport.attempts); assertFalse(vm.state.value.hasDraft); assertNotNull(vm.state.value.error)
    }

    @Test fun keyInputBufferIsClearedAndNeverPlacedInRequestBody() = runTest(dispatcher) {
        val vm = model(); advanceUntilIdle()
        val input = CharArray(8) { 'x' }
        vm.updateKey(input); advanceUntilIdle()
        assertTrue(input.all { it == '\u0000' }); assertEquals(0, transport.attempts)
        loadModel(vm); vm.setGrants(GenerationGrants(text = true)); vm.setConcept("Public synthetic concept")
        vm.prepareGeneration(); advanceUntilIdle()
        assertFalse(vm.state.value.preview!!.bodyPreview.contains("xxxxxxxx"))
        vm.deleteKey(); advanceUntilIdle(); assertNull(vm.state.value.preview)
    }

    @Test fun refreshingModelsReplacesOldCapabilityRecordsAndCanPrepareAgain() = runTest(dispatcher) {
        val vm = model(); loadModel(vm)
        val old = vm.state.value.models.single()
        loadModel(vm)
        assertNotSame(old, vm.state.value.models.single())
        vm.setGrants(GenerationGrants(text = true)); vm.setConcept("New concept"); vm.prepareGeneration(); advanceUntilIdle()
        assertNotNull(vm.state.value.preview)
        assertTrue(vm.state.value.preview!!.bodyPreview.contains("APPLICATION_JSON"))
    }

    @Test fun generatedGreetingsUseTypedArraySchemaAndShowCompleteBeforeAfterValues() = runTest(dispatcher) {
        val source = (engine.createTemplate(SourceFormat.V3) as ParseResult.Success).document.source
        val saved = repository.saveImported("v3.json", source)
        val vm = model(); vm.open(saved.id); advanceUntilIdle(); loadModel(vm)
        vm.setGrants(GenerationGrants(text = true)); vm.setField("group_only_greetings"); vm.setConcept("Two greetings")
        vm.prepareGeneration(); advanceUntilIdle()
        val body = Json.parseToJsonElement(vm.state.value.preview!!.bodyPreview).jsonObject
        val schema = body.getValue("generationConfig").jsonObject.getValue("responseFormat").jsonObject.getValue("text").jsonObject.getValue("schema").jsonObject
        assertEquals(JsonPrimitive("array"), schema["type"])
        transport.generatedText = "[\"Hello, everyone\\nWelcome\",\"\"]"
        vm.approveRequest(); settle(vm)
        assertTrue(vm.state.value.draftAcceptable)
        assertEquals("[]", vm.state.value.fieldDiff!!.before)
        assertEquals(transport.generatedText, vm.state.value.fieldDiff!!.after)
        vm.acceptDraft(); advanceUntilIdle()
        val current = repository.load(saved.id).currentSource.bytes().decodeToString()
        assertTrue(current.contains("Hello, everyone\\nWelcome"))
    }

    private class SyntheticCredentials : GeminiCredentials {
        override val state = MutableStateFlow(CredentialSnapshot(CredentialAvailability.AVAILABLE, "synthetic-generation"))
        var refreshes = 0
        override suspend fun refresh() { refreshes++ }
        override suspend fun update(value: CharArray) { value.fill('\u0000') }
        override suspend fun delete() { state.value = CredentialSnapshot() }
        override suspend fun <T> withCredential(expectedGeneration: String, action: suspend (CharArray) -> T): T {
            val buffer = CharArray(8) { 'x' }
            return try { action(buffer) } finally { buffer.fill('\u0000') }
        }
    }
    private class SyntheticTransport : GeminiTransport {
        var attempts = 0; var generatedText = ""
        override suspend fun execute(request: PreparedRequest, credential: CharArray): GeminiHttpResponse {
            attempts++
            val body = if (request.purpose == GenerationPurpose.MODEL_LIST)
                """{"models":[{"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent","countTokens"],"outputTokenLimit":8192}]}"""
            else buildJsonObject { putJsonArray("candidates") { add(buildJsonObject {
                put("finishReason", "STOP"); putJsonObject("content") { putJsonArray("parts") { add(buildJsonObject { put("text", generatedText) }) } }
            }) } }.toString()
            return GeminiHttpResponse(200, body.encodeToByteArray())
        }
    }
    private class GeneratedRepository(private val engine: CharacterCardEngine) : ProjectRepository {
        val docs = linkedMapOf<String, StoredProject>(); var edits = 0
        private val projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
        override fun observeProjects() = projects
        override suspend fun saveImported(fileName: String, source: SourceEnvelope): ProjectSummary {
            val doc = (engine.parse(source.bytes()) as ParseResult.Success).document
            val summary = ProjectSummary("generated-${docs.size}", doc.displayName.orEmpty(), fileName, doc.sourceFormat, doc.specVersion,
                !engine.validate(doc).isValid, 1, 1, source.sha256, source.sizeBytes)
            docs[summary.id] = StoredProject(summary, source); return summary
        }
        fun advance(id: String) { docs[id] = docs.getValue(id).let { it.copy(documentRevision = it.documentRevision + 1) } }
        override suspend fun load(id: String) = docs.getValue(id)
        override suspend fun applyEdits(id: String, expectedRevision: Long, operations: List<JsonPatch>): StoredProject {
            val old = load(id)
            if (old.documentRevision != expectedRevision) throw ProjectStorageException(ProjectStorageError.REVISION_CONFLICT, "Conflict")
            val base = (engine.restore(old.source, old.currentSource, old.documentRevision) as ParseResult.Success).document
            val doc = (engine.patch(base, expectedRevision, operations) as PatchResult.Success).document
            val saved = old.copy(currentSource = SourceEnvelope((engine.snapshot(doc) as ExportResult.Success).bytes), documentRevision = doc.revision)
            docs[id] = saved; edits++; return saved
        }
        override suspend fun rename(id: String, title: String) = Unit
        override suspend fun duplicate(id: String): ProjectSummary = error("Not used")
        override suspend fun delete(id: String) = Unit
    }
}

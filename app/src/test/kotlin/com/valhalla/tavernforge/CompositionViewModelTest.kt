package com.valhalla.tavernforge

import androidx.lifecycle.ViewModelStore
import com.valhalla.tavernforge.core.model.*
import com.valhalla.tavernforge.data.ProjectRepository
import com.valhalla.tavernforge.format.*
import com.valhalla.tavernforge.format.png.*
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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class CompositionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val cards = CharacterCardEngine()
    private val repository = MediaRepository(cards)
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { stores.forEach(ViewModelStore::clear); Dispatchers.resetMain() }
    private fun model() = CompositionViewModel(cards, repository, null, null, dispatcher).also { vm ->
        stores += ViewModelStore().also { it.put("media", vm) }
    }
    private fun cardPng() = syntheticPng("chara" to Base64.getEncoder().encode(cards.createV2("Original").source.bytes()))

    @Test fun artworkOnlyPreviewDoesNotPersistUntilExplicitSave() = runTest(dispatcher) {
        val bytes = syntheticPng(); val vm = model()
        vm.inspectSource { ImportedBytes("art.png", bytes) }; advanceUntilIdle()
        assertNotNull(vm.state.value.importPreview); assertEquals(0, repository.art.size)
        vm.saveArtwork(); advanceUntilIdle()
        assertNull(vm.state.value.importPreview); assertEquals(1, repository.art.size)
        assertArrayEquals(bytes, repository.art.values.single().bytes())
        assertNull(vm.state.value.character)
    }

    @Test fun malformedPreferredMetadataRequiresReadableCandidateSelection() = runTest(dispatcher) {
        val bytes = syntheticPng("ccv3" to "invalid base64".encodeToByteArray(),
            "chara" to Base64.getEncoder().encode(cards.createV2("Recoverable").source.bytes()))
        val vm = model(); vm.inspectSource { ImportedBytes("conflict.png", bytes) }; advanceUntilIdle()
        assertTrue(vm.state.value.importPreview!!.needsChoice)
        assertNull(vm.state.value.importPreview!!.selectedCandidateId)
        vm.savePngCard(); advanceUntilIdle(); assertTrue(repository.docs.isEmpty())
        val candidate = vm.state.value.importPreview!!.container.candidates.single { it.keyword == "chara" }
        vm.chooseCandidate(candidate.id); vm.savePngCard(); advanceUntilIdle()
        assertEquals(1, repository.docs.size); assertNotNull(vm.state.value.composition?.sourcePng)
        assertArrayEquals(bytes, repository.art.values.single().bytes())
    }

    @Test fun exportPickerCancellationWritesNothingAndBlocksSelectionDuringPreview() = runTest(dispatcher) {
        val saved = repository.savePngCard("card.png", cardPng())
        val vm = model(); vm.open(saved.summary.id); advanceUntilIdle(); vm.preparePngExport(); advanceUntilIdle()
        assertNotNull(vm.state.value.exportPreview)
        vm.open(null); assertNotNull(vm.state.value.character)
        assertNull(vm.takePngDestination())
        vm.approvePngExport(); advanceUntilIdle(); assertNotNull(vm.takePngDestination()); assertNull(vm.takePngDestination())
        var writes = 0; vm.completePngTo(false) { writes++ }; advanceUntilIdle()
        assertEquals(0, writes); assertNull(vm.state.value.exportPreview); assertFalse(vm.state.value.busy)
    }

    @Test fun reviewedPngExportUsesFrozenBytesAndRejectsRepeatedCompletion() = runTest(dispatcher) {
        val bytes = cardPng(); val saved = repository.savePngCard("card.png", bytes)
        val vm = model(); vm.open(saved.summary.id); advanceUntilIdle(); vm.preparePngExport(); advanceUntilIdle()
        vm.approvePngExport(); advanceUntilIdle(); vm.takePngDestination()
        var writes = 0; var output: ByteArray? = null
        vm.completePngTo(true) { writes++; output = it }; vm.completePngTo(true) { writes++ }; advanceUntilIdle()
        assertEquals(1, writes); assertArrayEquals(bytes, output); assertEquals("PNG exported.", vm.state.value.notice)
    }

    @Test fun staleCompositionCannotPrepareAnExportAndFailedWritesReportPartialPossibility() = runTest(dispatcher) {
        val saved = repository.savePngCard("card.png", cardPng())
        val vm = model(); vm.open(saved.summary.id); advanceUntilIdle()
        repository.composition = repository.composition!!.copy(compositionRevision = 1)
        vm.preparePngExport(); advanceUntilIdle(); assertNull(vm.state.value.exportPreview); assertNotNull(vm.state.value.error)
        vm.open(saved.summary.id); advanceUntilIdle(); vm.preparePngExport(); advanceUntilIdle(); vm.approvePngExport(); advanceUntilIdle(); vm.takePngDestination()
        vm.completePngTo(true) { throw IOException("private provider detail") }; advanceUntilIdle()
        assertTrue(vm.state.value.error!!.contains("partial file")); assertFalse(vm.state.value.error!!.contains("private"))
        assertNull(vm.state.value.notice)
    }

    private class MediaRepository(private val cards: CharacterCardEngine) : ProjectRepository {
        val docs = linkedMapOf<String, StoredProject>()
        val art = linkedMapOf<String, StoredArtwork>()
        var composition: ProjectComposition? = null
        private val projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
        private val images = MutableStateFlow<List<ArtworkSummary>>(emptyList())
        private val png = PngCardEngine(cards = cards)
        override fun observeProjects() = projects
        override fun observeArtwork() = images
        override suspend fun saveImported(fileName: String, source: SourceEnvelope): ProjectSummary {
            val doc = (cards.parse(source.bytes()) as ParseResult.Success).document
            val summary = ProjectSummary("p-${docs.size}", doc.displayName.orEmpty(), fileName, doc.sourceFormat, doc.specVersion,
                !cards.validate(doc).isValid, 1, 1, source.sha256, source.sizeBytes)
            docs[summary.id] = StoredProject(summary, source); projects.value = docs.values.map { it.summary }; return summary
        }
        override suspend fun load(id: String) = docs.getValue(id)
        override suspend fun saveArtwork(fileName: String, bytes: ByteArray): ArtworkSummary {
            val container = (png.inspect(bytes) as PngInspectionResult.Success).container
            val summary = ArtworkSummary("a-${art.size}", fileName, container.sha256, bytes.size, container.width, container.height, container.isAnimated, 1)
            art[summary.id] = StoredArtwork(summary, bytes); images.value = art.values.map { it.summary }; return summary
        }
        override suspend fun loadArtwork(id: String) = art.getValue(id)
        override suspend fun savePngCard(fileName: String, bytes: ByteArray, candidateId: String?): StoredProject {
            val container = (png.inspect(bytes) as PngInspectionResult.Success).container
            val selected = png.select(container, candidateId) as PngSelectionResult.Selected
            val summary = saveImported(fileName.removeSuffix(".png") + ".json", selected.document.source)
            val artwork = saveArtwork(fileName, bytes)
            composition = ProjectComposition(summary.id, 0, 0, artwork, artwork, selected.candidate.id, emptyList())
            return load(summary.id)
        }
        override suspend fun loadComposition(characterId: String) = requireNotNull(composition)
        override suspend fun applyEdits(id: String, expectedRevision: Long, operations: List<JsonPatch>): StoredProject = error("Not used")
        override suspend fun rename(id: String, title: String) = Unit
        override suspend fun duplicate(id: String): ProjectSummary = error("Not used")
        override suspend fun delete(id: String) = Unit
    }
}

/** Synthetic one-pixel RGBA PNG; no external artwork or metadata is used. */
private fun syntheticPng(vararg metadata: Pair<String, ByteArray>): ByteArray {
    fun chunk(type: String, payload: ByteArray): ByteArray {
        val name = type.encodeToByteArray()
        return ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(payload.size); data.write(name); data.write(payload)
            data.writeInt(CRC32().apply { update(name); update(payload) }.value.toInt())
        } }.toByteArray()
    }
    val header = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { it.writeInt(1); it.writeInt(1); it.write(byteArrayOf(8, 6, 0, 0, 0)) } }.toByteArray()
    val compressed = ByteArrayOutputStream().also { output -> DeflaterOutputStream(output).use { it.write(byteArrayOf(0, 100, 90, 80, 127)) } }.toByteArray()
    return ByteArrayOutputStream().also { output ->
        output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)); output.write(chunk("IHDR", header))
        metadata.forEach { (key, bytes) -> output.write(chunk("tEXt", key.encodeToByteArray() + byteArrayOf(0) + bytes)) }
        output.write(chunk("IDAT", compressed)); output.write(chunk("IEND", byteArrayOf()))
    }.toByteArray()
}

package com.valhalla.tavernforge

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.tavernforge.core.model.*
import com.valhalla.tavernforge.data.ProjectRepository
import com.valhalla.tavernforge.data.ProjectStorageException
import com.valhalla.tavernforge.format.*
import com.valhalla.tavernforge.format.combine.EmbeddingPlan
import com.valhalla.tavernforge.format.combine.EmbeddingPlanResult
import com.valhalla.tavernforge.format.png.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PngImportPreview(val fileName: String, val container: PngContainer, val selectedCandidateId: String?, val needsChoice: Boolean)
data class CompositionExportPreview(val id: Long, val fileName: String, val revision: Long, val reviewItems: List<String>,
    val pickerReady: Boolean = false, val pickerLaunched: Boolean = false)
data class EmbeddedBookPreview(val lorebookId: String, val name: String, val compositionRevision: Long, val plan: EmbeddingPlan)
data class CompositionState(
    val working: Boolean = false,
    val character: ProjectSummary? = null,
    val composition: ProjectComposition? = null,
    val projects: List<ProjectSummary> = emptyList(),
    val artwork: List<ArtworkSummary> = emptyList(),
    val importPreview: PngImportPreview? = null,
    val exportPreview: CompositionExportPreview? = null,
    val embeddingPreview: EmbeddedBookPreview? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val busy get() = working || exportPreview != null || embeddingPreview != null
    val lorebooks get() = projects.filter { it.sourceFormat in setOf(SourceFormat.LOREBOOK_V2, SourceFormat.LOREBOOK_V3, SourceFormat.WORLD_INFO) }
}

class CompositionViewModel(
    private val cards: CharacterCardEngine,
    private val repository: ProjectRepository,
    private val reader: DocumentReader?,
    private val writer: DocumentWriter?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val png = PngCardEngine(cards = cards)
    private val mutableState = MutableStateFlow(CompositionState())
    val state = mutableState.asStateFlow()
    private var exportPlan: PngExportPlan? = null
    private var exportBytes: ByteArray? = null
    private var exportId = 0L

    init {
        viewModelScope.launch {
            try { repository.observeProjects().collect { mutableState.value = mutableState.value.copy(projects = it) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { mutableState.value = mutableState.value.copy(error = "Could not read the project library.") }
        }
        viewModelScope.launch {
            try { repository.observeArtwork().collect { mutableState.value = mutableState.value.copy(artwork = it) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { mutableState.value = mutableState.value.copy(error = "Could not read the artwork library.") }
        }
    }

    fun open(characterId: String?) {
        if (mutableState.value.busy) return
        if (characterId == null) { mutableState.value = mutableState.value.copy(character = null, composition = null, error = null); return }
        action { refresh(characterId) }
    }
    private suspend fun refresh(id: String) {
        val saved = repository.load(id)
        val composition = repository.loadComposition(id)
        mutableState.value = mutableState.value.copy(character = saved.summary, composition = composition)
    }
    fun inspectPng(uri: Uri) = inspectSource { requireNotNull(reader).read(uri, png.limits.maxBytes) }
    internal fun inspectSource(read: suspend () -> ImportedBytes) = action {
        val imported = read()
        when (val inspected = withContext(dispatcher) { png.inspect(imported.bytes) }) {
            is PngInspectionResult.Failure -> mutableState.value = mutableState.value.copy(error = inspected.issues.joinToString("\n") { it.message })
            is PngInspectionResult.Success -> {
                val selection = png.select(inspected.container)
                mutableState.value = mutableState.value.copy(importPreview = PngImportPreview(imported.displayName, inspected.container,
                    (selection as? PngSelectionResult.Selected)?.candidate?.id, selection is PngSelectionResult.NeedsChoice))
            }
        }
    }
    fun chooseCandidate(id: String) {
        if (mutableState.value.busy) return
        val preview = mutableState.value.importPreview ?: return
        if (png.select(preview.container, id) !is PngSelectionResult.Selected) return
        mutableState.value = mutableState.value.copy(importPreview = preview.copy(selectedCandidateId = id, needsChoice = false))
    }
    fun discardImport() { if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(importPreview = null) }
    fun savePngCard() {
        val preview = mutableState.value.importPreview ?: return
        val candidate = preview.selectedCandidateId ?: return
        action {
            val saved = repository.savePngCard(preview.fileName, preview.container.bytes(), candidate)
            refresh(saved.summary.id)
            mutableState.value = mutableState.value.copy(importPreview = null, notice = "PNG card saved with its original image and metadata.")
        }
    }
    fun saveArtwork() {
        val preview = mutableState.value.importPreview ?: return
        action {
            repository.saveArtwork(preview.fileName, preview.container.bytes())
            mutableState.value = mutableState.value.copy(importPreview = null, notice = "Original PNG saved to the artwork library.")
        }
    }
    fun setArtwork(id: String?) {
        val composition = mutableState.value.composition ?: return
        action {
            val updated = repository.setArtwork(composition.characterId, composition.compositionRevision, id)
            mutableState.value = mutableState.value.copy(composition = updated, notice = "Artwork association updated.")
        }
    }
    fun deleteArtwork(id: String) = action { repository.deleteArtwork(id); mutableState.value = mutableState.value.copy(notice = "Artwork removed from this library.") }
    fun linkLorebook(id: String, remove: Boolean = false) {
        val composition = mutableState.value.composition ?: return
        action {
            val updated = if (remove) repository.unlinkLorebook(composition.characterId, composition.compositionRevision, id)
                else repository.linkLorebook(composition.characterId, composition.compositionRevision, id)
            mutableState.value = mutableState.value.copy(composition = updated, notice = if (remove) "Lorebook unlinked." else "Lorebook linked. Embedding remains an explicit snapshot action.")
        }
    }
    fun prepareEmbedding(id: String) {
        val composition = mutableState.value.composition ?: return
        val name = mutableState.value.projects.firstOrNull { it.id == id }?.title ?: "Lorebook"
        action {
            when (val result = repository.prepareEmbedding(composition.characterId, id)) {
                is EmbeddingPlanResult.Failure -> mutableState.value = mutableState.value.copy(error = result.message)
                is EmbeddingPlanResult.Ready -> mutableState.value = mutableState.value.copy(embeddingPreview = EmbeddedBookPreview(id, name, composition.compositionRevision, result.plan))
            }
        }
    }
    fun cancelEmbedding() { if (!mutableState.value.working) mutableState.value = mutableState.value.copy(embeddingPreview = null) }
    fun acceptEmbedding() {
        val preview = mutableState.value.embeddingPreview ?: return
        val composition = mutableState.value.composition ?: return
        if (mutableState.value.working) return
        action(allowPreview = true) {
            repository.applyEmbedding(composition.characterId, preview.compositionRevision, preview.lorebookId, preview.plan, preview.plan.reviewItems.toSet())
            refresh(composition.characterId)
            mutableState.value = mutableState.value.copy(embeddingPreview = null, notice = "Reviewed lorebook snapshot saved into the character. The linked original remains separate.")
        }
    }
    fun preparePngExport() {
        val composition = mutableState.value.composition ?: return
        val artwork = composition.artwork ?: composition.sourcePng ?: return
        action {
            val freshComposition = repository.loadComposition(composition.characterId)
            if (freshComposition.compositionRevision != composition.compositionRevision || freshComposition.documentRevision != composition.documentRevision)
                throw IllegalStateException("stale")
            val saved = repository.load(composition.characterId)
            val image = repository.loadArtwork(artwork.id)
            val plan = withContext(dispatcher) {
                val doc = (cards.restore(saved.source, saved.currentSource, saved.documentRevision) as? ParseResult.Success)?.document
                    ?: return@withContext null
                val container = (png.inspect(image.bytes()) as? PngInspectionResult.Success)?.container ?: return@withContext null
                png.planExport(container, doc)
            }
            when (plan) {
                is PngExportPlanResult.Ready -> {
                    exportPlan = plan.plan
                    mutableState.value = mutableState.value.copy(exportPreview = CompositionExportPreview(++exportId,
                        exportFileName(saved.summary.sourceFileName).removeSuffix(".json") + ".png", saved.documentRevision, plan.plan.reviewItems))
                }
                is PngExportPlanResult.Failure -> mutableState.value = mutableState.value.copy(error = plan.issues.joinToString("\n") { it.message }.ifBlank { "This PNG cannot be exported safely." })
                null -> mutableState.value = mutableState.value.copy(error = "Could not restore the character or artwork for export.")
            }
        }
    }
    fun approvePngExport() {
        val preview = mutableState.value.exportPreview ?: return
        val plan = exportPlan ?: return
        if (preview.pickerReady || preview.pickerLaunched) return
        action(allowPreview = true) {
            when (val result = withContext(dispatcher) { png.export(plan, plan.reviewItems.toSet()) }) {
                is PngExportResult.Success -> {
                    exportBytes = result.bytes
                    mutableState.value = mutableState.value.copy(exportPreview = preview.copy(pickerReady = true))
                }
                is PngExportResult.Failure -> mutableState.value = mutableState.value.copy(error = result.issues.joinToString("\n") { it.message })
            }
        }
    }
    fun takePngDestination(): String? {
        val preview = mutableState.value.exportPreview ?: return null
        if (mutableState.value.working || !preview.pickerReady || preview.pickerLaunched) return null
        mutableState.value = mutableState.value.copy(exportPreview = preview.copy(pickerLaunched = true))
        return preview.fileName
    }
    fun cancelPngExport() {
        if (mutableState.value.working || mutableState.value.exportPreview?.pickerLaunched == true) return
        clearExport()
    }
    fun pngLauncherFailed() { clearExport(); mutableState.value = mutableState.value.copy(error = "The document picker could not open. No export was written.") }
    fun completePngExport(uri: Uri?) = completePngTo(uri != null) { bytes -> requireNotNull(writer).write(requireNotNull(uri), bytes) }
    internal fun completePngTo(accepted: Boolean, write: suspend (ByteArray) -> Unit) {
        val preview = mutableState.value.exportPreview ?: return
        val bytes = exportBytes ?: return
        if (!preview.pickerLaunched || mutableState.value.working) return
        if (!accepted) { clearExport(); return }
        action(allowPreview = true) {
            try { write(bytes); mutableState.value = mutableState.value.copy(notice = "PNG exported.")
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { mutableState.value = mutableState.value.copy(error = "Export failed. The provider may retain a partial file.")
            } finally { clearExport() }
        }
    }
    private fun clearExport() { exportPlan = null; exportBytes = null; mutableState.value = mutableState.value.copy(exportPreview = null) }
    private fun action(allowPreview: Boolean = false, block: suspend () -> Unit) {
        if (mutableState.value.working || !allowPreview && mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(working = true, error = null, notice = null)
        viewModelScope.launch {
            try { block()
            } catch (e: CancellationException) { throw e
            } catch (e: ProjectStorageException) { mutableState.value = mutableState.value.copy(error = when (e.code) {
                com.valhalla.tavernforge.data.ProjectStorageError.IN_USE -> "This item is linked. Remove its associations before deleting it."
                com.valhalla.tavernforge.data.ProjectStorageError.REVISION_CONFLICT -> "The project changed. Refresh it before trying again."
                else -> "The local operation failed. Your saved originals remain available."
            })
            } catch (_: DocumentTooLargeException) { mutableState.value = mutableState.value.copy(error = "The PNG exceeds the 32 MiB input limit.")
            } catch (_: SecurityException) { mutableState.value = mutableState.value.copy(error = "Permission to read this document was revoked. Select it again.")
            } catch (_: Exception) { mutableState.value = mutableState.value.copy(error = "The operation could not complete. Refresh the project or select the file again.")
            } finally { mutableState.value = mutableState.value.copy(working = false) }
        }
    }
}

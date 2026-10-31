package com.valhalla.tavernforge

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.valhalla.tavernforge.core.model.ArtworkSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CompositionScreen(state: CompositionState, viewModel: CompositionViewModel, onClose: () -> Unit, onOpenCharacter: (String) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::inspectPng) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri -> viewModel.completePngExport(uri) }
    var deleteArtwork by remember { mutableStateOf<ArtworkSummary?>(null) }
    LaunchedEffect(state.exportPreview?.id, state.exportPreview?.pickerReady, state.working) {
        viewModel.takePngDestination()?.let { file ->
            try { exporter.launch(file) } catch (_: RuntimeException) { viewModel.pngLauncherFailed() }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            TextButton(onClick = onClose, enabled = !state.busy) { Text("Close artwork and lorebooks") }
            Text("Artwork and lorebooks", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text("Import a PNG card or artwork on its own, then associate saved artwork and lorebooks with a character.")
            if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.notice?.let { Text(it) }
            Button(onClick = { picker.launch(arrayOf("image/png")) }, enabled = !state.busy && state.importPreview == null) { Text("Choose PNG card or artwork") }
        }
        state.importPreview?.let { preview ->
            item {
                Card {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(preview.fileName, style = MaterialTheme.typography.titleLarge)
                        PngThumbnail(preview)
                        Text("${preview.container.width} × ${preview.container.height} · ${if (preview.container.isAnimated) "Animated PNG" else "PNG"}")
                        preview.container.issues.forEach { Text(it.message) }
                        if (preview.needsChoice) Text("The preferred metadata is conflicting or malformed. Choose a readable candidate explicitly; no fallback was selected.")
                        if (preview.container.candidates.isEmpty()) Text("No character metadata. This can be saved as standalone artwork.")
                        preview.container.candidates.forEach { candidate ->
                            FilterChip(selected = candidate.id == preview.selectedCandidateId,
                                onClick = { viewModel.chooseCandidate(candidate.id) }, enabled = !state.busy && candidate.document != null,
                                label = { Text("${candidate.keyword} · chunk ${candidate.chunkIndex} · ${candidate.document?.displayName ?: "Unnamed or unreadable"}") })
                            candidate.issues.forEach { Text(it.message, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (preview.selectedCandidateId != null) Button(onClick = viewModel::savePngCard, enabled = !state.busy) { Text("Save selected card and original PNG") }
                        OutlinedButton(onClick = viewModel::saveArtwork, enabled = !state.busy) { Text("Save original as artwork only") }
                        TextButton(onClick = viewModel::discardImport, enabled = !state.busy) { Text("Discard unsaved PNG") }
                        Text("The unsaved preview is held in memory until you choose Save. An artwork-only save does not adopt its card metadata.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        state.character?.let { character ->
            item {
                Card {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(character.title, style = MaterialTheme.typography.titleLarge)
                        Text("Character revision ${state.composition?.documentRevision ?: 0}")
                        TextButton(onClick = { onOpenCharacter(character.id) }, enabled = !state.busy && state.importPreview == null) { Text("Open character inspector") }
                        TextButton(onClick = { viewModel.open(character.id) }, enabled = !state.busy) { Text("Refresh associations") }
                        state.composition?.let { composition ->
                            Text("Artwork: ${composition.artwork?.fileName ?: "None selected"}")
                            composition.sourcePng?.let { Text("Original card PNG retained: ${it.fileName}", style = MaterialTheme.typography.bodySmall) }
                            if (composition.artwork != null) TextButton(onClick = { viewModel.setArtwork(null) }, enabled = !state.busy) { Text("Remove artwork association") }
                            if (composition.artwork != null || composition.sourcePng != null) Button(onClick = viewModel::preparePngExport, enabled = !state.busy) { Text("Review PNG export") }
                        }
                    }
                }
            }
        }
        item { Text("Artwork library", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
        if (state.artwork.isEmpty()) item { Text("No saved artwork. PNG artwork can be kept without a character.") }
        items(state.artwork, key = { "art-${it.id}" }) { artwork ->
            Card {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(artwork.fileName, style = MaterialTheme.typography.titleMedium)
                    Text("${artwork.width} × ${artwork.height} · ${artwork.sizeBytes} bytes")
                    if (state.character != null) OutlinedButton(onClick = { viewModel.setArtwork(artwork.id) }, enabled = !state.busy) { Text("Use for this character") }
                    TextButton(onClick = { deleteArtwork = artwork }, enabled = !state.busy) { Text("Delete artwork") }
                }
            }
        }
        if (state.character != null) {
            item {
                Text("Linked lorebooks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text("Links keep separate originals. Embedding is a reviewed snapshot for matching V2/V3 formats, and never silently converts native World Info.", style = MaterialTheme.typography.bodySmall)
            }
            items(state.lorebooks, key = { "lore-${it.id}" }) { lorebook ->
                val link = state.composition?.lorebooks?.firstOrNull { it.project.id == lorebook.id }
                Card {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(lorebook.title, style = MaterialTheme.typography.titleMedium)
                        Text(formatLabel(lorebook.sourceFormat))
                        if (link == null) OutlinedButton(onClick = { viewModel.linkLorebook(lorebook.id) }, enabled = !state.busy) { Text("Link lorebook") }
                        else {
                            Text("Linked revision ${link.documentRevision} · embedded snapshot ${link.embeddedRevision ?: "none"}")
                            Button(onClick = { viewModel.prepareEmbedding(lorebook.id) }, enabled = !state.busy) { Text("Review embedding snapshot") }
                            TextButton(onClick = { viewModel.linkLorebook(lorebook.id, remove = true) }, enabled = !state.busy) { Text("Unlink lorebook") }
                        }
                    }
                }
            }
        }
    }
    state.exportPreview?.takeUnless { it.pickerReady || it.pickerLaunched }?.let { preview ->
        AlertDialog(onDismissRequest = viewModel::cancelPngExport, title = { Text("Review PNG export") },
            text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("Export saved character revision ${preview.revision}. Image chunks are preserved; the following character metadata changes are explicit.") }
                if (preview.reviewItems.isEmpty()) item { Text("No existing card metadata needs replacement.") }
                items(preview.reviewItems) { Text(it) }
            } }, confirmButton = { TextButton(onClick = viewModel::approvePngExport, enabled = !state.working) { Text("Accept and choose destination") } },
            dismissButton = { TextButton(onClick = viewModel::cancelPngExport, enabled = !state.working) { Text("Cancel") } })
    }
    state.embeddingPreview?.let { preview ->
        AlertDialog(onDismissRequest = viewModel::cancelEmbedding, title = { Text("Embed lorebook snapshot?") },
            text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("Embed ${preview.name} revision ${preview.plan.lorebookRevision} into character revision ${preview.plan.characterRevision}. This saves a new character revision; later lorebook edits require another reviewed snapshot.") }
                items(preview.plan.reviewItems) { Text(it) }
            } }, confirmButton = { TextButton(onClick = viewModel::acceptEmbedding, enabled = !state.working) { Text("Accept snapshot and save") } },
            dismissButton = { TextButton(onClick = viewModel::cancelEmbedding, enabled = !state.working) { Text("Cancel") } })
    }
    deleteArtwork?.let { artwork ->
        AlertDialog(onDismissRequest = { deleteArtwork = null }, title = { Text("Delete artwork?") }, text = { Text("Remove ${artwork.fileName} from this device? Linked artwork and original PNG provenance cannot be deleted while in use.") },
            confirmButton = { TextButton(onClick = { deleteArtwork = null; viewModel.deleteArtwork(artwork.id) }, enabled = !state.busy) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteArtwork = null }) { Text("Keep artwork") } })
    }
}

@Composable
private fun PngThumbnail(preview: PngImportPreview) {
    val bitmap by produceState<ImageBitmap?>(null, preview.container.sha256) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                var sample = 1
                while (maxOf(preview.container.width, preview.container.height) / sample > 1024) sample *= 2
                val bytes = preview.container.bytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it, contentDescription = "Selected PNG artwork preview", modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) }
}

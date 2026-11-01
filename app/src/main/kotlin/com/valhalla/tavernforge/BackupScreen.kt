package com.valhalla.tavernforge

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun BackupScreen(state: BackupState, model: BackupViewModel, onClose: () -> Unit) {
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), model::completeExport)
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), model::completeRestore)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { TextButton(onClick = onClose, enabled = !state.busy) { Text("Back to library") } }
        item { Text("Library backup", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }) }
        item { Text("Keep a portable copy of saved projects, original and edited JSON, artwork, artwork associations, linked lorebooks and local tags. Backup and restore work offline.") }
        item { Text("API keys, provider request previews, unsaved edits and edit recovery files are excluded. The archive is not encrypted; choose a destination you trust.") }
        item { Button(onClick = { model.requestExport()?.let { name ->
            try { exportPicker.launch(name) } catch (_: RuntimeException) { model.pickerFailed() }
        } }, enabled = !state.busy && state.review == null) { Text("Export library backup") } }
        item { Text("Restore validates the entire archive before review. Accepted projects and artwork receive new identities; existing library items are kept. Restoring the same backup again creates additional copies.") }
        item { OutlinedButton(onClick = {
            if (model.requestRestore()) try { restorePicker.launch(arrayOf("application/zip", "application/octet-stream")) }
            catch (_: RuntimeException) { model.pickerFailed() }
        }, enabled = !state.busy && state.review == null) { Text("Choose backup to restore") } }
        item { Text("Supported limits: 256 MiB archive, 1,000 projects and 1,000 artwork files. JSON and image limits also apply.", style = MaterialTheme.typography.bodySmall) }
        if (state.working) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Processing library backup…") }
        state.notice?.let { notice -> item { Text(notice, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } }
        state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } }
    }
    state.review?.let { review ->
        AlertDialog(onDismissRequest = model::discardReview, title = { Text("Restore verified backup?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${review.projects} projects · ${review.artwork} artwork files")
                Text("${review.associations} artwork/composition records · ${review.loreLinks} lorebook links · ${review.taggedProjects} projects with local tags")
                Text("Add these as new library copies. Existing projects and artwork remain intact. Imported content keeps its original format and validation state.")
            } },
            confirmButton = { TextButton(onClick = model::acceptRestore) { Text("Restore as new copies") } },
            dismissButton = { TextButton(onClick = model::discardReview) { Text("Cancel") } })
    }
}

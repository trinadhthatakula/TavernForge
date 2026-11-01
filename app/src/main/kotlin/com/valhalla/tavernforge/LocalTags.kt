package com.valhalla.tavernforge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class LocalTagEditor(val projectId: String, val title: String, val revision: Long, val tags: List<String>, val error: String? = null)

@Composable
internal fun LocalTagsDialog(editor: LocalTagEditor, viewModel: InspectionViewModel, busy: Boolean) {
    AlertDialog(onDismissRequest = viewModel::dismissLocalTags, title = { Text("Local tags · ${editor.title}") },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("These organize your local library. Native character JSON tags remain unchanged. Up to 32 distinct tags, 1–64 characters each.") }
            items(editor.tags.size) { index ->
                OutlinedTextField(value = editor.tags[index], onValueChange = { viewModel.updateLocalTag(index, it) },
                    label = { Text("Tag ${index + 1}") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { viewModel.removeLocalTag(index) }, enabled = !busy) { Text("Remove tag ${index + 1}") }
            }
            item { TextButton(onClick = viewModel::addLocalTag, enabled = !busy && editor.tags.size < 32) { Text("Add local tag") } }
            editor.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        } }, confirmButton = { TextButton(onClick = viewModel::saveLocalTags, enabled = !busy) { Text("Save local tags") } },
        dismissButton = { TextButton(onClick = viewModel::dismissLocalTags, enabled = !busy) { Text("Cancel") } })
}

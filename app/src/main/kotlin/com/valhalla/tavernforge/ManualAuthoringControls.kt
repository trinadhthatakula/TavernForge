package com.valhalla.tavernforge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.valhalla.tavernforge.format.LoreCommand

@Composable
internal fun TypedInputControls(input: EditInput, viewModel: InspectionViewModel, enabled: Boolean) {
    val field = input.field
    if (field != null) {
        Text(if (field.required) "Required by this format. Empty text/arrays may still be valid." else "Optional. Absent and explicit null are distinct.", style = MaterialTheme.typography.bodySmall)
        if (field.help.isNotBlank()) Text(field.help, style = MaterialTheme.typography.bodySmall)
    }
    if (field != null && (field.control == FieldControl.BOOLEAN || field.nullable || field.options.isNotEmpty())) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (field.control == FieldControl.BOOLEAN) {
                FilterChip(selected = input.text == "true", onClick = { viewModel.setTypedValue("true") }, enabled = enabled, label = { Text("True") })
                FilterChip(selected = input.text == "false", onClick = { viewModel.setTypedValue("false") }, enabled = enabled, label = { Text("False") })
            }
            if (field.nullable) FilterChip(selected = input.text == "null", onClick = { viewModel.setTypedValue("null") }, enabled = enabled, label = { Text("Inherit (null)") })
            field.options.forEach { (label, value) ->
                FilterChip(selected = input.text == value, onClick = { viewModel.setTypedValue(value, field.control == FieldControl.TEXT) },
                    enabled = enabled, label = { Text(label) })
            }
        }
    }
    val items = input.stringItems
    if (items != null) {
        var page by remember(input.path) { mutableIntStateOf(0) }
        val pageSize = 10
        val lastPage = (items.size - 1).coerceAtLeast(0) / pageSize
        val shownPage = page.coerceAtMost(lastPage)
        Text("${items.size} items. Each input is one exact string; commas and newlines stay inside that item.", style = MaterialTheme.typography.bodySmall)
        items.drop(shownPage * pageSize).take(pageSize).forEachIndexed { local, value ->
            val index = shownPage * pageSize + local
            OutlinedTextField(value = value, onValueChange = { viewModel.setStringItem(index, it) },
                label = { Text("Item ${index + 1}") }, enabled = enabled, modifier = Modifier.fillMaxWidth(), maxLines = 6)
            TextButton(onClick = { viewModel.removeStringItem(index) }, enabled = enabled) { Text("Remove item ${index + 1}") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { page = shownPage - 1 }, enabled = shownPage > 0) { Text("Previous items") }
            TextButton(onClick = { page = shownPage + 1 }, enabled = shownPage < lastPage) { Text("Next items") }
            OutlinedButton(onClick = viewModel::addStringItem, enabled = enabled) { Text("Add empty item") }
        }
        TextButton(onClick = { viewModel.setEditText(input.text) }, enabled = enabled) { Text("Edit exact JSON array") }
    } else {
        OutlinedTextField(value = input.text, onValueChange = viewModel::setEditText,
            label = { Text("New value") }, minLines = if (field?.control in listOf(FieldControl.NUMBER, FieldControl.BOOLEAN)) 1 else 4,
            maxLines = 12, modifier = Modifier.fillMaxWidth(), enabled = enabled, isError = input.limitExceeded)
    }
}

@Composable
internal fun LoreControls(editor: JsonEditorState, viewModel: InspectionViewModel, enabled: Boolean) {
    if (editor.canAddBook) OutlinedButton(onClick = { viewModel.applyLoreCommand(LoreCommand.CreateEmbeddedBook) }, enabled = enabled) { Text("Add embedded lorebook") }
    if (!editor.hasBook) return
    var page by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<LoreEntryItem?>(null) }
    val pageSize = 12
    val lastPage = (editor.entries.size - 1).coerceAtLeast(0) / pageSize
    val shownPage = page.coerceAtMost(lastPage)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Lore entries (${editor.entries.size})", style = MaterialTheme.typography.titleLarge)
        Text(if (editor.nativeBook) "List order updates displayIndex. Insertion order, IDs and object keys stay unchanged."
            else "List order changes the entry array. Insertion order and IDs stay unchanged.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.applyLoreCommand(LoreCommand.AddEntry()) }, enabled = enabled) { Text("Add entry") }
            TextButton(onClick = { viewModel.selectLoreEntry(null) }, enabled = enabled && editor.selectedEntry != null) { Text("Book fields") }
        }
        editor.entries.drop(shownPage * pageSize).take(pageSize).forEach { entry ->
            FilterChip(selected = editor.selectedEntry == entry.locator, onClick = { viewModel.selectLoreEntry(entry.locator) },
                enabled = enabled, label = { Text(entry.label) })
        }
        if (lastPage > 0) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { page = shownPage - 1 }, enabled = shownPage > 0) { Text("Previous entries") }
            Text("Page ${shownPage + 1} of ${lastPage + 1}")
            TextButton(onClick = { page = shownPage + 1 }, enabled = shownPage < lastPage) { Text("Next entries") }
        }
        val selected = editor.entries.firstOrNull { it.locator == editor.selectedEntry }
        if (selected != null) {
            Text("Selected: ${selected.label}", style = MaterialTheme.typography.titleMedium)
            Text(selected.path.take(240), style = MaterialTheme.typography.bodySmall)
            val index = editor.entries.indexOf(selected)
            fun move(to: Int) {
                val order = editor.entries.map { it.locator }.toMutableList()
                order.add(to, order.removeAt(index))
                viewModel.applyLoreCommand(LoreCommand.ReorderEntries(order))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.applyLoreCommand(LoreCommand.DuplicateEntry(selected.locator)) }, enabled = enabled) { Text("Duplicate entry") }
                TextButton(onClick = { move(index - 1) }, enabled = enabled && index > 0) { Text("Move up") }
                TextButton(onClick = { move(index + 1) }, enabled = enabled && index < editor.entries.lastIndex) { Text("Move down") }
                TextButton(onClick = { deleting = selected }, enabled = enabled) { Text("Delete entry") }
            }
            Text("Duplicate keeps all content and extensions. A present entry ID is replaced with an unused ID. Native copies get a display index after the selected entry.", style = MaterialTheme.typography.bodySmall)
        }
    }
    deleting?.let { entry ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete entry?") },
            text = { Text("Remove ${entry.label}? Undo is available until you save these changes.") },
            confirmButton = { TextButton(onClick = { deleting = null; viewModel.applyLoreCommand(LoreCommand.DeleteEntry(entry.locator)) }, enabled = enabled) { Text("Delete entry") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep entry") } })
    }
}

@Composable
internal fun WritingGuidance(viewModel: InspectionViewModel, enabled: Boolean) {
    var topic by remember { mutableStateOf("Appearance") }
    var destination by remember { mutableStateOf("description") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Writing guidance", style = MaterialTheme.typography.titleLarge)
        Text("Choose a topic and destination. Review the inserted heading and write your details before applying. These are ordinary character text, not extra JSON fields.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Appearance", "Clothing", "Visual description", "Speech style", "Vocabulary", "Mannerisms").forEach { value ->
                FilterChip(selected = topic == value, onClick = { topic = value }, enabled = enabled, label = { Text(value) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("description", "personality").forEach { value ->
                FilterChip(selected = destination == value, onClick = { destination = value }, enabled = enabled, label = { Text("Into $value") })
            }
        }
        OutlinedButton(onClick = { viewModel.prepareGuidance(destination, "$topic: ") }, enabled = enabled) { Text("Preview heading in $destination") }
    }
}

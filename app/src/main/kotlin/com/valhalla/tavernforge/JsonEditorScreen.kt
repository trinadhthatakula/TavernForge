package com.valhalla.tavernforge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valhalla.tavernforge.core.model.SourceFormat

@Composable
internal fun JsonEditorScreen(state: InspectionState, viewModel: InspectionViewModel, onClose: () -> Unit) {
    val editor = state.editor
    val format = state.selectedProject?.sourceFormat
    val isCharacter = format in setOf(SourceFormat.V1, SourceFormat.V2, SourceFormat.V3)
    val hasLoreSection = !isCharacter || format != SourceFormat.V1
    var section by rememberSaveable(state.selectedProject?.id) { mutableStateOf(if (isCharacter) "Story" else "Lore") }
    var showHelp by rememberSaveable(state.selectedProject?.id) { mutableStateOf(false) }
    val input = editor?.input
    // Recovery may reopen an input before this screen's local section state exists.
    // Keep the active input visible and disable section changes until Apply or Discard.
    val shownSection = if (input != null && !input.pathEditable) {
        if (input.path.startsWith("/data/character_book/") || !isCharacter) "Lore" else "Story"
    } else section
    val selectedEntryPath = editor?.let { current -> current.entries.firstOrNull { it.locator == current.selectedEntry }?.path }
    val fields = editor?.fields.orEmpty().filter { field ->
        when {
            shownSection == "Story" -> !field.path.startsWith("/data/character_book/")
            selectedEntryPath != null -> field.path.startsWith("$selectedEntryPath/")
            isCharacter -> field.path.startsWith("/data/character_book/")
            else -> true
        }
    }
    val scrollState = rememberLazyListState()
    LaunchedEffect(state.selectedProject?.id, input?.initialPath) {
        if (input != null) {
            section = shownSection
            scrollState.scrollToItem(0)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.fillMaxHeight().widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(24.dp),
            state = scrollState, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onClose, enabled = !state.busy) { Text(stringResource(R.string.close_editor)) }
                    Text(if (isCharacter) "Character editor" else "Lorebook editor", style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() })
                    Text(state.selectedProject?.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
                    Text("Apply each edit, then save your changes.", style = MaterialTheme.typography.bodyMedium)
                    LibraryFeedback(state)
                }
            }
            if (editor != null) {
                if (editor.pendingPaths.isNotEmpty() || editor.canUndo || editor.canRedo) item {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(pluralStringResource(R.plurals.pending_changes, editor.pendingPaths.size, editor.pendingPaths.size), style = MaterialTheme.typography.titleSmall)
                            if (editor.hasUnappliedInput) Text(stringResource(R.string.apply_before_save), style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = viewModel::saveEdits, enabled = !state.busy && editor.pendingPaths.isNotEmpty() && !editor.hasUnappliedInput) {
                                    Text(stringResource(R.string.save_changes))
                                }
                                TextButton(onClick = viewModel::undoEdit, enabled = !state.busy && editor.canUndo && input == null) { Text("Undo") }
                                TextButton(onClick = viewModel::redoEdit, enabled = !state.busy && editor.canRedo && input == null) { Text("Redo") }
                            }
                            if (editor.mode == AuthoringMode.ADVANCED) {
                                editor.pendingPaths.takeLast(8).forEach { Text(it.take(240), style = MaterialTheme.typography.bodySmall) }
                                if (editor.pendingPaths.size > 8) Text(stringResource(R.string.recent_edits_shown))
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (isCharacter && hasLoreSection) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf("Story", "Lore").forEach { option ->
                                FilterChip(selected = shownSection == option, onClick = { section = option },
                                    enabled = !state.busy && input == null, label = { Text(option) })
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AuthoringMode.entries.forEach { mode ->
                                FilterChip(selected = editor.mode == mode, onClick = { viewModel.setAuthoringMode(mode) },
                                    enabled = !state.busy && input == null, label = { Text(if (mode == AuthoringMode.BASIC) "Basic" else "Advanced") })
                            }
                        }
                    }
                }
                if (input != null) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(input.field?.label ?: stringResource(if (input.mode == EditValueMode.TEXT) R.string.edit_text_value else R.string.edit_json_value),
                                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                                if (input.pathEditable) {
                                    Text(stringResource(R.string.json_pointer_help), style = MaterialTheme.typography.bodySmall)
                                    OutlinedTextField(value = input.path, onValueChange = viewModel::setEditPath,
                                        label = { Text(stringResource(R.string.json_path)) }, singleLine = true,
                                        modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                                } else if (editor.mode == AuthoringMode.ADVANCED) Text(input.path.take(512), style = MaterialTheme.typography.bodyMedium)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    FilterChip(selected = input.action == EditAction.SET, onClick = { viewModel.setEditAction(EditAction.SET) },
                                        label = { Text(stringResource(R.string.set_value)) }, enabled = !state.busy)
                                    FilterChip(selected = input.action == EditAction.REMOVE, onClick = { viewModel.setEditAction(EditAction.REMOVE) },
                                        label = { Text(stringResource(R.string.remove_value)) }, enabled = !state.busy)
                                }
                                if (input.action == EditAction.SET) {
                                    if (editor.mode == AuthoringMode.ADVANCED) {
                                        Text(stringResource(if (input.mode == EditValueMode.TEXT) R.string.text_value_help else R.string.json_value_help), style = MaterialTheme.typography.bodySmall)
                                        if (!input.pathEditable) Text(stringResource(R.string.previous_value, input.initialText.take(240).ifEmpty { "\"\"" }), style = MaterialTheme.typography.bodySmall)
                                    }
                                    TypedInputControls(input, viewModel, !state.busy)
                                } else Text(stringResource(R.string.remove_value_help))
                                if (input.limitExceeded) Text(stringResource(R.string.editor_input_limit), color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                Button(onClick = viewModel::applyEditInput, enabled = !state.busy && !input.limitExceeded, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(if (input.action == EditAction.REMOVE) R.string.apply_removal else R.string.apply_value))
                                }
                                TextButton(onClick = viewModel::cancelEditInput, enabled = !state.busy) { Text(stringResource(R.string.discard_value_input)) }
                            }
                        }
                    }
                } else {
                    if (shownSection == "Lore") item { LoreControls(editor, viewModel, !state.busy) }
                    if (editor.mode == AuthoringMode.ADVANCED) item {
                        OutlinedButton(onClick = viewModel::editAdvanced, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.advanced_json_value))
                        }
                    }
                    items(fields, key = { it.path }) { field ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(field.label, style = MaterialTheme.typography.titleMedium)
                                        Text(if (field.required) "Required by format" else "Optional", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TextButton(onClick = { viewModel.editField(field.path) }, enabled = !state.busy,
                                        modifier = Modifier.semantics { contentDescription = "Edit ${field.label}" }) { Text("Edit") }
                                }
                                Text(field.preview, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                if (editor.mode == AuthoringMode.ADVANCED) Text(field.path, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (editor.namedEntriesLimited) item { Text(stringResource(R.string.named_entries_limit)) }
                    if (shownSection == "Story" && editor.guidance) item { WritingGuidance(viewModel, !state.busy) }
                }
                items(editor.editIssues.take(20)) { DiagnosticCard(it) }
                item {
                    Text(stringResource(R.string.draft_validation), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() })
                }
                if (editor.issues.isEmpty()) item { Text(stringResource(R.string.no_issues)) }
                items(editor.issues.take(20)) { DiagnosticCard(it) }
                if (editor.issues.size > 20) item { Text(stringResource(R.string.first_issues_shown)) }
                item {
                    TextButton(onClick = { showHelp = !showHelp }) { Text(if (showHelp) "Hide saving and recovery help" else "Saving and recovery help") }
                    if (showHelp) {
                        Text(stringResource(R.string.edit_revision, state.documentRevision), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.editor_preservation), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.save_draft_edits), style = MaterialTheme.typography.bodySmall)
                        Text("Required fields may allow empty text or arrays. Format validity and creative completeness are separate.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

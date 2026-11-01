package com.valhalla.tavernforge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun JsonEditorScreen(state: InspectionState, viewModel: InspectionViewModel, onClose: () -> Unit) {
    val editor = state.editor
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onClose, enabled = !state.busy) { Text(stringResource(R.string.close_editor)) }
                Text(stringResource(R.string.edit_content), style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() })
                Text(state.selectedProject?.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.edit_revision, state.documentRevision))
                Text(stringResource(R.string.editor_preservation), style = MaterialTheme.typography.bodySmall)
                LibraryFeedback(state)
            }
        }
        if (editor != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(pluralStringResource(R.plurals.pending_changes, editor.pendingPaths.size, editor.pendingPaths.size), style = MaterialTheme.typography.titleMedium)
                        editor.pendingPaths.takeLast(8).forEach { Text(it.take(240), style = MaterialTheme.typography.bodySmall) }
                        if (editor.pendingPaths.size > 8) Text(stringResource(R.string.recent_edits_shown))
                        if (editor.hasUnappliedInput) Text(stringResource(R.string.apply_before_save))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(onClick = viewModel::undoEdit, enabled = !state.busy && editor.canUndo && editor.input == null) { Text("Undo unsaved change") }
                            TextButton(onClick = viewModel::redoEdit, enabled = !state.busy && editor.canRedo && editor.input == null) { Text("Redo") }
                        }
                        Button(onClick = viewModel::saveEdits, enabled = !state.busy && editor.pendingPaths.isNotEmpty() && !editor.hasUnappliedInput,
                            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_changes)) }
                        if (editor.pendingPaths.isNotEmpty()) Text(stringResource(R.string.save_draft_edits), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            val input = editor.input
            if (input != null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(if (input.mode == EditValueMode.TEXT) R.string.edit_text_value else R.string.edit_json_value),
                            style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        if (input.pathEditable) {
                            Text(stringResource(R.string.json_pointer_help), style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = input.path, onValueChange = viewModel::setEditPath,
                                label = { Text(stringResource(R.string.json_path)) }, singleLine = true,
                                modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                        } else Text(input.path.take(512), style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(selected = input.action == EditAction.SET, onClick = { viewModel.setEditAction(EditAction.SET) },
                                label = { Text(stringResource(R.string.set_value)) }, enabled = !state.busy)
                            FilterChip(selected = input.action == EditAction.REMOVE, onClick = { viewModel.setEditAction(EditAction.REMOVE) },
                                label = { Text(stringResource(R.string.remove_value)) }, enabled = !state.busy)
                        }
                        if (input.action == EditAction.SET) {
                            Text(stringResource(if (input.mode == EditValueMode.TEXT) R.string.text_value_help else R.string.json_value_help),
                                style = MaterialTheme.typography.bodySmall)
                            if (!input.pathEditable) {
                                Text(stringResource(R.string.previous_value, input.initialText.take(240).ifEmpty { "\"\"" }),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            TypedInputControls(input, viewModel, !state.busy)
                        } else Text(stringResource(R.string.remove_value_help))
                        if (input.limitExceeded) Text(stringResource(R.string.editor_input_limit), color = MaterialTheme.colorScheme.error)
                        Button(onClick = viewModel::applyEditInput, enabled = !state.busy && !input.limitExceeded,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(if (input.action == EditAction.REMOVE) R.string.apply_removal else R.string.apply_value))
                        }
                        TextButton(onClick = viewModel::cancelEditInput, enabled = !state.busy) {
                            Text(stringResource(R.string.discard_value_input))
                        }
                    }
                }
            } else {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AuthoringMode.entries.forEach { mode ->
                            FilterChip(selected = editor.mode == mode, onClick = { viewModel.setAuthoringMode(mode) },
                                enabled = !state.busy, label = { Text(if (mode == AuthoringMode.BASIC) "Basic" else "Advanced") })
                        }
                    }
                    Text("Empty required text can pass the format rules. Creative completeness is separate from validation.", style = MaterialTheme.typography.bodySmall)
                    if (editor.mode == AuthoringMode.ADVANCED) OutlinedButton(onClick = viewModel::editAdvanced, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.advanced_json_value))
                    }
                }
                item {
                    LoreControls(editor, viewModel, !state.busy)
                }
                items(editor.fields, key = { it.path }) { field ->
                    Card {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(field.label, style = MaterialTheme.typography.titleMedium)
                            Text(field.preview, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(field.path, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall)
                            Text(if (field.required) "Required by format" else "Optional", style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { viewModel.editField(field.path) }, enabled = !state.busy) {
                                Text(stringResource(R.string.edit_value))
                            }
                        }
                    }
                }
                if (editor.namedEntriesLimited) item { Text(stringResource(R.string.named_entries_limit)) }
                if (editor.guidance) item { WritingGuidance(viewModel, !state.busy) }
            }
            items(editor.editIssues.take(20)) { DiagnosticCard(it) }
            item {
                Text(stringResource(R.string.draft_validation), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() })
            }
            if (editor.issues.isEmpty()) item { Text(stringResource(R.string.no_issues)) }
            items(editor.issues.take(20)) { DiagnosticCard(it) }
            if (editor.issues.size > 20) item { Text(stringResource(R.string.first_issues_shown)) }
        }
    }
}

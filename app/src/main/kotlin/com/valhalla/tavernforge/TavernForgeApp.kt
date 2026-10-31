package com.valhalla.tavernforge

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.SupportingPaneSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberSupportingPaneSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass
import com.valhalla.tavernforge.core.model.Diagnostic
import com.valhalla.tavernforge.core.model.Severity
import com.valhalla.tavernforge.core.model.SourceFormat
import kotlinx.serialization.Serializable

@Serializable private data object Workshop : NavKey
@Serializable private data object Inspector : NavKey
@Serializable private data object Editor : NavKey

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun TavernForgeApp(viewModel: InspectionViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val backStack = rememberNavBackStack(Workshop)
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val sceneStrategies = if (adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        )) {
        val directive = calculatePaneScaffoldDirective(adaptiveInfo)
        listOf(rememberSupportingPaneSceneStrategy<NavKey>(
            directive = PaneScaffoldDirective(
                maxHorizontalPartitions = directive.maxHorizontalPartitions,
                horizontalPartitionSpacerSize = directive.horizontalPartitionSpacerSize,
                maxVerticalPartitions = directive.maxVerticalPartitions,
                verticalPartitionSpacerSize = directive.verticalPartitionSpacerSize,
                defaultPanePreferredWidth = directive.defaultPanePreferredWidth,
                defaultPanePreferredHeight = directive.defaultPanePreferredHeight,
                excludedBounds = directive.excludedBounds,
                // Pane changes must not select the library search field and open the keyboard.
                shouldAutoFocusCurrentDestination = false,
            ),
        ))
    } else {
        // Tall phone windows still use native single-destination navigation.
        emptyList()
    }
    var pendingReplacement by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.inspect(uri)
            if (backStack.lastOrNull() != Inspector) backStack.add(Inspector)
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        viewModel.completeExport(uri)
    }
    fun protectDraft(action: () -> Unit) {
        if (state.busy) return
        val proceed = { viewModel.discardEdits(); action() }
        if (state.hasUnsavedWork) pendingReplacement = proceed else proceed()
    }
    val importFile = { protectDraft { launcher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) } }
    val closeInspector = { if (!state.busy && backStack.size > 1) backStack.removeLastOrNull(); Unit }
    val closeEditor = { protectDraft { backStack.removeLastOrNull() } }
    LaunchedEffect(state.editor, state.operation) {
        if (state.editor == null && state.operation == null && backStack.lastOrNull() == Editor) backStack.removeLastOrNull()
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) }) },
    ) { padding ->
        NavDisplay(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            backStack = backStack,
            onBack = { if (backStack.lastOrNull() == Editor) closeEditor() else closeInspector() },
            sceneStrategies = sceneStrategies,
            entryProvider = entryProvider {
                entry<Workshop>(metadata = SupportingPaneSceneStrategy.mainPane()) {
                    LibraryScreen(state, importFile, onView = {
                        if (backStack.lastOrNull() != Inspector) backStack.add(Inspector)
                    }, onOpen = { id -> protectDraft {
                        viewModel.openProject(id)
                        if (backStack.lastOrNull() != Inspector) backStack.add(Inspector)
                    } }, onQuery = viewModel::setQuery, onSort = viewModel::setSort, onRetry = viewModel::refreshLibrary)
                }
                entry<Inspector>(metadata = SupportingPaneSceneStrategy.supportingPane()) {
                    InspectorScreen(state, importFile, closeInspector, onClear = { protectDraft {
                        viewModel.clear(); closeInspector()
                    } }, onSave = viewModel::saveDraft, onRename = viewModel::renameProject,
                        onDuplicate = viewModel::duplicateProject, onDelete = viewModel::deleteProject,
                        onEdit = { if (viewModel.beginEditing()) backStack.add(Editor) }, onExport = viewModel::prepareExport)
                }
                entry<Editor> { JsonEditorScreen(state, viewModel, closeEditor) }
            },
        )
    }
    state.exportPreview?.takeUnless { it.pickerLaunched }?.let { preview ->
        AlertDialog(onDismissRequest = viewModel::cancelExportPreview,
            title = { Text(stringResource(R.string.export_review_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.export_review_body, preview.revision, formatLabel(preview.sourceFormat),
                    pluralStringResource(R.plurals.byte_size, preview.sizeBytes, preview.sizeBytes)))
                Text(stringResource(if (preview.formattingChanged) R.string.export_formatting_changed else R.string.export_formatting_original))
            } },
            confirmButton = { TextButton(onClick = {
                viewModel.confirmExport()?.let { ready ->
                    try { exportLauncher.launch(ready.fileName) } catch (_: RuntimeException) { viewModel.exportLauncherFailed() }
                }
            }) { Text(stringResource(R.string.choose_export_destination)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelExportPreview) { Text(stringResource(R.string.cancel)) } })
    }
    if (pendingReplacement != null) {
        AlertDialog(onDismissRequest = { pendingReplacement = null },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_body)) },
            confirmButton = { TextButton(onClick = {
                val action = pendingReplacement
                pendingReplacement = null
                action?.invoke()
            }) { Text(stringResource(R.string.discard_continue)) } },
            dismissButton = { TextButton(onClick = { pendingReplacement = null }) { Text(stringResource(R.string.keep_draft)) } })
    }
}

@Composable
private fun InspectorScreen(
    state: InspectionState,
    onImport: () -> Unit,
    onClose: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onRename: (String) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
) {
    var renameOpen by remember(state.selectedProject?.id) { mutableStateOf(false) }
    var deleteOpen by remember(state.selectedProject?.id) { mutableStateOf(false) }
    var title by remember(state.selectedProject?.id) { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Column {
                TextButton(onClick = onClose) { Text(stringResource(R.string.close_inspection)) }
                Text(stringResource(R.string.inspect_title), style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() })
                LibraryFeedback(state)
            }
        }
        if (!state.loading && state.failure != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.failure_title), style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Text(stringResource(when (state.failure) {
                            ImportFailure.TOO_LARGE -> R.string.failure_large
                            ImportFailure.PERMISSION -> R.string.failure_permission
                            ImportFailure.READ -> R.string.failure_read
                        }))
                        Button(onClick = onImport, enabled = !state.busy) { Text(stringResource(R.string.import_another)) }
                    }
                }
            }
        }
        if (!state.loading && state.summary != null) {
            val summary = state.summary
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.selectedProject?.title ?: summary.documentName?.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.unnamed_document),
                        style = MaterialTheme.typography.titleLarge)
                    Text(summary.fileName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ValidationBadge(summary.status)
                    Text(stringResource(if (summary.status == InspectionStatus.UNSUPPORTED)
                        R.string.unsupported_note else R.string.schema_note), style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.selectedProject == null) {
                            Text(stringResource(if (summary.canSaveDraft) R.string.unsaved_note else R.string.cannot_save_malformed))
                            if (summary.canSaveDraft) Text(stringResource(R.string.export_requires_save), style = MaterialTheme.typography.bodySmall)
                            if (summary.canSaveDraft && summary.status != InspectionStatus.VALID) {
                                Text(stringResource(R.string.save_invalid_note))
                            }
                            Button(onClick = onSave, enabled = summary.canSaveDraft && !state.busy,
                                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_draft)) }
                        } else {
                            Text(stringResource(R.string.saved_note))
                            Text(stringResource(R.string.edit_revision, state.documentRevision))
                            Button(onClick = onEdit, enabled = state.canEdit && !state.busy,
                                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.edit_content)) }
                            OutlinedButton(onClick = onExport, enabled = state.canExport && !state.busy,
                                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_json)) }
                            if (!state.canExport) Text(stringResource(R.string.export_validation_blocked), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { title = state.selectedProject.title; renameOpen = true },
                                enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.rename_project)) }
                            OutlinedButton(onClick = onDuplicate, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.duplicate_project)) }
                            TextButton(onClick = { deleteOpen = true }, enabled = !state.busy) {
                                Text(stringResource(R.string.delete_project), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            item {
                Card {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Fact(stringResource(R.string.source_format), formatLabel(summary.sourceFormat))
                        summary.specVersion?.let { Fact(stringResource(R.string.spec_version), it) }
                        summary.loreEntryCount?.let {
                            Fact(stringResource(R.string.lore_entries), pluralStringResource(R.plurals.entry_count, it, it))
                        }
                        Fact(stringResource(R.string.source_size), pluralStringResource(R.plurals.byte_size, summary.sizeBytes, summary.sizeBytes))
                        if (summary.sha256.isNotEmpty()) Fact(stringResource(R.string.source_hash), summary.sha256)
                    }
                }
            }
            item {
                Text(stringResource(R.string.validation_title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() })
            }
            if (summary.issues.isEmpty()) {
                item { Text(stringResource(R.string.no_issues)) }
            } else {
                items(summary.issues) { issue -> DiagnosticCard(issue) }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.preservation_note), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(if (state.selectedProject == null) R.string.session_note else R.string.saved_note), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onImport, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.import_another))
                    }
                    TextButton(onClick = onClear, enabled = !state.busy) { Text(stringResource(R.string.clear_file)) }
                }
            }
        } else if (!state.loading && state.failure == null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.no_selection), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.no_selection_body))
                    Button(onClick = onImport, enabled = !state.busy) { Text(stringResource(R.string.import_json)) }
                }
            }
        }
    }
    if (renameOpen) AlertDialog(onDismissRequest = { renameOpen = false },
        title = { Text(stringResource(R.string.rename_project)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.rename_note))
            OutlinedTextField(value = title, onValueChange = { title = it.take(256) },
                label = { Text(stringResource(R.string.library_name)) }, singleLine = true)
        } },
        confirmButton = { TextButton(onClick = { renameOpen = false; onRename(title) }, enabled = title.isNotBlank() && !state.busy) {
            Text(stringResource(R.string.rename_confirm))
        } },
        dismissButton = { TextButton(onClick = { renameOpen = false }) { Text(stringResource(R.string.cancel)) } })
    if (deleteOpen) AlertDialog(onDismissRequest = { deleteOpen = false },
        title = { Text(stringResource(R.string.delete_project)) },
        text = { Text(stringResource(R.string.delete_body, state.selectedProject?.title.orEmpty())) },
        confirmButton = { TextButton(onClick = { deleteOpen = false; onDelete() }, enabled = !state.busy) {
            Text(stringResource(R.string.delete_confirm), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun DiagnosticCard(issue: Diagnostic) {
    val color = if (issue.severity == Severity.ERROR) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${issue.severity.name} · ${issue.phase.name}", style = MaterialTheme.typography.labelLarge)
            Text(issue.message, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.issue_location, issue.path.ifEmpty { "/" }.take(512)),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ValidationBadge(status: InspectionStatus) {
    val valid = status == InspectionStatus.VALID
    Surface(color = if (valid) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(stringResource(when (status) {
            InspectionStatus.VALID -> R.string.structure_passes
            InspectionStatus.NEEDS_REVIEW -> R.string.needs_review
            InspectionStatus.UNSUPPORTED -> R.string.unsupported_format
        }),
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun formatLabel(format: SourceFormat): String = stringResource(when (format) {
    SourceFormat.V1 -> R.string.format_character_v1
    SourceFormat.V2 -> R.string.format_character_v2
    SourceFormat.V3 -> R.string.format_character_v3
    SourceFormat.LOREBOOK_V2 -> R.string.format_lorebook_v2
    SourceFormat.LOREBOOK_V3 -> R.string.format_lorebook_v3
    SourceFormat.WORLD_INFO -> R.string.format_world_info
    SourceFormat.UNKNOWN -> R.string.format_unknown
})

@Composable
private fun Fact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BuildLabel() {
    Text(stringResource(R.string.internal_build), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.secondary)
}

@Composable
private fun LoadingStatus() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(R.string.reading))
    }
}

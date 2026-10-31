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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.SupportingPaneSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberSupportingPaneSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.valhalla.tavernforge.core.model.Diagnostic
import com.valhalla.tavernforge.core.model.Severity
import kotlinx.serialization.Serializable

@Serializable private data object Workshop : NavKey
@Serializable private data object Inspector : NavKey

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun TavernForgeApp(viewModel: InspectionViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val backStack = rememberNavBackStack(Workshop)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.inspect(uri)
            if (backStack.lastOrNull() != Inspector) backStack.add(Inspector)
        }
    }
    val importFile = { launcher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
    val closeInspector = { if (backStack.size > 1) backStack.removeLastOrNull(); Unit }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) }) },
    ) { padding ->
        NavDisplay(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            backStack = backStack,
            onBack = closeInspector,
            sceneStrategies = listOf(rememberSupportingPaneSceneStrategy<NavKey>()),
            entryProvider = entryProvider {
                entry<Workshop>(metadata = SupportingPaneSceneStrategy.mainPane()) {
                    WorkshopScreen(state, importFile, onView = {
                        if (backStack.lastOrNull() != Inspector) backStack.add(Inspector)
                    })
                }
                entry<Inspector>(metadata = SupportingPaneSceneStrategy.supportingPane()) {
                    InspectorScreen(state, importFile, closeInspector, onClear = {
                        viewModel.clear()
                        closeInspector()
                    })
                }
            },
        )
    }
}

@Composable
private fun WorkshopScreen(state: InspectionState, onImport: () -> Unit, onView: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BuildLabel()
                Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(if (state.summary == null) R.string.empty_title else R.string.inspect_title),
                        style = MaterialTheme.typography.headlineMedium)
                    if (state.summary != null) {
                        Text(state.summary.characterName?.takeIf(String::isNotBlank)
                            ?: state.summary.fileName, style = MaterialTheme.typography.titleLarge)
                        ValidationBadge(state.summary.isValidV2)
                        OutlinedButton(onClick = onView) { Text(stringResource(R.string.view_results)) }
                    } else {
                        Text(stringResource(R.string.empty_body), style = MaterialTheme.typography.bodyLarge)
                    }
                    Button(onClick = onImport, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (state.summary == null) R.string.import_json else R.string.import_another))
                    }
                    if (state.loading) LoadingStatus()
                    Text(stringResource(R.string.privacy_label), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.scope_title), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.scope_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.session_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InspectorScreen(
    state: InspectionState,
    onImport: () -> Unit,
    onClose: () -> Unit,
    onClear: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Column {
                TextButton(onClick = onClose) { Text(stringResource(R.string.close_inspection)) }
                Text(stringResource(R.string.inspect_title), style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() })
            }
        }
        if (state.loading) {
            item { LoadingStatus() }
        } else if (state.failure != null) {
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
                        Button(onClick = onImport) { Text(stringResource(R.string.import_another)) }
                    }
                }
            }
        } else if (state.summary != null) {
            val summary = state.summary
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(summary.characterName?.takeIf(String::isNotBlank) ?: stringResource(R.string.unnamed_character),
                        style = MaterialTheme.typography.titleLarge)
                    Text(summary.fileName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ValidationBadge(summary.isValidV2)
                    Text(stringResource(R.string.schema_note), style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Card {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Fact(stringResource(R.string.source_format), summary.sourceFormat.name)
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
                    Text(stringResource(R.string.session_note), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.import_another))
                    }
                    TextButton(onClick = onClear) { Text(stringResource(R.string.clear_file)) }
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.no_selection), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.no_selection_body))
                    Button(onClick = onImport) { Text(stringResource(R.string.import_json)) }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticCard(issue: Diagnostic) {
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
private fun ValidationBadge(valid: Boolean) {
    Surface(color = if (valid) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(stringResource(if (valid) R.string.valid_v2 else R.string.needs_review),
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

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

package com.valhalla.tavernforge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.valhalla.tavernforge.core.model.Severity

internal enum class PrimaryDestination(val label: String) {
    LIBRARY("Library"), ARTWORK("Artwork"), SETTINGS("Settings"),
}

@Composable
internal fun PrimaryNavigation(
    selected: PrimaryDestination,
    enabled: Boolean,
    rail: Boolean,
    onSelect: (PrimaryDestination) -> Unit,
) {
    if (rail) {
        NavigationRail {
            PrimaryDestination.entries.forEach { destination ->
                NavigationRailItem(selected = selected == destination, enabled = enabled,
                    onClick = { onSelect(destination) }, icon = { DestinationIcon(destination) },
                    label = { Text(destination.label) })
            }
        }
    } else {
        NavigationBar {
            PrimaryDestination.entries.forEach { destination ->
                NavigationBarItem(selected = selected == destination, enabled = enabled,
                    onClick = { onSelect(destination) }, icon = { DestinationIcon(destination) },
                    label = { Text(destination.label) })
            }
        }
    }
}

/** Simple original line icons; labels on the native navigation items provide their semantics. */
@Composable
private fun DestinationIcon(destination: PrimaryDestination) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / 24f
        val stroke = Stroke(1.7f * unit)
        when (destination) {
            PrimaryDestination.LIBRARY -> {
                for (x in listOf(3f, 11f)) {
                    drawRoundRect(color, Offset(x * unit, 4f * unit), Size(6f * unit, 16f * unit), CornerRadius(unit), style = stroke)
                }
                drawLine(color, Offset(19f * unit, 5f * unit), Offset(22f * unit, 20f * unit), stroke.width)
            }
            PrimaryDestination.ARTWORK -> {
                drawRoundRect(color, Offset(3f * unit, 3f * unit), Size(18f * unit, 18f * unit), CornerRadius(2f * unit), style = stroke)
                drawCircle(color, 2f * unit, Offset(8f * unit, 8f * unit))
                drawLine(color, Offset(4f * unit, 19f * unit), Offset(12f * unit, 11f * unit), stroke.width)
                drawLine(color, Offset(12f * unit, 11f * unit), Offset(20f * unit, 19f * unit), stroke.width)
            }
            PrimaryDestination.SETTINGS -> {
                for ((y, x) in listOf(6f to 8f, 12f to 16f, 18f to 10f)) {
                    drawLine(color, Offset(3f * unit, y * unit), Offset(21f * unit, y * unit), stroke.width)
                    drawCircle(color, 2.5f * unit, Offset(x * unit, y * unit), style = stroke)
                }
            }
        }
    }
}

@Composable
internal fun SettingsContent(onGemini: () -> Unit, onBackup: () -> Unit, onLicenses: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text("Your library stays on this device. Gemini is optional.", style = MaterialTheme.typography.bodyLarge)
        }
        item {
            Card(onClick = onGemini, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Gemini and API permissions", style = MaterialTheme.typography.titleLarge)
                    Text("Manage your key and review separate text, image and follow-up permissions.")
                }
            }
        }
        item {
            Card(onClick = onBackup, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Library backup and restore", style = MaterialTheme.typography.titleLarge)
                    Text("Export saved projects and artwork, or review a backup before adding copies.")
                }
            }
        }
        item {
            Text("TavernForge · Internal test build", style = MaterialTheme.typography.titleMedium)
            Text("Free software · GPL-3.0-or-later", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onLicenses) { Text("About and open-source licenses") }
        }
    }
}

@Composable
internal fun EditingInspector(state: InspectionState, onHide: () -> Unit) {
    val editor = state.editor ?: return
    val errors = editor.issues.count { it.severity == Severity.ERROR }
    val warnings = editor.issues.count { it.severity == Severity.WARNING }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            TextButton(onClick = onHide) { Text("Hide inspector") }
            Text("Draft inspector", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(state.selectedProject?.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
            state.summary?.let { Text(formatLabel(it.sourceFormat)) }
            Text("Saved revision ${state.documentRevision}", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Text("${editor.pendingPaths.size} unsaved changes", style = MaterialTheme.typography.titleMedium)
            if (editor.hasUnappliedInput) Text("Field input has not been applied. Validation below describes the applied draft.")
            Text("$errors errors · $warnings warnings", style = MaterialTheme.typography.titleMedium)
            Text(if (errors > 0) "Fix format errors before strict export." else "Applied draft structure passes. Save changes before exporting.")
        }
        items(editor.editIssues.take(20) + editor.issues.take(20)) { DiagnosticCard(it) }
        item {
            Text("Source format and unknown fields are preserved. Artwork and lorebook associations remain separate from this editing session.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

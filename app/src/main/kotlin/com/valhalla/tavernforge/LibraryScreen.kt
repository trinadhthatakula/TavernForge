package com.valhalla.tavernforge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valhalla.tavernforge.data.ProjectStorageError

@Composable
internal fun LibraryScreen(
    state: InspectionState,
    onImport: () -> Unit,
    onView: () -> Unit,
    onOpen: (String) -> Unit,
    onQuery: (String) -> Unit,
    onSort: (ProjectSort) -> Unit,
    onRetry: () -> Unit,
    onCreate: () -> Unit,
    onArtwork: () -> Unit,
    onGenerate: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(300.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.internal_build), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary)
                Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onImport, enabled = !state.busy) { Text(stringResource(R.string.import_json)) }
                OutlinedButton(onClick = onCreate, enabled = !state.busy) { Text("Create manually") }
                OutlinedButton(onClick = onArtwork, enabled = !state.busy) { Text("PNG cards and artwork") }
                OutlinedButton(onClick = onGenerate, enabled = !state.busy) { Text("Optional Gemini and key settings") }
                Text(stringResource(R.string.privacy_label), style = MaterialTheme.typography.labelLarge)
                LibraryFeedback(state)
            }
        }
        if (state.summary != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(if (state.hasUnsavedDraft) R.string.unsaved_draft else R.string.current_inspection),
                            style = MaterialTheme.typography.labelLarge)
                        Text(state.selectedProject?.title ?: state.summary.documentName?.takeIf(String::isNotBlank)
                            ?: state.summary.fileName, style = MaterialTheme.typography.titleLarge,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        ValidationBadge(state.summary.status)
                        if (state.hasUnsavedDraft) Text(stringResource(R.string.unsaved_note))
                        OutlinedButton(onClick = onView, enabled = !state.busy) { Text(stringResource(R.string.view_results)) }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
                OutlinedTextField(value = state.query, onValueChange = onQuery,
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.search_projects)) })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = state.sort == ProjectSort.RECENT, onClick = { onSort(ProjectSort.RECENT) },
                        label = { Text(stringResource(R.string.sort_recent)) })
                    FilterChip(selected = state.sort == ProjectSort.TITLE, onClick = { onSort(ProjectSort.TITLE) },
                        label = { Text(stringResource(R.string.sort_title)) })
                }
                if (state.preferenceFailed) Text(stringResource(R.string.preference_failed), style = MaterialTheme.typography.bodySmall)
                Text(pluralStringResource(R.plurals.project_count, state.visibleProjects.size, state.visibleProjects.size),
                    style = MaterialTheme.typography.labelLarge)
            }
        }
        when {
            state.libraryLoading -> item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.libraryFailed -> item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(stringResource(R.string.library_failed))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
            }
            state.visibleProjects.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(if (state.query.isBlank()) R.string.library_empty else R.string.search_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(state.visibleProjects, key = { it.id }) { project ->
            Card {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(project.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(formatLabel(project.sourceFormat), style = MaterialTheme.typography.bodyMedium)
                    Text(project.sourceFileName, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(if (project.isDraft) R.string.saved_draft else R.string.saved_local),
                        style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = { onOpen(project.id) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.open_project))
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(stringResource(R.string.scope_body), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun LibraryFeedback(state: InspectionState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(when (state.operation) {
                LibraryOperation.OPENING -> R.string.opening_project
                LibraryOperation.SAVING -> R.string.saving_project
                LibraryOperation.RENAMING -> R.string.renaming_project
                LibraryOperation.DUPLICATING -> R.string.duplicating_project
                LibraryOperation.DELETING -> R.string.deleting_project
                LibraryOperation.EDITING -> R.string.editing_project
                LibraryOperation.PREPARING_EXPORT -> R.string.preparing_export
                LibraryOperation.EXPORTING -> R.string.writing_export
                null -> R.string.reading
            }))
        }
        state.storageFailure?.let { failure ->
            Text(stringResource(when (failure) {
                ProjectStorageError.NOT_FOUND -> R.string.storage_not_found
                ProjectStorageError.INVALID_INPUT -> R.string.storage_invalid_input
                ProjectStorageError.CORRUPT_SOURCE -> R.string.storage_corrupt
                ProjectStorageError.IO_FAILURE -> R.string.storage_io
                ProjectStorageError.DATABASE_FAILURE -> R.string.storage_database
                ProjectStorageError.REVISION_CONFLICT -> R.string.storage_revision_conflict
                ProjectStorageError.IN_USE -> R.string.storage_in_use
            }), color = MaterialTheme.colorScheme.error)
        }
        state.exportFailure?.let { failure ->
            Text(stringResource(when (failure) {
                ExportFailure.VALIDATION -> R.string.export_failed_validation
                ExportFailure.WRITE -> R.string.export_failed_write
                ExportFailure.UNAVAILABLE -> R.string.export_unavailable
            }), color = MaterialTheme.colorScheme.error)
        }
        state.notice?.let { notice ->
            Text(stringResource(when (notice) {
                LibraryNotice.SAVED -> R.string.notice_saved
                LibraryNotice.RENAMED -> R.string.notice_renamed
                LibraryNotice.DUPLICATED -> R.string.notice_duplicated
                LibraryNotice.DELETED -> R.string.notice_deleted
                LibraryNotice.EDITS_SAVED -> R.string.notice_edits_saved
                LibraryNotice.EXPORTED -> R.string.notice_exported
            }))
        }
    }
}

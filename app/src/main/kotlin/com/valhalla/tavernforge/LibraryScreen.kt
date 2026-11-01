package com.valhalla.tavernforge

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.valhalla.tavernforge.data.ProjectStorageError
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.core.model.LibraryDraftFilter

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
    onBackup: () -> Unit,
    onFormatFilter: (SourceFormat) -> Unit,
    onDraftFilter: (LibraryDraftFilter) -> Unit,
    onTagFilter: (String) -> Unit,
    onClearFilters: () -> Unit,
    coverRefreshKey: Any? = null,
    loadCover: suspend (String) -> ImageBitmap? = { null },
) {
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var collection by rememberSaveable { mutableStateOf("All projects") }
    var chooseImport by rememberSaveable { mutableStateOf(false) }
    val activeFilterCount = state.formatFilters.size + state.tagFilters.size + if (state.draftFilter != LibraryDraftFilter.ALL) 1 else 0
    val collectionProjects = state.visibleProjects.filter { project ->
        when (collection) {
            "Characters" -> project.sourceFormat in characterFormats
            "Lorebooks" -> project.sourceFormat in lorebookFormats
            else -> true
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(300.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.internal_build), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary)
                Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() })
                Text("A home for your characters and lorebooks.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { chooseImport = true }, enabled = !state.busy) { Text("Combine files") }
                    OutlinedButton(onClick = onCreate, enabled = !state.busy) { Text("Build manually") }
                    OutlinedButton(onClick = onGenerate, enabled = !state.busy) { Text("Generate with Gemini") }
                }
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
                Text("On your shelf", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif,
                    modifier = Modifier.semantics { heading() })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All projects", "Characters", "Lorebooks").forEach { option ->
                        FilterChip(selected = collection == option, onClick = { collection = option }, label = { Text(option) })
                    }
                }
                OutlinedTextField(value = state.query, onValueChange = onQuery,
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.search_projects)) })
                TextButton(onClick = { showFilters = !showFilters }, modifier = Modifier.semantics {
                    stateDescription = if (showFilters) "Expanded" else "Collapsed"
                }) { Text("Sort and filters ($activeFilterCount active) · ${if (showFilters) "Hide" else "Show"}") }
                if (showFilters) {
                    Text("Sort projects", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(selected = state.sort == ProjectSort.RECENT, onClick = { onSort(ProjectSort.RECENT) },
                            label = { Text(stringResource(R.string.sort_recent)) })
                        FilterChip(selected = state.sort == ProjectSort.TITLE, onClick = { onSort(ProjectSort.TITLE) },
                            label = { Text(stringResource(R.string.sort_title)) })
                        FilterChip(selected = state.sort == ProjectSort.OLDEST, onClick = { onSort(ProjectSort.OLDEST) }, label = { Text("Oldest first") })
                    }
                    Text("Filter by source format", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceFormat.entries.forEach { format ->
                            FilterChip(selected = format in state.formatFilters, onClick = { onFormatFilter(format) }, label = { Text(formatLabel(format)) })
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LibraryDraftFilter.entries.forEach { filter ->
                            FilterChip(selected = state.draftFilter == filter, onClick = { onDraftFilter(filter) },
                                label = { Text(when (filter) { LibraryDraftFilter.ALL -> "All validation states"; LibraryDraftFilter.DRAFTS -> "Needs validation fixes"; LibraryDraftFilter.VALID -> "Structure passes" }) })
                        }
                    }
                    if (state.tagFilters.isNotEmpty()) {
                        Text("Matching all local tags", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.tagFilters.forEach { tag -> FilterChip(selected = true, onClick = { onTagFilter(tag) }, label = { Text(tag) }) }
                        }
                    }
                }
                if (activeFilterCount > 0) TextButton(onClick = onClearFilters) { Text("Clear filters") }
                if (state.preferenceFailed) Text(stringResource(R.string.preference_failed), style = MaterialTheme.typography.bodySmall)
                Text(pluralStringResource(R.plurals.project_count, collectionProjects.size, collectionProjects.size),
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
            collectionProjects.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text(if (collection != "All projects" && state.query.isBlank() && activeFilterCount == 0)
                    "No ${collection.lowercase()} on this shelf yet. Combine files or build one manually."
                    else stringResource(if (state.query.isBlank() && activeFilterCount == 0) R.string.library_empty else R.string.search_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(collectionProjects, key = { it.id }) { project ->
            val cover = if (project.sourceFormat in characterFormats) {
                val loaded by produceState<ImageBitmap?>(null, project.id, coverRefreshKey) {
                    value = null
                    value = loadCover(project.id)
                }
                loaded
            } else null
            Card {
                if (cover != null) Image(cover, contentDescription = "Artwork for ${project.title}",
                    modifier = Modifier.fillMaxWidth().height(144.dp), contentScale = ContentScale.Crop)
                else ProjectCover(project.title, project.sourceFormat)
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(project.title, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(formatLabel(project.sourceFormat), style = MaterialTheme.typography.bodyMedium)
                    Text(project.sourceFileName, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(if (project.isDraft) R.string.saved_draft else R.string.saved_local),
                        style = MaterialTheme.typography.labelLarge)
                    state.libraryMetadata[project.id]?.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            tags.forEach { tag -> FilterChip(selected = tag in state.tagFilters, onClick = { onTagFilter(tag) }, label = { Text(tag) }) }
                        }
                    }
                    OutlinedButton(onClick = { onOpen(project.id) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Open project: ${project.title}"
                    }) {
                        Text(stringResource(R.string.open_project))
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Local by default. Every workflow shares the same validation and export.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (chooseImport) AlertDialog(onDismissRequest = { chooseImport = false }, title = { Text("Combine existing files") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Start with a character, lorebook or image. Inspect each source before saving and linking it to a character.")
            Button(onClick = { chooseImport = false; onImport() }, modifier = Modifier.fillMaxWidth()) { Text("Choose character or lorebook JSON") }
            OutlinedButton(onClick = { chooseImport = false; onArtwork() }, modifier = Modifier.fillMaxWidth()) { Text("Choose PNG card or PNG/JPEG artwork") }
        } },
        confirmButton = { TextButton(onClick = { chooseImport = false }) { Text("Cancel") } })
}

private val characterFormats = setOf(SourceFormat.V1, SourceFormat.V2, SourceFormat.V3)
private val lorebookFormats = setOf(SourceFormat.LOREBOOK_V2, SourceFormat.LOREBOOK_V3, SourceFormat.WORLD_INFO)

@Composable
private fun ProjectCover(title: String, format: SourceFormat) {
    val isLorebook = format in lorebookFormats
    val monogram = title.trim().let { if (it.isEmpty()) "" else String(Character.toChars(it.codePointAt(0))).uppercase() }
    Surface(color = if (isLorebook) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {}) {
        Box(Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(16.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(monogram,
                    style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
                Text(if (isLorebook) "Lorebook" else if (format in characterFormats) "Character" else "Document",
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun LibraryFeedback(state: InspectionState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        state.recoveryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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

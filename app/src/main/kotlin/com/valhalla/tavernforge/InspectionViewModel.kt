package com.valhalla.tavernforge

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.tavernforge.core.model.ProjectSummary
import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.data.ProjectRepository
import com.valhalla.tavernforge.data.ProjectStorageError
import com.valhalla.tavernforge.data.ProjectStorageException
import com.valhalla.tavernforge.format.CharacterCardEngine
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InspectionState(
    val loading: Boolean = false,
    val summary: InspectionSummary? = null,
    val selectedProject: ProjectSummary? = null,
    val failure: ImportFailure? = null,
    val projects: List<ProjectSummary> = emptyList(),
    val libraryLoading: Boolean = true,
    val libraryFailed: Boolean = false,
    val query: String = "",
    val sort: ProjectSort = ProjectSort.RECENT,
    val operation: LibraryOperation? = null,
    val storageFailure: ProjectStorageError? = null,
    val preferenceFailed: Boolean = false,
    val notice: LibraryNotice? = null,
) {
    val busy: Boolean get() = loading || operation != null
    val hasUnsavedDraft: Boolean get() = summary?.canSaveDraft == true && selectedProject == null
    val visibleProjects: List<ProjectSummary> get() = visibleProjects(projects, query, sort)
}

enum class ImportFailure { TOO_LARGE, PERMISSION, READ }
enum class LibraryOperation { OPENING, SAVING, RENAMING, DUPLICATING, DELETING }
enum class LibraryNotice { SAVED, RENAMED, DUPLICATED, DELETED }

class InspectionViewModel(
    private val engine: CharacterCardEngine,
    private val reader: DocumentReader?,
    private val repository: ProjectRepository,
    private val preferences: LibraryPreferences,
    private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val mutableState = MutableStateFlow(InspectionState())
    val state = mutableState.asStateFlow()
    private var inspection: Job? = null
    private var libraryObservation: Job? = null
    private var selectionRevision = 0L
    // Owned privately; a URI grant or reconstructed JSON object is never the saved copy.
    private var selectedSource: SourceEnvelope? = null

    init {
        refreshLibrary()
        viewModelScope.launch {
            try {
                preferences.sort.collect { sort -> mutableState.value = mutableState.value.copy(sort = sort) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: IOException) { mutableState.value = mutableState.value.copy(preferenceFailed = true) }
        }
    }

    fun refreshLibrary() {
        libraryObservation?.cancel()
        mutableState.value = mutableState.value.copy(libraryLoading = true, libraryFailed = false)
        libraryObservation = viewModelScope.launch {
            try {
                repository.observeProjects().collect { projects ->
                    val current = mutableState.value
                    mutableState.value = current.copy(projects = projects, libraryLoading = false,
                        selectedProject = projects.firstOrNull { it.id == current.selectedProject?.id } ?: current.selectedProject)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(libraryLoading = false, libraryFailed = true)
            }
        }
    }

    fun setQuery(query: String) { mutableState.value = mutableState.value.copy(query = query.take(256)) }

    fun setSort(sort: ProjectSort) {
        mutableState.value = mutableState.value.copy(sort = sort, preferenceFailed = false)
        viewModelScope.launch {
            try { preferences.setSort(sort)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: IOException) { mutableState.value = mutableState.value.copy(preferenceFailed = true) }
        }
    }

    fun inspect(uri: Uri) = inspectSource { requireNotNull(reader).read(uri, engine.limits.maxBytes) }

    internal fun inspectSource(read: suspend () -> ImportedBytes) {
        if (mutableState.value.operation != null) return
        select(null) { read() to null }
    }

    fun openProject(id: String) {
        if (mutableState.value.operation != null) return
        select(LibraryOperation.OPENING) {
            val stored = repository.load(id)
            ImportedBytes(stored.summary.sourceFileName, stored.source.bytes()) to stored.summary
        }
    }

    private fun select(operation: LibraryOperation?, read: suspend () -> Pair<ImportedBytes, ProjectSummary?>) {
        inspection?.cancel()
        val revision = ++selectionRevision
        mutableState.value = mutableState.value.copy(loading = true, operation = operation,
            failure = null, storageFailure = null, notice = null)
        inspection = viewModelScope.launch {
            try {
                val (imported, project) = read()
                val summary = withContext(parsingDispatcher) { inspectJson(engine, imported.displayName, imported.bytes) }
                if (revision != selectionRevision) return@launch
                selectedSource = if (summary.canSaveDraft) SourceEnvelope(imported.bytes) else null
                mutableState.value = mutableState.value.copy(summary = summary, selectedProject = project,
                    loading = false, operation = null, failure = null)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: ProjectStorageException) {
                if (revision == selectionRevision) mutableState.value = mutableState.value.copy(storageFailure = failure.code)
            } catch (_: DocumentTooLargeException) { importFailed(revision, ImportFailure.TOO_LARGE)
            } catch (_: SecurityException) { importFailed(revision, ImportFailure.PERMISSION)
            } catch (_: IOException) { importFailed(revision, ImportFailure.READ)
            } catch (_: IllegalArgumentException) { importFailed(revision, ImportFailure.READ)
            } finally {
                if (revision == selectionRevision) mutableState.value = mutableState.value.copy(loading = false, operation = null)
            }
        }
    }

    private fun importFailed(revision: Long, failure: ImportFailure) {
        if (revision == selectionRevision) mutableState.value = mutableState.value.copy(failure = failure)
    }

    fun saveDraft() {
        val current = mutableState.value
        val summary = current.summary ?: return
        val source = selectedSource ?: return
        if (!current.hasUnsavedDraft) return
        libraryAction(LibraryOperation.SAVING) {
            val saved = repository.saveImported(summary.fileName, source)
            mutableState.value = mutableState.value.copy(selectedProject = saved, notice = LibraryNotice.SAVED)
        }
    }

    fun renameProject(title: String) {
        val project = mutableState.value.selectedProject ?: return
        libraryAction(LibraryOperation.RENAMING) {
            repository.rename(project.id, title)
            mutableState.value = mutableState.value.copy(selectedProject = project.copy(title = title.trim()), notice = LibraryNotice.RENAMED)
        }
    }

    fun duplicateProject() {
        val project = mutableState.value.selectedProject ?: return
        libraryAction(LibraryOperation.DUPLICATING) {
            val duplicate = repository.duplicate(project.id)
            mutableState.value = mutableState.value.copy(selectedProject = duplicate, notice = LibraryNotice.DUPLICATED)
        }
    }

    fun deleteProject() {
        val project = mutableState.value.selectedProject ?: return
        libraryAction(LibraryOperation.DELETING) {
            repository.delete(project.id)
            selectedSource = null
            ++selectionRevision
            mutableState.value = mutableState.value.copy(summary = null, selectedProject = null,
                failure = null, notice = LibraryNotice.DELETED)
        }
    }

    private fun libraryAction(operation: LibraryOperation, action: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(operation = operation, storageFailure = null, notice = null)
        viewModelScope.launch {
            try { action()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: ProjectStorageException) {
                mutableState.value = mutableState.value.copy(storageFailure = failure.code)
            } finally { mutableState.value = mutableState.value.copy(operation = null) }
        }
    }

    fun clear() {
        if (mutableState.value.operation != null) return
        inspection?.cancel()
        ++selectionRevision
        selectedSource = null
        mutableState.value = mutableState.value.copy(loading = false, summary = null, selectedProject = null,
            failure = null, storageFailure = null, notice = null)
    }
}

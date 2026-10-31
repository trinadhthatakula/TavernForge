package com.valhalla.tavernforge

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.tavernforge.core.model.Diagnostic
import com.valhalla.tavernforge.core.model.ProjectSummary
import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.StoredProject
import com.valhalla.tavernforge.data.ProjectRepository
import com.valhalla.tavernforge.data.ProjectStorageError
import com.valhalla.tavernforge.data.ProjectStorageException
import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.CharacterDocument
import com.valhalla.tavernforge.format.ExportResult
import com.valhalla.tavernforge.format.JsonPatch
import com.valhalla.tavernforge.format.JsonValueResult
import com.valhalla.tavernforge.format.ParseResult
import com.valhalla.tavernforge.format.PatchResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

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
    val documentRevision: Long = 0,
    val editor: JsonEditorState? = null,
    val exportPreview: ExportPreview? = null,
    val exportFailure: ExportFailure? = null,
) {
    val busy: Boolean get() = loading || operation != null || exportPreview != null
    val hasUnsavedDraft: Boolean get() = summary?.canSaveDraft == true && selectedProject == null
    val hasUnsavedWork: Boolean get() = hasUnsavedDraft || editor?.hasChanges == true
    val canEdit: Boolean get() = selectedProject != null && summary?.canSaveDraft == true && summary.status != InspectionStatus.UNSUPPORTED
    val canExport: Boolean get() = selectedProject != null && summary?.status == InspectionStatus.VALID && editor == null
    val visibleProjects: List<ProjectSummary> get() = visibleProjects(projects, query, sort)
}

enum class ImportFailure { TOO_LARGE, PERMISSION, READ }
enum class LibraryOperation { OPENING, SAVING, RENAMING, DUPLICATING, DELETING, EDITING, PREPARING_EXPORT, EXPORTING }
enum class LibraryNotice { SAVED, RENAMED, DUPLICATED, DELETED, EDITS_SAVED, EXPORTED }

class InspectionViewModel(
    private val engine: CharacterCardEngine,
    private val reader: DocumentReader?,
    private val repository: ProjectRepository,
    private val preferences: LibraryPreferences,
    private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val writer: DocumentWriter? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(InspectionState())
    val state = mutableState.asStateFlow()
    private var inspection: Job? = null
    private var libraryObservation: Job? = null
    private var selectionRevision = 0L
    private var selectedSource: SourceEnvelope? = null
    private var selectedDocument: CharacterDocument? = null
    private var storedProject: StoredProject? = null
    private var editorBase: CharacterDocument? = null
    private var editorDocument: CharacterDocument? = null
    private var pendingPatches: List<JsonPatch> = emptyList()
    private var nextExportId = 0L
    private var preparedExport: PreparedExport? = null

    private class PreparedExport(val id: Long, val selection: Long, val projectId: String, val revision: Long, bytes: ByteArray) {
        private val content = bytes.copyOf()
        fun bytes() = content.copyOf()
    }
    private data class Selection(val name: String, val source: SourceEnvelope, val current: SourceEnvelope = source,
        val project: ProjectSummary? = null, val revision: Long = 0)

    init {
        refreshLibrary()
        viewModelScope.launch {
            try { preferences.sort.collect { sort -> mutableState.value = mutableState.value.copy(sort = sort) }
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
            } catch (_: Exception) { mutableState.value = mutableState.value.copy(libraryLoading = false, libraryFailed = true) }
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
        if (!mayReplaceSelection()) return
        select(null) { val imported = read(); Selection(imported.displayName, SourceEnvelope(imported.bytes)) }
    }
    fun openProject(id: String) {
        if (!mayReplaceSelection()) return
        select(LibraryOperation.OPENING) {
            val stored = repository.load(id)
            Selection(stored.summary.sourceFileName, stored.source, stored.currentSource, stored.summary, stored.documentRevision)
        }
    }
    private fun mayReplaceSelection() = mutableState.value.operation == null && mutableState.value.exportPreview == null &&
        mutableState.value.editor?.hasChanges != true

    private fun select(operation: LibraryOperation?, read: suspend () -> Selection) {
        inspection?.cancel()
        val revision = ++selectionRevision
        mutableState.value = mutableState.value.copy(loading = true, operation = operation,
            failure = null, storageFailure = null, exportFailure = null, notice = null)
        inspection = viewModelScope.launch {
            try {
                val input = read()
                val (document, summary) = withContext(parsingDispatcher) {
                    val parsed = if (input.project == null) engine.parse(input.source.bytes())
                        else engine.restore(input.source, input.current, input.revision)
                    val doc = (parsed as? ParseResult.Success)?.document
                    doc to if (doc != null) summarizeDocument(engine, input.name, doc, input.current)
                        else inspectJson(engine, input.name, input.current.bytes())
                }
                if (revision != selectionRevision) return@launch
                if (input.project != null && document == null) throw ProjectStorageException(ProjectStorageError.CORRUPT_SOURCE, "Cannot restore project")
                selectedSource = if (summary.canSaveDraft) input.source else null
                selectedDocument = document
                storedProject = input.project?.let { StoredProject(it, input.source, input.current, input.revision) }
                resetEditor()
                mutableState.value = mutableState.value.copy(summary = summary, selectedProject = input.project,
                    documentRevision = input.revision, loading = false, operation = null, failure = null, editor = null)
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
            storedProject = StoredProject(saved, source)
            mutableState.value = mutableState.value.copy(selectedProject = saved, notice = LibraryNotice.SAVED)
        }
    }
    fun renameProject(title: String) {
        val project = mutableState.value.selectedProject ?: return
        if (mutableState.value.editor != null) return
        libraryAction(LibraryOperation.RENAMING) {
            repository.rename(project.id, title)
            storedProject = storedProject?.let { it.copy(summary = it.summary.copy(title = title.trim())) }
            mutableState.value = mutableState.value.copy(selectedProject = project.copy(title = title.trim()), notice = LibraryNotice.RENAMED)
        }
    }
    fun duplicateProject() {
        val project = mutableState.value.selectedProject ?: return
        if (mutableState.value.editor != null) return
        libraryAction(LibraryOperation.DUPLICATING) {
            val duplicate = repository.duplicate(project.id)
            storedProject = storedProject?.copy(summary = duplicate)
            mutableState.value = mutableState.value.copy(selectedProject = duplicate, notice = LibraryNotice.DUPLICATED)
        }
    }
    fun deleteProject() {
        val project = mutableState.value.selectedProject ?: return
        if (mutableState.value.editor != null) return
        libraryAction(LibraryOperation.DELETING) {
            repository.delete(project.id)
            selectedSource = null; selectedDocument = null; storedProject = null
            ++selectionRevision
            mutableState.value = mutableState.value.copy(summary = null, selectedProject = null,
                failure = null, notice = LibraryNotice.DELETED, documentRevision = 0)
        }
    }

    fun beginEditing(): Boolean {
        val current = mutableState.value
        val document = selectedDocument ?: return false
        if (current.busy || !current.canEdit) return false
        if (current.editor != null) return true
        editorBase = document; editorDocument = document; pendingPatches = emptyList()
        mutableState.value = current.copy(editor = JsonEditorState(), operation = LibraryOperation.EDITING,
            notice = null, storageFailure = null, exportFailure = null)
        viewModelScope.launch {
            try {
                val editor = withContext(parsingDispatcher) { editorState(document) }
                mutableState.value = mutableState.value.copy(editor = editor)
            } finally { mutableState.value = mutableState.value.copy(operation = null) }
        }
        return true
    }
    private fun editorState(document: CharacterDocument): JsonEditorState {
        val (fields, limited) = editorFields(document)
        return JsonEditorState(fields = fields, pendingPaths = pendingPatches.map { it.path },
            issues = engine.validate(document).issues, namedEntriesLimited = limited)
    }
    fun editField(path: String) {
        val editor = mutableState.value.editor ?: return
        val document = editorDocument ?: return
        if (mutableState.value.busy || editor.input != null) return
        mutableState.value = mutableState.value.copy(operation = LibraryOperation.EDITING)
        viewModelScope.launch {
            try {
                val input = withContext(parsingDispatcher) {
                    val value = valueAt(document.raw, path)
                    val isArray = path.endsWith("/group_only_greetings")
                    val mode = if (value is JsonPrimitive && value.isString || value == null && !isArray) EditValueMode.TEXT else EditValueMode.JSON
                    val text = if (mode == EditValueMode.TEXT) (value as? JsonPrimitive)?.content.orEmpty()
                        else value?.toString() ?: "[]"
                    if (text.length > MAX_EDITOR_CHARS) null else EditInput(path, text, mode)
                }
                mutableState.value = mutableState.value.copy(editor = if (input != null) editor.copy(input = input, editIssues = emptyList())
                    else editor.copy(editIssues = listOf(Diagnostic("EDITOR_LIMIT", "This value is too large for the text editor; it has not been changed."))))
            } finally { mutableState.value = mutableState.value.copy(operation = null) }
        }
    }
    fun editAdvanced() {
        val editor = mutableState.value.editor ?: return
        if (mutableState.value.busy || editor.input != null) return
        mutableState.value = mutableState.value.copy(editor = editor.copy(input = EditInput("", "", EditValueMode.JSON, pathEditable = true), editIssues = emptyList()))
    }
    fun setEditText(text: String) = changeInput { input ->
        if (text.length > MAX_EDITOR_CHARS) input.copy(limitExceeded = true) else input.copy(text = text, limitExceeded = false)
    }
    fun setEditPath(path: String) = changeInput { input ->
        if (!input.pathEditable) input else if (path.length > MAX_EDITOR_PATH_CHARS) input.copy(limitExceeded = true)
        else input.copy(path = path, limitExceeded = false)
    }
    fun setEditAction(action: EditAction) = changeInput { it.copy(action = action) }
    private fun changeInput(change: (EditInput) -> EditInput) {
        val editor = mutableState.value.editor ?: return
        val input = editor.input ?: return
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(editor = editor.copy(input = change(input), editIssues = emptyList()))
    }
    fun cancelEditInput() {
        val editor = mutableState.value.editor ?: return
        if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(editor = editor.copy(input = null, editIssues = emptyList()))
    }
    fun applyEditInput() {
        val editor = mutableState.value.editor ?: return
        val input = editor.input ?: return
        val base = editorBase ?: return
        if (mutableState.value.busy || input.limitExceeded) return
        mutableState.value = mutableState.value.copy(operation = LibraryOperation.EDITING)
        viewModelScope.launch {
            try {
                val result = withContext(parsingDispatcher) {
                    if (input.path.isEmpty() || !input.path.startsWith('/')) return@withContext PatchResult.Failure(listOf(
                        Diagnostic("EDITOR_PATH", "Choose a JSON member path beginning with /. Whole-document replacement is not offered.")))
                    val patch = if (input.action == EditAction.REMOVE) JsonPatch.Remove(input.path) else {
                        val value = if (input.mode == EditValueMode.TEXT) JsonPrimitive(input.text) else {
                            when (val parsed = engine.parseValue(input.text.encodeToByteArray())) {
                                is JsonValueResult.Success -> parsed.value
                                is JsonValueResult.Failure -> return@withContext PatchResult.Failure(parsed.issues)
                            }
                        }
                        // RFC 6902 Add inserts into arrays. The UI's Set action instead
                        // replaces an existing value, including explicit JSON null.
                        if (valueAt(requireNotNull(editorDocument).raw, input.path) != null)
                            JsonPatch.Replace(input.path, value) else JsonPatch.Add(input.path, value)
                    }
                    val proposed = pendingPatches + patch
                    when (val patched = engine.patch(base, base.revision, proposed)) {
                        is PatchResult.Success -> { pendingPatches = proposed; patched }
                        is PatchResult.Failure -> patched
                    }
                }
                when (result) {
                    is PatchResult.Failure -> mutableState.value = mutableState.value.copy(editor = editor.copy(editIssues = result.issues))
                    is PatchResult.Success -> {
                        editorDocument = result.document
                        val preview = withContext(parsingDispatcher) { editorState(result.document) }
                        mutableState.value = mutableState.value.copy(editor = preview)
                    }
                }
            } finally { mutableState.value = mutableState.value.copy(operation = null) }
        }
    }
    fun saveEdits() {
        val editor = mutableState.value.editor ?: return
        val stored = storedProject ?: return
        if (editor.hasUnappliedInput || pendingPatches.isEmpty()) return
        val patches = pendingPatches.toList()
        libraryAction(LibraryOperation.SAVING) {
            val saved = repository.applyEdits(stored.summary.id, stored.documentRevision, patches)
            val (document, summary) = withContext(parsingDispatcher) {
                val doc = (engine.restore(saved.source, saved.currentSource, saved.documentRevision) as? ParseResult.Success)?.document
                    ?: throw ProjectStorageException(ProjectStorageError.CORRUPT_SOURCE, "Cannot restore saved edit")
                doc to summarizeDocument(engine, saved.summary.sourceFileName, doc, saved.currentSource)
            }
            storedProject = saved; selectedSource = saved.source; selectedDocument = document
            resetEditor()
            mutableState.value = mutableState.value.copy(editor = null, summary = summary, selectedProject = saved.summary,
                documentRevision = saved.documentRevision, notice = LibraryNotice.EDITS_SAVED)
        }
    }
    fun discardEdits() {
        if (mutableState.value.busy) return
        resetEditor()
        mutableState.value = mutableState.value.copy(editor = null, storageFailure = null)
    }
    private fun resetEditor() { editorBase = null; editorDocument = null; pendingPatches = emptyList() }

    fun prepareExport() {
        val current = mutableState.value
        val stored = storedProject ?: return
        if (current.busy || !current.canExport) return
        libraryAction(LibraryOperation.PREPARING_EXPORT) {
            val fresh = repository.load(stored.summary.id)
            if (fresh.documentRevision != stored.documentRevision || fresh.currentSource.sha256 != stored.currentSource.sha256)
                throw ProjectStorageException(ProjectStorageError.REVISION_CONFLICT, "Project changed before export")
            val result = withContext(parsingDispatcher) {
                val document = (engine.restore(fresh.source, fresh.currentSource, fresh.documentRevision) as? ParseResult.Success)?.document
                if (document == null) null else engine.export(document)
            }
            when (result) {
                is ExportResult.Success -> {
                    val bytes = result.bytes
                    val id = ++nextExportId
                    preparedExport = PreparedExport(id, selectionRevision, stored.summary.id, stored.documentRevision, bytes)
                    mutableState.value = mutableState.value.copy(exportPreview = ExportPreview(id, exportFileName(stored.summary.sourceFileName),
                        stored.summary.sourceFormat, stored.documentRevision, bytes.size, stored.documentRevision > 0))
                }
                else -> mutableState.value = mutableState.value.copy(exportFailure = ExportFailure.VALIDATION)
            }
        }
    }
    fun confirmExport(): ExportPreview? {
        val preview = mutableState.value.exportPreview ?: return null
        if (preview.pickerLaunched || mutableState.value.operation != null) return null
        val launched = preview.copy(pickerLaunched = true)
        mutableState.value = mutableState.value.copy(exportPreview = launched)
        return launched
    }
    fun cancelExportPreview() {
        if (mutableState.value.exportPreview?.pickerLaunched == true) return
        preparedExport = null
        mutableState.value = mutableState.value.copy(exportPreview = null)
    }
    fun exportLauncherFailed() {
        preparedExport = null
        mutableState.value = mutableState.value.copy(exportPreview = null, exportFailure = ExportFailure.UNAVAILABLE)
    }
    fun completeExport(uri: Uri?) = completeExportTo(uri != null) { bytes -> requireNotNull(writer).write(requireNotNull(uri), bytes) }
    internal fun completeExportTo(accepted: Boolean, write: suspend (ByteArray) -> Unit) {
        val preview = mutableState.value.exportPreview
        val prepared = preparedExport
        if (preview?.pickerLaunched != true || prepared == null || prepared.id != preview.id || mutableState.value.operation != null) {
            if (accepted && prepared == null) mutableState.value = mutableState.value.copy(exportFailure = ExportFailure.UNAVAILABLE, notice = null)
            return
        }
        if (!accepted) { preparedExport = null; mutableState.value = mutableState.value.copy(exportPreview = null); return }
        if (prepared.selection != selectionRevision || prepared.projectId != storedProject?.summary?.id || prepared.revision != storedProject?.documentRevision) {
            exportLauncherFailed(); return
        }
        mutableState.value = mutableState.value.copy(operation = LibraryOperation.EXPORTING, exportFailure = null, notice = null)
        viewModelScope.launch {
            try {
                write(prepared.bytes())
                mutableState.value = mutableState.value.copy(notice = LibraryNotice.EXPORTED)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.value = mutableState.value.copy(exportFailure = ExportFailure.WRITE)
            } finally {
                preparedExport = null
                mutableState.value = mutableState.value.copy(operation = null, exportPreview = null)
            }
        }
    }

    private fun libraryAction(operation: LibraryOperation, action: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(operation = operation, storageFailure = null, exportFailure = null, notice = null)
        viewModelScope.launch {
            try { action()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: ProjectStorageException) { mutableState.value = mutableState.value.copy(storageFailure = failure.code)
            } finally { mutableState.value = mutableState.value.copy(operation = null) }
        }
    }
    fun clear() {
        if (!mayReplaceSelection()) return
        inspection?.cancel(); ++selectionRevision
        selectedSource = null; selectedDocument = null; storedProject = null; resetEditor()
        mutableState.value = mutableState.value.copy(loading = false, summary = null, selectedProject = null,
            failure = null, storageFailure = null, exportFailure = null, notice = null, editor = null, documentRevision = 0)
    }
}

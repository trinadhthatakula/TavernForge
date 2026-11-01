package com.valhalla.tavernforge

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.tavernforge.core.model.Diagnostic
import com.valhalla.tavernforge.core.model.ProjectSummary
import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.StoredProject
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.core.model.LibraryQuery
import com.valhalla.tavernforge.core.model.LibrarySort
import com.valhalla.tavernforge.core.model.LibraryDraftFilter
import com.valhalla.tavernforge.core.model.ProjectMetadata
import com.valhalla.tavernforge.data.ProjectRepository
import com.valhalla.tavernforge.data.ProjectStorageError
import com.valhalla.tavernforge.data.ProjectStorageException
import com.valhalla.tavernforge.data.drafts.EditJournal
import com.valhalla.tavernforge.data.drafts.EditRecovery
import com.valhalla.tavernforge.data.drafts.RecoveryInput
import com.valhalla.tavernforge.data.drafts.EditJournalException
import com.valhalla.tavernforge.data.drafts.EditJournalError
import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.CharacterDocument
import com.valhalla.tavernforge.format.ExportResult
import com.valhalla.tavernforge.format.JsonPatch
import com.valhalla.tavernforge.format.JsonValueResult
import com.valhalla.tavernforge.format.ParseResult
import com.valhalla.tavernforge.format.PatchResult
import com.valhalla.tavernforge.format.EntryLocator
import com.valhalla.tavernforge.format.LoreCommand
import com.valhalla.tavernforge.format.LoreEditPlanResult
import com.valhalla.tavernforge.format.createTemplate
import com.valhalla.tavernforge.format.planLoreEdit
import com.valhalla.tavernforge.format.lorebookPath
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull

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
    val manualCreated: Boolean = false,
    val filteredProjects: List<ProjectSummary>? = null,
    val libraryMetadata: Map<String, ProjectMetadata> = emptyMap(),
    val formatFilters: Set<SourceFormat> = emptySet(),
    val draftFilter: LibraryDraftFilter = LibraryDraftFilter.ALL,
    val tagFilters: Set<String> = emptySet(),
    val tagEditor: LocalTagEditor? = null,
    val recoveryChecking: Boolean = false,
    val recoveryAvailable: Boolean = false,
    val recoveryCanRestore: Boolean = false,
    val recoveryError: String? = null,
    val recoveryOpened: Boolean = false,
) {
    val busy: Boolean get() = loading || operation != null || exportPreview != null || recoveryChecking
    val hasUnsavedDraft: Boolean get() = summary?.canSaveDraft == true && selectedProject == null
    val hasUnsavedWork: Boolean get() = hasUnsavedDraft || editor?.hasChanges == true
    val canEdit: Boolean get() = selectedProject != null && summary?.canSaveDraft == true && summary.status != InspectionStatus.UNSUPPORTED
    val canExport: Boolean get() = selectedProject != null && summary?.status == InspectionStatus.VALID && editor == null
    val visibleProjects: List<ProjectSummary> get() = filteredProjects ?: visibleProjects(projects, query, sort)
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
    private val journal: EditJournal? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(InspectionState(recoveryChecking = journal != null))
    val state = mutableState.asStateFlow()
    private var inspection: Job? = null
    private var libraryObservation: Job? = null
    private var filteredLibraryObservation: Job? = null
    private var selectionRevision = 0L
    private var selectedSource: SourceEnvelope? = null
    private var selectedDocument: CharacterDocument? = null
    private var storedProject: StoredProject? = null
    private var editorBase: CharacterDocument? = null
    private var editorDocument: CharacterDocument? = null
    private var pendingPatches: List<JsonPatch> = emptyList()
    private var editHistory: EditHistory? = null
    private var authoringMode = AuthoringMode.BASIC
    private var selectedEntry: EntryLocator? = null
    private var nextExportId = 0L
    private var preparedExport: PreparedExport? = null
    private var pendingRecovery: EditRecovery? = null
    private var journalSession: Long? = null
    private var journalWrite: Job? = null
    private var journalClear: Job? = null

    private class PreparedExport(val id: Long, val selection: Long, val projectId: String, val revision: Long, bytes: ByteArray) {
        private val content = bytes.copyOf()
        fun bytes() = content.copyOf()
    }
    private data class Selection(val name: String, val source: SourceEnvelope, val current: SourceEnvelope = source,
        val project: ProjectSummary? = null, val revision: Long = 0)

    init {
        refreshLibrary()
        if (journal != null) viewModelScope.launch {
            try {
                val recovery = journal.load()
                if (recovery != null && !recovery.hasAppliedChanges && recovery.input == null) journal.clear()
                else pendingRecovery = recovery
                mutableState.value = mutableState.value.copy(recoveryAvailable = pendingRecovery != null, recoveryCanRestore = pendingRecovery != null)
            } catch (_: EditJournalException) {
                mutableState.value = mutableState.value.copy(recoveryAvailable = true, recoveryCanRestore = false,
                    recoveryError = "The recovery file is unavailable or damaged. Saved library projects remain intact; discard this recovery file to continue.")
            } finally { mutableState.value = mutableState.value.copy(recoveryChecking = false) }
        }
        viewModelScope.launch {
            try { preferences.sort.collect { sort -> mutableState.value = mutableState.value.copy(sort = sort); observeFilteredLibrary() }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: IOException) { mutableState.value = mutableState.value.copy(preferenceFailed = true) }
        }
    }

    fun refreshLibrary() {
        libraryObservation?.cancel()
        mutableState.value = mutableState.value.copy(libraryLoading = true, libraryFailed = false)
        libraryObservation = viewModelScope.launch {
            try {
                repository.observeLibrary().collect { entries ->
                    val projects = entries.map { it.summary }
                    val current = mutableState.value
                    mutableState.value = current.copy(projects = projects, libraryLoading = false,
                        libraryMetadata = entries.associate { it.summary.id to it.metadata },
                        selectedProject = projects.firstOrNull { it.id == current.selectedProject?.id } ?: current.selectedProject)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.value = mutableState.value.copy(libraryLoading = false, libraryFailed = true) }
        }
        observeFilteredLibrary()
    }

    fun setQuery(query: String) { mutableState.value = mutableState.value.copy(query = query.take(256)); observeFilteredLibrary() }
    fun toggleFormatFilter(format: SourceFormat) {
        val current = mutableState.value.formatFilters
        mutableState.value = mutableState.value.copy(formatFilters = if (format in current) current - format else current + format)
        observeFilteredLibrary()
    }
    fun setDraftFilter(filter: LibraryDraftFilter) { mutableState.value = mutableState.value.copy(draftFilter = filter); observeFilteredLibrary() }
    fun toggleTagFilter(tag: String) {
        val tags = mutableState.value.tagFilters
        if (tag !in tags && tags.size >= 32) return
        mutableState.value = mutableState.value.copy(tagFilters = if (tag in tags) tags - tag else tags + tag)
        observeFilteredLibrary()
    }
    fun clearLibraryFilters() {
        mutableState.value = mutableState.value.copy(formatFilters = emptySet(), draftFilter = LibraryDraftFilter.ALL, tagFilters = emptySet())
        observeFilteredLibrary()
    }
    private fun observeFilteredLibrary() {
        filteredLibraryObservation?.cancel()
        val current = mutableState.value
        val query = LibraryQuery(current.query, current.formatFilters, current.draftFilter, current.tagFilters,
            when (current.sort) { ProjectSort.RECENT -> LibrarySort.RECENT; ProjectSort.TITLE -> LibrarySort.TITLE; ProjectSort.OLDEST -> LibrarySort.OLDEST })
        filteredLibraryObservation = viewModelScope.launch {
            try { repository.observeLibrary(query).collect { entries -> mutableState.value = mutableState.value.copy(filteredProjects = entries.map { it.summary }) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { mutableState.value = mutableState.value.copy(libraryFailed = true) }
        }
    }
    fun setSort(sort: ProjectSort) {
        mutableState.value = mutableState.value.copy(sort = sort, preferenceFailed = false)
        observeFilteredLibrary()
        viewModelScope.launch {
            try { preferences.setSort(sort)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: IOException) { mutableState.value = mutableState.value.copy(preferenceFailed = true) }
        }
    }

    fun editLocalTags() {
        val project = mutableState.value.selectedProject ?: return
        if (mutableState.value.editor != null) return
        libraryAction(LibraryOperation.EDITING) {
            val metadata = repository.projectMetadata(project.id)
            mutableState.value = mutableState.value.copy(tagEditor = LocalTagEditor(project.id, project.title, metadata.revision, metadata.tags))
        }
    }
    fun updateLocalTag(index: Int, text: String) {
        val editor = mutableState.value.tagEditor ?: return
        if (mutableState.value.busy || index !in editor.tags.indices || text.length > 64 || text.any(Char::isISOControl)) return
        mutableState.value = mutableState.value.copy(tagEditor = editor.copy(tags = editor.tags.toMutableList().also { it[index] = text }, error = null))
    }
    fun addLocalTag() {
        val editor = mutableState.value.tagEditor ?: return
        if (!mutableState.value.busy && editor.tags.size < 32) mutableState.value = mutableState.value.copy(tagEditor = editor.copy(tags = editor.tags + "", error = null))
    }
    fun removeLocalTag(index: Int) {
        val editor = mutableState.value.tagEditor ?: return
        if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(tagEditor = editor.copy(tags = editor.tags.filterIndexed { i, _ -> i != index }, error = null))
    }
    fun dismissLocalTags() { if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(tagEditor = null) }
    fun saveLocalTags() {
        val editor = mutableState.value.tagEditor ?: return
        val tags = editor.tags.map(String::trim)
        if (tags.any(String::isEmpty) || tags.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size != tags.size) {
            mutableState.value = mutableState.value.copy(tagEditor = editor.copy(error = "Use distinct nonempty tags; comparison ignores letter case.")); return
        }
        libraryAction(LibraryOperation.EDITING) {
            repository.setProjectTags(editor.projectId, editor.revision, tags)
            mutableState.value = mutableState.value.copy(tagEditor = null)
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
    fun createManual(format: SourceFormat, title: String) {
        if (!mayReplaceSelection() || mutableState.value.busy || title.isBlank() || title.length > 100) return
        libraryAction(LibraryOperation.SAVING) {
            val template = withContext(parsingDispatcher) { engine.createTemplate(format) }
            if (template !is ParseResult.Success) throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, "The template could not be created.")
            val fileName = title.trim().map { if (it in "/\\\u0000") '_' else it }.joinToString("") + ".json"
            val summary = repository.saveImported(fileName, template.document.source)
            val saved = repository.load(summary.id)
            storedProject = saved; selectedSource = saved.source; selectedDocument = template.document
            ++selectionRevision; resetEditor()
            val inspectionSummary = withContext(parsingDispatcher) { summarizeDocument(engine, fileName, template.document, saved.currentSource) }
            mutableState.value = mutableState.value.copy(selectedProject = saved.summary,
                summary = inspectionSummary,
                documentRevision = saved.documentRevision, editor = null, failure = null, notice = LibraryNotice.SAVED, manualCreated = true)
        }
    }
    fun consumeManualCreated() { mutableState.value = mutableState.value.copy(manualCreated = false) }
    private fun mayReplaceSelection() = mutableState.value.operation == null && mutableState.value.exportPreview == null &&
        mutableState.value.editor?.hasChanges != true && !mutableState.value.recoveryChecking && !mutableState.value.recoveryAvailable

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
        if (current.busy || current.recoveryAvailable || !current.canEdit) return false
        if (current.editor != null) return true
        editorBase = document; editorDocument = document; pendingPatches = emptyList(); editHistory = EditHistory(engine, document)
        authoringMode = AuthoringMode.BASIC; selectedEntry = null
        mutableState.value = current.copy(editor = JsonEditorState(), operation = LibraryOperation.EDITING,
            notice = null, storageFailure = null, exportFailure = null)
        viewModelScope.launch {
            try {
                journalClear?.join()
                journalSession = journal?.startSession()
                val editor = withContext(parsingDispatcher) { editorState(document) }
                mutableState.value = mutableState.value.copy(editor = editor)
            } catch (_: EditJournalException) {
                mutableState.value = mutableState.value.copy(recoveryError = "Edit recovery is unavailable. Save changes explicitly to keep them.")
            } finally { mutableState.value = mutableState.value.copy(operation = null) }
        }
        return true
    }
    private fun editorState(document: CharacterDocument): JsonEditorState {
        val path = document.lorebookPath()
        val book = path?.let { valueAt(document.raw, it) }
        val entries = loreEntries(document)
        if (entries.none { it.locator == selectedEntry }) selectedEntry = null
        return JsonEditorState(fields = authoringFields(document, authoringMode, selectedEntry), pendingPaths = pendingPatches.map { it.path },
            issues = engine.validate(document).issues, mode = authoringMode, entries = entries, selectedEntry = selectedEntry,
            hasBook = book is JsonObject, canAddBook = path != null && book == null && document.sourceFormat in setOf(SourceFormat.V2, SourceFormat.V3),
            nativeBook = document.sourceFormat == SourceFormat.WORLD_INFO, canUndo = editHistory?.canUndo == true,
            canRedo = editHistory?.canRedo == true, guidance = document.sourceFormat in setOf(SourceFormat.V1, SourceFormat.V2, SourceFormat.V3))
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
                    val field = authoringFields(document, AuthoringMode.ADVANCED, selectedEntry).firstOrNull { it.path == path }
                    val control = field?.control ?: if (path.endsWith("/group_only_greetings")) FieldControl.STRINGS else FieldControl.TEXT
                    val mode = if (value is JsonPrimitive && value.isString || value == null && control == FieldControl.TEXT) EditValueMode.TEXT else EditValueMode.JSON
                    val text = if (mode == EditValueMode.TEXT) (value as? JsonPrimitive)?.content.orEmpty()
                        else value?.toString() ?: when (control) { FieldControl.STRINGS -> "[]"; FieldControl.BOOLEAN -> "false"; FieldControl.NUMBER -> "0"; else -> "{}" }
                    val items = if (control == FieldControl.STRINGS && (value == null || value is JsonArray && value.all { it is JsonPrimitive && it.isString }))
                        (value as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList() else null
                    if (text.length > MAX_EDITOR_CHARS) null else EditInput(path, text, mode, field = field, stringItems = items)
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
        if (text.length > MAX_EDITOR_CHARS) input.copy(limitExceeded = true) else input.copy(text = text, stringItems = null, limitExceeded = false)
    }
    fun setTypedValue(text: String, asText: Boolean = false) = changeInput { input ->
        input.copy(text = text, mode = if (asText) EditValueMode.TEXT else EditValueMode.JSON,
            action = EditAction.SET, stringItems = null, limitExceeded = false)
    }
    fun setStringItem(index: Int, text: String) = updateStringItems { items ->
        if (index !in items.indices) items else items.toMutableList().also { it[index] = text }
    }
    fun addStringItem() = updateStringItems { if (it.size >= engine.limits.maxCollectionEntries) it else it + "" }
    fun removeStringItem(index: Int) = updateStringItems { items -> items.filterIndexed { i, _ -> i != index } }
    private fun updateStringItems(change: (List<String>) -> List<String>) = changeInput { input ->
        val items = input.stringItems ?: return@changeInput input
        val updated = change(items)
        if (updated.sumOf { it.length.toLong() } > MAX_EDITOR_CHARS) return@changeInput input.copy(limitExceeded = true)
        val json = JsonArray(updated.map(::JsonPrimitive)).toString()
        if (json.length > MAX_EDITOR_CHARS) input.copy(limitExceeded = true)
        else input.copy(text = json, stringItems = updated, mode = EditValueMode.JSON, limitExceeded = false)
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
        scheduleRecovery()
    }
    fun cancelEditInput() {
        val editor = mutableState.value.editor ?: return
        if (!mutableState.value.busy) {
            mutableState.value = mutableState.value.copy(editor = editor.copy(input = null, editIssues = emptyList()))
            scheduleRecovery()
        }
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
                    requireNotNull(editHistory).append(listOf(patch)).also {
                        if (it is PatchResult.Success) pendingPatches = requireNotNull(editHistory).patches
                    }
                }
                when (result) {
                    is PatchResult.Failure -> mutableState.value = mutableState.value.copy(editor = editor.copy(editIssues = result.issues))
                    is PatchResult.Success -> {
                        editorDocument = result.document
                        val preview = withContext(parsingDispatcher) { editorState(result.document) }
                        mutableState.value = mutableState.value.copy(editor = preview)
                        scheduleRecovery()
                    }
                }
            } finally { mutableState.value = mutableState.value.copy(operation = null) }
        }
    }
    fun setAuthoringMode(mode: AuthoringMode) = updateEditorView { authoringMode = mode }
    fun selectLoreEntry(locator: EntryLocator?) = updateEditorView { selectedEntry = locator }
    private fun updateEditorView(change: () -> Unit) {
        val editor = mutableState.value.editor ?: return
        val document = editorDocument ?: return
        if (mutableState.value.busy || editor.input != null) return
        mutableState.value = mutableState.value.copy(operation = LibraryOperation.EDITING)
        viewModelScope.launch {
            try { change(); mutableState.value = mutableState.value.copy(editor = withContext(parsingDispatcher) { editorState(document) })
            } finally { mutableState.value = mutableState.value.copy(operation = null) }
        }
    }
    fun applyLoreCommand(command: LoreCommand) = editorCommand {
        when (val plan = engine.planLoreEdit(requireNotNull(editorDocument), command)) {
            is LoreEditPlanResult.Failure -> PatchResult.Failure(plan.issues)
            is LoreEditPlanResult.Success -> requireNotNull(editHistory).append(plan.patches).also { result ->
                if (result is PatchResult.Success) selectedEntry = plan.selection
            }
        }
    }
    fun undoEdit() = editorCommand { requireNotNull(editHistory).undo().also { if (it is PatchResult.Success) selectedEntry = null } }
    fun redoEdit() = editorCommand { requireNotNull(editHistory).redo().also { if (it is PatchResult.Success) selectedEntry = null } }
    private fun editorCommand(action: () -> PatchResult) {
        val editor = mutableState.value.editor ?: return
        if (mutableState.value.busy || editor.input != null) return
        mutableState.value = mutableState.value.copy(operation = LibraryOperation.EDITING)
        viewModelScope.launch {
            try {
                when (val result = withContext(parsingDispatcher) { action() }) {
                    is PatchResult.Failure -> mutableState.value = mutableState.value.copy(editor = editor.copy(editIssues = result.issues))
                    is PatchResult.Success -> {
                        pendingPatches = requireNotNull(editHistory).patches; editorDocument = result.document
                        mutableState.value = mutableState.value.copy(editor = withContext(parsingDispatcher) { editorState(result.document) })
                        scheduleRecovery()
                    }
                }
            } finally { mutableState.value = mutableState.value.copy(operation = null) }
        }
    }
    /** Guidance becomes ordinary explicit text input, then the user reviews and applies it. */
    fun prepareGuidance(destination: String, text: String) {
        val document = editorDocument ?: return
        if (text.isBlank() || text.length > MAX_EDITOR_CHARS || mutableState.value.busy || mutableState.value.editor?.input != null) return
        val prefix = if (document.sourceFormat == SourceFormat.V1) "" else "/data"
        if (destination !in setOf("description", "personality") || document.sourceFormat !in setOf(SourceFormat.V1, SourceFormat.V2, SourceFormat.V3)) return
        val path = "$prefix/$destination"
        val previous = valueAt(document.raw, path)
        if (previous != null && (previous !is JsonPrimitive || !previous.isString)) return
        val before = (previous as? JsonPrimitive)?.content.orEmpty()
        val proposed = if (before.isEmpty()) text else "$before\n\n$text"
        if (proposed.length > MAX_EDITOR_CHARS) return
        val editor = mutableState.value.editor ?: return
        mutableState.value = mutableState.value.copy(editor = editor.copy(input = EditInput(path, proposed, EditValueMode.TEXT, initialText = before)))
        scheduleRecovery()
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
    private fun resetEditor() {
        val hadJournalSession = journalSession != null || journalWrite != null
        journalWrite?.cancel(); journalWrite = null; journalSession = null
        if (hadJournalSession) clearJournalAfterPrevious()
        editorBase = null; editorDocument = null; pendingPatches = emptyList(); editHistory = null; selectedEntry = null
    }

    private fun clearJournalAfterPrevious() {
        val journal = journal ?: return
        val previous = journalClear
        journalClear = viewModelScope.launch {
            previous?.join()
            try { journal.clear()
            } catch (_: EditJournalException) {
                mutableState.value = mutableState.value.copy(recoveryError = "The recovery file could not be cleared. Saved library content remains intact.")
            }
        }
    }

    private fun scheduleRecovery() {
        val journal = journal ?: return
        val session = journalSession ?: return
        val document = editorDocument ?: return
        val stored = storedProject ?: return
        val editor = mutableState.value.editor ?: return
        val changed = pendingPatches.isNotEmpty()
        val input = editor.input?.takeIf { it.changed }?.let { RecoveryInput(it.path, it.text, it.mode.name, it.action.name, it.pathEditable) }
        journalWrite?.cancel()
        journalWrite = viewModelScope.launch {
            delay(250)
            try {
                val source = if (!changed) stored.currentSource else withContext(parsingDispatcher) {
                    val snapshot = engine.snapshot(document) as? ExportResult.Success
                        ?: throw EditJournalException(EditJournalError.INVALID_DRAFT)
                    SourceEnvelope(snapshot.bytes)
                }
                journal.save(session, EditRecovery(stored.summary.id, stored.documentRevision, stored.source.sha256,
                    stored.currentSource.sha256, source, changed, input))
            } catch (_: EditJournalException) {
                mutableState.value = mutableState.value.copy(recoveryError = "The latest edits could not be written to recovery. Save changes explicitly to keep them.")
            }
        }
    }

    fun discardRecovery() {
        val journal = journal ?: return
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(operation = LibraryOperation.EDITING)
        viewModelScope.launch {
            try {
                journalWrite?.cancel(); journalWrite = null; journalSession = null
                journalClear?.join(); journal.clear(); pendingRecovery = null
                mutableState.value = mutableState.value.copy(recoveryAvailable = false, recoveryCanRestore = false, recoveryError = null)
            } catch (_: EditJournalException) {
                mutableState.value = mutableState.value.copy(recoveryError = "The recovery file could not be discarded. Retry before opening another project.")
            } finally { mutableState.value = mutableState.value.copy(operation = null) }
        }
    }

    fun recoverEdits() {
        val recovery = pendingRecovery ?: return
        val journal = journal ?: return
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(operation = LibraryOperation.OPENING, recoveryError = null)
        viewModelScope.launch {
            try {
                val saved = repository.load(recovery.projectId)
                val restored = withContext(parsingDispatcher) {
                    val base = (engine.restore(saved.source, saved.currentSource, saved.documentRevision) as? ParseResult.Success)?.document
                        ?: throw EditJournalException(EditJournalError.INVALID_DRAFT)
                    val patches = recovery.patchesFor(saved, engine)
                    val history = EditHistory(engine, base)
                    val preview = if (patches.isEmpty()) base else (history.append(patches) as? PatchResult.Success)?.document
                        ?: throw EditJournalException(EditJournalError.INVALID_DRAFT)
                    Triple(base, preview, history)
                }
                journalClear?.join(); journalSession = journal.startSession()
                storedProject = saved; selectedSource = saved.source; selectedDocument = restored.first
                editorBase = restored.first; editorDocument = restored.second; editHistory = restored.third; pendingPatches = restored.third.patches
                authoringMode = AuthoringMode.ADVANCED
                selectedEntry = recovery.input?.path?.let { inputPath -> loreEntries(restored.second).firstOrNull {
                    inputPath == it.path || inputPath.startsWith("${it.path}/")
                }?.locator }
                ++selectionRevision
                val editor = withContext(parsingDispatcher) { editorState(restored.second) }
                val restoredInput = withContext(parsingDispatcher) { recovery.input?.let {
                    val mode = EditValueMode.valueOf(it.mode)
                    val previous = valueAt(restored.second.raw, it.path)
                    val initialText = when {
                        previous == null -> "(absent)"
                        mode == EditValueMode.TEXT && previous is JsonPrimitive && previous.isString -> previous.content
                        else -> previous.toString()
                    }
                    val field = authoringFields(restored.second, AuthoringMode.ADVANCED, selectedEntry).firstOrNull { field -> field.path == it.path }
                    EditInput(it.path, it.text, mode, initialText = initialText, field = field,
                        action = EditAction.valueOf(it.action), pathEditable = it.pathEditable, recovered = true)
                } }
                val summary = withContext(parsingDispatcher) { summarizeDocument(engine, saved.summary.sourceFileName, restored.first, saved.currentSource) }
                pendingRecovery = null
                mutableState.value = mutableState.value.copy(selectedProject = saved.summary, summary = summary, documentRevision = saved.documentRevision,
                    editor = editor.copy(input = restoredInput), recoveryAvailable = false, recoveryCanRestore = false, recoveryOpened = true)
                scheduleRecovery()
            } catch (_: EditJournalException) { mutableState.value = mutableState.value.copy(recoveryError = "This recovery no longer matches the saved project's original and revision, or is invalid. It was not applied; discard it to reopen the saved project.")
            } catch (_: ProjectStorageException) { mutableState.value = mutableState.value.copy(recoveryError = "The project needed by this recovery is unavailable. No saved project was overwritten.")
            } finally { mutableState.value = mutableState.value.copy(operation = null) }
        }
    }
    fun consumeRecoveryOpened() { mutableState.value = mutableState.value.copy(recoveryOpened = false) }

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

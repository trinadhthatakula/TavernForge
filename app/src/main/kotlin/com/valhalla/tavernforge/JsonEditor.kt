package com.valhalla.tavernforge

import com.valhalla.tavernforge.core.model.Diagnostic
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.format.CharacterDocument
import com.valhalla.tavernforge.format.EntryLocator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val MAX_EDITOR_CHARS = 1024 * 1024
internal const val MAX_EDITOR_PATH_CHARS = 4096
internal const val MAX_NAMED_ENTRIES = 100

enum class EditValueMode { TEXT, JSON }
enum class EditAction { SET, REMOVE }

data class EditorField(val path: String, val label: String, val preview: String, val exists: Boolean,
    val control: FieldControl = FieldControl.TEXT, val required: Boolean = false, val nullable: Boolean = false,
    val options: List<Pair<String, String>> = emptyList(), val help: String = "")

data class EditInput(
    val path: String,
    val text: String,
    val mode: EditValueMode,
    val action: EditAction = EditAction.SET,
    val initialPath: String = path,
    val initialText: String = text,
    val initialAction: EditAction = action,
    val initialMode: EditValueMode = mode,
    val limitExceeded: Boolean = false,
    val pathEditable: Boolean = false,
    val field: EditorField? = null,
    val stringItems: List<String>? = null,
) {
    val changed: Boolean get() = path != initialPath || text != initialText || action != initialAction || mode != initialMode || limitExceeded
}

data class JsonEditorState(
    val fields: List<EditorField> = emptyList(),
    val input: EditInput? = null,
    val pendingPaths: List<String> = emptyList(),
    val issues: List<Diagnostic> = emptyList(),
    val editIssues: List<Diagnostic> = emptyList(),
    val namedEntriesLimited: Boolean = false,
    val mode: AuthoringMode = AuthoringMode.BASIC,
    val entries: List<LoreEntryItem> = emptyList(),
    val selectedEntry: EntryLocator? = null,
    val canAddBook: Boolean = false,
    val hasBook: Boolean = false,
    val nativeBook: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val guidance: Boolean = false,
) {
    val hasChanges: Boolean get() = pendingPaths.isNotEmpty() || input?.changed == true
    val hasUnappliedInput: Boolean get() = input?.changed == true
}

/** Builds conveniences over the raw tree. No values are synthesized or written here. */
internal fun editorFields(document: CharacterDocument): Pair<List<EditorField>, Boolean> {
    val root = document.raw
    val fields = mutableListOf<EditorField>()
    var limited = false
    fun field(parent: JsonObject?, prefix: String, key: String, label: String) {
        val path = "$prefix/${escapePointerToken(key)}"
        if (path.length > MAX_EDITOR_PATH_CHARS) { limited = true; return }
        val value = parent?.get(key)
        val preview = when {
            parent?.containsKey(key) != true -> "Missing"
            value is JsonPrimitive && value.isString -> value.content.ifEmpty { "Empty text" }
            else -> value.toString()
        }
        fields += EditorField(path, label,
            if (preview.length > 160) preview.take(159) + "…" else preview, parent?.containsKey(key) == true)
    }
    val characterPrefix = if (document.sourceFormat == SourceFormat.V1) "" else "/data"
    if (document.sourceFormat in setOf(SourceFormat.V1, SourceFormat.V2, SourceFormat.V3)) {
        val parent = if (characterPrefix.isEmpty()) root else root["data"] as? JsonObject
        listOf("name" to "Character name", "description" to "Description", "personality" to "Personality",
            "scenario" to "Scenario", "first_mes" to "First greeting", "mes_example" to "Example dialogue")
            .forEach { (key, label) -> field(parent, characterPrefix, key, label) }
        if (document.sourceFormat != SourceFormat.V1) {
            listOf("creator_notes" to "Creator notes", "system_prompt" to "System prompt",
                "post_history_instructions" to "Post-history instructions", "creator" to "Creator",
                "character_version" to "Character version").forEach { (key, label) -> field(parent, characterPrefix, key, label) }
        }
        if (document.sourceFormat == SourceFormat.V3) field(parent, characterPrefix,
            "group_only_greetings", "Group-only greetings (JSON array)")
    }
    val bookPrefix = when (document.sourceFormat) {
        SourceFormat.V2, SourceFormat.V3 -> "/data/character_book"
        SourceFormat.LOREBOOK_V3 -> "/data"
        SourceFormat.LOREBOOK_V2, SourceFormat.WORLD_INFO -> ""
        else -> null
    }
    val book = bookPrefix?.let { valueAt(root, it) as? JsonObject }
    if (book != null) {
        field(book, bookPrefix, "name", "Lorebook name")
        field(book, bookPrefix, "description", "Lorebook description")
        val entries = when (val value = book["entries"]) {
            is JsonArray -> value.mapIndexed { index, element -> index.toString() to element }
            is JsonObject -> value.entries.map { it.key to it.value }
            else -> emptyList()
        }
        entries.take(MAX_NAMED_ENTRIES).forEach { (id, value) ->
            if (value is JsonObject) {
                val path = "$bookPrefix/entries/${escapePointerToken(id)}"
                val entryLabel = "Entry ${id.take(48)}"
                field(value, path, "content", "$entryLabel · Content")
                field(value, path, if (document.sourceFormat == SourceFormat.WORLD_INFO) "comment" else "name", "$entryLabel · Name")
            }
        }
        return fields to (limited || entries.size > MAX_NAMED_ENTRIES)
    }
    return fields to limited
}

internal fun escapePointerToken(value: String): String = value.replace("~", "~0").replace("/", "~1")

/** Used only for known generated paths; the engine validates user-supplied patch paths. */
internal fun valueAt(root: JsonElement, path: String): JsonElement? {
    if (path.isEmpty()) return root
    var node: JsonElement? = root
    path.removePrefix("/").split('/').forEach { raw ->
        val token = raw.replace("~1", "/").replace("~0", "~")
        node = when (val current = node) {
            is JsonObject -> current[token]
            is JsonArray -> token.toIntOrNull()?.let { current.getOrNull(it) }
            else -> null
        }
    }
    return node
}

data class ExportPreview(
    val id: Long,
    val fileName: String,
    val sourceFormat: SourceFormat,
    val revision: Long,
    val sizeBytes: Int,
    val formattingChanged: Boolean,
    val pickerLaunched: Boolean = false,
)

enum class ExportFailure { VALIDATION, WRITE, UNAVAILABLE }

internal fun exportFileName(original: String): String {
    val base = original.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".json")
        .map { if (it.isLetterOrDigit() || it in " ._-") it else '_' }.joinToString("")
        .trim().trim('.').take(100).ifBlank { "TavernForge" }
    return "$base-edited.json"
}

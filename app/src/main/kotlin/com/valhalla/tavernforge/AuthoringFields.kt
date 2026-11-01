package com.valhalla.tavernforge

import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.format.CharacterDocument
import com.valhalla.tavernforge.format.EntryLocator
import com.valhalla.tavernforge.format.lorebookPath
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class AuthoringMode { BASIC, ADVANCED }
enum class FieldControl { TEXT, BOOLEAN, NUMBER, STRINGS, JSON }
data class LoreEntryItem(val locator: EntryLocator, val label: String, val path: String)

internal fun loreEntries(document: CharacterDocument): List<LoreEntryItem> {
    val path = document.lorebookPath() ?: return emptyList()
    val entries = valueAt(document.raw, "$path/entries")
    fun label(value: JsonElement, id: String): String {
        val obj = value as? JsonObject
        return listOf("name", "comment", "content").firstNotNullOfOrNull { key ->
            (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content?.take(100)
        } ?: "Entry $id"
    }
    return when (entries) {
        is JsonArray -> entries.mapIndexed { index, value -> LoreEntryItem(EntryLocator.ArrayIndex(index), label(value, (index + 1).toString()), "$path/entries/$index") }
        is JsonObject -> entries.entries.sortedWith(compareBy<Map.Entry<String, JsonElement>> {
            ((it.value as? JsonObject)?.get("displayIndex") as? JsonPrimitive)?.takeUnless { n -> n.isString }
                ?.content?.toBigDecimalOrNull() ?: it.key.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        }.thenBy { it.key }).map { (key, value) -> LoreEntryItem(EntryLocator.NativeKey(key), label(value, key), "$path/entries/${escapePointerToken(key)}") }
        else -> emptyList()
    }
}

/** Form metadata only; a missing optional field stays absent until explicit Apply. */
internal fun authoringFields(document: CharacterDocument, mode: AuthoringMode, selected: EntryLocator?): List<EditorField> {
    val fields = mutableListOf<EditorField>()
    val root = document.raw
    fun field(path: String, label: String, control: FieldControl = FieldControl.TEXT, required: Boolean = false,
              nullable: Boolean = false, options: List<Pair<String, String>> = emptyList(), help: String = "") {
        if (path.length > MAX_EDITOR_PATH_CHARS) return
        val value = valueAt(root, path)
        val preview = when {
            value == null -> "Absent"
            value is JsonPrimitive && value.isString -> value.content.ifEmpty { "Empty text" }
            else -> value.toString()
        }
        fields += EditorField(path, label, preview.take(160), value != null, control, required, nullable, options, help)
    }
    val char = document.sourceFormat in setOf(SourceFormat.V1, SourceFormat.V2, SourceFormat.V3)
    val v3 = document.sourceFormat in setOf(SourceFormat.V3, SourceFormat.LOREBOOK_V3)
    if (char) {
        val p = if (document.sourceFormat == SourceFormat.V1) "" else "/data"
        listOf("name" to "Character name", "description" to "Description", "personality" to "Personality", "scenario" to "Scenario",
            "first_mes" to "First greeting", "mes_example" to "Example dialogue").forEach { (key, label) -> field("$p/$key", label, required = true) }
        if (document.sourceFormat != SourceFormat.V1) {
            field("$p/creator", "Creator", required = true); field("$p/tags", "Tags", FieldControl.STRINGS, true)
            if (mode == AuthoringMode.ADVANCED) {
                listOf("creator_notes" to "Creator notes", "system_prompt" to "System prompt", "post_history_instructions" to "Post-history instructions",
                    "character_version" to "Character version").forEach { (key, label) -> field("$p/$key", label, required = true) }
                field("$p/alternate_greetings", "Alternate greetings", FieldControl.STRINGS, true)
                if (v3) {
                    field("$p/group_only_greetings", "Group-only greetings", FieldControl.STRINGS, true)
                    field("$p/nickname", "Nickname"); field("$p/source", "Sources", FieldControl.STRINGS)
                    field("$p/creator_notes_multilingual", "Multilingual creator notes", FieldControl.JSON)
                    field("$p/creation_date", "Creation timestamp", FieldControl.NUMBER)
                    field("$p/modification_date", "Modification timestamp", FieldControl.NUMBER)
                    field("$p/assets", "Asset metadata", FieldControl.JSON)
                }
                field("$p/extensions", "Character extensions", FieldControl.JSON, true)
            }
        }
    }
    val bookPath = document.lorebookPath() ?: return fields
    if (valueAt(root, bookPath) !is JsonObject) return fields
    val native = document.sourceFormat == SourceFormat.WORLD_INFO
    if (!native) {
        field("$bookPath/name", "Lorebook name"); field("$bookPath/description", "Lorebook description")
        if (mode == AuthoringMode.ADVANCED) {
            field("$bookPath/scan_depth", "Scan depth", FieldControl.NUMBER)
            field("$bookPath/token_budget", "Token budget", FieldControl.NUMBER)
            field("$bookPath/recursive_scanning", "Recursive scanning", FieldControl.BOOLEAN)
            field("$bookPath/extensions", "Lorebook extensions", FieldControl.JSON, true)
        }
    }
    val entry = loreEntries(document).firstOrNull { it.locator == selected } ?: return fields
    val p = entry.path
    if (valueAt(root, p) !is JsonObject) return fields
    field("$p/${if (native) "comment" else "name"}", "Entry name/comment")
    field("$p/content", "Entry content", required = !native)
    field("$p/${if (native) "key" else "keys"}", "Primary keys", FieldControl.STRINGS, !native)
    field("$p/${if (native) "disable" else "enabled"}", if (native) "Disabled" else "Enabled", FieldControl.BOOLEAN, !native)
    field("$p/constant", "Constant activation", FieldControl.BOOLEAN)
    if (mode == AuthoringMode.ADVANCED) {
        field("$p/${if (native) "keysecondary" else "secondary_keys"}", "Secondary keys", FieldControl.STRINGS)
        field("$p/selective", "Selective matching", FieldControl.BOOLEAN)
        field("$p/${if (native) "caseSensitive" else "case_sensitive"}", "Case sensitive", FieldControl.BOOLEAN, nullable = native)
        field("$p/${if (native) "order" else "insertion_order"}", "Insertion order", FieldControl.NUMBER, !native,
            help = "Controls prompt insertion; separate from entry list order.")
        field("$p/position", "Position", if (native) FieldControl.NUMBER else FieldControl.TEXT, options = if (native)
            listOf("Before character", "After character", "Author note top", "Author note bottom", "At depth", "Example top", "Example bottom", "Outlet").mapIndexed { i, s -> s to i.toString() }
            else listOf("Before character" to "before_char", "After character" to "after_char"))
        if (native) {
            field("$p/selectiveLogic", "Selective logic", FieldControl.NUMBER, options = listOf("AND any" to "0", "NOT all" to "1", "NOT any" to "2", "AND all" to "3"))
            listOf("matchWholeWords", "useGroupScoring").forEach { field("$p/$it", it, FieldControl.BOOLEAN, nullable = true) }
            listOf("excludeRecursion", "preventRecursion", "useProbability", "ignoreBudget", "vectorized", "groupOverride",
                "matchPersonaDescription", "matchCharacterDescription", "matchCharacterPersonality", "matchCharacterDepthPrompt", "matchScenario", "matchCreatorNotes")
                .forEach { field("$p/$it", it, FieldControl.BOOLEAN) }
            listOf("depth", "probability", "role", "groupWeight").forEach { field("$p/$it", it, FieldControl.NUMBER) }
            listOf("scanDepth", "sticky", "cooldown", "delay").forEach { field("$p/$it", it, FieldControl.NUMBER, nullable = true) }
            field("$p/delayUntilRecursion", "Delay until recursion", FieldControl.JSON, help = "Native number or preserved legacy boolean.")
            listOf("outletName", "group", "automationId").forEach { field("$p/$it", it) }
            field("$p/triggers", "Triggers", FieldControl.STRINGS); field("$p/characterFilter", "Character filter", FieldControl.JSON)
            field("$p/uid", "Native UID", FieldControl.JSON, help = "Editing an ID does not rename its object key.")
        } else {
            field("$p/comment", "Comment"); field("$p/priority", "Priority", FieldControl.NUMBER)
            field("$p/id", "Entry ID", FieldControl.JSON)
            if (v3) field("$p/use_regex", "Use regular expressions", FieldControl.BOOLEAN, true)
        }
        field("$p/extensions", "Entry extensions", FieldControl.JSON, !native)
    }
    return fields
}

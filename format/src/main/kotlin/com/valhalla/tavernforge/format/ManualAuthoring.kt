package com.valhalla.tavernforge.format

import com.valhalla.tavernforge.core.model.Diagnostic
import com.valhalla.tavernforge.core.model.SourceFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Creation defaults only; imported documents are never normalized through these templates. */
fun CharacterCardEngine.createTemplate(format: SourceFormat): ParseResult {
    val strings = listOf("name", "description", "personality", "scenario", "first_mes", "mes_example")
    val fields = strings.associateWith<String, JsonElement> { JsonPrimitive("") }.toMutableMap()
    val root = when (format) {
        SourceFormat.V1 -> JsonObject(fields)
        SourceFormat.V2, SourceFormat.V3 -> {
            listOf("creator_notes", "system_prompt", "post_history_instructions", "creator", "character_version")
                .forEach { fields[it] = JsonPrimitive("") }
            fields["alternate_greetings"] = JsonArray(emptyList()); fields["tags"] = JsonArray(emptyList())
            fields["extensions"] = JsonObject(emptyMap())
            if (format == SourceFormat.V3) fields["group_only_greetings"] = JsonArray(emptyList())
            JsonObject(mapOf("spec" to JsonPrimitive(if (format == SourceFormat.V2) "chara_card_v2" else "chara_card_v3"),
                "spec_version" to JsonPrimitive(if (format == SourceFormat.V2) "2.0" else "3.0"), "data" to JsonObject(fields)))
        }
        SourceFormat.LOREBOOK_V2 -> emptyManualBook()
        SourceFormat.LOREBOOK_V3 -> JsonObject(mapOf("spec" to JsonPrimitive("lorebook_v3"), "data" to emptyManualBook()))
        SourceFormat.WORLD_INFO -> JsonObject(mapOf("entries" to JsonObject(emptyMap())))
        SourceFormat.UNKNOWN -> return ParseResult.Failure(null, listOf(Diagnostic("UNSUPPORTED_TEMPLATE", "Choose a supported template.")))
    }
    return when (val result = parse(root.toString().encodeToByteArray())) {
        is ParseResult.Failure -> result
        is ParseResult.Success -> {
            val report = validate(result.document)
            if (result.document.sourceFormat != format || !report.isValid) ParseResult.Failure(result.document.source, report.issues)
            else result
        }
    }
}

sealed interface EntryLocator {
    data class ArrayIndex(val index: Int) : EntryLocator
    data class NativeKey(val key: String) : EntryLocator
}

sealed interface LoreCommand {
    data object CreateEmbeddedBook : LoreCommand
    data class AddEntry(val after: EntryLocator? = null) : LoreCommand
    data class DuplicateEntry(val source: EntryLocator) : LoreCommand
    data class DeleteEntry(val source: EntryLocator) : LoreCommand
    data class ReorderEntries(val ordered: List<EntryLocator>) : LoreCommand
}

sealed interface LoreEditPlanResult {
    class Success(patches: List<JsonPatch>, val document: CharacterDocument, val selection: EntryLocator?) : LoreEditPlanResult {
        private val owned = patches.map(::ownedManualPatch)
        val patches: List<JsonPatch> get() = owned.map(::ownedManualPatch)
    }
    data class Failure(val issues: List<Diagnostic>) : LoreEditPlanResult
}

/** A version-specific path, never a conversion between keyed and array books. */
fun CharacterDocument.lorebookPath(): String? = when (sourceFormat) {
    SourceFormat.V2, SourceFormat.V3 -> "/data/character_book"
    SourceFormat.LOREBOOK_V2, SourceFormat.WORLD_INFO -> ""
    SourceFormat.LOREBOOK_V3 -> "/data"
    else -> null
}

fun CharacterCardEngine.planLoreEdit(document: CharacterDocument, command: LoreCommand): LoreEditPlanResult {
    fun failure(message: String) = LoreEditPlanResult.Failure(listOf(Diagnostic("LORE_COMMAND", message)))
    if (!document.isSupportedFormat) return failure("This format has no supported lore editor.")
    val bookPath = document.lorebookPath() ?: return failure("This format has no embedded lorebook.")
    val root = document.raw
    val book = manualValueAt(root, bookPath)
    if (command == LoreCommand.CreateEmbeddedBook) {
        if (document.sourceFormat !in setOf(SourceFormat.V2, SourceFormat.V3) || book != null)
            return failure("An embedded book can only be added when it is absent.")
        return checkedManualPlan(document, listOf(JsonPatch.Add(bookPath, emptyManualBook())), null)
    }
    val entries = (book as? JsonObject)?.get("entries") ?: return failure("Repair the lorebook entries container before using entry controls.")
    val native = document.sourceFormat == SourceFormat.WORLD_INFO
    if (native && entries !is JsonObject || !native && entries !is JsonArray)
        return failure("The entry container does not match this source format.")
    val keyed = if (native) (entries as JsonObject).map { EntryLocator.NativeKey(it.key) to it.value }
        else (entries as JsonArray).mapIndexed { index, value -> EntryLocator.ArrayIndex(index) to value }
    val path = "$bookPath/entries"
    fun lookup(locator: EntryLocator) = keyed.firstOrNull { it.first == locator }
    fun target(locator: EntryLocator) = "$path/" + when (locator) {
        is EntryLocator.ArrayIndex -> locator.index.toString()
        is EntryLocator.NativeKey -> manualEscape(locator.key)
    }
    if (command is LoreCommand.ReorderEntries) {
        if (command.ordered.size != keyed.size || command.ordered.toSet() != keyed.map { it.first }.toSet())
            return failure("Reorder must include each entry exactly once.")
        val indexed = keyed.toMap()
        val reordered = if (native) {
            val orders = command.ordered.withIndex().associate { it.value to it.index }
            val output = linkedMapOf<String, JsonElement>()
            for ((locator, value) in keyed) {
                if (value !is JsonObject) return failure("Repair non-object entries before changing native display order.")
                output[(locator as EntryLocator.NativeKey).key] = JsonObject(value + ("displayIndex" to JsonPrimitive(orders.getValue(locator))))
            }
            JsonObject(output)
        } else JsonArray(command.ordered.map { indexed.getValue(it) })
        return checkedManualPlan(document, listOf(JsonPatch.Replace(path, reordered)), null)
    }
    val source = when (command) {
        is LoreCommand.AddEntry -> command.after
        is LoreCommand.DuplicateEntry -> command.source
        is LoreCommand.DeleteEntry -> command.source
        else -> null
    }
    if (source != null && lookup(source) == null) return failure("The selected entry no longer exists.")
    if (command is LoreCommand.DeleteEntry) return checkedManualPlan(document, listOf(JsonPatch.Remove(target(command.source))), null)
    if (keyed.size >= limits.maxCollectionEntries) return failure("The lorebook has reached the entry limit.")
    val duplicate = command is LoreCommand.DuplicateEntry
    val original = if (duplicate) lookup(requireNotNull(source))?.second as? JsonObject
        ?: return failure("Only an object entry can be duplicated.") else null
    if (native) {
        val objectEntries = entries as JsonObject
        val numericIds = objectEntries.keys.mapNotNull(::manualIntegerId).toSet() +
            objectEntries.values.mapNotNull { (it as? JsonObject)?.get("uid")?.let(::manualIntegerId) }
        val uid = (0..(objectEntries.size * 2)).firstOrNull { it.toBigDecimal() !in numericIds && it.toString() !in objectEntries }
            ?: return failure("No safe unused entry identifier was available.")
        // Place only the new entry. Existing displayIndex values, UID keys and runtime order stay intact.
        val ordered = objectEntries.entries.map { existing ->
            val rawIndex = (existing.value as? JsonObject)?.get("displayIndex") as? JsonPrimitive
            val index = rawIndex?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()
                ?: existing.key.takeIf { it.length <= 1024 }?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            existing.key to index
        }.sortedWith(compareBy<Pair<String, java.math.BigDecimal>> { it.second }.thenBy { it.first })
        if (ordered.any { it.second.precision() > 128 || it.second.scale() !in -128..128 })
            return failure("Use an explicit entry-list reorder to reset unusual display indices before inserting an entry.")
        val afterKey = (source as? EntryLocator.NativeKey)?.key
        val sourceIndex = if (afterKey == null) ordered.lastIndex else ordered.indexOfFirst { it.first == afterKey }
        val lower = ordered.getOrNull(sourceIndex)?.second
        val upper = ordered.getOrNull(sourceIndex + 1)?.second
        if (lower != null && upper != null && lower.compareTo(upper) == 0)
            return failure("Use an explicit entry-list reorder to separate tied display indices before inserting here.")
        val displayIndex = when {
            lower == null -> java.math.BigDecimal.ZERO
            upper == null -> lower + java.math.BigDecimal.ONE
            else -> (lower + upper).divide(java.math.BigDecimal(2))
        }
        val indexValue = (parseValue(displayIndex.toString().encodeToByteArray()) as? JsonValueResult.Success)?.value
            ?: return failure("The new display index exceeded the numeric limit.")
        val entry = JsonObject((original ?: nativeManualEntry()) + mapOf("uid" to JsonPrimitive(uid), "displayIndex" to indexValue))
        val locator = EntryLocator.NativeKey(uid.toString())
        return checkedManualPlan(document, listOf(JsonPatch.Add(target(locator), entry)), locator)
    }
    var entry = original ?: arrayManualEntry(document.sourceFormat in setOf(SourceFormat.V3, SourceFormat.LOREBOOK_V3))
    if (duplicate && entry.containsKey("id")) {
        val id = entry["id"] as? JsonPrimitive ?: return failure("Repair the entry ID before duplicating it.")
        val allIds = keyed.mapNotNull { (it.second as? JsonObject)?.get("id") }
        val newId = if (id.isString && document.sourceFormat in setOf(SourceFormat.V3, SourceFormat.LOREBOOK_V3)) {
            val used = allIds.filterIsInstance<JsonPrimitive>().filter { it.isString }.map { it.content }.toSet()
            val suffix = (1..(used.size + 1)).first { "${id.content}-copy-$it" !in used }
            JsonPrimitive("${id.content}-copy-$suffix")
        } else if (!id.isString && id.content.toDoubleOrNull()?.isFinite() == true) {
            val used = allIds.mapNotNull(::manualIntegerId).toSet()
            JsonPrimitive((0..used.size).first { it.toBigDecimal() !in used })
        } else return failure("Repair the entry ID before duplicating it.")
        entry = JsonObject(entry + ("id" to newId))
    }
    val index = (source as? EntryLocator.ArrayIndex)?.index?.plus(1) ?: keyed.size
    val locator = EntryLocator.ArrayIndex(index)
    return checkedManualPlan(document, listOf(JsonPatch.Add(target(locator), entry)), locator)
}

private fun CharacterCardEngine.checkedManualPlan(document: CharacterDocument, patches: List<JsonPatch>, selection: EntryLocator?) =
    when (val result = patch(document, document.revision, patches)) {
        is PatchResult.Success -> LoreEditPlanResult.Success(patches, result.document, selection)
        is PatchResult.Failure -> LoreEditPlanResult.Failure(result.issues)
    }

private fun emptyManualBook() = JsonObject(mapOf("extensions" to JsonObject(emptyMap()), "entries" to JsonArray(emptyList())))
private fun arrayManualEntry(v3: Boolean): JsonObject {
    val entry = linkedMapOf<String, JsonElement>("keys" to JsonArray(emptyList()), "content" to JsonPrimitive(""),
        "extensions" to JsonObject(emptyMap()), "enabled" to JsonPrimitive(true), "insertion_order" to JsonPrimitive(100))
    if (v3) entry["use_regex"] = JsonPrimitive(false)
    return JsonObject(entry)
}

/** Values from pinned ST world-info.js newWorldInfoEntryDefinition, not a mandatory schema. */
private fun nativeManualEntry(): JsonObject {
    val fields = linkedMapOf<String, JsonElement>()
    listOf("key", "keysecondary", "triggers").forEach { fields[it] = JsonArray(emptyList()) }
    listOf("comment", "content", "outletName", "group", "automationId").forEach { fields[it] = JsonPrimitive("") }
    listOf("constant", "vectorized", "addMemo", "disable", "ignoreBudget", "excludeRecursion", "preventRecursion",
        "matchPersonaDescription", "matchCharacterDescription", "matchCharacterPersonality", "matchCharacterDepthPrompt",
        "matchScenario", "matchCreatorNotes", "groupOverride").forEach { fields[it] = JsonPrimitive(false) }
    listOf("selective", "useProbability").forEach { fields[it] = JsonPrimitive(true) }
    listOf("selectiveLogic", "position", "delayUntilRecursion", "role").forEach { fields[it] = JsonPrimitive(0) }
    listOf("order", "probability", "groupWeight").forEach { fields[it] = JsonPrimitive(100) }
    fields["depth"] = JsonPrimitive(4)
    listOf("scanDepth", "caseSensitive", "matchWholeWords", "useGroupScoring", "sticky", "cooldown", "delay")
        .forEach { fields[it] = JsonNull }
    return JsonObject(fields)
}

private fun manualIntegerId(value: JsonElement) = (value as? JsonPrimitive)?.content?.let(::manualIntegerId)
private fun manualIntegerId(value: String): java.math.BigDecimal? {
    // Only allocator candidates matter. Do not expand an imported exponent or a huge string key.
    if (value.length > 1024) return null
    val number = value.toBigDecimalOrNull() ?: return null
    if (number.signum() < 0 || number > java.math.BigDecimal(2_000_000)) return null
    return number.stripTrailingZeros().takeIf { it.scale() <= 0 }?.setScale(0)
}
private fun manualEscape(value: String) = value.replace("~", "~0").replace("/", "~1")
private fun manualValueAt(root: JsonObject, path: String): JsonElement? {
    if (path.isEmpty()) return root
    var value: JsonElement? = root
    for (token in path.removePrefix("/").split('/')) value = (value as? JsonObject)?.get(token)
    return value
}
private fun ownedManualPatch(patch: JsonPatch): JsonPatch = when (patch) {
    is JsonPatch.Add -> JsonPatch.Add(patch.path, snapshot(patch.value))
    is JsonPatch.Replace -> JsonPatch.Replace(patch.path, snapshot(patch.value))
    is JsonPatch.Remove -> patch
}

package com.valhalla.tavernforge.format

import com.valhalla.tavernforge.core.model.Diagnostic
import com.valhalla.tavernforge.core.model.Severity
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.core.model.ValidationPhase
import com.valhalla.tavernforge.core.model.ValidationReport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.Locale

/** Pinned structural contracts and interpretation notes: docs/build/JSON_FORMAT_SUPPORT.md. */
internal object JsonFormatValidator {
    fun validate(document: CharacterDocument): ValidationReport {
        if (!document.isSupportedFormat) {
            val versioned = document.sourceFormat == SourceFormat.V2 || document.sourceFormat == SourceFormat.V3
            return ValidationReport(listOf(Diagnostic(
                if (versioned) "UNSUPPORTED_SPEC_VERSION" else "UNSUPPORTED_SOURCE_FORMAT",
                if (versioned) "This declared version has no pinned export contract. Original JSON is preserved for inspection."
                else "Unrecognized JSON format. Original JSON is preserved; conversion is not implemented.",
                if (versioned) "/spec_version" else "", phase = ValidationPhase.COMPATIBILITY,
            )))
        }
        return if (document.sourceFormat == SourceFormat.V2) V2Validator.validate(document)
        else Checker().check(document)
    }

    private class Checker {
        private val issues = mutableListOf<Diagnostic>()

        fun check(document: CharacterDocument): ValidationReport {
            val root = document.tree
            when (document.sourceFormat) {
                SourceFormat.V1 -> listOf("name", "description", "personality", "scenario", "first_mes", "mes_example")
                    .forEach { string(root, it, "", true) }
                SourceFormat.V3 -> {
                    val data = obj(root, "data", "", true)
                    if (data != null) characterV3(data)
                }
                SourceFormat.LOREBOOK_V2 -> book(root, "", false)
                SourceFormat.LOREBOOK_V3 -> obj(root, "data", "", true)?.let { book(it, "/data", true) }
                SourceFormat.WORLD_INFO -> worldInfo(root)
                else -> error("Unsupported format reached schema validation")
            }
            return ValidationReport(issues)
        }

        private fun characterV3(data: JsonObject) {
            V2Validator.stringFields.forEach { string(data, it, "/data", true) }
            listOf("alternate_greetings", "tags").forEach { stringArray(data, it, "/data", true) }
            if (!data.containsKey("group_only_greetings")) {
                issue("REQUIRED_FIELD", "V3 requires this array; it may be empty [] when no group greetings are used.",
                    "/data/group_only_greetings")
            } else stringArray(data, "group_only_greetings", "/data", true)
            obj(data, "extensions", "/data", true)
            obj(data, "character_book", "/data")?.let { book(it, "/data/character_book", true) }
            string(data, "nickname", "/data")
            stringArray(data, "source", "/data")
            number(data, "creation_date", "/data")
            number(data, "modification_date", "/data")
            obj(data, "creator_notes_multilingual", "/data")?.forEach { (language, value) ->
                val path = "/data/creator_notes_multilingual/${escape(language)}"
                if (language !in ISO_LANGUAGES) issue("INVALID_LANGUAGE_CODE", "Expected an ISO 639-1 language code without a region.", path)
                if (!isString(value)) issue("WRONG_TYPE", "Expected a translated creator note string.", path)
            }
            val assets = array(data, "assets", "/data") ?: return
            assets.forEachIndexed { index, value ->
                val path = "/data/assets/$index"
                if (value !is JsonObject) issue("WRONG_TYPE", "Expected an asset object.", path)
                else {
                    listOf("type", "uri", "name", "ext").forEach { string(value, it, path, true) }
                    val ext = (value["ext"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (ext != null && !EXTENSION.matches(ext)) issue("INVALID_ASSET_EXTENSION", "Asset extension must be lowercase without a dot.", "$path/ext")
                    val uri = (value["uri"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (uri != null && !URI_SCHEME.containsMatchIn(uri) && !uri.startsWith("__asset:")) {
                        issue("INVALID_ASSET_URI", "Expected an asset URI with a scheme.", "$path/uri")
                    }
                }
            }
            listOf("icon", "background").forEach { type ->
                val typed = assets.filterIsInstance<JsonObject>().filter { it["type"] == JsonPrimitive(type) }
                val mainCount = typed.count { it["name"] == JsonPrimitive("main") }
                if (typed.size > 1 && (mainCount > 1 || (type == "icon" && mainCount != 1))) {
                    issue("INVALID_MAIN_ASSET", "Multiple $type assets require ${if (type == "icon") "exactly" else "at most"} one asset named 'main'.", "/data/assets")
                }
            }
        }

        private fun book(book: JsonObject, path: String, v3: Boolean) {
            string(book, "name", path)
            string(book, "description", path)
            number(book, "scan_depth", path)
            number(book, "token_budget", path)
            boolean(book, "recursive_scanning", path)
            obj(book, "extensions", path, true)
            array(book, "entries", path, true)?.forEachIndexed { index, value ->
                val entryPath = "$path/entries/$index"
                if (value !is JsonObject) issue("WRONG_TYPE", "Expected a lore entry object.", entryPath)
                else entry(value, entryPath, v3)
            }
        }

        private fun entry(entry: JsonObject, path: String, v3: Boolean) {
            stringArray(entry, "keys", path, true)
            string(entry, "content", path, true)
            obj(entry, "extensions", path, true)
            boolean(entry, "enabled", path, true)
            number(entry, "insertion_order", path, true)
            boolean(entry, "case_sensitive", path)
            if (v3) boolean(entry, "use_regex", path, true)
            string(entry, "name", path)
            number(entry, "priority", path)
            if (v3) field(entry, "id", path, false, "string or finite number") { isString(it) || isNumber(it) }
            else number(entry, "id", path)
            string(entry, "comment", path)
            boolean(entry, "selective", path)
            // The pinned V3 TypeScript contract says string[]; its prose is ambiguous.
            stringArray(entry, "secondary_keys", path)
            boolean(entry, "constant", path)
            val position = string(entry, "position", path)
            if (position != null && position.content !in setOf("before_char", "after_char")) {
                issue("INVALID_ENUM", "Position must be 'before_char' or 'after_char'.", "$path/position")
            }
            if (entry["selective"] == JsonPrimitive(true) && entry["constant"] != JsonPrimitive(true) &&
                (entry["secondary_keys"] == null || (entry["secondary_keys"] as? JsonArray)?.isEmpty() == true)) {
                issues += Diagnostic("SELECTIVE_WITHOUT_SECONDARY_KEYS", "Selective matching has no secondary keys and may never activate.",
                    "$path/secondary_keys", Severity.WARNING, ValidationPhase.SEMANTIC)
            }
        }

        /** Native WI has no normative versioned schema. Validate pinned field types without adding defaults. */
        private fun worldInfo(root: JsonObject) {
            obj(root, "entries", "", true)?.forEach { (id, value) ->
                val path = "/entries/${escape(id)}"
                if (value !is JsonObject) issue("WRONG_TYPE", "Expected a World Info entry object.", path)
                else {
                    listOf("key", "keysecondary", "triggers").forEach { stringArray(value, it, path) }
                    listOf("comment", "content", "outletName", "group", "automationId").forEach { string(value, it, path) }
                    listOf("constant", "vectorized", "selective", "addMemo", "disable", "ignoreBudget", "excludeRecursion",
                        "preventRecursion", "matchPersonaDescription", "matchCharacterDescription", "matchCharacterPersonality",
                        "matchCharacterDepthPrompt", "matchScenario", "matchCreatorNotes", "useProbability", "groupOverride")
                        .forEach { boolean(value, it, path) }
                    listOf("order", "probability", "depth", "groupWeight", "role", "displayIndex")
                        .forEach { number(value, it, path) }
                    numericEnum(value, "position", path, 0..7)
                    numericEnum(value, "selectiveLogic", path, 0..3)
                    listOf("scanDepth", "sticky", "cooldown", "delay").forEach { number(value, it, path, nullable = true) }
                    listOf("caseSensitive", "matchWholeWords", "useGroupScoring").forEach { boolean(value, it, path, nullable = true) }
                    // The same pinned revision's CharacterBook converter emits false for this numeric field.
                    field(value, "delayUntilRecursion", path, false, "finite number or legacy boolean") { isNumber(it) || isBoolean(it) }
                    // Native creation uses an integer; its CharacterBook converter also preserves string IDs.
                    field(value, "uid", path, false, "string or finite number") { isString(it) || isNumber(it) }
                    obj(value, "extensions", path)
                    obj(value, "characterFilter", path)?.let { filter ->
                        boolean(filter, "isExclude", "$path/characterFilter")
                        stringArray(filter, "names", "$path/characterFilter")
                        stringArray(filter, "tags", "$path/characterFilter")
                    }
                }
            }
        }

        private fun string(obj: JsonObject, key: String, path: String, required: Boolean = false): JsonPrimitive? =
            field(obj, key, path, required, "string", ::isString) as? JsonPrimitive

        private fun number(obj: JsonObject, key: String, path: String, required: Boolean = false, nullable: Boolean = false) {
            field(obj, key, path, required, if (nullable) "finite number or null" else "finite number") { isNumber(it) || (nullable && it == JsonNull) }
        }

        private fun numericEnum(obj: JsonObject, key: String, path: String, allowed: IntRange) {
            val value = field(obj, key, path, false, "finite number", ::isNumber) as? JsonPrimitive ?: return
            // Compare exact decimal values: floating-point rounding must not admit fractional enums.
            val number = value.content.toBigDecimalOrNull()
            if (number == null || allowed.none { number.compareTo(it.toBigDecimal()) == 0 }) {
                issue("INVALID_ENUM", "Expected a pinned native value from ${allowed.first} through ${allowed.last}.", "$path/$key")
            }
        }

        private fun boolean(obj: JsonObject, key: String, path: String, required: Boolean = false, nullable: Boolean = false) {
            field(obj, key, path, required, if (nullable) "boolean or null" else "boolean") { isBoolean(it) || (nullable && it == JsonNull) }
        }

        private fun obj(obj: JsonObject, key: String, path: String, required: Boolean = false): JsonObject? =
            field(obj, key, path, required, "object") { it is JsonObject } as? JsonObject

        private fun array(obj: JsonObject, key: String, path: String, required: Boolean = false): JsonArray? =
            field(obj, key, path, required, "array") { it is JsonArray } as? JsonArray

        private fun stringArray(obj: JsonObject, key: String, path: String, required: Boolean = false) {
            array(obj, key, path, required)?.forEachIndexed { index, value ->
                if (!isString(value)) issue("WRONG_TYPE", "Expected a string array item.", "$path/$key/$index")
            }
        }

        private fun field(obj: JsonObject, key: String, path: String, required: Boolean, expected: String,
                          matches: (JsonElement) -> Boolean): JsonElement? {
            val value = obj[key]
            if (value == null) {
                if (required) issue("REQUIRED_FIELD", "Required field is missing; expected $expected.", "$path/$key")
                return null
            }
            if (!matches(value)) {
                issue("WRONG_TYPE", "Expected $expected.", "$path/$key")
                return null
            }
            return value
        }

        private fun issue(code: String, message: String, path: String) {
            issues += Diagnostic(code, message, path, phase = ValidationPhase.SCHEMA)
        }
    }

    private fun isString(value: JsonElement): Boolean = value is JsonPrimitive && value.isString
    private fun isNumber(value: JsonElement): Boolean = value is JsonPrimitive && !value.isString && value.content.toDoubleOrNull()?.isFinite() == true
    private fun isBoolean(value: JsonElement): Boolean = value is JsonPrimitive && !value.isString && value.booleanOrNull != null
    private fun escape(key: String): String = key.replace("~", "~0").replace("/", "~1")
    private val ISO_LANGUAGES = Locale.getISOLanguages().toSet()
    private val EXTENSION = Regex("[a-z0-9][a-z0-9_-]*")
    private val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
}

package com.valhalla.tavernforge.format

import com.valhalla.tavernforge.core.model.Diagnostic
import com.valhalla.tavernforge.core.model.Severity
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.core.model.ValidationPhase
import com.valhalla.tavernforge.core.model.ValidationReport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Contract: character-card-spec-v2@8083fb388615ccbce768e97cbbd49d2b3214632c/spec_v2.md. */
internal object V2Validator {
    val stringFields = listOf("name", "description", "personality", "scenario", "first_mes", "mes_example",
        "creator_notes", "system_prompt", "post_history_instructions", "creator", "character_version")

    fun validate(document: CharacterDocument): ValidationReport = Checker().check(document)

    private class Checker {
        private val issues = mutableListOf<Diagnostic>()
        fun check(document: CharacterDocument): ValidationReport {
            if (document.sourceFormat != SourceFormat.V2) {
                issues += Diagnostic("UNSUPPORTED_SOURCE_FORMAT", "Strict export currently supports imported or created V2 cards only. Conversion is not implemented.",
                    phase = ValidationPhase.COMPATIBILITY)
            }
            val root = document.tree
            literal(root, "spec", "chara_card_v2", "")
            literal(root, "spec_version", "2.0", "")
            val data = field(root, "data", "", true, "object") { it is JsonObject } as? JsonObject
            if (data != null) {
                stringFields.forEach { string(data, it, "/data", true) }
                stringArray(data, "alternate_greetings", "/data", true)
                stringArray(data, "tags", "/data", true)
                field(data, "extensions", "/data", true, "object") { it is JsonObject }
                val book = field(data, "character_book", "/data", false, "object") { it is JsonObject } as? JsonObject
                if (book != null) book(book, "/data/character_book")
            }
            return ValidationReport(issues)
        }

        private fun book(book: JsonObject, path: String) {
            string(book, "name", path)
            string(book, "description", path)
            number(book, "scan_depth", path)
            number(book, "token_budget", path)
            boolean(book, "recursive_scanning", path)
            field(book, "extensions", path, true, "object") { it is JsonObject }
            val entries = field(book, "entries", path, true, "array") { it is JsonArray } as? JsonArray ?: return
            entries.forEachIndexed { index, value ->
                val entryPath = "$path/entries/$index"
                if (value !is JsonObject) issue("WRONG_TYPE", "Expected a lore entry object.", entryPath)
                else entry(value, entryPath)
            }
        }

        private fun entry(entry: JsonObject, path: String) {
            stringArray(entry, "keys", path, true)
            string(entry, "content", path, true)
            field(entry, "extensions", path, true, "object") { it is JsonObject }
            boolean(entry, "enabled", path, true)
            number(entry, "insertion_order", path, true)
            boolean(entry, "case_sensitive", path)
            string(entry, "name", path)
            number(entry, "priority", path)
            number(entry, "id", path)
            string(entry, "comment", path)
            boolean(entry, "selective", path)
            stringArray(entry, "secondary_keys", path)
            boolean(entry, "constant", path)
            val position = string(entry, "position", path)
            if (position != null && position.content !in setOf("before_char", "after_char")) {
                issue("INVALID_ENUM", "V2 position must be 'before_char' or 'after_char'.", "$path/position")
            }
            if ((entry["selective"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true &&
                (entry["constant"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != true &&
                (entry["secondary_keys"] == null || (entry["secondary_keys"] as? JsonArray)?.isEmpty() == true)) {
                issues += Diagnostic("SELECTIVE_WITHOUT_SECONDARY_KEYS", "Selective matching has no secondary keys and may never activate.",
                    "$path/secondary_keys", Severity.WARNING, ValidationPhase.SEMANTIC)
            }
        }

        private fun literal(obj: JsonObject, key: String, value: String, path: String) {
            val actual = string(obj, key, path, true) ?: return
            if (actual.content != value) issue("INVALID_LITERAL", "Expected '$value'.", "$path/$key")
        }
        private fun string(obj: JsonObject, key: String, path: String, required: Boolean = false): JsonPrimitive? =
            field(obj, key, path, required, "string") { it is JsonPrimitive && it.isString } as? JsonPrimitive

        private fun number(obj: JsonObject, key: String, path: String, required: Boolean = false) {
            field(obj, key, path, required, "finite number") {
                it is JsonPrimitive && !it.isString && it.content.toDoubleOrNull()?.isFinite() == true
            }
        }
        private fun boolean(obj: JsonObject, key: String, path: String, required: Boolean = false) {
            field(obj, key, path, required, "boolean") { it is JsonPrimitive && !it.isString && it.booleanOrNull != null }
        }
        private fun stringArray(obj: JsonObject, key: String, path: String, required: Boolean = false) {
            val array = field(obj, key, path, required, "array") { it is JsonArray } as? JsonArray ?: return
            array.forEachIndexed { index, value ->
                if (value !is JsonPrimitive || !value.isString) issue("WRONG_TYPE", "Expected a string array item.", "$path/$key/$index")
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
}

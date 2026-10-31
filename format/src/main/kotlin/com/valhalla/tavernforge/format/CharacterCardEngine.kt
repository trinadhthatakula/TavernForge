package com.valhalla.tavernforge.format

import com.valhalla.tavernforge.core.model.Diagnostic
import com.valhalla.tavernforge.core.model.JsonLimits
import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.core.model.ValidationPhase
import com.valhalla.tavernforge.core.model.ValidationReport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Bounded JSON intake and explicit V2 validation/export; no implicit format conversion. */
class CharacterCardEngine(val limits: JsonLimits = JsonLimits()) {
    private val json = BoundedJson(limits)

    fun parse(bytes: ByteArray): ParseResult {
        if (bytes.size > limits.maxBytes) return ParseResult.Failure(null,
            listOf(Diagnostic("BYTE_LIMIT", "JSON exceeds the byte limit; the input was not copied.")))
        val source = SourceEnvelope(bytes)
        return try {
            val tree = json.parse(source.bytes())
            if (tree !is JsonObject) ParseResult.Failure(source,
                listOf(Diagnostic("ROOT_NOT_OBJECT", "A character document must be a JSON object.", phase = ValidationPhase.SCHEMA)))
            else ParseResult.Success(CharacterDocument.parsed(source, tree, detect(tree)))
        } catch (failure: JsonFailure) { ParseResult.Failure(source, listOf(failure.diagnostic)) }
    }

    fun validateV2(document: CharacterDocument): ValidationReport = V2Validator.validate(document)

    fun exportV2(document: CharacterDocument): ExportResult {
        val report = validateV2(document)
        if (!report.isValid) return ExportResult.Failure(report.issues)
        return try {
            // Recheck this engine's limits even if a different engine created the document.
            if (document.untouched) {
                val bytes = document.source.bytes()
                if (json.parse(bytes) != document.tree) return ExportResult.Failure(listOf(
                    Diagnostic("SOURCE_MISMATCH", "The original source does not match this document.", phase = ValidationPhase.COMPATIBILITY)))
                ExportResult.Success(bytes)
            } else ExportResult.Success(json.encode(document.tree))
        } catch (failure: JsonFailure) { ExportResult.Failure(listOf(failure.diagnostic)) }
    }

    fun patch(document: CharacterDocument, expectedRevision: Long, operations: List<JsonPatch>): PatchResult {
        if (expectedRevision != document.revision) return patchFailure("REVISION_CONFLICT", "The document changed; review edits against its current revision.")
        if (operations.size > limits.maxPatchOperations) return patchFailure("PATCH_LIMIT", "Too many patch operations.")
        if (operations.isEmpty()) return PatchResult.Success(document)
        if (document.revision == Long.MAX_VALUE) return patchFailure("REVISION_OVERFLOW", "Document revision cannot be incremented.")
        return try {
            var tree: JsonElement = document.tree
            var encodedWork = 0L
            fun charge(bytes: ByteArray): ByteArray {
                encodedWork += bytes.size
                if (encodedWork > limits.maxPatchWorkBytes) invalid("PATCH_WORK_LIMIT", "Patch batch exceeds its processing budget.", "")
                return bytes
            }
            for (operation in operations) {
                val tokens = parsePointer(operation.path)
                // Writing then reading establishes an owned, bounded, syntactically valid snapshot.
                val value = when (operation) {
                    is JsonPatch.Add -> json.parse(charge(json.encode(operation.value)))
                    is JsonPatch.Replace -> json.parse(charge(json.encode(operation.value)))
                    is JsonPatch.Remove -> null
                }
                tree = apply(tree, tokens, 0, operation, value)
                // Enforce budgets after each operation, not only after the last removal.
                charge(json.encode(tree))
            }
            if (tree !is JsonObject) return patchFailure("ROOT_NOT_OBJECT", "A character document must remain an object.")
            if (tree["spec"] != document.tree["spec"] || tree["spec_version"] != document.tree["spec_version"]) {
                return patchFailure("CONVERSION_REQUIRED", "Changing a format discriminator requires a reviewed conversion, which is not implemented.")
            }
            PatchResult.Success(document.withTree(tree))
        } catch (failure: JsonFailure) {
            PatchResult.Failure(listOf(failure.diagnostic.copy(phase = ValidationPhase.PATCH)))
        }
    }

    /** All required string fields exist but may be empty according to the V2 contract. */
    fun createV2(name: String = ""): CharacterDocument {
        val data = linkedMapOf<String, JsonElement>()
        V2Validator.stringFields.forEach { data[it] = JsonPrimitive(if (it == "name") name else "") }
        data["alternate_greetings"] = JsonArray(emptyList())
        data["tags"] = JsonArray(emptyList())
        data["extensions"] = JsonObject(emptyMap())
        val tree = JsonObject(mapOf("spec" to JsonPrimitive("chara_card_v2"),
            "spec_version" to JsonPrimitive("2.0"), "data" to JsonObject(data)))
        val bytes = try { json.encode(tree) } catch (failure: JsonFailure) {
            throw IllegalArgumentException("The V2 template exceeds configured limits: ${failure.diagnostic.code}")
        }
        return (parse(bytes) as ParseResult.Success).document
    }

    private fun detect(tree: JsonObject): SourceFormat = when ((tree["spec"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
        "chara_card_v2" -> SourceFormat.V2
        "chara_card_v3" -> SourceFormat.V3
        null -> if (!tree.containsKey("spec") && listOf("name", "description", "personality", "scenario", "first_mes", "mes_example")
            .all { tree.containsKey(it) }) SourceFormat.V1 else SourceFormat.UNKNOWN
        else -> SourceFormat.UNKNOWN
    }

    private fun parsePointer(path: String): List<String> {
        if (path.isEmpty()) return emptyList()
        if (path.length > limits.maxBytes || !path.startsWith('/')) invalid("INVALID_POINTER", "Expected an RFC 6901 JSON pointer.", path)
        if (path.count { it == '/' } > limits.maxDepth) invalid("DEPTH_LIMIT", "Pointer exceeds the nesting limit.", path)
        val parts = path.substring(1).split('/')
        return parts.map { part ->
            val result = StringBuilder()
            var index = 0
            while (index < part.length) {
                val c = part[index++]
                if (c != '~') result.append(c)
                else {
                    if (index >= part.length) invalid("INVALID_POINTER", "Incomplete pointer escape.", path)
                    result.append(when (part[index++]) {
                        '0' -> '~'
                        '1' -> '/'
                        else -> invalid("INVALID_POINTER", "Only ~0 and ~1 are valid pointer escapes.", path)
                    })
                }
            }
            result.toString()
        }
    }

    private fun apply(node: JsonElement, tokens: List<String>, depth: Int, operation: JsonPatch, value: JsonElement?): JsonElement {
        if (depth == tokens.size) return when (operation) {
            is JsonPatch.Remove -> invalid("INVALID_PATCH", "The document root cannot be removed.", operation.path)
            else -> requireNotNull(value)
        }
        val token = tokens[depth]
        val leaf = depth == tokens.lastIndex
        return when (node) {
            is JsonObject -> {
                val map = node.toMutableMap()
                if (leaf) {
                    when (operation) {
                        is JsonPatch.Add -> map[token] = requireNotNull(value)
                        is JsonPatch.Replace -> {
                            if (!map.containsKey(token)) invalid("MISSING_PATH", "Replace requires an existing member.", operation.path)
                            map[token] = requireNotNull(value)
                        }
                        is JsonPatch.Remove -> {
                            if (!map.containsKey(token)) invalid("MISSING_PATH", "Remove requires an existing member.", operation.path)
                            map.remove(token)
                        }
                    }
                } else {
                    val child = map[token] ?: invalid("MISSING_PATH", "Pointer parent does not exist.", operation.path)
                    map[token] = apply(child, tokens, depth + 1, operation, value)
                }
                JsonObject(map)
            }
            is JsonArray -> {
                val list = node.toMutableList()
                val index = if (token == "-" && leaf && operation is JsonPatch.Add) list.size else {
                    if (!INDEX.matches(token)) invalid("INVALID_INDEX", "Expected a canonical nonnegative array index.", operation.path)
                    token.toIntOrNull() ?: invalid("INVALID_INDEX", "Array index is too large.", operation.path)
                }
                if (leaf && operation is JsonPatch.Add) {
                    if (index !in 0..list.size) invalid("MISSING_PATH", "Array insertion index is outside its bounds.", operation.path)
                    list.add(index, requireNotNull(value))
                } else {
                    if (index !in list.indices) invalid("MISSING_PATH", "Array index does not exist.", operation.path)
                    if (!leaf) list[index] = apply(list[index], tokens, depth + 1, operation, value)
                    else when (operation) {
                        is JsonPatch.Remove -> list.removeAt(index)
                        else -> list[index] = requireNotNull(value)
                    }
                }
                JsonArray(list)
            }
            else -> invalid("INVALID_PARENT", "Pointer traverses a scalar value.", operation.path)
        }
    }
    private fun patchFailure(code: String, message: String): PatchResult.Failure =
        PatchResult.Failure(listOf(Diagnostic(code, message, phase = ValidationPhase.PATCH)))
    private fun invalid(code: String, message: String, path: String): Nothing =
        throw JsonFailure(Diagnostic(code, message, path, phase = ValidationPhase.PATCH))

    private companion object { val INDEX = Regex("0|[1-9][0-9]*") }
}

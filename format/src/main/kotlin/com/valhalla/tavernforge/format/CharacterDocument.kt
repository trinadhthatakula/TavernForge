package com.valhalla.tavernforge.format

import com.valhalla.tavernforge.core.model.CharacterFields
import com.valhalla.tavernforge.core.model.Diagnostic
import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.SourceFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Only the engine constructs documents; callers cannot assert an unearned no-op state. */
class CharacterDocument private constructor(
    val source: SourceEnvelope,
    internal val tree: JsonObject,
    val revision: Long,
    val sourceFormat: SourceFormat,
    internal val untouched: Boolean,
) {
    internal fun withTree(tree: JsonObject): CharacterDocument =
        CharacterDocument(source, tree, revision + 1, sourceFormat, false)

    internal companion object {
        fun parsed(source: SourceEnvelope, tree: JsonObject, sourceFormat: SourceFormat): CharacterDocument =
            CharacterDocument(source, tree, 0, sourceFormat, true)
    }

    /** A deep snapshot: casting a returned map/list cannot mutate this document. */
    val raw: JsonObject get() = snapshot(tree) as JsonObject

    val specVersion: String? get() = text(tree, "spec_version")

    /** Recognition of a pinned target, independent of whether its fields are valid. */
    val isSupportedFormat: Boolean get() = when (sourceFormat) {
        SourceFormat.V2 -> specVersion == "2.0"
        SourceFormat.V3 -> specVersion == "3.0"
        SourceFormat.UNKNOWN -> false
        else -> true
    }

    val displayName: String? get() = text(when (sourceFormat) {
        SourceFormat.V2, SourceFormat.V3, SourceFormat.LOREBOOK_V3 -> tree["data"] as? JsonObject
        else -> tree
    }, "name")

    val loreEntryCount: Int? get() {
        val book = when (sourceFormat) {
            SourceFormat.V2, SourceFormat.V3 -> (tree["data"] as? JsonObject)?.get("character_book") as? JsonObject
            SourceFormat.LOREBOOK_V3 -> tree["data"] as? JsonObject
            SourceFormat.LOREBOOK_V2, SourceFormat.WORLD_INFO -> tree
            else -> null
        }
        return when (val entries = book?.get("entries")) {
            is JsonArray -> entries.size
            is JsonObject -> if (sourceFormat == SourceFormat.WORLD_INFO) entries.size else null
            else -> null
        }
    }

    val fields: CharacterFields get() {
        val data = when (sourceFormat) {
            SourceFormat.V1 -> tree
            SourceFormat.V2, SourceFormat.V3 -> tree["data"] as? JsonObject
            else -> null
        }
        fun text(key: String): String? = (data?.get(key) as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        return CharacterFields(text("name"), text("description"), text("personality"),
            text("scenario"), text("first_mes"), text("mes_example"))
    }

    private fun text(obj: JsonObject?, key: String): String? =
        (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

sealed interface ParseResult {
    data class Success(val document: CharacterDocument) : ParseResult
    data class Failure(val source: SourceEnvelope?, val issues: List<Diagnostic>) : ParseResult
}

sealed interface JsonPatch {
    val path: String
    data class Add(override val path: String, val value: JsonElement) : JsonPatch
    data class Replace(override val path: String, val value: JsonElement) : JsonPatch
    data class Remove(override val path: String) : JsonPatch
}

sealed interface PatchResult {
    data class Success(val document: CharacterDocument) : PatchResult
    data class Failure(val issues: List<Diagnostic>) : PatchResult
}

sealed interface ExportResult {
    class Success(bytes: ByteArray) : ExportResult {
        private val content = bytes.copyOf()
        val bytes: ByteArray get() = content.copyOf()
    }
    data class Failure(val issues: List<Diagnostic>) : ExportResult
}

internal fun snapshot(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { (_, child) -> snapshot(child) })
    is JsonArray -> JsonArray(value.map(::snapshot))
    else -> value // JsonPrimitive and JsonNull are immutable.
}

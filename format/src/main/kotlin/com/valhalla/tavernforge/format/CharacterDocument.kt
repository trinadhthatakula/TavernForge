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

    val fields: CharacterFields get() {
        val data = if (sourceFormat == SourceFormat.V1) tree else tree["data"] as? JsonObject
        fun text(key: String): String? = (data?.get(key) as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        return CharacterFields(text("name"), text("description"), text("personality"),
            text("scenario"), text("first_mes"), text("mes_example"))
    }
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

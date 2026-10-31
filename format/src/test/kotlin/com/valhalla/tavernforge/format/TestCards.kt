package com.valhalla.tavernforge.format

import com.valhalla.tavernforge.core.model.Severity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue

internal fun fixtureBytes(name: String = "st-v2-empty-fields"): ByteArray =
    checkNotNull(object {}.javaClass.getResourceAsStream("/fixtures/$name.json")) {
        "Missing attributable fixture $name"
    }.use { it.readBytes() }

internal fun fixtureRoot(): JsonObject =
    Json.parseToJsonElement(fixtureBytes().decodeToString()) as JsonObject

internal fun JsonObject.withField(name: String, value: JsonElement): JsonObject =
    JsonObject(toMutableMap().also { it[name] = value })

internal fun JsonObject.withoutField(name: String): JsonObject =
    JsonObject(toMutableMap().also { it.remove(name) })

internal fun cardWithData(transform: (JsonObject) -> JsonObject): ByteArray {
    val root = fixtureRoot()
    return root.withField("data", transform(root.getValue("data") as JsonObject))
        .toString().encodeToByteArray()
}

internal fun CharacterCardEngine.read(bytes: ByteArray): CharacterDocument =
    when (val result = parse(bytes)) {
        is ParseResult.Success -> result.document
        is ParseResult.Failure -> error("Expected parse success: ${result.issues}")
    }

internal fun CharacterCardEngine.read(json: String): CharacterDocument = read(json.encodeToByteArray())

internal fun CharacterCardEngine.exported(document: CharacterDocument): ByteArray =
    when (val result = exportV2(document)) {
        is ExportResult.Success -> result.bytes
        is ExportResult.Failure -> error("Expected strict V2 export: ${result.issues}")
    }

internal fun CharacterCardEngine.changed(
    document: CharacterDocument,
    vararg operations: JsonPatch,
): CharacterDocument = when (val result = patch(document, document.revision, operations.toList())) {
    is PatchResult.Success -> result.document
    is PatchResult.Failure -> error("Expected patch success: ${result.issues}")
}

internal fun assertParseFailure(engine: CharacterCardEngine, bytes: ByteArray): ParseResult.Failure {
    val result = engine.parse(bytes)
    assertTrue("Expected a structured parse failure for ${bytes.size} bytes, got $result", result is ParseResult.Failure)
    return (result as ParseResult.Failure).also {
        assertTrue("Failure must explain its error", it.issues.any { issue -> issue.severity == Severity.ERROR })
    }
}

internal fun assertParseFailure(engine: CharacterCardEngine, json: String): ParseResult.Failure =
    assertParseFailure(engine, json.encodeToByteArray())

package com.valhalla.tavernforge.format

import com.valhalla.tavernforge.core.model.Severity
import com.valhalla.tavernforge.core.model.SourceFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independent expectations from the pinned V1/V2/V3 specifications, not generated DTO defaults. */
class JsonVersionValidationTest {
    private val engine = CharacterCardEngine()
    private val v1Fields = listOf("name", "description", "personality", "scenario", "first_mes", "mes_example")
    private val v3Strings = v1Fields + listOf("creator_notes", "system_prompt", "post_history_instructions", "creator", "character_version")
    private val v3Required = v3Strings + listOf("alternate_greetings", "tags", "extensions", "group_only_greetings")

    private fun root(name: String = "spec-v3-minimal") = Json.parseToJsonElement(fixtureBytes(name).decodeToString()) as JsonObject
    private fun v3Data(change: (JsonObject) -> JsonObject): String {
        val root = root()
        return root.withField("data", change(root.getValue("data") as JsonObject)).toString()
    }
    private fun invalid(json: String, path: String? = null) {
        val document = engine.read(json)
        val report = engine.validate(document)
        assertFalse("Expected schema failure for $path: ${report.issues}", report.isValid)
        if (path != null) assertTrue("Missing error at $path: ${report.issues}", report.issues.any { it.path == path && it.severity == Severity.ERROR })
        assertTrue("Invalid input must not produce strict export", engine.export(document) is ExportResult.Failure)
    }
    private fun valid(json: String): CharacterDocument = engine.read(json).also {
        assertTrue("Expected strict validity: ${engine.validate(it).issues}", engine.validate(it).isValid)
    }

    @Test fun genericDispatchAcceptsEachCharacterVersionWithoutMigration() {
        mapOf("st-v1-empty-fields" to SourceFormat.V1, "st-v2-empty-fields" to SourceFormat.V2,
            "spec-v3-minimal" to SourceFormat.V3, "spec-v3-rich" to SourceFormat.V3).forEach { (fixture, format) ->
            val bytes = fixtureBytes(fixture)
            val document = engine.read(bytes)
            assertEquals(format, document.sourceFormat)
            assertTrue("$fixture: ${engine.validate(document).issues}", engine.validate(document).isValid)
            assertArrayEquals(bytes, engine.exportOriginalFormat(document))
        }
    }

    @Test fun v1RequiresAllSixStringsButAllowsEmptyValues() {
        val v1 = root("st-v1-empty-fields")
        v1Fields.forEach { field ->
            invalid(v1.withoutField(field).toString())
            listOf<JsonElement>(JsonNull, JsonPrimitive(0), JsonPrimitive(false), JsonArray(emptyList()), JsonObject(emptyMap())).forEach {
                invalid(v1.withField(field, it).toString(), "/$field")
            }
        }
    }

    @Test fun knownFieldEditsPreserveUnknownValuesAndVersionAcrossV1V2V3() {
        listOf("st-v1-empty-fields", "st-v2-empty-fields", "spec-v3-rich").forEach { fixture ->
            val base = fixtureBytes(fixture).decodeToString().trimEnd().dropLast(1) +
                ",\"unknown\":{\"null\":null,\"number\":1.2300e+09,\"large\":123456789012345678901234567890,\"flag\":false}}"
            val original = engine.read(base)
            val path = if (original.sourceFormat == SourceFormat.V1) "/name" else "/data/name"
            val changed = engine.changed(original, JsonPatch.Replace(path, JsonPrimitive("Renamed")))
            val bytes = engine.exportOriginalFormat(changed)
            val reread = engine.read(bytes)
            assertEquals(original.sourceFormat, reread.sourceFormat)
            assertEquals("Renamed", reread.fields.name)
            assertEquals(original.raw["unknown"], reread.raw["unknown"])
            assertEquals(original.raw["spec"], reread.raw["spec"])
            assertEquals(original.raw["spec_version"], reread.raw["spec_version"])
            assertTrue(bytes.decodeToString().contains("1.2300e+09"))
            assertTrue(bytes.decodeToString().contains("123456789012345678901234567890"))
            if (original.sourceFormat == SourceFormat.V3) {
                val before = original.raw["data"] as JsonObject
                val after = reread.raw["data"] as JsonObject
                listOf("assets", "source", "creator_notes_multilingual", "character_book", "extensions", "creation_date", "modification_date").forEach {
                    assertEquals("V3 $it changed during unrelated edit", before[it], after[it])
                }
            }
        }
    }

    @Test fun explicitV2WrappersRemainStrictAndDoNotConvertV1OrV3() {
        listOf("st-v1-empty-fields", "spec-v3-minimal").forEach {
            val document = engine.read(fixtureBytes(it))
            assertFalse(engine.validateV2(document).isValid)
            assertTrue(engine.exportV2(document) is ExportResult.Failure)
            assertTrue(engine.export(document) is ExportResult.Success)
        }
    }

    @Test fun allInheritedV3FieldsAndGroupGreetingsAreRequiredAndNonNull() {
        v3Required.forEach { field ->
            invalid(v3Data { it.withoutField(field) }, "/data/$field")
            invalid(v3Data { it.withField(field, JsonNull) }, "/data/$field")
        }
    }

    @Test fun v3RequiredFieldTypesAndArrayElementsAreChecked() {
        v3Strings.forEach { field -> invalid(v3Data { it.withField(field, JsonPrimitive(7)) }, "/data/$field") }
        listOf("tags", "alternate_greetings", "group_only_greetings").forEach { field ->
            invalid(v3Data { it.withField(field, JsonPrimitive("not array")) }, "/data/$field")
            listOf<JsonElement>(JsonNull, JsonPrimitive(1), JsonPrimitive(false)).forEach { wrong ->
                invalid(v3Data { it.withField(field, JsonArray(listOf(wrong))) }, "/data/$field/0")
            }
        }
        invalid(v3Data { it.withField("extensions", JsonArray(emptyList())) }, "/data/extensions")
    }

    @Test fun v3EnvelopeOnlyHelperIsDetectableButCannotStrictlyExport() {
        val document = engine.read(fixtureBytes("st-v3-envelope-only"))
        assertEquals(SourceFormat.V3, document.sourceFormat)
        assertFalse(engine.validate(document).isValid)
        assertTrue(engine.export(document) is ExportResult.Failure)
    }

    @Test fun unknownAndFutureVersionDocumentsStayInspectableButBlockStrictExport() {
        listOf<JsonElement>(JsonNull, JsonPrimitive(3), JsonPrimitive("3"), JsonPrimitive("3.1"), JsonPrimitive("4.0")).forEach { version ->
            val candidate = root().withField("spec_version", version)
            val document = engine.read(candidate.toString())
            assertEquals(candidate, document.raw)
            invalid(candidate.toString())
        }
        listOf("chara_card_v4", "lorebook_v4", "other").forEach { spec -> invalid(root().withField("spec", JsonPrimitive(spec)).toString()) }
        listOf("spec", "spec_version", "data").forEach { invalid(root().withoutField(it).toString()) }
    }

    @Test fun v3OptionalMetadataTypesAreValidatedWithoutRequiringPresence() {
        valid(v3Data { it })
        mapOf<String, JsonElement>("nickname" to JsonPrimitive(1), "creator_notes_multilingual" to JsonArray(emptyList()),
            "source" to JsonPrimitive("id"), "creation_date" to JsonPrimitive("0"), "modification_date" to JsonPrimitive(false),
            "assets" to JsonObject(emptyMap()), "character_book" to JsonNull).forEach { (field, value) ->
            invalid(v3Data { it.withField(field, value) }, "/data/$field")
        }
        listOf("nickname", "creator_notes_multilingual", "source", "creation_date", "modification_date", "assets").forEach { field ->
            invalid(v3Data { it.withField(field, JsonNull) }, "/data/$field")
        }
        invalid(v3Data { it.withField("source", JsonArray(listOf(JsonNull))) }, "/data/source/0")
        valid(v3Data { it.withField("creation_date", JsonPrimitive(0)).withField("modification_date", JsonPrimitive(0.125)) })
    }

    @Test fun multilingualNotesRequireIsoLanguageKeysAndStringValues() {
        invalid(v3Data { it.withField("creator_notes_multilingual", JsonObject(mapOf("en" to JsonPrimitive(1)))) }, "/data/creator_notes_multilingual/en")
        listOf("en-US", "english", "zz").forEach { code ->
            invalid(v3Data { it.withField("creator_notes_multilingual", JsonObject(mapOf(code to JsonPrimitive("notes")))) })
        }
        valid(v3Data { it.withField("creator_notes_multilingual", JsonObject(mapOf("en" to JsonPrimitive(""), "te" to JsonPrimitive("గమనిక")))) })
    }

    @Test fun assetRecordsRequireFourStringsAndValidExtensionShape() {
        val asset = Json.parseToJsonElement("""{"type":"icon","uri":"https://example.invalid/a.png","name":"main","ext":"png"}""") as JsonObject
        fun input(value: JsonElement) = v3Data { it.withField("assets", JsonArray(listOf(value))) }
        listOf("type", "uri", "name", "ext").forEach { field ->
            invalid(input(asset.withoutField(field)), "/data/assets/0/$field")
            invalid(input(asset.withField(field, JsonNull)), "/data/assets/0/$field")
        }
        invalid(input(JsonNull), "/data/assets/0")
        listOf("PNG", ".png", "a/b").forEach { ext -> invalid(input(asset.withField("ext", JsonPrimitive(ext))), "/data/assets/0/ext") }
    }

    @Test fun mainIconAndBackgroundCardinalityAreCheckedWithoutFetchingAssets() {
        fun asset(type: String, name: String) = Json.parseToJsonElement("""{"type":"$type","uri":"ccdefault:","name":"$name","ext":"png"}""")
        fun input(vararg assets: JsonElement) = v3Data { it.withField("assets", JsonArray(assets.toList())) }
        valid(input(asset("icon", "alternate")))
        valid(input(asset("icon", "main"), asset("icon", "alternate")))
        invalid(input(asset("icon", "one"), asset("icon", "two")))
        invalid(input(asset("icon", "main"), asset("icon", "main")))
        valid(input(asset("background", "one"), asset("background", "two")))
        invalid(input(asset("background", "main"), asset("background", "main")))
    }

    @Test fun discriminatorEditsCannotMigrateOrRelabelAnyCharacterVersion() {
        listOf("st-v1-empty-fields", "st-v2-empty-fields", "spec-v3-minimal").forEach { fixture ->
            val document = engine.read(fixtureBytes(fixture))
            val result = engine.patch(document, document.revision, listOf(JsonPatch.Add("/spec", JsonPrimitive("chara_card_v4"))))
            assertTrue(result is PatchResult.Failure)
            assertArrayEquals(fixtureBytes(fixture), engine.exportOriginalFormat(document))
        }
    }
}

internal fun CharacterCardEngine.exportOriginalFormat(document: CharacterDocument): ByteArray =
    when (val result = export(document)) {
        is ExportResult.Success -> result.bytes
        is ExportResult.Failure -> error("Expected strict source-format export: ${result.issues}")
    }

package com.valhalla.tavernforge.format

import com.valhalla.tavernforge.core.model.Severity
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.core.model.ValidationPhase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expectations read independently from the pinned official spec_v2.md, not parser DTO defaults. */
class V2ValidationTest {
    private val engine = CharacterCardEngine()
    private val requiredStrings = listOf(
        "name", "description", "personality", "scenario", "first_mes", "mes_example",
        "creator_notes", "system_prompt", "post_history_instructions", "creator", "character_version",
    )
    private val requiredData = requiredStrings + listOf("alternate_greetings", "tags", "extensions")

    private fun assertInvalid(bytes: ByteArray, path: String? = null) {
        val document = engine.read(bytes)
        val report = engine.validateV2(document)
        assertFalse("Expected strict V2 failure at $path", report.isValid)
        if (path != null) assertTrue("Missing schema diagnostic $path: ${report.issues}", report.issues.any {
            it.path == path && it.severity == Severity.ERROR && it.phase == ValidationPhase.SCHEMA
        })
        assertTrue("Invalid document produced strict export bytes", engine.exportV2(document) is ExportResult.Failure)
    }

    private fun assertBookInvalid(book: JsonObject, path: String) =
        assertInvalid(cardWithData { it.withField("character_book", book) }, path)

    private fun minimalEntry(): JsonObject = Json.parseToJsonElement("""{"keys":[],"content":"","extensions":{},"enabled":false,"insertion_order":0}""") as JsonObject
    private fun minimalBook(entry: JsonObject? = null): JsonObject = JsonObject(mapOf(
        "extensions" to JsonObject(emptyMap()),
        "entries" to JsonArray(listOfNotNull(entry)),
    ))

    @Test
    fun upstreamEmptyAllowedV2HelperIsStrictlyValid() {
        val document = engine.read(fixtureBytes())
        assertTrue(engine.validateV2(document).isValid)
        assertArrayEquals(fixtureBytes(), engine.exported(document))
    }

    @Test
    fun everyRequiredDataFieldMustBePresent() {
        requiredData.forEach { field -> assertInvalid(cardWithData { it.withoutField(field) }, "/data/$field") }
    }

    @Test
    fun requiredFieldsCannotBeExplicitNull() {
        requiredData.forEach { field -> assertInvalid(cardWithData { it.withField(field, JsonNull) }, "/data/$field") }
    }

    @Test
    fun stringFieldsRejectNumbersBooleansArraysAndObjects() {
        requiredStrings.forEach { field ->
            listOf<JsonElement>(JsonPrimitive(1), JsonPrimitive(false), JsonArray(emptyList()), JsonObject(emptyMap())).forEach { value ->
                assertInvalid(cardWithData { it.withField(field, value) }, "/data/$field")
            }
        }
    }

    @Test
    fun requiredArraysAndTheirElementsHaveExactTypes() {
        listOf("tags", "alternate_greetings").forEach { field ->
            assertInvalid(cardWithData { it.withField(field, JsonPrimitive("not an array")) }, "/data/$field")
            listOf<JsonElement>(JsonNull, JsonPrimitive(0), JsonPrimitive(false), JsonObject(emptyMap())).forEach { element ->
                assertInvalid(cardWithData { it.withField(field, JsonArray(listOf(element))) }, "/data/$field/0")
            }
        }
        assertInvalid(cardWithData { it.withField("extensions", JsonArray(emptyList())) }, "/data/extensions")
    }

    @Test
    fun emptyAndUnusualStringTagsAreStillValid() {
        val document = engine.read(cardWithData { it.withField("tags", Json.parseToJsonElement("""["","重复","tag/with~symbols"," "]""")) })
        assertTrue(engine.validateV2(document).isValid)
    }

    @Test
    fun envelopeDiscriminatorAndVersionHaveExactRequiredValues() {
        listOf("spec", "spec_version", "data").forEach { field ->
            assertInvalid(fixtureRoot().withoutField(field).toString().encodeToByteArray())
        }
        listOf<JsonElement>(JsonNull, JsonPrimitive(2.0), JsonPrimitive("2"), JsonPrimitive("2.1")).forEach { value ->
            assertInvalid(fixtureRoot().withField("spec_version", value).toString().encodeToByteArray())
        }
        listOf<JsonElement>(JsonArray(emptyList()), JsonNull, JsonPrimitive("wrong")).forEach { value ->
            assertInvalid(fixtureRoot().withField("data", value).toString().encodeToByteArray())
        }
    }

    @Test
    fun characterBookMayBeAbsentButMustHaveCorrectTypeWhenPresent() {
        assertTrue(engine.validateV2(engine.read(fixtureBytes())).isValid)
        listOf<JsonElement>(JsonNull, JsonArray(emptyList()), JsonPrimitive("book")).forEach { value ->
            assertInvalid(cardWithData { it.withField("character_book", value) }, "/data/character_book")
        }
    }

    @Test
    fun minimalBookAndEntryDoNotRequireOptionalCreativeFields() {
        listOf(minimalBook(), minimalBook(minimalEntry())).forEach { book ->
            val document = engine.read(cardWithData { it.withField("character_book", book) })
            assertTrue("Only required book/entry fields should suffice: ${engine.validateV2(document).issues}", engine.validateV2(document).isValid)
        }
    }

    @Test
    fun bookRequiresExtensionsObjectAndEntryArray() {
        listOf("extensions", "entries").forEach { field ->
            assertBookInvalid(minimalBook().withoutField(field), "/data/character_book/$field")
        }
        assertBookInvalid(minimalBook().withField("entries", JsonObject(emptyMap())), "/data/character_book/entries")
        assertBookInvalid(minimalBook().withField("extensions", JsonNull), "/data/character_book/extensions")
        assertBookInvalid(minimalBook().withField("entries", JsonArray(listOf(JsonNull))), "/data/character_book/entries/0")
    }

    @Test
    fun everyEntryRequiredFieldIsCheckedForAbsenceAndWrongType() {
        val wrong = mapOf<String, JsonElement>(
            "keys" to JsonPrimitive("key"),
            "content" to JsonPrimitive(2),
            "extensions" to JsonArray(emptyList()),
            "enabled" to JsonPrimitive("false"),
            "insertion_order" to JsonPrimitive("0"),
        )
        wrong.forEach { (field, value) ->
            assertBookInvalid(minimalBook(minimalEntry().withoutField(field)), "/data/character_book/entries/0/$field")
            assertBookInvalid(minimalBook(minimalEntry().withField(field, value)), "/data/character_book/entries/0/$field")
        }
        assertBookInvalid(minimalBook(minimalEntry().withField("keys", JsonArray(listOf(JsonNull)))), "/data/character_book/entries/0/keys/0")
    }

    @Test
    fun optionalBookPropertiesAreValidatedWhenPresent() {
        val wrong = mapOf<String, JsonElement>(
            "name" to JsonPrimitive(1), "description" to JsonPrimitive(false),
            "scan_depth" to JsonPrimitive("1"), "token_budget" to JsonNull,
            "recursive_scanning" to JsonPrimitive(0),
        )
        wrong.forEach { (field, value) -> assertBookInvalid(minimalBook().withField(field, value), "/data/character_book/$field") }
    }

    @Test
    fun optionalEntryTypesAndPositionEnumAreValidatedWhenPresent() {
        val wrong = mapOf<String, JsonElement>(
            "case_sensitive" to JsonPrimitive("true"), "name" to JsonPrimitive(1),
            "priority" to JsonPrimitive("1"), "id" to JsonNull, "comment" to JsonPrimitive(false),
            "selective" to JsonPrimitive(1), "secondary_keys" to JsonPrimitive("keys"),
            "constant" to JsonPrimitive(0), "position" to JsonPrimitive("at_depth"),
        )
        wrong.forEach { (field, value) -> assertBookInvalid(minimalBook(minimalEntry().withField(field, value)), "/data/character_book/entries/0/$field") }
        assertBookInvalid(minimalBook(minimalEntry().withField("secondary_keys", JsonArray(listOf(JsonPrimitive(1))))), "/data/character_book/entries/0/secondary_keys/0")
    }

    @Test
    fun numericFieldsAreNumbersWithoutInventedIntegerOnlyRequirements() {
        val entry = minimalEntry().withField("insertion_order", JsonPrimitive(0.5))
            .withField("priority", JsonPrimitive(-1.5)).withField("id", JsonPrimitive(2.5))
            .withField("position", JsonPrimitive("before_char"))
        val book = minimalBook(entry).withField("scan_depth", JsonPrimitive(1.5))
            .withField("token_budget", JsonPrimitive(100.5))
        val document = engine.read(cardWithData { it.withField("character_book", book) })
        assertTrue(engine.validateV2(document).isValid)
    }

    @Test
    fun unknownBookAndEntryFieldsSurviveASingleKnownFieldEdit() {
        val entry = minimalEntry().withField("future", Json.parseToJsonElement("""{"values":[null,false,1.2300]}"""))
        val book = minimalBook(entry).withField("future_world", JsonNull)
        val original = engine.read(cardWithData { it.withField("character_book", book) })
        val edited = engine.changed(original, JsonPatch.Replace("/data/name", JsonPrimitive("Changed")))
        val after = engine.read(engine.exported(edited))
        assertEquals(book, (after.raw["data"] as JsonObject)["character_book"])
    }

    @Test
    fun projectionDoesNotNormalizeMissingNullAndWrongTypesIntoRawValues() {
        listOf(
            cardWithData { it.withoutField("name") },
            cardWithData { it.withField("name", JsonNull) },
            cardWithData { it.withField("name", JsonPrimitive(1)) },
        ).forEach { bytes ->
            val document = engine.read(bytes)
            assertNull(document.fields.name)
            assertEquals(Json.parseToJsonElement(bytes.decodeToString()), document.raw)
        }
    }

    @Test
    fun unsupportedVersionsAreInspectableWithoutFalseV2ExportSupport() {
        listOf("st-v1-empty-fields" to SourceFormat.V1, "st-v3-envelope-only" to SourceFormat.V3).forEach { (fixture, format) ->
            val document = engine.read(fixtureBytes(fixture))
            assertEquals(format, document.sourceFormat)
            assertFalse(engine.validateV2(document).isValid)
            assertTrue(engine.exportV2(document) is ExportResult.Failure)
            assertArrayEquals(fixtureBytes(fixture), document.source.bytes())
        }
    }

    @Test
    fun unknownSpecIsNotMisclassifiedAsV1ByFlatFields() {
        val v1 = Json.parseToJsonElement(fixtureBytes("st-v1-empty-fields").decodeToString()) as JsonObject
        val document = engine.read(v1.withField("spec", JsonPrimitive("future_card")).toString())
        assertEquals(SourceFormat.UNKNOWN, document.sourceFormat)
        assertTrue(engine.exportV2(document) is ExportResult.Failure)
    }
}

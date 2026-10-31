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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native World Info is a keyed object; a CharacterBook is an array. Neither is converted here. */
class LorebookJsonValidationTest {
    private val engine = CharacterCardEngine()
    private fun objectOf(json: String) = Json.parseToJsonElement(json) as JsonObject
    private fun entry() = objectOf("""{"keys":[],"content":"","extensions":{},"enabled":false,"insertion_order":0,"use_regex":false}""")
    private fun book(entry: JsonElement? = null): JsonObject = JsonObject(mapOf("extensions" to JsonObject(emptyMap()), "entries" to JsonArray(listOfNotNull(entry))))
    private fun standalone(book: JsonElement) = JsonObject(mapOf("spec" to JsonPrimitive("lorebook_v3"), "data" to book))
    private fun embedded(book: JsonElement): JsonObject {
        val card = objectOf(fixtureBytes("spec-v3-minimal").decodeToString())
        return card.withField("data", (card.getValue("data") as JsonObject).withField("character_book", book))
    }
    private fun invalid(root: JsonObject, path: String? = null) {
        val document = engine.read(root.toString())
        val report = engine.validate(document)
        assertFalse("Expected strict failure at $path: ${report.issues}", report.isValid)
        if (path != null) assertTrue("Missing error at $path: ${report.issues}", report.issues.any { it.path == path && it.severity == Severity.ERROR })
        assertTrue(engine.export(document) is ExportResult.Failure)
    }
    private fun valid(root: JsonObject): CharacterDocument = engine.read(root.toString()).also {
        assertTrue("Expected strict validity: ${engine.validate(it).issues}", engine.validate(it).isValid)
    }

    @Test fun standaloneFixturesRoundTripExactlyInTheirOwnFormats() {
        mapOf("spec-character-book-v2" to SourceFormat.LOREBOOK_V2, "spec-lorebook-v3" to SourceFormat.LOREBOOK_V3,
            "st-world-info-synthetic" to SourceFormat.WORLD_INFO).forEach { (fixture, format) ->
            val bytes = fixtureBytes(fixture)
            val document = engine.read(bytes)
            assertEquals(format, document.sourceFormat)
            assertTrue("$fixture: ${engine.validate(document).issues}", engine.validate(document).isValid)
            assertEquals(1, document.loreEntryCount)
            assertArrayEquals(bytes, engine.exportOriginalFormat(document))
            assertFalse(engine.validateV2(document).isValid)
        }
    }

    @Test fun lorebookContentEditsKeepSourceStructureAndUnknownData() {
        mapOf("spec-character-book-v2" to "/entries/0/content", "spec-lorebook-v3" to "/data/entries/0/content",
            "st-world-info-synthetic" to "/entries/7/content").forEach { (fixture, path) ->
            val original = engine.read(fixtureBytes(fixture))
            val changed = engine.changed(original, JsonPatch.Replace(path, JsonPrimitive("Updated lore.")))
            val bytes = engine.exportOriginalFormat(changed)
            val reread = engine.read(bytes)
            assertEquals(original.sourceFormat, reread.sourceFormat)
            assertEquals(changed.raw, reread.raw)
            assertEquals(original.raw["future_root"], reread.raw["future_root"])
            assertTrue(bytes.decodeToString().contains("1.2300e+09"))
            assertTrue(bytes.decodeToString().contains("123456789012345678901234567890"))
            assertEquals(original.raw["spec"], reread.raw["spec"])
            assertEquals(original.raw["spec_version"], reread.raw["spec_version"])
        }
    }

    @Test fun standaloneV3UsesOfficialEnvelopeWithoutInventedVersionProperty() {
        val document = valid(standalone(book()))
        assertEquals(SourceFormat.LOREBOOK_V3, document.sourceFormat)
        assertNull(document.specVersion)
        invalid(objectOf("""{"spec":"lorebook_v2","data":{"extensions":{},"entries":[]}}"""))
        invalid(objectOf("""{"spec":"lorebook_v3","entries":[]}"""))
        invalid(standalone(JsonNull), "/data")
    }

    @Test fun v3LorebookRequiredFieldsApplyToEmbeddedAndStandaloneForms() {
        listOf<(JsonElement) -> JsonObject>(::standalone, ::embedded).forEach { wrap ->
            val prefix = if (wrap(book()).getValue("spec") == JsonPrimitive("lorebook_v3")) "/data" else "/data/character_book"
            listOf("extensions", "entries").forEach { field ->
                invalid(wrap(book().withoutField(field)), "$prefix/$field")
                invalid(wrap(book().withField(field, JsonNull)), "$prefix/$field")
            }
            listOf("keys", "content", "extensions", "enabled", "insertion_order", "use_regex").forEach { field ->
                invalid(wrap(book(entry().withoutField(field))), "$prefix/entries/0/$field")
                invalid(wrap(book(entry().withField(field, JsonNull))), "$prefix/entries/0/$field")
            }
            valid(wrap(book(entry())))
        }
    }

    @Test fun v3RegexAndStringIdsDoNotAccidentallyUseV2Rules() {
        val v3 = entry().withField("id", JsonPrimitive("entry-alpha"))
        valid(standalone(book(v3)))
        valid(embedded(book(v3)))
        invalid(book(v3), "/entries/0/id")
        invalid(standalone(book(v3.withField("use_regex", JsonPrimitive("false")))), "/data/entries/0/use_regex")
        // V2 does not require use_regex. Optional V3 fields remain optional per the TS contract.
        valid(book(entry().withoutField("use_regex")))
        valid(standalone(book(entry().withField("id", JsonPrimitive(0.5)))))
    }

    @Test fun v3OptionalLorebookTypesAndPositionAreCheckedWhenPresent() {
        mapOf<String, JsonElement>("name" to JsonNull, "description" to JsonPrimitive(false),
            "scan_depth" to JsonPrimitive("1"), "token_budget" to JsonNull, "recursive_scanning" to JsonPrimitive(0)).forEach { (field, value) ->
            invalid(standalone(book().withField(field, value)), "/data/$field")
        }
        mapOf<String, JsonElement>("case_sensitive" to JsonPrimitive(1), "constant" to JsonNull, "name" to JsonPrimitive(false),
            "priority" to JsonPrimitive("1"), "id" to JsonNull, "comment" to JsonPrimitive(4), "selective" to JsonPrimitive("true"),
            "secondary_keys" to JsonPrimitive("a,b"), "position" to JsonPrimitive("at_depth")).forEach { (field, value) ->
            invalid(standalone(book(entry().withField(field, value))), "/data/entries/0/$field")
        }
        invalid(standalone(book(entry().withField("secondary_keys", JsonArray(listOf(JsonNull))))), "/data/entries/0/secondary_keys/0")
    }

    @Test fun nativeWorldInfoDefaultsAreNotInventedAsRequiredFields() {
        listOf("""{"entries":{}}""", """{"entries":{"0":{}}}""").forEach { json ->
            val document = valid(objectOf(json))
            assertEquals(SourceFormat.WORLD_INFO, document.sourceFormat)
            assertArrayEquals(json.encodeToByteArray(), engine.exportOriginalFormat(document))
        }
    }

    @Test fun worldInfoRequiresObjectEntriesWithoutReinterpretingMalformedCardSpecs() {
        invalid(objectOf("""{"entries":{"7":null}}"""), "/entries/7")
        invalid(objectOf("""{"entries":{"7":"bad"}}"""), "/entries/7")
        invalid(objectOf("""{"entries":null}"""))
        invalid(objectOf("""{"spec":"chara_card_v4","entries":{}}"""))
        invalid(objectOf("""{"spec":null,"entries":{}}"""))
        assertEquals(SourceFormat.LOREBOOK_V2, valid(book()).sourceFormat)
    }

    private fun worldEntry(field: String, value: JsonElement) = JsonObject(mapOf("entries" to JsonObject(mapOf("7" to JsonObject(mapOf(field to value))))))

    @Test fun worldInfoKnownTypesAndNullableOverridesAreValidated() {
        listOf("content", "comment", "outletName", "group", "automationId").forEach { field -> invalid(worldEntry(field, JsonPrimitive(1)), "/entries/7/$field") }
        listOf("constant", "vectorized", "selective", "disable", "useProbability", "preventRecursion", "matchScenario").forEach { field ->
            invalid(worldEntry(field, JsonPrimitive("false")), "/entries/7/$field")
        }
        listOf("order", "probability", "depth", "groupWeight").forEach { field -> invalid(worldEntry(field, JsonPrimitive("0")), "/entries/7/$field") }
        listOf("scanDepth", "sticky", "cooldown", "delay").forEach { field ->
            valid(worldEntry(field, JsonNull))
            valid(worldEntry(field, JsonPrimitive(0)))
            invalid(worldEntry(field, JsonPrimitive(false)), "/entries/7/$field")
        }
        listOf("caseSensitive", "matchWholeWords", "useGroupScoring").forEach { field ->
            valid(worldEntry(field, JsonNull))
            valid(worldEntry(field, JsonPrimitive(false)))
            invalid(worldEntry(field, JsonPrimitive(0)), "/entries/7/$field")
        }
    }

    @Test fun worldInfoKeywordListsAndFilterHaveExactTypes() {
        listOf("key", "keysecondary").forEach { field ->
            invalid(worldEntry(field, JsonPrimitive("key")), "/entries/7/$field")
            invalid(worldEntry(field, JsonArray(listOf(JsonNull))), "/entries/7/$field/0")
        }
        invalid(worldEntry("characterFilter", JsonArray(emptyList())), "/entries/7/characterFilter")
        invalid(worldEntry("characterFilter", objectOf("""{"names":[1]}""")), "/entries/7/characterFilter/names/0")
        valid(worldEntry("characterFilter", objectOf("""{"names":[],"tags":[],"isExclude":false,"future":null}""")))
    }

    @Test fun worldInfoNativeEnumsKeepAllPinnedValues() {
        (0..7).forEach { valid(worldEntry("position", JsonPrimitive(it))) }
        (0..3).forEach { valid(worldEntry("selectiveLogic", JsonPrimitive(it))) }
        listOf(-1, 8).forEach { invalid(worldEntry("position", JsonPrimitive(it)), "/entries/7/position") }
        listOf(-1, 4).forEach { invalid(worldEntry("selectiveLogic", JsonPrimitive(it)), "/entries/7/selectiveLogic") }
        invalid(worldEntry("position", JsonPrimitive(0.5)), "/entries/7/position")
        // Floating-point rounding must not turn a non-enum JSON number into a pinned value.
        listOf("7.0000000000000000001", "1e-1000").forEach {
            invalid(objectOf("""{"entries":{"7":{"position":$it}}}"""), "/entries/7/position")
        }
        listOf("7.0", "7e0").forEach { valid(objectOf("""{"entries":{"7":{"position":$it}}}""")) }
    }

    @Test fun changingBetweenNativeAndCharacterBookEntryShapesRequiresConversion() {
        val world = valid(objectOf("""{"entries":{}}"""))
        val characterBook = valid(book())
        val attempts = listOf(
            world to listOf(JsonPatch.Replace("/entries", JsonArray(emptyList())), JsonPatch.Add("/extensions", JsonObject(emptyMap()))),
            characterBook to listOf(JsonPatch.Replace("/entries", JsonObject(emptyMap()))),
        )
        attempts.forEach { (document, patches) ->
            val originalBytes = engine.exportOriginalFormat(document)
            val result = engine.patch(document, document.revision, patches)
            assertTrue("Changing implicit source kind must require conversion", result is PatchResult.Failure)
            assertArrayEquals(originalBytes, engine.exportOriginalFormat(document))
        }
    }

    @Test fun unknownWorldInfoExtensionsArePreservedWithoutSchemaGuessing() {
        val root = objectOf("""{"entries":{"custom/key":{"vendor":null,"unrecognized":{"flag":false,"number":1.2300e+09}}},"future":null}""")
        val document = valid(root)
        val changed = engine.changed(document, JsonPatch.Add("/entries/custom~1key/content", JsonPrimitive("Lore")))
        val reread = engine.read(engine.exportOriginalFormat(changed))
        val before = (root.getValue("entries") as JsonObject).getValue("custom/key") as JsonObject
        val after = (reread.raw.getValue("entries") as JsonObject).getValue("custom/key") as JsonObject
        assertEquals(before["vendor"], after["vendor"])
        assertEquals(before["unrecognized"], after["unrecognized"])
    }
}

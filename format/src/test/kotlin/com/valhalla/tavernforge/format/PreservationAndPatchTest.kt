package com.valhalla.tavernforge.format

import com.valhalla.tavernforge.core.model.JsonLimits
import com.valhalla.tavernforge.core.model.SourceFormat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreservationAndPatchTest {
    private val engine = CharacterCardEngine()

    @Test
    fun untouchedV2ExportsTheExactOriginalBytes() {
        val bytes = " \n${fixtureBytes().decodeToString()}\t ".encodeToByteArray()
        val document = engine.read(bytes)
        assertTrue(engine.validateV2(document).isValid)
        assertEquals(SourceFormat.V2, document.sourceFormat)
        assertArrayEquals(bytes, engine.exported(document))
    }

    @Test
    fun inputSourceAndExportBytesAreDefensivelyOwned() {
        val original = fixtureBytes()
        val mutableInput = original.copyOf()
        val document = engine.read(mutableInput)
        mutableInput.fill(0)
        document.source.bytes().fill(0)
        val result = engine.exportV2(document) as ExportResult.Success
        result.bytes.fill(0)
        assertArrayEquals(original, result.bytes)
        assertArrayEquals(original, document.source.bytes())
    }

    @Test
    fun editingKnownFieldPreservesAllUnknownSiblingsAndNumericLexemes() {
        val extension = Json.parseToJsonElement("""{"large":9007199254740993,"decimal":1.2300,"exponent":1e+09,"negativeZero":-0,"null":null,"nested":[false,{"中文":"🚀"}]}""")
        val bytes = cardWithData { it.withField("extensions", JsonObject(mapOf("vendor" to extension))) }
        val root = Json.parseToJsonElement(bytes.decodeToString()) as JsonObject
        val source = root.withField("unknownRoot", JsonArray(listOf(JsonNull, JsonPrimitive(7))))
            .toString().encodeToByteArray()
        val document = engine.read(source)
        val changed = engine.changed(document, JsonPatch.Replace("/data/name", JsonPrimitive("Mira")))
        val beforeData = document.raw.getValue("data") as JsonObject
        val afterData = changed.raw.getValue("data") as JsonObject
        assertEquals(beforeData.withoutField("name"), afterData.withoutField("name"))
        assertEquals(document.raw["unknownRoot"], changed.raw["unknownRoot"])
        assertEquals("Mira", changed.fields.name)
        assertEquals("", document.fields.name)
        assertArrayEquals(source, changed.source.bytes())
        val output = engine.exported(changed).decodeToString()
        listOf("9007199254740993", "1.2300", "1e+09", "-0").forEach {
            assertTrue("Unknown number $it lost its lexeme", output.contains(it))
        }
        assertNotEquals(document.revision, changed.revision)
    }

    @Test
    fun absentNullEmptyAndFalseRemainDistinctAcrossAnEdit() {
        val ext = Json.parseToJsonElement("""{"null":null,"empty":"","array":[],"object":{},"boolean":false,"number":0}""") as JsonObject
        val document = engine.read(cardWithData { it.withField("extensions", ext) })
        val changed = engine.changed(document, JsonPatch.Replace("/data/name", JsonPrimitive("Edited")))
        val resultExt = ((engine.read(engine.exported(changed)).raw["data"] as JsonObject)["extensions"] as JsonObject)
        assertEquals(ext, resultExt)
        assertFalse(resultExt.containsKey("missing"))
        assertTrue(resultExt.containsKey("null"))
        assertEquals(JsonNull, resultExt["null"])
    }

    @Test
    fun patchValueBackingCollectionsCannotMutateSavedDocument() {
        val backing = mutableMapOf<String, kotlinx.serialization.json.JsonElement>("kept" to JsonPrimitive("before"))
        val document = engine.read(fixtureBytes())
        val changed = engine.changed(document, JsonPatch.Add("/data/extensions/vendor", JsonObject(backing)))
        backing["kept"] = JsonPrimitive("after")
        backing["new"] = JsonNull
        val vendor = ((changed.raw["data"] as JsonObject)["extensions"] as JsonObject)["vendor"] as JsonObject
        assertEquals(JsonObject(mapOf("kept" to JsonPrimitive("before"))), vendor)
    }

    @Test
    fun staleRevisionRejectsAnEditWithoutChangingEitherSnapshot() {
        val original = engine.read(fixtureBytes())
        val changed = engine.changed(original, JsonPatch.Replace("/data/name", JsonPrimitive("First edit")))
        val attempt = engine.patch(changed, original.revision, listOf(JsonPatch.Replace("/data/name", JsonPrimitive("Stale edit"))))
        assertTrue(attempt is PatchResult.Failure)
        assertEquals("First edit", changed.fields.name)
        assertEquals("", original.fields.name)
    }

    @Test
    fun failedMultiOperationPatchIsAtomic() {
        val original = engine.read(fixtureBytes())
        val before = engine.exported(original)
        val result = engine.patch(original, original.revision, listOf(
            JsonPatch.Replace("/data/name", JsonPrimitive("Should not escape")),
            JsonPatch.Replace("/data/does-not-exist", JsonPrimitive("invalid")),
        ))
        assertTrue(result is PatchResult.Failure)
        assertArrayEquals(before, engine.exported(original))
        assertEquals("", original.fields.name)
    }

    @Test
    fun jsonPointerEscapesAddressLiteralSlashAndTildeNames() {
        val original = engine.read(cardWithData { data ->
            data.withField("extensions", Json.parseToJsonElement("""{"a/b":{"~key":"old"},"~1":"literal"}"""))
        })
        val changed = engine.changed(original,
            JsonPatch.Replace("/data/extensions/a~1b/~0key", JsonPrimitive("new")),
            JsonPatch.Replace("/data/extensions/~01", JsonPrimitive("escaped once")),
        )
        val ext = (changed.raw["data"] as JsonObject)["extensions"] as JsonObject
        assertEquals(JsonPrimitive("new"), (ext["a/b"] as JsonObject)["~key"])
        assertEquals(JsonPrimitive("escaped once"), ext["~1"])
    }

    @Test
    fun arrayInsertAppendReplaceAndRemoveKeepTheIntendedOrder() {
        val original = engine.read(cardWithData { it.withField("tags", Json.parseToJsonElement("""["a","c"]""")) })
        val changed = engine.changed(original,
            JsonPatch.Add("/data/tags/1", JsonPrimitive("b")),
            JsonPatch.Add("/data/tags/-", JsonPrimitive("d")),
            JsonPatch.Replace("/data/tags/0", JsonPrimitive("A")),
            JsonPatch.Remove("/data/tags/2"),
        )
        assertEquals(Json.parseToJsonElement("""["A","b","d"]"""), (changed.raw["data"] as JsonObject)["tags"])
        assertEquals(Json.parseToJsonElement("""["a","c"]"""), (original.raw["data"] as JsonObject)["tags"])
    }

    @Test
    fun missingPathsMalformedPointersAndInvalidArrayIndicesAreRejected() {
        val original = engine.read(cardWithData { it.withField("tags", Json.parseToJsonElement("""["one"]""")) })
        val invalid = listOf<JsonPatch>(
            JsonPatch.Replace("data/name", JsonPrimitive("x")),
            JsonPatch.Add("/missing/child", JsonPrimitive("x")),
            JsonPatch.Replace("/data/tags/01", JsonPrimitive("x")),
            JsonPatch.Add("/data/tags/2", JsonPrimitive("x")),
            JsonPatch.Remove("/data/tags/1"),
            JsonPatch.Replace("/data/tags/-", JsonPrimitive("x")),
            JsonPatch.Remove("/data/tags/-"),
            JsonPatch.Replace("/data/tags/-1", JsonPrimitive("x")),
            JsonPatch.Add("/data/extensions/bad~2key", JsonPrimitive("x")),
        )
        invalid.forEach { operation ->
            assertTrue("Accepted invalid operation $operation", engine.patch(original, original.revision, listOf(operation)) is PatchResult.Failure)
        }
    }

    @Test
    fun explicitRemovalOnlyDeletesTheRequestedUnknownField() {
        val original = engine.read(cardWithData { it.withField("extensions", Json.parseToJsonElement("""{"remove":1,"keep":{"x":null}}""")) })
        val changed = engine.changed(original, JsonPatch.Remove("/data/extensions/remove"))
        assertEquals(Json.parseToJsonElement("""{"keep":{"x":null}}"""), (changed.raw["data"] as JsonObject)["extensions"])
    }

    @Test
    fun patchOperationBudgetRejectsOversizedBatches() {
        val constrained = CharacterCardEngine(JsonLimits(maxPatchOperations = 1))
        val document = constrained.read(fixtureBytes())
        val result = constrained.patch(document, document.revision, listOf(
            JsonPatch.Replace("/data/name", JsonPrimitive("one")),
            JsonPatch.Replace("/data/description", JsonPrimitive("two")),
        ))
        assertTrue(result is PatchResult.Failure)
        assertArrayEquals(fixtureBytes(), constrained.exported(document))
    }

    @Test
    fun patchCannotBypassDocumentResourceBudgets() {
        val originalBytes = fixtureBytes()
        val constrained = CharacterCardEngine(JsonLimits(maxBytes = originalBytes.size + 20, maxStringChars = 32, maxDepth = 5))
        val document = constrained.read(originalBytes)
        val invalidValues = listOf(
            JsonPrimitive("x".repeat(33)),
            Json.parseToJsonElement("""{"a":{"b":{"c":{"d":0}}}}"""),
            JsonArray(List(100) { JsonPrimitive("1234567890") }),
        )
        invalidValues.forEach { value ->
            val result = constrained.patch(document, document.revision, listOf(JsonPatch.Add("/data/extensions/vendor", value)))
            assertTrue("Patch bypassed configured budgets", result is PatchResult.Failure)
        }
    }

    @Test
    fun invalidKnownFieldCanBeReviewedButNeverStrictlyExported() {
        val original = engine.read(fixtureBytes())
        val result = engine.patch(original, original.revision, listOf(JsonPatch.Replace("/data/name", JsonNull)))
        when (result) {
            is PatchResult.Success -> {
                assertFalse(engine.validateV2(result.document).isValid)
                assertTrue(engine.exportV2(result.document) is ExportResult.Failure)
            }
            is PatchResult.Failure -> assertTrue(result.issues.isNotEmpty())
        }
        assertArrayEquals(fixtureBytes(), engine.exported(original))
    }

    @Test
    fun ordinaryPatchDoesNotSilentlyConvertSourceVersion() {
        val v3Root = fixtureRoot().withField("spec", JsonPrimitive("chara_card_v3"))
            .withField("spec_version", JsonPrimitive("3.0"))
        val original = engine.read(v3Root.toString())
        assertEquals(SourceFormat.V3, original.sourceFormat)
        val result = engine.patch(original, original.revision, listOf(
            JsonPatch.Replace("/spec", JsonPrimitive("chara_card_v2")),
            JsonPatch.Replace("/spec_version", JsonPrimitive("2.0")),
        ))
        when (result) {
            is PatchResult.Failure -> assertTrue(result.issues.isNotEmpty())
            is PatchResult.Success -> {
                assertEquals(SourceFormat.V3, result.document.sourceFormat)
                assertTrue("Relabeling is not a reviewed format conversion", engine.exportV2(result.document) is ExportResult.Failure)
            }
        }
    }

    @Test
    fun createdV2UsesTheSameValidatorAndExporter() {
        val created = engine.createV2("Mira Vale")
        assertEquals("Mira Vale", created.fields.name)
        assertTrue(engine.validateV2(created).isValid)
        val reread = engine.read(engine.exported(created))
        assertEquals(created.raw, reread.raw)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun invalidUnquotedPatchLiteralCannotInjectAContainerOrNumber() {
        val original = engine.read(fixtureBytes())
        listOf("{}", "[]", "1,\"injected\":true", "NaN", "01").forEach { text ->
            val result = engine.patch(original, original.revision, listOf(
                JsonPatch.Add("/data/extensions/value", JsonUnquotedLiteral(text)),
            ))
            assertTrue("An invalid JSON primitive was accepted: $text", result is PatchResult.Failure)
        }
    }

    @Test
    fun cyclicExternalPatchValueIsAControlledFailure() {
        val original = engine.read(fixtureBytes())
        val backing = mutableListOf<kotlinx.serialization.json.JsonElement>()
        val cycle = JsonArray(backing)
        backing.add(cycle)
        val result = engine.patch(original, original.revision, listOf(
            JsonPatch.Add("/data/extensions/cycle", cycle),
        ))
        assertTrue(result is PatchResult.Failure)
        assertArrayEquals(fixtureBytes(), engine.exported(original))
    }

    @Test
    fun largeAncestorKeyAndManyDescendantsRemainPreservedThroughParseAndEdit() {
        // A lazy diagnostic path must not copy this ancestor string for every value.
        // This is a resource-shape regression, not a wall-clock benchmark assertion.
        val key = "k".repeat(512 * 1024)
        val values = JsonArray(List(10_000) { JsonPrimitive(it % 10) })
        val source = cardWithData { it.withField("extensions", JsonObject(mapOf(key to values))) }
        val original = engine.read(source)
        assertArrayEquals(source, engine.exported(original))
        val changed = engine.changed(original, JsonPatch.Replace("/data/name", JsonPrimitive("Edited")))
        val reread = engine.read(engine.exported(changed))
        val extensions = (reread.raw["data"] as JsonObject)["extensions"] as JsonObject
        assertEquals(values, extensions[key])
        assertEquals("Edited", reread.fields.name)
    }

    @Test
    fun cumulativePatchWorkBudgetRejectsWorkWithoutMutatingTheOriginal() {
        val original = engine.createV2()
        val expectedSize = engine.exported(engine.createV2("x")).size + "\"x\"".encodeToByteArray().size
        val exact = CharacterCardEngine(JsonLimits(maxPatchWorkBytes = expectedSize))
        assertEquals("x", exact.changed(original, JsonPatch.Replace("/data/name", JsonPrimitive("x"))).fields.name)
        val constrained = CharacterCardEngine(JsonLimits(maxPatchWorkBytes = expectedSize - 1))
        val result = constrained.patch(original, original.revision, listOf(
            JsonPatch.Replace("/data/name", JsonPrimitive("x")),
        ))
        assertTrue(result is PatchResult.Failure)
        assertTrue((result as PatchResult.Failure).issues.any { it.code == "PATCH_WORK_LIMIT" })
        assertEquals("", original.fields.name)
        assertArrayEquals(engine.exported(engine.createV2()), engine.exported(original))
    }
}

package com.valhalla.tavernforge.format

import com.valhalla.tavernforge.core.model.JsonLimits
import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.SourceFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class DocumentSnapshotTest {
    private val engine = CharacterCardEngine()

    private fun bytes(result: ExportResult): ByteArray {
        assertTrue("Expected snapshot/export success, got $result", result is ExportResult.Success)
        return (result as ExportResult.Success).bytes
    }

    private fun restored(original: SourceEnvelope, current: SourceEnvelope, revision: Long): CharacterDocument {
        val result = engine.restore(original, current, revision)
        assertTrue("Expected restore success, got $result", result is ParseResult.Success)
        return (result as ParseResult.Success).document
    }

    @Test fun untouchedSnapshotsRestoreAndStrictExportExactBytesForAllSixKinds() {
        listOf("st-v1-empty-fields", "st-v2-empty-fields", "spec-v3-rich",
            "spec-character-book-v2", "spec-lorebook-v3", "st-world-info-synthetic").forEach { fixture ->
            val original = SourceEnvelope(fixtureBytes(fixture))
            val document = restored(original, original, 0)
            assertEquals(0L, document.revision)
            assertArrayEquals(original.bytes(), bytes(engine.snapshot(document)))
            assertArrayEquals(original.bytes(), bytes(engine.export(document)))
        }
    }

    @Test fun editedSnapshotsRestoreRevisionAndRetainOriginalAndUnknownNumericLexemes() {
        val original = SourceEnvelope(""" {"name":"Original","description":"","personality":"","scenario":"","first_mes":"","mes_example":"","opaque":{"n":1.2300e+09,"big":123456789012345678901234567890,"zero":-0,"null":null,"flag":false}} """.encodeToByteArray())
        val base = engine.read(original.bytes())
        val edited = engine.changed(base, JsonPatch.Replace("/name", JsonPrimitive("Changed")))
        val current = SourceEnvelope(bytes(engine.snapshot(edited)))
        val document = restored(original, current, 14)
        assertEquals(14L, document.revision)
        assertEquals("Changed", document.fields.name)
        assertArrayEquals(original.bytes(), document.source.bytes())
        assertEquals(base.raw["opaque"], document.raw["opaque"])
        val exported = bytes(engine.export(document)).decodeToString()
        listOf("1.2300e+09", "123456789012345678901234567890", "-0", "null", "false").forEach {
            assertTrue("Numeric/value spelling $it must survive", exported.contains(it))
        }
        assertArrayEquals(current.bytes(), bytes(engine.snapshot(document)))
        assertTrue(engine.patch(document, 13, listOf(JsonPatch.Remove("/opaque"))) is PatchResult.Failure)
    }

    @Test fun editedSnapshotsRoundTripAllSixKindsWithoutChangingTheirContainers() {
        val cases = listOf("st-v1-empty-fields" to "/name", "st-v2-empty-fields" to "/data/name",
            "spec-v3-rich" to "/data/name", "spec-character-book-v2" to "/name",
            "spec-lorebook-v3" to "/data/name", "st-world-info-synthetic" to "/name")
        val opaque = (engine.parseValue("{\"number\":1.2300e+09,\"nil\":null,\"array\":[false,\"1\"]}".encodeToByteArray()) as JsonValueResult.Success).value
        cases.forEach { (fixture, namePath) ->
            val original = SourceEnvelope(fixtureBytes(fixture))
            val base = engine.read(original.bytes())
            val edited = engine.changed(base, JsonPatch.Add(namePath, JsonPrimitive("Edited title")),
                JsonPatch.Add("/__preservation_test", opaque))
            val current = SourceEnvelope(bytes(engine.snapshot(edited)))
            val reopened = restored(original, current, 1)
            val exported = engine.read(bytes(engine.export(reopened)))
            assertEquals(base.sourceFormat, exported.sourceFormat)
            assertEquals(base.specVersion, exported.specVersion)
            assertEquals(edited.raw, exported.raw)
            assertEquals(opaque, exported.raw["__preservation_test"])
            assertArrayEquals(original.bytes(), reopened.source.bytes())
        }
    }

    @Test fun removedV1RequiredFieldRestoresAsV1DraftAndCanBeRepaired() {
        val original = SourceEnvelope(fixtureBytes("st-v1-empty-fields"))
        val missing = engine.changed(engine.read(original.bytes()), JsonPatch.Remove("/name"))
        val current = SourceEnvelope(bytes(engine.snapshot(missing)))
        assertEquals(SourceFormat.UNKNOWN, engine.read(current.bytes()).sourceFormat)
        val reopened = restored(original, current, 1)
        assertEquals(SourceFormat.V1, reopened.sourceFormat)
        assertFalse(engine.validate(reopened).isValid)
        assertTrue(engine.export(reopened) is ExportResult.Failure)
        assertFalse(reopened.raw.containsKey("name"))
        val repaired = engine.changed(reopened, JsonPatch.Add("/name", JsonPrimitive("Restored name")))
        assertEquals(2L, repaired.revision)
        assertTrue(engine.export(repaired) is ExportResult.Success)
        assertArrayEquals(original.bytes(), repaired.source.bytes())
    }

    @Test fun v3MissingGroupArraySnapshotDoesNotInsertDefaultsAndExplicitRepairExports() {
        val original = SourceEnvelope(fixtureBytes("spec-v3-minimal"))
        val missing = engine.changed(engine.read(original.bytes()), JsonPatch.Remove("/data/group_only_greetings"))
        val current = SourceEnvelope(bytes(engine.snapshot(missing)))
        val reopened = restored(original, current, 7)
        assertTrue(engine.validate(reopened).issues.any { it.path == "/data/group_only_greetings" })
        assertFalse((reopened.raw.getValue("data") as JsonObject).containsKey("group_only_greetings"))
        assertTrue(engine.export(reopened) is ExportResult.Failure)
        val repaired = engine.changed(reopened, JsonPatch.Add("/data/group_only_greetings", JsonArray(emptyList())))
        val exported = bytes(engine.export(repaired))
        assertTrue(engine.validate(engine.read(exported)).isValid)
        assertArrayEquals(original.bytes(), repaired.source.bytes())
    }

    @Test fun restoreRejectsUnearnedZeroRevisionNegativeRevisionAndMalformedSnapshots() {
        val original = SourceEnvelope(fixtureBytes("st-v1-empty-fields"))
        val changed = SourceEnvelope(bytes(engine.snapshot(engine.changed(engine.read(original.bytes()),
            JsonPatch.Replace("/name", JsonPrimitive("Changed"))))))
        assertTrue(engine.restore(original, changed, 0) is ParseResult.Failure)
        assertTrue(engine.restore(original, original, -1) is ParseResult.Failure)
        val differentWhitespace = SourceEnvelope(original.bytes() + " ".encodeToByteArray())
        assertTrue(engine.restore(original, differentWhitespace, 0) is ParseResult.Failure)
        assertTrue(engine.restore(original, SourceEnvelope("{".encodeToByteArray()), 1) is ParseResult.Failure)
        assertTrue(engine.restore(SourceEnvelope("[]".encodeToByteArray()), original, 1) is ParseResult.Failure)
        val custom = SourceEnvelope(original.bytes(), "application/example+json")
        assertEquals(custom.mediaType, restored(custom, custom, 0).source.mediaType)
        assertTrue(engine.restore(custom, original, 0) is ParseResult.Failure)
    }

    @Test fun restoreCannotRelabelCurrentFormatOrVersion() {
        val original = SourceEnvelope(fixtureBytes("spec-v3-minimal"))
        val text = original.bytes().decodeToString()
        val changedSpec = SourceEnvelope(text.replace("chara_card_v3", "chara_card_v2").encodeToByteArray())
        val changedVersion = SourceEnvelope(text.replace("\"3.0\"", "\"2.0\"").encodeToByteArray())
        assertTrue(engine.restore(original, changedSpec, 1) is ParseResult.Failure)
        assertTrue(engine.restore(original, changedVersion, 1) is ParseResult.Failure)
        val v1 = SourceEnvelope(fixtureBytes("st-v1-empty-fields"))
        assertTrue(engine.restore(v1, SourceEnvelope("{\"entries\":[]}".encodeToByteArray()), 1) is ParseResult.Failure)
    }

    @Test fun snapshotAndRestoreApplyTheReceivingEnginesResourceLimits() {
        val document = engine.read(fixtureBytes("spec-v3-rich"))
        val small = CharacterCardEngine(JsonLimits(maxBytes = 16))
        assertTrue(small.snapshot(document) is ExportResult.Failure)
        assertTrue(small.restore(document.source, document.source, 0) is ParseResult.Failure)
    }

    @Test fun valueParsingRetainsScalarTypesAndNumberSpellingWithoutInventingDocumentFields() {
        val cases = listOf("null" to JsonNull, "false" to JsonPrimitive(false), "\"123\"" to JsonPrimitive("123"),
            "[]" to JsonArray(emptyList()))
        cases.forEach { (text, expected) ->
            val parsed = engine.parseValue(text.encodeToByteArray())
            assertTrue(parsed is JsonValueResult.Success)
            assertEquals(expected, (parsed as JsonValueResult.Success).value)
        }
        val number = engine.parseValue("1.2300e+09".encodeToByteArray()) as JsonValueResult.Success
        assertEquals("1.2300e+09", number.value.toString())
        assertFalse((number.value as JsonPrimitive).isString)
    }

    @Test fun valueParsingRejectsDuplicateKeysTrailingDataInvalidUtf8AndOversize() {
        listOf("{\"x\":1,\"x\":2}", "null false", "NaN", "{", "01").forEach {
            assertTrue("Must reject $it", engine.parseValue(it.encodeToByteArray()) is JsonValueResult.Failure)
        }
        assertTrue(engine.parseValue(byteArrayOf(0xC3.toByte(), 0x28)) is JsonValueResult.Failure)
        assertTrue(CharacterCardEngine(JsonLimits(maxBytes = 4)).parseValue("\"long\"".encodeToByteArray()) is JsonValueResult.Failure)
    }
}

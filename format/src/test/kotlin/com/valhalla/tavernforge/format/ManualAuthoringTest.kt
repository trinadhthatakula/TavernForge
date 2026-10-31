package com.valhalla.tavernforge.format

import com.valhalla.tavernforge.core.model.JsonLimits
import com.valhalla.tavernforge.core.model.SourceFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ManualAuthoringTest {
    private val engine = CharacterCardEngine()
    private fun template(format: SourceFormat) = (engine.createTemplate(format) as ParseResult.Success).document
    private fun apply(document: CharacterDocument, vararg patches: JsonPatch) =
        (engine.patch(document, document.revision, patches.toList()) as PatchResult.Success).document
    private fun command(document: CharacterDocument, command: LoreCommand) =
        (engine.planLoreEdit(document, command) as LoreEditPlanResult.Success).document
    private fun value(json: String) = (engine.parseValue(json.encodeToByteArray()) as JsonValueResult.Success).value

    @Test fun allSixTemplatesValidateEditExportAndReparseInTheSelectedFormat() {
        SourceFormat.entries.filter { it != SourceFormat.UNKNOWN }.forEach { format ->
            val original = template(format)
            assertEquals(format, original.sourceFormat); assertEquals(0L, original.revision)
            assertTrue("$format template", engine.validate(original).isValid)
            assertArrayEquals(original.source.bytes(), (engine.export(original) as ExportResult.Success).bytes)
            val path = when (format) { SourceFormat.V2, SourceFormat.V3, SourceFormat.LOREBOOK_V3 -> "/data/name"; else -> "/name" }
            val edited = apply(original, JsonPatch.Add(path, JsonPrimitive("Hand authored")), JsonPatch.Add("/opaque", value("{\"n\":1.200e+02,\"nil\":null}")))
            val exported = (engine.export(edited) as ExportResult.Success).bytes
            val reopened = (engine.parse(exported) as ParseResult.Success).document
            assertEquals(format, reopened.sourceFormat); assertTrue(engine.validate(reopened).isValid)
            assertEquals(edited.raw, reopened.raw); assertTrue(exported.decodeToString().contains("1.200e+02"))
            assertArrayEquals(original.source.bytes(), edited.source.bytes())
        }
    }

    @Test fun creationDoesNotInventOptionalAssetsTimestampsOrStandaloneVersion() {
        val v3 = template(SourceFormat.V3).raw["data"] as JsonObject
        assertEquals(JsonArray(emptyList()), v3["group_only_greetings"])
        listOf("assets", "creation_date", "modification_date", "character_book", "nickname").forEach { assertFalse(v3.containsKey(it)) }
        assertFalse(template(SourceFormat.LOREBOOK_V3).raw.containsKey("spec_version"))
        assertEquals("{\"entries\":{}}", template(SourceFormat.WORLD_INFO).raw.toString())
    }

    @Test fun unsupportedAndOverBudgetTemplatesFailWithoutFallback() {
        assertTrue(engine.createTemplate(SourceFormat.UNKNOWN) is ParseResult.Failure)
        assertTrue(CharacterCardEngine(JsonLimits(maxBytes = 5)).createTemplate(SourceFormat.V3) is ParseResult.Failure)
    }

    @Test fun fourArrayBookKindsPreserveEntryValuesThroughDuplicateReorderAndDelete() {
        listOf(SourceFormat.V2, SourceFormat.V3, SourceFormat.LOREBOOK_V2, SourceFormat.LOREBOOK_V3).forEach { format ->
            var doc = template(format)
            if (format in setOf(SourceFormat.V2, SourceFormat.V3)) doc = command(doc, LoreCommand.CreateEmbeddedBook)
            doc = command(doc, LoreCommand.AddEntry())
            val path = doc.lorebookPath() + "/entries"
            doc = apply(doc, JsonPatch.Add("$path/0/id", JsonPrimitive(17)),
                JsonPatch.Add("$path/0/opaque", value("{\"null\":null,\"n\":1.00e+01,\"nested\":[false]}")),
                JsonPatch.Replace("$path/0/insertion_order", value("91.00")))
            val sourceEntry = at(doc, path)[0] as JsonObject
            doc = command(doc, LoreCommand.DuplicateEntry(EntryLocator.ArrayIndex(0)))
            val copy = at(doc, path)[1] as JsonObject
            assertEquals(sourceEntry - "id", copy - "id"); assertNotEquals(sourceEntry["id"], copy["id"])
            doc = command(doc, LoreCommand.ReorderEntries(listOf(EntryLocator.ArrayIndex(1), EntryLocator.ArrayIndex(0))))
            assertEquals(copy, at(doc, path)[0]); assertEquals(sourceEntry, at(doc, path)[1])
            doc = command(doc, LoreCommand.DeleteEntry(EntryLocator.ArrayIndex(0)))
            assertEquals(sourceEntry, at(doc, path).single()); assertTrue(engine.validate(doc).isValid)
            assertTrue((engine.export(doc) as ExportResult.Success).bytes.decodeToString().contains("91.00"))
        }
    }

    @Test fun v3StringIdsGetDistinctSuffixAndAbsentIdsStayAbsent() {
        var doc = command(template(SourceFormat.LOREBOOK_V3), LoreCommand.AddEntry())
        doc = command(doc, LoreCommand.DuplicateEntry(EntryLocator.ArrayIndex(0)))
        assertFalse((at(doc, "/data/entries")[1] as JsonObject).containsKey("id"))
        doc = apply(doc, JsonPatch.Add("/data/entries/0/id", JsonPrimitive("α-copy-1")))
        doc = command(doc, LoreCommand.DuplicateEntry(EntryLocator.ArrayIndex(0)))
        assertEquals(JsonPrimitive("α-copy-1-copy-1"), (at(doc, "/data/entries")[1] as JsonObject)["id"])
    }

    @Test fun nativeAllocatorAvoidsKeysAndEquivalentUidValuesAndDoesNotFillDuplicateDefaults() {
        val doc = (engine.parse("""{"entries":{"0":{"uid":8,"content":"a"},"strange/~":{"uid":"1.0","order":9.10,"opaque":{"n":1e+02,"nil":null}},"2":{"uid":2}}} """.trim().encodeToByteArray()) as ParseResult.Success).document
        val result = engine.planLoreEdit(doc, LoreCommand.DuplicateEntry(EntryLocator.NativeKey("strange/~"))) as LoreEditPlanResult.Success
        val entries = result.document.raw["entries"] as JsonObject
        val copy = entries["3"] as JsonObject
        val old = (doc.raw["entries"] as JsonObject)["strange/~"] as JsonObject
        assertEquals(EntryLocator.NativeKey("3"), result.selection)
        assertEquals(old - "uid", copy - "uid" - "displayIndex"); assertEquals(JsonPrimitive(3), copy["uid"])
        assertFalse(copy.containsKey("key")); assertTrue(copy.containsKey("displayIndex"))
        assertEquals(old, entries["strange/~"])
    }

    @Test fun nativeInsertionAndDuplicationSetOnlyNewEntryDisplayPlacement() {
        val doc = (engine.parse("""{"entries":{"0":{"uid":0,"displayIndex":0,"order":90.00},"1":{"uid":1,"displayIndex":10,"order":-4}}}""".encodeToByteArray()) as ParseResult.Success).document
        val inserted = command(doc, LoreCommand.AddEntry(EntryLocator.NativeKey("0")))
        val entries = inserted.raw["entries"] as JsonObject
        assertEquals(value("5"), (entries["2"] as JsonObject)["displayIndex"])
        (doc.raw["entries"] as JsonObject).forEach { (key, old) -> assertEquals(old, entries[key]) }
        val appended = command(doc, LoreCommand.AddEntry())
        assertEquals(value("11"), (((appended.raw["entries"] as JsonObject)["2"]) as JsonObject)["displayIndex"])
        val strange = (engine.parse("""{"entries":{"1e9999999":{"uid":0}}}""".encodeToByteArray()) as ParseResult.Success).document
        assertTrue(engine.planLoreEdit(strange, LoreCommand.AddEntry()) is LoreEditPlanResult.Failure)
    }

    @Test fun nativeReorderChangesDisplayIndexOnlyAndEscapedDeleteTargetsExactKey() {
        val doc = (engine.parse("""{"entries":{"a/~":{"uid":"X","order":91.00,"displayIndex":null,"opaque":[null,false]},"b":{"uid":7,"order":-1e+02}}} """.trim().encodeToByteArray()) as ParseResult.Success).document
        val moved = command(doc, LoreCommand.ReorderEntries(listOf(EntryLocator.NativeKey("b"), EntryLocator.NativeKey("a/~"))))
        val before = doc.raw["entries"] as JsonObject; val after = moved.raw["entries"] as JsonObject
        before.forEach { (key, original) -> assertEquals((original as JsonObject) - "displayIndex", (after[key] as JsonObject) - "displayIndex") }
        assertEquals(JsonPrimitive(0), (after["b"] as JsonObject)["displayIndex"])
        val deleted = command(moved, LoreCommand.DeleteEntry(EntryLocator.NativeKey("a/~")))
        assertEquals(setOf("b"), (deleted.raw["entries"] as JsonObject).keys)
    }

    @Test fun nativeNewEntriesUsePinnedDefaultsWithoutRewritingSparseImports() {
        val doc = (engine.parse("{\"entries\":{\"4\":{\"uid\":4}}}".encodeToByteArray()) as ParseResult.Success).document
        val next = command(doc, LoreCommand.AddEntry())
        val entries = next.raw["entries"] as JsonObject
        assertEquals((doc.raw["entries"] as JsonObject)["4"], entries["4"])
        val added = entries["0"] as JsonObject
        assertEquals(JsonPrimitive(4), added["depth"]); assertEquals(JsonPrimitive(100), added["groupWeight"])
        assertEquals(JsonPrimitive(true), added["selective"]); assertEquals(value("null"), added["caseSensitive"])
        assertFalse(added.containsKey("characterFilterNames")); assertTrue(engine.validate(next).isValid)
    }

    @Test fun invalidLocatorsOrdersTypesAndCollectionLimitsAreAtomicFailures() {
        val doc = command(template(SourceFormat.LOREBOOK_V2), LoreCommand.AddEntry())
        val bytes = (engine.snapshot(doc) as ExportResult.Success).bytes
        listOf(LoreCommand.DeleteEntry(EntryLocator.ArrayIndex(2)), LoreCommand.ReorderEntries(emptyList()),
            LoreCommand.ReorderEntries(listOf(EntryLocator.ArrayIndex(0), EntryLocator.ArrayIndex(0))),
            LoreCommand.DuplicateEntry(EntryLocator.NativeKey("0"))).forEach { assertTrue(engine.planLoreEdit(doc, it) is LoreEditPlanResult.Failure) }
        val invalid = apply(doc, JsonPatch.Add("/entries/0/id", value("null")))
        assertTrue(engine.planLoreEdit(invalid, LoreCommand.DuplicateEntry(EntryLocator.ArrayIndex(0))) is LoreEditPlanResult.Failure)
        assertTrue(CharacterCardEngine(JsonLimits(maxCollectionEntries = 1)).planLoreEdit(doc, LoreCommand.AddEntry()) is LoreEditPlanResult.Failure)
        assertArrayEquals(bytes, (engine.snapshot(doc) as ExportResult.Success).bytes)
    }

    private fun at(doc: CharacterDocument, path: String): JsonArray {
        var value: kotlinx.serialization.json.JsonElement = doc.raw
        path.removePrefix("/").split('/').forEach { value = (value as JsonObject).getValue(it) }
        return value as JsonArray
    }
}

package com.valhalla.tavernforge

import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.format.CharacterCardEngine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** App dispatch checks use synthetic inputs; format-module fixtures establish adapter behavior. */
class InspectionSummaryTest {
    private val engine = CharacterCardEngine()

    @Test
    fun flatV1UsesItsOwnValidationAndName() {
        val fields = listOf("name", "description", "personality", "scenario", "first_mes", "mes_example")
        val summary = inspect(JsonObject(fields.associateWith { JsonPrimitive(if (it == "name") "V1 keeper" else "") }))
        assertEquals(SourceFormat.V1, summary.sourceFormat)
        assertEquals("V1 keeper", summary.documentName)
        assertEquals(InspectionStatus.VALID, summary.status)
        assertNull(summary.specVersion)
    }

    @Test
    fun v2StillValidatesAndReportsOriginalSource() {
        val bytes = engine.createV2("V2 keeper").source.bytes()
        val summary = inspectJson(engine, "card.json", bytes)
        assertEquals(SourceFormat.V2, summary.sourceFormat)
        assertEquals("2.0", summary.specVersion)
        assertEquals("V2 keeper", summary.documentName)
        assertEquals(InspectionStatus.VALID, summary.status)
        assertEquals("card.json", summary.fileName)
        assertEquals(bytes.size, summary.sizeBytes)
        assertEquals(SourceEnvelope(bytes).sha256, summary.sha256)
    }

    @Test
    fun validV3DoesNotFallBackToV2() {
        val summary = inspect(v3())
        assertEquals(SourceFormat.V3, summary.sourceFormat)
        assertEquals("3.0", summary.specVersion)
        assertEquals("V3 keeper", summary.documentName)
        assertEquals(InspectionStatus.VALID, summary.status)
        assertTrue(summary.issues.toString(), summary.issues.isEmpty())
    }

    @Test
    fun v3MissingItsAdditionalRequiredFieldNeedsReview() {
        val root = v3()
        val data = root.getValue("data") as JsonObject
        val summary = inspect(root.with("data", JsonObject(data - "group_only_greetings")))
        assertEquals(SourceFormat.V3, summary.sourceFormat)
        assertEquals(InspectionStatus.NEEDS_REVIEW, summary.status)
        assertTrue(summary.issues.any { it.path == "/data/group_only_greetings" })
        assertFalse(summary.issues.any { it.code == "UNSUPPORTED_SOURCE_FORMAT" })
    }

    @Test
    fun futureV3VersionIsVisibleAndNeverPasses() {
        val summary = inspect(v3().with("spec_version", JsonPrimitive("3.1")))
        assertEquals(SourceFormat.V3, summary.sourceFormat)
        assertEquals("3.1", summary.specVersion)
        assertEquals(InspectionStatus.UNSUPPORTED, summary.status)
        assertTrue(summary.issues.any { it.code == "UNSUPPORTED_SPEC_VERSION" })
    }

    @Test
    fun unknownJsonIsNotPresentedAsAValidCard() {
        val summary = inspect(JsonObject(mapOf("other" to JsonPrimitive("data"))))
        assertEquals(SourceFormat.UNKNOWN, summary.sourceFormat)
        assertEquals(InspectionStatus.UNSUPPORTED, summary.status)
        assertNull(summary.documentName)
        assertTrue(summary.issues.any { it.code == "UNSUPPORTED_SOURCE_FORMAT" })
    }

    @Test
    fun malformedJsonRetainsFailureAndFingerprint() {
        val bytes = "{broken".encodeToByteArray()
        val summary = inspectJson(engine, "broken.json", bytes)
        assertEquals(InspectionStatus.NEEDS_REVIEW, summary.status)
        assertEquals(SourceFormat.UNKNOWN, summary.sourceFormat)
        assertEquals(SourceEnvelope(bytes).sha256, summary.sha256)
        assertTrue(summary.issues.isNotEmpty())
    }

    @Test
    fun standaloneV2LorebookUsesBookTitleAndEntryCount() {
        val summary = inspect(book())
        assertEquals(SourceFormat.LOREBOOK_V2, summary.sourceFormat)
        assertEquals("Archive", summary.documentName)
        assertEquals(0, summary.loreEntryCount)
        assertEquals(InspectionStatus.VALID, summary.status)
    }

    @Test
    fun standaloneV3LorebookUsesItsEnvelopeWithoutInventingAVersion() {
        val summary = inspect(JsonObject(mapOf("spec" to JsonPrimitive("lorebook_v3"), "data" to book())))
        assertEquals(SourceFormat.LOREBOOK_V3, summary.sourceFormat)
        assertEquals("Archive", summary.documentName)
        assertEquals(0, summary.loreEntryCount)
        assertNull(summary.specVersion)
        assertEquals(InspectionStatus.VALID, summary.status)
    }

    @Test
    fun worldInfoUsesItsObjectEntries() {
        val summary = inspect(JsonObject(mapOf("entries" to JsonObject(emptyMap()))))
        assertEquals(SourceFormat.WORLD_INFO, summary.sourceFormat)
        assertEquals(0, summary.loreEntryCount)
        assertEquals(InspectionStatus.VALID, summary.status)
    }

    @Test
    fun embeddedBookCountIsShownForCharacters() {
        val root = v3()
        val data = root.getValue("data") as JsonObject
        val summary = inspect(root.with("data", data.with("character_book", book())))
        assertEquals(SourceFormat.V3, summary.sourceFormat)
        assertEquals(0, summary.loreEntryCount)
        assertEquals(InspectionStatus.VALID, summary.status)
    }

    @Test
    fun displayNameIsBoundedWithoutChangingValidationOrSource() {
        val document = engine.createV2("x".repeat(300))
        val summary = inspectJson(engine, "long.json", document.source.bytes())
        assertEquals("x".repeat(239) + "…", summary.documentName)
        assertEquals(document.source.sha256, summary.sha256)
        assertEquals(InspectionStatus.VALID, summary.status)
    }

    private fun inspect(root: JsonObject) = inspectJson(engine, "synthetic.json", root.toString().encodeToByteArray())

    private fun v3(): JsonObject {
        val root = engine.createV2("V3 keeper").raw
        val data = root.getValue("data") as JsonObject
        return root.with("spec", JsonPrimitive("chara_card_v3"))
            .with("spec_version", JsonPrimitive("3.0"))
            .with("data", data.with("group_only_greetings", JsonArray(emptyList())))
    }

    private fun book() = JsonObject(mapOf(
        "name" to JsonPrimitive("Archive"),
        "extensions" to JsonObject(emptyMap()),
        "entries" to JsonArray(emptyList()),
    ))

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))
}

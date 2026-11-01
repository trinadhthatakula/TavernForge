package com.valhalla.tavernforge.format

import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.format.png.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual pinned-upstream writer inputs and actual Kotlin engine outputs for the independent Node reader. */
class PngReferenceArtifactsTest {
    private val cards = CharacterCardEngine()
    private val png = PngCardEngine(cards = cards)
    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")).use { it.readBytes() }
    private fun inspect(bytes: ByteArray) = (png.inspect(bytes) as PngInspectionResult.Success).container
    private fun export(container: PngContainer, document: CharacterDocument): ByteArray {
        val plan = (png.planExport(container, document) as PngExportPlanResult.Ready).plan
        return (png.export(plan, plan.reviewItems.toSet()) as PngExportResult.Success).bytes
    }
    @Test fun realPinnedWriterV2BackfillIsAnInvalidV3DraftAndExplicitLegacyRecoveryWorks() {
        val original = inspect(fixture("st-writer-v2.png"))
        val preferred = png.select(original) as PngSelectionResult.Selected
        assertEquals(SourceFormat.V3, preferred.document.sourceFormat)
        assertFalse(cards.validate(preferred.document).isValid)
        assertTrue(png.planExport(original, preferred.document) is PngExportPlanResult.Failure)
        val recovered = png.select(original, original.candidates.single { it.keyword == "chara" }.id) as PngSelectionResult.Selected
        assertEquals(SourceFormat.V2, recovered.document.sourceFormat)
        assertArrayEquals(fixtureBytes(), recovered.document.source.bytes())
        val output = inspect(export(original, recovered.document))
        assertEquals(listOf("chara"), output.candidates.map { it.keyword })
        assertArrayEquals(fixtureBytes(), (png.select(output) as PngSelectionResult.Selected).document.source.bytes())
    }
    @Test fun realPinnedWriterV3PreservesBothCandidatesUntilReviewed() {
        val original = inspect(fixture("st-writer-v3.png"))
        val preferred = png.select(original) as PngSelectionResult.Selected
        assertEquals(SourceFormat.V3, preferred.document.sourceFormat)
        assertTrue(cards.validate(preferred.document).isValid)
        assertEquals(2, original.candidates.size)
        val plan = (png.planExport(original, preferred.document) as PngExportPlanResult.Ready).plan
        assertEquals(2, plan.reviewItems.size)
        assertTrue(png.export(plan, emptySet()) is PngExportResult.Failure)
        val output = inspect((png.export(plan, plan.reviewItems.toSet()) as PngExportResult.Success).bytes)
        assertEquals(listOf("ccv3"), output.candidates.map { it.keyword })
        assertEquals(preferred.document.raw, (png.select(output) as PngSelectionResult.Selected).document.raw)
    }
    @Test fun emitSixEngineOutputsForPinnedUpstreamReader() {
        val root = File("build/png-reference").apply { mkdirs() }
        val artwork = inspect(fixture("png-synthetic-artwork.png"))
        val outputs = mutableListOf<JsonObject>()
        for ((version, fixtureName, keyword) in listOf(Triple("v1", "st-v1-empty-fields", "chara"), Triple("v2", "st-v2-empty-fields", "chara"), Triple("v3", "spec-v3-minimal", "ccv3"))) {
            val document = cards.read(fixtureBytes(fixtureName))
            val originalBytes = export(artwork, document)
            val original = inspect(originalBytes)
            File(root, "$version-original.png").writeBytes(originalBytes)
            val selected = (png.select(original) as PngSelectionResult.Selected).document
            val edited = cards.changed(selected, JsonPatch.Replace(if (version == "v1") "/name" else "/data/name", JsonPrimitive("Reference $version edit")))
            for ((suffix, target) in listOf("noop" to selected, "edited" to edited)) {
                val bytes = export(original, target)
                val json = (cards.export(target) as ExportResult.Success).bytes
                File(root, "$version-$suffix.png").writeBytes(bytes)
                File(root, "$version-$suffix.json").writeBytes(json)
                outputs += buildJsonObject {
                    put("png", "$version-$suffix.png"); put("json", "$version-$suffix.json")
                    put("pngSha256", SourceEnvelope(bytes).sha256); put("jsonSha256", SourceEnvelope(json).sha256)
                    put("keyword", keyword); put("original", "$version-original.png"); put("noOp", suffix == "noop")
                }
                if (suffix == "noop") assertArrayEquals(originalBytes, bytes)
            }
        }
        File(root, "manifest.json").writeText(buildJsonObject { put("outputs", JsonArray(outputs)) }.toString())
        assertEquals(6, outputs.size)
    }
}

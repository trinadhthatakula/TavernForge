package com.valhalla.tavernforge.format

import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.format.png.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO

/** Original synthetic 1x1 RGBA artwork assembled from specification primitives, GPL-3.0-or-later. */
class PngCardEngineTest {
    private val png = PngCardEngine()
    private val cards = CharacterCardEngine()
    private fun container(bytes: ByteArray) = (png.inspect(bytes) as PngInspectionResult.Success).container
    private fun exported(c: PngContainer, d: CharacterDocument): ByteArray {
        val plan = (png.planExport(c, d) as PngExportPlanResult.Ready).plan
        return (png.export(plan, plan.reviewItems.toSet()) as PngExportResult.Success).bytes
    }
    @Test fun artworkOnlyIsIndependentAndDecodesInIndependentImageIo() {
        val bytes = image()
        val inspected = container(bytes)
        assertEquals(1, inspected.width); assertEquals(1, inspected.height)
        assertEquals(PngSelectionResult.ArtworkOnly, png.select(inspected))
        assertArrayEquals(bytes, inspected.bytes())
        val decoded = ImageIO.read(bytes.inputStream())
        assertEquals(0xff112233.toInt(), decoded.getRGB(0, 0))
    }
    @Test fun everyTruncationAndCorruptChecksumIsRejected() {
        val bytes = image()
        for (length in 0 until bytes.size) assertTrue("length $length", png.inspect(bytes.copyOf(length)) is PngInspectionResult.Failure)
        val damaged = bytes.copyOf().also { it[45] = (it[45].toInt() xor 1).toByte() }
        assertTrue(png.inspect(damaged) is PngInspectionResult.Failure)
        assertTrue(png.inspect(bytes + 0) is PngInspectionResult.Failure)
    }
    @Test fun eachCharacterVersionExportsCorrectKeywordAndKeepsExactNoOp() {
        for ((fixture, keyword, format) in listOf(Triple("st-v1-empty-fields", "chara", SourceFormat.V1), Triple("st-v2-empty-fields", "chara", SourceFormat.V2), Triple("spec-v3-minimal", "ccv3", SourceFormat.V3))) {
            val doc = cards.read(fixtureBytes(fixture))
            val bytes = exported(container(image()), doc)
            val inspected = container(bytes)
            val selected = png.select(inspected) as PngSelectionResult.Selected
            assertEquals(format, selected.document.sourceFormat)
            assertEquals(keyword, selected.candidate.keyword)
            assertEquals(1, inspected.candidates.size)
            assertArrayEquals(bytes, exported(inspected, selected.document))
            assertNotNull(ImageIO.read(bytes.inputStream()))
        }
    }
    @Test fun editedMetadataPreservesAllOtherChunksAndNumericLexemes() {
        // Explicitly omit only the synthetic CHARX-only asset from this PNG preservation case.
        // Unresolved embedded references have a separate strict-export rejection test.
        val doc = cards.read((cards.snapshot(cards.changed(cards.read(fixtureBytes("spec-v3-rich")),
            JsonPatch.Remove("/data/assets/1"))) as ExportResult.Success).bytes)
        val bytes = image(listOf(chunk("vpAG", byteArrayOf(5, 6)), chunk("tEXt", "comment\u0000unchanged".encodeToByteArray()), metadata("ccv3", doc.source.bytes())))
        val source = container(bytes)
        val selected = (png.select(source) as PngSelectionResult.Selected).document
        val edited = cards.changed(selected, JsonPatch.Replace("/data/name", JsonPrimitive("Changed")))
        val output = exported(source, edited)
        val result = container(output)
        val before = source.chunks.filter { it.type != "tEXt" || it !in source.candidates.map { c -> source.chunks[c.chunkIndex] } }
        val after = result.chunks.filter { it.type != "tEXt" || it !in result.candidates.map { c -> result.chunks[c.chunkIndex] } }
        assertEquals(before.map { it.type }, after.map { it.type })
        before.zip(after).forEach { (a, b) -> assertArrayEquals(source.bytes().copyOfRange(a.offset, a.offset + a.length + 12), output.copyOfRange(b.offset, b.offset + b.length + 12)) }
        assertEquals("Changed", (png.select(result) as PngSelectionResult.Selected).document.fields.name)
        val reimported = (png.select(result) as PngSelectionResult.Selected).document
        assertEquals((selected.raw["data"] as kotlinx.serialization.json.JsonObject)["extensions"],
            (reimported.raw["data"] as kotlinx.serialization.json.JsonObject)["extensions"])
        assertEquals(selected.raw["future_root"], reimported.raw["future_root"])
        assertTrue(reimported.source.bytes().decodeToString().contains("1.2300e+09"))
        assertTrue(reimported.source.bytes().decodeToString().contains("123456789012345678901234567890"))
        assertEquals(ImageIO.read(source.bytes().inputStream()).getRGB(0, 0), ImageIO.read(output.inputStream()).getRGB(0, 0))
    }
    @Test fun conflictingDuplicatesRequireSelectionAndExactReplacementReview() {
        val first = fixtureBytes("st-v1-empty-fields")
        val second = (cards.snapshot(cards.changed(cards.read(first), JsonPatch.Replace("/name", JsonPrimitive("Other")))) as ExportResult.Success).bytes
        val source = container(image(listOf(metadata("chara", first), metadata("CHARA", second))))
        assertTrue(png.select(source) is PngSelectionResult.NeedsChoice)
        val selected = png.select(source, source.candidates[1].id) as PngSelectionResult.Selected
        val plan = (png.planExport(source, selected.document) as PngExportPlanResult.Ready).plan
        assertEquals(2, plan.reviewItems.size)
        assertTrue(png.export(plan, emptySet()) is PngExportResult.Failure)
        val output = (png.export(plan, plan.reviewItems.toSet()) as PngExportResult.Success).bytes
        assertEquals("Other", (png.select(container(output)) as PngSelectionResult.Selected).document.fields.name)
        assertTrue(png.select(source, "stale") is PngSelectionResult.Failure)
    }
    @Test fun preferredV3NeverSilentlyFallsBackToLegacyOnMalformedData() {
        val source = container(image(listOf(chunk("tEXt", "ccv3\u0000%%%".encodeToByteArray()), metadata("chara", fixtureBytes("st-v1-empty-fields")))))
        assertTrue(png.select(source) is PngSelectionResult.NeedsChoice)
        assertTrue(png.select(source, source.candidates.last().id) is PngSelectionResult.Selected)
    }
    @Test fun mixedCaseV3TakesPrecedenceAndRemovesStaleBackfillOnlyAfterReview() {
        val source = container(image(listOf(metadata("chara", fixtureBytes("st-v1-empty-fields")), metadata("CcV3", fixtureBytes("spec-v3-minimal")))))
        val selected = png.select(source) as PngSelectionResult.Selected
        assertEquals(SourceFormat.V3, selected.document.sourceFormat)
        val plan = (png.planExport(source, selected.document) as PngExportPlanResult.Ready).plan
        assertEquals(2, plan.reviewItems.size)
        assertTrue(png.export(plan, emptySet()) is PngExportResult.Failure)
        val output = container((png.export(plan, plan.reviewItems.toSet()) as PngExportResult.Success).bytes)
        assertEquals(listOf("ccv3"), output.candidates.map { it.keyword })
    }
    @Test fun wrongKeywordVersionNeedsExplicitJsonRecovery() {
        val source = container(image(listOf(metadata("ccv3", fixtureBytes("st-v1-empty-fields")))))
        assertTrue(png.select(source) is PngSelectionResult.NeedsChoice)
        val recovered = png.select(source, source.candidates.single().id) as PngSelectionResult.Selected
        assertFalse(recovered.candidate.conforms)
        assertEquals(SourceFormat.V1, recovered.document.sourceFormat)
    }
    @Test fun noncanonicalBase64CompressedTextAndInvalidJsonStayDistinct() {
        listOf("e30=\n", "e31=", "e30", "====").forEach { bad ->
            assertEquals("PNG_BASE64", container(image(listOf(chunk("tEXt", ("chara\u0000" + bad).encodeToByteArray())))).candidates.single().issues.first().code)
        }
        val compressed = container(image(listOf(chunk("zTXt", "chara\u0000\u0000bogus".encodeToByteArray()))))
        assertEquals("PNG_METADATA_CONVENTION", compressed.candidates.single().issues.first().code)
        val invalid = container(image(listOf(metadata("chara", byteArrayOf(0xc3.toByte(), 0x28)))))
        assertEquals("PNG_JSON", invalid.candidates.single().issues.first().code)
    }
    @Test fun sizePixelChunkAndCandidateBudgetsRejectAtTheirBoundary() {
        val bytes = image()
        assertTrue(PngCardEngine(PngLimits(maxBytes = bytes.size)).inspect(bytes) is PngInspectionResult.Success)
        assertTrue(PngCardEngine(PngLimits(maxBytes = bytes.size - 1)).inspect(bytes) is PngInspectionResult.Failure)
        assertTrue(PngCardEngine(PngLimits(maxChunks = 2)).inspect(bytes) is PngInspectionResult.Failure)
        assertTrue(PngCardEngine(PngLimits(maxChunkBytes = 12)).inspect(bytes) is PngInspectionResult.Failure)
        val candidates = image(listOf(metadata("chara", fixtureBytes("st-v1-empty-fields")), metadata("chara", fixtureBytes("st-v1-empty-fields"))))
        assertTrue(PngCardEngine(PngLimits(maxCandidates = 1)).inspect(candidates) is PngInspectionResult.Failure)
        assertTrue(PngCardEngine(PngLimits(maxInflatedBytes = 4)).inspect(bytes) is PngInspectionResult.Failure)
    }
    @Test fun badFiltersMissingPixelsExtraStreamsAndBrokenOrderingFail() {
        listOf(byteArrayOf(5, 1, 2, 3, 4), byteArrayOf(0, 1), byteArrayOf(0, 1, 2, 3, 4, 5)).forEach {
            assertTrue(png.inspect(image(scanline = it)) is PngInspectionResult.Failure)
        }
        val compressed = compressed(byteArrayOf(0, 1, 2, 3, 4))
        assertTrue(png.inspect(signature + header() + chunk("IDAT", compressed + compressed) + chunk("IEND", byteArrayOf())) is PngInspectionResult.Failure)
        assertTrue(png.inspect(signature + header() + chunk("IDAT", compressed.copyOfRange(0, 3)) + chunk("tEXt", "a\u0000b".encodeToByteArray()) + chunk("IDAT", compressed.copyOfRange(3, compressed.size)) + chunk("IEND", byteArrayOf())) is PngInspectionResult.Failure)
    }
    @Test fun unknownCriticalAndAnimationAreRetainedButBlockStrictRewrite() {
        val doc = cards.read(fixtureBytes())
        for (extra in listOf(chunk("VpAG", byteArrayOf(1)), chunk("acTL", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0)))) {
            val bytes = image(listOf(extra)); val c = container(bytes)
            assertArrayEquals(bytes, c.bytes())
            assertTrue(png.planExport(c, doc) is PngExportPlanResult.Failure)
        }
    }
    @Test fun allReturnedByteArraysAreOwnedAndReceiptsCannotApproveAnotherPlan() {
        val bytes = image(); val c = container(bytes); bytes.fill(0)
        assertTrue(png.inspect(c.bytes()) is PngInspectionResult.Success)
        val doc = cards.read(fixtureBytes())
        val first = container(exported(c, doc))
        val p1 = (png.planExport(first, cards.changed(doc, JsonPatch.Replace("/data/name", JsonPrimitive("One")))) as PngExportPlanResult.Ready).plan
        val p2 = (png.planExport(container(exported(c, cards.changed(doc, JsonPatch.Replace("/data/name", JsonPrimitive("Two"))))), doc) as PngExportPlanResult.Ready).plan
        assertTrue(png.export(p2, p1.reviewItems.toSet()) is PngExportResult.Failure)
    }
    @Test fun unresolvedEmbeddedAssetsBlockStrictPngEvenOnOriginalContainer() {
        val doc = cards.read(fixtureBytes("spec-v3-rich"))
        val source = container(image(listOf(metadata("ccv3", doc.source.bytes()))))
        val failed = png.planExport(source, doc) as PngExportPlanResult.Failure
        assertEquals("PNG_ASSET_REFERENCES", failed.issues.single().code)
        assertArrayEquals(doc.source.bytes(), (png.select(source) as PngSelectionResult.Selected).document.source.bytes())
    }
    @Test fun adam7SinglePixelIsValidatedAndIndependentlyDecoded() {
        val bytes = image(interlace = 1)
        assertTrue(png.inspect(bytes) is PngInspectionResult.Success)
        assertEquals(0xff112233.toInt(), ImageIO.read(bytes.inputStream()).getRGB(0, 0))
    }
    companion object {
        private val signature = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)
        internal fun image(extras: List<ByteArray> = emptyList(), scanline: ByteArray = byteArrayOf(0, 0x11, 0x22, 0x33, 0xff.toByte()), interlace: Int = 0): ByteArray =
            signature + header(interlace) + extras.fold(byteArrayOf()) { a, b -> a + b } + chunk("IDAT", compressed(scanline)) + chunk("IEND", byteArrayOf())
        private fun header(interlace: Int = 0) = chunk("IHDR", byteArrayOf(0,0,0,1,0,0,0,1,8,6,0,0,interlace.toByte()))
        private fun compressed(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(bytes) } }.toByteArray()
        internal fun metadata(keyword: String, bytes: ByteArray) = chunk("tEXt", keyword.encodeToByteArray() + 0 + Base64.getEncoder().encode(bytes))
        internal fun chunk(type: String, payload: ByteArray): ByteArray {
            val buffer = java.nio.ByteBuffer.allocate(payload.size + 12)
            val kind = type.encodeToByteArray()
            buffer.putInt(payload.size); buffer.put(kind); buffer.put(payload)
            buffer.putInt(CRC32().apply { update(kind); update(payload) }.value.toInt())
            return buffer.array()
        }
    }
}

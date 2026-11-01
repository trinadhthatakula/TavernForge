package com.valhalla.tavernforge.format

import com.valhalla.tavernforge.format.png.*
import org.junit.Assert.*
import org.junit.Test

class PngExportBudgetTest {
    private val cards = CharacterCardEngine()
    private val document = cards.read(fixtureBytes("st-v1-empty-fields"))
    private fun artwork(): ByteArray = checkNotNull(javaClass.getResourceAsStream("/fixtures/png-synthetic-artwork.png")).use { it.readBytes() }
    private fun container(engine: PngCardEngine, bytes: ByteArray): PngContainer = (engine.inspect(bytes) as PngInspectionResult.Success).container
    private fun exported(engine: PngCardEngine, source: PngContainer): ByteArray {
        val plan = (engine.planExport(source, document) as PngExportPlanResult.Ready).plan
        return (engine.export(plan, plan.reviewItems.toSet()) as PngExportResult.Success).bytes
    }
    private fun requiredSize(): Int {
        val engine = PngCardEngine()
        return exported(engine, container(engine, artwork())).size
    }
    @Test fun metadataGrowthBeyondImportLimitFailsBeforeReturningOutputBytes() {
        val required = requiredSize()
        val engine = PngCardEngine(PngLimits(maxBytes = required - 1, maxOutputBytes = required + 128))
        val original = container(engine, artwork())
        val result = engine.planExport(original, document) as PngExportPlanResult.Failure
        assertEquals("PNG_LIMIT", result.issues.single().code)
        assertArrayEquals(artwork(), original.bytes())
    }
    @Test fun exactImportBoundaryProducesAReimportableCard() {
        val required = requiredSize()
        for (limit in listOf(required, required + 1)) {
            val engine = PngCardEngine(PngLimits(maxBytes = limit, maxOutputBytes = required + 128))
            val bytes = exported(engine, container(engine, artwork()))
            assertEquals(required, bytes.size)
            val reimported = engine.select(container(engine, bytes)) as PngSelectionResult.Selected
            assertArrayEquals(document.source.bytes(), reimported.document.source.bytes())
        }
    }
    @Test fun aStricterExplicitOutputLimitStillWins() {
        val required = requiredSize()
        val engine = PngCardEngine(PngLimits(maxBytes = required + 128, maxOutputBytes = required - 1))
        assertTrue(engine.planExport(container(engine, artwork()), document) is PngExportPlanResult.Failure)
    }
    @Test fun noOpCannotBypassTheCurrentEnginesImportBudget() {
        val generous = PngCardEngine()
        val bytes = exported(generous, container(generous, artwork()))
        val source = container(generous, bytes)
        val stricter = PngCardEngine(PngLimits(maxBytes = bytes.size - 1, maxOutputBytes = bytes.size + 128))
        assertTrue(stricter.planExport(source, document) is PngExportPlanResult.Failure)
    }
}

package com.valhalla.tavernforge

import com.valhalla.tavernforge.core.model.JsonLimits
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.format.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ManualEditHistoryTest {
    private val engine = CharacterCardEngine()
    private fun source() = (engine.parse("""{"entries":{"a/~":{"uid":"7","content":"keep","order":91.00,"unknown":{"nil":null,"n":1e+02}}}}""".encodeToByteArray()) as ParseResult.Success).document
    private fun document(result: PatchResult) = (result as PatchResult.Success).document

    @Test fun deletedUnknownSubtreeRestoresExactlyOnUndoAndRedoReappliesDeletion() {
        val base = source(); val history = EditHistory(engine, base)
        val removed = document(history.append(listOf(JsonPatch.Remove("/entries/a~1~0"))))
        assertNotEquals(base.raw, removed.raw); assertTrue(history.canUndo); assertFalse(history.canRedo)
        val restored = document(history.undo())
        assertEquals(base.raw, restored.raw); assertArrayEquals(base.source.bytes(), (engine.export(restored) as ExportResult.Success).bytes)
        assertTrue(history.patches.isEmpty()); assertFalse(history.canUndo); assertTrue(history.canRedo)
        assertEquals(removed.raw, document(history.redo()).raw)
    }

    @Test fun groupedCommandUndoAndNewBranchDoNotResurrectAbandonedChanges() {
        val base = source(); val history = EditHistory(engine, base)
        history.append(listOf(JsonPatch.Add("/one", JsonPrimitive(1)), JsonPatch.Add("/two", JsonPrimitive(2))))
        assertEquals(2, history.patches.size)
        assertEquals(base.raw, document(history.undo()).raw)
        val replacement = document(history.append(listOf(JsonPatch.Add("/three", JsonPrimitive(3)))))
        assertFalse(history.canRedo); assertFalse(replacement.raw.containsKey("one")); assertFalse(replacement.raw.containsKey("two"))
    }

    @Test fun historyCountAndByteBoundsRetainPriorCommandsAndRedoOnFailure() {
        val base = source(); val history = EditHistory(engine, base, maxCommands = 1)
        history.append(listOf(JsonPatch.Add("/one", JsonPrimitive(1))))
        assertTrue(history.append(listOf(JsonPatch.Add("/two", JsonPrimitive(2)))) is PatchResult.Failure)
        assertEquals(listOf("/one"), history.patches.map { it.path })
        history.undo()
        val bounded = EditHistory(engine, base, maxBytes = 10)
        assertTrue(bounded.append(listOf(JsonPatch.Add("/large", JsonPrimitive("way too much text")))) is PatchResult.Failure)
        assertTrue(bounded.patches.isEmpty()); assertFalse(bounded.canUndo)
    }

    @Test fun engineWorkAndFormatFailuresNeverEnterHistory() {
        val base = (engine.createTemplate(SourceFormat.V3) as ParseResult.Success).document
        val history = EditHistory(engine, base)
        assertTrue(history.append(listOf(JsonPatch.Replace("/spec_version", JsonPrimitive("2.0")))) is PatchResult.Failure)
        assertFalse(history.canUndo)
        val bounded = EditHistory(CharacterCardEngine(JsonLimits(maxPatchWorkBytes = 1)), base)
        assertTrue(bounded.append(listOf(JsonPatch.Replace("/data/name", JsonPrimitive("name")))) is PatchResult.Failure)
        assertTrue(bounded.patches.isEmpty())
    }
}

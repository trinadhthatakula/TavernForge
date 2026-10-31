package com.valhalla.tavernforge.format

import com.valhalla.tavernforge.format.combine.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class LorebookEmbeddingTest {
    private val cards = CharacterCardEngine()
    private val embedding = LorebookEmbeddingEngine(cards)
    @Test fun v2BookEmbedsWithoutChangingAnyUnknownValueOrOriginal() {
        val character = cards.read(fixtureBytes())
        val lore = cards.read(fixtureBytes("spec-character-book-v2"))
        val plan = (embedding.plan(character, lore) as EmbeddingPlanResult.Ready).plan
        val result = (embedding.accept(plan, plan.reviewItems.toSet()) as PatchResult.Success).document
        assertEquals(lore.raw, (result.raw["data"] as JsonObject)["character_book"])
        assertArrayEquals(character.source.bytes(), result.source.bytes())
        assertEquals(1L, result.revision)
    }
    @Test fun v3OuterUnknownFieldsHaveExactOmissionReview() {
        val character = cards.read(fixtureBytes("spec-v3-minimal"))
        val lore = cards.changed(cards.read(fixtureBytes("spec-lorebook-v3")), JsonPatch.Add("/unknown_outer", JsonPrimitive("retain")))
        val plan = (embedding.plan(character, lore) as EmbeddingPlanResult.Ready).plan
        assertTrue(plan.reviewItems.any { it.contains("/unknown_outer") })
        assertTrue(embedding.accept(plan, emptySet()) is PatchResult.Failure)
        val result = (embedding.accept(plan, plan.reviewItems.toSet()) as PatchResult.Success).document
        assertEquals(lore.raw["data"], (result.raw["data"] as JsonObject)["character_book"])
        assertEquals(JsonPrimitive("retain"), lore.raw["unknown_outer"])
    }
    @Test fun existingBookReplacementRequiresReview() {
        val character = cards.read(fixtureBytes("spec-v3-rich"))
        val lore = cards.read(fixtureBytes("spec-lorebook-v3"))
        val plan = (embedding.plan(character, lore) as EmbeddingPlanResult.Ready).plan
        assertTrue(plan.reviewItems.any { it.startsWith("Replace existing") })
        assertTrue(embedding.accept(plan, emptySet()) is PatchResult.Failure)
    }
    @Test fun nativeWorldAndCrossVersionNeverConvertSilently() {
        val character = cards.read(fixtureBytes())
        for (name in listOf("st-world-info-synthetic", "spec-lorebook-v3")) {
            assertEquals("CONVERSION_REQUIRED", (embedding.plan(character, cards.read(fixtureBytes(name))) as EmbeddingPlanResult.Failure).code)
        }
    }
    @Test fun invalidBookIsNotEmbeddedAndReceiptIsBoundToRevisionsAndContent() {
        val character = cards.read(fixtureBytes())
        val lore = cards.read(fixtureBytes("spec-character-book-v2"))
        val bad = cards.changed(lore, JsonPatch.Remove("/entries"))
        assertTrue(embedding.plan(character, bad) is EmbeddingPlanResult.Failure)
        val first = (embedding.plan(character, lore) as EmbeddingPlanResult.Ready).plan
        val changed = cards.changed(character, JsonPatch.Replace("/data/name", JsonPrimitive("New")))
        val second = (embedding.plan(changed, lore) as EmbeddingPlanResult.Ready).plan
        assertNotEquals(first.characterSha256, second.characterSha256)
        assertNotEquals(first.characterRevision, second.characterRevision)
    }
}

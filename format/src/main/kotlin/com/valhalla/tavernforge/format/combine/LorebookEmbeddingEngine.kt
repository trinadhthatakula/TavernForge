package com.valhalla.tavernforge.format.combine

import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.CharacterDocument
import com.valhalla.tavernforge.format.ExportResult
import com.valhalla.tavernforge.format.JsonPatch
import com.valhalla.tavernforge.format.PatchResult
import kotlinx.serialization.json.JsonObject

class EmbeddingPlan internal constructor(val characterRevision: Long, val characterSha256: String,
    val lorebookRevision: Long, val lorebookSha256: String, reviewItems: List<String>,
    internal val character: CharacterDocument, internal val patch: JsonPatch,
    val replacesExistingBook: Boolean, omittedOuterFields: List<String>) {
    private val omitted = omittedOuterFields.toList()
    val omittedOuterFields get() = omitted.toList()
    private val review = reviewItems.toList()
    val reviewItems get() = review.toList()
}
sealed interface EmbeddingPlanResult {
    data class Ready(val plan: EmbeddingPlan) : EmbeddingPlanResult
    data class Failure(val code: String, val message: String) : EmbeddingPlanResult
}
/** Same-schema snapshot embedding only; native-world/cross-version conversion stays explicit. */
class LorebookEmbeddingEngine(private val cards: CharacterCardEngine = CharacterCardEngine()) {
    fun plan(character: CharacterDocument, lorebook: CharacterDocument): EmbeddingPlanResult {
        if (!((character.sourceFormat == SourceFormat.V2 && lorebook.sourceFormat == SourceFormat.LOREBOOK_V2) ||
                (character.sourceFormat == SourceFormat.V3 && lorebook.sourceFormat == SourceFormat.LOREBOOK_V3)))
            return EmbeddingPlanResult.Failure("CONVERSION_REQUIRED", "These formats can be linked and exported separately; direct embedding requires matching V2 or V3 schemas.")
        val characterBytes = (cards.snapshot(character) as? ExportResult.Success)?.bytes
            ?: return EmbeddingPlanResult.Failure("INVALID_CHARACTER", "Character draft cannot be snapshotted.")
        val loreBytes = (cards.export(lorebook) as? ExportResult.Success)?.bytes
            ?: return EmbeddingPlanResult.Failure("INVALID_LOREBOOK", "Resolve lorebook errors before embedding.")
        val raw = lorebook.raw
        val book = if (lorebook.sourceFormat == SourceFormat.LOREBOOK_V3) raw["data"] as? JsonObject else raw
        if (book == null || character.raw["data"] !is JsonObject) return EmbeddingPlanResult.Failure("INVALID_STRUCTURE", "A character data object and lorebook object are required.")
        val characterHash = SourceEnvelope(characterBytes).sha256
        val loreHash = SourceEnvelope(loreBytes).sha256
        val reviews = mutableListOf<String>()
        val existing = (character.raw["data"] as JsonObject)["character_book"]
        if (existing != null) reviews += "Replace existing embedded lorebook in character $characterHash at revision ${character.revision} with lorebook $loreHash at revision ${lorebook.revision}"
        val omittedFields = if (lorebook.sourceFormat == SourceFormat.LOREBOOK_V3)
            raw.keys.filter { it !in setOf("spec", "spec_version", "data") }.sorted() else emptyList()
        omittedFields.forEach {
            reviews += "Omit outer lorebook field /${it.replace("~", "~0").replace("/", "~1")} from embedded snapshot $loreHash; standalone original is retained"
        }
        val patch = JsonPatch.Add("/data/character_book", book)
        return EmbeddingPlanResult.Ready(EmbeddingPlan(character.revision, characterHash, lorebook.revision, loreHash, reviews, character, patch, existing != null, omittedFields))
    }
    fun accept(plan: EmbeddingPlan, acknowledgedReviewItems: Set<String>): PatchResult {
        if (acknowledgedReviewItems != plan.reviewItems.toSet()) {
            // A rejected receipt must not produce operations or a modified document.
            return PatchResult.Failure(listOf(com.valhalla.tavernforge.core.model.Diagnostic("EMBEDDING_REVIEW", "Review the exact embedded-book replacement and omissions before saving.")))
        }
        return cards.patch(plan.character, plan.characterRevision, listOf(plan.patch))
    }
}

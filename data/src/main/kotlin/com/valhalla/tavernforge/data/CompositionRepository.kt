package com.valhalla.tavernforge.data

import com.valhalla.tavernforge.core.model.ArtworkSummary
import com.valhalla.tavernforge.core.model.ProjectComposition
import com.valhalla.tavernforge.core.model.StoredArtwork
import com.valhalla.tavernforge.core.model.StoredProject
import com.valhalla.tavernforge.format.combine.EmbeddingPlan
import com.valhalla.tavernforge.format.combine.EmbeddingPlanResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Default methods preserve compatibility for narrowly scoped test repositories. Production implements all. */
interface CompositionRepository {
    fun observeArtwork(): Flow<List<ArtworkSummary>> = flowOf(emptyList())
    suspend fun saveArtwork(fileName: String, bytes: ByteArray): ArtworkSummary = unavailable()
    suspend fun loadArtwork(id: String): StoredArtwork = unavailable()
    suspend fun deleteArtwork(id: String): Unit = unavailable()
    suspend fun prepareArtworkPngConversion(assetId: String): ArtworkPngConversionPlan = unavailable()
    suspend fun acceptArtworkPngConversion(plan: ArtworkPngConversionPlan, acknowledgedReviewItems: Set<String>): ArtworkSummary = unavailable()
    suspend fun savePngCard(fileName: String, bytes: ByteArray, candidateId: String? = null): StoredProject = unavailable()
    suspend fun loadComposition(characterId: String): ProjectComposition = unavailable()
    suspend fun setArtwork(characterId: String, expectedCompositionRevision: Long, assetId: String?): ProjectComposition = unavailable()
    suspend fun linkLorebook(characterId: String, expectedCompositionRevision: Long, lorebookId: String): ProjectComposition = unavailable()
    suspend fun unlinkLorebook(characterId: String, expectedCompositionRevision: Long, lorebookId: String): ProjectComposition = unavailable()
    suspend fun prepareEmbedding(characterId: String, lorebookId: String): EmbeddingPlanResult = unavailable()
    suspend fun applyEmbedding(characterId: String, expectedCompositionRevision: Long, lorebookId: String,
        plan: EmbeddingPlan, acknowledgedReviewItems: Set<String>): StoredProject = unavailable()
}
private fun unavailable(): Nothing = throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, "Artwork and associations are unavailable in this repository.")

data class ArtworkRecord(val summary: ArtworkSummary, val fileKey: String)
data class CompositionRecord(val characterId: String, val revision: Long = 0, val artworkId: String? = null,
    val sourcePngId: String? = null, val sourceCandidateId: String? = null)
data class LoreLinkRecord(val characterId: String, val lorebookId: String, val embeddedRevision: Long? = null, val embeddedSha256: String? = null)
interface CompositionIndex {
    fun observeArtwork(): Flow<List<ArtworkRecord>>
    suspend fun allArtwork(): List<ArtworkRecord>
    suspend fun findArtwork(id: String): ArtworkRecord?
    suspend fun insertArtwork(record: ArtworkRecord)
    suspend fun deleteArtwork(id: String): Int
    suspend fun artworkInUse(id: String): Boolean
    suspend fun lorebookInUse(id: String): Boolean
    suspend fun composition(id: String): CompositionRecord?
    suspend fun links(id: String): List<LoreLinkRecord>
    suspend fun insertPng(project: ProjectRecord, artwork: ArtworkRecord, composition: CompositionRecord)
    suspend fun setArtwork(id: String, expectedRevision: Long, artworkId: String?)
    suspend fun linkLorebook(id: String, expectedRevision: Long, lorebookId: String, remove: Boolean)
    suspend fun duplicate(project: ProjectRecord, originalId: String)
    suspend fun commitEmbedding(project: ProjectRecord, expectedRevision: Long, expectedCompositionRevision: Long,
        lorebookId: String, expectedLoreRevision: Long, loreSha256: String)
}

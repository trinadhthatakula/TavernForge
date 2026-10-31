package com.valhalla.tavernforge.core.model

/** Immutable private PNG original; displayed names are never storage paths. */
data class ArtworkSummary(val id: String, val fileName: String, val sha256: String, val sizeBytes: Int,
    val width: Int, val height: Int, val isAnimated: Boolean, val createdAtEpochMillis: Long,
    val mediaType: String = "image/png", val sourceArtworkId: String? = null, val transformation: String? = null)
class StoredArtwork(val summary: ArtworkSummary, bytes: ByteArray) {
    private val content = bytes.copyOf()
    fun bytes() = content.copyOf()
}
data class LinkedLorebook(val project: ProjectSummary, val documentRevision: Long,
    val embeddedRevision: Long? = null, val embeddedSha256: String? = null)
data class ProjectComposition(val characterId: String, val documentRevision: Long, val compositionRevision: Long,
    val artwork: ArtworkSummary?, val sourcePng: ArtworkSummary?, val sourceCandidateId: String?,
    val lorebooks: List<LinkedLorebook>)

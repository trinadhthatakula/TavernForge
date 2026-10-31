package com.valhalla.tavernforge.core.model

/** Library metadata; title changes never modify the imported document. */
data class ProjectSummary(
    val id: String,
    val title: String,
    val sourceFileName: String,
    val sourceFormat: SourceFormat,
    val specVersion: String?,
    val isDraft: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val sourceSha256: String,
    val sourceSizeBytes: Int,
)

/** This first persistence slice stores immutable imported JSON, with no payload edits. */
data class StoredProject(val summary: ProjectSummary, val source: SourceEnvelope) {
    val documentRevision: Long get() = 0
}

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

/** Original imported bytes remain immutable; current content and its revision are saved separately. */
data class StoredProject(
    val summary: ProjectSummary,
    val source: SourceEnvelope,
    val currentSource: SourceEnvelope = source,
    val documentRevision: Long = 0,
)

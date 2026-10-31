package com.valhalla.tavernforge.core.model

import java.security.MessageDigest

/** Resource budgets, enforced before JSON is converted into a map. */
data class JsonLimits(
    val maxBytes: Int = 4 * 1024 * 1024,
    /** Root value is depth 1; every contained value adds one. */
    val maxDepth: Int = 64,
    /** Decoded UTF-16 code units, for both object names and string values. */
    val maxStringChars: Int = 1024 * 1024,
    /** All JSON values, including containers; object names do not count as values. */
    val maxNodes: Int = 100_000,
    /** Maximum members per object or elements per array, including lore entries. */
    val maxCollectionEntries: Int = 10_000,
    val maxNumberChars: Int = 1024,
    val maxPatchOperations: Int = 1000,
    /** Cumulative encoded bytes checked while applying one atomic patch batch. */
    val maxPatchWorkBytes: Int = 64 * 1024 * 1024,
) {
    init {
        require(maxBytes in 1..64 * 1024 * 1024)
        require(maxDepth in 1..256)
        require(maxStringChars in 0..64 * 1024 * 1024)
        require(maxNodes in 1..1_000_000)
        require(maxCollectionEntries in 0..1_000_000)
        require(maxNumberChars in 1..4096)
        require(maxPatchOperations in 0..100_000)
        require(maxPatchWorkBytes in 1..1024 * 1024 * 1024)
    }
}

enum class SourceFormat { V1, V2, V3, LOREBOOK_V2, LOREBOOK_V3, WORLD_INFO, UNKNOWN }
enum class Severity { ERROR, WARNING, INFO }
enum class ValidationPhase { PARSE, SCHEMA, SEMANTIC, COMPATIBILITY, PATCH }

data class Diagnostic(
    val code: String,
    val message: String,
    val path: String = "",
    val severity: Severity = Severity.ERROR,
    val phase: ValidationPhase = ValidationPhase.PARSE,
    /** UTF-16 offset in the decoded JSON, when applicable. */
    val offset: Int? = null,
)

class ValidationReport(issues: List<Diagnostic>) {
    val issues: List<Diagnostic> = java.util.Collections.unmodifiableList(issues.toList())
    val isValid: Boolean get() = issues.none { it.severity == Severity.ERROR }
}

/** Original source ownership is independent of any mutable caller byte array. */
class SourceEnvelope(bytes: ByteArray, val mediaType: String = "application/json") {
    private val content = bytes.copyOf()
    val sizeBytes: Int get() = content.size
    val sha256: String = MessageDigest.getInstance("SHA-256")
        .digest(content).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    fun bytes(): ByteArray = content.copyOf()
}

/** Nullable projections do not replace absent/null/wrongly typed values in raw data. */
data class CharacterFields(
    val name: String?,
    val description: String?,
    val personality: String?,
    val scenario: String?,
    val firstMessage: String?,
    val exampleDialogue: String?,
)

/** A local project chooses an export target without relabeling its imported source. */
data class CharacterProject(
    val id: String,
    val documentRevision: Long,
    val exportTarget: SourceFormat = SourceFormat.V2,
    val isDraft: Boolean = true,
)

package com.valhalla.tavernforge

import com.valhalla.tavernforge.core.model.Diagnostic
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.CharacterDocument
import com.valhalla.tavernforge.format.ParseResult

enum class InspectionStatus { VALID, NEEDS_REVIEW, UNSUPPORTED }

data class InspectionSummary(
    val fileName: String,
    val documentName: String?,
    val sourceFormat: SourceFormat,
    val specVersion: String?,
    val loreEntryCount: Int?,
    val sizeBytes: Int,
    val sha256: String,
    val issues: List<Diagnostic>,
    val status: InspectionStatus,
    val canSaveDraft: Boolean,
)

/** Pure mapping keeps Android inspection on the same detected-format validator as strict export. */
internal fun inspectJson(engine: CharacterCardEngine, fileName: String, bytes: ByteArray): InspectionSummary =
    when (val result = engine.parse(bytes)) {
        is ParseResult.Success -> {
            val document = result.document
            val report = engine.validate(document)
            InspectionSummary(
                fileName = fileName,
                documentName = document.displayName?.preview(),
                sourceFormat = document.sourceFormat,
                specVersion = document.specVersion?.preview(),
                loreEntryCount = document.loreEntryCount,
                sizeBytes = document.source.sizeBytes,
                sha256 = document.source.sha256,
                issues = report.issues,
                status = when {
                    !document.isSupportedFormat -> InspectionStatus.UNSUPPORTED
                    report.isValid -> InspectionStatus.VALID
                    else -> InspectionStatus.NEEDS_REVIEW
                },
                canSaveDraft = true,
            )
        }
        is ParseResult.Failure -> InspectionSummary(
            fileName = fileName,
            documentName = null,
            sourceFormat = SourceFormat.UNKNOWN,
            specVersion = null,
            loreEntryCount = null,
            sizeBytes = bytes.size,
            sha256 = result.source?.sha256.orEmpty(),
            issues = result.issues,
            status = InspectionStatus.NEEDS_REVIEW,
            canSaveDraft = false,
        )
    }

internal fun summarizeDocument(engine: CharacterCardEngine, fileName: String, document: CharacterDocument,
    current: SourceEnvelope): InspectionSummary {
    val report = engine.validate(document)
    return InspectionSummary(fileName, document.displayName?.preview(), document.sourceFormat,
        document.specVersion?.preview(), document.loreEntryCount, current.sizeBytes, current.sha256, report.issues,
        when {
            !document.isSupportedFormat -> InspectionStatus.UNSUPPORTED
            report.isValid -> InspectionStatus.VALID
            else -> InspectionStatus.NEEDS_REVIEW
        }, true)
}

private fun String.preview(): String = if (length > 240) take(239) + "…" else this

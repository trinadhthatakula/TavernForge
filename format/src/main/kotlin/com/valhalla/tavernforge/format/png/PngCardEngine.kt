package com.valhalla.tavernforge.format.png

import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.CharacterDocument
import com.valhalla.tavernforge.format.ExportResult
import com.valhalla.tavernforge.format.ParseResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Inflater

/** Resource budgets, not limits imposed by the PNG/card specifications. */
data class PngLimits(
    val maxBytes: Int = 32 * 1024 * 1024,
    val maxOutputBytes: Int = 40 * 1024 * 1024,
    val maxChunkBytes: Int = 16 * 1024 * 1024,
    val maxChunks: Int = 4096,
    val maxDimension: Int = 16384,
    val maxPixels: Long = 16777216,
    val maxCandidates: Int = 16,
    val maxTotalCardBytes: Int = 8 * 1024 * 1024,
    val maxInflatedBytes: Long = 160L * 1024 * 1024,
) {
    init { require(maxBytes > 0 && maxOutputBytes > 0 && maxChunkBytes > 0 && maxChunks > 0 &&
        maxDimension > 0 && maxPixels > 0 && maxCandidates > 0 && maxTotalCardBytes > 0 && maxInflatedBytes > 0) }
}

data class PngIssue(val code: String, val message: String, val chunkIndex: Int? = null)
@ConsistentCopyVisibility
data class PngChunk internal constructor(val type: String, val offset: Int, val length: Int)
class PngCandidate internal constructor(val id: String, val keyword: String, val chunkIndex: Int,
    val document: CharacterDocument?, issues: List<PngIssue>, val conforms: Boolean) {
    private val diagnostics = issues.toList()
    val issues: List<PngIssue> get() = diagnostics.toList()
}
class PngContainer internal constructor(bytes: ByteArray, val width: Int, val height: Int,
    val isAnimated: Boolean, chunks: List<PngChunk>, candidates: List<PngCandidate>, issues: List<PngIssue>) {
    private val content = bytes.copyOf()
    val sha256: String = SourceEnvelope(content, "image/png").sha256
    private val chunkList = chunks.toList()
    private val cardList = candidates.toList()
    private val diagnosticList = issues.toList()
    val chunks get() = chunkList.toList()
    val candidates get() = cardList.toList()
    val issues get() = diagnosticList.toList()
    fun bytes(): ByteArray = content.copyOf()
}
sealed interface PngInspectionResult {
    data class Success(val container: PngContainer, val candidates: List<PngCandidate>, val issues: List<PngIssue>) : PngInspectionResult
    data class Failure(val issues: List<PngIssue>) : PngInspectionResult
}
sealed interface PngSelectionResult {
    data object ArtworkOnly : PngSelectionResult
    data class Selected(val document: CharacterDocument, val candidate: PngCandidate) : PngSelectionResult
    data class NeedsChoice(val candidates: List<PngCandidate>, val issues: List<PngIssue>) : PngSelectionResult
    data class Failure(val issues: List<PngIssue>) : PngSelectionResult
}
/** Receipt IDs name exact metadata removals and source bytes; plans cannot be constructed by UI. */
class PngExportPlan internal constructor(bytes: ByteArray, reviewItems: List<String>, val sourceSha256: String,
    val documentRevision: Long, val documentSha256: String, val sourceFormat: SourceFormat) {
    private val content = bytes.copyOf()
    private val review = reviewItems.toList()
    val reviewItems get() = review.toList()
    internal fun output() = content.copyOf()
}
sealed interface PngExportPlanResult {
    data class Ready(val plan: PngExportPlan) : PngExportPlanResult
    data class Failure(val issues: List<PngIssue>) : PngExportPlanResult
}
sealed interface PngExportResult {
    class Success(bytes: ByteArray) : PngExportResult {
        private val content = bytes.copyOf()
        val bytes get() = content.copyOf()
    }
    data class Failure(val issues: List<PngIssue>) : PngExportResult
}

/** Pure bounded PNG/container adapter. No bitmap, Android, network or format conversion. */
class PngCardEngine(val limits: PngLimits = PngLimits(), val cards: CharacterCardEngine = CharacterCardEngine()) {
    fun inspect(bytes: ByteArray): PngInspectionResult = try {
        if (bytes.size > limits.maxBytes) fail("PNG_LIMIT", "PNG exceeds the input byte limit.")
        if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(SIGNATURE)) fail("PNG_SIGNATURE", "This is not a PNG file.")
        val owned = bytes.copyOf()
        val chunks = mutableListOf<PngChunk>()
        var offset = 8
        while (offset < owned.size) {
            if (chunks.size >= limits.maxChunks) fail("PNG_LIMIT", "PNG exceeds the chunk-count limit.")
            if (owned.size.toLong() - offset < 12) fail("PNG_TRUNCATED", "PNG chunk is truncated.")
            val length = uint(owned, offset)
            if (length > 0x7fffffff || length > limits.maxChunkBytes) fail("PNG_LIMIT", "PNG chunk exceeds the payload limit.")
            val end = offset.toLong() + length + 12
            if (end > owned.size) fail("PNG_TRUNCATED", "PNG chunk payload is truncated.")
            val typeBytes = owned.copyOfRange(offset + 4, offset + 8)
            if (typeBytes.any { it.toInt() !in 65..90 && it.toInt() !in 97..122 }) fail("PNG_TYPE", "PNG chunk type is invalid.")
            if (typeBytes[2].toInt() and 32 != 0) fail("PNG_RESERVED", "PNG uses an unsupported reserved chunk-type bit.")
            val type = String(typeBytes, Charsets.US_ASCII)
            val crc = CRC32().apply { update(owned, offset + 4, length.toInt() + 4) }.value
            if (crc != uint(owned, offset + length.toInt() + 8)) fail("PNG_CRC", "PNG chunk checksum does not match.")
            chunks += PngChunk(type, offset, length.toInt())
            offset = end.toInt()
            if (type == "IEND" && offset != owned.size) fail("PNG_TRAILING", "PNG has data after IEND.")
        }
        if (chunks.firstOrNull()?.type != "IHDR" || chunks.first().length != 13 || chunks.count { it.type == "IHDR" } != 1)
            fail("PNG_IHDR", "PNG must begin with one 13-byte IHDR.")
        if (chunks.lastOrNull()?.type != "IEND" || chunks.last().length != 0 || chunks.count { it.type == "IEND" } != 1)
            fail("PNG_IEND", "PNG must end with one empty IEND.")
        val width = uint(owned, 16)
        val height = uint(owned, 20)
        if (width !in 1..limits.maxDimension.toLong() || height !in 1..limits.maxDimension.toLong() || width * height > limits.maxPixels)
            fail("PNG_DIMENSIONS", "PNG dimensions exceed the configured image budget.")
        val depth = owned[24].toInt() and 255
        val color = owned[25].toInt() and 255
        val legalDepths = when (color) { 0 -> setOf(1, 2, 4, 8, 16); 2, 4, 6 -> setOf(8, 16); 3 -> setOf(1, 2, 4, 8); else -> emptySet() }
        if (depth !in legalDepths || owned[26].toInt() != 0 || owned[27].toInt() != 0 || owned[28].toInt() !in 0..1)
            fail("PNG_IHDR", "PNG color, bit depth, compression, filter or interlace method is invalid.")
        val idats = chunks.withIndex().filter { it.value.type == "IDAT" }
        if (idats.isEmpty() || idats.last().index - idats.first().index + 1 != idats.size) fail("PNG_IDAT", "PNG requires contiguous IDAT chunks.")
        val palettes = chunks.withIndex().filter { it.value.type == "PLTE" }
        if (palettes.size > 1 || (color == 3 && palettes.isEmpty()) || (color in setOf(0, 4) && palettes.isNotEmpty()) ||
            palettes.any { it.index > idats.first().index || it.value.length !in 3..768 || it.value.length % 3 != 0 || (color == 3 && it.value.length / 3 > (1 shl depth)) })
            fail("PNG_PLTE", "PNG palette is missing, misplaced or invalid.")
        validateAncillary(owned, chunks, idats.first().index, palettes.firstOrNull()?.index, color, depth)
        val issues = mutableListOf<PngIssue>()
        if (chunks.any { it.type[0].isUpperCase() && it.type !in setOf("IHDR", "PLTE", "IDAT", "IEND") })
            issues += PngIssue("PNG_UNKNOWN_CRITICAL", "Unknown critical chunks prevent strict PNG export.")
        val animated = chunks.any { it.type in setOf("acTL", "fcTL", "fdAT") }
        if (animated) issues += PngIssue("APNG_NOT_YET_VERIFIED", "Animation bytes are retained; APNG rewriting is not yet verified.")
        validateImage(owned, idats.map { it.value }, width.toInt(), height.toInt(), depth, color, owned[28].toInt())
        val hash = SourceEnvelope(owned, "image/png").sha256
        val candidates = mutableListOf<PngCandidate>()
        var decodedTotal = 0L
        chunks.forEachIndexed { index, chunk ->
            if (chunk.type in setOf("tEXt", "zTXt", "iTXt")) {
                val start = chunk.offset + 8
                val separator = (start until minOf(start + chunk.length, start + 80)).firstOrNull { owned[it].toInt() == 0 }
                    ?: fail("PNG_TEXT", "PNG text keyword is missing or too long.")
                val keywordBytes = owned.copyOfRange(start, separator)
                if (keywordBytes.isEmpty() || keywordBytes.any { (it.toInt() and 255) !in 32..126 && (it.toInt() and 255) !in 161..255 } ||
                    keywordBytes.first() == 32.toByte() || keywordBytes.last() == 32.toByte() || keywordBytes.asList().zipWithNext().any { it.first == 32.toByte() && it.second == 32.toByte() })
                    fail("PNG_TEXT", "PNG text keyword is invalid.")
                val keyword = String(keywordBytes, Charsets.ISO_8859_1).lowercase(java.util.Locale.ROOT)
                if (keyword in setOf("chara", "ccv3")) {
                    if (candidates.size >= limits.maxCandidates) fail("PNG_LIMIT", "PNG exceeds the card-candidate limit.")
                    val errors = mutableListOf<PngIssue>()
                    var document: CharacterDocument? = null
                    if (chunk.type != "tEXt") errors += PngIssue("PNG_METADATA_CONVENTION", "Card metadata in compressed or international text is unsupported.", index)
                    else {
                        val encodedLength = start + chunk.length - separator - 1
                        if (encodedLength > 4L * ((cards.limits.maxBytes.toLong() + 2) / 3)) fail("PNG_LIMIT", "Encoded card exceeds the JSON budget.")
                        val encoded = owned.copyOfRange(separator + 1, start + chunk.length)
                        val decoded = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { null }
                        if (decoded == null || !Base64.getEncoder().encode(decoded).contentEquals(encoded))
                            errors += PngIssue("PNG_BASE64", "Card metadata is not canonical RFC 4648 base64.", index)
                        else {
                            decodedTotal += decoded.size
                            if (decoded.size > cards.limits.maxBytes || decodedTotal > limits.maxTotalCardBytes) fail("PNG_LIMIT", "Decoded cards exceed the JSON budget.")
                            when (val parsed = cards.parse(decoded)) {
                                is ParseResult.Success -> document = parsed.document
                                is ParseResult.Failure -> errors += PngIssue("PNG_JSON", "Card metadata is not bounded strict UTF-8 JSON; inspect its source separately.", index)
                            }
                        }
                    }
                    val conforms = when (keyword) { "ccv3" -> document?.sourceFormat == SourceFormat.V3; else -> document?.sourceFormat in setOf(SourceFormat.V1, SourceFormat.V2) }
                    if (document != null && !conforms) errors += PngIssue("PNG_KEYWORD_KIND", "Card keyword and detected source format disagree; explicit recovery is required.", index)
                    if (document != null && !cards.validate(document).isValid) errors += PngIssue("PNG_SCHEMA", "Extracted JSON has schema errors and can only be saved as a draft.", index)
                    candidates += PngCandidate("$hash:$index:${document?.source?.sha256 ?: "invalid"}", keyword, index, document, errors, conforms)
                }
            }
        }
        if (candidates.size > 1) issues += PngIssue("PNG_ALTERNATIVES", "Multiple card metadata chunks are retained. Inspect their candidates and the receiver's preferred version.")
        val container = PngContainer(owned, width.toInt(), height.toInt(), animated, chunks, candidates, issues)
        PngInspectionResult.Success(container, candidates.toList(), issues.toList())
    } catch (failure: InvalidPng) { PngInspectionResult.Failure(listOf(failure.issue)) }
      catch (_: java.util.zip.DataFormatException) { PngInspectionResult.Failure(listOf(PngIssue("PNG_ZLIB", "PNG image stream is corrupt."))) }

    fun select(container: PngContainer, candidateId: String? = null): PngSelectionResult {
        val candidates = container.candidates
        if (candidateId != null) {
            val selected = candidates.singleOrNull { it.id == candidateId }
                ?: return PngSelectionResult.Failure(listOf(PngIssue("PNG_SELECTION", "The selected metadata does not belong to this PNG.")))
            return selected.document?.let { PngSelectionResult.Selected(it, selected) }
                ?: PngSelectionResult.Failure(selected.issues)
        }
        if (candidates.isEmpty()) return PngSelectionResult.ArtworkOnly
        val preferred = candidates.filter { it.keyword == "ccv3" }.ifEmpty { candidates.filter { it.keyword == "chara" } }
        val first = preferred.first()
        if (first.document == null || !first.conforms || preferred.any { !it.conforms || it.document?.source?.sha256 != first.document.source.sha256 })
            return PngSelectionResult.NeedsChoice(candidates, listOf(PngIssue("PNG_CHOICE", "Choose which metadata to import; malformed/conflicting preferred data is never silently replaced.")))
        return PngSelectionResult.Selected(first.document, first)
    }

    fun planExport(container: PngContainer, document: CharacterDocument): PngExportPlanResult {
        if (container.isAnimated || container.issues.any { it.code == "PNG_UNKNOWN_CRITICAL" }) return PngExportPlanResult.Failure(container.issues)
        val keyword = when (document.sourceFormat) { SourceFormat.V1, SourceFormat.V2 -> "chara"; SourceFormat.V3 -> "ccv3"; else -> return exportFailure("PNG_FORMAT", "Only supported character formats can be embedded in PNG.") }
        val json = (cards.export(document) as? ExportResult.Success)?.bytes ?: return exportFailure("PNG_SCHEMA", "Resolve character validation errors before strict PNG export.")
        val jsonHash = SourceEnvelope(json).sha256
        val sourceCandidates = container.candidates
        val assets = (document.raw["data"] as? JsonObject)?.get("assets") as? JsonArray
        val embedded = assets?.any { asset ->
            val uri = ((asset as? JsonObject)?.get("uri") as? JsonPrimitive)?.content.orEmpty()
            uri.startsWith("embeded://") || uri.startsWith("__asset:")
        } == true
        if (embedded)
            return exportFailure("PNG_ASSET_REFERENCES", "Embedded asset references require a verified resolver before strict PNG export. Original PNG bytes and JSON remain available; review the references explicitly.")
        val bytes = container.bytes()
        val noOp = sourceCandidates.isNotEmpty() && sourceCandidates.all { it.conforms && it.document?.source?.sha256 == jsonHash } &&
            (select(container) as? PngSelectionResult.Selected)?.document?.source?.sha256 == jsonHash
        if (noOp && bytes.size > limits.maxOutputBytes) return exportFailure("PNG_LIMIT", "PNG export exceeds the output byte budget.")
        if (noOp) return PngExportPlanResult.Ready(PngExportPlan(bytes, emptyList(), container.sha256, document.revision, jsonHash, document.sourceFormat))
        val removalIndices = sourceCandidates.map { it.chunkIndex }.toSet()
        if (container.chunks.size - removalIndices.size + 1 > limits.maxChunks) return exportFailure("PNG_LIMIT", "PNG export exceeds the chunk-count budget.")
        val reviews = sourceCandidates.map { "Replace ${it.keyword} metadata chunk ${it.chunkIndex} (${it.id}) with $jsonHash at revision ${document.revision}; policy png1" }
        val encoded = Base64.getEncoder().encode(json)
        val payload = keyword.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + encoded
        if (payload.size > limits.maxChunkBytes) return exportFailure("PNG_LIMIT", "Export card metadata exceeds the chunk budget.")
        val outputLength = bytes.size.toLong() - container.chunks.filterIndexed { i, _ -> i in removalIndices }.sumOf { it.length.toLong() + 12 } + payload.size + 12
        if (outputLength > limits.maxOutputBytes || outputLength > Int.MAX_VALUE) return exportFailure("PNG_LIMIT", "PNG export exceeds the output byte budget.")
        val out = ByteArrayOutputStream(outputLength.toInt())
        out.write(SIGNATURE)
        container.chunks.forEachIndexed { index, chunk ->
            if (chunk.type == "IEND") out.write(textChunk(payload))
            if (index !in removalIndices) out.write(bytes, chunk.offset, chunk.length + 12)
        }
        return PngExportPlanResult.Ready(PngExportPlan(out.toByteArray(), reviews, container.sha256, document.revision, jsonHash, document.sourceFormat))
    }

    fun export(plan: PngExportPlan, acknowledgedReviewItems: Set<String>): PngExportResult =
        if (acknowledgedReviewItems != plan.reviewItems.toSet()) PngExportResult.Failure(listOf(PngIssue("PNG_REVIEW", "Review the exact metadata replacements before exporting this plan.")))
        else PngExportResult.Success(plan.output())

    private fun validateAncillary(bytes: ByteArray, chunks: List<PngChunk>, firstIdat: Int, palette: Int?, color: Int, depth: Int) {
        val beforePalette = setOf("cHRM", "gAMA", "iCCP", "sBIT", "sRGB")
        val beforeData = setOf("bKGD", "hIST", "tRNS", "pHYs", "sPLT", "eXIf")
        val single = beforePalette + (beforeData - "sPLT") + "tIME"
        single.forEach { type -> if (chunks.count { it.type == type } > 1) fail("PNG_ANCILLARY", "PNG repeats a single-instance ancillary chunk.") }
        if (chunks.any { it.type == "iCCP" } && chunks.any { it.type == "sRGB" }) fail("PNG_ANCILLARY", "PNG contains conflicting color profiles.")
        chunks.forEachIndexed { i, chunk ->
            if ((chunk.type in beforePalette && (i > firstIdat || palette != null && i > palette)) ||
                (chunk.type in beforeData && i > firstIdat)) fail("PNG_ANCILLARY", "PNG ancillary chunk order is invalid.")
            val payload = chunk.offset + 8
            val expected = when (chunk.type) {
                "cHRM" -> 32; "gAMA" -> 4; "sRGB" -> 1; "pHYs" -> 9; "tIME" -> 7
                "sBIT" -> when (color) { 0 -> 1; 2, 3 -> 3; 4 -> 2; else -> 4 }
                "bKGD" -> when (color) { 0, 4 -> 2; 3 -> 1; else -> 6 }
                "hIST" -> palette?.let { chunks[it].length / 3 * 2 } ?: -2
                "tRNS" -> when (color) { 0 -> 2; 2 -> 6; 3 -> chunk.length; else -> -2 }
                else -> null
            }
            if (expected != null && chunk.length != expected) fail("PNG_ANCILLARY", "PNG ancillary chunk length is invalid.")
            if (chunk.type in setOf("hIST", "bKGD", "tRNS") && color == 3) {
                if (palette == null || i < palette) fail("PNG_ANCILLARY", "Indexed PNG ancillary data requires a preceding palette.")
                val entries = chunks[palette].length / 3
                if (chunk.type == "tRNS" && chunk.length !in 1..entries || chunk.type == "bKGD" && (bytes[payload].toInt() and 255) >= entries)
                    fail("PNG_ANCILLARY", "Indexed PNG ancillary data exceeds its palette.")
            }
            if (chunk.type == "sRGB" && (bytes[payload].toInt() and 255) !in 0..3) fail("PNG_ANCILLARY", "PNG color intent is invalid.")
            if (chunk.type == "gAMA" && uint(bytes, payload) == 0L) fail("PNG_ANCILLARY", "PNG gamma must be nonzero.")
            if (chunk.type == "sBIT" && (0 until chunk.length).any { (bytes[payload + it].toInt() and 255) !in 1..(if (color == 3) 8 else depth) })
                fail("PNG_ANCILLARY", "PNG significant-bit values are invalid.")
        }
    }
    private fun validateImage(bytes: ByteArray, chunks: List<PngChunk>, width: Int, height: Int, depth: Int, color: Int, interlace: Int) {
        val channels = when (color) { 0, 3 -> 1; 2 -> 3; 4 -> 2; else -> 4 }
        val passes = if (interlace == 0) listOf(intArrayOf(0, 0, 1, 1)) else listOf(
            intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8), intArrayOf(0, 4, 4, 8), intArrayOf(2, 0, 4, 4),
            intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2), intArrayOf(0, 1, 1, 2))
        val rows = mutableListOf<Pair<Long, Int>>()
        var expected = 0L
        for (p in passes) {
            val w = if (width <= p[0]) 0 else (width - p[0] + p[2] - 1) / p[2]
            val h = if (height <= p[1]) 0 else (height - p[1] + p[3] - 1) / p[3]
            if (w == 0 || h == 0) continue
            val row = (w.toLong() * channels * depth + 7) / 8 + 1
            expected += row * h
            rows += row to h
        }
        if (expected > limits.maxInflatedBytes) fail("PNG_LIMIT", "Inflated PNG image exceeds the scanline budget.")
        val inflater = Inflater()
        try {
            val output = ByteArray(8192)
            var total = 0L
            var pass = 0
            var row = 0
            var position = 0L
            var chunkIndex = 0
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    while (chunkIndex < chunks.size && chunks[chunkIndex].length == 0) chunkIndex++
                    if (chunkIndex == chunks.size) fail("PNG_ZLIB", "PNG image stream is truncated.")
                    val chunk = chunks[chunkIndex++]
                    inflater.setInput(bytes, chunk.offset + 8, chunk.length)
                }
                val count = inflater.inflate(output)
                if (count == 0 && !inflater.finished() && !inflater.needsInput()) fail("PNG_ZLIB", "PNG image stream cannot be decoded.")
                total += count
                if (total > expected) fail("PNG_SCANLINES", "PNG image has excess decompressed scanline data.")
                for (i in 0 until count) {
                    if (pass >= rows.size) fail("PNG_SCANLINES", "PNG image has excess scanlines.")
                    if (position == 0L && output[i].toInt() !in 0..4) fail("PNG_FILTER", "PNG scanline filter is invalid.")
                    position++
                    if (position == rows[pass].first) { position = 0; row++; if (row == rows[pass].second) { pass++; row = 0 } }
                }
            }
            if (total != expected || inflater.remaining != 0 || chunks.drop(chunkIndex).any { it.length != 0 }) fail("PNG_SCANLINES", "PNG image stream has missing or trailing data.")
        } finally { inflater.end() }
    }
    private fun exportFailure(code: String, message: String) = PngExportPlanResult.Failure(listOf(PngIssue(code, message)))
    private fun fail(code: String, message: String): Nothing = throw InvalidPng(PngIssue(code, message))
    private class InvalidPng(val issue: PngIssue) : Exception()
    companion object {
        private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        private fun uint(bytes: ByteArray, at: Int): Long = (0..3).fold(0L) { n, i -> (n shl 8) or (bytes[at + i].toLong() and 255) }
        private fun textChunk(payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(payload.size + 12)
            fun number(value: Long) { for (shift in 24 downTo 0 step 8) out.write((value ushr shift).toInt() and 255) }
            number(payload.size.toLong())
            val type = "tEXt".toByteArray(Charsets.US_ASCII)
            out.write(type); out.write(payload)
            number(CRC32().apply { update(type); update(payload) }.value)
            return out.toByteArray()
        }
    }
}

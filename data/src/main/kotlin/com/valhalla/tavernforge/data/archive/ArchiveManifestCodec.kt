package com.valhalla.tavernforge.data.archive

import com.valhalla.tavernforge.core.model.*
import com.valhalla.tavernforge.data.CompositionRecord
import com.valhalla.tavernforge.data.LoreLinkRecord
import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.JsonValueResult
import kotlinx.serialization.json.*
import java.util.Locale

internal object ArchiveManifestCodec {
    fun encode(manifest: LibraryArchiveManifest, limits: ArchiveLimits): ByteArray {
        validate(manifest, limits)
        val value = buildJsonObject {
            put("format", "TavernForgeLibraryBackup"); put("version", 1); put("createdAtEpochMillis", manifest.createdAtEpochMillis)
            putJsonArray("projects") { manifest.projects.forEach { p -> add(buildJsonObject {
                put("summary", projectSummary(p.summary)); put("mediaType", p.mediaType); put("documentRevision", p.documentRevision)
                put("original", blob(p.original)); put("current", blob(p.current))
            }) } }
            putJsonArray("artwork") { manifest.artwork.forEach { a -> add(buildJsonObject {
                put("summary", artworkSummary(a.summary)); put("blob", blob(a.blob))
            }) } }
            putJsonArray("compositions") { manifest.compositions.forEach { c -> add(buildJsonObject {
                put("characterId", c.characterId); put("revision", c.revision); putNullable("artworkId", c.artworkId)
                putNullable("sourcePngId", c.sourcePngId); putNullable("sourceCandidateId", c.sourceCandidateId)
            }) } }
            putJsonArray("links") { manifest.links.forEach { l -> add(buildJsonObject {
                put("characterId", l.characterId); put("lorebookId", l.lorebookId)
                put("embeddedRevision", l.embeddedRevision?.let(::JsonPrimitive) ?: JsonNull)
                putNullable("embeddedSha256", l.embeddedSha256)
            }) } }
            putJsonArray("metadata") { manifest.metadata.forEach { m -> add(buildJsonObject {
                put("projectId", m.projectId); put("revision", m.revision); put("tags", JsonArray(m.tags.map(::JsonPrimitive)))
            }) } }
        }.toString().toByteArray(Charsets.UTF_8)
        if (value.size > limits.maxManifestBytes) fail(ArchiveError.LIMIT_EXCEEDED)
        return value
    }
    fun decode(bytes: ByteArray, limits: ArchiveLimits): LibraryArchiveManifest {
        if (bytes.size > limits.maxManifestBytes) fail(ArchiveError.LIMIT_EXCEEDED)
        val result = CharacterCardEngine(JsonLimits(maxBytes = limits.maxManifestBytes, maxDepth = 16,
            maxStringChars = limits.maxManifestBytes, maxNodes = 200_000, maxCollectionEntries = 10_000)).parseValue(bytes)
        val root = (result as? JsonValueResult.Success)?.value as? JsonObject ?: fail()
        keys(root, "format", "version", "createdAtEpochMillis", "projects", "artwork", "compositions", "links", "metadata")
        if (text(root, "format") != "TavernForgeLibraryBackup") fail()
        if (number(root, "version") != 1L) fail(ArchiveError.UNSUPPORTED_VERSION)
        val projects = boundedArray(root, "projects", limits.maxProjects).map { item ->
            val p = obj(item); keys(p, "summary", "mediaType", "documentRevision", "original", "current")
            ArchiveProject(readProjectSummary(obj(p.getValue("summary"))), text(p, "mediaType"), number(p, "documentRevision"),
                readBlob(obj(p.getValue("original"))), readBlob(obj(p.getValue("current"))))
        }
        val artwork = boundedArray(root, "artwork", limits.maxArtwork).map { item ->
            val a = obj(item); keys(a, "summary", "blob")
            ArchiveArtwork(readArtworkSummary(obj(a.getValue("summary"))), readBlob(obj(a.getValue("blob"))))
        }
        val compositions = boundedArray(root, "compositions", limits.maxProjects).map { item ->
            val c = obj(item); keys(c, "characterId", "revision", "artworkId", "sourcePngId", "sourceCandidateId")
            CompositionRecord(text(c, "characterId"), number(c, "revision"), nullableText(c, "artworkId"), nullableText(c, "sourcePngId"), nullableText(c, "sourceCandidateId"))
        }
        val links = boundedArray(root, "links", 10_000).map { item ->
            val l = obj(item); keys(l, "characterId", "lorebookId", "embeddedRevision", "embeddedSha256")
            LoreLinkRecord(text(l, "characterId"), text(l, "lorebookId"), nullableNumber(l, "embeddedRevision"), nullableText(l, "embeddedSha256"))
        }
        val metadata = boundedArray(root, "metadata", limits.maxProjects).map { item ->
            val m = obj(item); keys(m, "projectId", "revision", "tags")
            ProjectMetadata(text(m, "projectId"), number(m, "revision"), array(m, "tags").map { primitiveText(it) })
        }
        return LibraryArchiveManifest(projects, artwork, compositions, links, metadata, number(root, "createdAtEpochMillis")).also { validate(it, limits) }
    }
    fun validate(manifest: LibraryArchiveManifest, limits: ArchiveLimits): Map<String, ArchiveBlobRef> {
        if (manifest.projects.size > limits.maxProjects || manifest.artwork.size > limits.maxArtwork ||
            manifest.compositions.size > limits.maxProjects || manifest.metadata.size > limits.maxProjects || manifest.links.size > 10_000) fail(ArchiveError.LIMIT_EXCEEDED)
        if (manifest.createdAtEpochMillis < 0) fail()
        val projects = manifest.projects.associateBy { it.summary.id }
        val assets = manifest.artwork.associateBy { it.summary.id }
        if (projects.size != manifest.projects.size || assets.size != manifest.artwork.size) fail()
        val blobs = linkedMapOf<String, ArchiveBlobRef>()
        fun add(reference: ArchiveBlobRef, max: Int) {
            if (!hash(reference.sha256) || reference.sizeBytes < 1) fail()
            if (reference.sizeBytes > max) fail(ArchiveError.LIMIT_EXCEEDED)
            val old = blobs.putIfAbsent(reference.sha256, reference)
            if (old != null && old != reference) fail()
        }
        for (p in manifest.projects) {
            val s = p.summary
            if (!id(s.id) || s.title.length > 256 || s.sourceFileName.length > 255 || p.mediaType.length > 255 ||
                s.createdAtEpochMillis < 0 || s.updatedAtEpochMillis < 0 || p.documentRevision < 0 ||
                s.sourceSha256 != p.original.sha256 || s.sourceSizeBytes != p.original.sizeBytes ||
                p.documentRevision == 0L && p.original != p.current) fail()
            add(p.original, limits.maxJsonBytes); add(p.current, limits.maxJsonBytes)
        }
        for (a in manifest.artwork) {
            val s = a.summary
            if (!id(s.id) || s.fileName.length > 255 || s.sha256 != a.blob.sha256 || s.sizeBytes != a.blob.sizeBytes ||
                s.width !in 1..16384 || s.height !in 1..16384 || s.width.toLong() * s.height > 16_777_216 || s.createdAtEpochMillis < 0 ||
                s.mediaType !in setOf("image/png", "image/jpeg")) fail()
            if ((s.sourceArtworkId == null) != (s.transformation == null)) fail()
            if (s.sourceArtworkId != null) {
                val parent = assets[s.sourceArtworkId]?.summary ?: fail()
                if (s.sourceArtworkId == s.id || parent.mediaType != "image/jpeg" || s.mediaType != "image/png" || s.transformation != "android-jpeg-png-1") fail()
            }
            add(a.blob, limits.maxArtworkBytes)
        }
        if (manifest.compositions.map { it.characterId }.toSet().size != manifest.compositions.size ||
            manifest.links.map { it.characterId to it.lorebookId }.toSet().size != manifest.links.size ||
            manifest.metadata.map { it.projectId }.toSet().size != manifest.metadata.size) fail()
        for (c in manifest.compositions) {
            if (c.characterId !in projects || c.revision < 0 || c.artworkId?.let { it !in assets } == true ||
                c.sourcePngId?.let { assets[it]?.summary?.mediaType != "image/png" } == true || c.sourceCandidateId?.length?.let { it > 1024 } == true ||
                (c.sourcePngId == null) != (c.sourceCandidateId == null)) fail()
        }
        for (l in manifest.links) {
            if (l.characterId !in projects || l.lorebookId !in projects || l.characterId == l.lorebookId ||
                (l.embeddedRevision == null) != (l.embeddedSha256 == null) || l.embeddedRevision?.let { it < 0 } == true ||
                l.embeddedSha256?.let { !hash(it) } == true) fail()
        }
        for (m in manifest.metadata) {
            val tags = m.tags
            if (m.projectId !in projects || m.revision < 0 || tags.size > 32 ||
                tags.any { it.isBlank() || it != it.trim() || it.length > 64 || it.any(Char::isISOControl) } ||
                tags.map { it.lowercase(Locale.ROOT) }.toSet().size != tags.size) fail()
        }
        if (blobs.size + 1 > limits.maxEntries || blobs.values.sumOf { it.sizeBytes.toLong() } > limits.maxTotalBlobBytes) fail(ArchiveError.LIMIT_EXCEEDED)
        return blobs
    }
    private fun projectSummary(s: ProjectSummary) = buildJsonObject {
        put("id", s.id); put("title", s.title); put("sourceFileName", s.sourceFileName); put("sourceFormat", s.sourceFormat.name)
        putNullable("specVersion", s.specVersion); put("isDraft", s.isDraft); put("createdAtEpochMillis", s.createdAtEpochMillis)
        put("updatedAtEpochMillis", s.updatedAtEpochMillis); put("sourceSha256", s.sourceSha256); put("sourceSizeBytes", s.sourceSizeBytes)
    }
    private fun artworkSummary(s: ArtworkSummary) = buildJsonObject {
        put("id", s.id); put("fileName", s.fileName); put("sha256", s.sha256); put("sizeBytes", s.sizeBytes); put("width", s.width); put("height", s.height)
        put("isAnimated", s.isAnimated); put("createdAtEpochMillis", s.createdAtEpochMillis); put("mediaType", s.mediaType)
        putNullable("sourceArtworkId", s.sourceArtworkId); putNullable("transformation", s.transformation)
    }
    private fun readProjectSummary(s: JsonObject): ProjectSummary {
        keys(s, "id", "title", "sourceFileName", "sourceFormat", "specVersion", "isDraft", "createdAtEpochMillis", "updatedAtEpochMillis", "sourceSha256", "sourceSizeBytes")
        val format = runCatching { SourceFormat.valueOf(text(s, "sourceFormat")) }.getOrElse { fail() }
        return ProjectSummary(text(s, "id"), text(s, "title"), text(s, "sourceFileName"), format, nullableText(s, "specVersion"),
            bool(s, "isDraft"), number(s, "createdAtEpochMillis"), number(s, "updatedAtEpochMillis"), text(s, "sourceSha256"), int(s, "sourceSizeBytes"))
    }
    private fun readArtworkSummary(s: JsonObject): ArtworkSummary {
        keys(s, "id", "fileName", "sha256", "sizeBytes", "width", "height", "isAnimated", "createdAtEpochMillis", "mediaType", "sourceArtworkId", "transformation")
        return ArtworkSummary(text(s, "id"), text(s, "fileName"), text(s, "sha256"), int(s, "sizeBytes"), int(s, "width"), int(s, "height"),
            bool(s, "isAnimated"), number(s, "createdAtEpochMillis"), text(s, "mediaType"), nullableText(s, "sourceArtworkId"), nullableText(s, "transformation"))
    }
    private fun blob(ref: ArchiveBlobRef) = buildJsonObject { put("sha256", ref.sha256); put("sizeBytes", ref.sizeBytes) }
    private fun readBlob(value: JsonObject): ArchiveBlobRef { keys(value, "sha256", "sizeBytes"); return ArchiveBlobRef(text(value, "sha256"), int(value, "sizeBytes")) }
    private fun JsonObjectBuilder.putNullable(key: String, value: String?) { put(key, value?.let(::JsonPrimitive) ?: JsonNull) }
    private fun keys(value: JsonObject, vararg names: String) { if (value.keys != names.toSet()) fail() }
    private fun obj(value: JsonElement) = value as? JsonObject ?: fail()
    private fun array(value: JsonObject, key: String) = value[key] as? JsonArray ?: fail()
    private fun boundedArray(value: JsonObject, key: String, maximum: Int): JsonArray = array(value, key).also {
        if (it.size > maximum) fail(ArchiveError.LIMIT_EXCEEDED)
    }
    private fun text(value: JsonObject, key: String) = primitiveText(value[key] ?: fail())
    private fun primitiveText(value: JsonElement) = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail()
    private fun nullableText(value: JsonObject, key: String) = if (value[key] == JsonNull) null else text(value, key)
    private fun number(value: JsonObject, key: String): Long {
        val p = value[key] as? JsonPrimitive ?: fail()
        if (p.isString) fail()
        return p.content.toLongOrNull()?.takeIf { it >= 0 } ?: fail()
    }
    private fun nullableNumber(value: JsonObject, key: String) = if (value[key] == JsonNull) null else number(value, key)
    private fun int(value: JsonObject, key: String) = number(value, key).takeIf { it <= Int.MAX_VALUE }?.toInt() ?: fail()
    private fun bool(value: JsonObject, key: String): Boolean = when (value[key]) { JsonPrimitive(true) -> true; JsonPrimitive(false) -> false; else -> fail() }
    private fun id(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,80}"))
    private fun hash(value: String) = value.matches(Regex("[a-f0-9]{64}"))
    private fun fail(code: ArchiveError = ArchiveError.INVALID_MANIFEST): Nothing = throw ArchiveException(code)
}

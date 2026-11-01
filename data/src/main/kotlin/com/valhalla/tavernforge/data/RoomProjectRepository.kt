package com.valhalla.tavernforge.data

import com.valhalla.tavernforge.core.model.LibraryQuery
import com.valhalla.tavernforge.core.model.LibraryEntry
import com.valhalla.tavernforge.core.model.ProjectMetadata
import kotlinx.coroutines.flow.combine
import com.valhalla.tavernforge.core.model.ArtworkSummary
import com.valhalla.tavernforge.core.model.StoredArtwork
import com.valhalla.tavernforge.core.model.ProjectComposition
import com.valhalla.tavernforge.core.model.LinkedLorebook
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.format.png.PngCardEngine
import com.valhalla.tavernforge.format.png.PngContainer
import com.valhalla.tavernforge.format.png.PngInspectionResult
import com.valhalla.tavernforge.format.png.PngSelectionResult
import com.valhalla.tavernforge.format.combine.EmbeddingPlan
import com.valhalla.tavernforge.format.combine.EmbeddingPlanResult
import com.valhalla.tavernforge.format.combine.LorebookEmbeddingEngine
import com.valhalla.tavernforge.core.model.ProjectSummary
import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.StoredProject
import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.CharacterDocument
import com.valhalla.tavernforge.format.ExportResult
import com.valhalla.tavernforge.format.JsonPatch
import com.valhalla.tavernforge.format.ParseResult
import com.valhalla.tavernforge.format.PatchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import com.valhalla.tavernforge.data.archive.*

/**
 * Coordinates immutable managed source files with an atomic metadata index.
 * The production factory provides a single writer. Test/reopen instances sharing live stores
 * must also share this mutex; multiple application processes are not supported.
 */
class RoomProjectRepository(
    private val index: ProjectIndex,
    private val files: ProjectFileStore,
    private val engine: CharacterCardEngine = CharacterCardEngine(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mutex: Mutex = Mutex(),
    private val compositionIndex: CompositionIndex? = null,
    private val assetFiles: ProjectFileStore? = null,
    private val artworkCodec: ArtworkCodec = AndroidArtworkCodec(),
    private val metadataIndex: LibraryMetadataIndex? = null,
) : ProjectRepository {
    init { require((compositionIndex == null) == (assetFiles == null)) }
    private val png = PngCardEngine(cards = engine)
    private val embedding = LorebookEmbeddingEngine(engine)
    private var recovered = false

    override suspend fun <T> withArchiveSnapshot(action: suspend (LibraryArchiveSnapshot) -> T): T = locked {
        ensureRecovered()
        val records = databaseOperation { index.all() }
        val artwork = databaseOperation { compositions().allArtwork() }
        val readers = mutableMapOf<ArchiveBlobRef, () -> ByteArray>()
        val projects = records.map { record ->
            readRecord(record)
            val original = ArchiveBlobRef(record.summary.sourceSha256, record.summary.sourceSizeBytes)
            val current = ArchiveBlobRef(record.currentSha256, record.currentSizeBytes)
            readers[original] = { fileOperation { files.read(record.sourceFileKey, original.sha256, original.sizeBytes).bytes() } }
            readers[current] = { fileOperation { files.read(record.currentFileKey, current.sha256, current.sizeBytes).bytes() } }
            ArchiveProject(record.summary, record.mediaType, record.documentRevision, original, current)
        }
        val assets = artwork.map { record ->
            readArtwork(record)
            val ref = ArchiveBlobRef(record.summary.sha256, record.summary.sizeBytes)
            readers[ref] = { fileOperation { assetStore().read(record.fileKey, ref.sha256, ref.sizeBytes).bytes() } }
            ArchiveArtwork(record.summary, ref)
        }
        val compositions = records.mapNotNull { record -> databaseOperation { compositions().composition(record.summary.id) } }
        val links = records.flatMap { record -> databaseOperation { compositions().links(record.summary.id) } }
        val metadata = records.mapNotNull { record -> metadataIndex?.let { databaseOperation { it.find(record.summary.id) } } }
        val manifest = LibraryArchiveManifest(projects, assets, compositions, links, metadata, clock().coerceAtLeast(0))
        ArchiveManifestCodec.validate(manifest, ArchiveLimits())
        // Reentrant repository mutations are prohibited while the callback owns this snapshot.
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            action(LibraryArchiveSnapshot(manifest) { reference ->
                if (!active.get()) throw ArchiveException(ArchiveError.CLOSED)
                currentCoroutineContext().ensureActive()
                readers[reference]?.invoke() ?: throw ArchiveException(ArchiveError.INVALID_MANIFEST)
            })
        } finally { active.set(false) }
    }

    override suspend fun restoreArchive(archive: VerifiedLibraryArchive): ArchiveRestoreResult = locked {
        ensureRecovered()
        val manifest = archive.manifest
        ArchiveManifestCodec.validate(manifest, ArchiveLimits())
        val roles = manifest.projects.associate { it.summary.id to it.summary.sourceFormat }
        if (manifest.compositions.any { roles[it.characterId] !in CHARACTER_FORMATS } ||
            manifest.links.any { roles[it.characterId] !in CHARACTER_FORMATS || roles[it.lorebookId] !in LORE_FORMATS })
            throw ArchiveException(ArchiveError.INVALID_MANIFEST)
        val composed = compositions()
        val reserved = mutableSetOf<String>()
        suspend fun freshId(): String {
            repeat(32) {
                val id = newId()
                if (PROJECT_ID.matches(id) && id !in reserved && databaseOperation { index.find(id) } == null &&
                    databaseOperation { composed.findArtwork(id) } == null) {
                    reserved += id
                    return id
                }
            }
            throw ArchiveException(ArchiveError.IO_FAILURE)
        }
        val projectIds = mutableMapOf<String, String>()
        val artworkIds = mutableMapOf<String, String>()
        manifest.projects.forEach { projectIds[it.summary.id] = freshId() }
        manifest.artwork.forEach { artworkIds[it.summary.id] = freshId() }
        val projectRecords = mutableListOf<ProjectRecord>()
        val assetRecords = mutableMapOf<String, ArtworkRecord>()
        currentCoroutineContext().ensureActive()
        archive.consume()
        try {
            for (project in manifest.projects) {
                currentCoroutineContext().ensureActive()
                val original = SourceEnvelope(archive.readBlob(project.original), project.mediaType)
                val current = if (project.current == project.original) original else SourceEnvelope(archive.readBlob(project.current), project.mediaType)
                val doc = (engine.restore(original, current, project.documentRevision) as? ParseResult.Success)?.document
                    ?: throw ArchiveException(ArchiveError.INVALID_MANIFEST)
                if (doc.sourceFormat != project.summary.sourceFormat || doc.specVersion != project.summary.specVersion ||
                    project.summary.isDraft == engine.validate(doc).isValid) throw ArchiveException(ArchiveError.INVALID_MANIFEST)
                val originalKey = fileOperation { files.ensureSource(original) }
                val currentKey = fileOperation { files.ensureSource(current) }
                if (originalKey != "${original.sha256}.json" || currentKey != "${current.sha256}.json")
                    throw ArchiveException(ArchiveError.HASH_MISMATCH)
                projectRecords += ProjectRecord(project.summary.copy(id = projectIds.getValue(project.summary.id)), originalKey,
                    project.mediaType, project.documentRevision, currentKey, current.sha256, current.sizeBytes)
            }
            val projectsById = manifest.projects.associateBy { it.summary.id }
            val provenance = manifest.compositions.filter { it.sourcePngId != null }.groupBy { it.sourcePngId }
            for (asset in manifest.artwork) {
                currentCoroutineContext().ensureActive()
                val bytes = archive.readBlob(asset.blob)
                val info = inspectArtwork(bytes)
                val old = asset.summary
                if (info.mediaType != old.mediaType || info.width != old.width || info.height != old.height || info.animated != old.isAnimated)
                    throw ArchiveException(ArchiveError.INVALID_MANIFEST)
                provenance[old.id]?.let { references ->
                    if (info.mediaType != "image/png") throw ArchiveException(ArchiveError.INVALID_MANIFEST)
                    val container = inspectPng(bytes)
                    references.forEach { reference ->
                        val selected = png.select(container, reference.sourceCandidateId) as? PngSelectionResult.Selected
                        if (selected?.document?.source?.sha256 != projectsById.getValue(reference.characterId).original.sha256)
                            throw ArchiveException(ArchiveError.INVALID_MANIFEST)
                    }
                }
                val source = SourceEnvelope(bytes, old.mediaType)
                val key = fileOperation { assetStore().ensureSource(source) }
                val expectedKey = "${source.sha256}.${if (source.mediaType == "image/jpeg") "jpg" else "png"}"
                if (key != expectedKey) throw ArchiveException(ArchiveError.HASH_MISMATCH)
                assetRecords[old.id] = ArtworkRecord(old.copy(id = artworkIds.getValue(old.id),
                    sourceArtworkId = old.sourceArtworkId?.let(artworkIds::getValue)), key)
            }
            val ordered = mutableListOf<ArtworkRecord>()
            val pending = manifest.artwork.toMutableList()
            val added = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val ready = pending.filter { it.summary.sourceArtworkId == null || it.summary.sourceArtworkId in added }
                if (ready.isEmpty()) throw ArchiveException(ArchiveError.INVALID_MANIFEST)
                ready.forEach { ordered += assetRecords.getValue(it.summary.id); added += it.summary.id }
                pending.removeAll(ready.toSet())
            }
            val batch = LibraryRestoreBatch(projectRecords, ordered,
                manifest.compositions.map { it.copy(characterId = projectIds.getValue(it.characterId),
                    artworkId = it.artworkId?.let(artworkIds::getValue), sourcePngId = it.sourcePngId?.let(artworkIds::getValue)) },
                manifest.links.map { it.copy(characterId = projectIds.getValue(it.characterId), lorebookId = projectIds.getValue(it.lorebookId)) },
                manifest.metadata.map { ProjectMetadata(projectIds.getValue(it.projectId), it.revision, it.tags) })
            currentCoroutineContext().ensureActive()
            // Cancellation remains effective until the bounded final Room transaction starts.
            withContext(NonCancellable) { databaseOperation { composed.restoreArchiveBatch(batch) } }
            ArchiveRestoreResult(projectIds.toMap(), artworkIds.toMap())
        } catch (failure: Exception) {
            // A commit may succeed before reporting a failure. Prune only from actual references.
            withContext(NonCancellable) { cleanup(strict = false) }
            throw failure
        }
    }

    override fun observeProjects(): Flow<List<ProjectSummary>> = flow {
        recover()
        emitAll(index.observe().map { records -> records.map { it.summary } }.catch { failure ->
            throw databaseFailure(failure)
        })
    }.flowOn(dispatcher)

    override fun observeLibrary(query: LibraryQuery): Flow<List<LibraryEntry>> {
        val ownedQuery = query.copy(formats = query.formats.toSet(), tags = query.tags.toSet())
        val metadata = metadataIndex ?: return observeProjects().map { filterLibrary(it, emptyList(), ownedQuery) }
        return observeProjects().combine(metadata.observe()) { projects, labels -> filterLibrary(projects, labels, ownedQuery) }
            .catch { throw databaseFailure(it) }.flowOn(dispatcher)
    }
    override suspend fun projectMetadata(id: String): ProjectMetadata = locked {
        ensureRecovered(); find(id)
        databaseOperation { metadataIndex?.find(id) } ?: ProjectMetadata(id, 0, emptyList())
    }
    override suspend fun setProjectTags(id: String, expectedMetadataRevision: Long, tags: List<String>): ProjectMetadata = locked {
        ensureRecovered(); find(id)
        val metadata = metadataIndex ?: invalidComposition("Local metadata storage is not configured.")
        val normalized = normalizeProjectTags(tags)
        val old = databaseOperation { metadata.find(id) } ?: ProjectMetadata(id, 0, emptyList())
        if (old.revision != expectedMetadataRevision || old.revision == Long.MAX_VALUE) revisionConflict()
        if (old.tags == normalized) return@locked old
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) { databaseOperation { metadata.setTags(id, expectedMetadataRevision, normalized) } }
        databaseOperation { metadata.find(id) } ?: corrupt("The saved local tag record is unavailable.")
    }

    /** Recovery never infers references from a partial or failed database read. */
    suspend fun recover() = locked {
        cleanup(strict = true)
        recovered = true
    }

    override suspend fun saveImported(fileName: String, source: SourceEnvelope): ProjectSummary = locked {
        if (source.mediaType.length > MAX_MEDIA_TYPE_CHARS) {
            throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, "The source media type exceeds the configured metadata limit.")
        }
        if (source.sizeBytes > engine.limits.maxBytes) {
            throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, "The source exceeds the configured JSON limit.")
        }
        val parsed = engine.parse(source.bytes()) as? ParseResult.Success
            ?: throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, "Only bounded, parseable JSON objects can be saved.")
        val document = parsed.document
        val safeFileName = fileName.substringAfterLast('/').substringAfterLast('\\').trim().take(MAX_FILE_NAME_CHARS)
            .ifBlank { "import.json" }
        val title = document.displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: safeFileName.removeSuffix(".json").ifBlank { "Untitled project" }
        val now = clock().coerceAtLeast(0)
        val summary = ProjectSummary(
            id = allocateId(), title = title.take(MAX_TITLE_CHARS), sourceFileName = safeFileName,
            sourceFormat = document.sourceFormat, specVersion = document.specVersion,
            isDraft = !engine.validate(document).isValid, createdAtEpochMillis = now, updatedAtEpochMillis = now,
            sourceSha256 = source.sha256, sourceSizeBytes = source.sizeBytes,
        )
        ensureRecovered()
        currentCoroutineContext().ensureActive()
        // Once this critical section begins, cancellation may report after a successful commit.
        // Completing or rolling back the bounded save takes precedence over abandoning references.
        withContext(NonCancellable) {
            try {
                val key = fileOperation { files.ensureSource(source) }
                if (key != "${source.sha256}.json") corrupt("The managed source reference does not match its imported bytes.")
                val record = ProjectRecord(summary, key, source.mediaType)
                databaseOperation { index.insert(record) }
                summary
            } catch (failure: Exception) {
                // insert() may have committed and then thrown. Re-read ALL references before cleanup.
                cleanup(strict = false)
                throw failure
            }
        }
    }

    override suspend fun load(id: String): StoredProject = locked {
        ensureRecovered()
        loadRecord(find(id))
    }

    override suspend fun applyEdits(id: String, expectedRevision: Long, operations: List<JsonPatch>): StoredProject = locked {
        ensureRecovered()
        val old = find(id)
        if (old.documentRevision != expectedRevision) revisionConflict()
        val (stored, document) = readRecord(old)
        if (!document.isSupportedFormat) {
            throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, "Editing this source format is not supported. Its original has been retained.")
        }
        if (operations.isEmpty()) return@locked stored
        val patched = (engine.patch(document, expectedRevision, operations) as? PatchResult.Success)?.document
            ?: throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, "The changes could not be applied within the JSON and source-format rules.")
        val snapshot = engine.snapshot(patched) as? ExportResult.Success
            ?: throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, "The edited draft could not be stored within the JSON limits.")
        val current = SourceEnvelope(snapshot.bytes, old.mediaType)
        val summary = old.summary.copy(isDraft = !engine.validate(patched).isValid,
            updatedAtEpochMillis = maxOf(clock().coerceAtLeast(0), old.summary.updatedAtEpochMillis))
        currentCoroutineContext().ensureActive()
        // Finalize bytes before the conditional DB mutation. A late failure may be ambiguous,
        // so cleanup must re-read every original and current reference before pruning anything.
        withContext(NonCancellable) {
            try {
                val key = fileOperation { files.ensureSource(current) }
                if (key != "${current.sha256}.json") corrupt("The edited snapshot reference does not match its bytes.")
                val count = databaseOperation {
                    index.updateContent(id, expectedRevision, key, current.sha256, current.sizeBytes,
                        summary.isDraft, summary.updatedAtEpochMillis)
                }
                if (count != 1) revisionConflict()
                cleanup(strict = false)
                StoredProject(summary, stored.source, current, patched.revision)
            } catch (failure: Exception) {
                cleanup(strict = false)
                throw failure
            }
        }
    }

    override suspend fun rename(id: String, title: String) = locked {
        val normalized = title.trim()
        if (normalized.isBlank() || normalized.length > MAX_TITLE_CHARS) {
            throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, "Enter a project title of 1 to $MAX_TITLE_CHARS characters.")
        }
        ensureRecovered()
        val old = find(id)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            val count = databaseOperation { index.rename(id, normalized, maxOf(clock(), old.summary.updatedAtEpochMillis)) }
            if (count != 1) notFound()
        }
    }

    override suspend fun duplicate(id: String): ProjectSummary = locked {
        ensureRecovered()
        val original = find(id)
        loadRecord(original) // Never create another visible row pointing at missing/corrupt bytes.
        val now = clock().coerceAtLeast(0)
        val summary = original.summary.copy(
            id = allocateId(), title = original.summary.title.take(MAX_TITLE_CHARS - COPY_SUFFIX.length) + COPY_SUFFIX,
            createdAtEpochMillis = now, updatedAtEpochMillis = now,
        )
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            databaseOperation {
                if (compositionIndex != null) compositionIndex.duplicate(original.copy(summary = summary), id)
                else index.insert(original.copy(summary = summary))
            }
            summary
        }
    }

    override suspend fun delete(id: String) = locked {
        ensureRecovered()
        find(id)
        if (compositionIndex != null && databaseOperation { compositionIndex.lorebookInUse(id) }) inUse()
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            try {
                if (databaseOperation { index.delete(id) } != 1) notFound()
            } finally {
                // A shared source remains while any row references it. Cleanup failure leaves orphans.
                cleanup(strict = false)
            }
        }
    }

    override fun observeArtwork(): Flow<List<ArtworkSummary>> = flow {
        recover()
        emitAll(compositions().observeArtwork().map { rows -> rows.map { it.summary } }.catch { throw databaseFailure(it) })
    }.flowOn(dispatcher)

    override suspend fun saveArtwork(fileName: String, bytes: ByteArray): ArtworkSummary = locked {
        ensureRecovered()
        if (bytes.size > png.limits.maxBytes) invalidComposition("Artwork exceeds the configured input byte budget.")
        val owned = bytes.copyOf()
        val info = inspectArtwork(owned)
        val source = SourceEnvelope(owned, info.mediaType)
        val record = newArtwork(fileName, source, info.width, info.height, info.animated)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            try {
                finalizeArtwork(record, source)
                databaseOperation { compositions().insertArtwork(record) }
                record.summary
            } catch (failure: Exception) { cleanup(strict = false); throw failure }
        }
    }

    override suspend fun loadArtwork(id: String): StoredArtwork = locked {
        ensureRecovered()
        readArtwork(findArtwork(id))
    }

    override suspend fun deleteArtwork(id: String) = locked {
        ensureRecovered()
        findArtwork(id)
        if (databaseOperation { compositions().artworkInUse(id) }) inUse()
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            try { if (databaseOperation { compositions().deleteArtwork(id) } != 1) notFound() }
            finally { cleanup(strict = false) }
        }
    }

    override suspend fun savePngCard(fileName: String, bytes: ByteArray, candidateId: String?): StoredProject = locked {
        ensureRecovered()
        val container = inspectPng(bytes)
        val selected = png.select(container, candidateId) as? PngSelectionResult.Selected
            ?: invalidComposition("Choose a parseable character metadata candidate before saving a PNG card. Metadata-free images can be saved as artwork.")
        val document = selected.document
        if (document.sourceFormat !in CHARACTER_FORMATS) invalidComposition("This metadata is not a supported character. Save the PNG as artwork and recover its JSON separately.")
        val artwork = newArtwork(fileName, SourceEnvelope(container.bytes(), "image/png"), container.width, container.height, container.isAnimated)
        val source = document.source
        val now = clock().coerceAtLeast(0)
        val safeName = safeName(fileName, "card.png")
        val summary = ProjectSummary(allocateId(), document.displayName?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TITLE_CHARS)
            ?: safeName.removeSuffix(".png"), safeName, document.sourceFormat, document.specVersion,
            !engine.validate(document).isValid, now, now, source.sha256, source.sizeBytes)
        val project = ProjectRecord(summary, "${source.sha256}.json")
        val composition = CompositionRecord(summary.id, artworkId = artwork.summary.id, sourcePngId = artwork.summary.id,
            sourceCandidateId = selected.candidate.id)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            try {
                finalizeArtwork(artwork, SourceEnvelope(container.bytes(), "image/png"))
                val key = fileOperation { files.ensureSource(source) }
                if (key != project.sourceFileKey) corrupt("The extracted JSON reference does not match its bytes.")
                databaseOperation { compositions().insertPng(project, artwork, composition) }
                StoredProject(summary, source)
            } catch (failure: Exception) { cleanup(strict = false); throw failure }
        }
    }

    override suspend fun loadComposition(characterId: String): ProjectComposition = locked {
        ensureRecovered()
        readComposition(characterId)
    }

    override suspend fun setArtwork(characterId: String, expectedCompositionRevision: Long, assetId: String?): ProjectComposition = locked {
        ensureRecovered()
        requireCharacter(find(characterId))
        assetId?.let { readArtwork(findArtwork(it)) }
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) { databaseOperation { compositions().setArtwork(characterId, expectedCompositionRevision, assetId) } }
        readComposition(characterId)
    }

    override suspend fun linkLorebook(characterId: String, expectedCompositionRevision: Long, lorebookId: String): ProjectComposition =
        changeLoreLink(characterId, expectedCompositionRevision, lorebookId, false)

    override suspend fun unlinkLorebook(characterId: String, expectedCompositionRevision: Long, lorebookId: String): ProjectComposition =
        changeLoreLink(characterId, expectedCompositionRevision, lorebookId, true)

    private suspend fun changeLoreLink(characterId: String, expectedRevision: Long, lorebookId: String, remove: Boolean): ProjectComposition = locked {
        ensureRecovered()
        requireCharacter(find(characterId))
        val lore = find(lorebookId)
        if (lore.summary.sourceFormat !in LORE_FORMATS) invalidComposition("Select a supported standalone lorebook or World Info project.")
        loadRecord(lore)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) { databaseOperation { compositions().linkLorebook(characterId, expectedRevision, lorebookId, remove) } }
        readComposition(characterId)
    }

    override suspend fun prepareEmbedding(characterId: String, lorebookId: String): EmbeddingPlanResult = locked {
        ensureRecovered()
        val character = readRecord(find(characterId)).second
        val lore = readRecord(find(lorebookId)).second
        embedding.plan(character, lore)
    }

    override suspend fun applyEmbedding(characterId: String, expectedCompositionRevision: Long, lorebookId: String,
        plan: EmbeddingPlan, acknowledgedReviewItems: Set<String>): StoredProject = locked {
        ensureRecovered()
        val old = find(characterId)
        val loreRecord = find(lorebookId)
        if (old.documentRevision != plan.characterRevision || old.currentSha256 != plan.characterSha256 ||
            loreRecord.documentRevision != plan.lorebookRevision || loreRecord.currentSha256 != plan.lorebookSha256) revisionConflict()
        val (stored, document) = readRecord(old)
        val lore = readRecord(loreRecord).second
        val refreshed = embedding.plan(document, lore) as? EmbeddingPlanResult.Ready
            ?: invalidComposition("The selected lorebook cannot be directly embedded in this character format.")
        if (refreshed.plan.reviewItems != plan.reviewItems) revisionConflict()
        val patched = (embedding.accept(refreshed.plan, acknowledgedReviewItems) as? PatchResult.Success)?.document
            ?: invalidComposition("Review the exact embedded-book replacement and omissions before saving.")
        val snapshot = engine.snapshot(patched) as? ExportResult.Success ?: invalidComposition("The embedded snapshot exceeds the JSON limits.")
        val current = SourceEnvelope(snapshot.bytes, old.mediaType)
        val summary = old.summary.copy(isDraft = !engine.validate(patched).isValid,
            updatedAtEpochMillis = maxOf(clock().coerceAtLeast(0), old.summary.updatedAtEpochMillis))
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            try {
                val key = fileOperation { files.ensureSource(current) }
                if (key != "${current.sha256}.json") corrupt("The embedded snapshot reference does not match its bytes.")
                val updated = old.copy(summary = summary, currentFileKey = key, currentSha256 = current.sha256,
                    currentSizeBytes = current.sizeBytes, documentRevision = patched.revision)
                databaseOperation { compositions().commitEmbedding(updated, old.documentRevision, expectedCompositionRevision,
                    lorebookId, loreRecord.documentRevision, loreRecord.currentSha256) }
                cleanup(strict = false)
                StoredProject(summary, stored.source, current, patched.revision)
            } catch (failure: Exception) { cleanup(strict = false); throw failure }
        }
    }

    private suspend fun readComposition(id: String): ProjectComposition {
        val character = find(id)
        requireCharacter(character)
        val composition = databaseOperation { compositions().composition(id) } ?: CompositionRecord(id)
        val links = databaseOperation { compositions().links(id) }.map { link ->
            val lore = find(link.lorebookId)
            LinkedLorebook(lore.summary, lore.documentRevision, link.embeddedRevision, link.embeddedSha256)
        }
        return ProjectComposition(id, character.documentRevision, composition.revision,
            composition.artworkId?.let { findArtwork(it).summary }, composition.sourcePngId?.let { findArtwork(it).summary },
            composition.sourceCandidateId, links)
    }
    private fun requireCharacter(record: ProjectRecord) {
        if (record.summary.sourceFormat !in CHARACTER_FORMATS) invalidComposition("Artwork and lorebook associations require a character project.")
    }
    private fun inspectPng(bytes: ByteArray): PngContainer = (png.inspect(bytes) as? PngInspectionResult.Success)?.container
        ?: invalidComposition("The PNG is corrupt, unsupported or exceeds the configured image budget.")
    private data class ImageInfo(val mediaType: String, val width: Int, val height: Int, val animated: Boolean = false)
    private fun inspectArtwork(bytes: ByteArray): ImageInfo {
        if (bytes.size > png.limits.maxBytes) invalidComposition("Artwork exceeds the configured input byte budget.")
        return if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) {
            val image = fileOperation { artworkCodec.inspectJpeg(bytes) }
            if (image.width !in 1..png.limits.maxDimension || image.height !in 1..png.limits.maxDimension || image.width.toLong() * image.height > png.limits.maxPixels)
                invalidComposition("JPEG dimensions exceed the configured image budget.")
            ImageInfo("image/jpeg", image.width, image.height)
        } else {
            val image = inspectPng(bytes)
            ImageInfo("image/png", image.width, image.height, image.isAnimated)
        }
    }
    private suspend fun newArtwork(fileName: String, source: SourceEnvelope, width: Int, height: Int, animated: Boolean,
        sourceArtworkId: String? = null, transformation: String? = null): ArtworkRecord {
        val id = allocateId()
        if (databaseOperation { compositions().findArtwork(id) } != null) invalidComposition("A unique artwork identifier could not be generated.")
        val extension = if (source.mediaType == "image/jpeg") "jpg" else "png"
        return ArtworkRecord(ArtworkSummary(id, safeName(fileName, "artwork.$extension"), source.sha256, source.sizeBytes,
            width, height, animated, clock().coerceAtLeast(0), source.mediaType, sourceArtworkId, transformation), "${source.sha256}.$extension")
    }
    private fun finalizeArtwork(record: ArtworkRecord, source: SourceEnvelope) {
        val key = fileOperation { assetStore().ensureSource(source) }
        if (key != record.fileKey) corrupt("The artwork reference does not match its bytes.")
    }
    private suspend fun findArtwork(id: String) = databaseOperation { compositions().findArtwork(id) } ?: notFound()
    private fun readArtwork(record: ArtworkRecord): StoredArtwork {
        val source = fileOperation { assetStore().read(record.fileKey, record.summary.sha256, record.summary.sizeBytes) }
        val inspected = inspectArtwork(source.bytes())
        if (inspected.width != record.summary.width || inspected.height != record.summary.height || inspected.animated != record.summary.isAnimated ||
            inspected.mediaType != record.summary.mediaType || source.mediaType != record.summary.mediaType)
            corrupt("Stored artwork metadata does not match its preserved image.")
        return StoredArtwork(record.summary, source.bytes())
    }
    override suspend fun prepareArtworkPngConversion(assetId: String): ArtworkPngConversionPlan = locked {
        ensureRecovered()
        val asset = readArtwork(findArtwork(assetId))
        if (asset.summary.mediaType != "image/jpeg") invalidComposition("Only JPEG artwork requires this explicit PNG conversion.")
        val converted = fileOperation { artworkCodec.jpegToPng(asset.bytes()) }
        val image = inspectPng(converted.bytes())
        ArtworkPngConversionPlan(asset.summary, image.bytes(), image.width, image.height, converted.orientation)
    }
    override suspend fun acceptArtworkPngConversion(plan: ArtworkPngConversionPlan, acknowledgedReviewItems: Set<String>): ArtworkSummary = locked {
        ensureRecovered()
        val original = readArtwork(findArtwork(plan.sourceArtwork.id))
        if (original.summary != plan.sourceArtwork) revisionConflict()
        if (acknowledgedReviewItems != plan.reviewItems.toSet()) invalidComposition("Review the exact JPEG pixel conversion and omitted PNG metadata before saving.")
        val image = inspectPng(plan.bytes())
        if (image.sha256 != plan.outputSha256 || original.summary.mediaType != "image/jpeg") invalidComposition("The conversion plan no longer matches its inputs.")
        val name = original.summary.fileName.substringBeforeLast('.', original.summary.fileName) + "-converted.png"
        val source = SourceEnvelope(image.bytes(), "image/png")
        val record = newArtwork(name, source, image.width, image.height, false, original.summary.id, "android-jpeg-png-1")
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            try {
                finalizeArtwork(record, source)
                databaseOperation { compositions().insertArtwork(record) }
                record.summary
            } catch (failure: Exception) { cleanup(strict = false); throw failure }
        }
    }
    private fun safeName(name: String, fallback: String): String = name.substringAfterLast('/').substringAfterLast('\\').trim().take(MAX_FILE_NAME_CHARS).ifBlank { fallback }
    private fun compositions() = compositionIndex ?: invalidComposition("Artwork storage is not configured.")
    private fun assetStore() = assetFiles ?: invalidComposition("Artwork storage is not configured.")
    private fun invalidComposition(message: String): Nothing = throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, message)
    private fun inUse(): Nothing = throw ProjectStorageException(ProjectStorageError.IN_USE,
        "This item is referenced by another project. Review and remove its links first; imported source provenance must be retained.")

    private suspend fun loadRecord(record: ProjectRecord): StoredProject = readRecord(record).first

    private suspend fun readRecord(record: ProjectRecord): Pair<StoredProject, CharacterDocument> {
        if (record.documentRevision < 0L || record.mediaType.length > MAX_MEDIA_TYPE_CHARS) {
            corrupt("The stored revision or source metadata is invalid. Its files have been retained.")
        }
        val originalBytes = fileOperation {
            files.read(record.sourceFileKey, record.summary.sourceSha256, record.summary.sourceSizeBytes)
        }
        val source = if (originalBytes.mediaType == record.mediaType) originalBytes
            else SourceEnvelope(originalBytes.bytes(), record.mediaType)
        val current = if (record.currentFileKey == record.sourceFileKey &&
            record.currentSha256 == record.summary.sourceSha256 && record.currentSizeBytes == record.summary.sourceSizeBytes) source
        else {
            val bytes = fileOperation { files.read(record.currentFileKey, record.currentSha256, record.currentSizeBytes) }
            if (bytes.mediaType == record.mediaType) bytes else SourceEnvelope(bytes.bytes(), record.mediaType)
        }
        val restored = engine.restore(source, current, record.documentRevision) as? ParseResult.Success
            ?: corrupt("The stored JSON snapshots cannot be restored safely. Their files have been retained.")
        if (restored.document.sourceFormat != record.summary.sourceFormat || restored.document.specVersion != record.summary.specVersion) {
            corrupt("The stored source and project format metadata do not match. Its source has been retained.")
        }
        return StoredProject(record.summary, source, current, record.documentRevision) to restored.document
    }

    private suspend fun allocateId(): String {
        val id = newId()
        if (!PROJECT_ID.matches(id)) throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, "A valid project identifier could not be generated.")
        if (databaseOperation { index.find(id) } != null) {
            throw ProjectStorageException(ProjectStorageError.DATABASE_FAILURE, "A unique project identifier could not be generated.")
        }
        return id
    }

    private suspend fun find(id: String): ProjectRecord = databaseOperation { index.find(id) } ?: notFound()

    private suspend fun ensureRecovered() {
        if (!recovered) {
            cleanup(strict = true)
            recovered = true
        }
    }

    private suspend fun cleanup(strict: Boolean) {
        val referenced = try {
            databaseOperation { index.all() }.flatMapTo(mutableSetOf()) { listOf(it.sourceFileKey, it.currentFileKey) }
        } catch (failure: Exception) {
            if (failure is CancellationException || strict) throw failure
            // Never infer a reference set from an incomplete or failed database read.
            return
        }
        val assetReferences = try {
            compositionIndex?.let { databaseOperation { it.allArtwork() }.mapTo(mutableSetOf()) { asset -> asset.fileKey } }
        } catch (failure: Exception) {
            if (failure is CancellationException || strict) throw failure
            return
        }
        try {
            fileOperation { files.prune(referenced) }
            if (assetReferences != null) fileOperation { assetStore().prune(assetReferences) }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // Cleanup is best-effort even at startup: an undeletable orphan or bad reference must
            // not block access to healthy projects. Specific referenced corruption fails on load.
        }
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(dispatcher) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            block()
        }
    }

    private suspend fun <T> databaseOperation(block: suspend () -> T): T = try { block() } catch (failure: Exception) {
        throw databaseFailure(failure)
    }

    private fun databaseFailure(failure: Throwable): Throwable = when (failure) {
        is CancellationException, is ProjectStorageException -> failure
        else -> ProjectStorageException(ProjectStorageError.DATABASE_FAILURE, "The project library could not be read or updated. Please try again.", failure)
    }

    private fun <T> fileOperation(block: () -> T): T = try { block() } catch (failure: Exception) {
        when (failure) {
            is CancellationException, is ProjectStorageException -> throw failure
            else -> throw ProjectStorageException(ProjectStorageError.IO_FAILURE, "Project storage could not be read or written. Check available device storage and try again.", failure)
        }
    }

    private fun notFound(): Nothing = throw ProjectStorageException(ProjectStorageError.NOT_FOUND, "This saved project is no longer available.")
    private fun revisionConflict(): Nothing = throw ProjectStorageException(ProjectStorageError.REVISION_CONFLICT,
        "This project changed since editing began. Reopen its latest revision before saving changes.")
    private fun corrupt(message: String): Nothing = throw ProjectStorageException(ProjectStorageError.CORRUPT_SOURCE, message)

    companion object {
        private val CHARACTER_FORMATS = setOf(SourceFormat.V1, SourceFormat.V2, SourceFormat.V3)
        private val LORE_FORMATS = setOf(SourceFormat.LOREBOOK_V2, SourceFormat.LOREBOOK_V3, SourceFormat.WORLD_INFO)
        const val MAX_TITLE_CHARS = 256
        private const val MAX_FILE_NAME_CHARS = 255
        private const val MAX_MEDIA_TYPE_CHARS = 255
        private const val COPY_SUFFIX = " (copy)"
        private val PROJECT_ID = Regex("[A-Za-z0-9_-]{1,80}")
    }
}

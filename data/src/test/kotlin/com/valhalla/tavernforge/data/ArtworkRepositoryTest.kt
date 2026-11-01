package com.valhalla.tavernforge.data

import com.valhalla.tavernforge.core.model.JsonLimits
import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.format.JsonPatch
import com.valhalla.tavernforge.format.combine.EmbeddingPlanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** File/repository contract tests; Room transaction and migration evidence belongs to device tests. */
class ArtworkRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val index = FaultIndex()
    private val associations = AssociationMemoryIndex(index)
    private val jsonRoot by lazy { temp.newFolder("json") }
    private val assetRoot by lazy { temp.newFolder("assets") }
    private val jsonFiles by lazy { ManagedJsonFiles(jsonRoot) }
    private val assetFiles by lazy { FaultFiles(ManagedJsonFiles(assetRoot, JsonLimits(maxBytes = 32 * 1024 * 1024), "png", "image/png")) }
    private var id = 0
    private fun repository() = RoomProjectRepository(index, jsonFiles, newId = { "item-${++id}" }, dispatcher = Dispatchers.Unconfined,
        compositionIndex = associations, assetFiles = assetFiles)
    private suspend fun fails(code: ProjectStorageError, block: suspend () -> Unit) {
        try { block(); fail("Expected $code") } catch (failure: ProjectStorageException) { assertEquals(code, failure.code) }
    }
    @Test fun artworkOnlyPersistsWithoutInventingACharacterAndReopens() = runBlocking<Unit> {
        val bytes = png()
        val asset = repository().saveArtwork("/provider/art.png", bytes)
        assertTrue(index.records.isEmpty())
        assertEquals("art.png", asset.fileName)
        assertArrayEquals(bytes, repository().loadArtwork(asset.id).bytes())
        assertEquals(listOf(asset), repository().observeArtwork().first())
        repository().deleteArtwork(asset.id)
        assertTrue(assetRoot.listFiles().orEmpty().isEmpty())
    }
    @Test fun pngImportAtomicallySavesExtractedOriginalAndImmutableSourceProvenance() = runBlocking<Unit> {
        val bytes = png(preservedV1)
        val project = repository().savePngCard("card.png", bytes)
        val composition = repository().loadComposition(project.summary.id)
        assertEquals(composition.artwork?.id, composition.sourcePng?.id)
        assertNotNull(composition.sourceCandidateId)
        assertArrayEquals(preservedV1, repository().load(project.summary.id).source.bytes())
        assertArrayEquals(bytes, repository().loadArtwork(composition.sourcePng!!.id).bytes())
        val replacement = repository().saveArtwork("other.png", png())
        val changed = repository().setArtwork(project.summary.id, 0, replacement.id)
        assertEquals(replacement.id, changed.artwork?.id)
        assertEquals(composition.sourcePng?.id, changed.sourcePng?.id)
        fails(ProjectStorageError.IN_USE) { repository().deleteArtwork(composition.sourcePng!!.id) }
    }
    @Test fun failedAssetWriteCreatesNoVisibleHalfImport() = runBlocking<Unit> {
        assetFiles.beforeEnsure = { throw java.io.IOException("private provider detail") }
        fails(ProjectStorageError.IO_FAILURE) { repository().savePngCard("card.png", png(preservedV1)) }
        assertTrue(index.records.isEmpty()); assertTrue(associations.assets.isEmpty())
    }
    @Test fun failedAtomicInsertPrunesOnlyUnreferencedFilesAndAmbiguousCommitRetainsBoth() = runBlocking<Unit> {
        associations.beforeInsert = { error("reject") }
        fails(ProjectStorageError.DATABASE_FAILURE) { repository().savePngCard("card.png", png(preservedV1)) }
        assertTrue(index.records.isEmpty()); assertTrue(jsonRoot.listFiles().orEmpty().isEmpty()); assertTrue(assetRoot.listFiles().orEmpty().isEmpty())
        associations.beforeInsert = {}
        associations.afterInsert = { error("committed then lost acknowledgement") }
        fails(ProjectStorageError.DATABASE_FAILURE) { repository().savePngCard("card.png", png(preservedV1)) }
        assertEquals(1, index.records.size); assertEquals(1, associations.assets.size)
        assertArrayEquals(preservedV1, repository().load(index.records.keys.single()).source.bytes())
        assertArrayEquals(png(preservedV1), repository().loadArtwork(associations.assets.keys.single()).bytes())
    }
    @Test fun incompleteAssetReferenceReadNeverPrunesJsonOrArtwork() = runBlocking<Unit> {
        val repo = repository()
        val saved = repo.savePngCard("card.png", png(preservedV1))
        val jsonOrphan = jsonFiles.ensureSource(SourceEnvelope("{\"orphan\":1}".encodeToByteArray()))
        associations.failAll = true
        fails(ProjectStorageError.DATABASE_FAILURE) { repo.recover() }
        assertTrue(java.io.File(jsonRoot, jsonOrphan).exists())
        assertTrue(assetRoot.listFiles().orEmpty().isNotEmpty())
        associations.failAll = false
        assertArrayEquals(preservedV1, repo.load(saved.summary.id).source.bytes())
    }
    @Test fun sharedLoreLinksAreIdempotentAndBlockDeletionUntilExplicitUnlink() = runBlocking<Unit> {
        val repo = repository()
        val character = repo.saveImported("character.json", source())
        val lore = repo.saveImported("lore.json", SourceEnvelope(LORE.encodeToByteArray()))
        val linked = repo.linkLorebook(character.id, 0, lore.id)
        assertEquals(1L, linked.compositionRevision)
        assertEquals(1L, repo.linkLorebook(character.id, 1, lore.id).compositionRevision)
        fails(ProjectStorageError.REVISION_CONFLICT) { repo.unlinkLorebook(character.id, 0, lore.id) }
        fails(ProjectStorageError.IN_USE) { repo.delete(lore.id) }
        assertTrue(repo.unlinkLorebook(character.id, 1, lore.id).lorebooks.isEmpty())
        repo.delete(lore.id)
        fails(ProjectStorageError.INVALID_INPUT) { repo.linkLorebook(character.id, 2, character.id) }
    }
    @Test fun duplicateSharesArtworkAndLoreButOwnsItsCharacterDocument() = runBlocking<Unit> {
        val repo = repository()
        val saved = repo.savePngCard("card.png", png(preservedV1))
        val lore = repo.saveImported("lore.json", SourceEnvelope(LORE.encodeToByteArray()))
        val composition = repo.linkLorebook(saved.summary.id, 0, lore.id)
        val copy = repo.duplicate(saved.summary.id)
        val duplicated = repo.loadComposition(copy.id)
        assertEquals(composition.artwork, duplicated.artwork)
        assertEquals(composition.lorebooks, duplicated.lorebooks)
        repo.applyEdits(copy.id, 0, listOf(JsonPatch.Replace("/name", JsonPrimitive("Independent"))))
        assertArrayEquals(preservedV1, repo.load(saved.summary.id).currentSource.bytes())
    }
    @Test fun embeddingChecksCharacterLoreAndCompositionRevisionsAndRetainsOriginals() = runBlocking<Unit> {
        val repo = repository()
        val character = repo.saveImported("v2.json", SourceEnvelope(V2.encodeToByteArray()))
        val lore = repo.saveImported("lore.json", SourceEnvelope(LORE.encodeToByteArray()))
        val plan = (repo.prepareEmbedding(character.id, lore.id) as EmbeddingPlanResult.Ready).plan
        val embedded = repo.applyEmbedding(character.id, 0, lore.id, plan, plan.reviewItems.toSet())
        assertEquals(1L, embedded.documentRevision)
        assertArrayEquals(V2.encodeToByteArray(), embedded.source.bytes())
        assertTrue(embedded.currentSource.bytes().decodeToString().contains("1.2300e+09"))
        val linked = repo.loadComposition(character.id)
        assertEquals(0L, linked.lorebooks.single().embeddedRevision)
        assertEquals(lore.sourceSha256, linked.lorebooks.single().embeddedSha256)
        fails(ProjectStorageError.REVISION_CONFLICT) { repo.applyEmbedding(character.id, 1, lore.id, plan, plan.reviewItems.toSet()) }
        val next = (repo.prepareEmbedding(character.id, lore.id) as EmbeddingPlanResult.Ready).plan
        repo.applyEdits(lore.id, 0, listOf(JsonPatch.Add("/name", JsonPrimitive("Changed lore"))))
        fails(ProjectStorageError.REVISION_CONFLICT) { repo.applyEmbedding(character.id, 1, lore.id, next, next.reviewItems.toSet()) }
        val latest = (repo.prepareEmbedding(character.id, lore.id) as EmbeddingPlanResult.Ready).plan
        fails(ProjectStorageError.REVISION_CONFLICT) { repo.applyEmbedding(character.id, 0, lore.id, latest, latest.reviewItems.toSet()) }
        assertEquals(1L, repo.load(character.id).documentRevision)
    }
    @Test fun corruptPngAndForeignCandidateNeverReachStorage() = runBlocking<Unit> {
        fails(ProjectStorageError.INVALID_INPUT) { repository().saveArtwork("broken.png", byteArrayOf(1, 2)) }
        fails(ProjectStorageError.INVALID_INPUT) { repository().savePngCard("card.png", png(preservedV1), "foreign") }
        assertTrue(index.records.isEmpty()); assertTrue(associations.assets.isEmpty())
    }
    companion object {
        private const val LORE = """{"entries":[],"extensions":{},"unknown":1.2300e+09}"""
        private const val V2 = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"Keeper","description":"","personality":"","scenario":"","first_mes":"","mes_example":"","creator_notes":"","system_prompt":"","post_history_instructions":"","alternate_greetings":[],"tags":[],"creator":"","character_version":"","extensions":{}}}"""
        private fun png(json: ByteArray? = null): ByteArray {
            fun chunk(type: String, data: ByteArray): ByteArray {
                val kind = type.encodeToByteArray()
                return java.nio.ByteBuffer.allocate(data.size + 12).putInt(data.size).put(kind).put(data)
                    .putInt(CRC32().apply { update(kind); update(data) }.value.toInt()).array()
            }
            val compressed = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(byteArrayOf(0, 1, 2, 3, 0xff.toByte())) } }.toByteArray()
            return byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10) + chunk("IHDR", byteArrayOf(0,0,0,1,0,0,0,1,8,6,0,0,0)) +
                (json?.let { chunk("tEXt", "chara\u0000".encodeToByteArray() + Base64.getEncoder().encode(it)) } ?: byteArrayOf()) +
                chunk("IDAT", compressed) + chunk("IEND", byteArrayOf())
        }
    }
}

private class AssociationMemoryIndex(private val projects: FaultIndex) : CompositionIndex {
    val assets = linkedMapOf<String, ArtworkRecord>()
    private val compositions = linkedMapOf<String, CompositionRecord>()
    private val loreLinks = linkedMapOf<Pair<String, String>, LoreLinkRecord>()
    var beforeInsert: () -> Unit = {}
    var afterInsert: () -> Unit = {}
    var failAll = false
    override fun observeArtwork() = MutableStateFlow(assets.values.toList())
    override suspend fun allArtwork(): List<ArtworkRecord> { if (failAll) error("incomplete read"); return assets.values.toList() }
    override suspend fun findArtwork(id: String) = assets[id]
    override suspend fun insertArtwork(record: ArtworkRecord) { beforeInsert(); check(assets.putIfAbsent(record.summary.id, record) == null); afterInsert() }
    override suspend fun deleteArtwork(id: String) = if (assets.remove(id) != null) 1 else 0
    override suspend fun artworkInUse(id: String) = compositions.values.any { it.artworkId == id || it.sourcePngId == id }
    override suspend fun lorebookInUse(id: String) = loreLinks.values.any { it.lorebookId == id }
    override suspend fun composition(id: String) = compositions[id]
    override suspend fun links(id: String) = loreLinks.values.filter { it.characterId == id }
    override suspend fun insertPng(project: ProjectRecord, artwork: ArtworkRecord, composition: CompositionRecord) {
        beforeInsert(); projects.insert(project); assets[artwork.summary.id] = artwork; compositions[composition.characterId] = composition; afterInsert()
    }
    private fun checkRevision(id: String, revision: Long): CompositionRecord {
        val value = compositions[id] ?: CompositionRecord(id)
        if (value.revision != revision) throw ProjectStorageException(ProjectStorageError.REVISION_CONFLICT, "Stale composition")
        return value
    }
    override suspend fun setArtwork(id: String, expectedRevision: Long, artworkId: String?) {
        val old = checkRevision(id, expectedRevision)
        if (old.artworkId != artworkId) compositions[id] = old.copy(revision = old.revision + 1, artworkId = artworkId)
    }
    override suspend fun linkLorebook(id: String, expectedRevision: Long, lorebookId: String, remove: Boolean) {
        val old = checkRevision(id, expectedRevision); val key = id to lorebookId
        if (remove && key !in loreLinks || !remove && key in loreLinks) return
        if (remove) loreLinks.remove(key) else loreLinks[key] = LoreLinkRecord(id, lorebookId)
        compositions[id] = old.copy(revision = old.revision + 1)
    }
    override suspend fun duplicate(project: ProjectRecord, originalId: String) {
        projects.insert(project)
        compositions[originalId]?.let { compositions[project.summary.id] = it.copy(characterId = project.summary.id) }
        links(originalId).forEach { loreLinks[project.summary.id to it.lorebookId] = it.copy(characterId = project.summary.id) }
    }
    override suspend fun commitEmbedding(project: ProjectRecord, expectedRevision: Long, expectedCompositionRevision: Long,
        lorebookId: String, expectedLoreRevision: Long, loreSha256: String) {
        val old = checkRevision(project.summary.id, expectedCompositionRevision)
        val lore = projects.find(lorebookId)
        if (lore?.documentRevision != expectedLoreRevision || lore.currentSha256 != loreSha256) throw ProjectStorageException(ProjectStorageError.REVISION_CONFLICT, "Stale lore")
        if (projects.updateContent(project.summary.id, expectedRevision, project.currentFileKey, project.currentSha256, project.currentSizeBytes,
                project.summary.isDraft, project.summary.updatedAtEpochMillis) != 1) throw ProjectStorageException(ProjectStorageError.REVISION_CONFLICT, "Stale character")
        loreLinks[project.summary.id to lorebookId] = LoreLinkRecord(project.summary.id, lorebookId, expectedLoreRevision, loreSha256)
        compositions[project.summary.id] = old.copy(revision = old.revision + 1)
    }
}

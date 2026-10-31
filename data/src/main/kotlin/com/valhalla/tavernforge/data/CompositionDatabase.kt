package com.valhalla.tavernforge.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.valhalla.tavernforge.core.model.ArtworkSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "artwork_assets", primaryKeys = ["id"], foreignKeys = [
    ForeignKey(entity = ArtworkEntity::class, parentColumns = ["id"], childColumns = ["sourceArtworkId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index("sourceArtworkId")])
data class ArtworkEntity(val id: String, val fileName: String, val sha256: String, val sizeBytes: Int,
    val width: Int, val height: Int, val isAnimated: Boolean, val createdAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "'image/png'") val mediaType: String, val sourceArtworkId: String?, val transformation: String?, val fileKey: String) {
    constructor(summary: ArtworkSummary, fileKey: String) : this(summary.id, summary.fileName, summary.sha256,
        summary.sizeBytes, summary.width, summary.height, summary.isAnimated, summary.createdAtEpochMillis,
        summary.mediaType, summary.sourceArtworkId, summary.transformation, fileKey)
    fun record() = ArtworkRecord(ArtworkSummary(id, fileName, sha256, sizeBytes, width, height, isAnimated,
        createdAtEpochMillis, mediaType, sourceArtworkId, transformation), fileKey)
}
@Entity(tableName = "project_compositions", primaryKeys = ["characterId"], foreignKeys = [
    ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["characterId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = ArtworkEntity::class, parentColumns = ["id"], childColumns = ["artworkId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ArtworkEntity::class, parentColumns = ["id"], childColumns = ["sourcePngId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index("artworkId"), Index("sourcePngId")])
data class CompositionEntity(val characterId: String, val revision: Long, val artworkId: String?, val sourcePngId: String?, val sourceCandidateId: String?) {
    fun record() = CompositionRecord(characterId, revision, artworkId, sourcePngId, sourceCandidateId)
}
@Entity(tableName = "lore_links", primaryKeys = ["characterId", "lorebookId"], foreignKeys = [
    ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["characterId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["lorebookId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index("lorebookId")])
data class LoreLinkEntity(val characterId: String, val lorebookId: String, val embeddedRevision: Long?, val embeddedSha256: String?) {
    fun record() = LoreLinkRecord(characterId, lorebookId, embeddedRevision, embeddedSha256)
}

@Dao
interface CompositionDao {
    @Query("SELECT * FROM artwork_assets ORDER BY createdAtEpochMillis DESC, id ASC") fun observeArtwork(): Flow<List<ArtworkEntity>>
    @Query("SELECT * FROM artwork_assets") suspend fun allArtwork(): List<ArtworkEntity>
    @Query("SELECT * FROM artwork_assets WHERE id = :id") suspend fun artwork(id: String): ArtworkEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertArtwork(asset: ArtworkEntity)
    @Query("DELETE FROM artwork_assets WHERE id = :id") suspend fun deleteArtwork(id: String): Int
    @Query("SELECT EXISTS(SELECT 1 FROM project_compositions WHERE artworkId = :id OR sourcePngId = :id) OR EXISTS(SELECT 1 FROM artwork_assets WHERE sourceArtworkId = :id)") suspend fun artworkInUse(id: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM lore_links WHERE lorebookId = :id)") suspend fun lorebookInUse(id: String): Boolean
    @Query("SELECT * FROM project_compositions WHERE characterId = :id") suspend fun composition(id: String): CompositionEntity?
    @Query("SELECT * FROM lore_links WHERE characterId = :id ORDER BY lorebookId") suspend fun links(id: String): List<LoreLinkEntity>
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun project(id: String): ProjectEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertProject(project: ProjectEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun ensureComposition(composition: CompositionEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertLink(link: LoreLinkEntity)
    @Query("DELETE FROM lore_links WHERE characterId = :id AND lorebookId = :loreId") suspend fun removeLink(id: String, loreId: String)
    @Query("UPDATE project_compositions SET artworkId = :artworkId, revision = revision + 1 WHERE characterId = :id AND revision = :expectedRevision")
    suspend fun updateArtwork(id: String, expectedRevision: Long, artworkId: String?): Int
    @Query("UPDATE project_compositions SET revision = revision + 1 WHERE characterId = :id AND revision = :expectedRevision")
    suspend fun bumpComposition(id: String, expectedRevision: Long): Int
    @Query("UPDATE lore_links SET embeddedRevision = :revision, embeddedSha256 = :sha WHERE characterId = :id AND lorebookId = :loreId")
    suspend fun stampLink(id: String, loreId: String, revision: Long, sha: String)
    @Query("""UPDATE projects SET currentFileKey = :fileKey, currentSha256 = :sha, currentSizeBytes = :size,
        isDraft = :draft, updatedAtEpochMillis = :updatedAt, documentRevision = documentRevision + 1
        WHERE id = :id AND documentRevision = :expectedRevision""")
    suspend fun updateDocument(id: String, expectedRevision: Long, fileKey: String, sha: String, size: Int, draft: Boolean, updatedAt: Long): Int

    @Transaction suspend fun insertPng(project: ProjectEntity, artwork: ArtworkEntity, composition: CompositionEntity) {
        insertArtwork(artwork); insertProject(project); ensureComposition(composition)
    }
    @Transaction suspend fun setArtwork(id: String, expectedRevision: Long, artworkId: String?) {
        val old = checkedComposition(id, expectedRevision)
        if (old.artworkId == artworkId) return
        if (updateArtwork(id, expectedRevision, artworkId) != 1) conflict()
    }
    @Transaction suspend fun changeLink(id: String, expectedRevision: Long, lorebookId: String, remove: Boolean) {
        checkedComposition(id, expectedRevision)
        val exists = links(id).any { it.lorebookId == lorebookId }
        if (remove && !exists || !remove && exists) return
        if (remove) removeLink(id, lorebookId) else insertLink(LoreLinkEntity(id, lorebookId, null, null))
        if (bumpComposition(id, expectedRevision) != 1) conflict()
    }
    @Query("INSERT INTO project_metadata (projectId, revision, tagsJson) SELECT :newId, 0, tagsJson FROM project_metadata WHERE projectId = :oldId")
    suspend fun copyMetadata(oldId: String, newId: String)
    @Transaction suspend fun duplicate(project: ProjectEntity, originalId: String) {
        insertProject(project)
        composition(originalId)?.let { ensureComposition(it.copy(characterId = project.summary.id)) }
        links(originalId).forEach { insertLink(it.copy(characterId = project.summary.id)) }
        copyMetadata(originalId, project.summary.id)
    }
    @Transaction suspend fun commitEmbedding(updated: ProjectEntity, expectedRevision: Long, expectedCompositionRevision: Long,
        lorebookId: String, expectedLoreRevision: Long, loreSha256: String) {
        val id = updated.summary.id
        checkedComposition(id, expectedCompositionRevision)
        val lore = project(lorebookId)
        if (lore?.documentRevision != expectedLoreRevision || lore.currentSha256 != loreSha256) conflict()
        if (updateDocument(id, expectedRevision, updated.currentFileKey, updated.currentSha256, updated.currentSizeBytes,
                updated.summary.isDraft, updated.summary.updatedAtEpochMillis) != 1) conflict()
        if (links(id).none { it.lorebookId == lorebookId }) insertLink(LoreLinkEntity(id, lorebookId, expectedLoreRevision, loreSha256))
        else stampLink(id, lorebookId, expectedLoreRevision, loreSha256)
        if (bumpComposition(id, expectedCompositionRevision) != 1) conflict()
    }
    suspend fun checkedComposition(id: String, expectedRevision: Long): CompositionEntity {
        ensureComposition(CompositionEntity(id, 0, null, null, null))
        val old = composition(id) ?: conflict()
        if (old.revision != expectedRevision || old.revision == Long.MAX_VALUE) conflict()
        return old
    }
}
private fun conflict(): Nothing = throw ProjectStorageException(ProjectStorageError.REVISION_CONFLICT, "Character, lorebook or associations changed. Reopen and review the latest revisions.")

class RoomCompositionIndex(private val dao: CompositionDao) : CompositionIndex {
    override fun observeArtwork(): Flow<List<ArtworkRecord>> = dao.observeArtwork().map { it.map(ArtworkEntity::record) }
    override suspend fun allArtwork() = dao.allArtwork().map(ArtworkEntity::record)
    override suspend fun findArtwork(id: String) = dao.artwork(id)?.record()
    override suspend fun insertArtwork(record: ArtworkRecord) = dao.insertArtwork(record.entity())
    override suspend fun deleteArtwork(id: String) = dao.deleteArtwork(id)
    override suspend fun artworkInUse(id: String) = dao.artworkInUse(id)
    override suspend fun lorebookInUse(id: String) = dao.lorebookInUse(id)
    override suspend fun composition(id: String) = dao.composition(id)?.record()
    override suspend fun links(id: String) = dao.links(id).map(LoreLinkEntity::record)
    override suspend fun insertPng(project: ProjectRecord, artwork: ArtworkRecord, composition: CompositionRecord) =
        dao.insertPng(project.entity(), artwork.entity(), composition.entity())
    override suspend fun setArtwork(id: String, expectedRevision: Long, artworkId: String?) = dao.setArtwork(id, expectedRevision, artworkId)
    override suspend fun linkLorebook(id: String, expectedRevision: Long, lorebookId: String, remove: Boolean) = dao.changeLink(id, expectedRevision, lorebookId, remove)
    override suspend fun duplicate(project: ProjectRecord, originalId: String) = dao.duplicate(project.entity(), originalId)
    override suspend fun commitEmbedding(project: ProjectRecord, expectedRevision: Long, expectedCompositionRevision: Long,
        lorebookId: String, expectedLoreRevision: Long, loreSha256: String) = dao.commitEmbedding(project.entity(), expectedRevision,
            expectedCompositionRevision, lorebookId, expectedLoreRevision, loreSha256)
}
private fun ProjectRecord.entity() = ProjectEntity(summary, sourceFileKey, mediaType, documentRevision, currentFileKey, currentSha256, currentSizeBytes)
private fun ArtworkRecord.entity() = ArtworkEntity(summary, fileKey)
private fun CompositionRecord.entity() = CompositionEntity(characterId, revision, artworkId, sourcePngId, sourceCandidateId)

/** Adds references only; existing original/current JSON rows and private bytes are untouched. */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""CREATE TABLE IF NOT EXISTS `artwork_assets` (`fileKey` TEXT NOT NULL,
            `id` TEXT NOT NULL, `fileName` TEXT NOT NULL, `sha256` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL,
            `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `isAnimated` INTEGER NOT NULL,
            `createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
        connection.execSQL("""CREATE TABLE IF NOT EXISTS `project_compositions` (`characterId` TEXT NOT NULL,
            `revision` INTEGER NOT NULL, `artworkId` TEXT, `sourcePngId` TEXT, `sourceCandidateId` TEXT, PRIMARY KEY(`characterId`),
            FOREIGN KEY(`characterId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`artworkId`) REFERENCES `artwork_assets`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`sourcePngId`) REFERENCES `artwork_assets`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_project_compositions_artworkId` ON `project_compositions` (`artworkId`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_project_compositions_sourcePngId` ON `project_compositions` (`sourcePngId`)")
        connection.execSQL("""CREATE TABLE IF NOT EXISTS `lore_links` (`characterId` TEXT NOT NULL, `lorebookId` TEXT NOT NULL,
            `embeddedRevision` INTEGER, `embeddedSha256` TEXT, PRIMARY KEY(`characterId`, `lorebookId`),
            FOREIGN KEY(`characterId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`lorebookId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_lore_links_lorebookId` ON `lore_links` (`lorebookId`)")
    }
}

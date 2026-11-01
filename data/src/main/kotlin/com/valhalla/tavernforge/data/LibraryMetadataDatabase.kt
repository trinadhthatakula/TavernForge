package com.valhalla.tavernforge.data

import androidx.room3.*
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.valhalla.tavernforge.core.model.ProjectMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "project_metadata", primaryKeys = ["projectId"], foreignKeys = [
    ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE),
])
data class ProjectMetadataEntity(val projectId: String, val revision: Long, val tagsJson: String) {
    fun model(): ProjectMetadata {
        if (revision < 0) throw ProjectStorageException(ProjectStorageError.CORRUPT_SOURCE, "Saved metadata revision is invalid.")
        return ProjectMetadata(projectId, revision, decodeTags(tagsJson))
    }
}
@Dao
interface LibraryMetadataDao {
    @Query("SELECT * FROM project_metadata") fun observe(): Flow<List<ProjectMetadataEntity>>
    @Query("SELECT * FROM project_metadata WHERE projectId = :id") suspend fun find(id: String): ProjectMetadataEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun ensure(record: ProjectMetadataEntity)
    @Query("UPDATE project_metadata SET tagsJson = :tagsJson, revision = revision + 1 WHERE projectId = :id AND revision = :expectedRevision")
    suspend fun updateTags(id: String, expectedRevision: Long, tagsJson: String): Int
    @Transaction suspend fun setTags(id: String, expectedRevision: Long, tagsJson: String) {
        ensure(ProjectMetadataEntity(id, 0, "[]"))
        if (expectedRevision == Long.MAX_VALUE || updateTags(id, expectedRevision, tagsJson) != 1)
            throw ProjectStorageException(ProjectStorageError.REVISION_CONFLICT, "Local tags changed. Reopen their latest revision before saving.")
    }
}
class RoomLibraryMetadataIndex(private val dao: LibraryMetadataDao) : LibraryMetadataIndex {
    override fun observe(): Flow<List<ProjectMetadata>> = dao.observe().map { it.map(ProjectMetadataEntity::model) }
    override suspend fun find(id: String) = dao.find(id)?.model()
    override suspend fun setTags(id: String, expectedRevision: Long, tags: List<String>) = dao.setTags(id, expectedRevision, encodeTags(tags))
}
/** Additive migration: legacy PNG assets retain their original MIME, bytes and references. */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `artwork_assets` ADD COLUMN `mediaType` TEXT NOT NULL DEFAULT 'image/png'")
        connection.execSQL("ALTER TABLE `artwork_assets` ADD COLUMN `sourceArtworkId` TEXT REFERENCES `artwork_assets`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT")
        connection.execSQL("ALTER TABLE `artwork_assets` ADD COLUMN `transformation` TEXT")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_artwork_assets_sourceArtworkId` ON `artwork_assets` (`sourceArtworkId`)")
        connection.execSQL("""CREATE TABLE IF NOT EXISTS `project_metadata` (`projectId` TEXT NOT NULL,
            `revision` INTEGER NOT NULL, `tagsJson` TEXT NOT NULL, PRIMARY KEY(`projectId`),
            FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
    }
}

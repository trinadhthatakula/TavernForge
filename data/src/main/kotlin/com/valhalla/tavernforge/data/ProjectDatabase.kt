package com.valhalla.tavernforge.data

import android.content.Context
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import com.valhalla.tavernforge.core.model.ProjectSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

@Entity(tableName = "projects", primaryKeys = ["id"])
data class ProjectEntity(
    @Embedded val summary: ProjectSummary,
    val sourceFileKey: String,
    val mediaType: String,
    val documentRevision: Long,
) {
    fun record() = ProjectRecord(summary, sourceFileKey, mediaType, documentRevision)
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAtEpochMillis DESC, id ASC")
    fun observe(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY updatedAtEpochMillis DESC, id ASC")
    suspend fun all(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun find(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(project: ProjectEntity)

    @Query("UPDATE projects SET title = :title, updatedAtEpochMillis = :updatedAtEpochMillis WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAtEpochMillis: Long): Int

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Database(entities = [ProjectEntity::class], version = 1, exportSchema = true)
abstract class ProjectDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
}

class RoomProjectIndex(private val dao: ProjectDao) : ProjectIndex {
    override fun observe(): Flow<List<ProjectRecord>> = dao.observe().map { rows -> rows.map(ProjectEntity::record) }
    override suspend fun all(): List<ProjectRecord> = dao.all().map(ProjectEntity::record)
    override suspend fun find(id: String): ProjectRecord? = dao.find(id)?.record()
    override suspend fun insert(record: ProjectRecord) = dao.insert(ProjectEntity(
        record.summary, record.sourceFileKey, record.mediaType, record.documentRevision,
    ))
    override suspend fun rename(id: String, title: String, updatedAtEpochMillis: Long): Int = dao.rename(id, title, updatedAtEpochMillis)
    override suspend fun delete(id: String): Int = dao.delete(id)
}

/** Separate builder for real Room close/reopen tests. No destructive migration fallback. */
fun createProjectDatabase(context: Context, name: String = "projects.db"): ProjectDatabase {
    require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,100}"))) { "Invalid database name" }
    // Room 3.0.3's context builder defaults to AndroidSQLiteDriver; no bundled driver is needed.
    return Room.databaseBuilder<ProjectDatabase>(context.applicationContext, name)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}

@Volatile private var applicationRepository: ProjectRepository? = null

/** One repository owns all file/index mutations for the application process. */
fun createProjectRepository(context: Context): ProjectRepository = applicationRepository ?: synchronized(ProjectDatabase::class.java) {
    applicationRepository ?: run {
        val app = context.applicationContext
        val database = createProjectDatabase(app)
        RoomProjectRepository(RoomProjectIndex(database.projects()), ManagedJsonFiles(File(app.filesDir, "project-sources")))
            .also { applicationRepository = it }
    }
}

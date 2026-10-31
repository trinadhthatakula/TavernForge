package com.valhalla.tavernforge.compatibility

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

// Compile-only surface. There is no Activity, navigation, product screen, or app.
@Composable
internal fun ComposeCompileProbe() {
    MaterialTheme {
        Text("Compatibility probe")
        AsyncImage(model = null, contentDescription = null)
    }
}

@Entity(tableName = "probe_records")
data class ProbeEntity(@PrimaryKey val id: Long, val payload: String)

@Dao
interface ProbeDao {
    @Insert
    suspend fun insert(record: ProbeEntity)

    @Query("SELECT * FROM probe_records ORDER BY id")
    fun observe(): Flow<List<ProbeEntity>>
}

@Database(entities = [ProbeEntity::class], version = 1, exportSchema = true)
abstract class ProbeDatabase : RoomDatabase() {
    abstract fun records(): ProbeDao
}

class ProbeService
class ProbeConsumer(val service: ProbeService)

// These calls require the Koin Kotlin compiler plugin to transform them.
// A JVM test resolves ProbeConsumer to catch an untransformed placeholder.
fun probeModule() = module {
    single<ProbeService>()
    single<ProbeConsumer>()
}

@Serializable
data class ProbePayload(val value: String)

// Verifies that the selected Android variants expose compatible API types.
data class DependencyTypes(
    val preferences: DataStore<Preferences>,
    val client: HttpClient,
    val image: ImageRequest,
)

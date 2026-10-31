package com.valhalla.tavernforge

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.valhalla.tavernforge.core.model.ProjectSummary
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ProjectSort { RECENT, TITLE }

interface LibraryPreferences {
    val sort: Flow<ProjectSort>
    suspend fun setSort(sort: ProjectSort)
}

private val Context.libraryPreferences by preferencesDataStore(name = "library_preferences")

class DataStoreLibraryPreferences(context: Context) : LibraryPreferences {
    private val store = context.applicationContext.libraryPreferences
    private val sortKey = stringPreferencesKey("sort")
    override val sort = store.data.map { preferences ->
        ProjectSort.entries.firstOrNull { it.name == preferences[sortKey] } ?: ProjectSort.RECENT
    }
    override suspend fun setSort(sort: ProjectSort) { store.edit { it[sortKey] = sort.name } }
}

internal fun visibleProjects(projects: List<ProjectSummary>, query: String, sort: ProjectSort): List<ProjectSummary> {
    val needle = query.trim()
    val filtered = if (needle.isEmpty()) projects else projects.filter {
        it.title.contains(needle, ignoreCase = true) || it.sourceFileName.contains(needle, ignoreCase = true)
    }
    return when (sort) {
        ProjectSort.RECENT -> filtered.sortedWith(compareByDescending<ProjectSummary> { it.updatedAtEpochMillis }.thenBy { it.id })
        ProjectSort.TITLE -> filtered.sortedWith(compareBy<ProjectSummary> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id })
    }
}

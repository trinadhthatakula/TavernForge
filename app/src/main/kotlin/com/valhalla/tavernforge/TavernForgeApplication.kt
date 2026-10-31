package com.valhalla.tavernforge

import android.app.Application
import android.content.Context
import com.valhalla.tavernforge.data.ProjectRepository
import com.valhalla.tavernforge.data.createProjectRepository
import com.valhalla.tavernforge.format.CharacterCardEngine
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class FormatServices {
    val engine = CharacterCardEngine()
}

fun applicationModule() = module { single<FormatServices>() }

fun libraryModule(context: Context) = module {
    single<ProjectRepository> { createProjectRepository(context.applicationContext) }
    single<LibraryPreferences> { DataStoreLibraryPreferences(context.applicationContext) }
}

class TavernForgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin { modules(applicationModule(), libraryModule(this@TavernForgeApplication)) }
    }
}

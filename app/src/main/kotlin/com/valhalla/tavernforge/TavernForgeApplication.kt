package com.valhalla.tavernforge

import android.app.Application
import com.valhalla.tavernforge.format.CharacterCardEngine
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class FormatServices {
    val engine = CharacterCardEngine()
}

fun applicationModule() = module { single<FormatServices>() }

class TavernForgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin { modules(applicationModule()) }
    }
}

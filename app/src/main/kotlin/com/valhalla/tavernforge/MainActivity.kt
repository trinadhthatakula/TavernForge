package com.valhalla.tavernforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.koin.core.context.GlobalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val factory = viewModelFactory {
            initializer {
                InspectionViewModel(
                    GlobalContext.get().get<FormatServices>().engine,
                    DocumentReader(applicationContext.contentResolver),
                    GlobalContext.get().get(),
                    GlobalContext.get().get(),
                    writer = ContentResolverDocumentWriter(applicationContext.contentResolver),
                )
            }
        }
        setContent {
            TavernForgeTheme {
                TavernForgeApp(viewModel(factory = factory))
            }
        }
    }
}

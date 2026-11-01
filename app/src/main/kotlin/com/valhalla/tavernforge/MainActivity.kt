package com.valhalla.tavernforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.koin.core.context.GlobalContext
import com.valhalla.tavernforge.data.network.gemini.GeminiService
import com.valhalla.tavernforge.data.security.AndroidGeminiCredentials
import com.valhalla.tavernforge.data.drafts.FileEditJournal
import com.valhalla.tavernforge.data.archive.LibraryBackupService
import java.io.File

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
                    journal = FileEditJournal(File(applicationContext.noBackupFilesDir, "edit-recovery")),
                )
            }
        }
        val compositionFactory = viewModelFactory {
            initializer {
                CompositionViewModel(GlobalContext.get().get<FormatServices>().engine, GlobalContext.get().get(),
                    DocumentReader(applicationContext.contentResolver), ContentResolverDocumentWriter(applicationContext.contentResolver))
            }
        }
        val generationFactory = viewModelFactory {
            initializer {
                val credentials = AndroidGeminiCredentials(applicationContext)
                GenerationViewModel(GlobalContext.get().get<FormatServices>().engine, GlobalContext.get().get(), credentials, GeminiService(credentials))
            }
        }
        val backupFactory = viewModelFactory {
            initializer {
                BackupViewModel(LibraryBackupService(GlobalContext.get().get<com.valhalla.tavernforge.data.ProjectRepository>(),
                    File(applicationContext.noBackupFilesDir, "library-archive-staging")), applicationContext.contentResolver)
            }
        }
        setContent {
            TavernForgeTheme {
                TavernForgeApp(viewModel(factory = factory), viewModel(factory = compositionFactory), viewModel(factory = generationFactory), viewModel(factory = backupFactory))
            }
        }
    }
}

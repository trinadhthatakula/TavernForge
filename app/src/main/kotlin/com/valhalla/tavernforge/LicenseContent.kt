package com.valhalla.tavernforge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LicenseContent(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val uriHandler = LocalUriHandler.current
    var sourceUnavailable by remember { mutableStateOf(false) }
    val paragraphs by produceState<List<String>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            context.assets.open("open_source_notices.txt").bufferedReader().use { it.readText() }
                .split(Regex("\\n\\s*\\n")).filter(String::isNotBlank)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            TextButton(onClick = onClose) { Text("Back to settings") }
            Text("About and licenses", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text("TavernForge is free software. Its source and build instructions are available publicly.")
            TextButton(onClick = {
                try { uriHandler.openUri("https://github.com/trinadhthatakula/TavernForge") }
                catch (_: RuntimeException) { sourceUnavailable = true }
            }) { Text("Open source repository") }
            if (sourceUnavailable) SelectionContainer { Text("Open this address in a browser: https://github.com/trinadhthatakula/TavernForge") }
        }
        if (paragraphs == null) item { Text("Loading bundled notices…") }
        items(paragraphs.orEmpty()) { paragraph ->
            SelectionContainer { Text(paragraph, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

package com.valhalla.tavernforge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.valhalla.tavernforge.core.model.SourceFormat

@Composable
internal fun ManualCreationScreen(state: InspectionState, onCreate: (SourceFormat, String) -> Unit, onClose: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    val format = SourceFormat.entries.firstOrNull { it.name == selected }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            TextButton(onClick = onClose, enabled = !state.busy) { Text("Back to library") }
            Text("Create manually", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text("Choose the exact JSON format. Your draft works offline and uses the same validation and export as imported projects.")
            LibraryFeedback(state)
        }
        items(SourceFormat.entries.filter { it != SourceFormat.UNKNOWN }) { option ->
            FilterChip(selected = format == option, onClick = { selected = option.name }, enabled = !state.busy,
                label = { Text(formatLabel(option)) })
        }
        item {
            OutlinedTextField(value = title, onValueChange = { if (it.length <= 100) title = it },
                label = { Text("Local project name") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            Text("This library name is separate from the character or lorebook name inside JSON.", style = MaterialTheme.typography.bodySmall)
        }
        if (format != null) item {
            Card {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Creation preview", style = MaterialTheme.typography.titleLarge)
                    Text(formatLabel(format))
                    Text(when (format) {
                        SourceFormat.V1 -> "Six required empty text fields. No embedded lorebook or artwork."
                        SourceFormat.V2 -> "V2 envelope with required empty text, empty alternate greetings/tags and extensions. Add an embedded V2 book explicitly."
                        SourceFormat.V3 -> "V3 envelope with required empty text and arrays, including group-only greetings. No invented assets or timestamps."
                        SourceFormat.LOREBOOK_V2 -> "A V2 book with an empty entry array and extensions."
                        SourceFormat.LOREBOOK_V3 -> "A lorebook_v3 envelope with an empty entry array and extensions."
                        SourceFormat.WORLD_INFO -> "Native World Info with an empty keyed entries object."
                        SourceFormat.UNKNOWN -> ""
                    })
                    Text("Create saves the initial template on this device, then opens the editor. The chosen format remains fixed.")
                    Button(onClick = { onCreate(format, title) }, enabled = !state.busy && title.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Create draft") }
                }
            }
        }
    }
}

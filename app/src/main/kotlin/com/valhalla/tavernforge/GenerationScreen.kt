package com.valhalla.tavernforge

import android.graphics.BitmapFactory
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.core.model.generation.GenerationPurpose
import com.valhalla.tavernforge.core.model.generation.GenerationUsage
import com.valhalla.tavernforge.data.network.gemini.GeneratedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun GenerationScreen(state: GenerationState, viewModel: GenerationViewModel, onClose: () -> Unit, onOpenProject: (String) -> Unit, onOpenArtwork: () -> Unit) {
    // Secret input is ephemeral Compose memory: never saveable state, ViewModel state, or a journal.
    var secretInput by remember { mutableStateOf("") }
    var deleteKey by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val view = LocalView.current
    val uriHandler = LocalUriHandler.current
    DisposableEffect(view) {
        val window = view.context.generationActivity()?.window
        val alreadySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(state.credential.generation) { secretInput = "" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            TextButton(onClick = { secretInput = ""; onClose() }, enabled = !state.working && !state.keyWorking) { Text("Close Gemini") }
            Text("Optional Gemini", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text("Manual creation and imports work offline. Gemini sends only the reviewed request directly to Google with your own key.")
            if (state.working || state.keyWorking) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            state.notice?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (state.working) TextButton(onClick = viewModel::cancelRequest) { Text("Cancel provider request") }
        }
        item {
            Card {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Key and permissions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text("Key status: ${state.credential.availability.name.lowercase()}")
                    EphemeralKeyInput(secretInput, { secretInput = it }, !state.keyWorking)
                    Button(onClick = { val key = secretInput.toCharArray(); secretInput = ""; viewModel.updateKey(key) },
                        enabled = !state.keyWorking && secretInput.isNotBlank()) { Text("Store encrypted key") }
                    TextButton(onClick = { secretInput = ""; deleteKey = true }, enabled = !state.keyWorking) { Text("Delete stored key") }
                    Text("Saving/checking key storage makes no provider call. Updating or deleting it revokes pending credential access.", style = MaterialTheme.typography.bodySmall)
                    PermissionToggle("Allow text generation", state.grants.text) { viewModel.setGrants(state.grants.copy(text = it)) }
                    PermissionToggle("Allow image generation", state.grants.image) { viewModel.setGrants(state.grants.copy(image = it)) }
                    PermissionToggle("Allow automatic follow-ups", state.grants.automaticFollowups) { viewModel.setGrants(state.grants.copy(automaticFollowups = it)) }
                    Text("These independent permissions start off in each app process. This client still requires a separate request preview and approval for every call; it does not retry automatically.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { uriHandler.openUri("https://aistudio.google.com/apikey") }) { Text("Google AI Studio keys and quota") }
                    TextButton(onClick = { uriHandler.openUri("https://ai.google.dev/gemini-api/docs/pricing") }) { Text("Official Gemini pricing") }
                    TextButton(onClick = { uriHandler.openUri("https://ai.google.dev/gemini-api/docs/rate-limits") }) { Text("Official rate limits and quotas") }
                }
            }
        }
        if (!state.hasDraft) {
            item {
                Text("Models", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                OutlinedButton(onClick = { viewModel.prepareModels() }, enabled = state.editingAllowed && state.credential.available) { Text("Review model-list request") }
                Text("Model listing is an authenticated metadata call. The response and reviewed capability evidence determine which models can be used.", style = MaterialTheme.typography.bodySmall)
            }
            items(state.models, key = { it.name }) { model ->
                Column {
                    FilterChip(selected = state.modelName == model.name, onClick = { viewModel.setModel(model.name) }, enabled = state.editingAllowed,
                        label = { Text(model.displayName) })
                    Text("${model.name} · text ${model.capabilities.textOutput} · image ${model.capabilities.imageOutput}", style = MaterialTheme.typography.bodySmall)
                    model.capabilities.evidenceUrl?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (state.nextModelsPage != null) item { TextButton(onClick = { viewModel.prepareModels(true) }, enabled = state.editingAllowed) { Text("Review next models page request") } }
            item {
                Text(state.project?.let { "Target: ${it.title} · revision ${state.projectRevision}" } ?: "Target: new local draft", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GenerationMode.entries.filter { mode ->
                        state.project != null || mode in setOf(GenerationMode.NEW_JSON, GenerationMode.IMAGE)
                    }.filter { mode -> mode != GenerationMode.FIELD || state.project?.sourceFormat in GenerationViewModel.CHARACTER_FORMATS }.forEach { mode ->
                        FilterChip(selected = state.mode == mode, onClick = { viewModel.setMode(mode) }, enabled = state.editingAllowed,
                            label = { Text(when (mode) { GenerationMode.NEW_JSON -> "New JSON"; GenerationMode.FIELD -> "Rewrite field"; GenerationMode.LORE_ENTRY -> "New lore entry"; GenerationMode.IMAGE -> "Artwork" }) })
                    }
                }
                if (state.mode == GenerationMode.NEW_JSON) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceFormat.entries.filter { it != SourceFormat.UNKNOWN }.forEach { format ->
                        FilterChip(selected = state.format == format, onClick = { viewModel.setFormat(format) }, enabled = state.editingAllowed, label = { Text(formatLabel(format)) })
                    }
                }
                if (state.mode == GenerationMode.FIELD) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GenerationViewModel.GENERATION_FIELDS.filter { field ->
                        (state.project?.sourceFormat != SourceFormat.V1 || field !in setOf("creator_notes", "system_prompt", "post_history_instructions", "alternate_greetings", "group_only_greetings")) &&
                            (field != "group_only_greetings" || state.project?.sourceFormat == SourceFormat.V3)
                    }.forEach { field ->
                        FilterChip(selected = state.field == field, onClick = { viewModel.setField(field) }, enabled = state.editingAllowed, label = { Text(field) })
                    }
                }
                if (state.project != null) {
                    PermissionToggle("Include the entire current project JSON as context", state.includeContext, state.editingAllowed, viewModel::setContext)
                    Text("Off sends only your concept and the format/field instructions. On includes all current JSON, including unknown fields; inspect the exact request preview.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value = state.concept, onValueChange = viewModel::setConcept, label = { Text("Concept or rewrite instructions") },
                    minLines = 4, maxLines = 12, modifier = Modifier.fillMaxWidth(), enabled = state.editingAllowed)
                Text("Maximum output tokens", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1024, 2048, 4096, 8192).forEach { tokens -> FilterChip(selected = state.maxTokens == tokens,
                        onClick = { viewModel.setMaxTokens(tokens) }, enabled = state.editingAllowed, label = { Text(tokens.toString()) }) }
                }
                Button(onClick = viewModel::prepareGeneration, enabled = state.editingAllowed && state.concept.isNotBlank() && state.modelName != null) { Text("Preview exact request") }
            }
        }
        if (state.hasDraft) {
            item {
                Text("Unaccepted generated draft", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text("Provider output is untrusted. Review content, transformations and validation before accepting. It remains in memory until then.")
                state.returnedModel?.let { Text("Returned model version: $it", style = MaterialTheme.typography.bodySmall) }
                state.textDraft?.let { PagedTextPreview("Generated text", it) }
                state.fieldDiff?.let { diff ->
                    Text("Field change: ${diff.path}")
                    PagedTextPreview("Previous value", diff.before)
                    PagedTextPreview("Proposed value", diff.after)
                }
                state.imageDraft?.let { GeneratedImagePreview(it) }
                state.draftReview.forEach { Text(it) }
            }
            items(state.draftIssues.take(100)) { DiagnosticCard(it) }
            if (state.draftIssues.size > 100) item { Text("Showing the first 100 validation diagnostics.") }
            item {
                Button(onClick = viewModel::acceptDraft, enabled = state.draftAcceptable && !state.working) { Text("Accept reviewed draft and save") }
                TextButton(onClick = { discard = true }, enabled = !state.working) { Text("Discard generated draft") }
            }
        }
        state.acceptedProjectId?.let { id -> item { OutlinedButton(onClick = { onOpenProject(id) }, enabled = !state.working) { Text("Open accepted project") } } }
        state.acceptedArtwork?.let { artwork -> item {
            Text("Saved artwork: ${artwork.fileName}")
            if (state.project?.sourceFormat in GenerationViewModel.CHARACTER_FORMATS) OutlinedButton(onClick = viewModel::attachAcceptedArtwork, enabled = !state.working) { Text("Attach accepted artwork to this character") }
            TextButton(onClick = onOpenArtwork, enabled = !state.working) { Text("Open artwork library") }
        } }
        item { UsageSummary(state.usage) }
    }
    state.preview?.let { request ->
        AlertDialog(onDismissRequest = viewModel::cancelPreview, title = { Text("Approve one Gemini request?") },
            text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("Provider: ${request.provider}\nEndpoint: ${request.origin}\nPurpose: ${request.purpose}\nModel: ${request.model ?: "Model metadata"}\nTarget: ${request.target.label}\nCalls: ${request.callCount}\nMaximum output tokens: ${request.limits.maxOutputTokens}\nResponse byte limit: ${request.limits.maxResponseBytes}")
                    PagedTextPreview("Exact request body", request.bodyPreview.ifEmpty { "No body; authenticated model metadata request." })
                    Text("Your key is sent only as authentication, separate from this body. Cost is not supplied; actual usage is shown only if returned by the provider.")
                    if (request.purpose in setOf(GenerationPurpose.TEXT, GenerationPurpose.IMAGE)) TextButton(onClick = viewModel::prepareCount, enabled = !state.working) { Text("Review a separate token-count request") }
                }
            } }, confirmButton = { TextButton(onClick = viewModel::approveRequest, enabled = !state.working && !state.keyWorking) { Text("Approve and send once") } },
            dismissButton = { TextButton(onClick = viewModel::cancelPreview, enabled = !state.working) { Text("Cancel") } })
    }
    if (deleteKey) AlertDialog(onDismissRequest = { deleteKey = false }, title = { Text("Delete stored Gemini key?") },
        text = { Text("This removes the encrypted local key and revokes in-flight access. Saved projects remain available offline.") },
        confirmButton = { TextButton(onClick = { deleteKey = false; viewModel.deleteKey() }) { Text("Delete key") } },
        dismissButton = { TextButton(onClick = { deleteKey = false }) { Text("Keep key") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard generated draft?") },
        text = { Text("This unaccepted generated content has not been saved.") },
        confirmButton = { TextButton(onClick = { discard = false; viewModel.discardDraft() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep draft") } })
}

/** Platform flags disable autofill, saved-view state and personalized IME learning for secret input. */
@Composable
private fun EphemeralKeyInput(value: String, onValueChange: (String) -> Unit, enabled: Boolean) {
    AndroidView(modifier = Modifier.fillMaxWidth(), factory = { context ->
        EditText(context).apply {
            hint = "New Gemini API key"
            // setSingleLine changes the transformation method; set password behavior afterwards.
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isPassword = true
                    info.text = "•".repeat(text?.length?.coerceAtMost(64) ?: 0)
                }
                override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
                    super.onInitializeAccessibilityEvent(host, event)
                    event.isPassword = true
                    event.text.clear()
                    event.text.add("•".repeat(text?.length?.coerceAtMost(64) ?: 0))
                    event.beforeText = null
                }
                override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
                    super.onPopulateAccessibilityEvent(host, event)
                    event.isPassword = true
                    event.text.clear()
                    event.text.add("•".repeat(text?.length?.coerceAtMost(64) ?: 0))
                    event.beforeText = null
                }
            }
            filters = arrayOf(InputFilter.LengthFilter(4096))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onValueChange(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
    }, onReset = null, update = { field ->
        field.isEnabled = enabled
        if (field.text.toString() != value) field.setText(value)
    }, onRelease = { it.setText("") })
}

private fun Context.generationActivity(): Activity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return context as? Activity
}

@Composable
private fun PermissionToggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        // The labelled row owns interaction and semantics, so keyboard and screen readers
        // encounter one switch rather than an unlabelled switch beside separate text.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun PagedTextPreview(label: String, text: String) {
    var page by remember(label, text) { mutableIntStateOf(0) }
    val size = 8000
    val pages = ((text.length + size - 1) / size).coerceAtLeast(1)
    Text("$label · page ${page + 1}/$pages · ${text.length} characters", style = MaterialTheme.typography.labelLarge)
    Text(text.substring((page * size).coerceAtMost(text.length), ((page + 1) * size).coerceAtMost(text.length)), style = MaterialTheme.typography.bodySmall)
    if (pages > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { page-- }, enabled = page > 0) { Text("Previous text") }
        TextButton(onClick = { page++ }, enabled = page + 1 < pages) { Text("Next text") }
    }
}

@Composable
private fun GeneratedImagePreview(image: GeneratedImage) {
    val bitmap by produceState<ImageBitmap?>(null, image) {
        value = withContext(Dispatchers.Default) {
            var sample = 1
            while (maxOf(image.width, image.height) / sample > 1024) sample *= 2
            val bytes = image.bytes()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    Text("${image.mimeType} · ${image.width} × ${image.height} · ${image.sizeBytes} bytes")
    bitmap?.let { Image(it, "Unaccepted generated artwork", Modifier.fillMaxWidth().heightIn(max = 360.dp)) }
}

@Composable
private fun UsageSummary(usage: GenerationUsage?) {
    Text("Provider usage", style = MaterialTheme.typography.titleMedium)
    if (usage == null) Text("Usage not supplied. No token or cost estimate is being presented.")
    else {
        Text("Input ${usage.promptTokens ?: "not supplied"} · output ${usage.outputTokens ?: "not supplied"} · total ${usage.totalTokens ?: "not supplied"}")
        Text("Thinking ${usage.thinkingTokens ?: "not supplied"} · cached ${usage.cachedTokens ?: "not supplied"}", style = MaterialTheme.typography.bodySmall)
        if (usage.inputCountOnly) Text("Input count only; this does not generate content or predict output usage.")
        Text("Counts are provider-reported. Cost is not supplied.", style = MaterialTheme.typography.bodySmall)
    }
}

package com.valhalla.tavernforge

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.tavernforge.core.model.Diagnostic
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.ParseResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InspectionSummary(
    val fileName: String,
    val characterName: String?,
    val sourceFormat: SourceFormat,
    val sizeBytes: Int,
    val sha256: String,
    val issues: List<Diagnostic>,
    val isValidV2: Boolean,
)

data class InspectionState(
    val loading: Boolean = false,
    val summary: InspectionSummary? = null,
    val failure: ImportFailure? = null,
)

enum class ImportFailure { TOO_LARGE, PERMISSION, READ }

class InspectionViewModel(
    private val engine: CharacterCardEngine,
    private val reader: DocumentReader,
) : ViewModel() {
    private val mutableState = MutableStateFlow(InspectionState())
    val state = mutableState.asStateFlow()
    private var inspection: Job? = null

    fun inspect(uri: Uri) {
        inspection?.cancel()
        mutableState.value = InspectionState(loading = true)
        inspection = viewModelScope.launch {
            try {
                val imported = reader.read(uri, engine.limits.maxBytes)
                val summary = withContext(Dispatchers.Default) {
                    when (val result = engine.parse(imported.bytes)) {
                        is ParseResult.Success -> {
                            val report = engine.validateV2(result.document)
                            val fullName = result.document.fields.name
                            val previewName = fullName?.let { if (it.length > 240) it.take(239) + "…" else it }
                            InspectionSummary(imported.displayName, previewName,
                                result.document.sourceFormat, result.document.source.sizeBytes,
                                result.document.source.sha256, report.issues, report.isValid)
                        }
                        is ParseResult.Failure -> InspectionSummary(imported.displayName, null,
                            SourceFormat.UNKNOWN, imported.bytes.size, result.source?.sha256.orEmpty(),
                            result.issues, false)
                    }
                }
                mutableState.value = InspectionState(summary = summary)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: DocumentTooLargeException) {
                mutableState.value = InspectionState(failure = ImportFailure.TOO_LARGE)
            } catch (_: SecurityException) {
                mutableState.value = InspectionState(failure = ImportFailure.PERMISSION)
            } catch (_: IOException) {
                mutableState.value = InspectionState(failure = ImportFailure.READ)
            } catch (_: IllegalArgumentException) {
                mutableState.value = InspectionState(failure = ImportFailure.READ)
            }
        }
    }

    fun clear() {
        inspection?.cancel()
        mutableState.value = InspectionState()
    }
}

package com.valhalla.tavernforge

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.tavernforge.data.archive.ArchiveError
import com.valhalla.tavernforge.data.archive.ArchiveException
import com.valhalla.tavernforge.data.archive.ArchiveExportSummary
import com.valhalla.tavernforge.data.archive.LibraryBackupService
import com.valhalla.tavernforge.data.archive.VerifiedLibraryArchive
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BackupPicker { EXPORT, RESTORE }
data class BackupRestoreReview(val projects: Int, val artwork: Int, val associations: Int, val loreLinks: Int, val taggedProjects: Int)
data class BackupState(
    val working: Boolean = false,
    val picker: BackupPicker? = null,
    val review: BackupRestoreReview? = null,
    val notice: String? = null,
    val error: String? = null,
) {
    val busy get() = working || picker != null
}

/** Picker grants are used only for this operation; archive content is never kept in a Bundle. */
class BackupViewModel(private val service: LibraryBackupService, private val resolver: ContentResolver?) : ViewModel() {
    private val mutableState = MutableStateFlow(BackupState())
    val state = mutableState.asStateFlow()
    private var candidate: VerifiedLibraryArchive? = null
    private var exportCallbackHandled = false

    fun requestExport(): String? {
        if (mutableState.value.busy || candidate != null) return null
        exportCallbackHandled = false
        mutableState.value = BackupState(picker = BackupPicker.EXPORT)
        return "TavernForge-library.zip"
    }
    fun requestRestore(): Boolean {
        if (mutableState.value.busy || candidate != null) return false
        mutableState.value = BackupState(picker = BackupPicker.RESTORE)
        return true
    }
    fun pickerFailed() {
        if (mutableState.value.working) return
        mutableState.value = mutableState.value.copy(picker = null, error = "The system file picker could not be opened. Try again.")
    }
    fun completeExport(uri: Uri?) = completeExportTo(uri != null) {
        withContext(Dispatchers.IO) {
            val stream = requireNotNull(resolver).openOutputStream(requireNotNull(uri), "wt") ?: throw IOException("No output stream")
            stream.use { output -> service.export(output).also { output.flush() } }
        }
    }
    internal fun completeExportTo(hasDestination: Boolean, export: suspend () -> ArchiveExportSummary) {
        if (mutableState.value.working || exportCallbackHandled) return
        if (mutableState.value.picker != BackupPicker.EXPORT) {
            exportCallbackHandled = true
            if (hasDestination) mutableState.value = mutableState.value.copy(error =
                "Backup export was interrupted before writing. The document provider may have created an empty file; remove it and start a new export. Saved library data remains intact.")
            return
        }
        exportCallbackHandled = true
        mutableState.value = BackupState(working = hasDestination)
        if (!hasDestination) return
        viewModelScope.launch {
            try {
                val result = export()
                mutableState.value = BackupState(notice = "Backup exported: ${result.projectCount} projects and ${result.artworkCount} artwork files (${result.archiveBytes} bytes).")
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                mutableState.value = BackupState(error = "Backup export failed. The document provider may have left a partial file; remove that file before retrying. Saved library data remains intact.")
            } finally { mutableState.value = mutableState.value.copy(working = false) }
        }
    }

    // completeRestoreFrom owns and closes the returned picker stream with use, including failures.
    @SuppressLint("Recycle")
    fun completeRestore(uri: Uri?) = completeRestoreFrom(uri != null) {
        requireNotNull(resolver).openInputStream(requireNotNull(uri)) ?: throw IOException("No input stream")
    }
    internal fun completeRestoreFrom(hasSource: Boolean, open: suspend () -> InputStream) {
        if (mutableState.value.picker != BackupPicker.RESTORE || mutableState.value.working) return
        mutableState.value = BackupState(working = hasSource)
        if (!hasSource) return
        viewModelScope.launch {
            var prepared: VerifiedLibraryArchive? = null
            try {
                // Assign inside the dispatcher so cancellation at its return boundary still closes the handle.
                withContext(Dispatchers.IO) { open().use { prepared = service.prepareRestore(it) } }
                val verified = requireNotNull(prepared)
                val manifest = verified.manifest
                candidate = verified
                mutableState.value = BackupState(review = BackupRestoreReview(manifest.projects.size, manifest.artwork.size,
                    manifest.compositions.size, manifest.links.size, manifest.metadata.count { it.tags.isNotEmpty() }))
            } catch (e: CancellationException) { prepared?.close(); throw e
            } catch (e: Exception) { prepared?.close(); mutableState.value = BackupState(error = restoreError(e))
            } finally { mutableState.value = mutableState.value.copy(working = false) }
        }
    }
    fun discardReview() {
        if (mutableState.value.busy) return
        candidate?.close(); candidate = null
        mutableState.value = BackupState()
    }
    fun acceptRestore() {
        if (mutableState.value.busy) return
        val archive = candidate ?: return
        mutableState.value = BackupState(working = true)
        viewModelScope.launch {
            // onCleared retains ownership until this coroutine actually starts.
            candidate = null
            try {
                val result = service.restore(archive)
                mutableState.value = BackupState(notice = "Restored ${result.projectIds.size} projects and ${result.artworkIds.size} artwork files as new library copies.")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { mutableState.value = BackupState(error = restoreError(e))
            } finally { archive.close(); mutableState.value = mutableState.value.copy(working = false) }
        }
    }
    override fun onCleared() { candidate?.close(); candidate = null }
    private fun restoreError(error: Exception): String = when ((error as? ArchiveException)?.code) {
        ArchiveError.UNSUPPORTED_VERSION -> "This backup uses an unsupported archive version. The library was not changed."
        ArchiveError.LIMIT_EXCEEDED -> "This backup exceeds the supported size or item limits. The library was not changed."
        ArchiveError.HASH_MISMATCH -> "Backup content failed its integrity check. The library was not changed."
        ArchiveError.INVALID_ARCHIVE, ArchiveError.INVALID_MANIFEST -> "This is not a valid TavernForge library backup. The library was not changed."
        else -> "The backup could not be restored. Saved projects were not replaced. Try selecting the backup again."
    }
}

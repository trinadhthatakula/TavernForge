package com.valhalla.tavernforge

import androidx.lifecycle.ViewModelStore
import com.valhalla.tavernforge.core.model.ProjectMetadata
import com.valhalla.tavernforge.core.model.ProjectSummary
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.data.archive.*
import com.valhalla.tavernforge.format.CharacterCardEngine
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val gateway = ArchiveGateway()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { stores.forEach(ViewModelStore::clear); Dispatchers.resetMain() }
    private fun model(service: LibraryBackupService) = BackupViewModel(service, null).also { model ->
        stores += ViewModelStore().also { it.put("backup", model) }
    }
    private suspend fun TestScope.settle(model: BackupViewModel) {
        advanceUntilIdle()
        withContext(Dispatchers.Default) { withTimeout(5_000) { model.state.first { !it.working } } }
        advanceUntilIdle()
    }
    private suspend fun bytes(service: LibraryBackupService) = ByteArrayOutputStream().also { service.export(it) }.toByteArray()

    @Test fun cancelledPickersDoNotOpenStreamsOrMutateTheLibrary() = runTest(dispatcher) {
        val model = model(LibraryBackupService(gateway, temporary.newFolder()))
        var writes = 0; var reads = 0
        assertNotNull(model.requestExport()); assertNull(model.requestExport())
        model.completeExportTo(false) { writes++; error("Must not open") }
        assertTrue(model.requestRestore())
        model.completeRestoreFrom(false) { reads++; error("Must not open") }
        advanceUntilIdle()
        assertEquals(0, writes); assertEquals(0, reads); assertEquals(0, gateway.restores)
        assertFalse(model.state.value.busy); assertNull(model.state.value.review)
    }

    @Test fun exportConsumesPickerOnceAndDoesNotShowProviderExceptionText() = runTest(dispatcher) {
        val model = model(LibraryBackupService(gateway, temporary.newFolder())); var writes = 0
        model.requestExport()
        model.completeExportTo(true) { writes++; throw IOException("private document location") }
        model.completeExportTo(true) { writes++; error("repeated") }
        advanceUntilIdle()
        assertEquals(1, writes); assertTrue(model.state.value.error!!.contains("partial file"))
        assertFalse(model.state.value.error!!.contains("private")); assertNull(model.state.value.notice)
    }

    @Test fun acceptedOrphanExportReportsInterruptionWithoutWritingAndDuplicatesStayIgnored() = runTest(dispatcher) {
        val model = model(LibraryBackupService(gateway, temporary.newFolder())); var writes = 0
        model.completeExportTo(true) { writes++; error("Unreviewed orphan callback") }
        advanceUntilIdle()
        assertEquals(0, writes)
        assertTrue(model.state.value.error!!.contains("interrupted"))
        assertTrue(model.state.value.error!!.contains("empty file"))
        model.requestExport()
        model.completeExportTo(true) { writes++; ArchiveExportSummary(1, 0, 1, 100) }
        advanceUntilIdle()
        val success = model.state.value.notice
        model.completeExportTo(true) { writes++; error("Duplicate") }
        advanceUntilIdle()
        assertEquals(1, writes); assertEquals(success, model.state.value.notice); assertNull(model.state.value.error)
    }

    @Test fun validArchiveNeedsExplicitAcceptanceAndCannotRestoreTwice() = runTest(dispatcher) {
        val work = temporary.newFolder(); val service = LibraryBackupService(gateway, work); val bytes = bytes(service)
        val model = model(service)
        assertTrue(model.requestRestore()); model.completeRestoreFrom(true) { bytes.inputStream() }; settle(model)
        assertEquals(0, gateway.restores); assertEquals(1, model.state.value.review?.projects)
        assertEquals(1, model.state.value.review?.taggedProjects)
        assertNull(model.requestExport())
        model.acceptRestore(); model.acceptRestore(); settle(model)
        assertEquals(1, gateway.restores); assertNull(model.state.value.review)
        assertTrue(model.state.value.notice!!.contains("new library copies")); assertTrue(work.listFiles()!!.isEmpty())
    }

    @Test fun cancelledReviewAndClearingBeforeRestoreStartsBothDeleteStaging() = runTest(dispatcher) {
        val work = temporary.newFolder(); val service = LibraryBackupService(gateway, work); val bytes = bytes(service)
        val model = model(service)
        model.requestRestore(); model.completeRestoreFrom(true) { bytes.inputStream() }; settle(model)
        assertFalse(work.listFiles()!!.isEmpty())
        model.discardReview()
        assertEquals(0, gateway.restores); assertNull(model.state.value.review); assertTrue(work.listFiles()!!.isEmpty())
        model.requestRestore(); model.completeRestoreFrom(true) { bytes.inputStream() }; settle(model)
        model.acceptRestore(); stores.last().clear(); advanceUntilIdle()
        assertEquals(0, gateway.restores); assertTrue(work.listFiles()!!.isEmpty())
    }

    @Test fun invalidArchiveFailsBeforeReviewAndClearsStaging() = runTest(dispatcher) {
        val work = temporary.newFolder(); val model = model(LibraryBackupService(gateway, work))
        model.requestRestore(); model.completeRestoreFrom(true) { "private malformed input".byteInputStream() }; settle(model)
        assertNull(model.state.value.review); assertNotNull(model.state.value.error); assertEquals(0, gateway.restores)
        assertFalse(model.state.value.error!!.contains("private")); assertTrue(work.listFiles()!!.isEmpty())
    }

    @Test fun failedRestoreClosesHandleAndReportsNoSuccess() = runTest(dispatcher) {
        val work = temporary.newFolder(); val service = LibraryBackupService(gateway, work); val bytes = bytes(service)
        val model = model(service)
        model.requestRestore(); model.completeRestoreFrom(true) { bytes.inputStream() }; settle(model)
        gateway.failRestore = true; model.acceptRestore(); settle(model)
        assertNull(model.state.value.notice); assertNotNull(model.state.value.error); assertTrue(work.listFiles()!!.isEmpty())
    }

    private class ArchiveGateway : LibraryArchiveGateway {
        var restores = 0
        var failRestore = false
        private val source = CharacterCardEngine().createV2("Synthetic backup").source
        private val ref = ArchiveBlobRef(source.sha256, source.sizeBytes)
        private val summary = ProjectSummary("original", "Local title", "source.json", SourceFormat.V2, "2.0", false, 1, 1, source.sha256, source.sizeBytes)
        private val manifest = LibraryArchiveManifest(listOf(ArchiveProject(summary, "application/json", 0, ref, ref)),
            emptyList(), emptyList(), emptyList(), listOf(ProjectMetadata("original", 1, listOf("Local tag"))), 1)
        override suspend fun <T> withArchiveSnapshot(action: suspend (LibraryArchiveSnapshot) -> T) = action(LibraryArchiveSnapshot(manifest) { source.bytes() })
        override suspend fun restoreArchive(archive: VerifiedLibraryArchive): ArchiveRestoreResult {
            restores++
            if (failRestore) throw IOException("private transaction detail")
            return ArchiveRestoreResult(mapOf("original" to "new-copy"), emptyMap())
        }
    }
}

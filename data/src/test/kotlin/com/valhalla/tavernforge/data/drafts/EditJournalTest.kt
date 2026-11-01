package com.valhalla.tavernforge.data.drafts

import com.valhalla.tavernforge.core.model.ProjectSummary
import com.valhalla.tavernforge.core.model.SourceEnvelope
import com.valhalla.tavernforge.core.model.SourceFormat
import com.valhalla.tavernforge.core.model.StoredProject
import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.ExportResult
import com.valhalla.tavernforge.format.JsonPatch
import com.valhalla.tavernforge.format.ParseResult
import com.valhalla.tavernforge.format.PatchResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EditJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private val engine = CharacterCardEngine()
    private val original = SourceEnvelope("""{"name":"old","description":"","personality":"","scenario":"","first_mes":"","mes_example":"","unknown":{"n":1.2300e+2,"v":null}}""".toByteArray())
    private fun stored() = StoredProject(ProjectSummary("project-1", "Local", "source.json", SourceFormat.V1,
        null, false, 0, 0, original.sha256, original.sizeBytes), original)
    private fun record(input: RecoveryInput? = null): EditRecovery {
        val base = (engine.parse(original.bytes()) as ParseResult.Success).document
        val edited = (engine.patch(base, 0, listOf(JsonPatch.Replace("/name", JsonPrimitive("new")))) as PatchResult.Success).document
        val preview = SourceEnvelope((engine.snapshot(edited) as ExportResult.Success).bytes)
        return EditRecovery("project-1", 0, original.sha256, original.sha256, preview, true, input)
    }

    @Test fun reopenRestoresExactDraftAndUnfinishedInputWithoutMutatingOriginal() = runBlocking {
        val directory = temporary.newFolder()
        val journal = FileEditJournal(directory)
        val input = RecoveryInput("/description", "line\n🔮\uD800", "TEXT", "SET", false)
        val value = record(input)
        assertTrue(journal.save(journal.startSession(), value))
        val reopened = FileEditJournal(directory).load()!!
        assertEquals(input, reopened.input)
        assertArrayEquals(value.preview.bytes(), reopened.preview.bytes())
        val patches = reopened.patchesFor(stored(), engine)
        val base = (engine.parse(original.bytes()) as ParseResult.Success).document
        val edited = (engine.patch(base, 0, patches) as PatchResult.Success).document
        assertEquals("new", (edited.raw["name"] as JsonPrimitive).content)
        assertEquals(base.raw["unknown"], edited.raw["unknown"])
        assertArrayEquals(original.bytes(), edited.source.bytes())
    }

    @Test fun staleRevisionAndEitherHashRejectRecoveryWithoutRebase() {
        val value = record()
        val current = stored()
        for (changed in listOf(current.copy(documentRevision = 1),
            current.copy(source = SourceEnvelope("{}".toByteArray())),
            current.copy(currentSource = SourceEnvelope("{}".toByteArray())))) {
            assertEquals(EditJournalError.STALE_BASE, assertThrows(EditJournalException::class.java) {
                value.patchesFor(changed, engine)
            }.code)
        }
    }

    @Test fun changedVersionAndNoChangeWithDifferentPreviewAreRejected() {
        val versioned = SourceEnvelope("""{"spec":"future","spec_version":"9"}""".toByteArray())
        val invalid = EditRecovery("project-1", 0, original.sha256, original.sha256, versioned, true)
        assertThrows(EditJournalException::class.java) { invalid.patchesFor(stored(), engine) }
        val falseNoOp = EditRecovery("project-1", 0, original.sha256, original.sha256, record().preview, false)
        assertThrows(EditJournalException::class.java) { falseNoOp.patchesFor(stored(), engine) }
        val trueNoOp = EditRecovery("project-1", 0, original.sha256, original.sha256, original, false)
        assertTrue(trueNoOp.patchesFor(stored(), engine).isEmpty())
    }

    @Test fun invalidDraftRemainsRecoverableAndStrictExportStaysBlocked() {
        val base = (engine.parse(original.bytes()) as ParseResult.Success).document
        val changed = (engine.patch(base, 0, listOf(JsonPatch.Remove("/name"))) as PatchResult.Success).document
        val snapshot = SourceEnvelope((engine.snapshot(changed) as ExportResult.Success).bytes)
        val value = EditRecovery("project-1", 0, original.sha256, original.sha256, snapshot, true)
        val restored = (engine.patch(base, 0, value.patchesFor(stored(), engine)) as PatchResult.Success).document
        assertFalse(engine.validate(restored).isValid)
        assertTrue(engine.export(restored) is ExportResult.Failure)
    }

    @Test fun clearAndNewSessionRejectDelayedWritesAndPreserveUnrelatedFiles() = runBlocking {
        val directory = temporary.newFolder()
        val unrelated = directory.resolve("keep.txt").apply { writeText("keep") }
        val journal = FileEditJournal(directory)
        val first = journal.startSession()
        assertTrue(journal.save(first, record()))
        val next = journal.startSession()
        assertFalse(journal.save(first, record()))
        assertTrue(journal.save(next, record()))
        journal.clear()
        assertFalse(journal.save(next, record()))
        assertNull(journal.load())
        assertEquals("keep", unrelated.readText())
    }

    @Test fun corruptionTruncationAndOversizeFailClosedAndRetainJournal() = runBlocking {
        val directory = temporary.newFolder()
        val journal = FileEditJournal(directory)
        journal.save(journal.startSession(), record())
        val file = directory.resolve("edit-recovery.bin")
        val valid = file.readBytes()
        val variants = listOf(valid.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }, valid.copyOf(20), ByteArray(9 * 1024 * 1024 + 1))
        for (bytes in variants) {
            file.writeBytes(bytes)
            val error = try { journal.load(); null } catch (e: EditJournalException) { e.code }
            assertEquals(EditJournalError.CORRUPT_JOURNAL, error)
            assertTrue(file.exists())
        }
    }
}

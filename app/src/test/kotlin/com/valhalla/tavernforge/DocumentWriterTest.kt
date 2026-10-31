package com.valhalla.tavernforge

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DocumentWriterTest {
    @Test fun exactBytesAreWrittenAndSuccessIncludesFlushAndClose() = runBlocking {
        val bytes = ByteArray(50_000) { (it % 251).toByte() }
        val stream = RecordingStream()
        writeJsonDocument(bytes) { stream }
        assertArrayEquals(bytes, stream.output.toByteArray())
        assertTrue(stream.flushed); assertTrue(stream.closed)
    }

    @Test fun partialWriteFailureStillClosesTheProviderAndPropagates() = runBlocking {
        val stream = RecordingStream(failWrite = true)
        try { writeJsonDocument(ByteArray(40_000)) { stream }; fail("A partial write must fail")
        } catch (_: IOException) { }
        assertTrue(stream.closed)
        assertFalse(stream.flushed)
    }

    @Test fun flushAndCloseFailuresAreNotSuccessfulExports() = runBlocking {
        for (stream in listOf(RecordingStream(failFlush = true), RecordingStream(failClose = true))) {
            try { writeJsonDocument("{}".encodeToByteArray()) { stream }; fail("Finishing the stream must succeed")
            } catch (_: IOException) { }
            assertTrue(stream.closed)
        }
    }

    @Test fun missingProviderStreamFails() = runBlocking {
        try { writeJsonDocument("{}".encodeToByteArray()) { null }; fail("No stream must fail")
        } catch (_: IOException) { }
    }

    private class RecordingStream(val failWrite: Boolean = false, val failFlush: Boolean = false,
        val failClose: Boolean = false) : OutputStream() {
        val output = ByteArrayOutputStream()
        var flushed = false
        var closed = false
        override fun write(value: Int) { output.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            output.write(bytes, offset, length)
            if (failWrite) throw IOException("Provider write error")
        }
        override fun flush() { flushed = true; if (failFlush) throw IOException("Provider flush error") }
        override fun close() { closed = true; if (failClose) throw IOException("Provider close error") }
    }
}

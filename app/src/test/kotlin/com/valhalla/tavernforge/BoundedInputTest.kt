package com.valhalla.tavernforge

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedInputTest {
    @Test
    fun inputExactlyAtLimitIsReadWithoutModification() = runBlocking {
        val bytes = byteArrayOf(0, 1, -1, 127)
        assertArrayEquals(bytes, readBounded(ByteArrayInputStream(bytes), 4))
    }

    @Test
    fun emptyInputReachesParserAsEmptyBytes() = runBlocking {
        assertArrayEquals(byteArrayOf(), readBounded(ByteArrayInputStream(byteArrayOf()), 4))
    }

    @Test
    fun oneExtraByteIsRejectedEvenWhenAvailableLies() {
        val stream = object : ByteArrayInputStream(ByteArray(5)) {
            override fun available() = 0
        }
        assertThrows(DocumentTooLargeException::class.java) { runBlocking { readBounded(stream, 4) } }
    }

    @Test
    fun zeroLengthBulkReadStillMakesBoundedProgress() = runBlocking {
        val source = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val stream = object : InputStream() {
            override fun read() = source.read()
            override fun read(buffer: ByteArray, off: Int, len: Int) = 0
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), readBounded(stream, 3))
    }
}

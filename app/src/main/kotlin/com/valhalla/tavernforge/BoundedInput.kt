package com.valhalla.tavernforge

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounds real bytes rather than trusting provider metadata or InputStream.available(). */
internal suspend fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8192)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count == -1) break
        if (count == 0) {
            // Guard unusual providers that return zero without making progress.
            val byte = input.read()
            if (byte == -1) break
            if (output.size() == maxBytes) throw DocumentTooLargeException()
            output.write(byte)
        } else {
            if (count > maxBytes - output.size()) throw DocumentTooLargeException()
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}

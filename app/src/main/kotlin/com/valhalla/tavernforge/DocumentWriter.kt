package com.valhalla.tavernforge

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

fun interface DocumentWriter {
    suspend fun write(uri: Uri, bytes: ByteArray)
}

/** Only a CreateDocument URI is passed here. Never retain, overwrite the import, or guess cleanup. */
class ContentResolverDocumentWriter(private val resolver: ContentResolver) : DocumentWriter {
    override suspend fun write(uri: Uri, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        writeJsonDocument(bytes) { resolver.openOutputStream(uri, "wt") }
    }
}

/** Completion includes flush and close. Providers may leave partial files after an error. */
internal suspend fun writeJsonDocument(bytes: ByteArray, open: () -> OutputStream?) {
    coroutineContext.ensureActive()
    val stream = open() ?: throw IOException("Document provider returned no output stream")
    stream.use { output ->
        var offset = 0
        while (offset < bytes.size) {
            coroutineContext.ensureActive()
            val count = minOf(16 * 1024, bytes.size - offset)
            output.write(bytes, offset, count)
            offset += count
        }
        output.flush()
    }
    coroutineContext.ensureActive()
}

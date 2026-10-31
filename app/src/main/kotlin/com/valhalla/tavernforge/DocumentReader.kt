package com.valhalla.tavernforge

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImportedBytes(val displayName: String, val bytes: ByteArray)
class DocumentTooLargeException : IOException()

/** The system picker grants access; inspection neither persists the grant nor writes the file. */
class DocumentReader(private val resolver: ContentResolver) {
    suspend fun read(uri: Uri, maxBytes: Int): ImportedBytes = withContext(Dispatchers.IO) {
        // Some otherwise readable providers do not expose OpenableColumns metadata.
        val providerName = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column)?.take(160) else null
                }
        } catch (_: RuntimeException) { null }
        val name = providerName?.takeIf { it.isNotBlank() } ?: "Selected JSON file"
        val content = resolver.openInputStream(uri)?.use { input ->
            readBounded(input, maxBytes)
        } ?: throw IOException("Document provider returned no stream")
        ImportedBytes(name, content)
    }
}

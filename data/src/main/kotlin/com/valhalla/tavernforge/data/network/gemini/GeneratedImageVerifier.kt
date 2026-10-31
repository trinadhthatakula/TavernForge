package com.valhalla.tavernforge.data.network.gemini

import android.graphics.BitmapFactory
import com.valhalla.tavernforge.core.model.generation.GenerationError
import com.valhalla.tavernforge.core.model.generation.GenerationException
import com.valhalla.tavernforge.core.model.generation.GenerationLimits

fun interface GeneratedImageVerifier {
    /** Check content MIME, dimensions, decoded pixel budget and decoder validity before staging. */
    fun verify(bytes: ByteArray, mimeType: String, limits: GenerationLimits): Pair<Int, Int>
}

class AndroidGeneratedImageVerifier : GeneratedImageVerifier {
    override fun verify(bytes: ByteArray, mimeType: String, limits: GenerationLimits): Pair<Int, Int> {
        if (bytes.isEmpty() || bytes.size > limits.maxImageBytes) fail(GenerationError.LIMIT_EXCEEDED)
        val jpeg = bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        val png = bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        if (!((mimeType == "image/jpeg" && jpeg) || (mimeType == "image/png" && png))) fail(GenerationError.INVALID_RESPONSE)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType != mimeType) fail(GenerationError.INVALID_RESPONSE)
        if (bounds.outWidth.toLong() * bounds.outHeight > limits.maxImagePixels) fail(GenerationError.LIMIT_EXCEEDED)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: fail(GenerationError.INVALID_RESPONSE)
        try {
            if (decoded.width != bounds.outWidth || decoded.height != bounds.outHeight) fail(GenerationError.INVALID_RESPONSE)
        } finally { decoded.recycle() }
        return bounds.outWidth to bounds.outHeight
    }

    private fun fail(code: GenerationError): Nothing = throw GenerationException(code)
}

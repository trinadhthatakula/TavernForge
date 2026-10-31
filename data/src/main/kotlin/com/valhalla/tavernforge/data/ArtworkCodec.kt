package com.valhalla.tavernforge.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.valhalla.tavernforge.core.model.ArtworkSummary
import com.valhalla.tavernforge.core.model.SourceEnvelope
import java.io.ByteArrayOutputStream
import java.io.OutputStream

data class ArtworkImageInfo(val width: Int, val height: Int)
class ConvertedArtwork(bytes: ByteArray, val orientation: Int) {
    private val content = bytes.copyOf()
    fun bytes() = content.copyOf()
}
/** Android decoding is injected separately from the pure PNG engine and original file storage. */
interface ArtworkCodec {
    fun inspectJpeg(bytes: ByteArray): ArtworkImageInfo
    fun jpegToPng(bytes: ByteArray): ConvertedArtwork
}
class ArtworkPngConversionPlan internal constructor(val sourceArtwork: ArtworkSummary, bytes: ByteArray,
    val width: Int, val height: Int, val orientation: Int) {
    private val content = bytes.copyOf()
    val outputSha256 = SourceEnvelope(content, "image/png").sha256
    val reviewItems: List<String> get() = listOf(
        "Re-encode JPEG pixels as sRGB PNG and apply EXIF orientation $orientation; JPEG EXIF, ICC, XMP, IPTC, comments and thumbnails are omitted from the PNG. The original JPEG remains saved. Source ${sourceArtwork.id}:${sourceArtwork.sha256}; output $outputSha256; policy android-jpeg-png-1."
    )
    internal fun bytes() = content.copyOf()
}

/** Complete platform pixel decode after bounded header checks; never rewrites the imported JPEG. */
class AndroidArtworkCodec : ArtworkCodec {
    override fun inspectJpeg(bytes: ByteArray): ArtworkImageInfo {
        val bitmap = decode(bytes)
        return try { ArtworkImageInfo(bitmap.width, bitmap.height) } finally { bitmap.recycle() }
    }
    override fun jpegToPng(bytes: ByteArray): ConvertedArtwork {
        val orientation = try { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            catch (_: Exception) { invalid("JPEG metadata could not be read for reviewed conversion. Its original remains saved.") }
        if (orientation !in 0..8) invalid("JPEG orientation is unsupported; its original remains saved.")
        val decoded = decode(bytes)
        var oriented: Bitmap? = null
        try {
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> { matrix.setRotate(180f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            }
            oriented = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val output = LimitedOutput(MAX_BYTES)
            if (!oriented.compress(Bitmap.CompressFormat.PNG, 100, output)) invalid("JPEG pixels could not be encoded as a bounded PNG.")
            return ConvertedArtwork(output.bytes(), orientation)
        } finally { if (oriented != null && oriented !== decoded) oriented.recycle(); decoded.recycle() }
    }
    private fun decode(bytes: ByteArray): Bitmap {
        if (bytes.size !in 4..MAX_BYTES || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte() ||
            bytes[bytes.lastIndex - 1] != 0xff.toByte() || bytes.last() != 0xd9.toByte()) invalid("JPEG signature, end marker or byte budget is invalid.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outMimeType != "image/jpeg" || bounds.outWidth !in 1..MAX_DIMENSION || bounds.outHeight !in 1..MAX_DIMENSION ||
            bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) invalid("JPEG dimensions exceed the configured image budget.")
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB) }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: invalid("JPEG pixels could not be decoded.")
        if (bitmap.width != bounds.outWidth || bitmap.height != bounds.outHeight) { bitmap.recycle(); invalid("JPEG dimensions changed during decoding.") }
        return bitmap
    }
    private class LimitedOutput(private val limit: Int) : OutputStream() {
        private val output = ByteArrayOutputStream()
        override fun write(value: Int) { ensure(1); output.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { ensure(length); output.write(bytes, offset, length) }
        private fun ensure(count: Int) { if (count < 0 || output.size().toLong() + count > limit) throw java.io.IOException("Converted PNG exceeds its byte budget.") }
        fun bytes() = output.toByteArray()
    }
    private fun invalid(message: String): Nothing = throw ProjectStorageException(ProjectStorageError.INVALID_INPUT, message)
    companion object { const val MAX_BYTES = 32 * 1024 * 1024; const val MAX_DIMENSION = 16384; const val MAX_PIXELS = 16777216L }
}

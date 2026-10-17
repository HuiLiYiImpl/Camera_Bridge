package com.yaoyihan.nikonconnect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream

internal data class ExifTransform(
    val rotation: Float,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
)

internal fun exifTransform(orientation: Int): ExifTransform? = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransform(0f, -1f, 1f)
    ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransform(180f)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransform(180f, -1f, 1f)
    ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransform(90f, -1f, 1f)
    ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransform(90f)
    ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransform(-90f, -1f, 1f)
    ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransform(-90f)
    else -> null
}

internal fun resolveExifOrientation(thumbnailOrientation: Int?, originalOrientation: Int?): Int =
    thumbnailOrientation ?: originalOrientation ?: ExifInterface.ORIENTATION_NORMAL

/** RAW decoders use sensor pixels; the TIFF primary IFD owns their display orientation. */
internal fun primaryTiffOrientation(bytes: ByteArray): Int? {
    if (bytes.size < 8) return null
    val littleEndian = when {
        bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() -> true
        bytes[0] == 0x4d.toByte() && bytes[1] == 0x4d.toByte() -> false
        else -> return null
    }
    fun unsigned(offset: Int, count: Int): Long {
        var value = 0L
        repeat(count) { index ->
            val shift = (if (littleEndian) index else count - index - 1) * 8
            value = value or ((bytes[offset + index].toLong() and 0xff) shl shift)
        }
        return value
    }
    if (unsigned(2, 2) != 42L) return null
    val directory = unsigned(4, 4)
    if (directory < 8 || directory > bytes.size - 2L) return null
    val count = unsigned(directory.toInt(), 2).toInt()
    if (directory + 2L + count * 12L > bytes.size) return null
    repeat(count) { index ->
        val entry = directory.toInt() + 2 + index * 12
        if (unsigned(entry, 2) == 0x112L && unsigned(entry + 2, 2) == 3L && unsigned(entry + 4, 4) == 1L) {
            return unsigned(entry + 8, 2).toInt().takeIf { it in 1..8 }
        }
    }
    return null
}

internal object OrientedBitmaps {
    fun decode(bytes: ByteArray, orientation: Int = orientation(bytes)): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })?.let { orient(it, orientation) }

    fun orientation(bytes: ByteArray): Int = orientationOrNull(bytes) ?: ExifInterface.ORIENTATION_NORMAL

    fun orientationOrNull(bytes: ByteArray): Int? = primaryTiffOrientation(bytes) ?: runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED,
        ).takeIf { it in 1..8 }
    }.getOrNull()

    fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val transform = exifTransform(orientation) ?: return bitmap
        val matrix = Matrix().apply {
            setRotate(transform.rotation)
            postScale(transform.scaleX, transform.scaleY)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }

    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return bitmap
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply { setRotate(normalized.toFloat()) },
            true,
        )
    }
}

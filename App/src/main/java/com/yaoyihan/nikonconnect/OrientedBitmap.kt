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

internal object OrientedBitmaps {
    fun decode(bytes: ByteArray, orientation: Int = orientation(bytes)): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })?.let { orient(it, orientation) }

    fun orientation(bytes: ByteArray): Int = orientationOrNull(bytes) ?: ExifInterface.ORIENTATION_NORMAL

    fun orientationOrNull(bytes: ByteArray): Int? = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED,
        ).takeUnless { it == ExifInterface.ORIENTATION_UNDEFINED }
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

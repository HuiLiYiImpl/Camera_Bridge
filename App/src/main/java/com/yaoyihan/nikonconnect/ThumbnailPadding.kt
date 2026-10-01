package com.yaoyihan.nikonconnect

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

internal data class ThumbnailRect(val left: Int, val top: Int, val width: Int, val height: Int)

/** Crop only paired black padding that restores the camera-reported photo aspect ratio. */
internal fun thumbnailContentRect(width: Int, height: Int, imageWidth: Int, imageHeight: Int,
                                 pixel: (Int, Int) -> Int): ThumbnailRect? {
    if (width < 16 || height < 16 || imageWidth <= 0 || imageHeight <= 0) return null
    val photoRatio = imageWidth.toDouble() / imageHeight
    // ObjectInfo dimensions may describe sensor orientation rather than the EXIF display orientation.
    fun difference(w: Int, h: Int): Double {
        val ratio = w.toDouble() / h
        return min(abs(ln(ratio / photoRatio)), abs(ln(ratio * photoRatio)))
    }
    if (difference(width, height) < 0.05) return null
    fun black(color: Int) = ((color ushr 16) and 255) <= 16 &&
        ((color ushr 8) and 255) <= 16 && (color and 255) <= 16
    fun column(x: Int) = (0 until height).all { black(pixel(x, it)) }
    fun row(y: Int) = (0 until width).all { black(pixel(it, y)) }
    var left = 0; var right = 0; var top = 0; var bottom = 0
    while (left < width / 4 && column(left)) left++
    while (right < width / 4 && column(width - 1 - right)) right++
    while (top < height / 4 && row(top)) top++
    while (bottom < height / 4 && row(height - 1 - bottom)) bottom++
    // A capped scan, single dark edge or strongly asymmetric band is not reliable padding.
    if (left >= width / 4 || right >= width / 4 || top >= height / 4 || bottom >= height / 4) return null
    if (left + right > 0 && (left < 2 || right < 2 || abs(left - right) > 3)) return null
    if (top + bottom > 0 && (top < 2 || bottom < 2 || abs(top - bottom) > 3)) return null
    if (left + right + top + bottom == 0) return null
    val croppedWidth = width - left - right
    val croppedHeight = height - top - bottom
    if (difference(croppedWidth, croppedHeight) > 0.035) return null
    return ThumbnailRect(left, top, croppedWidth, croppedHeight)
}

internal fun trimThumbnailPadding(bitmap: Bitmap, imageWidth: Int, imageHeight: Int): Bitmap {
    if (imageWidth <= 0 || imageHeight <= 0) return bitmap
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val rect = thumbnailContentRect(bitmap.width, bitmap.height, imageWidth, imageHeight) { x, y ->
        pixels[y * bitmap.width + x]
    } ?: return bitmap
    return Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
}

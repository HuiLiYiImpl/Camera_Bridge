package com.yaoyihan.nikonconnect

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.roundToInt

data class CubeLut(val name: String, val size: Int, val values: FloatArray)

object CubeLuts {
    fun parse(name: String, source: String): CubeLut {
        var size = 0
        val values = ArrayList<Float>()
        source.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isBlank()) return@forEach
            val parts = line.split(Regex("\\s+"))
            when (parts.first().uppercase()) {
                "LUT_3D_SIZE" -> size = parts.getOrNull(1)?.toIntOrNull() ?: 0
                "TITLE", "DOMAIN_MIN", "DOMAIN_MAX", "LUT_1D_SIZE" -> Unit
                else -> if (parts.size >= 3) parts.take(3).mapNotNullTo(values) { it.toFloatOrNull() }
            }
        }
        require(size in 2..64) { "不支持的 LUT 尺寸" }
        require(values.size == size * size * size * 3) { "LUT 数据不完整" }
        return CubeLut(name.substringBeforeLast('.'), size, values.toFloatArray())
    }

    fun apply(source: Bitmap, lut: CubeLut): Bitmap {
        val width = source.width; val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val edge = lut.size - 1
        pixels.indices.forEach { index ->
            val pixel = pixels[index]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            val rf = r * edge; val gf = g * edge; val bf = b * edge
            val r0 = floor(rf).toInt(); val g0 = floor(gf).toInt(); val b0 = floor(bf).toInt()
            val r1 = minOf(r0 + 1, edge); val g1 = minOf(g0 + 1, edge); val b1 = minOf(b0 + 1, edge)
            fun sample(channel: Int, rr: Int, gg: Int, bb: Int) = lut.values[((bb * lut.size * lut.size + gg * lut.size + rr) * 3) + channel]
            fun channel(channel: Int): Int {
                fun mix(a: Float, c: Float, t: Float) = a + (c - a) * t
                val c00 = mix(sample(channel, r0, g0, b0), sample(channel, r1, g0, b0), rf - r0)
                val c10 = mix(sample(channel, r0, g1, b0), sample(channel, r1, g1, b0), rf - r0)
                val c01 = mix(sample(channel, r0, g0, b1), sample(channel, r1, g0, b1), rf - r0)
                val c11 = mix(sample(channel, r0, g1, b1), sample(channel, r1, g1, b1), rf - r0)
                return (mix(mix(c00, c10, gf - g0), mix(c01, c11, gf - g0), bf - b0).coerceIn(0f, 1f) * 255).roundToInt()
            }
            pixels[index] = (pixel and -0x1000000) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}

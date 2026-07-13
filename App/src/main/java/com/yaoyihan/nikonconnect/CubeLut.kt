package com.yaoyihan.nikonconnect

import android.graphics.Bitmap
import java.util.StringTokenizer
import kotlin.math.floor
import kotlin.math.roundToInt

data class CubeLut(
    val name: String,
    val size: Int,
    val values: FloatArray,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
)

object CubeLuts {
    fun parse(name: String, source: String): CubeLut {
        var size = 0
        var values: FloatArray? = null
        var valueCount = 0
        val domainMin = floatArrayOf(0f, 0f, 0f)
        val domainMax = floatArrayOf(1f, 1f, 1f)
        fun readVector(tokens: StringTokenizer, target: FloatArray, label: String) {
            repeat(3) { channel ->
                target[channel] = if (tokens.hasMoreTokens()) tokens.nextToken().toFloatOrNull()
                    ?: error("$label 格式错误") else error("$label 数据不完整")
            }
        }
        source.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isBlank()) return@forEach
            val tokens = StringTokenizer(line)
            val first = tokens.nextToken()
            when (if (first.first().isLetter()) first.uppercase() else null) {
                "LUT_3D_SIZE" -> {
                    size = if (tokens.hasMoreTokens()) tokens.nextToken().toIntOrNull() ?: 0 else 0
                    require(size in 2..64) { "不支持的 LUT 尺寸" }
                    values = FloatArray(size * size * size * 3)
                    valueCount = 0
                }
                "DOMAIN_MIN" -> readVector(tokens, domainMin, "DOMAIN_MIN")
                "DOMAIN_MAX" -> readVector(tokens, domainMax, "DOMAIN_MAX")
                "TITLE", "LUT_1D_SIZE" -> Unit
                else -> {
                    val target = values ?: error("LUT_3D_SIZE 必须位于 LUT 数据之前")
                    require(valueCount + 3 <= target.size) { "LUT 数据过多" }
                    target[valueCount++] = first.toFloatOrNull() ?: error("LUT 数据格式错误")
                    repeat(2) {
                        target[valueCount++] = if (tokens.hasMoreTokens()) tokens.nextToken().toFloatOrNull()
                            ?: error("LUT 数据格式错误") else error("LUT 数据不完整")
                    }
                }
            }
        }
        require(size in 2..64) { "不支持的 LUT 尺寸" }
        val parsedValues = values ?: error("LUT 数据不完整")
        require(valueCount == parsedValues.size) { "LUT 数据不完整" }
        require((0..2).all { domainMin[it].isFinite() && domainMax[it].isFinite() && domainMin[it] < domainMax[it] }) {
            "DOMAIN_MIN 必须小于 DOMAIN_MAX"
        }
        return CubeLut(name.substringBeforeLast('.'), size, parsedValues, domainMin, domainMax)
    }

    fun apply(source: Bitmap, lut: CubeLut): Bitmap {
        val width = source.width; val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val edge = lut.size - 1
        val edgeFloat = edge.toFloat()
        val domainScale = FloatArray(3) { edgeFloat / ((lut.domainMax[it] - lut.domainMin[it]) * 255f) }
        val domainOffset = FloatArray(3) { -lut.domainMin[it] * edgeFloat / (lut.domainMax[it] - lut.domainMin[it]) }
        pixels.indices.forEach { index ->
            val pixel = pixels[index]
            val rf = (((pixel shr 16) and 0xFF) * domainScale[0] + domainOffset[0]).coerceIn(0f, edgeFloat)
            val gf = (((pixel shr 8) and 0xFF) * domainScale[1] + domainOffset[1]).coerceIn(0f, edgeFloat)
            val bf = ((pixel and 0xFF) * domainScale[2] + domainOffset[2]).coerceIn(0f, edgeFloat)
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

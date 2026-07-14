package com.yaoyihan.nikonconnect

import android.graphics.Bitmap
import java.util.StringTokenizer
import kotlin.math.floor
import kotlin.math.roundToInt
import java.util.stream.IntStream

data class CubeLut(
    val name: String,
    val size: Int,
    val values: FloatArray,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
)

object CubeLuts {
    fun parse(name: String, source: String): CubeLut {
        require(source.length <= 32 * 1024 * 1024) { "LUT 文件过大" }
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
                    require(size in 2..65) { "不支持的 LUT 尺寸" }
                    values = FloatArray(size * size * size * 3)
                    valueCount = 0
                }
                "DOMAIN_MIN" -> readVector(tokens, domainMin, "DOMAIN_MIN")
                "DOMAIN_MAX" -> readVector(tokens, domainMax, "DOMAIN_MAX")
                "TITLE", "LUT_1D_SIZE" -> Unit
                else -> {
                    val target = values ?: error("LUT_3D_SIZE 必须位于 LUT 数据之前")
                    require(valueCount + 3 <= target.size) { "LUT 数据过多" }
                    target[valueCount++] = first.toFloatOrNull()?.also { require(it.isFinite()) { "LUT 含有非法数值" } } ?: error("LUT 数据格式错误")
                    repeat(2) {
                        target[valueCount++] = if (tokens.hasMoreTokens()) tokens.nextToken().toFloatOrNull()?.also { require(it.isFinite()) { "LUT 含有非法数值" } }
                            ?: error("LUT 数据格式错误") else error("LUT 数据不完整")
                    }
                }
            }
        }
        require(size in 2..65) { "不支持的 LUT 尺寸" }
        val parsedValues = values ?: error("LUT 数据不完整")
        require(valueCount == parsedValues.size) { "LUT 数据不完整" }
        require((0..2).all { domainMin[it].isFinite() && domainMax[it].isFinite() && domainMin[it] < domainMax[it] }) {
            "DOMAIN_MIN 必须小于 DOMAIN_MAX"
        }
        return CubeLut(name.substringBeforeLast('.'), size, parsedValues, domainMin, domainMax)
    }

    fun apply(source: Bitmap, lut: CubeLut, intensity: Float = 1f, inPlace: Boolean = false): Bitmap {
        val width = source.width; val height = source.height
        val edge = lut.size - 1
        val edgeFloat = edge.toFloat()
        val low = Array(3) { IntArray(256) }
        val high = Array(3) { IntArray(256) }
        val fraction = Array(3) { FloatArray(256) }
        repeat(3) { channel ->
            val scale = edgeFloat / ((lut.domainMax[channel] - lut.domainMin[channel]) * 255f)
            val offset = -lut.domainMin[channel] * edgeFloat / (lut.domainMax[channel] - lut.domainMin[channel])
            repeat(256) { value ->
                val coordinate = (value * scale + offset).coerceIn(0f, edgeFloat)
                val lower = floor(coordinate).toInt()
                low[channel][value] = lower
                high[channel][value] = minOf(lower + 1, edge)
                fraction[channel][value] = coordinate - lower
            }
        }
        val amount = intensity.coerceIn(0f, 1f)
        val output = if (inPlace && source.isMutable) source else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rowsPerChunk = ((2 * 1024 * 1024) / width.coerceAtLeast(1)).coerceIn(1, height)
        val pixels = IntArray(width * rowsPerChunk)
        var top = 0
        while (top < height) {
            val rows = minOf(rowsPerChunk, height - top)
            val count = width * rows
            source.getPixels(pixels, 0, width, 0, top, width, rows)
            applyParallel(pixels, count, lut, low, high, fraction, amount)
            output.setPixels(pixels, 0, width, 0, top, width, rows)
            top += rows
        }
        return output
    }

    private fun applyParallel(
        pixels: IntArray,
        count: Int,
        lut: CubeLut,
        low: Array<IntArray>,
        high: Array<IntArray>,
        fraction: Array<FloatArray>,
        intensity: Float,
    ) {
        val workers = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val chunks = (workers * 4).coerceAtMost(count.coerceAtLeast(1))
        IntStream.range(0, chunks).parallel().forEach { chunk ->
            val start = count * chunk / chunks
            val end = count * (chunk + 1) / chunks
            applyRange(pixels, start, end, lut, low, high, fraction, intensity)
        }
    }

    private fun applyRange(
        pixels: IntArray,
        start: Int,
        end: Int,
        lut: CubeLut,
        low: Array<IntArray>,
        high: Array<IntArray>,
        fraction: Array<FloatArray>,
        intensity: Float,
    ) {
        val values = lut.values
        val size = lut.size
        val plane = size * size
        for (index in start until end) {
            val pixel = pixels[index]
            val sourceR = (pixel ushr 16) and 0xFF
            val sourceG = (pixel ushr 8) and 0xFF
            val sourceB = pixel and 0xFF
            val r0 = low[0][sourceR]; val r1 = high[0][sourceR]; val tx = fraction[0][sourceR]
            val g0 = low[1][sourceG]; val g1 = high[1][sourceG]; val ty = fraction[1][sourceG]
            val b0 = low[2][sourceB]; val b1 = high[2][sourceB]; val tz = fraction[2][sourceB]
            val i000 = (b0 * plane + g0 * size + r0) * 3
            val i100 = (b0 * plane + g0 * size + r1) * 3
            val i010 = (b0 * plane + g1 * size + r0) * 3
            val i110 = (b0 * plane + g1 * size + r1) * 3
            val i001 = (b1 * plane + g0 * size + r0) * 3
            val i101 = (b1 * plane + g0 * size + r1) * 3
            val i011 = (b1 * plane + g1 * size + r0) * 3
            val i111 = (b1 * plane + g1 * size + r1) * 3
            var red = 0
            var green = 0
            var blue = 0
            for (channel in 0..2) {
                val c00 = values[i000 + channel] + (values[i100 + channel] - values[i000 + channel]) * tx
                val c10 = values[i010 + channel] + (values[i110 + channel] - values[i010 + channel]) * tx
                val c01 = values[i001 + channel] + (values[i101 + channel] - values[i001 + channel]) * tx
                val c11 = values[i011 + channel] + (values[i111 + channel] - values[i011 + channel]) * tx
                val lowB = c00 + (c10 - c00) * ty
                val highB = c01 + (c11 - c01) * ty
                val graded = ((lowB + (highB - lowB) * tz).coerceIn(0f, 1f) * 255f)
                val source = when (channel) { 0 -> sourceR; 1 -> sourceG; else -> sourceB }
                val mixed = (source + (graded - source) * intensity).roundToInt().coerceIn(0, 255)
                when (channel) { 0 -> red = mixed; 1 -> green = mixed; else -> blue = mixed }
            }
            pixels[index] = (pixel and -0x1000000) or (red shl 16) or (green shl 8) or blue
        }
    }
}

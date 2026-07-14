package com.yaoyihan.nikonconnect

import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import javax.xml.parsers.DocumentBuilderFactory

data class ImportedLut(val lut: CubeLut, val format: String)

object LutImporters {
    fun parse(name: String, bytes: ByteArray): ImportedLut {
        require(bytes.size <= 32 * 1024 * 1024) { "LUT 文件过大" }
        return when (name.substringAfterLast('.', "").lowercase()) {
            "cube" -> ImportedLut(CubeLuts.parse(name, bytes.toString(Charsets.UTF_8)), "CUBE")
            "png" -> ImportedLut(parsePng(bytes), "PNG")
            "xmp" -> ImportedLut(parseXmp(bytes), "XMP")
            else -> error("仅支持 .cube、.png 和 .xmp LUT")
        }
    }

    private fun parsePng(bytes: ByteArray): CubeLut {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取 PNG LUT" }
        require(bounds.outWidth.toLong() * bounds.outHeight <= 16_777_216) { "PNG LUT 像素数量过大" }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options()) ?: error("无法读取 PNG LUT")
        val width = bitmap.width; val height = bitmap.height
        val layout: Pair<Int, (Int, Int, Int) -> Pair<Int, Int>> = when {
            width == height -> {
                val level = Math.round(Math.cbrt(width.toDouble())).toInt()
                require(level >= 2 && level * level * level == width && level * level <= 65) { "该 PNG 不是可识别的 LUT 布局" }
                level * level to { r, g, b -> ((b * level + g) * level + r) % width to ((b * level + g) * level + r) / width }
            }
            width == height * height && height in 2..65 -> height to { r, g, b -> r + g * height to b }
            else -> error("该 PNG 不是可识别的 LUT 布局")
        }
        val size = layout.first
        val values = FloatArray(size * size * size * 3)
        try {
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                val (x, y) = layout.second(r, g, b)
                require(x in 0 until width && y in 0 until height) { "该 PNG 不是可识别的 LUT 布局" }
                val pixel = bitmap.getPixel(x, y)
                val index = ((b * size + g) * size + r) * 3
                values[index] = ((pixel shr 16) and 255) / 255f
                values[index + 1] = ((pixel shr 8) and 255) / 255f
                values[index + 2] = (pixel and 255) / 255f
            }
        } finally { bitmap.recycle() }
        return CubeLut("PNG LUT", size, values, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
    }

    private fun parseXmp(bytes: ByteArray): CubeLut {
        val text = bytes.toString(Charsets.UTF_8)
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "XMP 禁止使用外部实体" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setExpandEntityReferences(false)
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val crs = "http://ns.adobe.com/camera-raw-settings/1.0/"
        val descriptions = doc.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description")
        var tableId: String? = null; var encoded: String? = null
        for (i in 0 until descriptions.length) {
            val element = descriptions.item(i) as? org.w3c.dom.Element ?: continue
            tableId = tableId ?: element.getAttributeNS(crs, "RGBTable").takeIf { it.isNotBlank() }
        }
        for (i in 0 until descriptions.length) {
            val element = descriptions.item(i) as? org.w3c.dom.Element ?: continue
            val wanted = tableId?.let { "Table_$it" }
            if (wanted != null) encoded = element.getAttributeNS(crs, wanted).takeIf { it.isNotBlank() } ?: encoded
            if (encoded == null) for (j in 0 until element.attributes.length) {
                val attr = element.attributes.item(j)
                if (attr.localName?.startsWith("Table_") == true || attr.nodeName.startsWith("crs:Table_")) { encoded = attr.nodeValue; break }
            }
        }
        require(!encoded.isNullOrBlank()) { "该 XMP 不包含可识别的 3D LUT 数据" }
        val raw = inflate(decodeBase85(encoded!!))
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() >= 16) { "XMP RGB Table 数据不完整" }
        require(buffer.int == 1 && buffer.int == 1 && buffer.int == 3) { "不支持的 XMP RGB Table" }
        val size = buffer.int
        require(size in 2..65) { "不支持的 XMP LUT 尺寸" }
        val values = FloatArray(size * size * size * 3)
        for (r in 0 until size) for (g in 0 until size) for (b in 0 until size) {
            require(buffer.remaining() >= 6) { "XMP RGB Table 数据不完整" }
            val index = ((b * size + g) * size + r) * 3
            values[index] = (buffer.short.toInt() and 0xffff) / 65535f
            values[index + 1] = (buffer.short.toInt() and 0xffff) / 65535f
            values[index + 2] = (buffer.short.toInt() and 0xffff) / 65535f
        }
        return CubeLut("XMP LUT", size, values, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
    }

    private fun decodeBase85(input: String): ByteArray {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-:+=^!/*?`'|()[]{}@%$#"
        val values = IntArray(256) { -1 }; alphabet.forEachIndexed { index, c -> values[c.code] = index }
        val output = java.io.ByteArrayOutputStream(); var phase = 0; var value = 0L
        input.forEach { c -> val digit = c.code.takeIf { it < 256 }?.let { values[it] } ?: -1; if (digit < 0) return@forEach; value += digit.toLong() * Math.pow(85.0, phase.toDouble()).toLong(); phase++; if (phase == 5) { repeat(4) { output.write((value shr (8 * it)).toInt() and 255) }; phase = 0; value = 0 } }
        if (phase > 1) repeat(phase - 1) { output.write((value shr (8 * it)).toInt() and 255) }
        return output.toByteArray()
    }

    private fun inflate(data: ByteArray): ByteArray {
        require(data.size >= 4) { "XMP 压缩数据不完整" }
        val expected = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int
        require(expected in 1..16 * 1024 * 1024) { "XMP 解压数据过大" }
        val inflater = Inflater().apply { setInput(data, 4, data.size - 4) }
        val output = ByteArray(expected)
        try { val count = inflater.inflate(output); require(count == expected) { "XMP 解压数据长度错误" }; return output } finally { inflater.end() }
    }
}

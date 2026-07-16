package com.yaoyihan.nikonconnect

import com.yaoyihan.nikonconnect.lut.toMedia3Cube
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.Assert.assertThrows
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

class CubeLutsTest {
    @Test fun parsesTwoByTwoIdentityLut() {
        val lut = CubeLuts.parse("identity.cube", "LUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1")
        assertEquals(2, lut.size)
        assertEquals(24, lut.values.size)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), lut.domainMin, 0f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), lut.domainMax, 0f)
    }

    @Test fun parsesCustomDomain() {
        val lut = CubeLuts.parse("domain.cube", "DOMAIN_MIN -1 0.25 0\nDOMAIN_MAX 1 0.75 2\nLUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1")
        assertArrayEquals(floatArrayOf(-1f, .25f, 0f), lut.domainMin, 0f)
        assertArrayEquals(floatArrayOf(1f, .75f, 2f), lut.domainMax, 0f)
    }

    @Test fun rejectsIncompleteAndNonFiniteData() {
        assertThrows(IllegalArgumentException::class.java) { CubeLuts.parse("bad.cube", "LUT_3D_SIZE 2\n0 0 0") }
        assertThrows(IllegalArgumentException::class.java) { CubeLuts.parse("bad.cube", "LUT_3D_SIZE 2\nNaN 0 0") }
    }

    @Test fun media3CubeKeepsChannelOrderAndIntensity() {
        val black = CubeLut("black", 2, FloatArray(24), floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
        val full = black.toMedia3Cube(1f)
        val original = black.toMedia3Cube(0f)
        assertEquals(0xFF000000.toInt(), full[1][0][0])
        assertEquals(0xFFFF0000.toInt(), original[1][0][0])
        assertEquals(0xFFFFFFFF.toInt(), original[1][1][1])
    }

    @Test fun parsesAdobeXmpRgbTableResiduals() {
        val size = 3
        val raw = ByteBuffer.allocate(16 + size * size * size * 3 * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1)
            .putInt(1)
            .putInt(3)
            .putInt(size)
            .put(ByteArray(size * size * size * 3 * 2))
            .array()
        val encoded = encodeXmpTable(raw)
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                       xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/">
                <rdf:Description crs:RGBTable="test" crs:Table_test="$encoded" />
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val lut = LutImporters.parse("identity.xmp", xmp.toByteArray()).lut

        assertEquals(size, lut.size)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), lut.values.copyOfRange(0, 3), 0f)
        assertArrayEquals(floatArrayOf(32768f / 65535f, 0f, 0f), lut.values.copyOfRange(3, 6), 0f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), lut.values.copyOfRange(78, 81), 0f)
    }

    private fun encodeXmpTable(raw: ByteArray): String {
        val deflater = Deflater()
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        try {
            deflater.setInput(raw)
            deflater.finish()
            while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
        } finally { deflater.end() }
        val packed = ByteBuffer.allocate(4 + compressed.size())
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(raw.size)
            .put(compressed.toByteArray())
            .array()
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-:+=^!/*?`'|()[]{}@%$#"
        return buildString {
            var offset = 0
            while (offset < packed.size) {
                val count = minOf(4, packed.size - offset)
                var value = 0L
                repeat(count) { value = value or ((packed[offset + it].toLong() and 0xff) shl (8 * it)) }
                repeat(count + 1) {
                    append(alphabet[(value % 85).toInt()])
                    value /= 85
                }
                offset += count
            }
        }
    }
}

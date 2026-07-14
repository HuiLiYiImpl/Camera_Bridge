package com.yaoyihan.nikonconnect

import com.yaoyihan.nikonconnect.lut.toMedia3Cube
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.Assert.assertThrows

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
}

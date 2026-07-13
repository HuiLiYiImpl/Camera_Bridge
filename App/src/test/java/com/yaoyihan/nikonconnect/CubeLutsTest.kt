package com.yaoyihan.nikonconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

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
}

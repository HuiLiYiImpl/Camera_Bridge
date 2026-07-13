package com.yaoyihan.nikonconnect

import org.junit.Assert.assertEquals
import org.junit.Test

class CubeLutsTest {
    @Test fun parsesTwoByTwoIdentityLut() {
        val lut = CubeLuts.parse("identity.cube", "LUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1")
        assertEquals(2, lut.size)
        assertEquals(24, lut.values.size)
    }
}

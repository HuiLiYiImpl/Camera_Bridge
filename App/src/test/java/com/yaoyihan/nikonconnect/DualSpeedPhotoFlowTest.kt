package com.yaoyihan.nikonconnect

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class DualSpeedPhotoFlowTest {
    @Test fun middleOfStreamKeepsOnePointFiveSpeedRatioInBothDirections() {
        for (delta in listOf(48f, -48f)) {
            val (left, right) = dualSpeedScrollStep(delta, 5_000f, 4_000f, 500f)
            assertEquals(delta, right, .001f)
            assertEquals(right * 1.5f, left, .001f)
        }
    }

    @Test fun unequalColumnLengthsConvergeWithoutOvershootingOrLeavingAnUnreachableTail() {
        for ((initialLeft, initialRight) in listOf(4_000f to 3_000f, 2_000f to 5_000f, 11f to 700f, 0f to 800f)) {
            var left = initialLeft
            var right = initialRight
            repeat(600) {
                val (l, r) = dualSpeedScrollStep(32f, left, right, 500f)
                assertTrue(l in 0f..left && r in 0f..right)
                if (left + right > 0f) assertTrue(l + r > 0f)
                left -= l
                right -= r
            }
            assertEquals(0f, left, .01f)
            assertEquals(0f, right, .01f)
        }
    }

    @Test fun fastFlingClampsBothColumnsAtTheirActualEnds() {
        assertEquals(340f to 270f, dualSpeedScrollStep(10_000f, 340f, 270f, 500f))
        assertEquals(-340f to -270f, dualSpeedScrollStep(-10_000f, 340f, 270f, 500f))
    }

    @Test fun reverseScrollingAlsoReconcilesTheTopAndStopsNestedScrollConsumptionAtTheEdge() {
        var left = 1_900f
        var right = 3_700f
        repeat(300) {
            val (l, r) = dualSpeedScrollStep(-48f, left, right, 500f)
            assertTrue(l <= 0f && r <= 0f)
            left -= abs(l)
            right -= abs(r)
        }
        assertEquals(0f, left, .01f)
        assertEquals(0f, right, .01f)
        assertEquals(0f to 0f, dualSpeedScrollStep(-48f, left, right, 500f))
        assertEquals(0f to 0f, dualSpeedScrollStep(0f, 100f, 200f, 0f))
    }
}

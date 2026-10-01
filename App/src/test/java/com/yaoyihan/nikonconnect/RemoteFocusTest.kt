package com.yaoyihan.nikonconnect

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RemoteFocusTest {
    @Test fun portraitTapsIgnoreTopAndBottomBars() {
        // 3:2 frame occupies y=400..600 in this portrait viewport.
        assertNull(focusPointFromTap(150f, 399f, 300, 1000, 600, 400))
        assertNull(focusPointFromTap(150f, 600f, 300, 1000, 600, 400))
        assertPoint(.5f, .5f, focusPointFromTap(150f, 500f, 300, 1000, 600, 400))
        assertPoint(0f, 0f, focusPointFromTap(0f, 400f, 300, 1000, 600, 400))
    }

    @Test fun landscapeTapsIgnoreLeftAndRightBars() {
        // Image occupies x=200..800, with 200-pixel bars on either side.
        assertNull(focusPointFromTap(199f, 200f, 1000, 400, 600, 400))
        assertNull(focusPointFromTap(800f, 200f, 1000, 400, 600, 400))
        assertPoint(.5f, .5f, focusPointFromTap(500f, 200f, 1000, 400, 600, 400))
    }

    @Test fun rotationAndPreviewDownsamplingPreserveTheCameraPoint() {
        val portrait = focusPointFromTap(75f, 550f, 300, 1000, 600, 400)!!
        val landscape = focusPointFromTap(350f, 300f, 1000, 400, 300, 200)!!
        assertPoint(.25f, .75f, portrait)
        assertEquals(fullGeometry.cameraPoint(portrait), fullGeometry.cameraPoint(landscape))
    }

    @Test fun invalidViewportAndNonfiniteTapsAreRejected() {
        assertNull(focusPointFromTap(1f, 1f, 0, 100, 600, 400))
        assertNull(focusPointFromTap(1f, 1f, 100, 100, -1, 400))
        assertNull(focusPointFromTap(Float.NaN, 1f, 100, 100, 600, 400))
        assertNull(focusPointFromTap(1f, Float.POSITIVE_INFINITY, 100, 100, 600, 400))
        assertNull(focusPointFromTap(-1f, 50f, 100, 100, 600, 400))
    }

    @Test fun headerUsesBigEndianWholeImageDimensionsRatherThanJpegPixels() {
        val parsed = LiveViewFocusGeometry.parse(header(fullGeometry), 128)!!
        assertEquals(fullGeometry, parsed)
        assertEquals(3024 to 2000, parsed.cameraPoint(NormalizedFocusPoint(.5f, .5f)))
        assertEquals(1512 to 3000, parsed.cameraPoint(NormalizedFocusPoint(.25f, .75f)))
        assertEquals(6047 to 3999, parsed.cameraPoint(NormalizedFocusPoint(1f, 1f)))
    }

    @Test fun zoomedCropIncludesItsOffsetInWholeImageCoordinates() {
        val zoomed = fullGeometry.copy(displayWidth = 3024, displayHeight = 2000, centerX = 3500, centerY = 2200)
        val parsed = LiveViewFocusGeometry.parse(header(zoomed), 128)!!
        assertEquals(1988 to 1200, parsed.cameraPoint(NormalizedFocusPoint(0f, 0f)))
        assertEquals(3500 to 2200, parsed.cameraPoint(NormalizedFocusPoint(.5f, .5f)))
        assertEquals(5012 to 3200, parsed.cameraPoint(NormalizedFocusPoint(1f, 1f)))
    }

    @Test fun truncatedAndUnknownHeadersDoNotGuessCoordinates() {
        assertNull(LiveViewFocusGeometry.parse(ByteArray(24), 24))
        assertNull(LiveViewFocusGeometry.parse(header(fullGeometry), 200))
        assertNull(LiveViewFocusGeometry.parse(ByteArray(128), 128))
        assertNull(LiveViewFocusGeometry.parse(header(fullGeometry.copy(displayWidth = 6049)), 128))
        assertNull(LiveViewFocusGeometry.parse(header(fullGeometry.copy(centerX = 100)), 128))
        assertNull(LiveViewFocusGeometry.parse(header(fullGeometry.copy(centerY = 5000)), 128))
    }

    @Test fun invalidNormalizedPointsCannotBecomeCameraCommands() {
        assertThrows(IllegalArgumentException::class.java) { fullGeometry.cameraPoint(NormalizedFocusPoint(-.1f, .5f)) }
        assertThrows(IllegalArgumentException::class.java) { fullGeometry.cameraPoint(NormalizedFocusPoint(.5f, 1.1f)) }
        assertThrows(IllegalArgumentException::class.java) { fullGeometry.cameraPoint(NormalizedFocusPoint(Float.NaN, .5f)) }
    }

    private fun assertPoint(x: Float, y: Float, point: NormalizedFocusPoint?) {
        assertNotNull(point)
        assertEquals(x, point!!.x, .00001f)
        assertEquals(y, point.y, .00001f)
    }

    private fun header(geometry: LiveViewFocusGeometry): ByteArray = ByteBuffer.allocate(128)
        .order(ByteOrder.BIG_ENDIAN).apply {
            position(8)
            listOf(geometry.jpegWidth, geometry.jpegHeight, geometry.wholeWidth, geometry.wholeHeight,
                geometry.displayWidth, geometry.displayHeight, geometry.centerX, geometry.centerY)
                .forEach { putShort(it.toShort()) }
        }.array()

    private val fullGeometry = LiveViewFocusGeometry(640, 424, 6048, 4000, 6048, 4000, 3024, 2000)
}

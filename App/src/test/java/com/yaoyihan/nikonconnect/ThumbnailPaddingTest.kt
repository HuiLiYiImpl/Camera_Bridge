package com.yaoyihan.nikonconnect

import org.junit.Assert.*
import org.junit.Test

class ThumbnailPaddingTest {
    private val black = 0xff080808.toInt()
    private val photo = 0xff809080.toInt()
    @Test fun portraitPaddingRestoresPhotoRatio() {
        assertEquals(ThumbnailRect(10, 0, 80, 120), thumbnailContentRect(100, 120, 6000, 4000) { x, _ ->
            if (x < 10 || x >= 90) black else photo
        })
    }
    @Test fun landscapePaddingRestoresPhotoRatio() {
        assertEquals(ThumbnailRect(0, 10, 120, 80), thumbnailContentRect(120, 100, 6000, 4000) { _, y ->
            if (y < 10 || y >= 90) black else photo
        })
    }
    @Test fun unpaddedPhotoIsPreserved() {
        assertNull(thumbnailContentRect(100, 120, 6000, 4000) { _, _ -> photo })
    }
    @Test fun realPhotoWithBlackEdgesAndCorrectRatioIsPreserved() {
        assertNull(thumbnailContentRect(80, 120, 6000, 4000) { x, _ -> if (x < 10 || x >= 70) black else photo })
    }
    @Test fun entirelyBlackPhotoIsPreserved() {
        assertNull(thumbnailContentRect(100, 120, 6000, 4000) { _, _ -> black })
    }
    @Test fun asymmetricDarkSceneIsPreserved() {
        assertNull(thumbnailContentRect(100, 120, 6000, 4000) { x, _ -> if (x < 5 || x >= 85) black else photo })
    }
    @Test fun unknownDimensionsDoNotAllowGuessing() {
        assertNull(thumbnailContentRect(100, 120, 0, 0) { x, _ -> if (x < 10 || x >= 90) black else photo })
    }
    @Test fun WrongAspectRatioDoesNotAllowCropping() {
        assertNull(thumbnailContentRect(100, 120, 4000, 4000) { x, _ -> if (x < 10 || x >= 90) black else photo })
    }
}

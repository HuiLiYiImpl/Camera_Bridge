package com.yaoyihan.nikonconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrientedBitmapTest {
    @Test
    fun mapsEveryExifOrientation() {
        assertNull(exifTransform(1))
        assertEquals(ExifTransform(0f, -1f, 1f), exifTransform(2))
        assertEquals(ExifTransform(180f), exifTransform(3))
        assertEquals(ExifTransform(180f, -1f, 1f), exifTransform(4))
        assertEquals(ExifTransform(90f, -1f, 1f), exifTransform(5))
        assertEquals(ExifTransform(90f), exifTransform(6))
        assertEquals(ExifTransform(-90f, -1f, 1f), exifTransform(7))
        assertEquals(ExifTransform(-90f), exifTransform(8))
        assertNull(exifTransform(0))
    }

    @Test
    fun usesOriginalOrientationWhenThumbnailHasNone() {
        assertEquals(6, resolveExifOrientation(null, 6))
        assertEquals(8, resolveExifOrientation(8, 6))
        assertEquals(1, resolveExifOrientation(null, null))
    }
}

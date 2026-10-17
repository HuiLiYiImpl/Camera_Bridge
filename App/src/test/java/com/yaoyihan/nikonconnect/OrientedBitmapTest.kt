package com.yaoyihan.nikonconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OrientedBitmapTest {
    private fun tiff(orientation: Int, order: ByteOrder = ByteOrder.LITTLE_ENDIAN): ByteArray =
        ByteBuffer.allocate(44).order(order).apply {
            put(if (order == ByteOrder.LITTLE_ENDIAN) byteArrayOf(0x49, 0x49) else byteArrayOf(0x4d, 0x4d))
            putShort(42); putInt(8)
            putShort(2)
            putShort(0x112); putShort(3); putInt(1); putShort(orientation.toShort()); putShort(0)
            putShort(0x14a); putShort(4); putInt(1); putInt(38)
            putInt(0)
            putShort(0); putInt(0)
        }.array()

    @Test fun readsPrimaryRawOrientationInBothByteOrders() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            for (orientation in 1..8) assertEquals(orientation, primaryTiffOrientation(tiff(orientation, order)))
        }
    }

    @Test fun rejectsTruncatedAndInvalidRawDirectories() {
        val valid = tiff(8)
        for (length in 0..33) assertNull(primaryTiffOrientation(valid.copyOf(length)))
        assertNull(primaryTiffOrientation(tiff(0)))
        assertNull(primaryTiffOrientation(tiff(9)))
        assertNull(primaryTiffOrientation(valid.copyOf().apply { for (i in 4..7) this[i] = 0xff.toByte() }))
        assertNull(primaryTiffOrientation(valid.copyOf().apply { this[8] = 0xff.toByte(); this[9] = 0x7f }))
        assertNull(primaryTiffOrientation(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0, 0, 0, 0)))
    }

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

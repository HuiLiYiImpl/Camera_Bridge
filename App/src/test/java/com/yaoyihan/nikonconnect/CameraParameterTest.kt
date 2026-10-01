package com.yaoyihan.nikonconnect

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CameraParameterTest {
    @Test fun shutterWheelOrdersExposureTimeRatherThanPackedIntegerValues() {
        val descriptor = CameraParameterDescriptor(0xD100, 6, true, 0x0001007d,
            listOf(0xffffffffL, 0x001e0001L, 0x0001007dL, 0x00010001L, 0x00011f40L, 0xfffffffdL))
        assertEquals(listOf(0x00011f40L, 0x0001007dL, 0x00010001L, 0x001e0001L, 0xffffffffL, 0xfffffffdL),
            RemoteParameter.SHUTTER.wheelOptions(descriptor))
    }

    @Test fun isoWheelKeepsAutoBeforeNumericSensitivity() {
        val descriptor = CameraParameterDescriptor(0x500F, 4, true, 400, listOf(800, 0xffff, 100, 400, 200))
        assertEquals(listOf(0xffffL, 100L, 200L, 400L, 800L), RemoteParameter.ISO.wheelOptions(descriptor))
    }

    @Test fun largeStepRangesAllowDirectAccessWithoutBuildingMillionsOfValues() {
        val descriptor = CameraParameterDescriptor(0x500F, 6, true, 100,
            minimum = 100, maximum = 2048000, step = 1)
        val options = descriptor.options()
        assertEquals(2047901, options.size)
        assertEquals(2048000L, options[options.lastIndex])
        assertEquals(2047900, options.indexOf(2048000L))
        assertEquals(-1, options.indexOf(99L))
        val signed = descriptor.copy(current = 0, minimum = -5000, maximum = 5000, step = 333).options()
        assertEquals(-4667L, signed[1])
        assertEquals(1, signed.indexOf(-4667L))
        assertEquals(-1, signed.indexOf(-4666L))
    }

    @Test fun signedExposureCompensationRangeKeepsNegativeValues() {
        val bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x5010).putShort(3).put(1).putShort(0).putShort(-333)
            .put(1).putShort(-5000).putShort(5000).putShort(333).array()
        val descriptor = CameraParameterDescriptor.parse(bytes)
        assertEquals(-333L, descriptor.current)
        assertEquals(-5000L, descriptor.minimum)
        assertTrue(descriptor.allows(-4667))
        assertFalse(descriptor.allows(-4666))
        assertEquals("-0.3 EV", RemoteParameter.COMPENSATION.display(0x5010, descriptor.current))
        assertArrayEquals(byteArrayOf(0xc5.toByte(), 0xed.toByte()), descriptor.encode(-4667))
    }

    @Test fun unsignedNikonShutterEnumerationPreservesSentinels() {
        val bytes = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0xD100.toShort()).putShort(6).put(1).putInt(0).putInt(0x0001007d)
            .put(2).putShort(3).putInt(0x0001007d).putInt(-1).putInt(-3).array()
        val descriptor = CameraParameterDescriptor.parse(bytes)
        assertEquals(listOf(65661L, 0xffffffffL, 0xfffffffdL), descriptor.values)
        assertEquals("1/125", RemoteParameter.SHUTTER.display(descriptor.code, descriptor.current))
        assertEquals("Bulb", RemoteParameter.SHUTTER.display(descriptor.code, 0xffffffffL))
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), descriptor.encode(0xffffffffL))
    }

    @Test fun rejectsTruncatedAndUnsupportedDescriptors() {
        assertThrows(Exception::class.java) { CameraParameterDescriptor.parse(byteArrayOf(1, 2, 3)) }
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(0x5007).putShort(0x4004).array()
        assertThrows(Exception::class.java) { CameraParameterDescriptor.parse(bytes) }
    }

    @Test fun rejectsWritesOutsideCameraChoicesOrToReadonlyProperties() {
        val descriptor = CameraParameterDescriptor(0x500F, 4, true, 100, listOf(100, 200, 400))
        assertThrows(IllegalArgumentException::class.java) { descriptor.encode(125) }
        assertThrows(IllegalArgumentException::class.java) { descriptor.copy(writable = false).encode(200) }
    }

    @Test fun exposureModesGateShutterAndAperture() {
        assertTrue(RemoteParameter.SHUTTER.modeAllows(1))
        assertTrue(RemoteParameter.SHUTTER.modeAllows(4))
        assertFalse(RemoteParameter.SHUTTER.modeAllows(3))
        assertFalse(RemoteParameter.SHUTTER.modeAllows(null))
        assertTrue(RemoteParameter.APERTURE.modeAllows(1))
        assertTrue(RemoteParameter.APERTURE.modeAllows(3))
        assertFalse(RemoteParameter.APERTURE.modeAllows(2))
    }

    @Test fun sendsHostToCameraPhaseAndMatchingTransactionInAllPackets() {
        val packets = ptpParameterWritePackets(42u, 0x5010, byteArrayOf(0xb2.toByte(), 0xfe.toByte()))
        fun buffer(index: Int) = ByteBuffer.wrap(packets[index]).order(ByteOrder.LITTLE_ENDIAN)
        val operation = buffer(0)
        assertEquals(22, operation.int); assertEquals(6, operation.int)
        assertEquals(2, operation.int); assertEquals(0x1016, operation.short.toInt())
        assertEquals(42, operation.int); assertEquals(0x5010, operation.int)
        val start = buffer(1)
        assertEquals(20, start.int); assertEquals(9, start.int)
        assertEquals(42, start.int); assertEquals(2L, start.long)
        val end = buffer(2)
        assertEquals(14, end.int); assertEquals(12, end.int)
        assertEquals(42, end.int); assertEquals(-334, end.short.toInt())
    }
}

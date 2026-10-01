package com.yaoyihan.nikonconnect

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class RemoteColorTest {
    private fun lut(name: String) = CubeLut(name, 2, FloatArray(24), floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))

    @Test fun pictureControlChoicesAndWriteEncodingComeFromCamera() {
        val bytes = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0xD200.toShort()).putShort(4).put(1).putShort(1).putShort(8)
            .put(2).putShort(3).putShort(1).putShort(8).putShort(101).array()
        val descriptor = CameraParameterDescriptor.parse(bytes)
        assertEquals(listOf(1L, 8L, 101L), RemoteParameter.PICTURE_CONTROL.wheelOptions(descriptor))
        assertEquals("自动", RemoteParameter.PICTURE_CONTROL.display(descriptor.code, descriptor.current))
        assertArrayEquals(byteArrayOf(101, 0), descriptor.encode(101))
        assertThrows(IllegalArgumentException::class.java) { descriptor.encode(4) }
        assertThrows(IllegalArgumentException::class.java) { descriptor.copy(writable = false).encode(101) }
        assertEquals(0xD200, RemoteParameter.PICTURE_CONTROL.codes.single())
        val packets = ptpParameterWritePackets(9u, descriptor.code, descriptor.encode(101))
        assertEquals(0xD200, ByteBuffer.wrap(packets[0]).order(ByteOrder.LITTLE_ENDIAN).getInt(18))
        assertArrayEquals(byteArrayOf(101, 0), packets.last().copyOfRange(12, 14))
    }

    @Test fun pictureControlNamesDoNotMislabelUnknownFirmwareValues() {
        assertEquals("深色调单色", NikonPictureControls.label(10))
        assertEquals("自定义 C9", NikonPictureControls.label(209))
        assertTrue(NikonPictureControls.label(300).contains("0x12C"))
    }

    @Test fun lateLoadCannotRestorePresetAfterReturningToOriginal() = runBlocking {
        val finish = CompletableDeferred<Unit>()
        val controller = RemotePreviewColorController(this, true) { entry ->
            withContext(NonCancellable) { finish.await(); lut(entry.name) }
        }
        controller.select(LutEntry("slow", "slow")); yield()
        controller.select(null)
        finish.complete(Unit); yield()
        assertNull(controller.state.value.entry)
        assertNull(controller.state.value.lut)
        assertFalse(controller.state.value.loading)
    }

    @Test fun newestPresetWinsEvenIfAnEarlierReadFinishesLater() = runBlocking {
        val finish = CompletableDeferred<Unit>()
        val controller = RemotePreviewColorController(this, true) { entry ->
            if (entry.id == "old") withContext(NonCancellable) { finish.await() }
            lut(entry.name)
        }
        controller.select(LutEntry("old", "old")); yield()
        controller.select(LutEntry("new", "new")); yield()
        finish.complete(Unit); yield()
        assertEquals("new", controller.state.value.entry?.id)
        assertEquals("new", controller.state.value.lut?.name)
    }

    @Test fun unsupportedGpuDoesNotLoadOrPretendToApplyAPreset() = runBlocking {
        var reads = 0
        val controller = RemotePreviewColorController(this, false) { reads++; lut(it.name) }
        controller.select(LutEntry("preset", "preset")); yield()
        assertEquals(0, reads)
        assertNull(controller.state.value.lut)
        assertNull(controller.state.value.entry)
        assertNotNull(controller.state.value.message)
    }

    @Test fun failedLoadFallsBackToOriginalWhileIntensityIsLocalAndBounded() = runBlocking {
        val controller = RemotePreviewColorController(this, true) { error("missing file") }
        controller.setIntensity(.4f)
        controller.select(LutEntry("broken", "broken")); yield()
        assertNull(controller.state.value.lut)
        assertNull(controller.state.value.entry)
        assertNotNull(controller.state.value.message)
        assertEquals(.4f, controller.state.value.intensity, 0f)
        controller.setIntensity(Float.NaN)
        assertEquals(.4f, controller.state.value.intensity, 0f)
        controller.setIntensity(2f)
        assertEquals(1f, controller.state.value.intensity, 0f)
        controller.setIntensity(-1f)
        assertEquals(0f, controller.state.value.intensity, 0f)
    }
}

package info.skyblond.nsp.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 10-byte TIME layout from nsg/esp32/lib/nikon-protocol/TimeMessage.cpp. */
object TimePayloadGenerator {
    fun build(now: Instant = Instant.now(), localZone: ZoneId = ZoneId.systemDefault()): ByteArray {
        val utc = now.atZone(ZoneOffset.UTC)
        val offsetMinutes = localZone.rules.getOffset(now).totalSeconds / 60
        return ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(utc.year.toShort())
            put(utc.monthValue.toByte())
            put(utc.dayOfMonth.toByte())
            put(utc.hour.toByte())
            put(utc.minute.toByte())
            put(utc.second.toByte())
            put(0) // DST offset: nsg's ESP32 implementation sends zero.
            put((offsetMinutes / 60).toByte())
            put((offsetMinutes % 60).toByte())
        }.array()
    }
}

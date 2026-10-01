package info.skyblond.nsp.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZonedDateTime
import kotlin.math.absoluteValue

/**
 * Encodes a real location fix in Nikon's 41-byte GEO characteristic payload.
 */
object GeoPayloadGenerator {

    private const val HEADER: Short = 0x007F

    /**
     * Builds a 41-byte GEO payload from a real location fix.
     */
    fun build(latitude: Double, longitude: Double, altitude: Double, timestamp: ZonedDateTime): ByteArray {
        val latCoord = decimalToNikon(latitude, 'N', 'S')
        val lonCoord = decimalToNikon(longitude, 'E', 'W')

        val buffer = ByteBuffer.allocate(41).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(HEADER)
        buffer.put(latCoord.direction.code.toByte())
        buffer.put(latCoord.degrees.toByte())
        buffer.put(latCoord.minutes.toByte())
        buffer.put(latCoord.submin1.toByte())
        buffer.put(latCoord.submin2.toByte())
        buffer.put(lonCoord.direction.code.toByte())
        buffer.put(lonCoord.degrees.toByte())
        buffer.put(lonCoord.minutes.toByte())
        buffer.put(lonCoord.submin1.toByte())
        buffer.put(lonCoord.submin2.toByte())
        buffer.put(4.toByte()) // satellites (not available from Location API; fixed plausible value)
        buffer.put((if (altitude < 0) 'M' else 'P').code.toByte())
        buffer.putShort(altitude.absoluteValue.toInt().coerceAtMost(65535).toShort())
        // Embedded 7-byte time: year LE, month, day, hour, minute, second
        buffer.putShort(timestamp.year.toShort())
        buffer.put(timestamp.monthValue.toByte())
        buffer.put(timestamp.dayOfMonth.toByte())
        buffer.put(timestamp.hour.toByte())
        buffer.put(timestamp.minute.toByte())
        buffer.put(timestamp.second.toByte())
        buffer.put(0.toByte()) // subseconds
        buffer.put(0x01.toByte()) // valid
        buffer.put("WGS-84".toByteArray(Charsets.US_ASCII))
        // 10 bytes padding
        repeat(10) { buffer.put(0x00.toByte()) }

        return buffer.array()
    }
    private data class DirectionalCoordinate(
        val direction: Char,
        val degrees: Int,
        val minutes: Int,
        val submin1: Int,
        val submin2: Int
    )

    private fun decimalToNikon(
        decimal: Double,
        positiveDirection: Char,
        negativeDirection: Char
    ): DirectionalCoordinate {
        val direction = if (decimal < 0) negativeDirection else positiveDirection
        val abs = decimal.absoluteValue
        val degrees = abs.toInt()
        val minutesFull = (abs - degrees) * 60.0
        val minutes = minutesFull.toInt()
        val submin1Full = (minutesFull - minutes) * 100.0
        val submin1 = submin1Full.toInt()
        val submin2Full = (submin1Full - submin1) * 100.0
        val submin2 = submin2Full.toInt()
        return DirectionalCoordinate(
            direction = direction,
            degrees = degrees,
            minutes = minutes,
            submin1 = submin1,
            submin2 = submin2
        )
    }
}

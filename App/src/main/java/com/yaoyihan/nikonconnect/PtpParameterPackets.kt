package com.yaoyihan.nikonconnect

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PTP/IP host-to-camera data phase is 2 (not the receive/no-data phase 1). */
internal fun ptpParameterWritePackets(transaction: UInt, code: Int, value: ByteArray): List<ByteArray> {
    require(value.size in listOf(1, 2, 4))
    fun packet(type: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(payload.size + 8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(payload.size + 8).putInt(type).put(payload).array()
    val operation = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(2).putShort(0x1016.toShort()).putInt(transaction.toInt()).putInt(code).array()
    val start = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(transaction.toInt()).putLong(value.size.toLong()).array()
    val end = ByteBuffer.allocate(4 + value.size).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(transaction.toInt()).put(value).array()
    return listOf(packet(6, operation), packet(9, start), packet(12, end))
}

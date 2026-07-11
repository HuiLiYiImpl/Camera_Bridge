package com.yaoyihan.nikonconnect

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.SocketFactory

/** Small, dependency-free PTP/IP client for Nikon cameras. */
@OptIn(ExperimentalUnsignedTypes::class)
class PtpIpClient(
    private val host: String,
    private val port: Int,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
) : AutoCloseable {
    private lateinit var command: Channel
    private lateinit var event: Channel
    private var transaction = 1u
    private var objectHandles: List<UInt>? = null
    var cameraName: String = "Nikon 相机"; private set

    fun connect() {
        command = Channel(host, port, socketFactory).also { it.open() }
        // Nikon's Android client identifies its PTP/IP session with this stable value.
        command.send(packet(INIT_COMMAND_REQUEST, NIKON_ANDROID_GUID + utf16z("Android Device") + le32(PROTOCOL_VERSION)))
        val ack = command.read()
        require(ack.type == INIT_COMMAND_ACK) { "相机拒绝了命令通道连接" }
        val ackReader = Reader(ack.payload)
        val connectionNumber = ackReader.u32()
        ackReader.bytes(16)
        cameraName = ackReader.utf16z().ifBlank { "Nikon 相机" }
        val version = ackReader.u32()
        require(version shr 16 == 1u) { "不支持的 PTP/IP 协议版本" }

        event = Channel(host, port, socketFactory).also { it.open() }
        event.send(packet(INIT_EVENT_REQUEST, le32(connectionNumber)))
        require(event.read().type == INIT_EVENT_ACK) { "相机拒绝了事件通道连接" }
        val device = requestData(GET_DEVICE_INFO, 0u)
        cameraName = parseDeviceName(device).ifBlank { cameraName }
        requestResponse(OPEN_SESSION, 0u, 1u)
        transaction = 1u
    }

    fun assets(offset: Int = 0, limit: Int = 250): List<PhotoAsset> {
        // Zf rejects GetStorageIDs over its SnapBridge PTP session; Nikon's client
        // queries all objects directly with the PTP “all storages” sentinel instead.
        val handles = objectHandles ?: Reader(requestData(GET_OBJECT_HANDLES, next(), UInt.MAX_VALUE, 0u, 0u)).u32Array().asReversed().also { objectHandles = it }
        val page = handles.drop(offset).take(limit)
        return page.mapNotNull { handle -> runCatching { parseObject(handle, requestData(GET_OBJECT_INFO, next(), handle)) }.getOrNull() }
    }

    fun hasMoreAssets(offset: Int): Boolean = offset < (objectHandles?.size ?: Int.MAX_VALUE)
    fun refreshAssets() { objectHandles = null }

    fun thumbnail(asset: PhotoAsset): ByteArray? = runCatching { requestData(GET_THUMB, next(), asset.handle) }.getOrNull()
    fun download(asset: PhotoAsset, progress: (Long, Long) -> Unit): ByteArray {
        val output = java.io.ByteArrayOutputStream(asset.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        downloadTo(asset, output, progress)
        return output.toByteArray()
    }

    fun downloadTo(asset: PhotoAsset, output: OutputStream, progress: (Long, Long) -> Unit): Long {
        var offset = 0L
        while (offset < asset.size) {
            val count = minOf(PARTIAL_OBJECT_CHUNK, asset.size - offset).toInt()
            val data = requestData(GET_PARTIAL_OBJECT, next(), asset.handle, offset.toUInt(), count.toUInt())
            require(data.isNotEmpty()) { "相机未返回下载数据" }
            output.write(data)
            offset += data.size
            progress(offset, asset.size)
        }
        return offset
    }

    private fun parseObject(handle: UInt, data: ByteArray): PhotoAsset {
        val r = Reader(data)
        r.u32(); val format = r.u16(); r.u16(); val size = r.u32().toLong()
        r.u16(); r.u32(); r.u32(); r.u32(); r.u32(); r.u32(); r.u32(); r.u32(); r.u16(); r.u32(); r.u32()
        val name = r.ptpString().ifBlank { "IMG_$handle" }
        val date = parsePtpDate(r.ptpString())
        return PhotoAsset(handle, name, size, format, date)
    }

    private fun parseDeviceName(data: ByteArray): String = runCatching {
        val r = Reader(data); r.u16(); r.u32(); r.u16(); r.ptpString(); r.u16Array(); r.u16Array(); r.u16Array(); r.u16Array(); r.u16Array(); r.u16Array()
        val manufacturer = r.ptpString(); val model = r.ptpString(); "$manufacturer $model".trim()
    }.getOrDefault("")

    private fun requestResponse(operation: Int, id: UInt, vararg parameters: UInt) {
        command.send(operationPacket(operation, id, parameters)); val response = command.read()
        require(response.type == OPERATION_RESPONSE && Reader(response.payload).u16() == RESPONSE_OK) { "相机未接受请求（0x${operation.toString(16)}）" }
    }

    @Synchronized private fun requestData(operation: Int, id: UInt, vararg parameters: UInt): ByteArray = requestData(operation, id, null, *parameters)
    @Synchronized private fun requestData(operation: Int, id: UInt, progress: ((Long, Long) -> Unit)?, vararg parameters: UInt): ByteArray {
        command.send(operationPacket(operation, id, parameters)); val output = ArrayList<Byte>(); var expected = 0L
        while (true) {
            val packet = command.read()
            when (packet.type) {
                START_DATA -> { val r = Reader(packet.payload); r.u32(); expected = r.u64().toLong(); progress?.invoke(0, expected) }
                DATA, END_DATA -> { val r = Reader(packet.payload); r.u32(); val part = r.remaining(); output.addAll(part.toList()); progress?.invoke(output.size.toLong(), expected) }
                OPERATION_RESPONSE -> { val r = Reader(packet.payload); require(r.u16() == RESPONSE_OK) { "相机返回错误" }; return output.toByteArray() }
                else -> error("意外的 PTP/IP 数据包：${packet.type}")
            }
        }
    }

    @Synchronized private fun requestToStream(operation: Int, id: UInt, output: OutputStream, progress: (Long, Long) -> Unit, vararg parameters: UInt): Long {
        command.send(operationPacket(operation, id, parameters)); var written = 0L; var expected = 0L
        while (true) {
            val packet = command.read()
            when (packet.type) {
                START_DATA -> { val r = Reader(packet.payload); r.u32(); expected = r.u64().toLong(); progress(0, expected) }
                DATA, END_DATA -> { val r = Reader(packet.payload); r.u32(); val part = r.remaining(); output.write(part); written += part.size; progress(written, expected) }
                OPERATION_RESPONSE -> { val r = Reader(packet.payload); require(r.u16() == RESPONSE_OK) { "\u76f8\u673a\u8fd4\u56de\u4e86\u9519\u8bef" }; return written }
                else -> error("\u6536\u5230\u610f\u5916\u7684 PTP/IP \u6570\u636e\u5305\uff1a${packet.type}")
            }
        }
    }

    private fun next() = transaction++
    override fun close() { runCatching { if (::command.isInitialized) requestResponse(CLOSE_SESSION, next()) }; runCatching { command.close() }; runCatching { event.close() } }

    private class Channel(
        private val host: String,
        private val port: Int,
        private val socketFactory: SocketFactory,
    ) : AutoCloseable {
        private lateinit var socket: Socket; private lateinit var input: BufferedInputStream; private lateinit var output: BufferedOutputStream
        fun open() { socket = socketFactory.createSocket(host, port).apply { soTimeout = 15_000; tcpNoDelay = true; keepAlive = true }; input = BufferedInputStream(socket.getInputStream()); output = BufferedOutputStream(socket.getOutputStream()) }
        fun send(bytes: ByteArray) { output.write(bytes); output.flush() }
        fun read(): RawPacket { val header = input.readFully(8); val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN); val length = b.int; require(length >= 8) { "无效 PTP/IP 包长度" }; return RawPacket(b.int, input.readFully(length - 8)) }
        override fun close() { if (::socket.isInitialized) socket.close() }
    }
    private data class RawPacket(val type: Int, val payload: ByteArray)
    private class Reader(private val data: ByteArray) {
        private var at = 0
        fun u16() = ByteBuffer.wrap(bytes(2)).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        fun u32() = ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()
        fun u64() = ByteBuffer.wrap(bytes(8)).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
        fun bytes(n: Int) = data.copyOfRange(at, (at + n).also { require(it <= data.size) { "相机返回了截断的数据" }; at = it })
        fun remaining() = bytes(data.size - at)
        fun u16Array(): List<Int> = List(u32().toInt()) { u16() }
        fun u32Array(): List<UInt> = List(u32().toInt()) { u32() }
        fun ptpString(): String { val count = data.getOrNull(at)?.toInt()?.and(0xff) ?: return ""; at++; if (count == 0) return ""; return String(bytes(count * 2 - 2), Charsets.UTF_16LE).also { at += 2 } }
        fun utf16z(): String { val start = at; while (at + 1 < data.size && (data[at].toInt() != 0 || data[at + 1].toInt() != 0)) at += 2; val value = String(data, start, at - start, Charsets.UTF_16LE); at += 2; return value }
    }
    companion object {
        private const val PROTOCOL_VERSION = 0x00010000u
        private const val INIT_COMMAND_REQUEST = 1; private const val INIT_COMMAND_ACK = 2; private const val INIT_EVENT_REQUEST = 3; private const val INIT_EVENT_ACK = 4
        private const val OPERATION_REQUEST = 6; private const val OPERATION_RESPONSE = 7; private const val START_DATA = 9; private const val DATA = 10; private const val END_DATA = 12
        private const val GET_DEVICE_INFO = 0x1001; private const val OPEN_SESSION = 0x1002; private const val CLOSE_SESSION = 0x1003; private const val GET_STORAGE_IDS = 0x1004; private const val GET_OBJECT_HANDLES = 0x1007; private const val GET_OBJECT_INFO = 0x1008; private const val GET_OBJECT = 0x1009; private const val GET_THUMB = 0x100A; private const val GET_PARTIAL_OBJECT = 0x101B
        private const val RESPONSE_OK = 0x2001
        private const val PARTIAL_OBJECT_CHUNK = 1_048_576L
        private val NIKON_ANDROID_GUID = byteArrayOf(
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(),
            0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
        )
        private fun ByteArray.toList() = asList()
        private fun java.io.InputStream.readFully(size: Int): ByteArray { val out = ByteArray(size); var offset = 0; while (offset < size) { val read = read(out, offset, size - offset); if (read < 0) error("相机已断开连接"); offset += read }; return out }
        private fun le32(value: UInt) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()
        private fun utf16z(value: String) = (value + '\u0000').toByteArray(Charsets.UTF_16LE)
        private fun packet(type: Int, payload: ByteArray) = le32((payload.size + 8).toUInt()) + le32(type.toUInt()) + payload
        private fun operationPacket(operation: Int, id: UInt, parameters: UIntArray) = packet(OPERATION_REQUEST, le32(1u) + ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(operation.toShort()).array() + le32(id) + parameters.take(5).fold(ByteArray(0)) { all, value -> all + le32(value) })
        private fun parsePtpDate(value: String): Date = runCatching { SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).parse(value.take(15)) ?: Date() }.getOrDefault(Date())
    }
}

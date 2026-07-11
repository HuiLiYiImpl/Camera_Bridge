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

    /**
     * 下载资源到 [output]，使用动态 chunk 拥塞控制。
     * 小文件（≤ [FULL_OBJECT_THRESHOLD]）优先整文件下载，失败回退到分块；
     * 大文件用 [AdaptiveChunkController] 自适应调整 chunk 大小（1MB~8MB）。
     */
    fun downloadTo(asset: PhotoAsset, output: OutputStream, progress: (Long, Long) -> Unit): Long {
        // 小文件：尝试一次性 GET_OBJECT 整文件下载，省去分块往返
        if (asset.size in 1..FULL_OBJECT_THRESHOLD) {
            runCatching { requestToStream(GET_OBJECT, next(), asset.handle, output, progress, asset.size, 0L) }
                .onSuccess { return it }
            // 整文件失败则回退到分块下载（offset 从 0 开始）
            reconnect()
        }
        return downloadInChunks(asset, output, progress)
    }

    /** 分块下载：动态 chunk + 失败重试 + 自动重连。 */
    private fun downloadInChunks(asset: PhotoAsset, output: OutputStream, progress: (Long, Long) -> Unit): Long {
        val controller = AdaptiveChunkController(DEFAULT_CHUNK_SIZE, MIN_CHUNK_SIZE, MAX_CHUNK_SIZE)
        var offset = 0L
        var retries = 0
        while (offset < asset.size) {
            val count = controller.requestLength(asset.size - offset)
            try {
                requestToStream(GET_PARTIAL_OBJECT, next(), asset.handle, offset.toUInt(), count.toUInt(), output, progress, asset.size, offset)
                offset += count
                controller.registerSuccess()
                retries = 0
            } catch (e: Exception) {
                if (++retries > MAX_CHUNK_RETRIES) throw e
                controller.registerFailure()
                if (isDeviceBusy(e)) { Thread.sleep(retries * DEVICE_BUSY_BACKOFF_MS) }
                else { reconnect() }
            }
        }
        return offset
    }

    /** 重连 PTP/IP 会话（用于超时/断连后恢复）。 */
    private fun reconnect() {
        runCatching { command.close() }; runCatching { event.close() }
        connect()
    }

    /** deviceBusy / 相机正忙等可重试错误判断。 */
    private fun isDeviceBusy(e: Throwable): Boolean {
        val message = e.message ?: return false
        return message.contains("device busy", ignoreCase = true) || message.contains("busy", ignoreCase = true) || message.contains("0x2019", ignoreCase = true)
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
        command.send(operationPacket(operation, id, parameters)); val output = java.io.ByteArrayOutputStream(); var expected = 0L
        while (true) {
            val packet = command.read()
            when (packet.type) {
                START_DATA -> { val r = Reader(packet.payload); r.u32(); expected = r.u64().toLong(); progress?.invoke(0, expected) }
                DATA, END_DATA -> { val r = Reader(packet.payload); r.u32(); val part = r.remaining(); output.write(part); progress?.invoke(output.size().toLong(), expected) }
                OPERATION_RESPONSE -> { val r = Reader(packet.payload); require(r.u16() == RESPONSE_OK) { "相机返回错误" }; return output.toByteArray() }
                else -> error("意外的 PTP/IP 数据包：${packet.type}")
            }
        }
    }

    /** 流式下载：数据包直接写入 [output]，不经过 ArrayList 装箱，避免 GC 压力。 */
    @Synchronized private fun requestToStream(operation: Int, id: UInt, output: OutputStream, progress: (Long, Long) -> Unit, total: Long, baseOffset: Long, vararg parameters: UInt): Long {
        command.send(operationPacket(operation, id, parameters)); var written = 0L; var expected = 0L
        while (true) {
            val packet = command.read()
            when (packet.type) {
                START_DATA -> { val r = Reader(packet.payload); r.u32(); expected = r.u64().toLong(); progress(baseOffset, total) }
                DATA, END_DATA -> { val r = Reader(packet.payload); r.u32(); val part = r.remaining(); output.write(part); written += part.size; progress(baseOffset + written, total) }
                OPERATION_RESPONSE -> { val r = Reader(packet.payload); require(r.u16() == RESPONSE_OK) { "相机返回了错误" }; return written }
                else -> error("收到意外的 PTP/IP 数据包：${packet.type}")
            }
        }
    }

    /** GET_OBJECT 整文件下载（无 offset/count 参数，只有 handle）。 */
    private fun requestToStream(operation: Int, id: UInt, handle: UInt, output: OutputStream, progress: (Long, Long) -> Unit, total: Long, baseOffset: Long): Long =
        requestToStream(operation, id, output, progress, total, baseOffset, handle)

    /** GET_PARTIAL_OBJECT 分块下载（handle + offset + count）。 */
    private fun requestToStream(operation: Int, id: UInt, handle: UInt, offset: UInt, count: UInt, output: OutputStream, progress: (Long, Long) -> Unit, total: Long, baseOffset: Long): Long =
        requestToStream(operation, id, output, progress, total, baseOffset, handle, offset, count)

    private fun next() = transaction++
    override fun close() { runCatching { if (::command.isInitialized) requestResponse(CLOSE_SESSION, next()) }; runCatching { command.close() }; runCatching { event.close() } }

    private class Channel(
        private val host: String,
        private val port: Int,
        private val socketFactory: SocketFactory,
    ) : AutoCloseable {
        private lateinit var socket: Socket; private lateinit var input: BufferedInputStream; private lateinit var output: BufferedOutputStream
        fun open() {
            socket = socketFactory.createSocket(host, port).apply {
                soTimeout = SOCKET_TIMEOUT_MS; tcpNoDelay = true; keepAlive = true
                runCatching { receiveBufferSize = SOCKET_BUFFER_SIZE }; runCatching { sendBufferSize = SOCKET_BUFFER_SIZE }
            }
            input = BufferedInputStream(socket.getInputStream(), STREAM_BUFFER_SIZE)
            output = BufferedOutputStream(socket.getOutputStream(), STREAM_BUFFER_SIZE)
        }
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

    /**
     * 动态 chunk 拥塞控制器（类 TCP AIMD）：
     * 连续 [STABLE_THRESHOLD] 次成功后 chunk 翻倍（上限 [maximum]）；失败后减半（下限 [minimum]）。
     */
    private class AdaptiveChunkController(initial: Int, private val minimum: Int, private val maximum: Int) {
        private var currentChunkSize: Int = initial.coerceIn(minimum, maximum)
        private var consecutiveStable = 0

        fun requestLength(remaining: Long): Int {
            if (remaining >= currentChunkSize) return currentChunkSize
            return remaining.toInt().coerceAtLeast(1)
        }

        fun registerSuccess() {
            consecutiveStable++
            if (consecutiveStable >= STABLE_THRESHOLD && currentChunkSize < maximum) {
                consecutiveStable = 0
                currentChunkSize = minOf(currentChunkSize * 2, maximum)
            }
        }

        fun registerFailure() {
            consecutiveStable = 0
            val reduced = maxOf(currentChunkSize / 2, minimum)
            currentChunkSize = reduced
        }
    }

    companion object {
        private const val PROTOCOL_VERSION = 0x00010000u
        private const val INIT_COMMAND_REQUEST = 1; private const val INIT_COMMAND_ACK = 2; private const val INIT_EVENT_REQUEST = 3; private const val INIT_EVENT_ACK = 4
        private const val OPERATION_REQUEST = 6; private const val OPERATION_RESPONSE = 7; private const val START_DATA = 9; private const val DATA = 10; private const val END_DATA = 12
        private const val GET_DEVICE_INFO = 0x1001; private const val OPEN_SESSION = 0x1002; private const val CLOSE_SESSION = 0x1003; private const val GET_STORAGE_IDS = 0x1004; private const val GET_OBJECT_HANDLES = 0x1007; private const val GET_OBJECT_INFO = 0x1008; private const val GET_OBJECT = 0x1009; private const val GET_THUMB = 0x100A; private const val GET_PARTIAL_OBJECT = 0x101B
        private const val RESPONSE_OK = 0x2001

        // —— 动态 chunk 拥塞控制参数 ——
        private const val MIN_CHUNK_SIZE = 1_048_576          // 1 MiB 下限
        private const val DEFAULT_CHUNK_SIZE = 4_194_304      // 4 MiB 起始
        private const val MAX_CHUNK_SIZE = 8_388_608          // 8 MiB 上限
        private const val FULL_OBJECT_THRESHOLD = 16_777_216L // 16 MiB 以下整文件下载
        private const val STABLE_THRESHOLD = 2                // 连续成功次数后翻倍
        private const val MAX_CHUNK_RETRIES = 5               // 单 chunk 最大重试
        private const val DEVICE_BUSY_BACKOFF_MS = 50L        // deviceBusy 退避基数

        // —— Socket / 缓冲区 ——
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val SOCKET_BUFFER_SIZE = 256 * 1024     // 256 KB socket 收发缓冲
        private const val STREAM_BUFFER_SIZE = 64 * 1024      // 64 KB 流缓冲

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

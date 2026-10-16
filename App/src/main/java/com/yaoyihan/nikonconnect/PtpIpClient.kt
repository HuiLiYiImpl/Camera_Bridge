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
    private val onProtocolEvent: (String) -> Unit = {},
) : CameraClient, RemoteCameraControl {
    private lateinit var command: Channel
    private lateinit var event: Channel
    private var transaction = 1u
    private var objectHandles: List<UInt>? = null
    private val assetCache = mutableListOf<PhotoAsset>()
    private var scannedHandles = 0
    private var storageEnumerationUnsupported = false
    private var supportedOperations = emptySet<Int>()
    private var supportedProperties = emptySet<Int>()
    private val unreadableProperties = mutableSetOf<Int>()
    private var vendorPropertiesLoaded = false
    private var liveViewActive = false
    private var liveViewOwned = false
    private var touchFocusConfirmed = false
    private var cameraModeChanged = false
    private var applicationModeChanged = false
    private var captureEvents = java.util.concurrent.ConcurrentLinkedQueue<CaptureEvent>()
    private val unsupportedEventOperations = mutableSetOf<Int>()
    override val supportsRemoteCapture: Boolean
        @Synchronized get() = NIKON_CAPTURE_TO_CARD in supportedOperations || INITIATE_CAPTURE in supportedOperations
    override val supportsLiveView: Boolean
        @Synchronized get() = listOf(NIKON_START_LIVE_VIEW, NIKON_END_LIVE_VIEW, NIKON_GET_LIVE_VIEW).all { it in supportedOperations }
    override val supportsTouchFocus: Boolean
        @Synchronized get() = listOf(NIKON_CHANGE_AF_AREA, NIKON_AF_DRIVE, NIKON_DEVICE_READY).all { it in supportedOperations }
    override var cameraName: String = "Nikon 相机"; private set
    override var cameraDetails: CameraDetails = CameraDetails(manufacturer = "Nikon"); private set
    override val transport = ConnectionTransport.WIFI

    @Synchronized override fun connect() {
        cameraModeChanged = false
        applicationModeChanged = false
        liveViewActive = false
        liveViewOwned = false
        touchFocusConfirmed = false
        unreadableProperties.clear()
        vendorPropertiesLoaded = false
        storageEnumerationUnsupported = false
        captureEvents = java.util.concurrent.ConcurrentLinkedQueue()
        unsupportedEventOperations.clear()
        command = Channel(host, port, socketFactory).also { it.open() }
        // Nikon's Android client identifies its PTP/IP session with this stable value.
        command.send(packet(INIT_COMMAND_REQUEST, NIKON_ANDROID_GUID + utf16z("Android Device") + le32(PROTOCOL_VERSION)))
        val ack = command.read()
        require(ack.type == INIT_COMMAND_ACK) { "相机拒绝了命令通道连接" }
        val ackReader = Reader(ack.payload)
        val connectionNumber = ackReader.u32()
        ackReader.bytes(16)
        val connectionName = ackReader.utf16z().ifBlank { "Nikon 相机" }
        val version = ackReader.u32()
        require(version shr 16 == 1u) { "不支持的 PTP/IP 协议版本" }

        event = Channel(host, port, socketFactory).also { it.open() }
        event.send(packet(INIT_EVENT_REQUEST, le32(connectionNumber)))
        require(event.read().type == INIT_EVENT_ACK) { "相机拒绝了事件通道连接" }
        val device = parseDeviceInfo(requestData(GET_DEVICE_INFO, 0u))
        supportedOperations = device.supportedOperations
        supportedProperties = device.supportedProperties
        cameraDetails = device.details
        cameraName = cameraDisplayName(cameraDetails, connectionName)
        requestResponse(OPEN_SESSION, 0u, 1u)
        transaction = 1u
        cameraDetails = readLiveCameraDetails(cameraDetails, device.supportedProperties)
        startEventReader(event)
    }

    @Synchronized override fun assets(offset: Int, limit: Int): List<PhotoAsset> {
        require(offset >= 0 && limit in 1..250)
        val handles = objectHandles ?: readAllObjectHandles().asReversed().also {
            objectHandles = it; assetCache.clear(); scannedHandles = 0
        }
        while (assetCache.size < offset + limit && scannedHandles < handles.size) {
            val handle = handles[scannedHandles]
            val asset = try { parseObject(handle, requestData(GET_OBJECT_INFO, next(), handle)) }
            catch (error: PtpResponseException) {
                if (error.response != 0x2009) throw error // Only skip an explicitly invalid object handle.
                null
            }
            scannedHandles++
            if (asset != null && asset.format != 0x3001) assetCache.add(asset)
        }
        return assetCache.drop(offset).take(limit)
    }

    @Synchronized override fun hasMoreAssets(offset: Int): Boolean = assetCache.size > offset || objectHandles?.let { scannedHandles < it.size } != false
    @Synchronized override fun refreshAssets() { objectHandles = null; assetCache.clear(); scannedHandles = 0 }

    private fun readAllObjectHandles(): List<UInt> {
        fun handles(storage: UInt) = Reader(requestData(GET_OBJECT_HANDLES, next(), storage, 0u, 0u)).u32Array()
        // Enumerate each slot instead of relying solely on the all-storages sentinel.
        // Zf/SnapBridge can reject GetStorageIDs; keep its verified all-storages fallback.
        if (GET_STORAGE_IDS !in supportedOperations || storageEnumerationUnsupported) return handles(UInt.MAX_VALUE).distinct()
        val storages = try { Reader(requestData(GET_STORAGE_IDS, next())).u32Array().distinct() }
        catch (error: PtpResponseException) {
            if (error.response in listOf(0x2003, 0x2019)) throw error
            storageEnumerationUnsupported = true
            return handles(UInt.MAX_VALUE).distinct()
        }
        if (storages.isEmpty()) return handles(UInt.MAX_VALUE).distinct()
        return storages.flatMap { storage ->
            try { handles(storage) }
            catch (error: PtpResponseException) {
                // Empty/removed slots must not hide the other card; transport errors still propagate.
                if (error.response !in listOf(0x2008, 0x2013)) throw error
                emptyList()
            }
        }.distinct()
    }

    @Synchronized override fun checkConnection(): Boolean {
        requestData(GET_DEVICE_INFO, next())
        return true
    }

    @Synchronized override fun thumbnail(asset: PhotoAsset): ByteArray? = runCatching {
        if (NIKON_GET_LARGE_THUMB in supportedOperations) {
            val large = try { requestData(NIKON_GET_LARGE_THUMB, next(), asset.handle) }
            catch (error: PtpResponseException) {
                // Only an explicit response leaves the connection safe for another request.
                if (error.response !in listOf(0x2005, 0x200A, 0x2010, 0x201D)) throw error
                null
            }
            if (large != null && large.isNotEmpty()) {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(large, 0, large.size, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) return@runCatching large
            }
        }
        requestData(GET_THUMB, next(), asset.handle)
    }.getOrNull()
    @Synchronized override fun imageHeader(asset: PhotoAsset): ByteArray? = runCatching {
        val length = minOf(asset.size.takeIf { it > 0 } ?: IMAGE_HEADER_SIZE.toLong(), IMAGE_HEADER_SIZE.toLong()).toUInt()
        requestData(GET_PARTIAL_OBJECT, next(), asset.handle, 0u, length)
    }.getOrNull()

    @Synchronized override fun download(asset: PhotoAsset, progress: (Long, Long) -> Unit): ByteArray {
        val output = java.io.ByteArrayOutputStream(asset.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        downloadTo(asset, output, progress)
        return output.toByteArray()
    }

    /**
     * 下载资源到 [output]，使用动态 chunk 拥塞控制。
     * 小文件（≤ [FULL_OBJECT_THRESHOLD]）优先整文件下载，仅接口不支持且尚未写入时回退；
     * 大文件用 [AdaptiveChunkController] 自适应调整 chunk 大小（1MB~8MB）。
     */
    @Synchronized override fun downloadTo(asset: PhotoAsset, output: OutputStream, progress: (Long, Long) -> Unit, isCancelled: () -> Boolean): Long {
        if (isCancelled()) throw DownloadCancelledException()
        require(asset.size <= UInt.MAX_VALUE.toLong()) { "此文件超出当前尼康 PTP 下载的大小范围" }
        if (asset.size <= FULL_OBJECT_THRESHOLD) {
            var touchedOutput = false
            val destination = object : OutputStream() {
                override fun write(value: Int) { touchedOutput = true; output.write(value) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { touchedOutput = true; output.write(bytes, offset, length) }
            }
            try {
                val copied = requestToStream(GET_OBJECT, next(), asset.handle, destination, progress, asset.size, 0L, isCancelled)
                check(copied > 0 && (asset.size <= 0 || copied == asset.size)) { "尼康文件传输不完整，请重新下载" }
                return copied
            } catch (error: PtpResponseException) {
                // A stream cannot rewind arbitrary destinations. Never append a retry after any write.
                if (touchedOutput || error.response != 0x2005 || asset.size <= 0) throw error
            }
        }
        return downloadInChunks(asset, output, progress, isCancelled)
    }

    /** 分块下载：动态 chunk + 失败重试 + 自动重连。 */
    private fun downloadInChunks(asset: PhotoAsset, output: OutputStream, progress: (Long, Long) -> Unit, isCancelled: () -> Boolean): Long {
        val controller = AdaptiveChunkController(DEFAULT_CHUNK_SIZE, MIN_CHUNK_SIZE, MAX_CHUNK_SIZE)
        var offset = 0L
        var retries = 0
        while (offset < asset.size) {
            if (isCancelled()) throw DownloadCancelledException()
            val count = controller.requestLength(asset.size - offset)
            // ponytail: stage at most 8 MiB so failed retries cannot duplicate bytes in MediaStore.
            val chunk = java.io.ByteArrayOutputStream(count)
            try {
                val copied = requestToStream(GET_PARTIAL_OBJECT, next(), asset.handle, offset.toUInt(), count.toUInt(), chunk, progress, asset.size, offset, isCancelled)
                check(copied in 1..count.toLong()) { "尼康相机返回的分块长度无效" }
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException || isCancelled()) throw DownloadCancelledException()
                if (e is PtpResponseException && e.response != 0x2019) throw e
                if (++retries > MAX_CHUNK_RETRIES) throw e
                controller.registerFailure()
                if (isDeviceBusy(e)) { Thread.sleep(retries * DEVICE_BUSY_BACKOFF_MS) }
                else { reconnect() }
                continue
            }
            if (isCancelled()) throw DownloadCancelledException()
            // Commit outside the retry block: output/storage failures must never resend this chunk.
            chunk.writeTo(output)
            offset += chunk.size()
            controller.registerSuccess(); retries = 0
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
        r.u16(); r.u32(); r.u32(); r.u32()
        val imageWidth = r.u32().toInt(); val imageHeight = r.u32().toInt()
        r.u32(); r.u32(); r.u16(); r.u32(); r.u32()
        val name = r.ptpString().ifBlank { "IMG_$handle" }
        val date = parsePtpDate(r.ptpString())
        return PhotoAsset(handle, name, size, format, date, imageWidth, imageHeight)
    }

    private fun parseDeviceInfo(data: ByteArray): ParsedDeviceInfo {
        val r = Reader(data)
        r.u16(); r.u32(); r.u16(); r.ptpString(); r.u16()
        val operations = r.u16Array().toSet()
        r.u16Array()
        val supportedProperties = r.u16Array().toSet()
        r.u16Array()
        r.u16Array()
        return ParsedDeviceInfo(
            CameraDetails(
                manufacturer = r.ptpString().ifBlank { null },
                model = r.ptpString().ifBlank { null },
                deviceVersion = r.ptpString().ifBlank { null },
                serialNumber = r.ptpString().ifBlank { null },
            ),
            supportedProperties,
            operations,
        )
    }

    private fun readLiveCameraDetails(details: CameraDetails, supportedProperties: Set<Int>): CameraDetails {
        fun property(code: Int): Long? {
            if (code !in supportedProperties) return null
            return runCatching {
                val bytes = requestData(GET_DEVICE_PROP_VALUE, next(), code.toUInt())
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                when (bytes.size) {
                    1 -> bytes[0].toLong() and 0xff
                    2 -> buffer.short.toLong() and 0xffff
                    4 -> buffer.int.toLong() and 0xffffffffL
                    8 -> buffer.long
                    else -> null
                }
            }.getOrNull()
        }

        val minFocal = property(NIKON_FOCAL_LENGTH_MIN)
        val maxFocal = property(NIKON_FOCAL_LENGTH_MAX)
        val minAperture = property(NIKON_MAX_APERTURE_MIN)
        val maxAperture = property(NIKON_MAX_APERTURE_MAX)
        val focalText = when {
            minFocal == null && maxFocal == null -> null
            minFocal == maxFocal || maxFocal == null -> "${formatHundredths(minFocal!!)}mm"
            minFocal == null -> "${formatHundredths(maxFocal)}mm"
            else -> "${formatHundredths(minFocal)}–${formatHundredths(maxFocal)}mm"
        }
        val apertureText = when {
            minAperture == null && maxAperture == null -> null
            minAperture == maxAperture || maxAperture == null -> "f/${formatHundredths(minAperture!!)}"
            minAperture == null -> "f/${formatHundredths(maxAperture)}"
            else -> "f/${formatHundredths(minAperture)}–${formatHundredths(maxAperture)}"
        }
        return details.copy(
            batteryPercent = property(BATTERY_LEVEL)?.toInt()?.takeIf { it in 0..100 },
            lensSpec = listOfNotNull(focalText, apertureText).joinToString(" · ").ifBlank { null },
        )
    }

    private fun formatHundredths(value: Long): String {
        val scaled = value / 100.0
        return if (scaled % 1.0 == 0.0) scaled.toInt().toString() else "%.1f".format(Locale.US, scaled)
    }

    private data class ParsedDeviceInfo(val details: CameraDetails, val supportedProperties: Set<Int>, val supportedOperations: Set<Int>)

    private data class CaptureEvent(val code: Int, val transaction: UInt, val at: Long, val handle: UInt? = null)

    private fun eventPollOperation(): Int? = listOf(NIKON_GET_EVENT_EX, NIKON_GET_EVENT).firstOrNull {
        it in supportedOperations && it !in unsupportedEventOperations
    }

    /** Formats verified in SnapBridge ptpclient s2/t2; polling consumes pending vendor events. */
    private fun pollNikonEvents(): List<CaptureEvent> {
        while (true) {
            val operation = eventPollOperation() ?: return emptyList()
            val bytes = try { requestData(operation, next()) }
            catch (error: PtpResponseException) {
                if (error.response !in listOf(0x2005, 0x200A, 0x201D)) throw error
                unsupportedEventOperations.add(operation)
                continue
            }
            val reader = Reader(bytes)
            val count = if (operation == NIKON_GET_EVENT_EX) reader.u32().toLong() else reader.u16().toLong()
            require(count <= bytes.size / 4) { "尼康事件数量无效" }
            return List(count.toInt()) {
                val code = reader.u16()
                val parameters = if (operation == NIKON_GET_EVENT_EX) reader.u16() else 1
                require(parameters <= 16) { "尼康事件参数数量无效" }
                val values = List(parameters) { reader.u32() }
                CaptureEvent(code, 0u, System.nanoTime(), values.firstOrNull())
            }
        }
    }

    private fun prepareRemoteControl() {
        if (!cameraModeChanged && NIKON_CHANGE_CAMERA_MODE in supportedOperations) {
            try {
                requestResponse(NIKON_CHANGE_CAMERA_MODE, next(), 1u)
                cameraModeChanged = true
            } catch (error: PtpResponseException) {
                if (error.response != 0xA003) throw error
            }
        }
        if (!applicationModeChanged && NIKON_CHANGE_APPLICATION_MODE in supportedOperations) {
            requestResponse(NIKON_CHANGE_APPLICATION_MODE, next(), 1u)
            applicationModeChanged = true
        }
        loadVendorProperties()
    }

    private fun loadVendorProperties() {
        if (vendorPropertiesLoaded) return
        // Nikon's generic DeviceInfo can omit the vendor shutter/ISO/live-view properties.
        if (0x90CA in supportedOperations) {
            try { supportedProperties = supportedProperties + Reader(requestData(0x90CA, next())).u16Array() }
            catch (error: PtpResponseException) { if (error.response == 0x2019) throw error }
        }
        if (0x9439 in supportedOperations) {
            try { supportedProperties = supportedProperties + Reader(requestData(0x9439, next(), 0xDu)).u32Array().map { it.toInt() } }
            catch (error: PtpResponseException) { if (error.response == 0x2019) throw error }
        }
        vendorPropertiesLoaded = true
    }

    @Synchronized override fun startLiveView() {
        require(supportsLiveView) { "当前连接未提供实时取景能力" }
        if (liveViewActive) return
        prepareRemoteControl()
        if (unsignedProperty(NIKON_LIVE_VIEW_STATUS) == 1L) {
            liveViewActive = true
            return // Do not end live view that was already active on the camera.
        }
        requestResponse(NIKON_START_LIVE_VIEW, next())
        liveViewOwned = true
        liveViewActive = true
    }

    @Synchronized override fun stopLiveView() {
        if (liveViewOwned) {
            try { requestResponse(NIKON_END_LIVE_VIEW, next()) }
            catch (error: PtpResponseException) { if (error.response != 0xA00B) throw error }
        }
        liveViewOwned = false
        liveViewActive = false
        touchFocusConfirmed = false
    }

    @Synchronized override fun liveViewFrame(): CameraLiveViewFrame {
        startLiveView()
        val data = try { requestData(NIKON_GET_LIVE_VIEW, next()) }
        catch (error: PtpResponseException) {
            if (error.response == 0xA00B) { liveViewActive = false; liveViewOwned = false }
            throw error
        }
        // Nikon prepends model-specific metadata. Locate JPEG instead of hardcoding an offset.
        for (offset in 0 until (data.size - 2).coerceAtLeast(0)) {
            if (data[offset] == 0xff.toByte() && data[offset + 1] == 0xd8.toByte() && data[offset + 2] == 0xff.toByte()) {
                val geometry = LiveViewFocusGeometry.parse(data, offset)
                val focusStatus = if (geometry != null) data[48].toInt() and 0xff else null
                if (focusStatus == 1) touchFocusConfirmed = false
                return CameraLiveViewFrame(data.copyOfRange(offset, data.size), geometry, focusStatus)
            }
        }
        throw LiveViewImageException("相机取景数据中没有可显示的 JPEG 画面")
    }

    @Synchronized override fun focusAt(point: NormalizedFocusPoint, geometry: LiveViewFocusGeometry,
        isCancelled: () -> Boolean): RemoteFocusResult {
        require(supportsTouchFocus) { "当前连接不支持点击对焦" }
        fun checkCancelled() {
            if (isCancelled()) throw kotlin.coroutines.cancellation.CancellationException("对焦操作已取消")
        }
        checkCancelled()
        require(liveViewActive) { "请等待实时取景恢复后再对焦" }
        touchFocusConfirmed = false
        require(unsignedProperty(0x500A) != 1L) { "相机当前为手动对焦，请在机身上切换为自动对焦" }
        val currentFrame = liveViewFrame()
        require(currentFrame.focusGeometry == geometry) { "相机取景范围已改变，请在最新画面上重新点按" }
        val (x, y) = geometry.cameraPoint(point)
        checkCancelled()
        requestResponse(NIKON_CHANGE_AF_AREA, next(), x.toUInt(), y.toUInt())
        checkCancelled()
        requestResponse(NIKON_AF_DRIVE, next())
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            checkCancelled()
            try {
                requestResponse(NIKON_DEVICE_READY, next())
                val resultFrame = liveViewFrame()
                checkCancelled()
                if (resultFrame.focusGeometry != geometry) {
                    return RemoteFocusResult(RemoteFocusPhase.UNCONFIRMED, "相机取景范围已改变，请重新点按对焦")
                }
                return when (resultFrame.focusStatus) {
                    2 -> { touchFocusConfirmed = true; RemoteFocusResult(RemoteFocusPhase.FOCUSED, "已合焦") }
                    1 -> RemoteFocusResult(RemoteFocusPhase.FAILED, "未能合焦，请换一个位置或检查镜头")
                    else -> RemoteFocusResult(RemoteFocusPhase.UNCONFIRMED, "对焦指令已完成，相机未提供合焦状态")
                }
            } catch (error: PtpResponseException) {
                when (error.response) {
                    0x2019 -> Thread.sleep(100)
                    0xA002 -> return RemoteFocusResult(RemoteFocusPhase.FAILED, "未能合焦，请换一个位置或检查镜头")
                    else -> throw error
                }
            }
        }
        return RemoteFocusResult(RemoteFocusPhase.UNCONFIRMED, "暂未确认对焦结果，请查看画面后重试")
    }

    private fun unsignedProperty(code: Int): Long? {
        if (code !in supportedProperties || code in unreadableProperties) return null
        val bytes = try { requestData(GET_DEVICE_PROP_VALUE, next(), code.toUInt()) }
        catch (error: PtpResponseException) {
            if (error.response == 0x2019) throw error
            unreadableProperties += code
            return null
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return when (bytes.size) {
            1 -> bytes[0].toLong() and 0xff
            2 -> buffer.short.toLong() and 0xffff
            4 -> buffer.int.toLong() and 0xffffffffL
            8 -> buffer.long
            else -> null
        }
    }

    @Synchronized override fun shootingParameters(): CameraShootingParameters {
        loadVendorProperties()
        val nikonTime = unsignedProperty(0xD100)
        val shutter = when (nikonTime) {
            0xffffffffL -> "Bulb"
            0xfffffffdL -> "Time"
            0xfffffffeL -> "X 1/200"
            null -> unsignedProperty(0x500D)?.takeIf { it > 0 }?.let { ticks ->
                val seconds = ticks / 10000.0
                if (seconds < 1) "1/${kotlin.math.round(1 / seconds).toInt()}" else "${"%.1f".format(Locale.US, seconds).removeSuffix(".0")}s"
            }
            else -> {
                val numerator = nikonTime shr 16
                val denominator = nikonTime and 0xffff
                when {
                    denominator == 1L -> "${numerator}s"
                    numerator > 0 && denominator > 0 -> "$numerator/$denominator"
                    else -> null
                }
            }
        }
        val aperture = unsignedProperty(0x5007)?.takeIf { it > 0 }?.let { "f/${formatHundredths(it)}" }
        val iso = (unsignedProperty(0x500F) ?: unsignedProperty(0xD0B4))?.let {
            when (it) { 0L, 0xffffL, 0xffffffffL -> "AUTO"; else -> it.toString() }
        }
        val mode = when (val value = unsignedProperty(0x500E)) {
            1L -> "M"; 2L -> "P"; 3L -> "A"; 4L -> "S"; 0x8010L -> "AUTO"
            null -> null; else -> "0x${value.toString(16)}"
        }
        return CameraShootingParameters(shutter, aperture, iso, mode,
            unsignedProperty(0x5008)?.takeIf { it > 0 }?.let { "${formatHundredths(it)} mm" },
            unsignedProperty(BATTERY_LEVEL)?.toInt()?.takeIf { it in 0..100 },
            unsignedProperty(0x5010)?.let { RemoteParameter.COMPENSATION.display(0x5010, it.toShort().toLong()) },
            unsignedProperty(0x5005)?.let { RemoteParameter.WHITE_BALANCE.display(0x5005, it) },
            unsignedProperty(0xD200)?.let(NikonPictureControls::label))
    }

    @Synchronized override fun parameterDescriptor(parameter: RemoteParameter): CameraParameterDescriptor {
        prepareRemoteControl()
        loadVendorProperties()
        require(0x1014 in supportedOperations) { "当前连接不支持读取可调参数" }
        var fallback: CameraParameterDescriptor? = null
        for (code in parameter.codes.filter { it in supportedProperties }) {
            val descriptor = try {
                CameraParameterDescriptor.parse(requestData(0x1014, next(), code.toUInt()))
                    .also { require(it.code == code) { "相机返回的参数编号不匹配" } }
            } catch (error: PtpResponseException) {
                if (error.response !in listOf(0x2005, 0x200A)) throw error
                continue
            }
            fallback = fallback ?: descriptor
            if (descriptor.writable) { fallback = descriptor; break }
        }
        val descriptor = fallback ?: error("相机未提供${parameter.title}的可调参数")
        if (parameter == RemoteParameter.PICTURE_CONTROL && descriptor.values.isEmpty()) {
            return descriptor.copy(writable = false, reason = "相机未提供可选择的机内滤镜列表")
        }
        val mode = unsignedProperty(0x500E)
        val allowed = parameter.modeAllows(mode)
        return descriptor.copy(
            writable = descriptor.writable && allowed && 0x1016 in supportedOperations,
            reason = when {
                !allowed -> "当前拍摄模式下由相机控制，请在机身上选择适合的模式"
                !descriptor.writable -> descriptor.reason ?: "此参数当前由相机控制，只能查看"
                0x1016 !in supportedOperations -> "当前连接不支持修改参数"
                else -> null
            },
        )
    }

    @Synchronized override fun setParameter(parameter: RemoteParameter, value: Long): CameraShootingParameters {
        // Re-read mode, write permission and allowed values immediately before sending.
        val descriptor = parameterDescriptor(parameter)
        val bytes = descriptor.encode(value)
        val id = next()
        for (writePacket in ptpParameterWritePackets(id, descriptor.code, bytes)) command.send(writePacket)
        validateResponse(0x1016, id, command.read())
        val actual = CameraParameterDescriptor.parse(requestData(0x1014, next(), descriptor.code.toUInt()))
        require(actual.code == descriptor.code && actual.current == value) {
            "相机返回的实际值为${parameter.display(descriptor.code, actual.current)}，未确认修改生效"
        }
        // A previously unavailable value may become readable after the mode changes.
        unreadableProperties.remove(descriptor.code)
        return shootingParameters()
    }

    /** One reader per event socket; close/reconnect unblocks and retires the old reader. */
    private fun startEventReader(channel: Channel) {
        channel.disableReadTimeout()
        val queue = captureEvents
        Thread({
            try {
                while (true) {
                    val incoming = channel.read()
                    if (incoming.type == PING) { channel.send(packet(PONG, ByteArray(0))); continue }
                    if (incoming.type != EVENT || incoming.payload.size < 6) continue
                    val reader = Reader(incoming.payload)
                    val code = reader.u16()
                    val id = reader.u32()
                    if (code == OBJECT_ADDED || code == CAPTURE_COMPLETE) {
                        queue.add(CaptureEvent(code, id, System.nanoTime(), if (incoming.payload.size >= 10) reader.u32() else null))
                        while (queue.size > 64) queue.poll()
                    }
                }
            } catch (_: Exception) {
                // A disconnected/malformed event stream must not terminate the process.
                // Object-list polling remains available on the command transport.
            }
        }, "Link-PTP-events").apply { isDaemon = true; start() }
    }

    /** No shutter retries: a lost reply may still mean the camera took the photograph. */
    @Synchronized override fun capture(isCancelled: () -> Boolean, onSending: () -> Unit): RemoteCaptureResult {
        require(supportsRemoteCapture) { "当前相机连接未提供遥控拍摄能力" }
        fun checkCancelled() { if (isCancelled()) throw kotlin.coroutines.cancellation.CancellationException("拍摄已取消") }
        checkCancelled()
        // Retain the already working AF + card capture path while live view is paused.
        val cameraLiveViewKept = liveViewActive && !liveViewOwned
        val preserveTouchFocus = liveViewActive && touchFocusConfirmed
        stopLiveView()
        prepareRemoteControl()
        checkCancelled()
        val preparationStarted = System.nanoTime()
        // Drain pre-existing events so old photographs cannot confirm the new shutter.
        if (eventPollOperation() != null) pollNikonEvents()
        val vendorEvents = eventPollOperation() != null
        // Vendor events avoid enumerating both cards for every shot. Legacy fallback still snapshots all cards.
        val baseline = try {
            if (vendorEvents) objectHandles?.toSet() else readAllObjectHandles().toSet()
        } catch (_: PtpResponseException) { null }
        onProtocolEvent("Nikon capture preparation: eventPoll=${eventPollOperation()?.toString(16) ?: "none"}, durationMs=${(System.nanoTime() - preparationStarted) / 1_000_000}")
        checkCancelled()
        captureEvents.clear()
        val startedAt = System.nanoTime()
        val captureId = next()
        val operation = if (NIKON_CAPTURE_TO_CARD in supportedOperations) NIKON_CAPTURE_TO_CARD else INITIATE_CAPTURE
        val parameters = if (operation == NIKON_CAPTURE_TO_CARD) uintArrayOf(if (cameraLiveViewKept || preserveTouchFocus) 0xffffffffu else 0xfffffffeu, 0u) else uintArrayOf(0u, 0u)
        // The UI stops offering cancellation before any shutter bytes are sent.
        onSending()
        try {
            requestResponse(operation, captureId, *parameters)
        } catch (error: PtpResponseException) {
            throw error // Explicit rejection: safe to let the user retry manually.
        } catch (_: Exception) {
            return RemoteCaptureResult(false, "拍摄指令可能已发送，暂未确认结果，请查看相机；不会自动重拍")
        }
        onProtocolEvent("Nikon shutter accepted: durationMs=${(System.nanoTime() - startedAt) / 1_000_000}")
        val deadline = System.nanoTime() + 30_000_000_000L
        var lastPoll = startedAt
        var lastEventPoll = 0L
        while (System.nanoTime() < deadline) {
            var received = captureEvents.poll()
            while (received != null) {
                if (received.at >= startedAt && (received.transaction == captureId || received.transaction == 0u || received.transaction == UInt.MAX_VALUE)) {
                    val objectHandle = received.handle
                    val newObject = received.code == OBJECT_ADDED && objectHandle != null && objectHandle != 0u && objectHandle != UInt.MAX_VALUE &&
                        (if (baseline != null) objectHandle !in baseline else vendorEvents || received.transaction == captureId)
                    if (received.code == CAPTURE_COMPLETE || newObject) {
                        objectHandles = null
                        onProtocolEvent("Nikon capture confirmed: source=event, code=${received.code.toString(16)}, durationMs=${(System.nanoTime() - startedAt) / 1_000_000}")
                        return RemoteCaptureResult(true, if (newObject) "相机已生成新照片，照片保存在相机中" else "相机已确认拍摄完成，照片保存在相机中")
                    }
                }
                received = captureEvents.poll()
            }
            // SnapBridge polls GetEventEx/GetEvent even when the PTP/IP event socket is silent.
            if (eventPollOperation() != null && System.nanoTime() - lastEventPoll >= 150_000_000L) {
                try { captureEvents.addAll(pollNikonEvents()) }
                catch (error: PtpResponseException) { if (error.response != 0x2019) break }
                catch (_: Exception) { break }
                lastEventPoll = System.nanoTime()
                if (captureEvents.isNotEmpty()) continue
            }
            // Only legacy bodies without vendor event support need expensive full-card polling.
            if (eventPollOperation() == null && baseline != null && System.nanoTime() - lastPoll >= 1_000_000_000L) {
                lastPoll = System.nanoTime()
                try {
                    val handles = readAllObjectHandles()
                    if (handles.any { it !in baseline }) {
                        objectHandles = null
                        onProtocolEvent("Nikon capture confirmed: source=card-list, durationMs=${(System.nanoTime() - startedAt) / 1_000_000}")
                        return RemoteCaptureResult(true, "相机中已出现新照片，照片保存在相机中")
                    }
                } catch (error: PtpResponseException) {
                    if (error.response != 0x2019) break
                } catch (_: Exception) { break }
            }
            Thread.sleep(100)
        }
        objectHandles = null
        return RemoteCaptureResult(false, "相机已接受快门指令，暂未确认拍摄完成；长曝光请等待并查看相机")
    }

    private fun validateResponse(operation: Int, id: UInt, response: RawPacket) {
        require(response.type == OPERATION_RESPONSE && response.payload.size >= 6) { "收到无效的 PTP 响应" }
        val reader = Reader(response.payload)
        val code = reader.u16()
        val responseId = reader.u32()
        require(responseId == id || operation == CLOSE_SESSION) { "相机事务编号不匹配，请重新连接" }
        if (code != RESPONSE_OK) throw PtpResponseException(operation, code)
    }

    @Synchronized private fun requestResponse(operation: Int, id: UInt, vararg parameters: UInt) {
        command.send(operationPacket(operation, id, parameters)); val response = command.read()
        validateResponse(operation, id, response)
    }

    @Synchronized private fun requestData(operation: Int, id: UInt, vararg parameters: UInt): ByteArray = requestData(operation, id, null, *parameters)
    @Synchronized private fun requestData(operation: Int, id: UInt, progress: ((Long, Long) -> Unit)?, vararg parameters: UInt): ByteArray {
        command.send(operationPacket(operation, id, parameters)); val output = java.io.ByteArrayOutputStream(); var expected = 0L
        while (true) {
            val packet = command.read()
            when (packet.type) {
                START_DATA -> { val r = Reader(packet.payload); r.u32(); expected = r.u64().toLong(); progress?.invoke(0, expected) }
                DATA, END_DATA -> { val r = Reader(packet.payload); r.u32(); val part = r.remaining(); output.write(part); progress?.invoke(output.size().toLong(), expected) }
                OPERATION_RESPONSE -> { validateResponse(operation, id, packet); return output.toByteArray() }
                else -> error("意外的 PTP/IP 数据包：${packet.type}")
            }
        }
    }

    /** 流式下载：数据包直接写入 [output]，不经过 ArrayList 装箱，避免 GC 压力。 */
    @Synchronized private fun requestToStream(operation: Int, id: UInt, output: OutputStream, progress: (Long, Long) -> Unit, total: Long, baseOffset: Long, isCancelled: () -> Boolean, vararg parameters: UInt): Long {
        if (isCancelled()) throw DownloadCancelledException()
        command.send(operationPacket(operation, id, parameters)); var written = 0L; var received = 0L
        var expected: Long? = null
        val maximum = if (operation == GET_PARTIAL_OBJECT) parameters.getOrNull(2)?.toLong() else total.takeIf { it > 0 }
        var started = false; var ended = false
        var draining = false
        while (true) {
            val packet = command.read()
            when (packet.type) {
                START_DATA -> {
                    check(!started) { "尼康重复返回传输起始包" }
                    val r = Reader(packet.payload)
                    check(r.u32() == id) { "尼康数据事务编号不匹配" }
                    val length = r.u64()
                    check(length == ULong.MAX_VALUE || length <= Long.MAX_VALUE.toULong()) { "尼康传输长度无效" }
                    expected = length.takeUnless { it == ULong.MAX_VALUE }?.toLong()
                    check(maximum == null || expected == null || expected!! <= maximum) { "尼康声明的传输长度超过请求范围" }
                    started = true
                    if (!isCancelled()) progress(baseOffset, total.takeIf { it > 0 } ?: expected ?: 0) else draining = true
                }
                DATA, END_DATA -> {
                    check(started && !ended) { "尼康数据包顺序无效" }
                    val r = Reader(packet.payload)
                    check(r.u32() == id) { "尼康数据事务编号不匹配" }
                    val part = r.remaining(); received += part.size
                    check(expected == null || received <= expected!!) { "尼康传输数据超过声明长度" }
                    check(maximum == null || received <= maximum) { "尼康传输数据超过请求范围" }
                    if (isCancelled()) draining = true
                    if (!draining) { output.write(part); written += part.size; progress(baseOffset + written, total.takeIf { it > 0 } ?: expected ?: 0) }
                    ended = packet.type == END_DATA
                }
                OPERATION_RESPONSE -> {
                    validateResponse(operation, id, packet)
                    if (draining || isCancelled()) throw DownloadCancelledException()
                    check(started && ended && (expected == null || received == expected)) { "尼康文件传输不完整，请重新下载" }
                    return written
                }
                else -> error("收到意外的 PTP/IP 数据包：${packet.type}")
            }
        }
    }

    /** GET_OBJECT 整文件下载（无 offset/count 参数，只有 handle）。 */
    private fun requestToStream(operation: Int, id: UInt, handle: UInt, output: OutputStream, progress: (Long, Long) -> Unit, total: Long, baseOffset: Long, isCancelled: () -> Boolean = { false }): Long =
        requestToStream(operation, id, output, progress, total, baseOffset, isCancelled, handle)

    /** GET_PARTIAL_OBJECT 分块下载（handle + offset + count）。 */
    private fun requestToStream(operation: Int, id: UInt, handle: UInt, offset: UInt, count: UInt, output: OutputStream, progress: (Long, Long) -> Unit, total: Long, baseOffset: Long, isCancelled: () -> Boolean): Long =
        requestToStream(operation, id, output, progress, total, baseOffset, isCancelled, handle, offset, count)

    @Synchronized override fun exitRemoteControl() {
        stopLiveView()
        if (applicationModeChanged) {
            requestResponse(NIKON_CHANGE_APPLICATION_MODE, next(), 0u)
            applicationModeChanged = false
        }
        if (cameraModeChanged) {
            requestResponse(NIKON_CHANGE_CAMERA_MODE, next(), 0u)
            cameraModeChanged = false
        }
    }

    private fun next() = transaction++
    @Synchronized override fun close() {
        if (::command.isInitialized) {
            runCatching {
                exitRemoteControl()
                requestResponse(CLOSE_SESSION, next())
            }
            runCatching { command.close() }
        }
        if (::event.isInitialized) runCatching { event.close() }
        supportedOperations = emptySet()
        supportedProperties = emptySet()
        captureEvents.clear()
    }

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
        fun disableReadTimeout() { socket.soTimeout = 0 }
        fun read(): RawPacket { val header = input.readFully(8); val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN); val length = b.int; require(length in 8..(32 * 1024 * 1024)) { "无效 PTP/IP 包长度" }; return RawPacket(b.int, input.readFully(length - 8)) }
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
        private const val NIKON_GET_LARGE_THUMB = 0x90C4
        private const val GET_DEVICE_INFO = 0x1001; private const val OPEN_SESSION = 0x1002; private const val CLOSE_SESSION = 0x1003; private const val GET_STORAGE_IDS = 0x1004; private const val GET_OBJECT_HANDLES = 0x1007; private const val GET_OBJECT_INFO = 0x1008; private const val GET_OBJECT = 0x1009; private const val GET_THUMB = 0x100A; private const val GET_DEVICE_PROP_VALUE = 0x1015; private const val GET_PARTIAL_OBJECT = 0x101B
        private const val RESPONSE_OK = 0x2001
        private const val INITIATE_CAPTURE = 0x100E
        private const val NIKON_CHANGE_CAMERA_MODE = 0x90C2
        private const val NIKON_CHANGE_APPLICATION_MODE = 0x9435
        private const val NIKON_CAPTURE_TO_CARD = 0x9207
        private const val NIKON_START_LIVE_VIEW = 0x9201
        private const val NIKON_END_LIVE_VIEW = 0x9202
        private const val NIKON_GET_LIVE_VIEW = 0x9203
        private const val NIKON_CHANGE_AF_AREA = 0x9205
        private const val NIKON_AF_DRIVE = 0x90C1
        private const val NIKON_DEVICE_READY = 0x90C8
        private const val NIKON_GET_EVENT = 0x90C7
        private const val NIKON_GET_EVENT_EX = 0x941C
        private const val NIKON_LIVE_VIEW_STATUS = 0xD1A2
        private const val EVENT = 8; private const val PING = 13; private const val PONG = 14
        private const val OBJECT_ADDED = 0x4002; private const val CAPTURE_COMPLETE = 0x400D
        private const val BATTERY_LEVEL = 0x5001
        private const val NIKON_FOCAL_LENGTH_MIN = 0xD0E3
        private const val NIKON_FOCAL_LENGTH_MAX = 0xD0E4
        private const val NIKON_MAX_APERTURE_MIN = 0xD0E5
        private const val NIKON_MAX_APERTURE_MAX = 0xD0E6

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

/* Adapted from studio-camera (develop e4a82d7), Copyright (c) 2026 Lenny Obez.
 * Personal/non-commercial use; see assets/licenses/studio-camera.txt and CAMERA_INTEGRATION.md. */
package com.yaoyihan.nikonconnect

import android.net.Network
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.ArrayDeque
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

internal object SonyDiscovery {
    fun search(network: Network, fallbackHost: String, cancelled: () -> Boolean): List<SonyDevice> {
        val locations = linkedSetOf<String>()
        DatagramSocket(null).use { socket ->
            network.bindSocket(socket); socket.bind(InetSocketAddress(0))
            val query = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\nST: urn:schemas-sony-com:service:ScalarWebAPI:1\r\n\r\n").toByteArray(Charsets.US_ASCII)
            repeat(2) { socket.send(DatagramPacket(query, query.size, InetAddress.getByName("239.255.255.250"), 1900)) }
            val deadline = System.nanoTime() + 2_500_000_000L
            socket.soTimeout = 300
            while (System.nanoTime() < deadline && locations.size < 16) {
                if (cancelled()) throw DownloadCancelledException()
                try {
                    val packet = DatagramPacket(ByteArray(8192), 8192)
                    socket.receive(packet)
                    val location = String(packet.data, 0, packet.length, Charsets.UTF_8).lineSequence()
                        .firstOrNull { it.substringBefore(':').equals("location", true) }?.substringAfter(':')?.trim() ?: continue
                    val uri = runCatching { URI(location) }.getOrNull() ?: continue
                    if (uri.scheme == "http" && uri.userInfo == null && uri.host == packet.address.hostAddress) locations.add(location)
                } catch (_: SocketTimeoutException) { }
            }
        }
        val devices = locations.mapNotNull { location ->
            if (cancelled()) throw DownloadCancelledException()
            runCatching {
                val connection = network.openConnection(URL(location)) as HttpURLConnection
                try {
                    connection.connectTimeout = 2000; connection.readTimeout = 2000; connection.instanceFollowRedirects = false
                    if (connection.responseCode != 200) null else SonyProtocol.description(
                        connection.inputStream.use { it.readCameraBytes(1024 * 1024) }.toString(Charsets.UTF_8), location)
                } finally { connection.disconnect() }
            }.getOrNull()
        }.distinctBy { it.endpoint }
        if (devices.isNotEmpty()) return devices
        require(fallbackHost.isNotBlank() && !fallbackHost.contains('/') && !fallbackHost.contains(':')) { "相机地址无效" }
        // Probe only the connected camera gateway, without scanning unrelated addresses.
        for (port in listOf(8080, 10000)) {
            if (cancelled()) throw DownloadCancelledException()
            val base = "http://$fallbackHost:$port/sony"
            val device = SonyDevice("$base/camera", "$base/avContent", "Sony", CameraDetails(manufacturer = "Sony"))
            val probe = SonyCameraClient(device, { network.openConnection(it) as HttpURLConnection }, cancelled)
            try {
                if (probe.checkConnection()) return listOf(device)
            } catch (_: Exception) { } finally { probe.close() }
        }
        return emptyList()
    }
}

internal class SonyCameraClient(
    val device: SonyDevice,
    private val openConnection: (URL) -> HttpURLConnection,
    private val isConnectionCancelled: () -> Boolean = { false },
) : CameraClient, RemoteCameraControl {
    private data class Directory(val uri: String, var offset: Int = 0, var previous: String? = null)
    private data class Entry(val asset: PhotoAsset, val media: SonyMedia)
    private val requests = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val entries = linkedMapOf<UInt, Entry>()
    private val handles = mutableMapOf<String, UInt>()
    private val directories = ArrayDeque<Directory>()
    private val seenDirectories = mutableSetOf<String>()
    private var requestId = 0
    private var connected = false
    @Volatile private var closed = false
    private var cameraApis = emptySet<String>()
    private var declaredApis = emptySet<String>()
    private var function: String? = null
    private var stream: HttpURLConnection? = null
    private var streamInput: InputStream? = null
    private var streamStarted = false
    override val cameraName get() = device.name
    override val cameraDetails get() = device.details
    override val transport = ConnectionTransport.WIFI
    override val supportsRemoteCapture get() = "actTakePicture" in declaredApis
    override val supportsLiveView get() = "startLiveview" in declaredApis
    override val supportsTouchFocus = false

    private fun active() { if (closed || isConnectionCancelled()) throw DownloadCancelledException() }

    private fun connection(url: String, timeout: Int = 8000): HttpURLConnection {
        active()
        val result = openConnection(URL(SonyProtocol.resolve(device.endpoint, url)))
        requests.add(result)
        result.connectTimeout = 3000; result.readTimeout = timeout
        result.instanceFollowRedirects = false; result.useCaches = false
        return result
    }

    private fun release(connection: HttpURLConnection) { requests.remove(connection); connection.disconnect() }

    private fun rpc(method: String, params: JSONArray = JSONArray(), endpoint: String = device.endpoint, version: String = "1.0"): JSONArray {
        active()
        val id = ++requestId
        val connection = connection(endpoint)
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(SonyProtocol.request(method, params, version, id).toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) throw IOException("索尼接口请求失败（HTTP ${connection.responseCode}，$method）")
            val body = connection.inputStream.use { it.readCameraBytes(8 * 1024 * 1024) }.toString(Charsets.UTF_8)
            return SonyProtocol.result(body, method, id)
        } finally { release(connection) }
    }

    @Synchronized override fun checkConnection(): Boolean = SonyProtocol.apiNames(rpc("getAvailableApiList")).isNotEmpty()

    @Synchronized override fun connect() {
        cameraApis = SonyProtocol.apiNames(rpc("getAvailableApiList"))
        if (cameraApis.isEmpty()) throw IOException("此索尼设备未提供 Camera Remote API")
        declaredApis = cameraApis
        if ("getMethodTypes" in cameraApis) {
            val methods = rpc("getMethodTypes", JSONArray().put(""))
            declaredApis = declaredApis + (0 until methods.length()).mapNotNull { methods.optJSONArray(it)?.optString(0)?.takeIf(String::isNotBlank) }
        }
        connected = true
        refreshAssets()
    }

    private fun switchFunction(target: String) {
        if (function == target) return
        stopLiveView()
        cameraApis = SonyProtocol.apiNames(rpc("getAvailableApiList"))
        if ("setCameraFunction" in cameraApis) rpc("setCameraFunction", JSONArray().put(target))
        else if (target == "RemoteShooting" && "startRecMode" in cameraApis) rpc("startRecMode")
        cameraApis = SonyProtocol.apiNames(rpc("getAvailableApiList"))
        declaredApis = declaredApis + cameraApis
        function = target
    }

    @Synchronized override fun refreshAssets() {
        check(connected) { "请先连接索尼相机" }
        entries.clear(); directories.clear(); seenDirectories.clear()
        directories.add(Directory("storage:memoryCard1")); seenDirectories.add("storage:memoryCard1")
    }

    @Synchronized override fun assets(offset: Int, limit: Int): List<PhotoAsset> {
        require(offset >= 0 && limit in 1..200)
        check(connected) { "请先连接索尼相机" }
        if (entries.size < offset + limit && directories.isNotEmpty()) switchFunction("ContentsTransfer")
        var pages = 0
        while (entries.size < offset + limit && directories.isNotEmpty()) {
            active()
            if (++pages > 100) throw IOException("索尼相册目录过多，请重新读取")
            val directory = directories.first()
            val count = minOf(limit, 100)
            val params = JSONArray().put(JSONObject().put("uri", directory.uri).put("stIdx", directory.offset)
                .put("cnt", count).put("view", "flat").put("sort", ""))
            val page = SonyProtocol.page(rpc("getContentList", params, device.contentEndpoint, "1.3"), device.endpoint)
            if (page.returned > 0 && page.signature == directory.previous) throw IOException("索尼相机重复返回同一页，请刷新相册")
            directory.previous = page.signature; directory.offset += page.returned
            if (page.returned < count) directories.removeFirst()
            page.directories.filter { seenDirectories.add(it) }.forEach { directories.add(Directory(it)) }
            page.media.forEach { media ->
                val handle = handles.getOrPut(media.key) { (handles.size + 1).toUInt() }
                if (handle !in entries) {
                    val format = when {
                        mediaKindFor(null, media.name) == MediaKind.RAW_IMAGE -> 0x3000
                        media.name.endsWith(".jpg", true) || media.name.endsWith(".jpeg", true) -> 0x3801
                        media.name.endsWith(".png", true) -> 0x380B
                        else -> 0
                    }
                    entries[handle] = Entry(PhotoAsset(handle, media.name, media.size, format, Date(media.capturedAt)), media)
                }
            }
        }
        return entries.values.drop(offset).take(limit).map { it.asset }
    }

    @Synchronized override fun hasMoreAssets(offset: Int) = entries.size > offset || directories.isNotEmpty()
    private fun entry(asset: PhotoAsset) = entries[asset.handle]?.takeIf { it.asset.name == asset.name } ?: throw IOException("相册已更新，请重新选择文件")

    private fun <T> resource(url: String, range: Int? = null, block: (HttpURLConnection) -> T): T {
        switchFunction("ContentsTransfer")
        val connection = connection(url)
        try {
            range?.let { connection.setRequestProperty("Range", "bytes=0-${it - 1}") }
            if (connection.responseCode !in 200..299) throw IOException("索尼文件读取失败（HTTP ${connection.responseCode}）")
            if (connection.contentType.orEmpty().let { it.startsWith("text/", true) || it.contains("xml", true) || it.contains("json", true) })
                throw IOException("索尼相机返回了错误页面，请重新连接")
            return block(connection)
        } finally { release(connection) }
    }

    @Synchronized override fun thumbnail(asset: PhotoAsset): ByteArray? = entry(asset).media.thumbnail?.let { url ->
        resource(url) { it.inputStream.use { input -> input.readCameraBytes(4 * 1024 * 1024) } }
    }
    @Synchronized override fun imageHeader(asset: PhotoAsset): ByteArray? = if (asset.name.isVideoName()) null else {
        resource(entry(asset).media.url, IMAGE_HEADER_SIZE) { it.inputStream.use { input -> input.readCameraBytes(IMAGE_HEADER_SIZE, truncate = true) } }
    }
    @Synchronized override fun download(asset: PhotoAsset, progress: (Long, Long) -> Unit): ByteArray =
        ByteArrayOutputStream().also { downloadTo(asset, it, progress) }.toByteArray()

    @Synchronized override fun downloadTo(asset: PhotoAsset, output: OutputStream, progress: (Long, Long) -> Unit, isCancelled: () -> Boolean): Long {
        if (isCancelled()) throw DownloadCancelledException()
        return resource(entry(asset).media.url) { connection ->
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.size
            var done = 0L
            progress(0, total)
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    active(); if (isCancelled()) throw DownloadCancelledException()
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (isCancelled()) throw DownloadCancelledException()
                    output.write(buffer, 0, count); done += count; progress(done, total)
                }
            }
            if (done == 0L || (total > 0 && done != total) || (asset.size > 0 && done != asset.size)) throw IOException("索尼文件传输不完整，请重新下载")
            done
        }
    }

    @Synchronized override fun startLiveView() {
        if (streamInput != null) return
        switchFunction("RemoteShooting")
        require("startLiveview" in cameraApis) { "此索尼机型当前未提供实时取景" }
        val url = rpc("startLiveview").optString(0).takeIf(String::isNotBlank) ?: throw IOException("索尼未返回取景地址")
        streamStarted = true
        val connection = connection(url)
        try {
            if (connection.responseCode != 200) throw IOException("索尼取景连接失败（HTTP ${connection.responseCode}）")
            streamInput = connection.inputStream; stream = connection
        } catch (error: Exception) { release(connection); stopLiveView(); throw error }
    }

    @Synchronized override fun liveViewFrame() = CameraLiveViewFrame(SonyProtocol.frame(streamInput ?: throw IOException("请先开启索尼取景")), null, null)

    @Synchronized override fun stopLiveView() {
        stream?.let { release(it) }; stream = null; streamInput = null
        if (streamStarted) {
            streamStarted = false
            if (!closed && !isConnectionCancelled()) rpc("stopLiveview")
        }
    }

    @Synchronized override fun shootingParameters(): CameraShootingParameters {
        if (function != "RemoteShooting" || "getEvent" !in cameraApis) return CameraShootingParameters()
        val values = mutableMapOf<String, String>()
        fun collect(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    val item = value.opt(key)
                    if (item is JSONObject || item is JSONArray) collect(item) else if (item != null && item != JSONObject.NULL) values[key] = item.toString()
                }
                is JSONArray -> (0 until value.length()).forEach { collect(value.opt(it)) }
            }
        }
        collect(rpc("getEvent", JSONArray().put(false)))
        return CameraShootingParameters(shutter = values["currentShutterSpeed"], aperture = values["currentFNumber"]?.let { "f/$it" },
            iso = values["currentIsoSpeedRate"], mode = values["currentExposureMode"], compensation = values["currentExposureCompensation"],
            whiteBalance = values["currentWhiteBalanceMode"])
    }

    override fun parameterDescriptor(parameter: RemoteParameter) = CameraParameterDescriptor(0, 0, false, 0, reason = "索尼拍摄参数暂为只读，请在机身上调整")
    override fun setParameter(parameter: RemoteParameter, value: Long): CameraShootingParameters = throw IOException("索尼拍摄参数暂为只读")
    override fun focusAt(point: NormalizedFocusPoint, geometry: LiveViewFocusGeometry, isCancelled: () -> Boolean): RemoteFocusResult =
        throw IOException("索尼点击对焦尚未接入")

    @Synchronized override fun capture(isCancelled: () -> Boolean, onSending: () -> Unit): RemoteCaptureResult {
        if (isCancelled()) throw DownloadCancelledException()
        switchFunction("RemoteShooting")
        require("actTakePicture" in cameraApis) { "此索尼机型当前未提供遥控快门" }
        if (isCancelled()) throw DownloadCancelledException()
        onSending()
        val result = try { rpc("actTakePicture") }
        catch (error: SonyApiException) { throw error }
        catch (error: IOException) { return RemoteCaptureResult(false, "快门指令已尝试发送，未收到拍摄确认，请检查相机后再操作") }
        val confirmed = result.optJSONArray(0)?.let { it.length() > 0 && it.optString(0).isNotBlank() } == true
        return RemoteCaptureResult(confirmed, if (confirmed) "索尼相机已确认拍摄" else "相机已响应，尚未确认生成照片，请查看机身")
    }

    override fun close() {
        closed = true; connected = false
        requests.forEach { it.disconnect() }; requests.clear()
        stream = null; streamInput = null; streamStarted = false
    }
}

private fun InputStream.readCameraBytes(limit: Int, truncate: Boolean = false): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    val maximum = if (truncate) limit else limit + 1
    while (output.size() < maximum) {
        val count = read(buffer, 0, minOf(buffer.size, maximum - output.size()))
        if (count == -1) break
        output.write(buffer, 0, count)
    }
    if (!truncate && output.size() > limit) throw IOException("索尼响应过大")
    return output.toByteArray()
}

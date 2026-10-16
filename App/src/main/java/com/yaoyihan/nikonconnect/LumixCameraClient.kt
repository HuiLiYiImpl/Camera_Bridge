package com.yaoyihan.nikonconnect

import android.net.Network
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

internal object LumixDiscovery {
    fun search(network: Network, fallbackHost: String, cancelled: () -> Boolean): List<LumixDevice> {
        val locations = linkedSetOf<String>()
        DatagramSocket(null).use { socket ->
            network.bindSocket(socket)
            socket.bind(InetSocketAddress(0))
            val request = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n").toByteArray(Charsets.US_ASCII)
            val destination = InetAddress.getByName("239.255.255.250")
            repeat(2) { socket.send(DatagramPacket(request, request.size, destination, 1900)) }
            val deadline = System.nanoTime() + 3_500_000_000L
            while (System.nanoTime() < deadline && locations.size < 16) {
                if (cancelled()) throw DownloadCancelledException()
                socket.soTimeout = minOf(400, ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1).toInt())
                val packet = DatagramPacket(ByteArray(8192), 8192)
                try {
                    socket.receive(packet)
                    val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val location = response.lineSequence().firstOrNull { it.substringBefore(':').equals("location", true) }
                        ?.substringAfter(':')?.trim() ?: continue
                    val uri = runCatching { URI(location) }.getOrNull() ?: continue
                    if (uri.scheme == "http" && uri.userInfo == null && uri.host == packet.address.hostAddress) locations.add(location)
                } catch (_: SocketTimeoutException) { }
            }
        }
        // A camera AP may suppress SSDP responses; this description path is also used by Sync.
        if (locations.isEmpty() && fallbackHost.isNotBlank() && !fallbackHost.contains('/') && !fallbackHost.contains(':')) {
            locations.add("http://$fallbackHost:60606/Lumix/Server0/ddd")
        }
        return locations.mapNotNull { location ->
            if (cancelled()) throw DownloadCancelledException()
            runCatching {
                val connection = network.openConnection(URL(location)) as HttpURLConnection
                try {
                    connection.connectTimeout = 2500; connection.readTimeout = 2500
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("User-Agent", "LUMIX Sync")
                    if (connection.responseCode != 200) null else {
                        val bytes = connection.inputStream.use { it.readBytesLimited(1024 * 1024) }
                        LumixProtocol.device(bytes.toString(Charsets.UTF_8), location)
                    }
                } finally { connection.disconnect() }
            }.getOrNull()
        }.distinctBy { it.udn }
    }
}

internal class LumixCameraClient(
    val device: LumixDevice,
    private val clientId: String,
    private val deviceName: String,
    private val openConnection: (URL) -> HttpURLConnection,
    private val isConnectionCancelled: () -> Boolean = { false },
    private val onProtocolEvent: (String) -> Unit = {},
) : CameraClient {
    private data class Directory(val id: String, var offset: Int = 0, var previousPage: String? = null)
    private data class Entry(val asset: PhotoAsset, val original: LumixResource, val thumbnail: LumixResource?)
    private val entries = mutableListOf<Entry>()
    private val byHandle = mutableMapOf<UInt, Entry>()
    private val handles = mutableMapOf<String, UInt>()
    private val directories = ArrayDeque<Directory>()
    private val seenDirectories = mutableSetOf<String>()
    private val requests = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    @Volatile private var closed = false
    private var connected = false
    private var sessionId: String? = null
    override val cameraName get() = device.name
    override val cameraDetails get() = device.details
    override val transport = ConnectionTransport.WIFI

    @Synchronized override fun connect() {
        check(!closed) { "相机连接已关闭" }
        var identity = clientId
        var name = deviceName
        if (device.encrypted) {
            val challenge = command("accctrl", "req_acc_g").trim().split(',')
            check(challenge.firstOrNull()?.trim()?.equals("ok", true) == true) { "无法获取松下授权密钥" }
            val key = challenge.getOrNull(1)?.trim()?.toLongOrNull(16)?.takeIf { it in 0..0xffffffffL }
                ?: throw IOException("松下返回的授权密钥无效")
            identity = LumixProtocol.encrypt(identity, key.toInt())
            name = LumixProtocol.encrypt(name, key.toInt())
        }
        val accessBody = command("accctrl", if (device.encrypted) "req_acc_e" else "req_acc", identity, name)
        val response = LumixProtocol.access(accessBody, device.name)
        val knownStatus = response.result.takeIf { it in setOf("ok", "err_busy", "err_reject", "err_param", "err_non_support", "wifi_reset") } ?: "other"
        onProtocolEvent("LUMIX authorization: encrypted=${device.encrypted}, format=${if (accessBody.trimStart().startsWith("<")) "XML" else "CSV"}, fields=${if (accessBody.trimStart().startsWith("<")) 0 else accessBody.trim().split(',').size}, result=$knownStatus, sessionPresent=${response.sessionId != null}")
        requireSuccess(response.result)
        sessionId = response.sessionId
        val naming = LumixProtocol.result(command("setsetting", "device_name", deviceName))
        // Naming is optional in Sync; some older cameras reject this setting with err_param.
        if (naming !in setOf("ok", "err_non_support", "err_param")) requireSuccess(naming)
        val raw = LumixProtocol.result(command("setsetting", "raw_img_send", "enable"))
        if (raw !in setOf("ok", "err_non_support", "err_param")) requireSuccess(raw)
        requireSuccess(LumixProtocol.result(command("getinfo", "capability")))
        onProtocolEvent("LUMIX capability check succeeded")
        connected = true
        refreshAssets()
    }

    private fun requireSuccess(result: String) {
        if (result == "ok") return
        throw IOException(when (result) {
            "err_reject" -> "松下相机拒绝连接，请在相机上确认授权，并关闭 LUMIX Sync 后重试"
            "err_busy" -> "松下相机正忙，请退出其他连接或传输后重试"
            "wifi_reset" -> "松下相机正在重启 Wi‑Fi，请重新连接热点"
            "err_non_support" -> "当前松下机型不支持此操作"
            else -> "松下相机未接受操作（$result）"
        })
    }

    private fun command(mode: String, type: String? = null, value: String? = null, value2: String? = null): String =
        request(LumixProtocol.cgi(device.cgiBase, mode, type, value, value2)) { connection ->
            connection.inputStream.use { it.readBytesLimited(2 * 1024 * 1024) }.toString(Charsets.UTF_8)
        }

    private fun <T> request(url: String, soap: String? = null, range: Int? = null, block: (HttpURLConnection) -> T): T {
        checkActive()
        val safeUrl = LumixProtocol.resolve(device.descriptionUrl, url)
        val connection = openConnection(URL(safeUrl))
        requests.add(connection)
        try {
            checkActive()
            connection.connectTimeout = 5000; connection.readTimeout = 8000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.setRequestProperty("User-Agent", if (soap == null) "LUMIX Sync" else "Panasonic MIL DLNA CP UPnP/1.0")
            sessionId?.let { connection.setRequestProperty("X-SESSION_ID", it) }
            range?.let { connection.setRequestProperty("Range", "bytes=0-${it - 1}") }
            if (soap != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                connection.setRequestProperty("SOAPAction", "\"${device.serviceType}#Browse\"")
                val bytes = soap.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status != 200 && !(range != null && status == 206)) throw IOException("松下相机请求失败（HTTP $status）")
            return block(connection)
        } finally { requests.remove(connection); connection.disconnect() }
    }

    private fun checkActive() {
        if (closed || isConnectionCancelled() || Thread.currentThread().isInterrupted) throw DownloadCancelledException()
    }

    @Synchronized override fun checkConnection(): Boolean = connected && !closed &&
        LumixProtocol.result(command("getstate")) == "ok"

    @Synchronized override fun refreshAssets() {
        entries.clear(); byHandle.clear(); directories.clear(); seenDirectories.clear()
        directories.add(Directory("0")); seenDirectories.add("0")
    }

    // ponytail: serialize camera reads; use separate control/media locks only if hardware testing warrants it.
    @Synchronized override fun assets(offset: Int, limit: Int): List<PhotoAsset> {
        require(offset >= 0 && limit in 1..250)
        check(connected && !closed) { "请先连接松下相机" }
        while (entries.size < offset + limit && directories.isNotEmpty()) {
            checkActive()
            val directory = directories.first()
            val count = (offset + limit - entries.size).coerceIn(1, 100)
            val soap = LumixProtocol.browseRequest(directory.id, directory.offset, count, device.serviceType)
            val page = request(device.controlUrl, soap) { connection ->
                val body = connection.inputStream.use { it.readBytesLimited(8 * 1024 * 1024) }.toString(Charsets.UTF_8)
                LumixProtocol.browse(body, device.controlUrl)
            }
            val signature = (page.items.map { it.id } + page.containers).joinToString("\u0000")
            if (page.returned > 0 && signature == directory.previousPage) throw IOException("松下相机重复返回同一页，请重新读取相册")
            directory.previousPage = signature
            if (page.returned == 0 || page.total?.let { directory.offset + page.returned >= it } == true ||
                (page.total == null && page.returned < count)) directories.removeFirst()
            directory.offset += page.returned
            page.containers.filter { seenDirectories.add(it) }.forEach { directories.add(Directory(it)) }
            for (item in page.items) {
                val thumbnail = item.resources.firstOrNull { it.profile == "CAM_LRGTN" }
                    ?: item.resources.firstOrNull { it.profile == "CAM_TN" }
                item.resources.filter { it.original }.distinctBy { it.url }.forEach { resource ->
                    val raw = resource.profile in setOf("CAM_RAW", "CAM_HSP_RAW") || URI(resource.url).path.substringAfterLast('.').lowercase() in setOf("rw2", "raw")
                    val extension = when {
                        raw -> "RW2"
                        resource.profile.startsWith("CAM_AVC_MOV_") -> "MOV"
                        resource.mime.startsWith("video/") || resource.profile.startsWith("CAM_AVC_") -> "MP4"
                        else -> "JPG"
                    }
                    val sourceName = resource.filename.ifBlank { URI(resource.url).path.substringAfterLast('/').takeIf { it.contains('.') } ?: item.title }
                    val filename = sourceName.substringAfterLast('/').substringAfterLast('\\')
                        .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").ifBlank { item.id.replace(Regex("[^a-zA-Z0-9_-]"), "_") }
                        .let { if (it.contains('.')) it else "$it.$extension" }
                    val key = "${item.id}|${resource.profile}|$filename"
                    val handle = handles.getOrPut(key) { (handles.size + 1).toUInt() }
                    if (handle !in byHandle) {
                        val timestamp = runCatching { Instant.parse(item.date).toEpochMilli() }.getOrElse {
                            runCatching { LocalDateTime.parse(item.date).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(0L)
                        }
                        val asset = PhotoAsset(handle, filename, resource.size, if (raw) 0x3000 else if (extension == "JPG") 0x3801 else 0,
                            Date(timestamp), resource.width, resource.height)
                        Entry(asset, resource, thumbnail).also { entries.add(it); byHandle[handle] = it }
                    }
                }
            }
        }
        return entries.subList(offset.coerceAtMost(entries.size), (offset + limit).coerceAtMost(entries.size)).map { it.asset }
    }

    @Synchronized override fun hasMoreAssets(offset: Int): Boolean = entries.size > offset || directories.isNotEmpty()

    private fun entry(asset: PhotoAsset): Entry = byHandle[asset.handle]?.takeIf { it.asset.name == asset.name }
        ?: throw IOException("照片列表已更新，请重新选择文件")

    @Synchronized override fun thumbnail(asset: PhotoAsset): ByteArray? = entry(asset).thumbnail?.let { thumb ->
        request(thumb.url) { connection -> connection.inputStream.use { it.readBytesLimited(4 * 1024 * 1024) } }
    }

    @Synchronized override fun imageHeader(asset: PhotoAsset): ByteArray? {
        if (asset.name.isVideoName()) return null
        return request(entry(asset).original.url, range = IMAGE_HEADER_SIZE) { connection ->
            // Cameras may ignore Range: cap the read locally in either case.
            connection.inputStream.use { it.readAtMost(IMAGE_HEADER_SIZE) }
        }
    }

    @Synchronized override fun download(asset: PhotoAsset, progress: (Long, Long) -> Unit): ByteArray =
        ByteArrayOutputStream().also { downloadTo(asset, it, progress) }.toByteArray()

    @Synchronized override fun downloadTo(asset: PhotoAsset, output: OutputStream, progress: (Long, Long) -> Unit, isCancelled: () -> Boolean): Long {
        if (isCancelled()) throw DownloadCancelledException()
        return request(entry(asset).original.url) { connection ->
            if (connection.contentType.orEmpty().let { it.contains("xml", true) || it.contains("json", true) || it.startsWith("text/", true) }) {
                throw IOException("松下相机返回错误页面，请重新连接后下载")
            }
            val total = connection.getHeaderField("X-FILE_SIZE")?.toLongOrNull()?.takeIf { it > 0 }
                ?: connection.contentLengthLong.takeIf { it > 0 } ?: asset.size
            var done = 0L
            progress(0, total)
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkActive()
                    if (isCancelled()) throw DownloadCancelledException()
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (isCancelled()) throw DownloadCancelledException()
                    output.write(buffer, 0, count); done += count
                    progress(done, total)
                }
            }
            if (done == 0L || (total > 0 && done != total) || (asset.size > 0 && done != asset.size)) throw IOException("松下文件传输不完整，请重试下载")
            done
        }
    }

    override fun close() {
        closed = true; connected = false; sessionId = null
        requests.forEach { it.disconnect() }
    }
}

private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count == -1) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val bytes = readAtMost(limit + 1)
    if (bytes.size > limit) throw IOException("松下相机响应过大")
    return bytes
}

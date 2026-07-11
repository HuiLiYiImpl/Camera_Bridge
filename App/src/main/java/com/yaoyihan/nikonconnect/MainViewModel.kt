package com.yaoyihan.nikonconnect

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = AppStore(application)
    private var client: PtpIpClient? = null
    val config = MutableStateFlow(store.config())
    val workflow = MutableStateFlow(Workflow.WAITING)
    val session = MutableStateFlow<CameraSession?>(null)
    val photos = MutableStateFlow<List<PhotoAsset>>(emptyList())
    val downloads = MutableStateFlow(store.downloads())
    val notice = MutableStateFlow<String?>(null)
    val isBusy = MutableStateFlow(false)
    val hasMorePhotos = MutableStateFlow(false)
    val thumbnails = mutableStateMapOf<UInt, Bitmap?>()
    private val pageSize = 30

    fun updateConfig(transform: (CameraConfig) -> CameraConfig) {
        config.update(transform); store.save(config.value)
    }

    fun connect(manual: Boolean = false) = viewModelScope.launch {
        val target = config.value
        val network = cameraNetwork()
        if (network == null) {
            if (manual) openWifiSettings() else showError("请先连接相机 Wi‑Fi 后再继续")
            return@launch
        }
        val host = cameraGateway().ifBlank { target.host.trim() }
        if (target.host.isBlank() || target.port !in 1..65535) { showError("请填写有效的相机地址和端口"); return@launch }
        isBusy.value = true; workflow.value = Workflow.CONNECTING; notice.value = "正在连接 ${target.host}:${target.port}"
        runCatching {
            withContext(Dispatchers.IO) {
                client?.close(); PtpIpClient(host, target.port, network.socketFactory).also { it.connect(); client = it }
            }
        }.onSuccess { ptp ->
            session.value = CameraSession(ptp.cameraName, host, target.port)
            keepConnectionAlive(true)
            workflow.value = Workflow.CONNECTED; notice.value = "已连接 ${ptp.cameraName}"
            currentSsid()?.let { ssid -> if (ssid.isNotBlank()) updateConfig { c -> c.copy(lastSsid = ssid) } }
            loadPhotos()
        }.onFailure {
            workflow.value = Workflow.WAITING
            if (manual) openWifiSettings() else showError(it.message ?: "无法连接相机，请确认已连接相机 Wi‑Fi")
        }
        isBusy.value = false
    }

    private fun openWifiSettings() {
        val app = getApplication<Application>()
        app.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun disconnect() = viewModelScope.launch(Dispatchers.IO) {
        keepConnectionAlive(false)
        client?.close(); client = null; hasMorePhotos.value = false
        withContext(Dispatchers.Main) { session.value = null; photos.value = emptyList(); thumbnails.clear(); workflow.value = Workflow.WAITING; notice.value = "连接已断开" }
    }

    fun loadPhotos() = viewModelScope.launch {
        val active = client ?: run { showError("请先连接相机"); return@launch }
        isBusy.value = true; workflow.value = Workflow.LOADING; notice.value = "正在读取相册"
        runCatching { withContext(Dispatchers.IO) { active.refreshAssets(); active.assets(limit = pageSize) } }
            .onSuccess { result ->
                photos.value = if (config.value.jpegFirst) result.sortedBy { it.type != "JPEG" } else result
                hasMorePhotos.value = active.hasMoreAssets(photos.value.size)
                workflow.value = Workflow.CONNECTED; notice.value = "已读取 ${result.size} 张文件"
            }.onFailure { showError(it.message ?: "读取相册失败") }
        isBusy.value = false
    }

    fun loadMorePhotos() = viewModelScope.launch {
        if (isBusy.value || !hasMorePhotos.value) return@launch
        val active = client ?: return@launch
        isBusy.value = true
        runCatching { withContext(Dispatchers.IO) { active.assets(photos.value.size, pageSize) } }
            .onSuccess { page ->
                val merged = photos.value + page
                photos.value = if (config.value.jpegFirst) merged.sortedBy { it.type != "JPEG" } else merged
                hasMorePhotos.value = active.hasMoreAssets(merged.size)
            }
            .onFailure { showError(it.message ?: "继续读取照片失败") }
        isBusy.value = false
    }

    fun loadThumbnail(asset: PhotoAsset) {
        if (thumbnails.containsKey(asset.handle)) return
        thumbnails[asset.handle] = null
        viewModelScope.launch {
            val data = runCatching { withContext(Dispatchers.IO) { client?.thumbnail(asset) } }.getOrNull()
            val bitmap = data?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            thumbnails[asset.handle] = bitmap
        }
    }

    suspend fun loadOriginalPreview(asset: PhotoAsset): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = client?.download(asset) { _, _ -> } ?: return@runCatching null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    suspend fun loadLocalOriginal(uri: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
                BitmapFactory.decodeStream(it)
            }
        }.getOrNull()
    }

    fun download(asset: PhotoAsset) = viewModelScope.launch {
        val active = client ?: run { showError("相机连接已断开"); return@launch }
        isBusy.value = true; workflow.value = Workflow.DOWNLOADING; notice.value = "正在下载 ${asset.name}"
        runCatching {
            withContext(Dispatchers.IO) {
                saveToMediaStore(asset, config.value.autoExport) { stream -> active.downloadTo(asset, stream) { done, total ->
                    viewModelScope.launch { notice.value = "正在下载 ${asset.name} · ${done.prettySize()} / ${total.prettySize()}" }
                } }
            }
        }.onSuccess { record ->
            store.addDownload(record); downloads.value = store.downloads(); workflow.value = Workflow.CONNECTED; notice.value = "已保存到系统相册"
        }.onFailure { showError(it.message ?: "下载失败") }
        isBusy.value = false
    }

    fun downloadAll(assets: List<PhotoAsset>) = viewModelScope.launch {
        val active = client ?: run { showError("相机连接已断开"); return@launch }
        if (assets.isEmpty()) return@launch
        isBusy.value = true
        workflow.value = Workflow.DOWNLOADING
        for ((index, asset) in assets.withIndex()) {
            val record = runCatching {
                withContext(Dispatchers.IO) {
                    saveToMediaStore(asset, config.value.autoExport) { stream ->
                        active.downloadTo(asset, stream) { done, total ->
                            viewModelScope.launch { notice.value = "正在下载 ${index + 1}/${assets.size}：${done.prettySize()} / ${total.prettySize()}" }
                        }
                    }
                }
            }.getOrElse { error ->
                showError(error.message ?: "下载失败")
                isBusy.value = false
                return@launch
            }
            store.addDownload(record)
        }
        downloads.value = store.downloads()
        workflow.value = Workflow.CONNECTED
        notice.value = "已下载 ${assets.size} 张照片"
        isBusy.value = false
    }

    private fun saveToMediaStore(asset: PhotoAsset, exportToGallery: Boolean, write: (java.io.OutputStream) -> Long): DownloadRecord {
        val resolver = getApplication<Application>().contentResolver
        val mime = when (asset.name.substringAfterLast('.', "").lowercase()) { "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "mov" -> "video/quicktime"; "mp4" -> "video/mp4"; else -> "application/octet-stream" }
        val isImage = mime.startsWith("image/")
        val collection = if (exportToGallery && isImage) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val folder = if (exportToGallery && isImage) "${Environment.DIRECTORY_PICTURES}/Nikon Connect" else "${Environment.DIRECTORY_DOWNLOADS}/Nikon Connect"
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, asset.name); put(MediaStore.MediaColumns.MIME_TYPE, mime); put(MediaStore.MediaColumns.RELATIVE_PATH, folder); put(MediaStore.MediaColumns.IS_PENDING, 1) }
        val uri = requireNotNull(resolver.insert(collection, values)) { "无法创建保存文件" }
        var size = 0L
        try { resolver.openOutputStream(uri)?.use { size = write(it) } ?: error("无法写入系统相册"); values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0); resolver.update(uri, values, null, null) } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
        return DownloadRecord(asset.name, size, uri.toString(), Date().time)
    }

    private fun showError(message: String) { workflow.value = Workflow.ERROR; notice.value = message }
    override fun onCleared() { keepConnectionAlive(false); client?.close() }

    private fun keepConnectionAlive(enabled: Boolean) {
        val app = getApplication<Application>()
        val intent = Intent(app, ConnectionKeepAliveService::class.java)
        if (enabled) ContextCompat.startForegroundService(app, intent) else app.stopService(intent)
    }

    private fun cameraNetwork() = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        .allNetworks.firstOrNull { network ->
            getApplication<Application>().getSystemService(ConnectivityManager::class.java)
                .getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

    @Suppress("DEPRECATION")
    private fun cameraGateway(): String {
        val address = getApplication<Application>().getSystemService(WifiManager::class.java).dhcpInfo?.serverAddress ?: return ""
        if (address == 0) return ""
        return listOf(address and 0xFF, address shr 8 and 0xFF, address shr 16 and 0xFF, address shr 24 and 0xFF).joinToString(".")
    }

    fun currentSsid(): String? {
        val app = getApplication<Application>()
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val network = cameraNetwork() ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        // ponytail: read SSID from WifiInfo (API 31+: via transportInfo to avoid deprecated allNetworks info path)
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) caps.transportInfo as? android.net.wifi.WifiInfo else null
        @Suppress("DEPRECATION")
        val ssid = info?.ssid ?: app.getSystemService(WifiManager::class.java).connectionInfo?.ssid
        return ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    fun hasCameraWifi(): Boolean {
        val remembered = config.value.lastSsid
        if (remembered.isBlank()) return false
        return currentSsid() == remembered
    }
}

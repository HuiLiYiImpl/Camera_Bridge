package com.yaoyihan.nikonconnect

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val luts = MutableStateFlow(store.luts())
    val recentLutIds = MutableStateFlow(store.recentLuts())
    val notice = MutableStateFlow<String?>(null)
    val snackbar = MutableStateFlow<String?>(null)
    val pageTask = MutableStateFlow<PageTask?>(null)
    val isBusy = MutableStateFlow(false)
    val hasMorePhotos = MutableStateFlow(false)
    val hasLoadedPhotos = MutableStateFlow(false)
    val thumbnails = mutableStateMapOf<UInt, Bitmap?>()
    private var lastFailedAsset: PhotoAsset? = null
    private var noticeClearJob: Job? = null
    private var snackbarClearJob: Job? = null
    private var pageTaskClearJob: Job? = null
    private var pageRetry: (() -> Unit)? = null
    private val pageSize = 30

    private fun showTransient(message: String) {
        noticeClearJob?.cancel(); notice.value = message
        noticeClearJob = viewModelScope.launch { delay(3_000); if (notice.value == message) notice.value = null }
    }

    private fun showSnackbar(message: String) {
        snackbarClearJob?.cancel(); snackbar.value = message
        snackbarClearJob = viewModelScope.launch { delay(3_000); if (snackbar.value == message) snackbar.value = null }
    }

    private fun showPageProgress(message: String) {
        pageTaskClearJob?.cancel(); pageTask.value = PageTask(message, running = true); noticeClearJob?.cancel(); notice.value = null
    }

    private fun showPageSuccess(message: String) {
        pageRetry = null; pageTask.value = PageTask(message, running = false)
        pageTaskClearJob?.cancel(); pageTaskClearJob = viewModelScope.launch { delay(3_000); if (pageTask.value?.message == message) pageTask.value = null }
    }

    private fun showPageError(message: String) {
        pageTaskClearJob?.cancel(); pageTask.value = PageTask(message, running = false, failed = true); workflow.value = Workflow.ERROR; noticeClearJob?.cancel(); notice.value = null
    }

    fun retryPageTask() { pageRetry?.invoke() }

    fun updateConfig(transform: (CameraConfig) -> CameraConfig) {
        config.update(transform); store.save(config.value)
    }

    fun markLutUsed(entry: LutEntry) {
        val updated = (listOf(entry.id) + recentLutIds.value.filterNot { it == entry.id }).take(5)
        recentLutIds.value = updated
        store.saveRecentLuts(updated)
    }

    fun retry() { lastFailedAsset?.let(::download) ?: connect(false) }

    fun checkConnectionOnResume() = viewModelScope.launch {
        val current = session.value ?: return@launch
        val network = cameraNetwork()
        val active = client
        val connected = network != null && active != null && withContext(Dispatchers.IO) { runCatching { active.checkConnection() }.getOrDefault(false) }
        if (connected) {
            workflow.value = Workflow.CONNECTED
            notice.value = "已连接 ${current.name} · 已读取 ${photos.value.size} 张"
        } else {
            keepConnectionAlive(false)
            client?.close(); client = null; bindCameraNetwork(null)
            session.value = null; photos.value = emptyList(); thumbnails.clear(); hasMorePhotos.value = false; hasLoadedPhotos.value = false
            workflow.value = Workflow.ERROR; notice.value = "相机连接已断开"
        }
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
        bindCameraNetwork(network)
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
            bindCameraNetwork(null)
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
        client?.close(); client = null; bindCameraNetwork(null); hasMorePhotos.value = false; hasLoadedPhotos.value = false
        withContext(Dispatchers.Main) { session.value = null; photos.value = emptyList(); thumbnails.clear(); workflow.value = Workflow.WAITING; notice.value = "连接已断开" }
    }

    fun loadPhotos() = viewModelScope.launch {
        val active = client ?: run { showError("请先连接相机"); return@launch }
        isBusy.value = true; workflow.value = Workflow.LOADING; hasLoadedPhotos.value = false; notice.value = "正在读取相册（首批 30 张）"
        runCatching { withContext(Dispatchers.IO) { active.refreshAssets(); active.assets(limit = pageSize) } }
            .onSuccess { result ->
                photos.value = result
                hasMorePhotos.value = active.hasMoreAssets(photos.value.size)
                hasLoadedPhotos.value = true
                workflow.value = Workflow.CONNECTED; notice.value = "已连接 ${session.value?.name ?: "相机"} · 已读取 ${result.size} 张"
            }.onFailure { showError(it.message ?: "读取相册失败") }
        isBusy.value = false
    }

    fun loadMorePhotos() = viewModelScope.launch {
        if (isBusy.value || !hasMorePhotos.value) return@launch
        val active = client ?: return@launch
        isBusy.value = true; notice.value = "正在加载更多"
        runCatching { withContext(Dispatchers.IO) { active.assets(photos.value.size, 15) } }
            .onSuccess { page ->
                val merged = photos.value + page
                photos.value = merged
                hasMorePhotos.value = active.hasMoreAssets(merged.size)
                notice.value = if (hasMorePhotos.value) "已读取 ${merged.size} 张" else "已全部加载"
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

    fun importLut(uri: Uri) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val source = app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Unable to read LUT file")
                val lut = CubeLuts.parse(uri.lastPathSegment ?: "LUT", source)
                val id = "${System.currentTimeMillis()}_${lut.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.cube"
                app.filesDir.resolve("luts").apply { mkdirs() }.resolve(id).writeText(source)
                LutEntry(id, lut.name) to lut
            }
        }.onSuccess { (entry, _) ->
            luts.value = luts.value + entry
            store.saveLuts(luts.value)
            showSnackbar("已导入 LUT：${entry.name}")
        }.onFailure { showError(it.message ?: "导入 LUT 失败") }
    }

    suspend fun readLut(entry: LutEntry): CubeLut = withContext(Dispatchers.IO) {
        val source = getApplication<Application>().filesDir.resolve("luts").resolve(entry.id).readText()
        CubeLuts.parse(entry.name, source)
    }

    fun removeLut(entry: LutEntry) {
        getApplication<Application>().filesDir.resolve("luts").resolve(entry.id).delete()
        luts.value = luts.value.filterNot { it.id == entry.id }
        store.saveLuts(luts.value)
        recentLutIds.value = recentLutIds.value.filterNot { it == entry.id }
        store.saveRecentLuts(recentLutIds.value)
        showSnackbar("已删除 LUT")
    }

    fun updateLut(entry: LutEntry, name: String = entry.name, category: LutCategory = entry.category) {
        val updated = entry.copy(name = name.trim().ifBlank { entry.name }, category = category)
        luts.value = luts.value.map { if (it.id == entry.id) updated else it }
        store.saveLuts(luts.value)
        when {
            updated.name != entry.name -> showSnackbar("已重命名 LUT")
            updated.category != entry.category -> showSnackbar("已修改 LUT 分类")
        }
    }

    suspend fun loadLut(uri: Uri): Result<CubeLut> = withContext(Dispatchers.IO) {
        runCatching {
            val source = getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("无法读取 LUT 文件")
            CubeLuts.parse(uri.lastPathSegment ?: "LUT", source)
        }
    }

    fun exportLut(asset: PhotoAsset, bitmap: Bitmap, lut: CubeLut, inline: Boolean = false, onFinished: (Result<DownloadRecord>) -> Unit = {}) = viewModelScope.launch {
        isBusy.value = true; workflow.value = Workflow.DOWNLOADING
        if (!inline) notice.value = "正在导出 ${asset.name}"
        updateExportNotif(asset.name, 0, 0)
        val result = runCatching { withContext(Dispatchers.IO) { saveLutBitmap(asset, bitmap, lut.name) } }
        result.onSuccess { record -> store.addDownload(record); downloads.value = store.downloads(); workflow.value = Workflow.CONNECTED; if (!inline) showTransient("已导出套 LUT 图片") }
            .onFailure { if (inline) workflow.value = Workflow.CONNECTED else showError(it.message ?: "导出 LUT 图片失败") }
        clearExportNotif(); isBusy.value = false; onFinished(result)
    }

    fun exportLutDownload(record: DownloadRecord, bitmap: Bitmap, lut: CubeLut, inline: Boolean = false, onFinished: (Result<DownloadRecord>) -> Unit = {}) = viewModelScope.launch {
        isBusy.value = true; workflow.value = Workflow.DOWNLOADING
        if (!inline) notice.value = "正在导出 ${record.name}"
        updateExportNotif(record.name, 0, 0)
        val result = runCatching { withContext(Dispatchers.IO) { saveLutBitmap(PhotoAsset(0u, record.name, record.size, 0x3801), bitmap, lut.name) } }
        result.onSuccess { output -> store.addDownload(output); downloads.value = store.downloads(); workflow.value = Workflow.CONNECTED; if (!inline) showTransient("已导出套 LUT 图片") }
            .onFailure { if (inline) workflow.value = Workflow.CONNECTED else showError(it.message ?: "导出 LUT 图片失败") }
        clearExportNotif(); isBusy.value = false; onFinished(result)
    }

    fun downloadAllWithLut(assets: List<PhotoAsset>, lut: CubeLut, onFinished: () -> Unit = {}): Job = viewModelScope.launch {
        val retry: () -> Unit = { downloadAllWithLut(assets, lut, onFinished); Unit }
        pageRetry = retry
        val active = client ?: run { showPageError("套 LUT 失败：相机连接已断开"); return@launch }
        isBusy.value = true; workflow.value = Workflow.DOWNLOADING
        showPageProgress("正在套用 LUT 0 / ${assets.size}")
        var completed = 0
        var skipped = 0
        assets.forEachIndexed { index, asset ->
            if (!asset.name.supportsLutInput()) {
                skipped++
            } else runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = active.download(asset) { _, _ -> }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("无法解码 ${asset.name}")
                    saveLutBitmap(asset, CubeLuts.apply(bitmap, lut), lut.name)
                }
            }.onSuccess { store.addDownload(it); completed++ }
                .onFailure { skipped++ }
            showPageProgress("正在套用 LUT ${index + 1} / ${assets.size}")
            updateExportNotif(asset.name, index + 1, assets.size)
        }
        downloads.value = store.downloads(); workflow.value = Workflow.CONNECTED
        clearExportNotif()
        showPageSuccess(if (skipped == 0) "已导出 $completed 张套 LUT 图片" else "已导出 $completed 张，跳过 $skipped 个文件")
        isBusy.value = false
        onFinished()
    }

    fun applyLutToDownloads(records: List<DownloadRecord>, lut: CubeLut, onFinished: () -> Unit = {}): Job = viewModelScope.launch {
        val retry: () -> Unit = { applyLutToDownloads(records, lut, onFinished); Unit }
        pageRetry = retry
        isBusy.value = true; workflow.value = Workflow.DOWNLOADING
        showPageProgress("正在套用 LUT 0 / ${records.size}")
        var completed = 0
        var skipped = 0
        records.forEachIndexed { index, record ->
            if (!record.name.supportsLutInput()) {
                skipped++
            } else runCatching {
                withContext(Dispatchers.IO) {
                    val bitmap = getApplication<Application>().contentResolver.openInputStream(Uri.parse(record.uri))?.use { BitmapFactory.decodeStream(it) } ?: error("无法解码 ${record.name}")
                    saveLutBitmap(PhotoAsset(0u, record.name, record.size, 0x3801), CubeLuts.apply(bitmap, lut), lut.name)
                }
            }.onSuccess { store.addDownload(it); completed++ }
                .onFailure { skipped++ }
            showPageProgress("正在套用 LUT ${index + 1} / ${records.size}")
            updateExportNotif(record.name, index + 1, records.size)
        }
        downloads.value = store.downloads(); workflow.value = Workflow.CONNECTED
        clearExportNotif()
        showPageSuccess("已导出 $completed 张，跳过 $skipped 个不支持的文件。")
        isBusy.value = false
        onFinished()
    }

    suspend fun loadLocalOriginal(uri: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
                BitmapFactory.decodeStream(it)
            }
        }.getOrNull()
    }

    fun download(asset: PhotoAsset, inline: Boolean = false, onFinished: (Result<DownloadRecord>) -> Unit = {}) = viewModelScope.launch {
        val active = client ?: run {
            val error = IllegalStateException("相机连接已断开")
            if (!inline) showError(error.message!!)
            onFinished(Result.failure(error)); return@launch
        }
        isBusy.value = true; workflow.value = Workflow.DOWNLOADING; if (!inline) notice.value = "正在下载 ${asset.name}"
        val result = runCatching {
            withContext(Dispatchers.IO) {
                saveToMediaStore(asset, config.value.autoExport) { stream -> active.downloadTo(asset, stream) { done, total ->
                    viewModelScope.launch { if (!inline) notice.value = "正在下载 ${asset.name} · ${done.prettySize()} / ${total.prettySize()}"; updateDownloadNotif(asset.name, done, total) }
                } }
            }
        }
        result.onSuccess { record ->
            store.addDownload(record); downloads.value = store.downloads(); workflow.value = Workflow.CONNECTED; if (!inline) showTransient("已保存到系统相册"); lastFailedAsset = null; clearDownloadNotif()
        }.onFailure { error -> lastFailedAsset = asset; if (inline) workflow.value = Workflow.CONNECTED else showError(error.message ?: "下载失败"); clearDownloadNotif() }
        isBusy.value = false; onFinished(result)
    }

    fun retryDownload() { lastFailedAsset?.let { download(it) } }

    fun downloadAll(assets: List<PhotoAsset>): Job = viewModelScope.launch {
        val retry: () -> Unit = { downloadAll(assets); Unit }
        pageRetry = retry
        val active = client ?: run { showPageError("下载失败：相机连接已断开"); return@launch }
        if (assets.isEmpty()) return@launch
        isBusy.value = true
        workflow.value = Workflow.DOWNLOADING
        showPageProgress("正在下载 0 / ${assets.size}")
        for ((index, asset) in assets.withIndex()) {
            val record = runCatching {
                withContext(Dispatchers.IO) {
                    saveToMediaStore(asset, config.value.autoExport) { stream ->
                        active.downloadTo(asset, stream) { done, total ->
                            viewModelScope.launch { showPageProgress("正在下载 ${index + 1} / ${assets.size}"); updateDownloadNotif("${index + 1}/${assets.size} ${asset.name}", done, total) }
                        }
                    }
                }
            }.getOrElse { error ->
                showPageError("下载失败：${error.message ?: "未知错误"}")
                isBusy.value = false
                clearDownloadNotif()
                return@launch
            }
            store.addDownload(record)
            showPageProgress("正在下载 ${index + 1} / ${assets.size}")
        }
        downloads.value = store.downloads()
        workflow.value = Workflow.CONNECTED
        showPageSuccess("已下载 ${assets.size} 张照片")
        clearDownloadNotif()
        isBusy.value = false
    }

    fun deleteDownloads(uris: Set<String>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        showPageProgress("正在删除 ${uris.size} 个文件")
        val resolver = getApplication<Application>().contentResolver
        withContext(Dispatchers.IO) {
            uris.forEach { uri -> runCatching { resolver.delete(android.net.Uri.parse(uri), null, null) } }
        }
        store.removeDownloads(uris)
        downloads.value = store.downloads()
        showPageSuccess("已删除 ${uris.size} 个文件")
        showSnackbar("已删除 ${uris.size} 个文件")
    }

    private fun saveToMediaStore(asset: PhotoAsset, exportToGallery: Boolean, write: (java.io.OutputStream) -> Long): DownloadRecord {
        val resolver = getApplication<Application>().contentResolver
        val mime = when (asset.name.substringAfterLast('.', "").lowercase()) { "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "mov" -> "video/quicktime"; "mp4" -> "video/mp4"; else -> "application/octet-stream" }
        val isImage = mime.startsWith("image/")
        val collection = if (exportToGallery && isImage) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val folder = if (exportToGallery && isImage) "${Environment.DIRECTORY_PICTURES}/Nikon Connect" else "${Environment.DIRECTORY_DOWNLOADS}/Nikon Connect"
        val displayName = uniqueDisplayName(resolver, collection, folder, asset.name)
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, displayName); put(MediaStore.MediaColumns.MIME_TYPE, mime); put(MediaStore.MediaColumns.RELATIVE_PATH, folder); put(MediaStore.MediaColumns.IS_PENDING, 1) }
        val uri = requireNotNull(resolver.insert(collection, values)) { "无法创建保存文件" }
        var size = 0L
        try { resolver.openOutputStream(uri)?.use { size = write(it) } ?: error("无法写入系统相册"); values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0); resolver.update(uri, values, null, null) } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
        return DownloadRecord(displayName, size, uri.toString(), Date().time)
    }

    private fun uniqueDisplayName(resolver: android.content.ContentResolver, collection: Uri, folder: String, requested: String): String {
        val existing = buildSet {
            runCatching {
                resolver.query(collection, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(folder), null)?.use { cursor ->
                    val index = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (index >= 0 && cursor.moveToNext()) add(cursor.getString(index))
                }
            }
        }
        if (requested !in existing) return requested
        val dot = requested.lastIndexOf('.')
        val stem = if (dot > 0) requested.substring(0, dot) else requested
        val suffix = if (dot > 0) requested.substring(dot) else ""
        var index = 1
        while ("${stem}_${index.toString().padStart(2, '0')}$suffix" in existing) index++
        return "${stem}_${index.toString().padStart(2, '0')}$suffix"
    }

    private fun saveLutBitmap(asset: PhotoAsset, bitmap: Bitmap, lutName: String): DownloadRecord {
        val output = asset.copy(name = "${asset.name.substringBeforeLast('.')}_${lutName.replace(Regex("[^A-Za-z0-9_-]"), "_")}.jpg")
        return saveToMediaStore(output, true) { stream ->
            require(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) { "无法编码 JPG" }
            output.size
        }
    }

    private fun showError(message: String) { noticeClearJob?.cancel(); workflow.value = Workflow.ERROR; notice.value = message }
    override fun onCleared() { keepConnectionAlive(false); client?.close(); bindCameraNetwork(null); clearDownloadNotif(); clearExportNotif() }

    private fun keepConnectionAlive(enabled: Boolean) {
        val app = getApplication<Application>()
        val intent = Intent(app, ConnectionKeepAliveService::class.java)
        if (enabled) ContextCompat.startForegroundService(app, intent) else app.stopService(intent)
    }

    private fun updateDownloadNotif(name: String, done: Long, total: Long) {
        val app = getApplication<Application>()
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        val notif = androidx.core.app.NotificationCompat.Builder(app, "camera_connection")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在下载 $name")
            .setContentText("${done.prettySize()} / ${total.prettySize()} · $percent%")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        app.getSystemService(android.app.NotificationManager::class.java).notify(1002, notif)
    }

    private fun clearDownloadNotif() {
        getApplication<Application>().getSystemService(android.app.NotificationManager::class.java).cancel(1002)
    }

    private fun updateExportNotif(name: String, done: Int, total: Int) {
        val app = getApplication<Application>()
        val determinate = total > 0
        val percent = if (determinate) (done * 100 / total).coerceIn(0, 100) else 0
        val notif = androidx.core.app.NotificationCompat.Builder(app, "camera_connection")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在导出套色图")
            .setContentText(if (determinate) "$name · $done / $total" else name)
            .setProgress(100, percent, !determinate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        app.getSystemService(android.app.NotificationManager::class.java).notify(1003, notif)
    }

    private fun clearExportNotif() {
        getApplication<Application>().getSystemService(android.app.NotificationManager::class.java).cancel(1003)
    }

    private fun cameraNetwork(): android.net.Network? = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        .allNetworks.firstOrNull { network ->
            getApplication<Application>().getSystemService(ConnectivityManager::class.java)
                .getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

    private fun bindCameraNetwork(network: Network?) {
        getApplication<Application>().getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(network)
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
}

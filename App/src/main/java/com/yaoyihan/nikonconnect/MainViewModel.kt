package com.yaoyihan.nikonconnect

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.IntentFilter
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.net.SocketTimeoutException
import kotlin.math.roundToInt
import com.yaoyihan.nikonconnect.lut.LutCache
import com.yaoyihan.nikonconnect.lut.LutBinaryCodec
import com.yaoyihan.nikonconnect.lut.VideoLutExporter

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = AppStore(application)
    private val lutCache = LutCache(8)
    private val diagnostics = DiagnosticLogger(application)
    private var client: CameraClient? = null
    private var usbDevice: UsbDevice? = null
    private var cameraNetworkBound = false
    private var requestedCameraNetwork: Network? = null
    private var requestedWifiCallback: ConnectivityManager.NetworkCallback? = null
    private var lastDiagnosticProgressAt = 0L
    val config = MutableStateFlow(store.config())
    val workflow = MutableStateFlow(Workflow.WAITING)
    val session = MutableStateFlow<CameraSession?>(null)
    val photos = MutableStateFlow<List<PhotoAsset>>(emptyList())
    val downloads = MutableStateFlow(store.downloads())
    val luts = MutableStateFlow(store.luts())
    val recentLutIds = MutableStateFlow(store.recentLuts())
    val watermarks = MutableStateFlow(store.watermarks())
    val diagnosticState = diagnostics.state
    val usbState = MutableStateFlow(UsbConnectionState())
    val notice = MutableStateFlow<String?>(null)
    val snackbar = MutableStateFlow<String?>(null)
    val pageTask = MutableStateFlow<PageTask?>(null)
    val isBusy = MutableStateFlow(false)
    val remoteCapture = MutableStateFlow(RemoteCaptureState())
    val remoteSupported = MutableStateFlow(false)
    val remoteLiveView = MutableStateFlow(RemoteLiveViewState())
    val remoteParameterEditor = MutableStateFlow(RemoteParameterEditor())
    private val remoteColorController = RemotePreviewColorController(viewModelScope,
        (application.getSystemService(android.app.ActivityManager::class.java)?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= 0x30000,
        ::readLut)
    val remotePreviewColor = remoteColorController.state
    val remoteFocus = MutableStateFlow(RemoteFocusState())
    private var remoteFocusJob: Job? = null
    private val remoteFocusGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private var remoteParameterJob: Job? = null
    private val remotePreviewMutex = Mutex()
    private var remotePreviewJob: Job? = null
    private var remotePreviewClient: CameraClient? = null
    private var remoteViewVisible = false
    private var remoteForeground = true
    private var remoteCaptureJob: Job? = null
    private var remoteCancellation = java.util.concurrent.atomic.AtomicBoolean(false)
    val hasMorePhotos = MutableStateFlow(false)
    val hasLoadedPhotos = MutableStateFlow(false)
    val thumbnails = mutableStateMapOf<UInt, Bitmap?>()
    private val cameraThumbnailCache = BitmapMemoryCache<UInt> { thumbnails.remove(it) }
    private val localThumbnailCache = BitmapMemoryCache<String>()
    private val thumbnailLoads = mutableSetOf<String>()
    private val thumbnailDiskCache = ThumbnailDiskCache(application.cacheDir.resolve("camera_thumbnails_v1"))
    val thumbnailCacheBytes = MutableStateFlow(0L)
    val clearingThumbnailCache = MutableStateFlow(false)
    val thumbnailVersion = MutableStateFlow(0)
    val isLoadingMorePhotos = MutableStateFlow(false)
    private var loadMoreJob: Job? = null
    private var metadataJob: Job? = null
    private val editMutex = Mutex()
    private var batchEditJob: Job? = null
    private var photoGeneration = 0
    private val queuedOriginals = mutableMapOf<String, ByteArray>()
    private val _downloadTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloadTasks = _downloadTasks.asStateFlow()
    private val downloadLock = Any()
    private val cancelledDownloadIds = mutableSetOf<String>()
    private var downloadWorkerJob: Job? = null
    private var lastFailedAsset: PhotoAsset? = null
    private var noticeClearJob: Job? = null
    private var snackbarClearJob: Job? = null
    private var pageTaskClearJob: Job? = null
    private var pageRetry: (() -> Unit)? = null
    private var photoLoadJob: Job? = null
    private var usbConnectJob: Job? = null
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            when (intent.action) {
                USB_PERMISSION_ACTION -> {
                    val device = intent.usbDevice() ?: return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    logDiagnostic(
                        event = if (granted) "USB_PERMISSION_GRANTED" else "USB_PERMISSION_DENIED",
                        phase = "USB_PERMISSION",
                        transport = ConnectionTransport.USB,
                        level = if (granted) "INFO" else "WARN",
                        result = if (granted) "SUCCESS" else "DENIED",
                        message = if (granted) "USB permission granted" else "USB permission denied",
                        device = device,
                        permissionGranted = granted,
                    )
                    if (granted) connectUsbDevice(device)
                    else usbState.value = usbState.value.copy(message = "未获得 USB 访问权限")
                }
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.usbDevice()
                    logDiagnostic("USB_DEVICE_ATTACHED", "USB_DETECTED", ConnectionTransport.USB, message = "USB device detected", device = device)
                    detectUsbCamera()
                }
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.usbDevice()
                    logDiagnostic("USB_DEVICE_DETACHED", "USB_DETACHED", ConnectionTransport.USB, level = "WARN", result = "DISCONNECTED", message = "USB device detached", device = device, permissionGranted = false)
                    diagnostics.recordUsbConnectionState(false, diagnosticUsbSnapshot(device ?: usbDevice, false, false))
                    if (device?.deviceName == usbDevice?.deviceName) handleUsbDisconnect()
                    else detectUsbCamera()
                }
            }
        }
    }

    init {
        refreshThumbnailCacheSize()
        registerUsbReceiver()
        logDiagnostic("APP_STARTED", "APP_READY", message = "Link started")
        if (store.consumeInterruptedDownload()) pageTask.value = PageTask("上次下载因应用关闭而中断，请重新连接原相机后创建任务", running = false, failed = true)
        viewModelScope.launch(Dispatchers.IO) { cleanupInterruptedFiles() }
        detectUsbCamera()
    }

    private fun logDiagnostic(
        event: String,
        phase: String,
        transport: ConnectionTransport? = session.value?.transport,
        level: String = "INFO",
        result: String = "SUCCESS",
        errorCode: String? = null,
        message: String = "",
        durationMs: Long? = null,
        error: Throwable? = null,
        device: UsbDevice? = usbDevice,
        permissionGranted: Boolean = device?.let { usbManager().hasPermission(it) } == true,
    ) {
        val transportName = transport?.name
        diagnostics.log(DiagnosticEventInput(
            event = event,
            connectionPhase = phase,
            transport = transportName,
            cameraBrand = if (transport == ConnectionTransport.USB) "AUTO" else config.value.brand.name,
            cameraModel = session.value?.name ?: config.value.lastCameraName.takeIf { it.isNotBlank() },
            level = level,
            result = result,
            errorCode = errorCode,
            message = message,
            durationMs = durationMs,
            network = if (transport == ConnectionTransport.WIFI || event.startsWith("WIFI")) diagnosticNetworkSnapshot(currentSsid(), cameraNetworkBound) else null,
            usb = if (transport == ConnectionTransport.USB || event.startsWith("USB") || event.startsWith("MTP")) diagnosticUsbSnapshot(device, permissionGranted, usbState.value.ready) else null,
            error = error,
        ))
    }

    private fun diagnosticSnapshot(): DiagnosticSnapshot {
        val current = session.value
        val usb = usbDevice
        val usbPermission = usb?.let { usbManager().hasPermission(it) } == true
        return DiagnosticSnapshot(
            transport = current?.transport?.name ?: usb?.let { "USB" },
            cameraBrand = if (current?.transport == ConnectionTransport.USB) "AUTO" else config.value.brand.name,
            cameraModel = current?.name ?: config.value.lastCameraName.takeIf { it.isNotBlank() },
            network = diagnosticNetworkSnapshot(currentSsid(), cameraNetworkBound),
            usb = diagnosticUsbSnapshot(usb, usbPermission, usbState.value.ready),
            mtpSessionState = when {
                current?.transport != ConnectionTransport.USB -> "NOT_APPLICABLE"
                usbState.value.ready -> "OPEN"
                usb != null -> "DETECTED_NOT_OPEN"
                else -> "NOT_DETECTED"
            },
        )
    }

    fun exportDiagnostics(onFinished: (Result<Uri>) -> Unit = {}) = viewModelScope.launch(Dispatchers.IO) {
        val result = runCatching {
            val file = diagnostics.exportPackage(diagnosticSnapshot())
            FileProvider.getUriForFile(getApplication(), "${getApplication<Application>().packageName}.diagnostics", file)
        }
        withContext(kotlinx.coroutines.Dispatchers.Main) { onFinished(result) }
    }

    fun clearDiagnosticData() = diagnostics.clear()

    fun diagnosticCopyText(): String = diagnostics.copyText()

    private fun showTransient(message: String) {
        noticeClearJob?.cancel(); notice.value = message
        noticeClearJob = viewModelScope.launch { delay(3_000); if (notice.value == message) notice.value = null }
    }

    private fun showSnackbar(message: String) {
        snackbarClearJob?.cancel(); snackbar.value = message
        snackbarClearJob = viewModelScope.launch { delay(3_000); if (snackbar.value == message) snackbar.value = null }
    }

    fun notify(message: String) = showSnackbar(message)

    private fun showPageProgress(message: String) {
        pageTaskClearJob?.cancel(); pageTask.value = PageTask(message, running = true); noticeClearJob?.cancel(); notice.value = null
    }

    private fun showPageSuccess(message: String) {
        pageRetry = null; pageTask.value = PageTask(message, running = false)
        pageTaskClearJob?.cancel(); pageTaskClearJob = viewModelScope.launch { delay(3_000); if (pageTask.value?.message == message) pageTask.value = null }
    }

    private fun showPageError(message: String) {
        logDiagnostic("BATCH_TASK_FAILED", "BATCH_TASK", session.value?.transport, level = "ERROR", result = "FAILED", message = message)
        pageTaskClearJob?.cancel(); pageTask.value = PageTask(message, running = false, failed = true); workflow.value = Workflow.ERROR; noticeClearJob?.cancel(); notice.value = null
    }

    fun retryPageTask() { pageRetry?.invoke() }

    fun detectUsbCamera() = viewModelScope.launch {
        val device = usbManager().deviceList.values.firstOrNull(::isMtpCamera)
        usbDevice = device
        val hasPermission = device?.let { usbManager().hasPermission(it) } == true
        usbState.value = if (device == null) {
            UsbConnectionState(message = "未检测到可读取的 USB 相机")
        } else {
            UsbConnectionState(
                detected = true,
                permissionGranted = hasPermission,
                deviceName = device.deviceName,
                model = device.productName,
                message = if (hasPermission) "已检测到 USB 相机" else "已检测到 USB 相机，等待 USB 访问权限",
            )
        }
        if (device != null && hasPermission && session.value == null) {
            connectUsbDevice(device)
        }
    }

    fun requestUsbPermission() {
        val device = usbDevice ?: usbManager().deviceList.values.firstOrNull(::isMtpCamera)
        if (device == null) {
            detectUsbCamera()
            return
        }
        usbDevice = device
        logDiagnostic("USB_PERMISSION_REQUESTED", "USB_PERMISSION", ConnectionTransport.USB, message = "Requesting USB permission", device = device)
        if (usbManager().hasPermission(device)) connectUsbDevice(device)
        else usbManager().requestPermission(device, usbPermissionIntent())
    }

    fun connectUsb() {
        if (session.value?.transport == ConnectionTransport.USB && client != null) {
            loadPhotos()
            return
        }
        val device = usbDevice
        if (device == null) {
            detectUsbCamera()
            return
        }
        if (usbManager().hasPermission(device)) connectUsbDevice(device) else requestUsbPermission()
    }

    private fun connectUsbDevice(device: UsbDevice) {
        if (remoteCapture.value.inProgress) { showSnackbar("请等待拍摄完成，或先取消倒计时"); return }
        if (usbConnectJob?.isActive == true) return
        if (session.value?.transport == ConnectionTransport.USB && client != null) {
            loadPhotos()
            return
        }
        usbConnectJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            isBusy.value = true
            workflow.value = Workflow.CONNECTING
            notice.value = "正在建立 USB 相机连接"
            logDiagnostic("MTP_OPEN_STARTED", "MTP_SESSION_OPEN", ConnectionTransport.USB, message = "Opening MTP session", device = device)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    client?.close()
                    UsbMtpClient(getApplication(), device).also { it.connect(); client = it }
                }
            }
            result.onSuccess { usb ->
                remoteSupported.value = false
                remoteCapture.value = RemoteCaptureState(message = "首版遥控支持 Wi-Fi，请切换连接方式")
                usbDevice = device
                logDiagnostic("MTP_OPEN_SUCCEEDED", "MTP_SESSION_OPEN", ConnectionTransport.USB, message = "MTP session opened", durationMs = System.currentTimeMillis() - startedAt, device = device, permissionGranted = true)
                logDiagnostic("CAMERA_SESSION_STARTED", "CAMERA_SESSION", ConnectionTransport.USB, message = "USB camera session started", device = device, permissionGranted = true)
                diagnostics.recordUsbConnectionState(true, diagnosticUsbSnapshot(device, true, true))
                session.value = CameraSession(usb.cameraName, "USB", 0, ConnectionTransport.USB, usb.cameraDetails)
                usbState.value = usbState.value.copy(detected = true, permissionGranted = true, ready = true, model = usb.cameraName, message = "MTP · 存储卡已就绪")
                updateConfig { it.copy(lastCameraName = usb.cameraName, lastTransport = ConnectionTransport.USB) }
                workflow.value = Workflow.CONNECTED
                notice.value = "已连接 ${usb.cameraName} · USB"
                isBusy.value = false
                loadPhotos()
            }.onFailure {
                withContext(Dispatchers.IO) { client?.close(); client = null }
                logDiagnostic("MTP_OPEN_FAILED", "MTP_SESSION_OPEN", ConnectionTransport.USB, level = "ERROR", result = "FAILED", message = it.message ?: "Unable to open MTP session", durationMs = System.currentTimeMillis() - startedAt, error = it, device = device)
                usbState.value = usbState.value.copy(ready = false, message = it.message ?: "无法连接 USB 相机")
                workflow.value = Workflow.WAITING
                showError(it.message ?: "无法连接 USB 相机")
                isBusy.value = false
            }
        }
    }

    private fun handleUsbDisconnect() {
        val disconnectedClient = client
        client = null
        usbConnectJob?.cancel()
        if (session.value?.transport == ConnectionTransport.USB) {
            photoLoadJob?.cancel()
            metadataJob?.cancel()
            loadMoreJob?.cancel()
            logDiagnostic("MTP_SESSION_LOST", "MTP_SESSION_LOST", ConnectionTransport.USB, level = "ERROR", result = "DISCONNECTED", message = "MTP session lost", device = usbDevice, permissionGranted = false)
            diagnostics.recordUsbConnectionState(false, diagnosticUsbSnapshot(usbDevice, false, false))
            session.value = null
            photos.value = emptyList(); resetCameraThumbnails(); hasMorePhotos.value = false; hasLoadedPhotos.value = false
            showError("USB 相机已断开，请重新连接数据线", event = "MTP_SESSION_LOST", transport = ConnectionTransport.USB)
        }
        usbDevice = null
        usbState.value = UsbConnectionState(message = "USB 相机已断开，请重新连接数据线")
        // MTP close waits for any native import holding UsbMtpClient's I/O lock; never block
        // the main-thread USB broadcast while a large transfer drains.
        if (disconnectedClient != null) viewModelScope.launch(Dispatchers.IO) { disconnectedClient.close() }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(USB_PERMISSION_ACTION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val app = getApplication<Application>()
        ContextCompat.registerReceiver(app, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun usbPermissionIntent(): PendingIntent {
        val app = getApplication<Application>()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(app, 2001, android.content.Intent(USB_PERMISSION_ACTION).setPackage(app.packageName), flags)
    }

    private fun usbManager() = getApplication<Application>().getSystemService(UsbManager::class.java)

    private fun android.content.Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private fun isMtpCamera(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_STILL_IMAGE) return true
        return (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
    }

    fun updateConfig(transform: (CameraConfig) -> CameraConfig) {
        config.update {
            transform(it).let { updated ->
                updated.copy(
                    jpegQuality = updated.jpegQuality.coerceIn(80, 100),
                    initialPhotoBatchSize = updated.initialPhotoBatchSize.coerceIn(MIN_PHOTO_BATCH_SIZE, MAX_PHOTO_BATCH_SIZE),
                    loadMorePhotoBatchSize = updated.loadMorePhotoBatchSize.coerceIn(MIN_PHOTO_BATCH_SIZE, MAX_PHOTO_BATCH_SIZE),
                )
            }
        }
        store.save(config.value)
    }

    fun markLutUsed(entry: LutEntry) {
        val updated = (listOf(entry.id) + recentLutIds.value.filterNot { it == entry.id }).take(5)
        recentLutIds.value = updated
        store.saveRecentLuts(updated)
        luts.value = luts.value.map { if (it.id == entry.id) it.copy(lastUsedAt = System.currentTimeMillis()) else it }
        store.saveLuts(luts.value)
    }

    fun addWatermark(preset: WatermarkPreset) {
        watermarks.value = watermarks.value + preset
        store.saveWatermarks(watermarks.value)
        showSnackbar("已创建水印预设")
    }

    fun updateWatermark(preset: WatermarkPreset) {
        watermarks.value = watermarks.value.map { if (it.id == preset.id) preset else it }
        store.saveWatermarks(watermarks.value)
        showSnackbar("已保存水印预设")
    }

    fun removeWatermark(preset: WatermarkPreset) {
        watermarks.value = watermarks.value.filterNot { it.id == preset.id }
        store.saveWatermarks(watermarks.value)
        showSnackbar("已删除水印预设")
    }

    fun clearThumbnailCache() {
        if (clearingThumbnailCache.value) return
        clearingThumbnailCache.value = true
        thumbnailDiskCache.invalidate()
        cameraThumbnailCache.clear()
        localThumbnailCache.clear()
        thumbnails.clear()
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { thumbnailDiskCache.clear() } }
            refreshThumbnailCacheSize()
            clearingThumbnailCache.value = false
            thumbnailVersion.update { it + 1 }
            showSnackbar(if (result.isSuccess) "已清理缩略图缓存" else "部分缓存清理失败，请重试")
        }
    }

    fun refreshThumbnailCacheSize() {
        viewModelScope.launch(Dispatchers.IO) { thumbnailCacheBytes.value = thumbnailDiskCache.sizeBytes() }
    }

    private fun thumbnailDiskKey(camera: CameraClient, asset: PhotoAsset): String =
        listOf("v1", camera.cameraDetails.manufacturer, camera.cameraDetails.model, camera.cameraDetails.serialNumber,
            camera.cameraName, asset.handle, asset.name, asset.size, asset.capturedAt.time,
            asset.imageWidth, asset.imageHeight).joinToString("\u0000")

    private fun cacheThumbnail(key: String, bitmap: Bitmap, cacheGeneration: Long) {
        runCatching {
            val bytes = java.io.ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output))
                output.toByteArray()
            }
            thumbnailDiskCache.write(key, bytes, cacheGeneration)
        }
        thumbnailCacheBytes.value = thumbnailDiskCache.sizeBytes()
    }

    private fun resetCameraThumbnails() {
        cameraThumbnailCache.clear()
        thumbnails.clear()
        thumbnailVersion.update { it + 1 }
    }

    suspend fun loadLocalThumbnail(record: DownloadRecord): Bitmap? = withContext(Dispatchers.IO) {
        localThumbnailCache.get(record.uri)?.let { return@withContext it }
        loadDownloadThumbnail(getApplication(), record.uri, getApplication<Application>().mediaKind(record))
            ?.also { localThumbnailCache.put(record.uri, it) }
    }

    fun retry() {
        _downloadTasks.value.firstOrNull { it.status == DownloadTaskStatus.FAILED }?.let { retryDownloadTask(it.id) } ?: if (session.value?.transport == ConnectionTransport.USB || usbDevice != null || config.value.lastTransport == ConnectionTransport.USB) {
            logDiagnostic("USB_RECONNECT_ATTEMPT", "USB_RECONNECT", ConnectionTransport.USB, result = "STARTED", message = "Retrying USB camera connection", device = usbDevice)
            connectUsb()
        } else connect(false)
    }

    fun dismissError() {
        if (workflow.value != Workflow.ERROR) return
        noticeClearJob?.cancel()
        notice.value = null
        workflow.value = if (session.value == null) Workflow.WAITING else Workflow.CONNECTED
    }

    fun checkConnectionOnResume() = viewModelScope.launch {
        if (remoteCapture.value.inProgress) return@launch
        val current = session.value ?: run {
            detectUsbCamera()
            return@launch
        }
        if (_downloadTasks.value.any { it.status == DownloadTaskStatus.DOWNLOADING || it.status == DownloadTaskStatus.CANCELLING }) return@launch
        val network = cameraNetwork()
        val active = client
        val connected = active != null && withContext(Dispatchers.IO) {
            if (current.transport == ConnectionTransport.USB) runCatching { active.checkConnection() }.getOrDefault(false)
            else network != null && runCatching { active.checkConnection() }.getOrDefault(false)
        }
        if (connected) {
            workflow.value = Workflow.CONNECTED
            notice.value = "已连接 ${current.name} · ${current.transport.title} · 已读取 ${photos.value.size} 张"
        } else {
            if (current.transport == ConnectionTransport.WIFI) {
                logDiagnostic("WIFI_NETWORK_LOST", "NETWORK_LOST", ConnectionTransport.WIFI, level = "ERROR", result = "DISCONNECTED", message = "Camera Wi-Fi network is no longer available")
                keepConnectionAlive(false); bindCameraNetwork(null)
            } else {
                logDiagnostic("MTP_SESSION_LOST", "MTP_SESSION_LOST", ConnectionTransport.USB, level = "ERROR", result = "DISCONNECTED", message = "MTP session no longer responds", device = usbDevice)
                diagnostics.recordUsbConnectionState(false, diagnosticUsbSnapshot(usbDevice, false, false))
            }
            withContext(Dispatchers.IO) { client?.close(); client = null }
            session.value = null; photos.value = emptyList(); resetCameraThumbnails(); hasMorePhotos.value = false; hasLoadedPhotos.value = false
            val message = if (current.transport == ConnectionTransport.USB) "USB 相机已断开，请重新连接数据线" else "相机 Wi‑Fi 已断开，请重新连接"
            showError(message, event = if (current.transport == ConnectionTransport.USB) "MTP_SESSION_LOST" else "CAMERA_SESSION_LOST", transport = current.transport)
        }
    }

    fun connect(manual: Boolean = false) = viewModelScope.launch {
        if (remoteCapture.value.inProgress) { showSnackbar("请等待拍摄完成，或先取消倒计时"); return@launch }
        val target = config.value
        val network = cameraNetwork()
        if (network == null) {
            logDiagnostic("WIFI_NETWORK_LOST", "NETWORK_LOOKUP", ConnectionTransport.WIFI, level = "ERROR", result = "NOT_FOUND", message = "No Wi-Fi network available")
            if (manual) openWifiSettings() else showError("请先连接相机 Wi‑Fi 后再继续")
            return@launch
        }
        logDiagnostic("WIFI_NETWORK_FOUND", "NETWORK_LOOKUP", ConnectionTransport.WIFI, message = "Wi-Fi network found")
        val host = cameraGateway(network).ifBlank { target.host.trim() }
        if (target.host.isBlank() || target.port !in 1..65535) { showError("请填写有效的相机地址和端口"); return@launch }
        isBusy.value = true; workflow.value = Workflow.CONNECTING; notice.value = "正在连接 ${target.host}:${target.port}"
        logDiagnostic("CAMERA_DISCOVERY_STARTED", "CAMERA_DISCOVERY", ConnectionTransport.WIFI, message = "Starting camera discovery")
        bindCameraNetwork(network)
        runCatching {
            withContext(Dispatchers.IO) {
                client?.close(); PtpIpClient(host, target.port, network.socketFactory).also { it.connect(); client = it }
            }
        }.onSuccess { ptp ->
            remoteSupported.value = ptp.supportsRemoteCapture
            remoteCapture.value = RemoteCaptureState(message = if (ptp.supportsRemoteCapture) "相机已连接，可以遥控拍摄" else "当前相机连接未提供遥控拍摄能力")
            logDiagnostic("CAMERA_DISCOVERED", "CAMERA_DISCOVERY", ConnectionTransport.WIFI, message = "Camera discovered: ${ptp.cameraName}")
            logDiagnostic("CAMERA_SESSION_STARTED", "CAMERA_SESSION", ConnectionTransport.WIFI, message = "Wi-Fi camera session started")
            session.value = CameraSession(ptp.cameraName, host, target.port, ConnectionTransport.WIFI, ptp.cameraDetails)
            keepConnectionAlive(true)
            updateConfig { it.copy(lastCameraName = ptp.cameraName, lastTransport = ConnectionTransport.WIFI) }
            workflow.value = Workflow.CONNECTED; notice.value = "已连接 ${ptp.cameraName} · Wi‑Fi"
            currentSsid()?.let { ssid -> if (ssid.isNotBlank()) updateConfig { c -> c.copy(lastSsid = ssid) } }
            logDiagnostic("CAMERA_SESSION_READY", "CAMERA_SESSION", ConnectionTransport.WIFI, message = "Camera session ready")
            loadPhotos()
        }.onFailure {
            bindCameraNetwork(null)
            if (requestedCameraNetwork != null) releaseRequestedWifi()
            workflow.value = Workflow.WAITING
            val timeout = it is SocketTimeoutException || it.message.orEmpty().contains("timeout", ignoreCase = true) || it.message.orEmpty().contains("timed out", ignoreCase = true)
            logDiagnostic(if (timeout) "CAMERA_PROTOCOL_TIMEOUT" else "CAMERA_SESSION_LOST", "CAMERA_SESSION", ConnectionTransport.WIFI, level = "ERROR", result = "FAILED", message = it.message ?: "Unable to establish camera session", error = it)
            if (manual) openWifiSettings() else showError(it.message ?: "无法连接相机，请确认已连接相机 Wi‑Fi", event = if (timeout) "CAMERA_PROTOCOL_TIMEOUT" else "CAMERA_SESSION_LOST", transport = ConnectionTransport.WIFI)
        }
        isBusy.value = false
    }

    fun openWifiSettings() {
        val app = getApplication<Application>()
        app.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun connectRecentWifi(ssid: String, password: String) {
        if (remoteCapture.value.inProgress) { showSnackbar("请等待拍摄完成，或先取消倒计时"); return }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            openWifiSettings()
            return
        }
        if (_downloadTasks.value.any { it.status == DownloadTaskStatus.DOWNLOADING || it.status == DownloadTaskStatus.CANCELLING }) return
        if (isBusy.value) return
        val app = getApplication<Application>()
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        releaseRequestedWifi()
        val request = runCatching {
            val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
                if (password.isNotEmpty()) setWpa2Passphrase(password)
            }.build()
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
        }.getOrElse {
            showError("相机 Wi‑Fi 信息无效，请检查热点名称和密码", transport = ConnectionTransport.WIFI)
            return
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val activeCallback = this
                viewModelScope.launch {
                    if (requestedWifiCallback !== activeCallback) return@launch
                    requestedCameraNetwork = network
                    isBusy.value = false
                    connect(false)
                }
            }

            override fun onUnavailable() {
                val activeCallback = this
                viewModelScope.launch {
                    if (requestedWifiCallback !== activeCallback) return@launch
                    releaseRequestedWifi()
                    isBusy.value = false
                    workflow.value = Workflow.WAITING
                    notice.value = null
                    showSnackbar("暂时没找到 $ssid，请确认相机热点已开启后重试")
                }
            }

            override fun onLost(network: Network) {
                val activeCallback = this
                viewModelScope.launch {
                    if (requestedWifiCallback !== activeCallback || requestedCameraNetwork != network) return@launch
                    releaseRequestedWifi()
                    if (session.value?.transport == ConnectionTransport.WIFI) checkConnectionOnResume()
                }
            }
        }
        requestedWifiCallback = callback
        isBusy.value = true
        workflow.value = Workflow.CONNECTING
        notice.value = "正在切换到相机 Wi‑Fi：$ssid"
        runCatching { connectivity.requestNetwork(request, callback, 60_000) }.onFailure {
            releaseRequestedWifi()
            isBusy.value = false
            showError("无法发起 Wi‑Fi 连接：${it.message ?: "请检查附近设备权限"}", transport = ConnectionTransport.WIFI)
        }
    }

    private fun releaseRequestedWifi() {
        requestedWifiCallback?.let { callback ->
            runCatching { getApplication<Application>().getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        }
        requestedWifiCallback = null
        requestedCameraNetwork = null
    }

    fun disconnect() = viewModelScope.launch(Dispatchers.IO) {
        clearRemoteFocus()
        remoteParameterJob?.cancel()
        remoteParameterEditor.value = RemoteParameterEditor()
        cancelPendingRemoteCapture()
        remoteSupported.value = false
        val closing = client
        client = null
        val transport = session.value?.transport
        _downloadTasks.value.filter { it.status == DownloadTaskStatus.QUEUED || it.status == DownloadTaskStatus.DOWNLOADING }
            .forEach { cancelDownloadTask(it.id) }
        photoLoadJob?.cancel()
        metadataJob?.cancel()
        loadMoreJob?.cancel()
        if (transport == ConnectionTransport.USB) {
            logDiagnostic("MTP_SESSION_LOST", "DISCONNECT_REQUESTED", ConnectionTransport.USB, result = "USER_DISCONNECTED", message = "USB session closed by user", device = usbDevice)
            diagnostics.recordUsbConnectionState(false, diagnosticUsbSnapshot(usbDevice, false, false))
        }
        if (transport == ConnectionTransport.WIFI) { keepConnectionAlive(false); bindCameraNetwork(null); releaseRequestedWifi() }
        closing?.close(); hasMorePhotos.value = false; hasLoadedPhotos.value = false
        withContext(Dispatchers.Main) {
            session.value = null; photos.value = emptyList(); resetCameraThumbnails(); workflow.value = Workflow.WAITING
            notice.value = if (transport == ConnectionTransport.USB) "USB 相机已断开" else "连接已断开"
            if (transport == ConnectionTransport.USB) usbState.value = usbState.value.copy(ready = false, message = "USB 相机未连接")
        }
    }

    fun startRemoteCapture(delaySeconds: Int) {
        if (remoteFocus.value.inProgress) { showSnackbar("请等待对焦完成"); return }
        if (remoteParameterEditor.value.busy) { showSnackbar("请等待参数读取或修改完成"); return }
        if (remoteCaptureJob?.isActive == true || remoteCapture.value.inProgress) return
        val active = client
        val remote = active as? RemoteCameraControl
        if (session.value?.transport != ConnectionTransport.WIFI || remote == null) {
            showSnackbar("请先连接相机 Wi-Fi")
            return
        }
        if (!remoteSupported.value) { showSnackbar("当前连接未提供遥控拍摄能力"); return }
        if (isBusy.value || _downloadTasks.value.any { it.status == DownloadTaskStatus.DOWNLOADING || it.status == DownloadTaskStatus.CANCELLING || it.status == DownloadTaskStatus.QUEUED }) {
            showSnackbar("请等待相册读取或下载完成后再拍摄")
            return
        }
        val seconds = delaySeconds.takeIf { it in listOf(0, 2, 5, 10) } ?: 0
        val cancellation = java.util.concurrent.atomic.AtomicBoolean(false)
        clearRemoteFocus()
        remoteCancellation = cancellation
        metadataJob?.cancel()
        loadMoreJob?.cancel()
        remoteCapture.value = RemoteCaptureState(
            if (seconds > 0) RemoteCapturePhase.COUNTDOWN else RemoteCapturePhase.PREPARING,
            seconds, if (seconds > 0) "倒计时结束后拍摄" else "正在准备拍摄",
        )
        remoteCaptureJob = viewModelScope.launch {
            try {
                for (remaining in seconds downTo 1) {
                    remoteCapture.value = RemoteCaptureState(RemoteCapturePhase.COUNTDOWN, remaining, "倒计时结束后拍摄")
                    delay(1000)
                    if (cancellation.get() || client !== active) throw CancellationException("拍摄已取消")
                }
                remoteCapture.value = RemoteCaptureState(RemoteCapturePhase.PREPARING, message = "正在准备拍摄")
                logDiagnostic("REMOTE_CAPTURE_STARTED", "REMOTE_CAPTURE", ConnectionTransport.WIFI, message = "Starting remote shutter; delay=$seconds")
                val result = withContext(Dispatchers.IO) {
                    remote.capture(
                        isCancelled = { cancellation.get() || client !== active },
                        onSending = {
                            synchronized(cancellation) {
                                if (cancellation.get() || client !== active) throw CancellationException("拍摄已取消")
                                remoteCapture.value = RemoteCaptureState(RemoteCapturePhase.WAITING, message = "快门指令发送中，正在等待相机确认")
                            }
                        },
                    )
                }
                if (client !== active) return@launch
                remoteCapture.value = RemoteCaptureState(if (result.confirmed) RemoteCapturePhase.CONFIRMED else RemoteCapturePhase.UNKNOWN, message = result.message)
                logDiagnostic(if (result.confirmed) "REMOTE_CAPTURE_CONFIRMED" else "REMOTE_CAPTURE_UNCONFIRMED", "REMOTE_CAPTURE", ConnectionTransport.WIFI,
                    level = if (result.confirmed) "INFO" else "WARN", result = if (result.confirmed) "SUCCESS" else "UNKNOWN", message = result.message)
                if (result.confirmed) loadPhotos()
            } catch (cancelled: CancellationException) {
                remoteCapture.value = RemoteCaptureState(message = "已取消待发送的拍摄")
                throw cancelled
            } catch (error: Exception) {
                if (client === active) {
                    remoteCapture.value = RemoteCaptureState(RemoteCapturePhase.FAILED, message = error.message ?: "遥控拍摄失败，请检查相机状态")
                    logDiagnostic("REMOTE_CAPTURE_FAILED", "REMOTE_CAPTURE", ConnectionTransport.WIFI, level = "ERROR", result = "FAILED", message = error.message ?: "Remote shutter failed", error = error)
                }
            } finally {
                if (remoteCapture.value.inProgress) remoteCapture.value = RemoteCaptureState(message = "连接已改变，请重新连接后拍摄")
            }
        }
    }

    /** Once WAITING starts, cancellation cannot promise to stop a physical exposure. */
    fun cancelPendingRemoteCapture() {
        val cancellation = remoteCancellation
        synchronized(cancellation) {
            when (remoteCapture.value.phase) {
                RemoteCapturePhase.COUNTDOWN -> { cancellation.set(true); remoteCaptureJob?.cancel() }
                RemoteCapturePhase.PREPARING -> cancellation.set(true)
                else -> Unit
            }
        }
    }

    fun setRemoteViewVisible(visible: Boolean) {
        if (!visible) clearRemoteFocus()
        if (!visible) closeRemoteParameterEditor()
        remoteViewVisible = visible
        reconcileRemotePreview()
    }

    fun setRemoteForeground(foreground: Boolean) {
        if (!foreground) clearRemoteFocus()
        remoteForeground = foreground
        reconcileRemotePreview()
    }

    fun retryRemotePreview() {
        remotePreviewJob?.cancel()
        remotePreviewClient = null
        reconcileRemotePreview()
    }

    private fun parameterAccessAllowed(): Boolean = remoteViewVisible && remoteForeground &&
        session.value?.transport == ConnectionTransport.WIFI && !isBusy.value &&
        !remoteCapture.value.inProgress && !remoteFocus.value.inProgress && !_downloadTasks.value.any {
            it.status == DownloadTaskStatus.DOWNLOADING || it.status == DownloadTaskStatus.CANCELLING || it.status == DownloadTaskStatus.QUEUED
        }

    fun openRemoteParameter(parameter: RemoteParameter) {
        if (remoteParameterJob?.isActive == true) return
        if (!parameterAccessAllowed()) { showSnackbar("请连接相机，并等待拍摄或传输完成"); return }
        val active = client as? RemoteCameraControl ?: return
        remoteParameterEditor.value = RemoteParameterEditor(parameter = parameter, loading = true)
        remoteParameterJob = viewModelScope.launch {
            try {
                val descriptor = withContext(Dispatchers.IO) { active.parameterDescriptor(parameter) }
                if (client === active && remoteViewVisible) {
                    remoteParameterEditor.value = RemoteParameterEditor(parameter, descriptor, message = descriptor.reason)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (client === active && remoteViewVisible) remoteParameterEditor.value = RemoteParameterEditor(parameter = parameter, message = error.message ?: "无法读取参数")
            }
        }
    }

    fun setRemoteParameter(value: Long) {
        val editor = remoteParameterEditor.value
        val parameter = editor.parameter ?: return
        if (editor.busy || !parameterAccessAllowed()) { showSnackbar("请等待拍摄或传输完成后再修改参数"); return }
        val active = client as? RemoteCameraControl ?: return
        if (editor.descriptor?.let { it.writable && it.allows(value) } != true) return
        remoteParameterEditor.value = editor.copy(saving = true, message = "正在修改并确认相机实际值")
        remoteParameterJob = viewModelScope.launch {
            try {
                val parameters = withContext(Dispatchers.IO) { active.setParameter(parameter, value) }
                if (client === active) {
                    remoteLiveView.update { it.copy(parameters = parameters) }
                    remoteParameterEditor.value = RemoteParameterEditor()
                    showSnackbar("${parameter.title}已更新")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (client === active && remoteViewVisible) {
                    val actual = withContext(Dispatchers.IO) { runCatching { active.parameterDescriptor(parameter) }.getOrNull() }
                    remoteParameterEditor.value = editor.copy(descriptor = actual ?: editor.descriptor, saving = false,
                        message = error.message ?: "未确认修改生效，请检查相机")
                    logDiagnostic("REMOTE_PARAMETER_FAILED", "REMOTE_PARAMETER", ConnectionTransport.WIFI,
                        level = "WARN", result = "FAILED", message = "${parameter.title}: ${error.message}", error = error)
                }
            }
        }
    }

    fun closeRemoteParameterEditor() {
        if (remoteParameterEditor.value.saving) return
        remoteParameterJob?.cancel()
        remoteParameterEditor.value = RemoteParameterEditor()
    }

    fun selectRemotePreviewLut(entry: LutEntry?) {
        remoteColorController.select(entry)
    }

    fun setRemotePreviewIntensity(value: Float) {
        remoteColorController.setIntensity(value)
    }

    private fun clearRemoteFocus() {
        remoteFocusGeneration.incrementAndGet()
        remoteFocusJob?.cancel()
        remoteFocus.value = RemoteFocusState()
    }

    fun focusRemoteAt(point: NormalizedFocusPoint, geometry: LiveViewFocusGeometry?) {
        if (remoteFocus.value.inProgress) return
        if (!parameterAccessAllowed() || remoteParameterEditor.value.busy || !remoteLiveView.value.streaming) {
            showSnackbar("请等待取景恢复，并等待拍摄或传输完成"); return
        }
        if (geometry == null) { showSnackbar("相机未提供可识别的对焦坐标信息"); return }
        if (remoteLiveView.value.focusGeometry != geometry) return
        val active = client as? RemoteCameraControl ?: return
        clearRemoteFocus()
        val generation = remoteFocusGeneration.get()
        remoteFocus.value = RemoteFocusState(point, RemoteFocusPhase.FOCUSING, "对焦中")
        remoteFocusJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    active.focusAt(point, geometry) {
                        generation != remoteFocusGeneration.get() || client !== active || !remoteViewVisible || !remoteForeground
                    }
                }
                if (generation == remoteFocusGeneration.get() && client === active && remoteViewVisible && remoteForeground) {
                    remoteFocus.value = RemoteFocusState(point, result.phase, result.message)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == remoteFocusGeneration.get() && client === active && remoteViewVisible && remoteForeground) {
                    val message = if (error is PtpResponseException && error.response == 0xA002) "相机未能合焦" else error.message ?: "未确认对焦结果"
                    remoteFocus.value = RemoteFocusState(point, RemoteFocusPhase.FAILED, message)
                    logDiagnostic("REMOTE_FOCUS_FAILED", "REMOTE_FOCUS", ConnectionTransport.WIFI,
                        level = "WARN", result = "FAILED", message = message, error = error)
                }
            } finally {
                if (generation == remoteFocusGeneration.get() && remoteFocus.value.inProgress) remoteFocus.value = RemoteFocusState()
            }
            delay(3000)
            if (generation == remoteFocusGeneration.get()) remoteFocus.value = RemoteFocusState()
        }
    }

    private fun reconcileRemotePreview() {
        val active = client
        if (!remoteViewVisible || !remoteForeground || session.value?.transport != ConnectionTransport.WIFI || active !is RemoteCameraControl) {
            remotePreviewJob?.cancel()
            remotePreviewClient = null
            remoteLiveView.value = RemoteLiveViewState()
            return
        }
        if (remotePreviewClient === active && remotePreviewJob?.isActive == true) return
        remotePreviewJob?.cancel()
        remotePreviewClient = active
        remotePreviewJob = viewModelScope.launch {
            // A new page/session waits for the old session's live-view cleanup.
            remotePreviewMutex.withLock {
                if (!isActive || client !== active || !remoteViewVisible || !remoteForeground) return@withLock
                remoteLiveView.value = RemoteLiveViewState(loading = true, message = "正在开启实时取景")
                var started = false
                var frameFailed = false
                var transientErrors = 0
                var invalidFrames = 0
                var frameFailureMessage: String? = null
                var lastParametersAt = 0L
                try {
                    val supported = withContext(Dispatchers.IO) { active.supportsLiveView }
                    if (!supported) {
                        frameFailed = true
                        frameFailureMessage = "当前连接未提供实时取景能力，仍可读取拍摄参数"
                        remoteLiveView.value = remoteLiveView.value.copy(loading = false, failed = true, message = "当前连接未提供实时取景能力，仍可读取拍摄参数")
                    }
                    while (isActive && client === active && remoteViewVisible && remoteForeground) {
                        // Keep live view active during AF; do not stop it when a transfer gets queued.
                        if (remoteFocus.value.inProgress) { delay(50); continue }
                        val shooting = remoteCapture.value.phase == RemoteCapturePhase.PREPARING || remoteCapture.value.phase == RemoteCapturePhase.WAITING
                        val transferring = _downloadTasks.value.any { it.status == DownloadTaskStatus.DOWNLOADING || it.status == DownloadTaskStatus.CANCELLING || it.status == DownloadTaskStatus.QUEUED }
                        if (shooting || transferring || isBusy.value) {
                            remoteLiveView.value = remoteLiveView.value.copy(streaming = false, loading = false,
                                message = if (shooting) "拍摄中，取景暂时暂停" else "相机正在读取或传输，取景暂时暂停")
                            if (started) {
                                withContext(Dispatchers.IO) { active.stopLiveView() }
                                started = false
                            }
                            delay(200)
                            continue
                        }
                        val frameStartedAt = SystemClock.elapsedRealtime()
                        try {
                            if (frameFailed) remoteLiveView.value = remoteLiveView.value.copy(streaming = false, loading = false, failed = true, message = frameFailureMessage ?: "无法显示实时画面")
                            if (!frameFailed) {
                                if (!started) {
                                    remoteLiveView.value = remoteLiveView.value.copy(loading = true, message = "正在开启实时取景")
                                    withContext(Dispatchers.IO) { active.startLiveView() }
                                    started = true
                                }
                                val frame = withContext(Dispatchers.IO) {
                                    val liveFrame = active.liveViewFrame()
                                    val jpeg = liveFrame.jpeg
                                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
                                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw LiveViewImageException("相机返回了无法解码的取景画面")
                                    var sample = 1
                                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                                    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
                                        inSampleSize = sample
                                        inPreferredConfig = Bitmap.Config.RGB_565
                                    }) ?: throw LiveViewImageException("无法解码相机实时画面")
                                    bitmap to liveFrame.focusGeometry?.takeIf { it.jpegWidth == bounds.outWidth && it.jpegHeight == bounds.outHeight }
                                }
                                if (client !== active) break
                                remoteLiveView.value = remoteLiveView.value.copy(frame = frame.first, focusGeometry = frame.second,
                                    streaming = true, loading = false, failed = false, message = "实时取景")
                                transientErrors = 0
                                invalidFrames = 0
                            }
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastParametersAt >= 1500) {
                                val parameters = withContext(Dispatchers.IO) { active.shootingParameters() }
                                if (client !== active) break
                                remoteLiveView.value = remoteLiveView.value.copy(parameters = parameters)
                                lastParametersAt = now
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: LiveViewImageException) {
                            if (++invalidFrames > 3) throw error
                            delay(120)
                            continue
                        }
                        catch (error: PtpResponseException) {
                            if (error.response in listOf(0x2019, 0xA004, 0xA00B) && ++transientErrors <= 20) {
                                remoteLiveView.value = remoteLiveView.value.copy(streaming = false, loading = true, message = "相机正在准备取景")
                                delay(250)
                                continue
                            }
                            if (!frameFailed) {
                                frameFailed = true
                                frameFailureMessage = error.message ?: "无法开启实时取景"
                                remoteLiveView.value = remoteLiveView.value.copy(streaming = false, loading = false, failed = true, message = error.message ?: "无法开启实时取景")
                                logDiagnostic("REMOTE_LIVE_VIEW_FAILED", "REMOTE_LIVE_VIEW", ConnectionTransport.WIFI, level = "WARN", result = "FAILED", message = error.message ?: "Live view failed", error = error)
                            } else throw error
                        }
                        // No prefetch queue: drop the old bitmap naturally after Compose releases it.
                        delay(if (frameFailed) 1000 else (100 - (SystemClock.elapsedRealtime() - frameStartedAt)).coerceAtLeast(16))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (client === active) {
                        remoteLiveView.value = remoteLiveView.value.copy(streaming = false, loading = false, failed = true, message = error.message ?: "取景连接中断，请重试")
                        logDiagnostic("REMOTE_LIVE_VIEW_FAILED", "REMOTE_LIVE_VIEW", ConnectionTransport.WIFI, level = "WARN", result = "FAILED", message = error.message ?: "Live view failed", error = error)
                    }
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        // ON_STOP pauses frames; keep remote/application mode until the user
                        // leaves the remote page. Backgrounding is not an explicit remote exit.
                        runCatching {
                            if (remoteViewVisible && client === active) active.stopLiveView()
                            else active.exitRemoteControl()
                        }.onFailure { error ->
                            logDiagnostic("REMOTE_EXIT_FAILED", "REMOTE_LIVE_VIEW", ConnectionTransport.WIFI,
                                level = "WARN", result = "FAILED", message = error.message ?: "Unable to release remote control", error = error)
                            showSnackbar("退出遥控未完成，请检查相机，必要时重新连接")
                        }
                    }
                    if (!remoteViewVisible || !remoteForeground || client !== active) remoteLiveView.value = RemoteLiveViewState()
                }
            }
        }
    }

    fun loadPhotos() {
        if (remoteCapture.value.inProgress) { showSnackbar("拍摄完成后可刷新相册"); return }
        if (photoLoadJob?.isActive == true) return
        if (_downloadTasks.value.any { it.status == DownloadTaskStatus.DOWNLOADING || it.status == DownloadTaskStatus.CANCELLING }) {
            showSnackbar("正在传输文件，完成后可刷新相册")
            return
        }
        metadataJob?.cancel()
        loadMoreJob?.cancel()
        val generation = ++photoGeneration
        resetCameraThumbnails()
        photoLoadJob = viewModelScope.launch {
            val active = client ?: run { showError("请先连接相机"); return@launch }
            val batchSize = config.value.initialPhotoBatchSize
            isBusy.value = true; workflow.value = Workflow.LOADING; hasLoadedPhotos.value = false; notice.value = "正在读取相册（首批 ${batchSize} 张）"
            val startedAt = System.currentTimeMillis()
            logDiagnostic("ASSET_LIST_STARTED", "ASSET_LIST", session.value?.transport, message = "Reading first asset page")
            try {
            runCatching {
                withContext(Dispatchers.IO) {
                    active.refreshAssets()
                    val result = active.assets(limit = batchSize)
                    result to active.hasMoreAssets(result.size)
                }
            }
                .onSuccess { (result, hasMore) ->
                    if (client !== active || generation != photoGeneration) return@onSuccess
                    photos.value = result
                    hasMorePhotos.value = hasMore
                    hasLoadedPhotos.value = true
                    workflow.value = Workflow.CONNECTED; notice.value = "${session.value?.name ?: "相机"} · ${session.value?.transport?.title ?: "Wi‑Fi"} · 已读取 ${result.size} 张"
                    if (session.value?.transport == ConnectionTransport.USB) logDiagnostic("MTP_STORAGE_DISCOVERED", "MTP_STORAGE", ConnectionTransport.USB, message = "MTP storage discovered", device = usbDevice)
                    logDiagnostic("ASSET_LIST_SUCCEEDED", "ASSET_LIST", session.value?.transport, message = "Read ${result.size} assets", durationMs = System.currentTimeMillis() - startedAt)
                    enrichCameraMetadata(active, result, generation)
                }.onFailure {
                    if (it is CancellationException) throw it
                    logDiagnostic("ASSET_LIST_FAILED", "ASSET_LIST", session.value?.transport, level = "ERROR", result = "FAILED", message = it.message ?: "Unable to read asset list", durationMs = System.currentTimeMillis() - startedAt, error = it)
                    showError(it.message ?: "读取相册失败", event = "ASSET_LIST_FAILED", transport = session.value?.transport)
                }
            } finally { isBusy.value = false }
        }
    }

    private fun enrichCameraMetadata(active: CameraClient, assets: List<PhotoAsset>, generation: Int) {
        metadataJob = viewModelScope.launch {
            val stills = assets.filter { !it.name.isVideoName() }
            val candidates = stills.filter { it.name.endsWith(".jpg", true) || it.name.endsWith(".jpeg", true) }.take(10) +
                stills.filterNot { it.name.endsWith(".jpg", true) || it.name.endsWith(".jpeg", true) }.take(4)
            var fallback: Pair<PhotoAsset, PhotoMetadata>? = null
            for (asset in candidates) {
                _downloadTasks.first { tasks -> tasks.none { it.status == DownloadTaskStatus.DOWNLOADING || it.status == DownloadTaskStatus.CANCELLING } }
                if (client !== active || generation != photoGeneration) return@launch
                val metadata = withContext(Dispatchers.IO) {
                    runCatching { active.imageHeader(asset)?.let { ExifMetadataReader.fromBytes(it, PhotoMetadata(cameraModel = session.value?.name, capturedAt = asset.capturedAt)) } }.getOrNull()
                } ?: continue
                if (client !== active || generation != photoGeneration) return@launch
                if (fallback == null) fallback = asset to metadata
                if (metadata.lensModel != null) {
                    updateSessionFromPhoto(asset, metadata)
                    return@launch
                }
            }
            fallback?.let { (asset, metadata) -> updateSessionFromPhoto(asset, metadata) }
        }
    }

    private fun updateSessionFromPhoto(asset: PhotoAsset, metadata: PhotoMetadata) {
        val capturedAt = metadata.capturedAt?.time ?: asset.capturedAt.time
        session.update { current ->
            if (current == null || (current.details.recentCapturedAt ?: Long.MIN_VALUE) > capturedAt) current
            else current.copy(details = current.details.copy(
                manufacturer = current.details.manufacturer ?: metadata.cameraBrand,
                model = current.details.model ?: metadata.cameraModel,
                lensName = metadata.lensModel ?: current.details.lensName,
                lensFromPhoto = metadata.lensModel != null || current.details.lensFromPhoto,
                recentFocalLength = metadata.focalLength,
                recentAperture = metadata.aperture,
                recentShutter = metadata.shutterSpeed,
                recentIso = metadata.iso,
                recentCapturedAt = capturedAt,
            ))
        }
    }

    fun loadMorePhotos() {
        if (loadMoreJob?.isActive == true || !hasMorePhotos.value || photoLoadJob?.isActive == true) return
        val generation = photoGeneration
        loadMoreJob = viewModelScope.launch {
            val active = client ?: return@launch
            isLoadingMorePhotos.value = true
            try {
                _downloadTasks.first { tasks -> tasks.none { it.status == DownloadTaskStatus.DOWNLOADING || it.status == DownloadTaskStatus.CANCELLING } }
                if (client !== active || generation != photoGeneration) return@launch
                val offset = photos.value.size
                logDiagnostic("ASSET_LIST_STARTED", "ASSET_LIST", session.value?.transport, message = "Reading next asset page")
                runCatching { withContext(Dispatchers.IO) {
                    val page = active.assets(offset, config.value.loadMorePhotoBatchSize)
                    page to active.hasMoreAssets(offset + page.size)
                } }.onSuccess { (page, hasMore) ->
                    if (client !== active || generation != photoGeneration) return@onSuccess
                    photos.value = photos.value + page
                    hasMorePhotos.value = hasMore
                    notice.value = if (hasMore) "已读取 ${photos.value.size} 张" else "已全部加载"
                }.onFailure {
                    if (it is CancellationException) throw it
                    showError(it.message ?: "继续读取照片失败", event = "ASSET_LIST_FAILED", transport = session.value?.transport)
                }
            } finally {
                isLoadingMorePhotos.value = false
            }
        }
    }

    fun loadThumbnail(asset: PhotoAsset) {
        if (clearingThumbnailCache.value) return
        if (cameraThumbnailCache.get(asset.handle) != null) return
        val generation = photoGeneration
        val cacheGeneration = thumbnailDiskCache.generation
        val loadKey = "$generation:$cacheGeneration:${asset.handle}"
        if (!thumbnailLoads.add(loadKey)) return
        val camera = client
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val active = camera ?: return@withContext null
                    val diskKey = thumbnailDiskKey(active, asset)
                    thumbnailDiskCache.read(diskKey)?.let { cached ->
                        BitmapFactory.decodeByteArray(cached, 0, cached.size)?.let { return@withContext it }
                    }
                    val data = active.thumbnail(asset) ?: return@withContext null
                    val orientation = OrientedBitmaps.orientationOrNull(data)
                        ?: active.imageHeader(asset)?.let(OrientedBitmaps::orientationOrNull)
                        ?: android.media.ExifInterface.ORIENTATION_NORMAL
                    OrientedBitmaps.decode(data, orientation)?.let { decoded ->
                        val clean = trimThumbnailPadding(decoded, asset.imageWidth, asset.imageHeight)
                        if (clean !== decoded) decoded.recycle()
                        scaledThumbnail(clean).also {
                            if (it !== clean) clean.recycle()
                            cacheThumbnail(diskKey, it, cacheGeneration)
                        }
                    }
                }
            }
            result.onSuccess { bitmap ->
                if (bitmap != null && client === camera && generation == photoGeneration &&
                    cacheGeneration == thumbnailDiskCache.generation) keepSharperThumbnail(asset.handle, bitmap)
                if (bitmap == null) logDiagnostic("THUMBNAIL_LOAD_FAILED", "THUMBNAIL", session.value?.transport, level = "WARN", result = "FAILED", message = "Unable to decode thumbnail ${asset.name}")
            }.onFailure {
                logDiagnostic("THUMBNAIL_LOAD_FAILED", "THUMBNAIL", session.value?.transport, level = "WARN", result = "FAILED", message = it.message ?: "Unable to load thumbnail ${asset.name}", error = it)
            }
            thumbnailLoads.remove(loadKey)
        }
    }

    suspend fun loadOriginalPreview(asset: PhotoAsset): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = client?.download(asset) { _, _ -> } ?: return@runCatching null
            decodePreviewBitmap(bytes)
        }.onFailure { logDiagnostic("ORIGINAL_LOAD_FAILED", "ORIGINAL_PREVIEW", session.value?.transport, level = "ERROR", result = "FAILED", message = it.message ?: "Unable to load original ${asset.name}", error = it) }.getOrNull()
    }

    private suspend fun readCameraOriginal(active: CameraClient, asset: PhotoAsset): ByteArray {
        val context = currentCoroutineContext()
        val output = java.io.ByteArrayOutputStream(asset.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        active.downloadTo(asset, output, { _, _ -> }, isCancelled = { !context.isActive })
        context.ensureActive()
        return output.toByteArray()
    }

    suspend fun loadOriginalPhoto(asset: PhotoAsset): LoadedPhoto? = withContext(Dispatchers.IO) {
        runCatching {
            val camera = client ?: return@runCatching null
            val cacheGeneration = thumbnailDiskCache.generation
            val bytes = readCameraOriginal(camera, asset)
            val bitmap = decodePreviewBitmap(bytes) ?: return@runCatching null
            val thumbnail = scaledThumbnail(bitmap)
            cacheThumbnail(thumbnailDiskKey(camera, asset), thumbnail, cacheGeneration)
            withContext(Dispatchers.Main) {
                if (client === camera && cacheGeneration == thumbnailDiskCache.generation) keepSharperThumbnail(asset.handle, thumbnail)
            }
            val fallback = PhotoMetadata(cameraModel = session.value?.name, capturedAt = asset.capturedAt)
            LoadedPhoto(bitmap, ExifMetadataReader.fromBytes(bytes, fallback), bytes)
        }.onFailure { if (it is CancellationException) throw it; logDiagnostic("ORIGINAL_LOAD_FAILED", "ORIGINAL_PREVIEW", session.value?.transport, level = "ERROR", result = "FAILED", message = it.message ?: "Unable to load original ${asset.name}", error = it) }.getOrNull()
    }

    private fun decodePreviewBitmap(bytes: ByteArray, maxEdge: Int = 2560): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val oriented = OrientedBitmaps.orient(decoded, OrientedBitmaps.orientation(bytes))
        val longest = maxOf(oriented.width, oriented.height)
        if (longest <= maxEdge) return oriented
        val factor = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            oriented,
            (oriented.width * factor).roundToInt().coerceAtLeast(1),
            (oriented.height * factor).roundToInt().coerceAtLeast(1),
            true,
        ).also { if (it !== oriented) oriented.recycle() }
    }

    fun importLut(uri: Uri, onFinished: () -> Unit = {}) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val resolver = app.contentResolver
                val sourceName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    cursor.takeIf { it.moveToFirst() }?.getString(0)
                }?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "LUT"
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取预设文件")
                val imported = LutImporters.parse(sourceName, bytes)
                val lut = imported.lut
                val id = "${System.currentTimeMillis()}_${lut.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.${imported.format.lowercase()}"
                val root = app.filesDir.resolve("luts").apply { mkdirs() }
                val sourceFile = root.resolve(id)
                val binaryFile = root.resolve("$id.nlut")
                val tempSource = root.resolve(".$id.tmp")
                val tempBinary = root.resolve(".$id.nlut.tmp")
                try {
                    tempSource.writeBytes(bytes); tempSource.renameTo(sourceFile)
                    tempBinary.outputStream().use { LutBinaryCodec.write(lut, it) }; tempBinary.renameTo(binaryFile)
                } catch (error: Exception) {
                    tempSource.delete(); tempBinary.delete(); sourceFile.delete(); binaryFile.delete(); throw error
                }
                LutEntry(id, lut.name, format = imported.format, dimension = lut.size, sourceName = sourceName) to lut
            }
        }.onSuccess { (entry, _) ->
            luts.value = luts.value + entry
            store.saveLuts(luts.value)
            showSnackbar("已导入预设：${entry.name}")
        }.onFailure { showError(it.message ?: "导入预设失败") }
        onFinished()
    }

    private fun scaledThumbnail(bitmap: Bitmap, maxEdge: Int = 960): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val factor = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * factor).roundToInt().coerceAtLeast(1),
            (bitmap.height * factor).roundToInt().coerceAtLeast(1), true)
    }

    private fun keepSharperThumbnail(handle: UInt, candidate: Bitmap) {
        val current = thumbnails[handle]
        if (current == null || candidate.width.toLong() * candidate.height > current.width.toLong() * current.height) {
            thumbnails[handle] = candidate
            cameraThumbnailCache.put(handle, candidate)
        }
    }

    suspend fun readLut(entry: LutEntry): CubeLut = withContext(Dispatchers.IO) {
        lutCache.get(entry.id)?.let { return@withContext it }
        val app = getApplication<Application>()
        val binary = app.filesDir.resolve("luts").resolve("${entry.id}.nlut")
        val parsed = if (binary.isFile) LutBinaryCodec.read(entry.name, binary.inputStream()) else {
            val source = app.filesDir.resolve("luts").resolve(entry.id).readText()
            CubeLuts.parse(entry.name, source).also { lut ->
                runCatching { binary.outputStream().use { LutBinaryCodec.write(lut, it) } }
            }
        }
        lutCache.put(entry.id, parsed)
        parsed
    }

    fun removeLut(entry: LutEntry) {
        if (remotePreviewColor.value.entry?.id == entry.id) selectRemotePreviewLut(null)
        getApplication<Application>().filesDir.resolve("luts").resolve(entry.id).delete()
        luts.value = luts.value.filterNot { it.id == entry.id }
        store.saveLuts(luts.value)
        recentLutIds.value = recentLutIds.value.filterNot { it == entry.id }
        store.saveRecentLuts(recentLutIds.value)
        showSnackbar("已删除预设")
    }

    fun updateLut(entry: LutEntry, name: String = entry.name, category: LutCategory = entry.category) {
        val updated = entry.copy(name = name.trim().ifBlank { entry.name }, category = category)
        luts.value = luts.value.map { if (it.id == entry.id) updated else it }
        store.saveLuts(luts.value)
        when {
            updated.name != entry.name -> showSnackbar("已重命名预设")
            updated.category != entry.category -> showSnackbar("已修改预设分类")
        }
    }

    suspend fun loadLut(uri: Uri): Result<CubeLut> = withContext(Dispatchers.IO) {
        runCatching {
            val source = getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("无法读取预设文件")
            CubeLuts.parse(uri.lastPathSegment ?: "LUT", source)
        }
    }

    fun exportLut(asset: PhotoAsset, bitmap: Bitmap, lut: CubeLut, inline: Boolean = false, onFinished: (Result<DownloadRecord>) -> Unit = {}) = viewModelScope.launch {
        isBusy.value = true; workflow.value = Workflow.DOWNLOADING
        if (!inline) notice.value = "正在导出 ${asset.name}"
        logDiagnostic("LUT_EXPORT_STARTED", "LUT_EXPORT", session.value?.transport, message = "Exporting ${asset.name} with LUT ${lut.name}")
        updateExportNotif(asset.name, 0, 0)
        val result = runCatching { withContext(Dispatchers.IO) { saveLutBitmap(asset, bitmap, lut.name) } }
        result.onSuccess { record ->
            logDiagnostic("LUT_EXPORT_SUCCEEDED", "LUT_EXPORT", session.value?.transport, message = "LUT export completed: ${record.name}")
            recordDownload(record); workflow.value = Workflow.CONNECTED; if (!inline) showTransient("已导出调色图片")
        }.onFailure {
            logDiagnostic("LUT_EXPORT_FAILED", "LUT_EXPORT", session.value?.transport, level = "ERROR", result = "FAILED", message = it.message ?: "LUT export failed", error = it)
            if (inline) workflow.value = Workflow.CONNECTED else showError(it.message ?: "导出调色图片失败", event = "LUT_EXPORT_FAILED", transport = session.value?.transport)
        }
        clearExportNotif(); isBusy.value = false; onFinished(result)
    }

    fun exportLutDownload(record: DownloadRecord, bitmap: Bitmap, lut: CubeLut, inline: Boolean = false, onFinished: (Result<DownloadRecord>) -> Unit = {}) = viewModelScope.launch {
        isBusy.value = true; workflow.value = Workflow.DOWNLOADING
        if (!inline) notice.value = "正在导出 ${record.name}"
        logDiagnostic("LUT_EXPORT_STARTED", "LUT_EXPORT", message = "Exporting local ${record.name} with LUT ${lut.name}")
        updateExportNotif(record.name, 0, 0)
        val result = runCatching { withContext(Dispatchers.IO) { saveLutBitmap(PhotoAsset(0u, record.name, record.size, 0x3801), bitmap, lut.name) } }
        result.onSuccess { output ->
            logDiagnostic("LUT_EXPORT_SUCCEEDED", "LUT_EXPORT", message = "Local LUT export completed: ${output.name}")
            recordDownload(output); workflow.value = Workflow.CONNECTED; if (!inline) showTransient("已导出调色图片")
        }.onFailure {
            logDiagnostic("LUT_EXPORT_FAILED", "LUT_EXPORT", level = "ERROR", result = "FAILED", message = it.message ?: "LUT export failed", error = it)
            if (inline) workflow.value = Workflow.CONNECTED else showError(it.message ?: "导出调色图片失败", event = "LUT_EXPORT_FAILED")
        }
        clearExportNotif(); isBusy.value = false; onFinished(result)
    }

    private fun <T> launchBatchEdits(
        items: List<T>,
        title: String,
        supported: (T) -> Boolean,
        fileName: (T) -> String,
        onFinished: () -> Unit,
        process: suspend (T) -> DownloadRecord,
    ): Job {
        batchEditJob?.takeIf { it.isActive }?.let {
            showSnackbar("已有批量任务正在处理，请稍候")
            return it
        }
        val job = viewModelScope.launch {
            editMutex.withLock {
                isBusy.value = true
                var completed = 0
                var unsupported = 0
                val failed = mutableListOf<T>()
                showPageProgress("正在$title 0 / ${items.size}")
                try {
                    items.forEachIndexed { index, item ->
                        if (!supported(item)) unsupported++
                        else try {
                            withContext(Dispatchers.IO) { store.addDownload(process(item)) }
                            completed++
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            failed += item
                            logDiagnostic("EDIT_EXPORT_FAILED", "EDIT_EXPORT_BATCH", level = "WARN", result = "FAILED", message = "${fileName(item)}: ${error.message}", error = error)
                        }
                        showPageProgress("正在$title ${index + 1} / ${items.size}")
                        updateExportNotif(fileName(item), index + 1, items.size)
                    }
                    downloads.value = withContext(Dispatchers.IO) { store.downloads() }
                    val message = "已导出 $completed 张，失败 ${failed.size} 张，跳过 $unsupported 个不支持的文件"
                    if (failed.isNotEmpty()) {
                        pageRetry = { launchBatchEdits(failed.toList(), title, supported, fileName, onFinished, process); Unit }
                        showPageError(message)
                    } else {
                        pageRetry = null
                        showPageSuccess(message)
                    }
                    onFinished()
                } finally {
                    clearExportNotif()
                    isBusy.value = false
                }
            }
        }
        batchEditJob = job
        return job
    }

    fun downloadAllWithLut(assets: List<PhotoAsset>, lut: CubeLut, onFinished: () -> Unit = {}): Job {
        val sourceSession = session.value
        return launchBatchEdits(assets, "套用预设", { it.name.supportsLutInput() }, { it.name }, onFinished) { asset ->
            check(asset.downloadSourceKey(session.value) == asset.downloadSourceKey(sourceSession)) { "请连接创建任务时的原相机后重试" }
            val active = client ?: error("相机连接已断开")
            val bytes = active.download(asset) { _, _ -> }
            renderAndSaveOriginal(asset, bytes, lut, null, lut.name, config.value.jpegQuality, PhotoMetadata(capturedAt = asset.capturedAt))
        }
    }

    fun applyLutToDownloads(records: List<DownloadRecord>, lut: CubeLut, onFinished: () -> Unit = {}): Job =
        launchBatchEdits(records, "套用预设", { it.name.supportsLutInput() && !it.name.isVideoName() }, { it.name }, onFinished) { record ->
            val bytes = getApplication<Application>().contentResolver.openInputStream(Uri.parse(record.uri))?.use { it.readBytes() }
                ?: error("无法读取 ${record.name}")
            renderAndSaveOriginal(PhotoAsset(0u, record.name, record.size, 0x3801), bytes, lut, null, lut.name, config.value.jpegQuality, PhotoMetadata(capturedAt = Date(record.completedAt)))
        }

    fun exportEditedPhoto(
        asset: PhotoAsset,
        lut: CubeLut?,
        watermark: WatermarkPreset?,
        suffix: String,
        quality: Int = config.value.jpegQuality,
        rotation: Int = 0,
        sourceBytes: ByteArray? = null,
        lutIntensity: Float = 1f,
        inline: Boolean = false,
        onFinished: (Result<DownloadRecord>) -> Unit = {},
    ): Job = launchEditedExport(asset.name, session.value?.transport, inline, onFinished) {
        val bytes = sourceBytes ?: (client ?: error("相机连接已断开")).download(asset) { _, _ -> }
        renderAndSaveOriginal(
            asset = asset,
            bytes = bytes,
            lut = lut,
            lutIntensity = lutIntensity,
            watermark = watermark,
            suffix = suffix,
            quality = quality,
            rotation = rotation,
            fallback = PhotoMetadata(cameraModel = session.value?.name, capturedAt = asset.capturedAt),
        )
    }

    fun exportEditedDownload(
        record: DownloadRecord,
        lutEntry: LutEntry?,
        watermark: WatermarkPreset?,
        suffix: String,
        quality: Int = config.value.jpegQuality,
        rotation: Int = 0,
        sourceBytes: ByteArray? = null,
        lutIntensity: Float = 1f,
        inline: Boolean = false,
        onFinished: (Result<DownloadRecord>) -> Unit = {},
    ): Job = launchEditedExport(record.name, null, inline, onFinished) {
        if (getApplication<Application>().mediaKind(record) == MediaKind.VIDEO) error("视频暂不支持调色和水印")
        val bytes = sourceBytes ?: getApplication<Application>().contentResolver.openInputStream(Uri.parse(record.uri))?.use { it.readBytes() }
            ?: error("无法读取 ${record.name}")
        renderAndSaveOriginal(
            asset = PhotoAsset(0u, record.name, record.size, 0x3801),
            bytes = bytes,
            lut = lutEntry?.let { readLut(it) },
            lutIntensity = lutIntensity,
            watermark = watermark,
            suffix = suffix,
            quality = quality,
            rotation = rotation,
            fallback = PhotoMetadata(capturedAt = Date(record.completedAt)),
        )
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun exportEditedVideo(
        record: DownloadRecord,
        lut: CubeLut?,
        lutIntensity: Float,
        clockwiseQuarterTurns: Int,
        suffix: String,
        onProgress: (Int) -> Unit = {},
        onFinished: (Result<DownloadRecord>) -> Unit = {},
    ): Job = viewModelScope.launch {
        editMutex.withLock {
            require(getApplication<Application>().mediaKind(record) == MediaKind.VIDEO) { "只能导出视频文件" }
            var temp: java.io.File? = null
            try {
                isBusy.value = true
                workflow.value = Workflow.DOWNLOADING
                val app = getApplication<Application>()
                val tempDirectory = app.externalCacheDir ?: app.cacheDir
                val result = runCatching {
                    requireVideoExportSpace(tempDirectory, record.size.coerceAtLeast(256L * 1024 * 1024) + 256L * 1024 * 1024, "生成视频")
                    val outputFile = java.io.File.createTempFile("camera_bridge_video_", ".mp4", tempDirectory).also { temp = it }
                    logDiagnostic("VIDEO_EXPORT_STARTED", "VIDEO_EXPORT", message = "Hardware export started: ${record.name}")
                    updateVideoExportNotif(record.name, 0)
                    VideoLutExporter.export(
                        context = getApplication(),
                        input = Uri.parse(record.uri),
                        output = outputFile,
                        lut = lut,
                        intensity = lutIntensity,
                        clockwiseQuarterTurns = clockwiseQuarterTurns,
                    ) { progress ->
                        onProgress(progress)
                        updateVideoExportNotif(record.name, progress)
                    }
                    requireVideoExportSpace(Environment.getExternalStorageDirectory(), outputFile.length() + 128L * 1024 * 1024, "保存到相册")
                    withContext(Dispatchers.IO) { saveExportedVideo(record, outputFile, suffix) }
                }
                temp?.delete()
                result.onSuccess { output ->
                    recordDownload(output)
                    logDiagnostic("VIDEO_EXPORT_SUCCEEDED", "VIDEO_EXPORT", message = "Video export completed: ${output.name}")
                    showSnackbar(if (config.value.autoExport) "视频已导出到相册" else "视频已保存到下载目录")
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    logDiagnostic("VIDEO_EXPORT_FAILED", "VIDEO_EXPORT", level = "ERROR", result = "FAILED", message = error.message ?: "Video export failed", error = error)
                    showSnackbar("视频导出失败：${error.message ?: "未知错误"}")
                }
                workflow.value = if (session.value == null) Workflow.WAITING else Workflow.CONNECTED
                clearExportNotif()
                isBusy.value = false
                onFinished(result)
            } finally {
                temp?.delete()
                isBusy.value = false
                clearExportNotif()
                if (workflow.value == Workflow.DOWNLOADING) workflow.value = if (session.value == null) Workflow.WAITING else Workflow.CONNECTED
            }
        }
    }


    private fun requireVideoExportSpace(directory: java.io.File, requiredBytes: Long, stage: String) {
        val available = StatFs(directory.absolutePath).availableBytes
        require(available >= requiredBytes) { "${stage}所需空间不足：至少需要 ${requiredBytes.prettySize()}，当前可用 ${available.prettySize()}" }
    }

    private fun cleanupInterruptedFiles() {
        val app = getApplication<Application>()
        listOfNotNull(app.cacheDir, app.externalCacheDir).distinctBy { it.absolutePath }.forEach { directory ->
            directory.listFiles { file -> file.name.startsWith("camera_bridge_video_") && file.extension == "mp4" }
                ?.forEach { it.delete() }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = app.contentResolver
        val pendingFolders = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_PICTURES}/Link/",
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_MOVIES}/Link/",
            MediaStore.Downloads.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_DOWNLOADS}/Link/",
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_PICTURES}/Camera_Bridge/",
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_MOVIES}/Camera_Bridge/",
            MediaStore.Downloads.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_DOWNLOADS}/Camera_Bridge/",
        )
        pendingFolders.forEach { (collection, folder) ->
            val ids = mutableListOf<Long>()
            runCatching {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(app.packageName, folder),
                    null,
                )?.use { cursor -> while (cursor.moveToNext()) ids += cursor.getLong(0) }
            }
            ids.forEach { id -> runCatching { resolver.delete(ContentUris.withAppendedId(collection, id), null, null) } }
        }
    }

    private fun launchEditedExport(
        sourceName: String,
        transport: ConnectionTransport?,
        inline: Boolean,
        onFinished: (Result<DownloadRecord>) -> Unit,
        export: suspend () -> DownloadRecord,
    ): Job = viewModelScope.launch {
        editMutex.withLock {
            try {
                isBusy.value = true
                workflow.value = Workflow.DOWNLOADING
                if (!inline) notice.value = "正在导出 $sourceName"
                logDiagnostic("WATERMARK_EXPORT_STARTED", "EDIT_EXPORT", transport, message = "Exporting original file: $sourceName")
                updateExportNotif(sourceName, 0, 0)
                val result = runCatching { withContext(Dispatchers.IO) { export() } }
                result.onSuccess { record ->
                    recordDownload(record)
                    workflow.value = if (session.value == null) Workflow.WAITING else Workflow.CONNECTED
                    logDiagnostic("WATERMARK_EXPORT_SUCCEEDED", "EDIT_EXPORT", transport, message = "Edit export completed: ${record.name}")
                    if (!inline) showTransient("已导出编辑图片")
                }.onFailure {
                    if (it is CancellationException) throw it
                    workflow.value = if (session.value == null) Workflow.WAITING else Workflow.CONNECTED
                    logDiagnostic("WATERMARK_EXPORT_FAILED", "EDIT_EXPORT", transport, level = "ERROR", result = "FAILED", message = it.message ?: "Edit export failed", error = it)
                    if (!inline) showError(it.message ?: "导出编辑图片失败", event = "WATERMARK_EXPORT_FAILED", transport = transport)
                }
                clearExportNotif()
                isBusy.value = false
                onFinished(result)
            } finally {
                isBusy.value = false
                clearExportNotif()
                if (workflow.value == Workflow.DOWNLOADING) workflow.value = if (session.value == null) Workflow.WAITING else Workflow.CONNECTED
            }
        }
    }


    private fun renderAndSaveOriginal(
        asset: PhotoAsset,
        bytes: ByteArray,
        lut: CubeLut?,
        watermark: WatermarkPreset?,
        suffix: String,
        quality: Int,
        fallback: PhotoMetadata,
        rotation: Int = 0,
        lutIntensity: Float = 1f,
    ): DownloadRecord {
        val original = OrientedBitmaps.decode(bytes) ?: error("无法解码 ${asset.name}")
        var graded: Bitmap? = null
        var rotated: Bitmap? = null
        var watermarked: Bitmap? = null
        return try {
            val metadata = ExifMetadataReader.fromBytes(bytes, fallback)
            val working = if (lut == null) original else CubeLuts.apply(original, lut, lutIntensity, inPlace = true).also { graded = it }
            if (working !== original) original.recycle()
            val selected = OrientedBitmaps.rotate(working, rotation).also { if (it !== working) rotated = it }
            if (selected !== working && !working.isRecycled) working.recycle()
            val output = if (watermark == null) selected else WatermarkRenderer.render(selected, metadata, watermark, getApplication()).also { watermarked = it }
            saveEditedBitmap(asset, output, metadata, suffix, quality)
        } finally {
            watermarked?.takeIf { !it.isRecycled }?.recycle()
            rotated?.takeIf { !it.isRecycled }?.recycle()
            graded?.takeIf { !it.isRecycled }?.recycle()
            if (!original.isRecycled) original.recycle()
        }
    }

    fun addWatermarkToPhotos(assets: List<PhotoAsset>, preset: WatermarkPreset, onFinished: () -> Unit = {}): Job = processRemoteEdits(assets, null, preset, onFinished)

    fun applyLutAndWatermarkToPhotos(assets: List<PhotoAsset>, lut: CubeLut, preset: WatermarkPreset, onFinished: () -> Unit = {}): Job = processRemoteEdits(assets, lut, preset, onFinished)

    fun addWatermarkToDownloads(records: List<DownloadRecord>, preset: WatermarkPreset, onFinished: () -> Unit = {}): Job = processLocalEdits(records, null, preset, onFinished)

    fun applyLutAndWatermarkToDownloads(records: List<DownloadRecord>, lut: CubeLut, preset: WatermarkPreset, onFinished: () -> Unit = {}): Job = processLocalEdits(records, lut, preset, onFinished)

    private fun processRemoteEdits(assets: List<PhotoAsset>, lut: CubeLut?, preset: WatermarkPreset, onFinished: () -> Unit): Job {
        val sourceSession = session.value
        val title = if (lut == null) "添加水印" else "调色 + 水印"
        val suffix = if (lut == null) "Watermark" else "${lut.name}_Watermark"
        return launchBatchEdits(assets, title, { it.name.supportsLutInput() }, { it.name }, onFinished) { asset ->
            check(asset.downloadSourceKey(session.value) == asset.downloadSourceKey(sourceSession)) { "请连接创建任务时的原相机后重试" }
            val active = client ?: error("相机连接已断开")
            val bytes = active.download(asset) { _, _ -> }
            renderAndSaveOriginal(asset, bytes, lut, preset, suffix, preset.quality, PhotoMetadata(cameraModel = session.value?.name, capturedAt = asset.capturedAt))
        }
    }

    private fun processLocalEdits(records: List<DownloadRecord>, lut: CubeLut?, preset: WatermarkPreset, onFinished: () -> Unit): Job {
        val title = if (lut == null) "添加水印" else "调色 + 水印"
        val suffix = if (lut == null) "Watermark" else "${lut.name}_Watermark"
        return launchBatchEdits(records, title, { it.name.supportsLutInput() && !it.name.isVideoName() }, { it.name }, onFinished) { record ->
            val bytes = getApplication<Application>().contentResolver.openInputStream(Uri.parse(record.uri))?.use { it.readBytes() }
                ?: error("无法读取 ${record.name}")
            renderAndSaveOriginal(PhotoAsset(0u, record.name, record.size, 0x3801), bytes, lut, preset, suffix, preset.quality, PhotoMetadata(capturedAt = Date(record.completedAt)))
        }
    }

    suspend fun loadLocalOriginal(uri: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = getApplication<Application>().contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() }
                ?: return@runCatching null
            OrientedBitmaps.decode(bytes)
        }.getOrNull()
    }

    suspend fun loadLocalPhoto(record: DownloadRecord): LoadedPhoto? = withContext(Dispatchers.IO) {
        if (getApplication<Application>().mediaKind(record) == MediaKind.VIDEO) return@withContext null
        runCatching {
            val parsed = Uri.parse(record.uri)
            val bytes = getApplication<Application>().contentResolver.openInputStream(parsed)?.use { it.readBytes() }
                ?: return@runCatching null
            val bitmap = decodePreviewBitmap(bytes) ?: return@runCatching null
            val fallback = PhotoMetadata(capturedAt = Date(record.completedAt))
            LoadedPhoto(bitmap, ExifMetadataReader.fromBytes(bytes, fallback), bytes)
        }.getOrNull()
    }

    fun enqueueDownload(asset: PhotoAsset, sourceBytes: ByteArray? = null): EnqueueDownloadResult {
        val result = synchronized(downloadLock) {
            val key = asset.downloadSourceKey(session.value)
            if (_downloadTasks.value.any { it.id == key }) EnqueueDownloadResult.ALREADY_EXISTS
            else {
                sourceBytes?.let { queuedOriginals[key] = it }
                _downloadTasks.value = _downloadTasks.value + DownloadTask(id = key, asset = asset)
                EnqueueDownloadResult.CREATED
            }
        }
        if (result == EnqueueDownloadResult.CREATED) startDownloadWorker()
        return result
    }

    fun showDownloadEnqueueMessage(asset: PhotoAsset, result: EnqueueDownloadResult) {
        showSnackbar(if (result == EnqueueDownloadResult.CREATED) "已创建下载任务：${asset.name}" else "该文件已有下载任务")
    }

    fun enqueueDownloads(assets: List<PhotoAsset>): Pair<Int, Int> {
        var created = 0
        var existing = 0
        synchronized(downloadLock) {
            val known = _downloadTasks.value.mapTo(mutableSetOf()) { it.id }
            val added = assets.filter { asset ->
                val id = asset.downloadSourceKey(session.value)
                if (id in known) { existing++; false } else { known += id; created++; true }
            }.map { DownloadTask(id = it.downloadSourceKey(session.value), asset = it) }
            if (added.isNotEmpty()) _downloadTasks.value = _downloadTasks.value + added
        }
        if (created > 0) startDownloadWorker()
        return created to existing
    }

    fun retryDownloadTask(id: String) {
        synchronized(downloadLock) {
            _downloadTasks.update { tasks -> tasks.map { task -> if (task.id == id && task.status == DownloadTaskStatus.FAILED) task.copy(downloadedBytes = 0, totalBytes = task.asset.size.coerceAtLeast(0), bytesPerSecond = null, remainingSeconds = null, status = DownloadTaskStatus.QUEUED, errorMessage = null) else task } }
        }
        startDownloadWorker()
    }

    fun cancelDownloadTask(id: String) {
        var cancelling: DownloadTask? = null
        synchronized(downloadLock) {
            cancelling = _downloadTasks.value.firstOrNull { it.id == id }
            if (cancelling?.status == DownloadTaskStatus.DOWNLOADING) {
                cancelledDownloadIds += id
                _downloadTasks.update { tasks -> tasks.map { if (it.id == id) it.copy(status = DownloadTaskStatus.CANCELLING) else it } }
            } else if (cancelling?.status == DownloadTaskStatus.QUEUED) {
                queuedOriginals.remove(id)
                _downloadTasks.update { tasks -> tasks.filterNot { it.id == id } }
            }
        }
        cancelling?.let { task ->
            logDiagnostic("DOWNLOAD_CANCEL_REQUESTED", "DOWNLOAD", session.value?.transport, result = "REQUESTED", message = "taskId=$id ${task.asset.name}")
            if (task.status == DownloadTaskStatus.DOWNLOADING) {
                logDiagnostic("DOWNLOAD_CANCELLING", "DOWNLOAD", session.value?.transport, result = "DRAINING", message = "taskId=$id ${task.asset.name}")
                logDiagnostic("DOWNLOAD_CANCEL_DRAIN_STARTED", "DOWNLOAD", session.value?.transport, result = "DRAINING", message = "taskId=$id ${task.asset.name}")
            }
            if (task.status == DownloadTaskStatus.QUEUED) showSnackbar("已取消下载")
        }
    }

    private suspend fun recordDownload(record: DownloadRecord) {
        downloads.value = withContext(Dispatchers.IO) {
            store.addDownload(record)
            store.downloads()
        }
    }

    private fun startDownloadWorker() {
        if (downloadWorkerJob?.isActive == true) return
        downloadWorkerJob = viewModelScope.launch { processDownloadQueue() }
    }

    private suspend fun processDownloadQueue() {
        store.setDownloadWorkerActive(true)
        try {
        while (true) {
            // New downloads may be queued during a countdown; do not interleave with a shutter.
            while (remoteCapture.value.inProgress || remoteParameterEditor.value.busy || remoteFocus.value.inProgress) delay(200)
            val task = synchronized(downloadLock) {
                val queued = _downloadTasks.value.firstOrNull { it.status == DownloadTaskStatus.QUEUED } ?: return@synchronized null
                _downloadTasks.value = _downloadTasks.value.map { if (it.id == queued.id) it.copy(status = DownloadTaskStatus.DOWNLOADING) else it }
                queued
            } ?: break
            val result = runCatching {
                val original = synchronized(downloadLock) { queuedOriginals.remove(task.id) }
                if (original == null) require(task.belongsTo(session.value)) { "请连接创建任务时的原相机后重试" }
                val active = client
                if (original == null && active == null) error("相机连接已断开")
                withContext(Dispatchers.IO) {
                    var lastAt = SystemClock.elapsedRealtime()
                    var lastDone = 0L
                    var smoothedSpeed: Double? = null
                    var lastEmit = 0L
                    saveToMediaStore(task.asset, config.value.autoExport) { stream ->
                        if (original != null) {
                            var offset = 0
                            while (offset < original.size) {
                                if (synchronized(downloadLock) { task.id in cancelledDownloadIds }) throw DownloadCancelledException()
                                val count = minOf(1024 * 1024, original.size - offset)
                                stream.write(original, offset, count)
                                offset += count
                            }
                            original.size.toLong()
                        } else requireNotNull(active).downloadTo(task.asset, stream, { done, callbackTotal ->
                            val now = SystemClock.elapsedRealtime()
                            val (total, _) = downloadProgress(done, callbackTotal, task.asset.size)
                            val normalizedDone = normalizedDownloadBytes(done, total)
                            val elapsed = now - lastAt
                            if (elapsed > 0 && normalizedDone >= lastDone) {
                                val speed = (normalizedDone - lastDone) * 1000.0 / elapsed
                                if (speed > 0) smoothedSpeed = smoothedSpeed?.let { it * .75 + speed * .25 } ?: speed
                            }
                            val shouldEmit = now - lastEmit >= 200 || (total > 0 && normalizedDone >= total)
                            if (shouldEmit) {
                                lastAt = now; lastDone = normalizedDone; lastEmit = now
                                val remaining = (total - normalizedDone).coerceAtLeast(0)
                                val eta = smoothedSpeed?.takeIf { it > 0 && remaining > 0 }?.let { (remaining / it).toLong().takeIf { seconds -> seconds >= 0 } }
                                _downloadTasks.update { tasks -> tasks.map { current -> if (current.id == task.id) current.copy(downloadedBytes = normalizedDone, totalBytes = total, bytesPerSecond = smoothedSpeed?.toLong(), remainingSeconds = eta) else current } }
                                updateDownloadNotif(task.asset.name, normalizedDone, total)
                            }
                        }, isCancelled = { synchronized(downloadLock) { task.id in cancelledDownloadIds } })
                    }
                }
            }
            result.onSuccess { record ->
                val cancelled = synchronized(downloadLock) { task.id in cancelledDownloadIds }
                if (cancelled) {
                    logDiagnostic("DOWNLOAD_CANCEL_DRAIN_COMPLETED", "DOWNLOAD", session.value?.transport, result = "DRAINED", message = "taskId=${task.id} ${task.asset.name}")
                    val deleted = runCatching { getApplication<Application>().contentResolver.delete(Uri.parse(record.uri), null, null) }.getOrDefault(0)
                    if (deleted <= 0) logDiagnostic("DOWNLOAD_CANCEL_CLEANUP_FAILED", "DOWNLOAD", session.value?.transport, level = "WARN", result = "FAILED", message = "taskId=${task.id} ${task.asset.name}")
                    synchronized(downloadLock) { cancelledDownloadIds.remove(task.id) }
                    _downloadTasks.update { it.filterNot { taskItem -> taskItem.id == task.id } }
                    logDiagnostic("DOWNLOAD_CANCELLED", "DOWNLOAD", session.value?.transport, result = "CANCELLED", message = "taskId=${task.id} ${task.asset.name}")
                    showSnackbar("已取消下载")
                } else {
                    recordDownload(record)
                    val kind = mediaKindFor(null, record.name)
                    if (kind != MediaKind.VIDEO) {
                        withContext(Dispatchers.IO) {
                            loadDownloadThumbnail(getApplication(), record.uri, kind)
                        }?.let { keepSharperThumbnail(task.asset.handle, it) }
                    }
                    _downloadTasks.update { it.filterNot { taskItem -> taskItem.id == task.id } }
                    showSnackbar("下载完成，可到下载页查看")
                }
                clearDownloadNotif()
            }.onFailure { error ->
                val cancelRequested = synchronized(downloadLock) { cancelledDownloadIds.remove(task.id) }
                if (isUserDownloadCancellation(error, cancelRequested)) {
                    logDiagnostic("DOWNLOAD_CANCEL_DRAIN_COMPLETED", "DOWNLOAD", session.value?.transport, result = "DRAINED", message = "taskId=${task.id} ${task.asset.name}")
                    _downloadTasks.update { it.filterNot { taskItem -> taskItem.id == task.id } }
                    logDiagnostic("DOWNLOAD_CANCELLED", "DOWNLOAD", session.value?.transport, result = "CANCELLED", message = "taskId=${task.id} ${task.asset.name}")
                    showSnackbar("已取消下载")
                    clearDownloadNotif()
                } else if (error is CancellationException) {
                    clearDownloadNotif()
                    throw error
                } else {
                    _downloadTasks.update { tasks -> tasks.map { if (it.id == task.id) it.copy(status = DownloadTaskStatus.FAILED, errorMessage = error.message ?: "未知错误") else it } }
                    showSnackbar("下载失败：${error.message ?: "未知错误"}")
                    clearDownloadNotif()
                }
            }
        }
        } finally {
            store.setDownloadWorkerActive(false)
            clearDownloadNotif()
            synchronized(downloadLock) { queuedOriginals.clear() }
        }
    }

    fun downloadAll(assets: List<PhotoAsset>): Job = viewModelScope.launch {
        val (created, existing) = enqueueDownloads(assets)
        showSnackbar(if (existing == 0) "已创建 $created 个下载任务" else "已创建 $created 个任务，$existing 个文件已有下载任务")
    }

    fun retryDownload() { _downloadTasks.value.firstOrNull { it.status == DownloadTaskStatus.FAILED }?.let { retryDownloadTask(it.id) } }

    fun removeDownloadRecords(uris: Set<String>): Job = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        val (removed, remaining) = withContext(Dispatchers.IO) {
            val existing = store.downloads().mapTo(mutableSetOf()) { it.uri }.intersect(uris)
            store.removeDownloads(existing)
            existing to store.downloads()
        }
        removed.forEach { localThumbnailCache.remove(it) }
        thumbnailVersion.value += 1
        downloads.value = remaining
        logDiagnostic("DOWNLOAD_RECORDS_REMOVED", "DELETE", message = "Removed ${removed.size} local records without modifying media files")
        val message = "已移除 ${removed.size} 条下载记录，相册文件不受影响"
        showPageSuccess(message)
        showSnackbar(message)
    }

    fun deleteDownloads(uris: Set<String>): Job = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        logDiagnostic("DELETE_STARTED", "DELETE", message = "Deleting ${uris.size} downloaded files")
        showPageProgress("正在删除 ${uris.size} 个文件")
        val resolver = getApplication<Application>().contentResolver
        var inaccessibleCount = 0
        val (deleted, missing, remainingRecords) = withContext(Dispatchers.IO) {
            val deleted = mutableSetOf<String>()
            val missing = mutableSetOf<String>()
            for (value in uris) {
                val uri = Uri.parse(value)
                try {
                    if (resolver.delete(uri, null, null) > 0) {
                        deleted += value
                    } else {
                        // A successful empty MediaStore query confirms a stale record.
                        // Null cursors and permission errors must not count as missing files.
                        val absent = uri.authority == MediaStore.AUTHORITY &&
                            resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                                ?.use { !it.moveToFirst() } == true
                        if (absent) missing += value
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: SecurityException) {
                    inaccessibleCount += 1
                    logDiagnostic("DELETE_FAILED", "DELETE", level = "WARN", result = "FAILED", message = "No media access for $value; local record can be removed separately", error = error)
                } catch (error: Exception) {
                    logDiagnostic("DELETE_FAILED", "DELETE", level = "WARN", result = "FAILED", message = "Unable to delete $value", error = error)
                }
            }
            store.removeDownloads(deleted + missing)
            Triple(deleted, missing, store.downloads())
        }
        val removed = deleted + missing
        removed.forEach { localThumbnailCache.remove(it) }
        downloads.value = remainingRecords
        val failed = uris.size - removed.size
        val message = buildList {
            if (deleted.isNotEmpty()) add("已删除 ${deleted.size} 个文件")
            if (missing.isNotEmpty()) add("已清理 ${missing.size} 条失效记录")
            if (failed > 0) add("$failed 个未删除，可重试")
            if (inaccessibleCount > 0) add("其中 $inaccessibleCount 个无访问权限，可在操作菜单中选择‘仅移除记录’")
        }.joinToString("，")
        if (failed > 0) {
            pageRetry = { deleteDownloads(uris - removed); Unit }
            showPageError(message)
        } else showPageSuccess(message)
        showSnackbar(message)
    }

    private fun saveToMediaStore(
        asset: PhotoAsset,
        exportToGallery: Boolean,
        afterWrite: ((Uri) -> Unit)? = null,
        write: (java.io.OutputStream) -> Long,
    ): DownloadRecord {
        val resolver = getApplication<Application>().contentResolver
        val extension = asset.name.substringAfterLast('.', "").lowercase()
        val mime = when (extension) { "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "mov" -> "video/quicktime"; "mp4" -> "video/mp4"; "m4v" -> "video/x-m4v"; "3gp" -> "video/3gpp"; else -> "application/octet-stream" }
        val isVideo = mime.startsWith("video/")
        val isImage = mime.startsWith("image/")
        val scopedStorage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val collection = when {
            exportToGallery && isImage -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            exportToGallery && isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            scopedStorage -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }
        val folder = when {
            exportToGallery && isImage -> "${Environment.DIRECTORY_PICTURES}/Link"
            exportToGallery && isVideo -> "${Environment.DIRECTORY_MOVIES}/Link"
            else -> "${Environment.DIRECTORY_DOWNLOADS}/Link"
        }
        val displayName = uniqueDisplayName(resolver, collection, folder.takeIf { scopedStorage }, asset.name)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (scopedStorage) { put(MediaStore.MediaColumns.RELATIVE_PATH, folder); put(MediaStore.MediaColumns.IS_PENDING, 1) }
        }
        val uri = requireNotNull(resolver.insert(collection, values)) { "无法创建保存文件" }
        var size = 0L
        try {
            resolver.openOutputStream(uri)?.use { size = write(it) } ?: error("无法写入系统相册")
            afterWrite?.invoke(uri)
            if (size <= 0L) size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            if (scopedStorage) { values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0); resolver.update(uri, values, null, null) }
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
        return DownloadRecord(displayName, size, uri.toString(), Date().time)
    }

    private fun uniqueDisplayName(resolver: android.content.ContentResolver, collection: Uri, folder: String?, requested: String): String {
        val existing = buildSet {
            runCatching {
                resolver.query(collection, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), folder?.let { "${MediaStore.MediaColumns.RELATIVE_PATH} = ?" }, folder?.let { arrayOf(it) }, null)?.use { cursor ->
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
        val output = asset.copy(name = editedFileName(asset.name, lutName, "jpg"))
        return saveToMediaStore(output, config.value.autoExport) { stream ->
            require(bitmap.compress(Bitmap.CompressFormat.JPEG, config.value.jpegQuality, stream)) { "无法编码 JPG" }
            0L
        }
    }

    private fun saveEditedBitmap(asset: PhotoAsset, bitmap: Bitmap, metadata: PhotoMetadata, suffix: String, quality: Int): DownloadRecord {
        val output = asset.copy(name = editedFileName(asset.name, suffix, "jpg"))
        return saveToMediaStore(output, config.value.autoExport, afterWrite = { uri -> preserveExif(uri, metadata) }) { stream ->
            require(bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(80, 100), stream)) { "无法编码 JPG" }
            0L
        }
    }

    private fun saveExportedVideo(record: DownloadRecord, file: java.io.File, suffix: String): DownloadRecord {
        val output = PhotoAsset(
            handle = 0u,
            name = editedFileName(record.name, suffix, "mp4"),
            size = file.length(),
            format = 0x300C,
        )
        return saveToMediaStore(output, config.value.autoExport) { stream ->
            file.inputStream().use { it.copyTo(stream, 1024 * 1024) }
        }
    }

    private fun editedFileName(original: String, suffix: String, extension: String): String {
        val base = original.substringBeforeLast('.')
        val date = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(Date())
        val pattern = config.value.fileNamingRule.ifBlank { "原文件名_编辑类型" }
        val name = pattern.replace("{name}", base).replace("{type}", suffix).replace("{date}", date)
            .replace("原文件名", base).replace("编辑类型", suffix).replace("日期", date)
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
            .removeSuffix(".$extension").ifBlank { "${base}_$suffix" }
        return "$name.$extension"
    }

    private fun preserveExif(uri: Uri, metadata: PhotoMetadata) {
        runCatching {
            getApplication<Application>().contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                val exif = android.media.ExifInterface(descriptor.fileDescriptor)
                metadata.cameraBrand?.let { exif.setAttribute("Make", it) }
                metadata.cameraModel?.let { exif.setAttribute("Model", it) }
                metadata.lensModel?.let { exif.setAttribute("LensModel", it) }
                metadata.iso?.let { exif.setAttribute("ISOSpeedRatings", it.toString()) }
                exif.setAttribute(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL.toString(),
                )
                metadata.capturedAt?.let { date ->
                    exif.setAttribute("DateTimeOriginal", java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).format(date))
                }
                metadata.copyrightText?.let { exif.setAttribute("Copyright", it) }
                exif.saveAttributes()
            }
        }
    }

    private fun showError(message: String, event: String = "APP_ERROR", transport: ConnectionTransport? = session.value?.transport) {
        if (event == "APP_ERROR") logDiagnostic(event, "ERROR", transport, level = "ERROR", result = "FAILED", message = message)
        noticeClearJob?.cancel(); workflow.value = Workflow.ERROR; notice.value = message
    }
    override fun onCleared() {
        remoteViewVisible = false
        remotePreviewJob?.cancel()
        remoteCancellation.set(true)
        runCatching { getApplication<Application>().unregisterReceiver(usbReceiver) }
        val closing = client
        client = null
        Thread({ runCatching { closing?.close() } }, "Link-close-camera").apply { isDaemon = true; start() }
        keepConnectionAlive(false); bindCameraNetwork(null); releaseRequestedWifi(); clearDownloadNotif(); clearExportNotif(); diagnostics.close()
    }

    private fun keepConnectionAlive(enabled: Boolean) {
        val app = getApplication<Application>()
        val intent = Intent(app, ConnectionKeepAliveService::class.java)
        if (enabled) ContextCompat.startForegroundService(app, intent) else app.stopService(intent)
    }

    private fun updateDownloadNotif(name: String, done: Long, total: Long) {
        val app = getApplication<Application>()
        val determinate = total > 0
        val percent = if (determinate) (done * 100 / total).toInt().coerceIn(0, 100) else 0
        val notif = androidx.core.app.NotificationCompat.Builder(app, "camera_connection")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在下载 $name")
            .setContentText(if (determinate) "${done.prettySize()} / ${total.prettySize()} · $percent%" else "已传 ${done.prettySize()} · 总大小未知")
            .setProgress(100, percent, !determinate)
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

    private fun updateVideoExportNotif(name: String, progress: Int) {
        val app = getApplication<Application>()
        val value = progress.coerceIn(0, 100)
        val notif = androidx.core.app.NotificationCompat.Builder(app, "camera_connection")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在硬件导出视频")
            .setContentText("$name · $value%")
            .setProgress(100, value, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        app.getSystemService(android.app.NotificationManager::class.java).notify(1003, notif)
    }

    private fun clearExportNotif() {
        getApplication<Application>().getSystemService(android.app.NotificationManager::class.java).cancel(1003)
    }

    private fun cameraNetwork(): android.net.Network? = requestedCameraNetwork ?: getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        .allNetworks.firstOrNull { network ->
            getApplication<Application>().getSystemService(ConnectivityManager::class.java)
                .getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

    private fun bindCameraNetwork(network: Network?) {
        val changed = cameraNetworkBound != (network != null)
        cameraNetworkBound = network != null
        getApplication<Application>().getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(network)
        if (changed) logDiagnostic(if (network == null) "WIFI_NETWORK_LOST" else "WIFI_NETWORK_BOUND", "NETWORK_BIND", ConnectionTransport.WIFI, result = if (network == null) "UNBOUND" else "BOUND", message = if (network == null) "App unbound from camera Wi-Fi" else "App bound to camera Wi-Fi")
    }

    @Suppress("DEPRECATION")
    private fun cameraGateway(network: Network): String {
        getApplication<Application>().getSystemService(ConnectivityManager::class.java)
            .getLinkProperties(network)?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }
            ?.gateway?.hostAddress?.let { return it }
        if (requestedCameraNetwork == network) return ""
        val address = getApplication<Application>().getSystemService(WifiManager::class.java).dhcpInfo?.serverAddress ?: return ""
        if (address == 0) return ""
        return listOf(address and 0xFF, address shr 8 and 0xFF, address shr 16 and 0xFF, address shr 24 and 0xFF).joinToString(".")
    }

    fun currentSsid(): String? {
        val app = getApplication<Application>()
        @Suppress("DEPRECATION")
        val connectedSsid = app.getSystemService(WifiManager::class.java).connectionInfo?.ssid
            ?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        if (requestedCameraNetwork == null && connectedSsid != null) return connectedSsid
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val network = cameraNetwork() ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        // ponytail: read SSID from WifiInfo (API 31+: via transportInfo to avoid deprecated allNetworks info path)
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) caps.transportInfo as? android.net.wifi.WifiInfo else null
        @Suppress("DEPRECATION")
        val ssid = info?.ssid ?: app.getSystemService(WifiManager::class.java).connectionInfo?.ssid
        return ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: connectedSsid
    }

    private companion object {
        const val USB_PERMISSION_ACTION = "com.yaoyihan.nikonconnect.USB_PERMISSION"
    }
}

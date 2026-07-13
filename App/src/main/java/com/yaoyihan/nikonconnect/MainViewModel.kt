package com.yaoyihan.nikonconnect

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.net.SocketTimeoutException
import kotlin.math.roundToInt

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = AppStore(application)
    private val diagnostics = DiagnosticLogger(application)
    private var client: CameraClient? = null
    private var usbDevice: UsbDevice? = null
    private var cameraNetworkBound = false
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
    val hasMorePhotos = MutableStateFlow(false)
    val hasLoadedPhotos = MutableStateFlow(false)
    val thumbnails = mutableStateMapOf<UInt, Bitmap?>()
    private var lastFailedAsset: PhotoAsset? = null
    private var noticeClearJob: Job? = null
    private var snackbarClearJob: Job? = null
    private var pageTaskClearJob: Job? = null
    private var pageRetry: (() -> Unit)? = null
    private var photoLoadJob: Job? = null
    private var usbConnectJob: Job? = null
    private val pageSize = 30
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
        registerUsbReceiver()
        logDiagnostic("APP_STARTED", "APP_READY", message = "Camera Bridge started")
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
        if (device != null && hasPermission && config.value.usbAutoRead && session.value == null) {
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
                client?.close(); client = null
                logDiagnostic("MTP_OPEN_FAILED", "MTP_SESSION_OPEN", ConnectionTransport.USB, level = "ERROR", result = "FAILED", message = it.message ?: "Unable to open MTP session", durationMs = System.currentTimeMillis() - startedAt, error = it, device = device)
                usbState.value = usbState.value.copy(ready = false, message = it.message ?: "无法连接 USB 相机")
                workflow.value = Workflow.WAITING
                showError(it.message ?: "无法连接 USB 相机")
                isBusy.value = false
            }
        }
    }

    private fun handleUsbDisconnect() {
        if (session.value?.transport == ConnectionTransport.USB) {
            photoLoadJob?.cancel()
            client?.close(); client = null
            logDiagnostic("MTP_SESSION_LOST", "MTP_SESSION_LOST", ConnectionTransport.USB, level = "ERROR", result = "DISCONNECTED", message = "MTP session lost", device = usbDevice, permissionGranted = false)
            diagnostics.recordUsbConnectionState(false, diagnosticUsbSnapshot(usbDevice, false, false))
            session.value = null
            photos.value = emptyList(); thumbnails.clear(); hasMorePhotos.value = false; hasLoadedPhotos.value = false
            showError("USB 相机已断开，请重新连接数据线", event = "MTP_SESSION_LOST", transport = ConnectionTransport.USB)
        }
        usbDevice = null
        usbState.value = UsbConnectionState(message = "USB 相机已断开，请重新连接数据线")
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(USB_PERMISSION_ACTION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") { app.registerReceiver(usbReceiver, filter) }
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
        config.update(transform); store.save(config.value)
    }

    fun markLutUsed(entry: LutEntry) {
        val updated = (listOf(entry.id) + recentLutIds.value.filterNot { it == entry.id }).take(5)
        recentLutIds.value = updated
        store.saveRecentLuts(updated)
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
        thumbnails.clear()
        showSnackbar("已清理缩略图缓存")
    }

    fun retry() {
        lastFailedAsset?.let(::download) ?: if (session.value?.transport == ConnectionTransport.USB || usbDevice != null || config.value.lastTransport == ConnectionTransport.USB) {
            logDiagnostic("USB_RECONNECT_ATTEMPT", "USB_RECONNECT", ConnectionTransport.USB, result = "STARTED", message = "Retrying USB camera connection", device = usbDevice)
            connectUsb()
        } else connect(false)
    }

    fun checkConnectionOnResume() = viewModelScope.launch {
        val current = session.value ?: return@launch
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
            client?.close(); client = null
            session.value = null; photos.value = emptyList(); thumbnails.clear(); hasMorePhotos.value = false; hasLoadedPhotos.value = false
            val message = if (current.transport == ConnectionTransport.USB) "USB 相机已断开，请重新连接数据线" else "相机 Wi‑Fi 已断开，请重新连接"
            showError(message, event = if (current.transport == ConnectionTransport.USB) "MTP_SESSION_LOST" else "CAMERA_SESSION_LOST", transport = current.transport)
        }
    }

    fun connect(manual: Boolean = false) = viewModelScope.launch {
        val target = config.value
        val network = cameraNetwork()
        if (network == null) {
            logDiagnostic("WIFI_NETWORK_LOST", "NETWORK_LOOKUP", ConnectionTransport.WIFI, level = "ERROR", result = "NOT_FOUND", message = "No Wi-Fi network available")
            if (manual) openWifiSettings() else showError("请先连接相机 Wi‑Fi 后再继续")
            return@launch
        }
        logDiagnostic("WIFI_NETWORK_FOUND", "NETWORK_LOOKUP", ConnectionTransport.WIFI, message = "Wi-Fi network found")
        val host = cameraGateway().ifBlank { target.host.trim() }
        if (target.host.isBlank() || target.port !in 1..65535) { showError("请填写有效的相机地址和端口"); return@launch }
        isBusy.value = true; workflow.value = Workflow.CONNECTING; notice.value = "正在连接 ${target.host}:${target.port}"
        logDiagnostic("CAMERA_DISCOVERY_STARTED", "CAMERA_DISCOVERY", ConnectionTransport.WIFI, message = "Starting camera discovery")
        bindCameraNetwork(network)
        runCatching {
            withContext(Dispatchers.IO) {
                client?.close(); PtpIpClient(host, target.port, network.socketFactory).also { it.connect(); client = it }
            }
        }.onSuccess { ptp ->
            logDiagnostic("CAMERA_DISCOVERED", "CAMERA_DISCOVERY", ConnectionTransport.WIFI, message = "Camera discovered: ${ptp.cameraName}")
            logDiagnostic("CAMERA_SESSION_STARTED", "CAMERA_SESSION", ConnectionTransport.WIFI, message = "Wi-Fi camera session started")
            session.value = CameraSession(ptp.cameraName, host, target.port, ConnectionTransport.WIFI, ptp.cameraDetails)
            if (config.value.keepWifiAlive) keepConnectionAlive(true)
            updateConfig { it.copy(lastCameraName = ptp.cameraName, lastTransport = ConnectionTransport.WIFI) }
            workflow.value = Workflow.CONNECTED; notice.value = "已连接 ${ptp.cameraName} · Wi‑Fi"
            currentSsid()?.let { ssid -> if (ssid.isNotBlank()) updateConfig { c -> c.copy(lastSsid = ssid) } }
            logDiagnostic("CAMERA_SESSION_READY", "CAMERA_SESSION", ConnectionTransport.WIFI, message = "Camera session ready")
            loadPhotos()
        }.onFailure {
            bindCameraNetwork(null)
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

    fun disconnect() = viewModelScope.launch(Dispatchers.IO) {
        val transport = session.value?.transport
        photoLoadJob?.cancel()
        if (transport == ConnectionTransport.USB) {
            logDiagnostic("MTP_SESSION_LOST", "DISCONNECT_REQUESTED", ConnectionTransport.USB, result = "USER_DISCONNECTED", message = "USB session closed by user", device = usbDevice)
            diagnostics.recordUsbConnectionState(false, diagnosticUsbSnapshot(usbDevice, false, false))
        }
        if (transport == ConnectionTransport.WIFI) { keepConnectionAlive(false); bindCameraNetwork(null) }
        client?.close(); client = null; hasMorePhotos.value = false; hasLoadedPhotos.value = false
        withContext(Dispatchers.Main) {
            session.value = null; photos.value = emptyList(); thumbnails.clear(); workflow.value = Workflow.WAITING
            notice.value = if (transport == ConnectionTransport.USB) "USB 相机已断开" else "连接已断开"
            if (transport == ConnectionTransport.USB) usbState.value = usbState.value.copy(ready = false, message = "USB 相机未连接")
        }
    }

    fun loadPhotos() {
        if (photoLoadJob?.isActive == true) return
        photoLoadJob = viewModelScope.launch {
            val active = client ?: run { showError("请先连接相机"); return@launch }
            isBusy.value = true; workflow.value = Workflow.LOADING; hasLoadedPhotos.value = false; notice.value = "正在读取相册（首批 30 张）"
            val startedAt = System.currentTimeMillis()
            logDiagnostic("ASSET_LIST_STARTED", "ASSET_LIST", session.value?.transport, message = "Reading first asset page")
            runCatching {
                withContext(Dispatchers.IO) {
                    active.refreshAssets()
                    val result = active.assets(limit = pageSize)
                    val recent = result.firstOrNull { it.type !in setOf("MOV", "MP4") }?.let { asset ->
                        active.imageHeader(asset)?.let { header ->
                            asset to ExifMetadataReader.fromBytes(header, PhotoMetadata(cameraModel = session.value?.name, capturedAt = asset.capturedAt))
                        }
                    }
                    result to recent
                }
            }
                .onSuccess { (result, recent) ->
                    photos.value = result
                    recent?.let { (asset, metadata) -> updateSessionFromPhoto(asset, metadata) }
                    hasMorePhotos.value = active.hasMoreAssets(photos.value.size)
                    hasLoadedPhotos.value = true
                    workflow.value = Workflow.CONNECTED; notice.value = "${session.value?.name ?: "相机"} · ${session.value?.transport?.title ?: "Wi‑Fi"} · 已读取 ${result.size} 张"
                    if (session.value?.transport == ConnectionTransport.USB) logDiagnostic("MTP_STORAGE_DISCOVERED", "MTP_STORAGE", ConnectionTransport.USB, message = "MTP storage discovered", device = usbDevice)
                    logDiagnostic("ASSET_LIST_SUCCEEDED", "ASSET_LIST", session.value?.transport, message = "Read ${result.size} assets", durationMs = System.currentTimeMillis() - startedAt)
                }.onFailure {
                    logDiagnostic("ASSET_LIST_FAILED", "ASSET_LIST", session.value?.transport, level = "ERROR", result = "FAILED", message = it.message ?: "Unable to read asset list", durationMs = System.currentTimeMillis() - startedAt, error = it)
                    showError(it.message ?: "读取相册失败", event = "ASSET_LIST_FAILED", transport = session.value?.transport)
                }
            isBusy.value = false
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

    fun loadMorePhotos() = viewModelScope.launch {
        if (isBusy.value || !hasMorePhotos.value) return@launch
        val active = client ?: return@launch
        isBusy.value = true; notice.value = "正在加载更多"
        logDiagnostic("ASSET_LIST_STARTED", "ASSET_LIST", session.value?.transport, message = "Reading next asset page")
        runCatching { withContext(Dispatchers.IO) { active.assets(photos.value.size, 15) } }
            .onSuccess { page ->
                val merged = photos.value + page
                photos.value = merged
                hasMorePhotos.value = active.hasMoreAssets(merged.size)
                notice.value = if (hasMorePhotos.value) "已读取 ${merged.size} 张" else "已全部加载"
                logDiagnostic("ASSET_LIST_SUCCEEDED", "ASSET_LIST", session.value?.transport, message = "Read ${page.size} more assets")
            }
            .onFailure {
                logDiagnostic("ASSET_LIST_FAILED", "ASSET_LIST", session.value?.transport, level = "ERROR", result = "FAILED", message = it.message ?: "Unable to read more assets", error = it)
                showError(it.message ?: "继续读取照片失败", event = "ASSET_LIST_FAILED", transport = session.value?.transport)
            }
        isBusy.value = false
    }

    fun loadThumbnail(asset: PhotoAsset) {
        if (thumbnails.containsKey(asset.handle)) return
        thumbnails[asset.handle] = null
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val active = client ?: return@withContext null
                    val data = active.thumbnail(asset) ?: return@withContext null
                    val orientation = resolveExifOrientation(
                        OrientedBitmaps.orientationOrNull(data),
                        active.imageHeader(asset)?.let(OrientedBitmaps::orientationOrNull),
                    )
                    OrientedBitmaps.decode(data, orientation)
                }
            }
            result.onSuccess { bitmap ->
                thumbnails[asset.handle] = bitmap
                if (bitmap == null) logDiagnostic("THUMBNAIL_LOAD_FAILED", "THUMBNAIL", session.value?.transport, level = "WARN", result = "FAILED", message = "Unable to decode thumbnail ${asset.name}")
            }.onFailure {
                thumbnails[asset.handle] = null
                logDiagnostic("THUMBNAIL_LOAD_FAILED", "THUMBNAIL", session.value?.transport, level = "WARN", result = "FAILED", message = it.message ?: "Unable to load thumbnail ${asset.name}", error = it)
            }
        }
    }

    suspend fun loadOriginalPreview(asset: PhotoAsset): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = client?.download(asset) { _, _ -> } ?: return@runCatching null
            decodePreviewBitmap(bytes)
        }.onFailure { logDiagnostic("ORIGINAL_LOAD_FAILED", "ORIGINAL_PREVIEW", session.value?.transport, level = "ERROR", result = "FAILED", message = it.message ?: "Unable to load original ${asset.name}", error = it) }.getOrNull()
    }

    suspend fun loadOriginalPhoto(asset: PhotoAsset): LoadedPhoto? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = client?.download(asset) { _, _ -> } ?: return@runCatching null
            val bitmap = decodePreviewBitmap(bytes) ?: return@runCatching null
            val fallback = PhotoMetadata(cameraModel = session.value?.name, capturedAt = asset.capturedAt)
            LoadedPhoto(bitmap, ExifMetadataReader.fromBytes(bytes, fallback))
        }.onFailure { logDiagnostic("ORIGINAL_LOAD_FAILED", "ORIGINAL_PREVIEW", session.value?.transport, level = "ERROR", result = "FAILED", message = it.message ?: "Unable to load original ${asset.name}", error = it) }.getOrNull()
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
        logDiagnostic("LUT_EXPORT_STARTED", "LUT_EXPORT", session.value?.transport, message = "Exporting ${asset.name} with LUT ${lut.name}")
        updateExportNotif(asset.name, 0, 0)
        val result = runCatching { withContext(Dispatchers.IO) { saveLutBitmap(asset, bitmap, lut.name) } }
        result.onSuccess { record ->
            logDiagnostic("LUT_EXPORT_SUCCEEDED", "LUT_EXPORT", session.value?.transport, message = "LUT export completed: ${record.name}")
            store.addDownload(record); downloads.value = store.downloads(); workflow.value = Workflow.CONNECTED; if (!inline) showTransient("已导出套 LUT 图片")
        }.onFailure {
            logDiagnostic("LUT_EXPORT_FAILED", "LUT_EXPORT", session.value?.transport, level = "ERROR", result = "FAILED", message = it.message ?: "LUT export failed", error = it)
            if (inline) workflow.value = Workflow.CONNECTED else showError(it.message ?: "导出 LUT 图片失败", event = "LUT_EXPORT_FAILED", transport = session.value?.transport)
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
            store.addDownload(output); downloads.value = store.downloads(); workflow.value = Workflow.CONNECTED; if (!inline) showTransient("已导出套 LUT 图片")
        }.onFailure {
            logDiagnostic("LUT_EXPORT_FAILED", "LUT_EXPORT", level = "ERROR", result = "FAILED", message = it.message ?: "LUT export failed", error = it)
            if (inline) workflow.value = Workflow.CONNECTED else showError(it.message ?: "导出 LUT 图片失败", event = "LUT_EXPORT_FAILED")
        }
        clearExportNotif(); isBusy.value = false; onFinished(result)
    }

    fun downloadAllWithLut(assets: List<PhotoAsset>, lut: CubeLut, onFinished: () -> Unit = {}): Job = viewModelScope.launch {
        val retry: () -> Unit = { downloadAllWithLut(assets, lut, onFinished); Unit }
        pageRetry = retry
        val active = client ?: run { showPageError("套 LUT 失败：相机连接已断开"); return@launch }
        isBusy.value = true; workflow.value = Workflow.DOWNLOADING
        showPageProgress("正在套用 LUT 0 / ${assets.size}")
        logDiagnostic("LUT_EXPORT_STARTED", "LUT_EXPORT_BATCH", session.value?.transport, message = "Batch LUT export started: ${assets.size} assets")
        var completed = 0
        var skipped = 0
        assets.forEachIndexed { index, asset ->
            if (!asset.name.supportsLutInput()) {
                skipped++
            } else runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = active.download(asset) { _, _ -> }
                    val bitmap = OrientedBitmaps.decode(bytes) ?: error("无法解码 ${asset.name}")
                    saveLutBitmap(asset, CubeLuts.apply(bitmap, lut), lut.name)
                }
            }.onSuccess { store.addDownload(it); completed++ }
                .onFailure { error ->
                    logDiagnostic("LUT_EXPORT_FAILED", "LUT_EXPORT_BATCH", session.value?.transport, level = "WARN", result = "FAILED", message = "Skipped ${asset.name}: ${error.message ?: "processing failed"}", error = error)
                    skipped++
                }
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
        logDiagnostic("LUT_EXPORT_STARTED", "LUT_EXPORT_BATCH", message = "Batch local LUT export started: ${records.size} files")
        var completed = 0
        var skipped = 0
        records.forEachIndexed { index, record ->
            if (!record.name.supportsLutInput()) {
                skipped++
            } else runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(Uri.parse(record.uri))?.use { it.readBytes() }
                        ?: error("无法读取 ${record.name}")
                    val bitmap = OrientedBitmaps.decode(bytes) ?: error("无法解码 ${record.name}")
                    saveLutBitmap(PhotoAsset(0u, record.name, record.size, 0x3801), CubeLuts.apply(bitmap, lut), lut.name)
                }
            }.onSuccess { store.addDownload(it); completed++ }
                .onFailure { error ->
                    logDiagnostic("LUT_EXPORT_FAILED", "LUT_EXPORT_BATCH", level = "WARN", result = "FAILED", message = "Skipped ${record.name}: ${error.message ?: "processing failed"}", error = error)
                    skipped++
                }
            showPageProgress("正在套用 LUT ${index + 1} / ${records.size}")
            updateExportNotif(record.name, index + 1, records.size)
        }
        downloads.value = store.downloads(); workflow.value = Workflow.CONNECTED
        clearExportNotif()
        showPageSuccess("已导出 $completed 张，跳过 $skipped 个不支持的文件。")
        isBusy.value = false
        onFinished()
    }

    fun exportEditedPhoto(
        asset: PhotoAsset,
        lut: CubeLut?,
        watermark: WatermarkPreset?,
        suffix: String,
        quality: Int = 95,
        inline: Boolean = false,
        onFinished: (Result<DownloadRecord>) -> Unit = {},
    ): Job = launchEditedExport(asset.name, session.value?.transport, inline, onFinished) {
        val active = client ?: error("相机连接已断开")
        val bytes = active.download(asset) { _, _ -> }
        renderAndSaveOriginal(
            asset = asset,
            bytes = bytes,
            lut = lut,
            watermark = watermark,
            suffix = suffix,
            quality = quality,
            fallback = PhotoMetadata(cameraModel = session.value?.name, capturedAt = asset.capturedAt),
        )
    }

    fun exportEditedDownload(
        record: DownloadRecord,
        lutEntry: LutEntry?,
        watermark: WatermarkPreset?,
        suffix: String,
        quality: Int = 95,
        inline: Boolean = false,
        onFinished: (Result<DownloadRecord>) -> Unit = {},
    ): Job = launchEditedExport(record.name, null, inline, onFinished) {
        val bytes = getApplication<Application>().contentResolver.openInputStream(Uri.parse(record.uri))?.use { it.readBytes() }
            ?: error("无法读取 ${record.name}")
        renderAndSaveOriginal(
            asset = PhotoAsset(0u, record.name, record.size, 0x3801),
            bytes = bytes,
            lut = lutEntry?.let { readLut(it) },
            watermark = watermark,
            suffix = suffix,
            quality = quality,
            fallback = PhotoMetadata(capturedAt = Date(record.completedAt)),
        )
    }

    private fun launchEditedExport(
        sourceName: String,
        transport: ConnectionTransport?,
        inline: Boolean,
        onFinished: (Result<DownloadRecord>) -> Unit,
        export: suspend () -> DownloadRecord,
    ): Job = viewModelScope.launch {
        isBusy.value = true
        workflow.value = Workflow.DOWNLOADING
        if (!inline) notice.value = "正在导出 $sourceName"
        logDiagnostic("WATERMARK_EXPORT_STARTED", "EDIT_EXPORT", transport, message = "Exporting original file: $sourceName")
        updateExportNotif(sourceName, 0, 0)
        val result = runCatching { withContext(Dispatchers.IO) { export() } }
        result.onSuccess { record ->
            store.addDownload(record)
            downloads.value = store.downloads()
            workflow.value = if (session.value == null) Workflow.WAITING else Workflow.CONNECTED
            logDiagnostic("WATERMARK_EXPORT_SUCCEEDED", "EDIT_EXPORT", transport, message = "Edit export completed: ${record.name}")
            if (!inline) showTransient("已导出编辑图片")
        }.onFailure {
            workflow.value = if (session.value == null) Workflow.WAITING else Workflow.CONNECTED
            logDiagnostic("WATERMARK_EXPORT_FAILED", "EDIT_EXPORT", transport, level = "ERROR", result = "FAILED", message = it.message ?: "Edit export failed", error = it)
            if (!inline) showError(it.message ?: "导出编辑图片失败", event = "WATERMARK_EXPORT_FAILED", transport = transport)
        }
        clearExportNotif()
        isBusy.value = false
        onFinished(result)
    }

    private fun renderAndSaveOriginal(
        asset: PhotoAsset,
        bytes: ByteArray,
        lut: CubeLut?,
        watermark: WatermarkPreset?,
        suffix: String,
        quality: Int,
        fallback: PhotoMetadata,
    ): DownloadRecord {
        val original = OrientedBitmaps.decode(bytes) ?: error("无法解码 ${asset.name}")
        var graded: Bitmap? = null
        var watermarked: Bitmap? = null
        return try {
            val metadata = ExifMetadataReader.fromBytes(bytes, fallback)
            val working = if (lut == null) original else CubeLuts.apply(original, lut).also { graded = it }
            if (working !== original) original.recycle()
            val output = if (watermark == null) working else WatermarkRenderer.render(working, metadata, watermark, getApplication()).also { watermarked = it }
            saveEditedBitmap(asset, output, metadata, suffix, quality)
        } finally {
            watermarked?.takeIf { !it.isRecycled }?.recycle()
            graded?.takeIf { !it.isRecycled }?.recycle()
            if (!original.isRecycled) original.recycle()
        }
    }

    fun addWatermarkToPhotos(assets: List<PhotoAsset>, preset: WatermarkPreset, onFinished: () -> Unit = {}): Job = processRemoteEdits(assets, null, preset, onFinished)

    fun applyLutAndWatermarkToPhotos(assets: List<PhotoAsset>, lut: CubeLut, preset: WatermarkPreset, onFinished: () -> Unit = {}): Job = processRemoteEdits(assets, lut, preset, onFinished)

    fun addWatermarkToDownloads(records: List<DownloadRecord>, preset: WatermarkPreset, onFinished: () -> Unit = {}): Job = processLocalEdits(records, null, preset, onFinished)

    fun applyLutAndWatermarkToDownloads(records: List<DownloadRecord>, lut: CubeLut, preset: WatermarkPreset, onFinished: () -> Unit = {}): Job = processLocalEdits(records, lut, preset, onFinished)

    private fun processRemoteEdits(assets: List<PhotoAsset>, lut: CubeLut?, preset: WatermarkPreset, onFinished: () -> Unit): Job {
        val retry: () -> Unit = { processRemoteEdits(assets, lut, preset, onFinished); Unit }
        pageRetry = retry
        return viewModelScope.launch {
            val active = client ?: run { showPageError("添加水印失败：相机连接已断开"); return@launch }
            if (assets.isEmpty()) return@launch
            val title = if (lut == null) "添加水印" else "套 LUT + 水印"
            val suffix = if (lut == null) "Watermark" else "${lut.name}_Watermark"
            isBusy.value = true
            workflow.value = Workflow.DOWNLOADING
            showPageProgress("正在$title 0 / ${assets.size}")
            logDiagnostic("WATERMARK_EXPORT_STARTED", "EDIT_EXPORT_BATCH", session.value?.transport, message = "Batch $title started: ${assets.size} assets")
            var completed = 0
            var skipped = 0
            assets.forEachIndexed { index, asset ->
                if (!asset.name.supportsLutInput()) {
                    skipped++
                } else runCatching {
                    withContext(Dispatchers.IO) {
                        val bytes = active.download(asset) { _, _ -> }
                        renderAndSaveOriginal(
                            asset,
                            bytes,
                            lut,
                            preset,
                            suffix,
                            preset.quality,
                            PhotoMetadata(cameraModel = session.value?.name, capturedAt = asset.capturedAt),
                        )
                    }
                }.onSuccess { store.addDownload(it); completed++ }.onFailure { error ->
                    logDiagnostic("WATERMARK_EXPORT_FAILED", "EDIT_EXPORT_BATCH", session.value?.transport, level = "WARN", result = "FAILED", message = "Skipped ${asset.name}: ${error.message ?: "processing failed"}", error = error)
                    skipped++
                }
                showPageProgress("正在$title ${index + 1} / ${assets.size}")
                updateExportNotif(asset.name, index + 1, assets.size)
            }
            downloads.value = store.downloads()
            workflow.value = Workflow.CONNECTED
            clearExportNotif()
            logDiagnostic("WATERMARK_EXPORT_SUCCEEDED", "EDIT_EXPORT_BATCH", session.value?.transport, message = "Batch $title completed: $completed exported, $skipped skipped")
            showPageSuccess(if (skipped == 0) "已导出 $completed 张水印图片" else "已导出 $completed 张，跳过 $skipped 个不支持或失败的文件")
            isBusy.value = false
            onFinished()
        }
    }

    private fun processLocalEdits(records: List<DownloadRecord>, lut: CubeLut?, preset: WatermarkPreset, onFinished: () -> Unit): Job {
        val retry: () -> Unit = { processLocalEdits(records, lut, preset, onFinished); Unit }
        pageRetry = retry
        return viewModelScope.launch {
            if (records.isEmpty()) return@launch
            val title = if (lut == null) "添加水印" else "套 LUT + 水印"
            val suffix = if (lut == null) "Watermark" else "${lut.name}_Watermark"
            isBusy.value = true
            workflow.value = Workflow.DOWNLOADING
            showPageProgress("正在$title 0 / ${records.size}")
            logDiagnostic("WATERMARK_EXPORT_STARTED", "EDIT_EXPORT_BATCH", message = "Batch local $title started: ${records.size} files")
            var completed = 0
            var skipped = 0
            records.forEachIndexed { index, record ->
                if (!record.name.supportsLutInput()) {
                    skipped++
                } else runCatching {
                    withContext(Dispatchers.IO) {
                        val bytes = getApplication<Application>().contentResolver.openInputStream(Uri.parse(record.uri))?.use { it.readBytes() }
                            ?: error("无法读取 ${record.name}")
                        renderAndSaveOriginal(
                            PhotoAsset(0u, record.name, record.size, 0x3801),
                            bytes,
                            lut,
                            preset,
                            suffix,
                            preset.quality,
                            PhotoMetadata(capturedAt = Date(record.completedAt)),
                        )
                    }
                }.onSuccess { store.addDownload(it); completed++ }.onFailure { error ->
                    logDiagnostic("WATERMARK_EXPORT_FAILED", "EDIT_EXPORT_BATCH", level = "WARN", result = "FAILED", message = "Skipped ${record.name}: ${error.message ?: "processing failed"}", error = error)
                    skipped++
                }
                showPageProgress("正在$title ${index + 1} / ${records.size}")
                updateExportNotif(record.name, index + 1, records.size)
            }
            downloads.value = store.downloads()
            workflow.value = Workflow.CONNECTED
            clearExportNotif()
            logDiagnostic("WATERMARK_EXPORT_SUCCEEDED", "EDIT_EXPORT_BATCH", message = "Batch local $title completed: $completed exported, $skipped skipped")
            showPageSuccess(if (skipped == 0) "已导出 $completed 张水印图片" else "已导出 $completed 张，跳过 $skipped 个不支持或失败的文件")
            isBusy.value = false
            onFinished()
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
        runCatching {
            val parsed = Uri.parse(record.uri)
            val bytes = getApplication<Application>().contentResolver.openInputStream(parsed)?.use { it.readBytes() }
                ?: return@runCatching null
            val bitmap = decodePreviewBitmap(bytes) ?: return@runCatching null
            val fallback = PhotoMetadata(capturedAt = Date(record.completedAt))
            LoadedPhoto(bitmap, ExifMetadataReader.fromBytes(bytes, fallback))
        }.getOrNull()
    }

    fun download(asset: PhotoAsset, inline: Boolean = false, onFinished: (Result<DownloadRecord>) -> Unit = {}) = viewModelScope.launch {
        val active = client ?: run {
            val error = IllegalStateException("相机连接已断开")
            logDiagnostic("DOWNLOAD_FAILED", "DOWNLOAD", session.value?.transport, level = "ERROR", result = "FAILED", message = error.message!!, error = error)
            if (!inline) showError(error.message!!, event = "DOWNLOAD_FAILED", transport = session.value?.transport)
            onFinished(Result.failure(error)); return@launch
        }
        isBusy.value = true; workflow.value = Workflow.DOWNLOADING; if (!inline) notice.value = "正在下载 ${asset.name}"
        logDiagnostic("DOWNLOAD_STARTED", "DOWNLOAD", session.value?.transport, message = "Downloading ${asset.name}")
        val result = runCatching {
            withContext(Dispatchers.IO) {
                var lastLoggedAt = 0L
                saveToMediaStore(asset, config.value.autoExport) { stream -> active.downloadTo(asset, stream) { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastLoggedAt >= 1_000 || done >= total) {
                        lastLoggedAt = now
                        logDiagnostic("DOWNLOAD_PROGRESS", "DOWNLOAD", session.value?.transport, result = "IN_PROGRESS", message = "${asset.name}: $done / $total", durationMs = done)
                    }
                    viewModelScope.launch { if (!inline) notice.value = "正在下载 ${asset.name} · ${done.prettySize()} / ${total.prettySize()}"; updateDownloadNotif(asset.name, done, total) }
                } }
            }
        }
        result.onSuccess { record ->
            logDiagnostic("DOWNLOAD_SUCCEEDED", "DOWNLOAD", session.value?.transport, message = "Downloaded ${record.name}")
            store.addDownload(record); downloads.value = store.downloads(); workflow.value = Workflow.CONNECTED; if (!inline) showTransient("已保存到系统相册"); lastFailedAsset = null; clearDownloadNotif()
        }.onFailure { error ->
            lastFailedAsset = asset
            logDiagnostic("DOWNLOAD_FAILED", "DOWNLOAD", session.value?.transport, level = "ERROR", result = "FAILED", message = error.message ?: "Download failed", error = error)
            if (inline) workflow.value = Workflow.CONNECTED else showError(error.message ?: "下载失败", event = "DOWNLOAD_FAILED", transport = session.value?.transport)
            clearDownloadNotif()
        }
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
        logDiagnostic("DOWNLOAD_STARTED", "DOWNLOAD_BATCH", session.value?.transport, message = "Batch download started: ${assets.size} assets")
        showPageProgress("正在下载 0 / ${assets.size}")
        for ((index, asset) in assets.withIndex()) {
            logDiagnostic("DOWNLOAD_STARTED", "DOWNLOAD_BATCH_ITEM", session.value?.transport, message = "Downloading ${asset.name}")
            val record = runCatching {
                withContext(Dispatchers.IO) {
                    saveToMediaStore(asset, config.value.autoExport) { stream ->
                        active.downloadTo(asset, stream) { done, total ->
                            viewModelScope.launch { showPageProgress("正在下载 ${index + 1} / ${assets.size}"); updateDownloadNotif("${index + 1}/${assets.size} ${asset.name}", done, total) }
                        }
                    }
                }
            }.getOrElse { error ->
                logDiagnostic("DOWNLOAD_FAILED", "DOWNLOAD_BATCH_ITEM", session.value?.transport, level = "ERROR", result = "FAILED", message = "${asset.name}: ${error.message ?: "Download failed"}", error = error)
                showPageError("下载失败：${error.message ?: "未知错误"}")
                isBusy.value = false
                clearDownloadNotif()
                return@launch
            }
            store.addDownload(record)
            logDiagnostic("DOWNLOAD_SUCCEEDED", "DOWNLOAD_BATCH_ITEM", session.value?.transport, message = "Downloaded ${record.name}")
            showPageProgress("正在下载 ${index + 1} / ${assets.size}")
        }
        downloads.value = store.downloads()
        workflow.value = Workflow.CONNECTED
        logDiagnostic("DOWNLOAD_SUCCEEDED", "DOWNLOAD_BATCH", session.value?.transport, message = "Batch download completed: ${assets.size} assets")
        showPageSuccess("已下载 ${assets.size} 张照片")
        clearDownloadNotif()
        isBusy.value = false
    }

    fun deleteDownloads(uris: Set<String>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        logDiagnostic("DELETE_STARTED", "DELETE", message = "Deleting ${uris.size} downloaded files")
        showPageProgress("正在删除 ${uris.size} 个文件")
        val resolver = getApplication<Application>().contentResolver
        withContext(Dispatchers.IO) {
            uris.forEach { uri -> runCatching { resolver.delete(android.net.Uri.parse(uri), null, null) } }
        }
        store.removeDownloads(uris)
        downloads.value = store.downloads()
        logDiagnostic("DELETE_SUCCEEDED", "DELETE", message = "Deleted ${uris.size} downloaded files")
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

    private fun saveEditedBitmap(asset: PhotoAsset, bitmap: Bitmap, metadata: PhotoMetadata, suffix: String, quality: Int): DownloadRecord {
        val safeSuffix = suffix.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val output = asset.copy(name = "${asset.name.substringBeforeLast('.')}_${safeSuffix}.jpg")
        val temp = java.io.File.createTempFile("camera_bridge_", ".jpg", getApplication<Application>().cacheDir)
        return try {
            temp.outputStream().use { stream -> require(bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(80, 100), stream)) { "无法编码 JPG" } }
            preserveExif(temp, metadata)
            saveToMediaStore(output, true) { stream -> temp.inputStream().use { it.copyTo(stream) }; temp.length() }
        } finally {
            temp.delete()
        }
    }

    private fun preserveExif(file: java.io.File, metadata: PhotoMetadata) {
        runCatching {
            val exif = android.media.ExifInterface(file.absolutePath)
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

    private fun showError(message: String, event: String = "APP_ERROR", transport: ConnectionTransport? = session.value?.transport) {
        if (event == "APP_ERROR") logDiagnostic(event, "ERROR", transport, level = "ERROR", result = "FAILED", message = message)
        noticeClearJob?.cancel(); workflow.value = Workflow.ERROR; notice.value = message
    }
    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(usbReceiver) }
        keepConnectionAlive(false); client?.close(); bindCameraNetwork(null); clearDownloadNotif(); clearExportNotif(); diagnostics.close()
    }

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
        val changed = cameraNetworkBound != (network != null)
        cameraNetworkBound = network != null
        getApplication<Application>().getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(network)
        if (changed) logDiagnostic(if (network == null) "WIFI_NETWORK_LOST" else "WIFI_NETWORK_BOUND", "NETWORK_BIND", ConnectionTransport.WIFI, result = if (network == null) "UNBOUND" else "BOUND", message = if (network == null) "App unbound from camera Wi-Fi" else "App bound to camera Wi-Fi")
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

    private companion object {
        const val USB_PERMISSION_ACTION = "com.yaoyihan.nikonconnect.USB_PERMISSION"
    }
}

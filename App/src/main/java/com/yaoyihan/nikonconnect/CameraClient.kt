package com.yaoyihan.nikonconnect

import java.io.OutputStream

enum class ConnectionTransport(val title: String) {
    WIFI("Wi‑Fi"),
    USB("USB"),
}

data class UsbConnectionState(
    val detected: Boolean = false,
    val permissionGranted: Boolean = false,
    val deviceName: String? = null,
    val model: String? = null,
    val ready: Boolean = false,
    val message: String = "未检测到可读取的 USB 相机",
)

data class CameraDetails(
    val manufacturer: String? = null,
    val model: String? = null,
    val deviceVersion: String? = null,
    val serialNumber: String? = null,
    val batteryPercent: Int? = null,
    val lensName: String? = null,
    val lensSpec: String? = null,
    val lensFromPhoto: Boolean = false,
    val recentFocalLength: String? = null,
    val recentAperture: String? = null,
    val recentShutter: String? = null,
    val recentIso: Int? = null,
    val recentCapturedAt: Long? = null,
)

internal fun cameraDisplayName(details: CameraDetails, fallback: String): String {
    val manufacturer = details.manufacturer.orEmpty().trim()
    val model = details.model.orEmpty().trim()
    if (model.isBlank()) return manufacturer.ifBlank { fallback }
    return if (manufacturer.isBlank() || model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
}

interface CameraClient : AutoCloseable {
    val cameraName: String
    val cameraDetails: CameraDetails
    val transport: ConnectionTransport

    fun connect()
    fun checkConnection(): Boolean
    fun refreshAssets()
    fun assets(offset: Int = 0, limit: Int = 250): List<PhotoAsset>
    fun hasMoreAssets(offset: Int): Boolean
    fun thumbnail(asset: PhotoAsset): ByteArray?
    fun imageHeader(asset: PhotoAsset): ByteArray?
    fun download(asset: PhotoAsset, progress: (Long, Long) -> Unit): ByteArray
    fun downloadTo(asset: PhotoAsset, output: OutputStream, progress: (Long, Long) -> Unit): Long
}

internal const val IMAGE_HEADER_SIZE = 256 * 1024

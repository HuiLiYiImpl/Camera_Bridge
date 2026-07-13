package com.yaoyihan.nikonconnect

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.mtp.MtpConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Date

/** Android's native MTP bridge. It keeps USB protocol details out of the UI and export layers. */
class UsbMtpClient(
    context: Context,
    private val usbDevice: UsbDevice,
) : CameraClient {
    private val appContext = context.applicationContext
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val ioLock = Any()
    private var connection: UsbDeviceConnection? = null
    private lateinit var mtp: MtpDevice
    private var objectHandles: List<Int>? = null
    override var cameraName: String = usbDevice.productName ?: "USB 相机"
        private set
    override var cameraDetails: CameraDetails = CameraDetails(model = usbDevice.productName)
        private set
    override val transport = ConnectionTransport.USB

    override fun connect() = synchronized(ioLock) {
        val opened = usbManager.openDevice(usbDevice) ?: error("无法打开 USB 相机，请确认已授予 USB 访问权限")
        connection = opened
        mtp = MtpDevice(usbDevice)
        require(mtp.open(opened)) { "无法建立 MTP 会话" }
        val info = mtp.getDeviceInfo()
        cameraDetails = CameraDetails(
            manufacturer = info?.getManufacturer()?.trim()?.takeIf { it.isNotBlank() },
            model = info?.getModel()?.trim()?.takeIf { it.isNotBlank() } ?: usbDevice.productName,
            deviceVersion = info?.getVersion()?.trim()?.takeIf { it.isNotBlank() },
            serialNumber = info?.getSerialNumber()?.trim()?.takeIf { it.isNotBlank() },
        )
        cameraName = cameraDisplayName(cameraDetails, usbDevice.productName ?: "USB 相机")
    }

    override fun checkConnection(): Boolean = synchronized(ioLock) {
        runCatching { usbManager.deviceList[usbDevice.deviceName] != null && mtp.getDeviceInfo() != null }.getOrDefault(false)
    }

    override fun refreshAssets() = synchronized(ioLock) { objectHandles = null }

    override fun assets(offset: Int, limit: Int): List<PhotoAsset> = synchronized(ioLock) {
        val handles = objectHandles ?: loadObjectHandles().also { objectHandles = it }
        return handles.drop(offset).take(limit).mapNotNull { handle ->
            runCatching {
                val info = mtp.getObjectInfo(handle) ?: return@runCatching null
                if (info.getFormat() == MtpConstants.FORMAT_ASSOCIATION) return@runCatching null
                PhotoAsset(handle.toUInt(), info.getName().orEmpty().ifBlank { "IMG_$handle" }, info.getCompressedSizeLong(), info.getFormat(), Date(info.getDateCreated().takeIf { it > 0 } ?: System.currentTimeMillis()))
            }.getOrNull()
        }
    }

    override fun hasMoreAssets(offset: Int): Boolean = synchronized(ioLock) { offset < (objectHandles?.size ?: Int.MAX_VALUE) }

    override fun thumbnail(asset: PhotoAsset): ByteArray? = synchronized(ioLock) {
        runCatching { mtp.getThumbnail(asset.handle.toInt()) }.getOrNull()
    }

    override fun imageHeader(asset: PhotoAsset): ByteArray? = synchronized(ioLock) {
        runCatching {
            val size = minOf(asset.size.takeIf { it > 0 } ?: IMAGE_HEADER_SIZE.toLong(), IMAGE_HEADER_SIZE.toLong()).toInt()
            val buffer = ByteArray(size)
            val read = mtp.getPartialObject64(asset.handle.toInt(), 0L, size.toLong(), buffer).toInt()
            buffer.copyOf(read.coerceIn(0, size)).takeIf { it.isNotEmpty() }
        }.getOrNull() ?: runCatching {
            // ponytail: Nikon Zf over MTP rejects getPartialObject64; fall back to getPartialObject (32-bit)
            val size = minOf(asset.size.takeIf { it > 0 } ?: IMAGE_HEADER_SIZE.toLong(), IMAGE_HEADER_SIZE.toLong()).toInt()
            val buffer = ByteArray(size)
            val read = mtp.getPartialObject(asset.handle.toInt(), 0L, size.toLong(), buffer).toInt()
            buffer.copyOf(read.coerceIn(0, size)).takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    override fun download(asset: PhotoAsset, progress: (Long, Long) -> Unit): ByteArray {
        val output = ByteArrayOutputStream(asset.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        downloadTo(asset, output, progress)
        return output.toByteArray()
    }

    override fun downloadTo(asset: PhotoAsset, output: OutputStream, progress: (Long, Long) -> Unit): Long = synchronized(ioLock) {
        // Native import is compatible with cameras that reject GetPartialObject64 (including Nikon Z f over MTP).
        val temp = File.createTempFile("camera_bridge_mtp_", ".part", appContext.cacheDir).apply { delete() }
        try {
            if (!mtp.importFile(asset.handle.toInt(), temp.absolutePath)) {
                val size = asset.size.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
                    ?: error("USB 文件过大，无法读取")
                val bytes = mtp.getObject(asset.handle.toInt(), size) ?: error("USB 相机未返回文件内容")
                output.write(bytes)
                progress(bytes.size.toLong(), bytes.size.toLong())
                return@synchronized bytes.size.toLong()
            }
            val total = temp.length().takeIf { it > 0 } ?: asset.size.coerceAtLeast(0L)
            var copied = 0L
            val buffer = ByteArray(1024 * 1024)
            temp.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    progress(copied, total)
                }
            }
            require(copied > 0L) { "USB 相机未返回文件内容" }
            copied
        } finally {
            temp.delete()
        }
    }

    override fun close() = synchronized(ioLock) {
        runCatching { if (::mtp.isInitialized) mtp.close() }
        runCatching { connection?.close() }
        connection = null
        objectHandles = null
    }

    private fun loadObjectHandles(): List<Int> = buildList {
        mtp.getStorageIds()?.forEach { storageId ->
            val handles = mtp.getObjectHandles(storageId, 0, 0)
            handles?.forEach { handle ->
                val info = mtp.getObjectInfo(handle)
                if (info != null && info.getFormat() != MtpConstants.FORMAT_ASSOCIATION) add(handle)
            }
        }
    }.distinct().asReversed()
}

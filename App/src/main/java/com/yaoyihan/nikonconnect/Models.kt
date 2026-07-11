package com.yaoyihan.nikonconnect

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CameraConfig(
    val host: String = "192.168.1.1",
    val port: Int = 15740,
    val autoExport: Boolean = true,
    val jpegFirst: Boolean = false,
    val lastSsid: String = "",
)

data class CameraSession(val name: String, val host: String, val port: Int)

data class PhotoAsset(
    val handle: UInt,
    val name: String,
    val size: Long,
    val format: Int,
    val capturedAt: Date = Date(),
) {
    val type: String get() = when (format) {
        0x3801, 0x3808 -> "JPEG"
        0x380B -> "PNG"
        0x3000, 0x3004 -> "RAW"
        0x300C, 0x380D -> "MOV"
        else -> name.substringAfterLast('.', "文件").uppercase(Locale.ROOT)
    }
}

data class DownloadRecord(val name: String, val size: Long, val uri: String, val completedAt: Long)

enum class Workflow { WAITING, CONNECTING, CONNECTED, LOADING, DOWNLOADING, ERROR }

class AppStore(context: Context) {
    private val prefs = context.getSharedPreferences("nikon_connect", Context.MODE_PRIVATE)

    fun config(): CameraConfig = CameraConfig(
        prefs.getString("host", "192.168.1.1") ?: "192.168.1.1",
        prefs.getInt("port", 15740),
        prefs.getBoolean("autoExport", true),
        prefs.getBoolean("jpegFirst", false),
        prefs.getString("last_ssid", "") ?: "",
    )

    fun save(config: CameraConfig) = prefs.edit()
        .putString("host", config.host).putInt("port", config.port)
        .putBoolean("autoExport", config.autoExport).putBoolean("jpegFirst", config.jpegFirst)
        .putString("last_ssid", config.lastSsid).apply()

    fun downloads(): List<DownloadRecord> = runCatching {
        val array = JSONArray(prefs.getString("downloads", "[]"))
        (0 until array.length()).map { i ->
            array.getJSONObject(i).let { DownloadRecord(it.getString("name"), it.getLong("size"), it.getString("uri"), it.getLong("time")) }
        }.sortedByDescending { it.completedAt }
    }.getOrDefault(emptyList())

    fun addDownload(record: DownloadRecord) {
        val rows = downloads().toMutableList().apply { add(0, record) }.take(100)
        val array = JSONArray()
        rows.forEach { array.put(JSONObject().put("name", it.name).put("size", it.size).put("uri", it.uri).put("time", it.completedAt)) }
        prefs.edit().putString("downloads", array.toString()).apply()
    }
}

fun Long.prettySize(): String = when {
    this >= 1_073_741_824 -> "%.1f GB".format(Locale.getDefault(), this / 1_073_741_824.0)
    this >= 1_048_576 -> "%.1f MB".format(Locale.getDefault(), this / 1_048_576.0)
    else -> "%.0f KB".format(Locale.getDefault(), this / 1024.0)
}

fun Date.prettyDate(): String = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(this)

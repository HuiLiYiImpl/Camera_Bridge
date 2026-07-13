package com.yaoyihan.nikonconnect

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CameraBrand(val title: String, val subtitle: String, val available: Boolean) {
    NIKON("尼康", "PTP/IP", true),
    CANON("佳能", "即将支持", false),
    SONY("索尼", "即将支持", false),
    FUJIFILM("富士", "即将支持", false),
}

data class CameraConfig(
    val host: String = "192.168.1.1",
    val port: Int = 15740,
    val autoExport: Boolean = true,
    val lastSsid: String = "",
    val brand: CameraBrand = CameraBrand.NIKON,
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
enum class LutCategory(val title: String) { PORTRAIT("人像"), FILM("胶片"), CINEMA("电影"), LANDSCAPE("风光"), OTHER("未分类") }
data class LutEntry(val id: String, val name: String, val category: LutCategory = LutCategory.OTHER)

private val LutInputExtensions = setOf("jpg", "jpeg", "png", "nef", "nrw", "cr2", "arw", "dng", "raf")
fun String.supportsLutInput(): Boolean = substringAfterLast('.', "").lowercase(Locale.ROOT) in LutInputExtensions

enum class Workflow { WAITING, CONNECTING, CONNECTED, LOADING, DOWNLOADING, ERROR }
data class PageTask(val message: String, val running: Boolean, val failed: Boolean = false)

class AppStore(context: Context) {
    private val prefs = context.getSharedPreferences("nikon_connect", Context.MODE_PRIVATE)

    fun config(): CameraConfig = CameraConfig(
        prefs.getString("host", "192.168.1.1") ?: "192.168.1.1",
        prefs.getInt("port", 15740),
        prefs.getBoolean("autoExport", true),
        prefs.getString("last_ssid", "") ?: "",
        prefs.getString("camera_brand", CameraBrand.NIKON.name)?.let { runCatching { CameraBrand.valueOf(it) }.getOrDefault(CameraBrand.NIKON) } ?: CameraBrand.NIKON,
    )

    fun save(config: CameraConfig) = prefs.edit()
        .putString("host", config.host).putInt("port", config.port)
        .putBoolean("autoExport", config.autoExport)
        .putString("last_ssid", config.lastSsid)
        .putString("camera_brand", config.brand.name).apply()

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

    fun removeDownloads(uris: Set<String>) {
        val rows = downloads().filter { it.uri !in uris }
        val array = JSONArray()
        rows.forEach { array.put(JSONObject().put("name", it.name).put("size", it.size).put("uri", it.uri).put("time", it.completedAt)) }
        prefs.edit().putString("downloads", array.toString()).apply()
    }

    fun luts(): List<LutEntry> = runCatching {
        val array = JSONArray(prefs.getString("luts", "[]"))
        (0 until array.length()).map { i ->
            array.getJSONObject(i).let { LutEntry(it.getString("id"), it.getString("name"), runCatching { LutCategory.valueOf(it.optString("category")) }.getOrDefault(LutCategory.OTHER)) }
        }
    }.getOrDefault(emptyList())

    fun saveLuts(entries: List<LutEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("category", it.category.name)) }
        prefs.edit().putString("luts", array.toString()).apply()
    }

    fun recentLuts(): List<String> = prefs.getString("recent_luts", "")
        ?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

    fun saveRecentLuts(ids: List<String>) = prefs.edit().putString("recent_luts", ids.joinToString(",")).apply()

    fun watermarks(): List<WatermarkPreset> = runCatching {
        if (!prefs.contains("watermarks")) return defaultWatermarkPresets()
        val array = JSONArray(prefs.getString("watermarks", "[]"))
        (0 until array.length()).map { i ->
            array.getJSONObject(i).let { json ->
                WatermarkPreset(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    layout = runCatching { WatermarkLayout.valueOf(json.optString("layout")) }.getOrDefault(WatermarkLayout.MINIMAL),
                    fields = json.optString("fields").split(',').mapNotNull { runCatching { WatermarkField.valueOf(it) }.getOrNull() }.toSet(),
                    fontSize = json.optInt("fontSize", 34),
                    textColor = json.optInt("textColor", -1),
                    backgroundColor = json.optInt("backgroundColor", -16777216),
                    backgroundAlpha = json.optInt("backgroundAlpha", 68),
                    margin = json.optInt("margin", 28),
                    showBorder = json.optBoolean("showBorder", false),
                    customText = json.optString("customText"),
                    copyrightText = json.optString("copyrightText"),
                    logoEnabled = json.optBoolean("logoEnabled", true),
                    useBrandLogo = json.optBoolean("useBrandLogo", true),
                    logoUri = json.optString("logoUri").takeIf { it.isNotBlank() },
                    logoScale = json.optInt("logoScale", 100),
                    logoAlpha = json.optInt("logoAlpha", 100),
                    logoPosition = runCatching { WatermarkLogoPosition.valueOf(json.optString("logoPosition")) }.getOrDefault(WatermarkLogoPosition.TOP_RIGHT),
                    quality = json.optInt("quality", 95),
                )
            }
        }
    }.getOrDefault(defaultWatermarkPresets())

    fun saveWatermarks(entries: List<WatermarkPreset>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id); put("name", entry.name); put("layout", entry.layout.name); put("fields", entry.fields.joinToString(",") { it.name })
                put("fontSize", entry.fontSize); put("textColor", entry.textColor); put("backgroundColor", entry.backgroundColor); put("backgroundAlpha", entry.backgroundAlpha)
                put("margin", entry.margin); put("showBorder", entry.showBorder); put("customText", entry.customText); put("copyrightText", entry.copyrightText)
                put("logoEnabled", entry.logoEnabled); put("useBrandLogo", entry.useBrandLogo); put("logoUri", entry.logoUri ?: ""); put("logoScale", entry.logoScale); put("logoAlpha", entry.logoAlpha); put("logoPosition", entry.logoPosition.name); put("quality", entry.quality)
            })
        }
        prefs.edit().putString("watermarks", array.toString()).apply()
    }

}

private fun defaultWatermarkPresets() = listOf(
    WatermarkPreset("minimal", "极简底部信息", WatermarkLayout.MINIMAL),
    WatermarkPreset("left", "左下角摄影参数", WatermarkLayout.LEFT_PARAMS, setOf(WatermarkField.CAMERA_MODEL, WatermarkField.LENS_MODEL, WatermarkField.FOCAL_LENGTH, WatermarkField.APERTURE, WatermarkField.SHUTTER, WatermarkField.ISO)),
    WatermarkPreset("right", "右下角摄影参数", WatermarkLayout.RIGHT_PARAMS),
    WatermarkPreset("white", "白色底边框信息", WatermarkLayout.WHITE_BORDER, showBorder = true, backgroundColor = android.graphics.Color.WHITE, textColor = android.graphics.Color.BLACK, backgroundAlpha = 230),
    WatermarkPreset("custom", "仅自定义文字", WatermarkLayout.CUSTOM, setOf(WatermarkField.CUSTOM_TEXT), logoEnabled = false, useBrandLogo = false),
)

fun Long.prettySize(): String = when {
    this >= 1_073_741_824 -> "%.1f GB".format(Locale.getDefault(), this / 1_073_741_824.0)
    this >= 1_048_576 -> "%.1f MB".format(Locale.getDefault(), this / 1_048_576.0)
    else -> "%.0f KB".format(Locale.getDefault(), this / 1024.0)
}

fun Date.prettyDate(): String = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(this)

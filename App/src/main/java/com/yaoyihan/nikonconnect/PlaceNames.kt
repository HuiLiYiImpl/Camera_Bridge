package com.yaoyihan.nikonconnect

import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import org.json.JSONObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.HttpURLConnection
import java.net.URL

internal data class PlaceNameSettings(val enabled: Boolean = false, val key: String = "")

internal class PlaceNameStore(context: Context) {
    val prefs = context.getSharedPreferences("link_geocoding", Context.MODE_PRIVATE)
    fun read() = PlaceNameSettings(prefs.getBoolean("enabled", false), prefs.getString("key", "").orEmpty())
    fun save(settings: PlaceNameSettings) {
        prefs.edit().putBoolean("enabled", settings.enabled).putString("key", settings.key.trim()).apply()
    }
}

data class PlaceNameState(val address: String? = null, val message: String = "可在设置中开启地点名称", val loading: Boolean = false)

internal fun nearby(lat: Double, lng: Double, otherLat: Double, otherLng: Double): Boolean {
    val distance = FloatArray(1)
    Location.distanceBetween(lat, lng, otherLat, otherLng, distance)
    return distance[0] < 100f
}

internal class PlaceNameException(message: String) : Exception(message)

internal class TencentPlaceNames(private val context: Context) {
    suspend fun resolve(latitude: Double, longitude: Double, key: String): String {
        // Android GPS uses WGS84; convert only the lookup copy, never the camera's GPS fix.
        val converted = request("coord/v1/translate", key, mapOf("locations" to "$latitude,$longitude", "type" to "1"))
            .getJSONArray("locations").getJSONObject(0)
        val lat = converted.getDouble("lat")
        val lng = converted.getDouble("lng")
        require(lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0)
        currentCoroutineContext().ensureActive()
        val result = request("geocoder/v1/", key, mapOf("location" to "$lat,$lng", "get_poi" to "0")).getJSONObject("result")
        return result.optJSONObject("formatted_addresses")?.optString("recommend")?.takeIf { it.isNotBlank() }
            ?: result.optString("address").takeIf { it.isNotBlank() }
            ?: throw PlaceNameException("该位置暂无地点名称")
    }

    private fun request(path: String, key: String, parameters: Map<String, String>): JSONObject {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val internet = manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        } ?: throw PlaceNameException("地点解析需要互联网连接")
        val uri = Uri.parse("https://apis.map.qq.com/ws/$path").buildUpon().appendQueryParameter("key", key)
        parameters.forEach { (name, value) -> uri.appendQueryParameter(name, value) }
        val connection = internet.openConnection(URL(uri.build().toString())) as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            if (connection.responseCode != 200) throw PlaceNameException("腾讯地图暂不可用，请稍后重试")
            val bytes = connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 64 * 1024) throw PlaceNameException("地点解析返回异常")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val status = json.getInt("status")
            if (status != 0) throw PlaceNameException(when (status) {
                110, 111, 112, 311 -> "请检查腾讯地图 Key 和 WebService API 授权"
                120, 121 -> "腾讯地图调用额度或频率受限，请查看控制台"
                else -> "腾讯地图解析失败（$status），请检查 Key 权限与额度"
            })
            return json
        } finally { connection.disconnect() }
    }
}

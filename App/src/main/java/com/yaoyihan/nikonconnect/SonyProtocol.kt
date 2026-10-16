/* Adapted from studio-camera (develop e4a82d7), Copyright (c) 2026 Lenny Obez.
 * Personal/non-commercial use; see assets/licenses/studio-camera.txt and CAMERA_INTEGRATION.md. */
package com.yaoyihan.nikonconnect

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.URI
import java.time.OffsetDateTime
import javax.xml.parsers.DocumentBuilderFactory

internal data class SonyDevice(val endpoint: String, val contentEndpoint: String, val name: String, val details: CameraDetails) {
    val host: String get() = URI(endpoint).host
}

internal data class SonyMedia(val key: String, val name: String, val url: String, val thumbnail: String?, val size: Long, val capturedAt: Long)
internal data class SonyPage(val media: List<SonyMedia>, val directories: List<String>, val returned: Int, val signature: String)
internal class SonyApiException(val code: Int, method: String) : IOException(when (code) {
    2 -> "索尼相机未接受参数（$method）"
    3 -> "索尼相机暂时不可用，请检查拍摄或传输模式（$method）"
    12, 14 -> "此索尼机型未提供所需接口（$method）"
    403 -> "索尼相机拒绝操作，请在机身上确认连接"
    else -> "索尼操作失败（$method，错误 $code）"
})

internal object SonyProtocol {
    fun resolve(base: String, path: String): String {
        val uri = URI(base).resolve(path.trim())
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null &&
            uri.host.equals(URI(base).host, true)) { "索尼相机返回了无效或跨设备的资源地址" }
        return uri.toASCIIString()
    }

    fun description(xml: String, location: String): SonyDevice? {
        require(xml.length <= 1024 * 1024 && !Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(xml)) { "索尼设备描述无效" }
        val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
            .newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        fun Element.all(name: String): List<Element> = getElementsByTagNameNS("*", name).let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
        fun Element.text(name: String) = all(name).firstOrNull()?.textContent?.trim().orEmpty()
        if (!root.text("manufacturer").contains("Sony", true)) return null
        val services = root.all("X_ScalarWebAPI_Service").mapNotNull { service ->
            val type = service.text("X_ScalarWebAPI_ServiceType")
            val action = service.text("X_ScalarWebAPI_ActionList_URL")
            if (type.isBlank() || action.isBlank()) null else {
                val base = resolve(location, action).trimEnd('/')
                type to if (base.endsWith("/$type")) base else "$base/$type"
            }
        }.toMap()
        val camera = services["camera"] ?: return null
        val details = CameraDetails(manufacturer = "Sony", model = root.text("modelName").ifBlank { null },
            serialNumber = root.text("serialNumber").ifBlank { root.text("UDN") }.ifBlank { null })
        return SonyDevice(camera, services["avContent"] ?: camera.substringBeforeLast('/') + "/avContent",
            root.text("friendlyName").ifBlank { details.model ?: "Sony" }, details)
    }

    fun request(method: String, params: JSONArray, version: String, id: Int): String = JSONObject()
        .put("method", method).put("params", params).put("id", id).put("version", version).toString()

    fun result(body: String, method: String, id: Int): JSONArray {
        val response = JSONObject(body)
        if (response.optInt("id", -1) != id) throw IOException("索尼响应与当前请求不匹配")
        response.optJSONArray("error")?.takeIf { it.length() > 0 }?.let { throw SonyApiException(it.optInt(0, -1), method) }
        return response.optJSONArray("result") ?: throw IOException("索尼响应缺少操作结果（$method）")
    }

    fun apiNames(result: JSONArray): Set<String> {
        val names = result.optJSONArray(0) ?: throw IOException("索尼能力列表格式无效")
        return (0 until names.length()).mapNotNull { names.optString(it).takeIf(String::isNotBlank) }.toSet()
    }

    fun page(result: JSONArray, base: String): SonyPage {
        val rows = result.optJSONArray(0) ?: throw IOException("索尼相册响应格式无效")
        val media = mutableListOf<SonyMedia>()
        val directories = mutableListOf<String>()
        val identities = mutableListOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val uri = row.optString("uri")
            identities.add(uri.ifBlank { row.toString() })
            if (row.optString("contentKind") == "directory" || row.optString("isBrowsable").equals("true", true)) {
                if (uri.isNotBlank()) directories.add(uri)
                continue
            }
            val content = row.optJSONObject("content") ?: continue
            val originals = content.optJSONArray("original") ?: continue
            val thumb = content.optString("thumbnailUrl").ifBlank {
                content.optJSONArray("thumbnail")?.optJSONObject(0)?.optString("url").orEmpty()
            }.takeIf(String::isNotBlank)?.let { resolve(base, it) }
            val date = runCatching { OffsetDateTime.parse(row.optString("createdTime")).toInstant().toEpochMilli() }.getOrDefault(0)
            for (j in 0 until originals.length()) {
                val original = originals.getJSONObject(j)
                val url = original.optString("url").takeIf(String::isNotBlank)?.let { resolve(base, it) } ?: continue
                val name = original.optString("fileName").ifBlank { URI(url).path.substringAfterLast('/') }
                    .substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_")
                if (name.isBlank()) continue
                val key = "${uri.ifBlank { URI(url).path }}|$name"
                media.add(SonyMedia(key, name, url, thumb, original.optLong("fileSize", 0).coerceAtLeast(0), date))
            }
        }
        return SonyPage(media, directories.distinct(), rows.length(), identities.joinToString("\u0000"))
    }

    /** A stream stays open between calls; consume metadata and padding before returning a JPEG. */
    fun frame(input: InputStream): ByteArray {
        val data = DataInputStream(input)
        repeat(64) {
            val common = ByteArray(8).also(data::readFully)
            val header = ByteArray(128).also(data::readFully)
            if (common[0] != 0xff.toByte() || !header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x24, 0x35, 0x68, 0x79)))
                throw LiveViewImageException("索尼取景数据头无效，请重新开启取景")
            val size = ((header[4].toInt() and 255) shl 16) or ((header[5].toInt() and 255) shl 8) or (header[6].toInt() and 255)
            if (size !in 1..5_000_000) throw LiveViewImageException("索尼取景数据长度无效")
            val payload = ByteArray(size).also(data::readFully)
            data.readFully(ByteArray(header[7].toInt() and 255))
            if (common[1] == 1.toByte()) {
                if (payload.size < 4 || payload[0] != 0xff.toByte() || payload[1] != 0xd8.toByte() ||
                    payload[payload.lastIndex - 1] != 0xff.toByte() || payload.last() != 0xd9.toByte())
                    throw LiveViewImageException("索尼取景 JPEG 不完整")
                return payload
            }
        }
        throw LiveViewImageException("索尼连续返回取景元数据，未收到画面")
    }
}

package com.yaoyihan.nikonconnect

import org.w3c.dom.Element
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

internal data class LumixDevice(
    val descriptionUrl: String,
    val udn: String,
    val name: String,
    val details: CameraDetails,
    val controlUrl: String,
    val serviceType: String,
    val encrypted: Boolean,
) {
    val host: String get() = URI(descriptionUrl).host
    val cgiBase: String get() = "http://$host"
}

internal data class LumixResource(
    val url: String,
    val profile: String,
    val mime: String,
    val filename: String,
    val size: Long,
    val width: Int,
    val height: Int,
) {
    val thumbnail: Boolean get() = profile in setOf("CAM_TN", "CAM_LRGTN")
    val original: Boolean get() = profile in setOf(
        "CAM_ORG", "CAM_RAW", "CAM_RAW_JPG", "CAM_HSP_JPG", "CAM_HSP_RAW", "CAM_HSP_RAW_JPG",
        "CAM_MPO", "CAM_MPO_JPG", "CAM_FOCUS_SELECT_MP4",
    ) || profile.startsWith("CAM_AVC_MP4_") || profile.startsWith("CAM_AVC_MOV_") || profile.startsWith("CAM_AVC_TS_HP_")
}

internal data class LumixItem(val id: String, val title: String, val date: String, val resources: List<LumixResource>)
internal data class LumixBrowsePage(val items: List<LumixItem>, val containers: List<String>, val returned: Int, val total: Int?)
internal data class LumixAccess(val result: String, val sessionId: String?)

/** Wire formats checked against the local LUMIX Sync sources; no Panasonic binaries are bundled. */
internal object LumixProtocol {
    const val CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:1"

    private fun xml(text: String): Element {
        require(text.length <= 8 * 1024 * 1024) { "松下 XML 响应过大" }
        require(!Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "相机 XML 包含不允许的声明" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(text))).documentElement
    }

    private fun Element.children(name: String): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }.filter { (it.localName ?: it.tagName) == name }
    private fun Element.elements(name: String): List<Element> = getElementsByTagNameNS("*", name).let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as Element }
    }
    private fun Element.value(name: String): String = children(name).firstOrNull()?.textContent?.trim().orEmpty()

    fun resolve(base: String, value: String): String {
        val uri = URI(base).resolve(value.trim())
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "相机返回了无效的资源地址"
        }
        require(uri.host.equals(URI(base).host, ignoreCase = true)) { "相机资源地址指向其他设备" }
        return uri.toASCIIString()
    }

    fun device(description: String, location: String): LumixDevice? {
        val root = xml(description)
        val base = root.value("URLBase").takeIf(String::isNotBlank)?.let { resolve(location, it) } ?: location
        val device = root.elements("device").firstOrNull {
            it.value("manufacturer").contains("Panasonic", true) &&
                it.value("X_AdditionalFunction") in setOf("CPRemoteView", "MovieRemote")
        } ?: return null
        val service = device.children("serviceList").flatMap { it.children("service") }.firstOrNull {
            it.value("serviceType").startsWith("urn:schemas-upnp-org:service:ContentDirectory:")
        } ?: return null
        val udn = device.value("UDN").takeIf(String::isNotBlank) ?: return null
        val control = service.value("controlURL").takeIf(String::isNotBlank) ?: return null
        val details = CameraDetails(
            manufacturer = device.value("manufacturer"), model = device.value("modelName").ifBlank { null },
            deviceVersion = device.value("X_FirmVersion").ifBlank { null },
            serialNumber = device.value("serialNumber").ifBlank { udn },
        )
        return LumixDevice(location, udn, device.value("friendlyName").ifBlank { details.model ?: "LUMIX" },
            details, resolve(base, control), service.value("serviceType"), device.value("X_CamCapability").endsWith("1"))
    }

    fun cgi(base: String, mode: String, type: String? = null, value: String? = null, value2: String? = null): String {
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        val query = listOf("mode" to mode, "type" to type, "value" to value, "value2" to value2)
            .mapNotNull { (key, v) -> v?.let { "$key=${encode(it)}" } }.joinToString("&")
        return "$base/cam.cgi?$query"
    }

    fun encrypt(value: String, key: Int): String {
        val mask = (if (key == 0) -1875740628 else key).toLong() and 0xffffffffL
        return value.padEnd((value.length + 3) / 4 * 4, '\u00ff').chunked(4).joinToString("") { chunk ->
            val word = chunk.fold(0L) { acc, ch -> (acc shl 8) or ch.code.toLong() } xor mask
            val reversed = ((word and 255) shl 24) or ((word and 0xff00) shl 8) or
                ((word ushr 8) and 0xff00) or ((word ushr 24) and 255)
            "%08x".format(Locale.ROOT, reversed)
        }
    }

    fun result(body: String): String = if (body.trimStart().startsWith("<")) {
        val root = xml(body)
        root.value("result").ifBlank { throw IOException("相机未返回操作结果") }.lowercase(Locale.ROOT)
    } else body.trim().substringBefore(',').trim().lowercase(Locale.ROOT)

    fun access(body: String, cameraName: String? = null): LumixAccess {
        val status = result(body)
        require(status.isNotBlank()) { "松下相机未返回授权结果" }
        if (body.trimStart().startsWith("<")) return LumixAccess(status, null)
        val fields = body.trim().split(',').map(String::trim)
        // Sync accepts status=ok independently of the mode. Names can contain unquoted commas.
        val expectedName = cameraName?.split(',')?.joinToString(",") { it.trim() }
        val nameEnd = expectedName?.takeIf(String::isNotBlank)?.let { name ->
            (1 until fields.size - 1).firstOrNull { fields.subList(1, it + 1).joinToString(",").equals(name, true) }
        }
        val modeIndex = (2 until fields.size).lastOrNull { fields[it] in setOf("upload", "upload_bt", "transfer") }
        val modePosition = nameEnd?.plus(1) ?: modeIndex ?: 2
        val session = fields.getOrNull(modePosition + 2)?.takeIf { status == "ok" && it.isNotBlank() }
        require(session == null || (session.length <= 512 && session.none(Char::isISOControl))) { "松下相机会话标识无效" }
        return LumixAccess(status, session)
    }

    fun browseRequest(objectId: String, offset: Int, count: Int, serviceType: String = CONTENT_DIRECTORY): String {
        fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        require(offset >= 0 && count in 1..200)
        return """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Browse xmlns:u="${escape(serviceType)}" xmlns:pana="urn:schemas-panasonic-com:pana"><ObjectID>${escape(objectId)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>$offset</StartingIndex><RequestedCount>$count</RequestedCount><SortCriteria></SortCriteria><pana:X_FromCP>LumixLink2.0</pana:X_FromCP></u:Browse></s:Body></s:Envelope>"""
    }

    fun browse(body: String, base: String): LumixBrowsePage {
        val root = xml(body)
        root.elements("Fault").firstOrNull()?.let { throw IOException("松下相册请求失败：${it.textContent.trim().take(180)}") }
        val result = root.elements("Result").firstOrNull() ?: throw IOException("松下相册响应缺少 Result")
        val didl = result.children("DIDL-Lite").firstOrNull() ?: result.textContent.trim().let {
            if (it.isEmpty()) null else xml(it)
        }
        val containers = didl?.children("container").orEmpty().map { it.getAttribute("id") }.filter(String::isNotBlank)
        val items = didl?.children("item").orEmpty().map { item ->
            LumixItem(item.getAttribute("id"), item.value("title"), item.value("date"), item.children("res").map { res ->
                val protocol = res.getAttribute("protocolInfo").split(':', limit = 4)
                val params = protocol.getOrElse(3) { "" }.split(';').mapNotNull { part ->
                    val pair = part.split('=', limit = 2)
                    if (pair.size == 2) pair[0].trim() to pair[1].trim().trim('\'', '"') else null
                }.toMap()
                val dimensions = res.getAttribute("resolution").split('x', 'X')
                LumixResource(resolve(base, res.textContent), params["PANASONIC.COM_PN"].orEmpty().uppercase(Locale.ROOT),
                    protocol.getOrElse(2) { "" }, params["OriginalFileName"].orEmpty(),
                    res.getAttribute("size").toLongOrNull()?.coerceAtLeast(0) ?: 0,
                    dimensions.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    dimensions.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0)
            })
        }
        val returned = root.elements("NumberReturned").firstOrNull()?.textContent?.trim()?.toIntOrNull() ?: (containers.size + items.size)
        val total = root.elements("TotalMatches").firstOrNull()?.textContent?.trim()?.toIntOrNull()
        require(returned >= 0 && (total == null || total >= 0) && (returned > 0 || (items.isEmpty() && containers.isEmpty()))) { "松下相册分页响应无效" }
        return LumixBrowsePage(items, containers, returned, total)
    }
}

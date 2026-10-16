package com.yaoyihan.nikonconnect

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class SonyProtocolTest {
    private val base = "http://192.168.122.1:8080/sony"
    private val device = SonyDevice("$base/camera", "$base/avContent", "My Sony", CameraDetails(manufacturer = "Sony", model = "Test"))
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
    private fun row(id: String, raw: Boolean = false) = JSONObject().put("uri", "image:$id").put("title", "Different title")
        .put("createdTime", "2026-10-15T12:00:00+08:00").put("contentKind", "still").put("content", JSONObject()
            .put("thumbnailUrl", "$base/thumb/$id").put("original", JSONArray().put(JSONObject().put("fileName", "$id.JPG")
                .put("url", "$base/original/$id.JPG").put("fileSize", jpeg.size)).also {
                if (raw) it.put(JSONObject().put("fileName", "$id.ARW").put("url", "$base/original/$id.ARW").put("fileSize", jpeg.size))
            }))

    private class FakeConnection(url: URL, private val content: (FakeConnection) -> ByteArray) : HttpURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        private val bytes by lazy { content(this) }
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode(): Int { bytes; return 200 }
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getHeaderField(name: String): String? = when (name) {
            "Content-Length" -> bytes.size.toString()
            "Content-Type" -> if (url.path.endsWith("camera") || url.path.endsWith("avContent")) "application/json" else "application/octet-stream"
            else -> null
        }
    }

    private inner class Rig(val rows: (Int) -> JSONArray = { JSONArray().put(row("DSC0001", raw = true)) },
        val apiError: (String) -> Int? = { null }, val originalBytes: ByteArray = jpeg,
        val directoryRows: ((String, Int) -> JSONArray)? = null) {
        val requests = mutableListOf<FakeConnection>()
        val methods = mutableListOf<String>()
        val functions = mutableListOf<String>()
        val client = SonyCameraClient(device, { url ->
            FakeConnection(url) { connection ->
                if (url.path.endsWith("camera") || url.path.endsWith("avContent")) {
                    val request = JSONObject(connection.sent.toString("UTF-8"))
                    val method = request.getString("method")
                    methods.add(method)
                    val error = apiError(method)
                    val response = JSONObject().put("id", request.getInt("id"))
                    if (error != null) response.put("error", JSONArray().put(error).put("test error")) else {
                        val result = when (method) {
                            "getAvailableApiList" -> JSONArray().put(JSONArray(listOf("getAvailableApiList", "getMethodTypes", "setCameraFunction", "actTakePicture", "startLiveview", "stopLiveview", "getEvent")))
                            "getMethodTypes" -> JSONArray().put(JSONArray(listOf("actTakePicture", "", "", "1.0")))
                            "setCameraFunction" -> { functions.add(request.getJSONArray("params").getString(0)); JSONArray().put(0) }
                            "getContentList" -> request.getJSONArray("params").getJSONObject(0).let {
                                JSONArray().put(directoryRows?.invoke(it.getString("uri"), it.getInt("stIdx")) ?: rows(it.getInt("stIdx")))
                            }
                            "startLiveview" -> JSONArray().put("$base/live")
                            "actTakePicture" -> JSONArray().put(JSONArray().put("$base/postview.jpg"))
                            "getEvent" -> JSONArray().put(JSONObject().put("currentIsoSpeedRate", "400").put("currentFNumber", "2.8"))
                            else -> JSONArray().put(0)
                        }
                        response.put("result", result)
                    }
                    response.toString().toByteArray()
                } else if (url.path.endsWith("/live")) frame(2, byteArrayOf(1, 2)) + frame(1, jpeg)
                else originalBytes
            }.also { requests.add(it) }
        })
    }

    @Test fun discoveryUsesNamespacedServiceUrlsAndRejectsExternalEntitiesOrOtherHosts() {
        val description = """<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:s="urn:schemas-sony-com:av"><device>
            <manufacturer>Sony Corporation</manufacturer><friendlyName>My camera</friendlyName><modelName>ILCE-test</modelName><UDN>uuid:sony</UDN>
            <s:X_ScalarWebAPI_ServiceList><s:X_ScalarWebAPI_Service><s:X_ScalarWebAPI_ServiceType>camera</s:X_ScalarWebAPI_ServiceType>
            <s:X_ScalarWebAPI_ActionList_URL>$base</s:X_ScalarWebAPI_ActionList_URL></s:X_ScalarWebAPI_Service>
            <s:X_ScalarWebAPI_Service><s:X_ScalarWebAPI_ServiceType>avContent</s:X_ScalarWebAPI_ServiceType>
            <s:X_ScalarWebAPI_ActionList_URL>$base</s:X_ScalarWebAPI_ActionList_URL></s:X_ScalarWebAPI_Service></s:X_ScalarWebAPI_ServiceList></device></root>"""
        val result = SonyProtocol.description(description, "$base/description.xml")!!
        assertEquals(device.endpoint, result.endpoint)
        assertEquals(device.contentEndpoint, result.contentEndpoint)
        assertEquals("ILCE-test", result.details.model)
        assertNull(SonyProtocol.description(description.replace("Sony Corporation", "Other"), "$base/description.xml"))
        assertThrows(IllegalArgumentException::class.java) { SonyProtocol.description("<!DOCTYPE root><root/>", base) }
        assertThrows(IllegalArgumentException::class.java) { SonyProtocol.description(description.replace(base, "http://another-device/sony"), base) }
    }

    @Test fun rpcErrorsAndMismatchedResponsesCannotBeMistakenForSuccess() {
        assertThrows(SonyApiException::class.java) { SonyProtocol.result("""{"id":1,"error":[12,"unsupported"]}""", "getContentList", 1) }
        assertThrows(IOException::class.java) { SonyProtocol.result("""{"id":2,"result":[0]}""", "actTakePicture", 1) }
        assertThrows(IOException::class.java) { SonyProtocol.result("""{"id":1}""", "getAvailableApiList", 1) }
        val rig = Rig(apiError = { if (it == "getAvailableApiList") 403 else null })
        assertThrows(SonyApiException::class.java) { rig.client.connect() }
        assertThrows(IllegalStateException::class.java) { rig.client.assets(0, 1) }
        assertTrue(rig.requests.all { it.disconnected })
    }

    @Test fun albumCountsCameraRowsAndExpandsRawJpegOriginalsWithCorrectNamesAndThumbnails() {
        val rig = Rig(rows = { offset -> if (offset < 2) JSONArray().put(row("DSC$offset", raw = true)) else JSONArray() })
        rig.client.connect()
        val first = rig.client.assets(0, 1).single()
        assertEquals("DSC0.JPG", first.name)
        assertTrue(rig.client.hasMoreAssets(1))
        val rest = rig.client.assets(1, 3)
        assertEquals(listOf("DSC0.ARW", "DSC1.JPG", "DSC1.ARW"), rest.map { it.name })
        assertArrayEquals(jpeg, rig.client.thumbnail(first))
        assertArrayEquals(jpeg, rig.client.download(rest.first()) { _, _ -> })
        assertFalse(rig.client.hasMoreAssets(4))
        assertEquals(listOf("ContentsTransfer"), rig.functions)
        assertEquals(MediaKind.RAW_IMAGE, mediaKindFor(null, rest.first().name))
        val contentRequests = rig.requests.filter { it.url.path.endsWith("avContent") }
        assertEquals(listOf(0, 1), contentRequests.map { JSONObject(it.sent.toString("UTF-8")).getJSONArray("params").getJSONObject(0).getInt("stIdx") })
        assertTrue(rig.requests.all { it.disconnected })
    }

    @Test fun directoryTraversalAndSkippedResourcesDoNotStopPaginationEarly() {
        val rig = Rig(directoryRows = { uri, offset -> when {
            uri == "storage:memoryCard1" && offset == 0 -> JSONArray().put(JSONObject().put("uri", "storage:folder").put("contentKind", "directory"))
            uri == "storage:folder" && offset == 0 -> JSONArray().put(row("later"))
            else -> JSONArray()
        } })
        rig.client.connect()
        assertEquals("later.JPG", rig.client.assets(0, 1).single().name)
        assertThrows(IllegalArgumentException::class.java) {
            SonyProtocol.page(JSONArray().put(JSONArray().put(row("x").also {
                it.getJSONObject("content").getJSONArray("original").getJSONObject(0).put("url", "http://other-device/photo.jpg")
            })), base)
        }
        val repeating = Rig(rows = { JSONArray().put(row("same")) })
        repeating.client.connect(); repeating.client.assets(0, 1)
        assertThrows(IOException::class.java) { repeating.client.assets(1, 1) }
        val before = SonyProtocol.page(JSONArray().put(JSONArray().put(row("same"))), base).media.first()
        val changedToken = row("same").also { it.getJSONObject("content").getJSONArray("original").getJSONObject(0).put("url", before.url + "?token=new") }
        assertEquals(before.key, SonyProtocol.page(JSONArray().put(JSONArray().put(changedToken)), base).media.first().key)
    }

    @Test fun unsupportedAlbumsFailExplicitlyWhileRemoteCapabilitiesStayAvailable() {
        val rig = Rig(apiError = { if (it == "getContentList") 12 else null })
        rig.client.connect()
        assertThrows(SonyApiException::class.java) { rig.client.assets(0, 1) }
        assertTrue(rig.client.supportsRemoteCapture)
        assertTrue(rig.client.checkConnection())
    }

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val common = ByteArray(8).apply { this[0] = 0xff.toByte(); this[1] = type.toByte() }
        val header = ByteArray(128).apply {
            byteArrayOf(0x24, 0x35, 0x68, 0x79).copyInto(this)
            this[4] = (payload.size ushr 16).toByte(); this[5] = (payload.size ushr 8).toByte(); this[6] = payload.size.toByte(); this[7] = 3
        }
        return common + header + payload + byteArrayOf(9, 9, 9)
    }

    @Test fun liveViewConsumesMetadataAndPaddingAndTransfersReturnToContentMode() {
        val rig = Rig()
        rig.client.connect(); val photo = rig.client.assets(0, 2).first()
        rig.client.startLiveView()
        assertArrayEquals(jpeg, rig.client.liveViewFrame().jpeg)
        val parameters = rig.client.shootingParameters()
        assertEquals("400", parameters.iso)
        assertEquals("f/2.8", parameters.aperture)
        assertArrayEquals(jpeg, rig.client.download(photo) { _, _ -> })
        assertEquals(listOf("ContentsTransfer", "RemoteShooting", "ContentsTransfer"), rig.functions)
        assertEquals(1, rig.methods.count { it == "stopLiveview" })
        assertThrows(IOException::class.java) { SonyProtocol.frame(ByteArrayInputStream(frame(1, jpeg).dropLast(4).toByteArray())) }
        assertThrows(LiveViewImageException::class.java) { SonyProtocol.frame(ByteArrayInputStream(frame(1, jpeg).also { it[8] = 0 })) }
        assertTrue(rig.requests.all { it.disconnected })
    }

    @Test fun cancelledOrTruncatedTransfersCannotReportSuccessAndCloseInterruptsRequests() {
        val rig = Rig()
        rig.client.connect(); val photo = rig.client.assets(0, 1).single()
        val output = ByteArrayOutputStream()
        var cancel = false
        assertThrows(DownloadCancelledException::class.java) { rig.client.downloadTo(photo, output, { _, _ -> cancel = true }, { cancel }) }
        assertEquals(0, output.size())
        val truncated = Rig(originalBytes = jpeg.dropLast(1).toByteArray())
        truncated.client.connect(); val incomplete = truncated.client.assets(0, 1).single()
        assertThrows(IOException::class.java) { truncated.client.download(incomplete) { _, _ -> } }
        rig.client.startLiveView(); rig.client.close()
        assertTrue(rig.requests.all { it.disconnected })
        assertThrows(DownloadCancelledException::class.java) { rig.client.download(photo) { _, _ -> } }
    }

    @Test fun captureRequiresConfirmationAndNeverResendsAnUncertainShutter() {
        val rig = Rig()
        rig.client.connect()
        assertThrows(DownloadCancelledException::class.java) { rig.client.capture({ true }, {}) }
        assertFalse("actTakePicture" in rig.methods)
        var sending = 0
        assertTrue(rig.client.capture({ false }, { sending++ }).confirmed)
        assertEquals(1, sending)
        val uncertain = Rig(apiError = { if (it == "actTakePicture") throw IOException("timeout") else null })
        uncertain.client.connect()
        assertFalse(uncertain.client.capture({ false }, {}).confirmed)
        assertEquals(1, uncertain.methods.count { it == "actTakePicture" })
    }
}

package com.yaoyihan.nikonconnect

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class LumixProtocolTest {
    private val location = "http://192.168.54.1:60606/Lumix/Server0/ddd"
    private val description = """<root xmlns="urn:schemas-upnp-org:device-1-0"><device>
        <manufacturer>Panasonic</manufacturer><modelName>DC-S5M2</modelName><friendlyName>My LUMIX</friendlyName>
        <UDN>uuid:camera-1</UDN><X_AdditionalFunction>CPRemoteView</X_AdditionalFunction><X_CamCapability>01</X_CamCapability>
        <X_FirmVersion>3.7</X_FirmVersion><serviceList><service><serviceType>${LumixProtocol.CONTENT_DIRECTORY}</serviceType>
        <controlURL>/Server0/CDS_control</controlURL></service></serviceList></device></root>"""
    private val device get() = LumixProtocol.device(description, location)!!

    private fun res(profile: String, mime: String, filename: String = "", url: String = "/media/$profile", size: Int = 4): String =
        """<res protocolInfo="http-get:*:$mime:PANASONIC.COM_PN=$profile;OriginalFileName='$filename'" size="$size" resolution="6000x4000">$url</res>"""

    private fun didl(content: String) = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/">$content</DIDL-Lite>"""
    private fun item(id: String, resources: String) = """<item id="$id"><dc:title>Photo</dc:title><dc:date>2026-10-15T12:00:00Z</dc:date>$resources</item>"""
    private fun soap(content: String, returned: Int, total: Int, escaped: Boolean = true): String {
        val result = if (escaped) content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") else content
        return """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:BrowseResponse xmlns:u="${LumixProtocol.CONTENT_DIRECTORY}"><Result>$result</Result><NumberReturned>$returned</NumberReturned><TotalMatches>$total</TotalMatches></u:BrowseResponse></s:Body></s:Envelope>"""
    }

    @Test fun descriptionResolvesServiceAndRejectsUnrelatedServers() {
        assertEquals("http://192.168.54.1:60606/Server0/CDS_control", device.controlUrl)
        assertEquals("3.7", device.details.deviceVersion)
        assertTrue(device.encrypted)
        assertNull(LumixProtocol.device(description.replace("Panasonic", "Other"), location))
        assertNull(LumixProtocol.device(description.replace("CPRemoteView", "MediaServer"), location))
        assertThrows(IllegalArgumentException::class.java) {
            LumixProtocol.device(description.replace("/Server0/CDS_control", "http://other.example/control"), location)
        }
    }

    @Test fun cgiKeepsAccessControlParametersInTheirCorrectSlots() {
        assertEquals("http://192.168.54.1/cam.cgi?mode=accctrl&type=req_acc&value=client&value2=Camera%20%26%20Bridge",
            LumixProtocol.cgi(device.cgiBase, "accctrl", "req_acc", "client", "Camera & Bridge"))
        assertEquals("http://192.168.54.1/cam.cgi?mode=accctrl&type=req_acc_g", LumixProtocol.cgi(device.cgiBase, "accctrl", "req_acc_g"))
        assertEquals("40404040", LumixProtocol.encrypt("ABCD", 0x01020304))
        assertEquals("fbfcfd40", LumixProtocol.encrypt("A", 0x01020304))
        assertEquals("bbbcbdbe", LumixProtocol.encrypt("ABCD", -1))
        assertEquals(LumixProtocol.encrypt("ABCD", -1875740628), LumixProtocol.encrypt("ABCD", 0))
    }

    @Test fun accessResponseAllowsCommasInTheCameraNameAndExtractsSession() {
        assertEquals(LumixAccess("ok", "session-7"), LumixProtocol.access("ok,Camera, in studio,transfer,S5M2,session-7\r\n"))
        assertEquals(LumixAccess("ok", null), LumixProtocol.access("ok,Camera,upload,S5M2"))
        assertEquals("err_reject", LumixProtocol.result("<camrply><result>err_reject</result></camrply>"))
        assertEquals("wifi_reset", LumixProtocol.result("wifi_reset"))
        assertEquals(LumixAccess("ok", null), LumixProtocol.access("ok,unexpected"))
        assertThrows(IllegalArgumentException::class.java) { LumixProtocol.access(" ") }
    }

    @Test fun successfulAccessDoesNotRequireAKnownModeAndPreservesOptionalSession() {
        assertEquals(LumixAccess("ok", null), LumixProtocol.access("ok\r\n"))
        assertEquals(LumixAccess("ok", null), LumixProtocol.access("OK ,Studio,remote,S5M2"))
        assertEquals(LumixAccess("ok", "session-8"), LumixProtocol.access("ok,Studio,remote,S5M2,session-8"))
        assertEquals(LumixAccess("ok", "session-9"), LumixProtocol.access("ok,Studio, Room,remote,S5M2,session-9", "Studio, Room"))
        assertEquals(LumixAccess("ok", null), LumixProtocol.access("ok,Studio, Room,remote,S5M2", "Studio, Room"))
        assertEquals(LumixAccess("ok", null), LumixProtocol.access("<camrply><result>OK</result></camrply>"))
        assertEquals("err_reject", LumixProtocol.access("err_reject,Studio,remote,S5M2").result)
        assertThrows(IllegalArgumentException::class.java) { LumixProtocol.access("ok,Studio,remote,S5M2,session\r\ninjected") }
    }

    @Test fun escapedAndEmbeddedDidlKeepRawJpegAndThumbnailSeparate() {
        val resources = res("CAM_RAW_JPG", "application/octet-stream", "P1000001.JPG", "/photo.JPG?x=1&amp;y=2") +
            res("CAM_RAW", "application/octet-stream", "P1000001.RW2") + res("CAM_TN", "image/jpeg")
        for (escaped in listOf(true, false)) {
            val page = LumixProtocol.browse(soap(didl(item("1", resources)), 1, 1, escaped), location)
            assertEquals(1, page.returned)
            val parsed = page.items.single().resources
            assertEquals(listOf("P1000001.JPG", "P1000001.RW2"), parsed.filter { it.original }.map { it.filename })
            assertTrue(parsed.last().thumbnail)
            assertFalse(parsed.last().original)
            assertEquals(6000, parsed.first().width)
            assertTrue(parsed.first().url.endsWith("?x=1&y=2"))
        }
        assertFalse(LumixResource("/x", "UNKNOWN_DATA", "image/jpeg", "", 0, 0, 0).original)
    }

    @Test fun xmlRejectsExternalEntitiesAndSoapFaults() {
        assertThrows(IllegalArgumentException::class.java) {
            LumixProtocol.device("<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///private'>]><root>&x;</root>", location)
        }
        assertThrows(IOException::class.java) { LumixProtocol.browse("<Envelope><Fault><errorCode>701</errorCode></Fault></Envelope>", location) }
        assertTrue(LumixProtocol.browseRequest("A&B<1>", 5, 10).contains("<ObjectID>A&amp;B&lt;1&gt;</ObjectID>"))
        assertTrue(LumixProtocol.browseRequest("0", 0, 10).contains("<pana:X_FromCP>LumixLink2.0</pana:X_FromCP>"))
    }

    private class FakeConnection(url: URL, private val content: (FakeConnection) -> ByteArray, private val mime: String = "text/xml") : HttpURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(content(this))
        override fun getHeaderField(name: String): String? = when (name) {
            "Content-Type" -> mime
            "Content-Length" -> content(this).size.toString()
            else -> null
        }
    }

    private class Rig(val device: LumixDevice, val browse: (FakeConnection) -> String, val bytes: ByteArray = byteArrayOf(1, 2, 3, 4),
        val accessBody: String = "ok,Studio,transfer,S5M2,secret-session", val capabilityResult: String = "ok",
        val namingResult: String = "ok", val challengeResult: String = "ok,01020304") {
        val requests = mutableListOf<FakeConnection>()
        val client = LumixCameraClient(device, "client", "Bridge", { url ->
            FakeConnection(url, { connection ->
                when {
                    url.path == "/cam.cgi" -> when {
                        url.query.contains("req_acc_g") -> challengeResult.toByteArray()
                        url.query.contains("req_acc_e") -> accessBody.toByteArray()
                        url.query.contains("type=device_name") -> "<camrply><result>$namingResult</result></camrply>".toByteArray()
                        url.query.contains("mode=getinfo") -> "<camrply><result>$capabilityResult</result></camrply>".toByteArray()
                        else -> "<camrply><result>ok</result></camrply>".toByteArray()
                    }
                    url.path.endsWith("CDS_control") -> browse(connection).toByteArray()
                    else -> bytes
                }
            }, if (url.path.startsWith("/media")) "application/octet-stream" else "text/xml").also { requests.add(it) }
        })
    }

    @Test fun shortAuthorizationSuccessStillRequiresCapabilityCheckAndRejectionsStopInitialization() {
        val browse = { _: FakeConnection -> soap(didl(""), 0, 0) }
        val short = Rig(device, browse, accessBody = "ok")
        short.client.connect()
        assertTrue(short.requests.any { it.url.query?.contains("type=capability") == true })
        val refused = Rig(device, browse, accessBody = "err_reject")
        assertThrows(IOException::class.java) { refused.client.connect() }
        assertFalse(refused.requests.any { it.url.query?.contains("type=capability") == true })
        val capabilityRefused = Rig(device, browse, accessBody = "ok", capabilityResult = "err_reject")
        assertThrows(IOException::class.java) { capabilityRefused.client.connect() }
        assertThrows(IllegalStateException::class.java) { capabilityRefused.client.assets(0, 1) }
    }

    @Test fun clientTraversesContainersExpandsPairsAndUsesSessionForOriginalDownloads() {
        val rig = Rig(device, { connection ->
            if (connection.sent.toString("UTF-8").contains("<ObjectID>0</ObjectID>")) soap(didl("<container id=\"photos\"/>"), 1, 1)
            else soap(didl(item("1", res("CAM_RAW_JPG", "application/octet-stream", "P1.JPG") + res("CAM_RAW", "application/octet-stream", "P1.RW2") + res("CAM_LRGTN", "image/jpeg"))), 1, 1)
        })
        rig.client.connect()
        val photos = rig.client.assets(0, 10)
        assertEquals(listOf("P1.JPG", "P1.RW2"), photos.map { it.name })
        assertEquals(listOf("JPEG", "RAW"), photos.map { it.type })
        assertFalse(rig.client.hasMoreAssets(photos.size))
        assertArrayEquals(rig.bytes, rig.client.download(photos.last()) { _, _ -> })
        val download = rig.requests.last()
        assertEquals("secret-session", download.getRequestProperty("X-SESSION_ID"))
        assertNull(download.getRequestProperty("X-CONVERT"))
        assertTrue(download.disconnected)
        val handles = photos.map { it.handle }
        rig.client.refreshAssets()
        assertEquals(handles, rig.client.assets(0, 10).map { it.handle })
        assertEquals(MediaKind.RAW_IMAGE, mediaKindFor(null, "P1.RW2"))
    }

    @Test fun paginationUsesCameraItemCountRatherThanExpandedRawJpegCount() {
        val rig = Rig(device, { connection ->
            val offset = Regex("<StartingIndex>(\\d+)</StartingIndex>").find(connection.sent.toString("UTF-8"))!!.groupValues[1].toInt()
            soap(didl(item("item$offset", res("CAM_ORG", "image/jpeg", "P$offset.JPG", "/media/$offset"))), 1, 2)
        })
        rig.client.connect()
        assertEquals("P0.JPG", rig.client.assets(0, 1).single().name)
        assertTrue(rig.client.hasMoreAssets(1))
        assertEquals("P1.JPG", rig.client.assets(1, 1).single().name)
        assertFalse(rig.client.hasMoreAssets(2))
    }

    @Test fun cancelledAndTruncatedDownloadsNeverReportSuccessAndCloseTheRequest() {
        val rig = Rig(device, { soap(didl(item("1", res("CAM_ORG", "image/jpeg", "P.JPG", size = 6))), 1, 1) })
        rig.client.connect()
        val photo = rig.client.assets(0, 1).single()
        val output = ByteArrayOutputStream()
        var cancel = false
        assertThrows(DownloadCancelledException::class.java) {
            rig.client.downloadTo(photo, output, { _, _ -> cancel = true }, { cancel })
        }
        assertEquals(0, output.size())
        assertTrue(rig.requests.last().disconnected)
        assertThrows(IOException::class.java) { rig.client.download(photo) { _, _ -> } }
        assertTrue(rig.requests.last().disconnected)
        rig.client.close()
        assertThrows(DownloadCancelledException::class.java) { rig.client.download(photo) { _, _ -> } }
    }

    @Test fun optionalNamingRejectionDoesNotBlockAccessButAuthorizationFailuresStillDo() {
        val rig = Rig(device, { soap(didl(""), 0, 0) }, namingResult = "err_param", challengeResult = " OK ,01020304\r\n")
        rig.client.connect()
        assertTrue(rig.requests.any { it.url.query?.contains("type=capability") == true })
        assertTrue(rig.client.hasMoreAssets(0))
        val denied = Rig(device, { soap(didl(""), 0, 0) }, namingResult = "err_reject")
        assertThrows(IOException::class.java) { denied.client.connect() }
    }

    @Test fun emptyOriginalWithUnknownSizeIsAFailure() {
        val rig = Rig(device, { soap(didl(item("1", res("CAM_ORG", "image/jpeg", "EMPTY.JPG", size = 0))), 1, 1) }, bytes = byteArrayOf())
        rig.client.connect()
        val photo = rig.client.assets(0, 1).single()
        assertThrows(IOException::class.java) { rig.client.download(photo) { _, _ -> } }
        assertTrue(rig.requests.last().disconnected)
    }
}

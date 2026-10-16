package com.yaoyihan.nikonconnect

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.SocketFactory

/** Exercise the public client through encoded PTP/IP packets, including reconnects. */
class PtpIpTransferTest {
    private fun u16(value: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
    private fun u32(value: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()
    private fun u64(value: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    private fun packet(type: Int, body: ByteArray) = u32((body.size + 8).toLong()) + u32(type.toLong()) + body
    private fun string(value: String): ByteArray = if (value.isEmpty()) byteArrayOf(0) else byteArrayOf((value.length + 1).toByte()) + (value + '\u0000').toByteArray(Charsets.UTF_16LE)
    private fun array16(values: List<Int>) = u32(values.size.toLong()) + values.fold(byteArrayOf()) { bytes, value -> bytes + u16(value) }
    private fun response(id: Long, code: Int = 0x2001) = packet(7, u16(code) + u32(id))
    private fun data(id: Long, bytes: ByteArray, declared: Long = bytes.size.toLong()): ByteArray =
        packet(9, u32(id) + u64(declared)) + packet(12, u32(id) + bytes) + response(id)
    private fun asset(size: Long) = PhotoAsset(1u, "TEST.NEF", size, 0x3000)

    private inner class Rig(val storageSupported: Boolean = false, val captureSupported: Boolean = false,
        val eventOperations: List<Int> = emptyList(), val operation: (Int, Long, List<Long>) -> ByteArray?) {
        val calls = mutableListOf<Pair<Int, List<Long>>>()
        val traces = mutableListOf<String>()
        var commandConnections = 0
        private val factory = object : SocketFactory() {
            override fun createSocket(host: String, port: Int): Socket = WireSocket()
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = createSocket(host, port)
            override fun createSocket(host: InetAddress, port: Int) = createSocket(host.hostAddress!!, port)
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int) = createSocket(host, port)
        }
        val client = PtpIpClient("camera", 15740, factory, onProtocolEvent = traces::add)

        private inner class WireSocket : Socket() {
            private var incoming = ByteArrayInputStream(byteArrayOf())
            private var closed = false
            private val output = object : ByteArrayOutputStream() {
                override fun flush() {
                    val bytes = toByteArray(); reset()
                    if (bytes.isEmpty()) return
                    val reader = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    reader.int
                    incoming = ByteArrayInputStream(when (reader.int) {
                        1 -> { commandConnections++; packet(2, u32(1) + ByteArray(16) + "Nikon\u0000".toByteArray(Charsets.UTF_16LE) + u32(0x10000)) }
                        3 -> packet(4, byteArrayOf())
                        6 -> {
                            reader.int
                            val op = reader.short.toInt() and 0xffff
                            val id = reader.int.toLong() and 0xffffffffL
                            val params = mutableListOf<Long>()
                            while (reader.remaining() >= 4) params.add(reader.int.toLong() and 0xffffffffL)
                            calls.add(op to params)
                            operation(op, id, params) ?: when (op) {
                                0x1001 -> data(id, u16(100) + u32(10) + u16(100) + string("") + u16(0) +
                                    array16(listOf(0x1009, 0x101B) + (if (storageSupported) listOf(0x1004) else emptyList()) +
                                        (if (captureSupported) listOf(0x100E) else emptyList()) + eventOperations) + array16(emptyList()) + array16(emptyList()) +
                                    array16(emptyList()) + array16(emptyList()) + string("Nikon") + string("Test") + string("1.0") + string("serial"))
                                else -> response(id)
                            }
                        }
                        else -> throw AssertionError("Unexpected request")
                    })
                }
            }
            override fun getOutputStream(): OutputStream = output
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read() = if (closed) -1 else incoming.read()
                override fun read(bytes: ByteArray, offset: Int, length: Int) = if (closed) -1 else incoming.read(bytes, offset, length)
            }
            override fun close() { closed = true }
            override fun setSoTimeout(value: Int) = Unit
            override fun setTcpNoDelay(value: Boolean) = Unit
            override fun setKeepAlive(value: Boolean) = Unit
            override fun setReceiveBufferSize(value: Int) = Unit
            override fun setSendBufferSize(value: Int) = Unit
        }
    }

    @Test fun interruptedWholeObjectIsNeverAppendedWithAnAutomaticRetry() {
        val bytes = ByteArray(4096) { 7 }
        val rig = Rig { op, id, _ -> if (op == 0x1009) packet(9, u32(id) + u64(8192)) + packet(10, u32(id) + bytes) else null }
        rig.client.connect()
        val output = ByteArrayOutputStream()
        assertThrows(Exception::class.java) { rig.client.downloadTo(asset(8192), output, { _, _ -> }, { false }) }
        assertEquals(4096, output.size())
        assertEquals(1, rig.calls.count { it.first == 0x1009 })
        assertFalse(rig.calls.any { it.first == 0x101B })
        assertEquals(1, rig.commandConnections)
        rig.client.close()
    }

    @Test fun explicitUnsupportedWholeObjectCanFallBackBeforeWriting() {
        val bytes = ByteArray(32) { it.toByte() }
        val rig = Rig { op, id, _ -> when (op) { 0x1009 -> response(id, 0x2005); 0x101B -> data(id, bytes); else -> null } }
        rig.client.connect()
        val output = ByteArrayOutputStream()
        assertEquals(32, rig.client.downloadTo(asset(32), output, { _, _ -> }, { false }))
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(1, rig.commandConnections)
        rig.client.close()
    }

    @Test fun failedChunksAreDiscardedAndShortSuccessfulChunksAdvanceByActualBytes() {
        val size = 17L * 1024 * 1024 + 13
        var failed = false
        var shortened = false
        val rig = Rig { op, id, params -> if (op != 0x101B) null else {
            val offset = params[1]; val count = params[2].toInt()
            if (!failed) {
                failed = true
                packet(9, u32(id) + u64(count.toLong())) + packet(10, u32(id) + ByteArray(4096) { 99 })
            } else {
                val actual = if (!shortened) { shortened = true; count / 2 } else count
                data(id, ByteArray(actual) { ((offset + it) % 251).toByte() })
            }
        } }
        rig.client.connect()
        var committed = 0L
        val destination = object : OutputStream() {
            override fun write(value: Int) { assertEquals((committed % 251).toInt(), value and 255); committed++ }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                for (i in offset until offset + length) {
                    if (bytes[i] != (committed % 251).toByte()) throw AssertionError("Corrupted byte at $committed")
                    committed++
                }
            }
        }
        assertEquals(size, rig.client.downloadTo(asset(size), destination, { _, _ -> }, { false }))
        assertEquals(size, committed)
        val requests = rig.calls.filter { it.first == 0x101B }.map { it.second }
        assertEquals(0, requests[0][1]); assertEquals(0, requests[1][1])
        assertEquals(requests[1][2] / 2, requests[2][1])
        assertEquals(2, rig.commandConnections)
        rig.client.close()
    }

    @Test fun truncatedAndMismatchedTransactionsCannotReportSuccess() {
        for (wrongId in listOf(false, true)) {
            val rig = Rig { op, id, _ -> if (op == 0x1009) {
                if (wrongId) data(id + 1, byteArrayOf(1, 2)) else data(id, byteArrayOf(1, 2), declared = 4)
            } else null }
            rig.client.connect()
            assertThrows(IllegalStateException::class.java) { rig.client.download(asset(4)) { _, _ -> } }
            assertEquals(1, rig.calls.count { it.first == 0x1009 })
            rig.client.close()
        }
    }

    @Test fun storageWriteFailuresDoNotRetryTheChunk() {
        val rig = Rig { op, id, params -> if (op == 0x101B) data(id, ByteArray(params[2].toInt())) else null }
        rig.client.connect()
        val brokenOutput = object : OutputStream() { override fun write(value: Int): Unit = throw IOException("disk full") }
        assertThrows(IOException::class.java) { rig.client.downloadTo(asset(17L * 1024 * 1024), brokenOutput, { _, _ -> }, { false }) }
        assertEquals(1, rig.calls.count { it.first == 0x101B })
        assertEquals(1, rig.commandConnections)
        rig.client.close()
    }

    @Test fun cancellationDrainsCurrentTransactionAndPreservesCameraSession() {
        val rig = Rig { op, id, _ -> if (op == 0x1009) data(id, byteArrayOf(1, 2, 3, 4)) else null }
        rig.client.connect()
        val output = ByteArrayOutputStream()
        assertThrows(DownloadCancelledException::class.java) { rig.client.downloadTo(asset(4), output, { _, _ -> }, { true }) }
        assertFalse(rig.calls.any { it.first == 0x1009 })
        var cancelled = false
        assertThrows(DownloadCancelledException::class.java) { rig.client.downloadTo(asset(4), output, { _, _ -> cancelled = true }, { cancelled }) }
        assertEquals(0, output.size())
        assertTrue(rig.client.checkConnection())
        assertEquals(1, rig.commandConnections)
        assertThrows(IllegalArgumentException::class.java) { rig.client.downloadTo(asset(0x1_0000_0000), output, { _, _ -> }, { false }) }
        rig.client.close()
    }

    private fun objectInfo(name: String, format: Int) = u32(1) + u16(format) + u16(0) + u32(4) + u16(0) +
        u32(0) + u32(0) + u32(0) + u32(6000) + u32(4000) + u32(24) + u32(0) + u16(0) + u32(0) + u32(0) + string(name) + string("20261015T120000")

    @Test fun gallerySkipsFoldersAndDeletedHandlesWithoutRepeatingPagesOrMetadataReads() {
        val rig = Rig { op, id, params -> when (op) {
            0x1007 -> data(id, u32(4) + u32(1) + u32(2) + u32(3) + u32(4))
            0x1008 -> when (params.single()) {
                4L -> data(id, objectInfo("NEW.JPG", 0x3801))
                3L -> data(id, objectInfo("DCIM", 0x3001))
                2L -> response(id, 0x2009)
                else -> data(id, objectInfo("OLD.NEF", 0x3000))
            }
            else -> null
        } }
        rig.client.connect()
        assertEquals(listOf("NEW.JPG"), rig.client.assets(0, 1).map { it.name })
        assertTrue(rig.client.hasMoreAssets(1))
        assertEquals(listOf("OLD.NEF"), rig.client.assets(1, 1).map { it.name })
        assertFalse(rig.client.hasMoreAssets(2))
        assertEquals("NEW.JPG", rig.client.assets(0, 1).single().name)
        assertEquals(4, rig.calls.count { it.first == 0x1008 })
        rig.client.refreshAssets(); rig.client.assets(0, 1)
        assertEquals(5, rig.calls.count { it.first == 0x1008 })
        rig.client.close()
    }

    @Test fun dualCardsAreEnumeratedSeparatelyAndSameFilenamesRemainDistinct() {
        val rig = Rig(storageSupported = true) { op, id, params -> when (op) {
            0x1004 -> data(id, u32(2) + u32(0x10001) + u32(0x20001))
            0x1007 -> data(id, when (params.first()) {
                0x10001L -> u32(1) + u32(11)
                0x20001L -> u32(1) + u32(22)
                else -> u32(1) + u32(11) // Simulate the old all-storages request exposing only card 1.
            })
            0x1008 -> data(id, objectInfo("DSC0001.JPG", 0x3801))
            0x1009 -> data(id, ByteArray(4) { params.single().toByte() })
            else -> null
        } }
        rig.client.connect()
        val first = rig.client.assets(0, 1).single()
        assertTrue(rig.client.hasMoreAssets(1))
        val second = rig.client.assets(1, 1).single()
        assertEquals(listOf(22u, 11u), listOf(first.handle, second.handle))
        assertEquals(first.name, second.name)
        assertArrayEquals(ByteArray(4) { 22 }, rig.client.download(first) { _, _ -> })
        assertArrayEquals(ByteArray(4) { 11 }, rig.client.download(second) { _, _ -> })
        assertFalse(rig.client.hasMoreAssets(2))
        assertEquals(listOf(0x10001L, 0x20001L), rig.calls.filter { it.first == 0x1007 }.map { it.second.first() })
        rig.client.close()
    }

    @Test fun unsupportedStorageEnumerationFallsBackAndRemovedCardDoesNotHideRemainingCard() {
        for (rejection in listOf(0x2005, 0x2002, 0x200F, null)) {
            val unsupported = rejection != null
            val rig = Rig(storageSupported = true) { op, id, params -> when (op) {
                0x1004 -> if (rejection != null) response(id, rejection) else data(id, u32(2) + u32(0x10001) + u32(0x20001))
                0x1007 -> if (!unsupported && params.first() == 0x10001L) response(id, 0x2013) else data(id, u32(1) + u32(22))
                0x1008 -> data(id, objectInfo("CARD2.NEF", 0x3000))
                else -> null
            } }
            rig.client.connect()
            assertEquals("CARD2.NEF", rig.client.assets(0, 5).single().name)
            assertFalse(rig.client.hasMoreAssets(1))
            if (unsupported) assertEquals(0xffffffffL, rig.calls.single { it.first == 0x1007 }.second.first())
            rig.client.close()
        }
    }

    @Test fun capturePollingFindsANewPhotoOnTheSecondCard() {
        var captured = false
        val rig = Rig(storageSupported = true, captureSupported = true) { op, id, params -> when (op) {
            0x1004 -> data(id, u32(2) + u32(0x10001) + u32(0x20001))
            0x1007 -> data(id, when {
                params.first() == 0x10001L -> u32(1) + u32(11)
                captured -> u32(2) + u32(22) + u32(23)
                else -> u32(1) + u32(22)
            })
            0x100E -> { captured = true; response(id) }
            else -> null
        } }
        rig.client.connect()
        assertTrue(rig.client.capture({ false }, {}).confirmed)
        assertEquals(1, rig.calls.count { it.first == 0x100E })
        assertEquals(2, rig.calls.count { it.first == 0x1007 && it.second.first() == 0x20001L })
        rig.client.close()
    }

    private fun events(operation: Int, vararg records: Pair<Int, List<Long>>): ByteArray =
        (if (operation == 0x941C) u32(records.size.toLong()) else u16(records.size)) +
            records.fold(byteArrayOf()) { bytes, (code, params) ->
                bytes + u16(code) + (if (operation == 0x941C) u16(params.size) else byteArrayOf()) +
                    params.fold(byteArrayOf()) { result, parameter -> result + u32(parameter) }
            }

    @Test fun vendorCaptureEventsConfirmWithoutReadingEitherCardAndIgnoreDrainedOldEvents() {
        for (eventOperation in listOf(0x941C, 0x90C7)) for (eventCode in listOf(0x400D, 0x4002)) {
            var captured = false
            val rig = Rig(storageSupported = true, captureSupported = true, eventOperations = listOf(eventOperation)) { op, id, _ -> when (op) {
                eventOperation -> data(id, if (captured) events(op, eventCode to listOf(23L)) else events(op, 0x400D to listOf(0L), 0x4002 to listOf(11L)))
                0x100E -> { captured = true; response(id) }
                0x1004, 0x1007 -> throw AssertionError("Vendor confirmation must not enumerate either card")
                else -> null
            } }
            rig.client.connect()
            assertTrue(rig.client.capture({ false }, {}).confirmed)
            assertEquals(1, rig.calls.count { it.first == 0x100E })
            assertEquals(2, rig.calls.count { it.first == eventOperation })
            assertTrue(rig.traces.any { "source=event" in it })
            rig.client.close()
        }
    }

    @Test fun eventExRejectionFallsBackToLegacyEventsWithoutRepeatingTheShutter() {
        var captured = false
        val rig = Rig(captureSupported = true, eventOperations = listOf(0x941C, 0x90C7)) { op, id, _ -> when (op) {
            0x941C -> response(id, 0x2005)
            0x90C7 -> data(id, if (captured) events(op, 0x400D to listOf(0L)) else events(op))
            0x100E -> { captured = true; response(id) }
            0x1007 -> throw AssertionError("Legacy vendor events do not need card enumeration")
            else -> null
        } }
        rig.client.connect()
        assertTrue(rig.client.capture({ false }, {}).confirmed)
        assertEquals(1, rig.calls.count { it.first == 0x941C })
        assertEquals(1, rig.calls.count { it.first == 0x100E })
        rig.client.close()
    }

    @Test fun staleObjectEventsAndTemporaryBusyCannotConfirmOrResendTheShutter() {
        var captured = false
        var postCapturePolls = 0
        val rig = Rig(captureSupported = true, eventOperations = listOf(0x941C)) { op, id, _ -> when (op) {
            0x1007 -> data(id, u32(1) + u32(11))
            0x1008 -> data(id, objectInfo("OLD.JPG", 0x3801))
            0x941C -> if (!captured) data(id, events(op)) else when (++postCapturePolls) {
                1 -> response(id, 0x2019)
                2 -> data(id, events(op, 0x4002 to listOf(0L), 0x4002 to listOf(11L, 0x10001L)))
                else -> data(id, events(op, 0x4002 to listOf(23L, 0x20001L)))
            }
            0x100E -> { captured = true; response(id) }
            else -> null
        } }
        rig.client.connect(); rig.client.assets(0, 1)
        assertTrue(rig.client.capture({ false }, {}).confirmed)
        assertEquals(3, postCapturePolls)
        assertEquals(1, rig.calls.count { it.first == 0x100E })
        assertEquals(1, rig.calls.count { it.first == 0x1007 })
        rig.client.close()
    }

    @Test fun malformedPostShutterEventsRemainUnconfirmedAndNeverResendTheShutter() {
        var captured = false
        val rig = Rig(captureSupported = true, eventOperations = listOf(0x941C)) { op, id, _ -> when (op) {
            0x941C -> data(id, if (captured) u32(1) else events(op))
            0x100E -> { captured = true; response(id) }
            else -> null
        } }
        rig.client.connect()
        assertFalse(rig.client.capture({ false }, {}).confirmed)
        assertEquals(1, rig.calls.count { it.first == 0x100E })
        rig.client.close()
    }
}

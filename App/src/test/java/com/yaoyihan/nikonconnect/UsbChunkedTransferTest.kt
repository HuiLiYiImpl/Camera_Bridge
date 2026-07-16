package com.yaoyihan.nikonconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class UsbChunkedTransferTest {
    @Test fun copiesInChunksAndStopsBeforeTheNextReadWhenCancelled() {
        val output = ByteArrayOutputStream()
        var reads = 0

        assertThrows(DownloadCancelledException::class.java) {
            copyMtpObjectInChunks(
                total = 3L * 1024 * 1024,
                output = output,
                progress = { _, _ -> },
                isCancelled = { reads == 1 },
            ) { _, buffer, request ->
                buffer.fill(7, 0, request)
                reads++
                request
            }
        }

        assertEquals(1, reads)
        assertEquals(1024 * 1024, output.size())
    }
}

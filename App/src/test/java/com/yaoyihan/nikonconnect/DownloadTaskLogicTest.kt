package com.yaoyihan.nikonconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class DownloadTaskLogicTest {
    private val asset = PhotoAsset(7u, "DSC_1234.JPG", 75L * 1024 * 1024, 0x3801)

    @Test
    fun stableSourceKeyDeduplicatesQueuedDownloadingAndFailedTasks() {
        val session = CameraSession("Z 8", "192.168.1.1", 15740)
        val key = asset.downloadSourceKey(session)
        val tasks = listOf(
            DownloadTask(key, asset),
            DownloadTask(key, asset, status = DownloadTaskStatus.DOWNLOADING),
            DownloadTask(key, asset, status = DownloadTaskStatus.FAILED),
        )
        assertEquals(1, tasks.map { it.id }.distinct().size)
        assertEquals(key, asset.downloadSourceKey(session))
        assertEquals(true, tasks.first().belongsTo(session))
        assertEquals(false, tasks.first().belongsTo(CameraSession("Z 9", "USB", 0, ConnectionTransport.USB)))
    }

    @Test
    fun progressUsesAssetSizeAndStaysBounded() {
        assertEquals(100L, downloadProgress(200, 0, 100).first)
        assertEquals(0f, downloadProgress(-10, 100, 50).second)
        assertEquals(1f, downloadProgress(200, 100, 50).second)
        assertEquals(12_345L, normalizedDownloadBytes(12_345, 0))
        assertEquals(0L, (100L - 200).coerceAtLeast(0))
    }

    @Test
    fun insufficientSpeedShowsCalculatingAndNormalSpeedFormatsTime() {
        assertEquals("计算中", formatRemainingTime(null))
        assertEquals("00:18", formatRemainingTime(18))
        assertEquals("1:01:02", formatRemainingTime(3662))
        assertEquals("00:00", formatRemainingTime(0))
    }

    @Test
    fun mediaKindUsesVideoMimeAndCaseInsensitiveFallbacks() {
        assertEquals(MediaKind.VIDEO, mediaKindFor("video/mp4", "photo.JPG"))
        assertEquals(MediaKind.VIDEO, mediaKindFor(null, "clip.MP4"))
        assertEquals(MediaKind.VIDEO, mediaKindFor(null, "clip.MOV"))
        assertEquals(MediaKind.VIDEO, mediaKindFor(null, "clip.m4v"))
        assertEquals(MediaKind.VIDEO, mediaKindFor(null, "clip.3GP"))
        assertEquals(MediaKind.RAW_IMAGE, mediaKindFor(null, "photo.NEF"))
        assertEquals(MediaKind.IMAGE, mediaKindFor("image/jpeg", "photo.jpg"))
    }

    @Test
    fun cancellingIsAnIntermediateTaskStateAndKeepsTheSameId() {
        val cancelling = DownloadTask("task-1", asset, status = DownloadTaskStatus.CANCELLING)
        assertEquals("task-1", cancelling.id)
        assertEquals(DownloadTaskStatus.CANCELLING, cancelling.status)
    }

    @Test
    fun onlyUserCancellationRemovesTheDownloadTask() {
        assertTrue(isUserDownloadCancellation(DownloadCancelledException(), false))
        assertTrue(isUserDownloadCancellation(IllegalStateException("USB disconnected"), true))
        assertFalse(isUserDownloadCancellation(CancellationException("process stopped"), false))
    }

    @Test
    fun completionUsesSavedFileSizeAndKeepsSourceIdentityForFeedback() {
        val session = CameraSession("Z 8", "192.168.1.1", 15740)
        val task = DownloadTask(asset.downloadSourceKey(session), asset, downloadedBytes = 400,
            totalBytes = 0, bytesPerSecond = 200, remainingSeconds = 5,
            status = DownloadTaskStatus.DOWNLOADING)
        val saved = DownloadRecord(asset.name, 512, "content://media/external/images/media/123", 1)
        val completed = task.completed(saved)
        assertEquals(task.id, completed.id)
        assertEquals(task.asset, completed.asset)
        assertTrue(completed.belongsTo(session))
        assertEquals(DownloadTaskStatus.COMPLETED, completed.status)
        assertEquals(saved.uri, completed.completedUri)
        assertEquals(512L, completed.downloadedBytes)
        assertEquals(1f, downloadProgress(completed.downloadedBytes, completed.totalBytes, asset.size).second)
        assertEquals(null, completed.bytesPerSecond)
        assertEquals(0L, completed.remainingSeconds)
    }
}

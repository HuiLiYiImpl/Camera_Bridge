package com.yaoyihan.nikonconnect

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThumbnailDiskCacheTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun persistsAcrossCacheRecreation() {
        val root = folder.newFolder()
        val cache = ThumbnailDiskCache(root)
        cache.write("camera/photo", byteArrayOf(1, 2, 3), cache.generation)
        assertArrayEquals(byteArrayOf(1, 2, 3), ThumbnailDiskCache(root).read("camera/photo"))
        assertEquals(3L, cache.sizeBytes())
    }
    @Test fun cameraAndPhotoKeysRemainSeparateAndSafe() {
        val root = folder.newFolder()
        val cache = ThumbnailDiskCache(root)
        cache.write("../camera-a/photo", byteArrayOf(1), cache.generation)
        cache.write("../camera-b/photo", byteArrayOf(2), cache.generation)
        assertArrayEquals(byteArrayOf(1), cache.read("../camera-a/photo"))
        assertArrayEquals(byteArrayOf(2), cache.read("../camera-b/photo"))
        assertEquals(2, root.listFiles()!!.size)
    }
    @Test fun replacementDoesNotDoubleCountSpace() {
        val cache = ThumbnailDiskCache(folder.newFolder())
        cache.write("photo", byteArrayOf(1), cache.generation)
        cache.write("photo", byteArrayOf(2, 3), cache.generation)
        assertArrayEquals(byteArrayOf(2, 3), cache.read("photo"))
        assertEquals(2L, cache.sizeBytes())
    }
    @Test fun limitEvictsOldestAndCountsRemainingFiles() {
        val root = folder.newFolder()
        val cache = ThumbnailDiskCache(root, maxBytes = 3)
        cache.write("old", byteArrayOf(1, 2), cache.generation)
        root.listFiles()!!.single().setLastModified(1)
        cache.write("new", byteArrayOf(3, 4), cache.generation)
        assertNull(cache.read("old"))
        assertArrayEquals(byteArrayOf(3, 4), cache.read("new"))
        assertEquals(2L, cache.sizeBytes())
    }
    @Test fun clearRejectsInflightWriters() {
        val cache = ThumbnailDiskCache(folder.newFolder())
        val oldGeneration = cache.generation
        cache.write("photo", byteArrayOf(1), oldGeneration)
        cache.invalidate()
        cache.clear()
        cache.write("late-photo", byteArrayOf(2), oldGeneration)
        assertNull(cache.read("photo"))
        assertNull(cache.read("late-photo"))
        assertEquals(0L, cache.sizeBytes())
        cache.write("fresh", byteArrayOf(3), cache.generation)
        assertArrayEquals(byteArrayOf(3), cache.read("fresh"))
    }
    @Test fun oversizedEntryIsNotStored() {
        val cache = ThumbnailDiskCache(folder.newFolder(), maxBytes = 2)
        cache.write("photo", byteArrayOf(1, 2, 3), cache.generation)
        assertEquals(0L, cache.sizeBytes())
    }
}

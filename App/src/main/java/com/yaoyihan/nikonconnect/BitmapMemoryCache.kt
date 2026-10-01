package com.yaoyihan.nikonconnect

import android.graphics.Bitmap
import android.util.LruCache

internal class BitmapMemoryCache<K : Any>(onEvicted: (K) -> Unit = {}) {
    private val budget = (Runtime.getRuntime().maxMemory() / 16)
        .coerceIn(4L * 1024 * 1024, 24L * 1024 * 1024).toInt()
    private val cache = object : LruCache<K, Bitmap>(budget) {
        override fun sizeOf(key: K, value: Bitmap) = value.allocationByteCount
        override fun entryRemoved(evicted: Boolean, key: K, oldValue: Bitmap, newValue: Bitmap?) {
            if (newValue == null) onEvicted(key)
            // Visible cards can still hold this bitmap after cache eviction.
        }
    }

    fun get(key: K): Bitmap? = cache.get(key)
    fun put(key: K, bitmap: Bitmap) { cache.put(key, bitmap) }
    fun remove(key: K) { cache.remove(key) }
    fun clear() = cache.evictAll()
}

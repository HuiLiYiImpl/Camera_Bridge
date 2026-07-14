package com.yaoyihan.nikonconnect.lut

import android.util.LruCache
import com.yaoyihan.nikonconnect.CubeLut

class LutCache(maxEntries: Int = 8) {
    private val cache = object : LruCache<String, CubeLut>(maxEntries) {}
    @Synchronized fun get(id: String): CubeLut? = cache.get(id)
    @Synchronized fun put(id: String, lut: CubeLut) { cache.put(id, lut) }
    @Synchronized fun clear() = cache.evictAll()
}

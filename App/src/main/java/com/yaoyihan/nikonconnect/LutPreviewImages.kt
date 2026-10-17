package com.yaoyihan.nikonconnect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream

internal data class LutCover(val original: Bitmap?, val graded: Bitmap?, val error: String? = null)

/** Small, local previews. Full-size user photos and URI permissions are never retained. */
internal object LutPreviewImages {
    private const val EffectIntensity = .5f
    private val lock = Mutex()
    private fun coverCache(bytes: Int) = object : LruCache<String, LutCover>(bytes) {
        override fun sizeOf(key: String, value: LutCover): Int =
            (value.original?.allocationByteCount ?: 0) + (value.graded?.allocationByteCount ?: 0)
    }
    private val cache = coverCache(12 * 1024 * 1024)
    private val detailCache = coverCache(6 * 1024 * 1024)
    private var defaultSource: Bitmap? = null
    private val defaultSizes = mutableMapOf<Int, Bitmap>()

    private fun key(context: Context, entry: LutEntry, maxEdge: Int, includeEffect: Boolean) =
        "${context.filesDir.absolutePath}|v3:${entry.id}:${entry.coverKey}:$maxEdge:${if (includeEffect) "effect50" else "original"}"

    fun cached(context: Context, entry: LutEntry, maxEdge: Int, includeEffect: Boolean): LutCover? {
        val images = if (maxEdge > 384) detailCache else cache
        return images.get(key(context, entry, maxEdge, includeEffect))
            ?: if (!includeEffect) images.get(key(context, entry, maxEdge, true)) else null
    }

    suspend fun load(context: Context, entry: LutEntry, maxEdge: Int = 768, includeEffect: Boolean = true, readLut: suspend () -> CubeLut): LutCover =
        withContext(Dispatchers.IO) {
            val key = key(context, entry, maxEdge, includeEffect)
            val targetCache = if (maxEdge > 384) detailCache else cache
            cached(context, entry, maxEdge, includeEffect)?.let { return@withContext it }
            lock.withLock {
                cached(context, entry, maxEdge, includeEffect)?.let { return@withLock it }
                try {
                    val original = cached(context, entry, maxEdge, false)?.original ?: if (entry.coverKey.isNotEmpty()) {
                        decodePreview(file(context, entry.coverKey, false), maxEdge)
                    } else defaultImage(context, maxEdge)
                    if (!includeEffect) {
                        val result = LutCover(original, null)
                        targetCache.put(key, result)
                        currentCoroutineContext().ensureActive()
                        return@withLock result
                    }
                    val disk = ThumbnailDiskCache(context.cacheDir.resolve("lut_preview_thumbnails"), 32L * 1024 * 1024)
                    val graded = disk.read(key)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: run {
                        val savedEffect = entry.coverKey.takeIf { it.isNotEmpty() }?.let { file(context, it, true) }
                        val rendered = if (savedEffect?.isFile == true) decodePreview(savedEffect, maxEdge)
                            else CubeLuts.apply(original, readLut(), EffectIntensity)
                        runCatching {
                            val bytes = ByteArrayOutputStream().use { output ->
                                check(rendered.compress(Bitmap.CompressFormat.JPEG, 92, output)); output.toByteArray()
                            }
                            disk.write(key, bytes, disk.generation)
                        }
                        rendered
                    }
                    val result = LutCover(original, graded)
                    // Keep completed work even when its list item scrolls offscreen before delivery.
                    targetCache.put(key, result)
                    currentCoroutineContext().ensureActive()
                    result
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { LutCover(cached(context, entry, maxEdge, false)?.original, null, error.message ?: "预览暂不可用") }
            }
        }

    suspend fun import(context: Context, uri: Uri, lut: CubeLut, key: String) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        require(resolver.getType(uri)?.startsWith("image/") != false) { "请选择图片文件" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片格式无法识别" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1536) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: error("无法读取图片")
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val oriented = OrientedBitmaps.orient(decoded, orientation)
        val scale = (768f / maxOf(oriented.width, oriented.height)).coerceAtMost(1f)
        val original = if (scale < 1f) Bitmap.createScaledBitmap(oriented,
            (oriented.width * scale).toInt().coerceAtLeast(1), (oriented.height * scale).toInt().coerceAtLeast(1), true)
            .also { oriented.recycle() } else oriented
        var graded: Bitmap? = null
        try {
            currentCoroutineContext().ensureActive()
            graded = CubeLuts.apply(original, lut, EffectIntensity)
            file(context, key, false).outputStream().use { check(original.compress(Bitmap.CompressFormat.JPEG, 92, it)) { "预览图保存失败" } }
            file(context, key, true).outputStream().use { check(graded.compress(Bitmap.CompressFormat.JPEG, 92, it)) { "效果图保存失败" } }
            currentCoroutineContext().ensureActive()
        } finally { original.recycle(); graded?.recycle() }
    }

    fun remove(context: Context, key: String) {
        if (!key.matches(Regex("[a-f0-9-]{36}"))) return
        file(context, key, false).delete()
        file(context, key, true).delete()
        context.filesDir.resolve("lut_previews").resolve("$key-graded.jpg").delete()
        listOf(cache, detailCache).forEach { images ->
            images.snapshot().keys.filter { it.contains(":$key:") }.forEach { images.remove(it) }
        }
        // Cache eviction releases references; displayed bitmaps must not be recycled here.
    }

    private fun file(context: Context, key: String, graded: Boolean): File {
        require(key.matches(Regex("[a-f0-9-]{36}"))) { "预览图标识无效" }
        return context.filesDir.resolve("lut_previews").apply { mkdirs() }
            .resolve("$key${if (graded) "-graded50" else ""}.jpg")
    }

    private fun decodePreview(file: File, maxEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("预览图无法读取，请重新设置")
        return resized(bitmap, maxEdge).also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun resized(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val factor = (maxEdge.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
        return if (factor == 1f) bitmap else Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * factor).toInt().coerceAtLeast(1), (bitmap.height * factor).toInt().coerceAtLeast(1), true)
    }

    private fun defaultImage(context: Context, maxEdge: Int): Bitmap {
        defaultSizes[maxEdge]?.let { return it }
        defaultSource?.let { source -> return resized(source, maxEdge).also { defaultSizes[maxEdge] = it } }
        // Reuse the photographic region of an existing bundled sample, without its poster text.
        val source = context.assets.open("ai_skills/photo-to-zine-postcard/assets/forest-homestead.png").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
        } ?: error("示例图无法读取")
        val photo = Bitmap.createBitmap(source, (source.width * .066f).toInt(), (source.height * .055f).toInt(),
            (source.width * .868f).toInt(), (source.height * .43f).toInt()).also { if (it !== source) source.recycle() }
        defaultSource = photo
        return resized(photo, maxEdge).also { defaultSizes[maxEdge] = it }
    }
}

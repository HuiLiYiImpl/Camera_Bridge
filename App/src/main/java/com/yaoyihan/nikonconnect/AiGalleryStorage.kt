package com.yaoyihan.nikonconnect

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

internal fun aiSaveToGallery(context: Context, sourceName: String, styleName: String, bitmap: Bitmap, taskId: String): DownloadRecord {
    val resolver = context.contentResolver
    val stem = sourceName.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80)
    val name = "${stem}_AI_${styleName}_${taskId.take(8)}.png"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Link")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        } else {
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Link")
            check(folder.isDirectory || folder.mkdirs()) { "无法创建相册目录" }
            put(MediaStore.Images.Media.DATA, File(folder, name).absolutePath)
        }
    }
    val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
    try {
        resolver.openOutputStream(uri)?.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            ?: error("无法写入相册")
        val now = System.currentTimeMillis()
        val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        values.clear()
        values.put(MediaStore.Images.Media.DATE_TAKEN, now)
        if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return DownloadRecord(name, size, uri.toString(), now).also { AppStore(context).addDownload(it) }
    } catch (error: Exception) {
        resolver.delete(uri, null, null)
        throw error
    }
}

package com.yaoyihan.nikonconnect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

data class DownloadedPhotoDetails(
    val metadata: PhotoMetadata,
    val width: Int?,
    val height: Int?,
    val mimeType: String?,
)

object DownloadedPhotoDetailsReader {
    fun hasLocationAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun read(context: Context, record: DownloadRecord): DownloadedPhotoDetails? {
        val uri = Uri.parse(record.uri)
        val resolver = context.contentResolver
        if (runCatching { resolver.openFileDescriptor(uri, "r")?.use { true } }.getOrNull() != true) return null
        val originalUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            hasLocationAccess(context) && uri.authority == MediaStore.AUTHORITY
        ) MediaStore.setRequireOriginal(uri) else uri
        val metadata = (ExifMetadataReader.fromUriOrNull(context, originalUri)
            ?: if (originalUri != uri) ExifMetadataReader.fromUriOrNull(context, uri) else null)
            ?: PhotoMetadata()

        var width = metadata.pixelWidth
        var height = metadata.pixelHeight
        if (width == null || height == null) {
            runCatching {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            width = width ?: cursor.getInt(0).takeIf { it > 0 }
                            height = height ?: cursor.getInt(1).takeIf { it > 0 }
                        }
                    }
            }
        }
        if (width == null || height == null) {
            runCatching {
                resolver.openInputStream(uri)?.use { stream ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, bounds)
                    width = width ?: bounds.outWidth.takeIf { it > 0 }
                    height = height ?: bounds.outHeight.takeIf { it > 0 }
                }
            }
        }
        return DownloadedPhotoDetails(metadata, width, height, resolver.getType(uri))
    }
}

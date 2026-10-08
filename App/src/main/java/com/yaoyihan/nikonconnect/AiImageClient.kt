package com.yaoyihan.nikonconnect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class AiImageClient(private val context: Context) {
    suspend fun generate(config: AiImageConfig, source: Bitmap, rotation: Int, prompt: String): AiImageReference = withContext(Dispatchers.IO) {
        config.validate()
        val network = internetNetwork()
        val image = prepareImage(source, rotation)
        currentCoroutineContext().ensureActive()
        val request = AiImageProtocol.request(config, "data:image/jpeg;base64,${Base64.encodeToString(image, Base64.NO_WRAP)}", prompt)
            .toString().toByteArray(Charsets.UTF_8)
        val response = request(network, config.endpoint, request, config.key, MAX_IMAGE_BYTES)
        AiImageProtocol.response(config.provider, JSONObject(String(response, Charsets.UTF_8)))
    }

    suspend fun fetch(reference: AiImageReference): Bitmap = withContext(Dispatchers.IO) {
        val bytes = when (reference) {
            is AiImageReference.Encoded -> {
                if (reference.base64.length > MAX_IMAGE_BYTES * 4L / 3 + 4) throw AiImageException("生成图片超出大小限制")
                Base64.decode(reference.base64, Base64.DEFAULT)
            }
            is AiImageReference.Remote -> download(internetNetwork(), reference.url)
        }
        currentCoroutineContext().ensureActive()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw AiImageException("返回内容不是可解码的图片")
        val options = BitmapFactory.Options().apply { inSampleSize = aiImageSampleSize(bounds.outWidth, bounds.outHeight) }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw AiImageException("无法解码生成图片")
    }

    private fun internetNetwork(): Network {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        } ?: throw AiImageException("AI 生成需要互联网；连接相机热点时请同时开启移动数据")
    }

    private fun prepareImage(source: Bitmap, rotation: Int): ByteArray {
        val factor = minOf(1f, 2048f / maxOf(source.width, source.height))
        val flat = Bitmap.createBitmap((source.width * factor).roundToInt().coerceAtLeast(1),
            (source.height * factor).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            Canvas(flat).apply {
                drawColor(Color.WHITE)
                drawBitmap(source, null, android.graphics.Rect(0, 0, flat.width, flat.height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
            val oriented = OrientedBitmaps.rotate(flat, rotation)
            try {
                return ByteArrayOutputStream().use { output ->
                    if (!oriented.compress(Bitmap.CompressFormat.JPEG, 90, output)) throw AiImageException("照片编码失败")
                    if (output.size() > 8 * 1024 * 1024) throw AiImageException("上传图片过大，请选择较小图片")
                    output.toByteArray()
                }
            } finally { if (oriented !== flat) oriented.recycle() }
        } finally { flat.recycle() }
    }

    private suspend fun download(network: Network, startUrl: String): ByteArray {
        var url = startUrl
        repeat(4) {
            try { return request(network, url, null, null, MAX_IMAGE_BYTES) }
            catch (redirect: ImageRedirect) {
                val next = URL(URL(url), redirect.location).toString()
                if (!aiHttpsUrl(next)) throw AiImageException("图片下载跳转到了不安全的地址")
                url = next
            }
        }
        throw AiImageException("图片下载跳转次数过多")
    }

    // Bind only this request to the Internet network; camera sockets keep their own network.
    private suspend fun request(network: Network, url: String, body: ByteArray?, key: String?, limit: Int): ByteArray =
        suspendCancellableCoroutine { continuation ->
            var connection: HttpURLConnection? = null
            try {
                if (!aiHttpsUrl(url)) throw AiImageException("请求地址不是有效 HTTPS 链接")
                connection = network.openConnection(URL(url)) as HttpURLConnection
                val active = connection
                continuation.invokeOnCancellation { active.disconnect() }
                active.connectTimeout = 15_000
                active.readTimeout = if (body == null) 60_000 else 180_000
                active.instanceFollowRedirects = false
                active.useCaches = false
                if (body != null) {
                    active.requestMethod = "POST"
                    active.doOutput = true
                    active.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    active.setRequestProperty("Authorization", "Bearer $key")
                    active.setFixedLengthStreamingMode(body.size)
                    active.outputStream.use { stream ->
                        var offset = 0
                        while (offset < body.size) {
                            if (!continuation.isActive) return@use
                            val count = minOf(16 * 1024, body.size - offset)
                            stream.write(body, offset, count)
                            offset += count
                        }
                    }
                }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                val status = active.responseCode
                if (status in 300..399 && body == null) throw ImageRedirect(active.getHeaderField("Location") ?: "")
                if (status !in 200..299) {
                    val code = runCatching {
                        active.errorStream?.use { JSONObject(String(readBounded(it, 64 * 1024) { continuation.isActive }, Charsets.UTF_8)) }
                            ?.let { it.optJSONObject("error")?.optString("code") ?: it.optString("code") }.orEmpty()
                    }.getOrDefault("")
                    throw AiImageException(aiImageError(status, code))
                }
                val bytes = active.inputStream.use { readBounded(it, limit) { continuation.isActive } }
                if (continuation.isActive) continuation.resume(bytes)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(when (error) {
                    is AiImageException, is ImageRedirect -> error
                    is SocketTimeoutException -> AiImageException(if (body == null) "图片下载超时，可重试下载" else "请求超时，服务商可能已受理并计费；重新生成前请先检查控制台")
                    else -> AiImageException(if (body == null) "生成图片下载失败，可重试下载" else "请求中断，服务商可能已受理；请检查网络和控制台后再重试")
                })
            } finally { connection?.disconnect() }
        }

    private fun readBounded(stream: InputStream, limit: Int, active: () -> Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (active()) {
            val count = stream.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size().toLong() + count > limit) throw AiImageException("服务商返回内容超出大小限制")
            output.write(buffer, 0, count)
        }
        throw kotlinx.coroutines.CancellationException()
    }

    private class ImageRedirect(val location: String) : Exception()
    companion object { private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024 }
}

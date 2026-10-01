package com.yaoyihan.nikonconnect

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal const val LINK_UPDATE_BASE = "https://link-1316787468.cos.ap-chengdu.myqcloud.com/"
internal const val LINK_DOWNLOAD_PAGE = "https://www.pgyer.com/link-android-k"

internal data class LinkRelease(
    val versionCode: Long,
    val versionName: String,
    val title: String,
    val notes: List<String>,
    val downloadPageUrl: String,
    val sizeBytes: Long,
)

internal data class LinkUpdateState(
    val checking: Boolean = false,
    val release: LinkRelease? = null,
    val dialogVisible: Boolean = false,
    val message: String? = null,
)

internal class LinkUpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(LinkUpdateState())
    val state = mutable.asStateFlow()
    private var checkJob: Job? = null

    fun check(silent: Boolean = false) {
        if (checkJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            mutable.update { it.copy(checking = true, message = null) }
            try {
                val release = withContext(Dispatchers.IO) {
                    val connection = connect("${LINK_UPDATE_BASE}version.json?t=${System.currentTimeMillis()}")
                    try {
                        require(connection.responseCode == 200) { "更新公告暂不可用，请确认 version.json 已上传并设为公有读" }
                        val text = connection.inputStream.use { stream ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while (true) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                require(output.size() + read <= 64 * 1024) { "更新公告格式异常" }
                                output.write(buffer, 0, read)
                            }
                            output.toString("UTF-8")
                        }
                        val json = JSONObject(text)
                        val code = json.getLong("versionCode")
                        require(code > 0) { "公告中的版本号无效" }
                        if (code <= BuildConfig.VERSION_CODE) null else {
                            // Older announcements have only apkUrl; use the stable download page.
                            val page = json.optString("downloadPageUrl", LINK_DOWNLOAD_PAGE)
                            trustedDownloadPage(page)
                            val notes = json.optJSONArray("notes")
                            LinkRelease(code, json.getString("versionName"), json.optString("title", "Link 更新"),
                                List(notes?.length()?.coerceAtMost(30) ?: 0) { notes!!.getString(it) },
                                page, json.optLong("sizeBytes", 0).coerceAtLeast(0))
                        }
                    } finally { connection.disconnect() }
                }
                mutable.update {
                    it.copy(checking = false, release = release, dialogVisible = release != null,
                        message = if (release == null && !silent) "当前已是最新版本" else null)
                }
            } catch (error: CancellationException) {
                mutable.update { it.copy(checking = false) }
                throw error
            } catch (error: Exception) {
                mutable.update { it.copy(checking = false, message = if (silent) null else (error.message ?: "检查更新失败，请重试")) }
            }
        }
    }

    fun showRelease() { mutable.update { it.copy(dialogVisible = it.release != null) } }
    fun dismiss() { mutable.update { it.copy(dialogVisible = false) } }

    fun openDownloadPage(context: Context) {
        try {
            val page = mutable.value.release?.downloadPageUrl ?: LINK_DOWNLOAD_PAGE
            trustedDownloadPage(page)
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            mutable.update { it.copy(dialogVisible = false, message = null) }
        } catch (_: Exception) {
            mutable.update { it.copy(message = "无法打开下载页面，请确认手机已安装浏览器") }
        }
    }

    private fun trustedDownloadPage(value: String) {
        val url = URL(value)
        require(url.protocol == "https" && url.host == "www.pgyer.com" &&
            url.port in listOf(-1, 443) && url.userInfo == null && url.path == "/link-android-k" &&
            url.query == null && url.ref == null) { "更新下载页面不是 Link 的蒲公英地址" }
    }

    private fun connect(value: String): HttpURLConnection {
        val url = URL(value)
        require(url.protocol == "https" && url.host == URL(LINK_UPDATE_BASE).host &&
            url.port in listOf(-1, 443) && url.userInfo == null) { "更新公告地址无效" }
        // Bypass a process binding to the camera hotspot when Internet is available.
        val manager = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val internet = manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
        return ((internet?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Accept-Encoding", "identity")
        }
    }
}
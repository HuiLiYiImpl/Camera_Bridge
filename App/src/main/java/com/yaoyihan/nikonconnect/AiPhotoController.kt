package com.yaoyihan.nikonconnect

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal data class AiPhotoTask(
    val id: String, val photoKey: String, val sourceName: String, val style: AiPhotoStyle,
    val active: Boolean = true, val message: String = "等待后台服务…",
    val failed: Boolean = false, val saved: DownloadRecord? = null,
)

internal class AiPhotoController private constructor(context: Context) {
    private val app = context.applicationContext
    private val store = AiImageStore(app)
    private val client = AiImageClient(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _catalog = MutableStateFlow(store.load())
    val catalog = _catalog.asStateFlow()
    private val _configBusy = MutableStateFlow(false)
    val configBusy = _configBusy.asStateFlow()
    private val configMutex = Mutex()
    private val _config = MutableStateFlow(_catalog.value.activeConfig())
    val config = _config.asStateFlow()
    private val _tasks = MutableStateFlow<List<AiPhotoTask>>(emptyList())
    val tasks = _tasks.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()
    private data class Submission(val source: Bitmap, val config: AiImageConfig, val prompt: String)
    private val pending = mutableMapOf<String, Submission>()
    private val jobs = mutableMapOf<String, Job>()

    suspend fun saveProfile(profile: AiProviderProfile) = updateCatalog { it.upsert(profile) }
    suspend fun deleteProfile(id: String) = updateCatalog { it.remove(id) }
    suspend fun selectModel(profileId: String, modelId: String) = updateCatalog { it.select(profileId, modelId) }
    suspend fun enableProfile(id: String, enabled: Boolean) = updateCatalog { current ->
        current.upsert(requireNotNull(current.profiles.find { it.id == id }).copy(enabled = enabled))
    }
    suspend fun clearKey(id: String) = updateCatalog { current ->
        current.upsert(requireNotNull(current.profiles.find { it.id == id }).copy(key = ""))
    }
    private suspend fun updateCatalog(update: (AiProviderCatalog) -> AiProviderCatalog) = configMutex.withLock {
        _configBusy.value = true
        try {
            val next = update(_catalog.value).reconcile()
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { store.save(next) }
                _catalog.value = next
                _config.value = next.activeConfig()
            }
        } finally { _configBusy.value = false }
    }

    // Called from the foreground UI; reserve a slot before starting the service.
    fun generate(photoKey: String, name: String, source: Bitmap, rotation: Int, style: AiPhotoStyle,
        title: String, postcardBack: Boolean = false): String? {
        aiSubmissionError(_tasks.value, photoKey)?.let { return it }
        if (_configBusy.value) return "配置正在保存，请稍后"
        val config = _catalog.value.activeConfig()
        val submission = try {
            require(config.profileId.isNotBlank()) { "请先选择默认供应商和模型" }
            config.validate()
            val rotated = rotation % 180 != 0
            val prompt = aiSkillGenerationPrompt(style, style.readPrompt(app), title,
                if (rotated) source.height else source.width, if (rotated) source.width else source.height, postcardBack)
            // The gallery may release its bitmap after the sheet closes.
            Submission(aiArchiveSource(source, rotation), config, prompt)
        } catch (error: Exception) { return (error as? IllegalArgumentException)?.message ?: "无法准备参考照片" }
        val id = UUID.randomUUID().toString()
        pending[id] = submission
        _tasks.value = _tasks.value.filter { it.active } + _tasks.value.filterNot { it.active }.takeLast(27) + AiPhotoTask(id, photoKey, name, style)
        try { ContextCompat.startForegroundService(app, Intent(app, AiPhotoService::class.java)) }
        catch (error: Exception) {
            pending.remove(id)?.source?.recycle()
            _tasks.value = _tasks.value.filterNot { it.id == id }
            return "无法启动后台生成，请保持应用在前台并重试"
        }
        _events.tryEmit("已提交 AI 生成任务，完成后自动保存相册")
        return null
    }

    fun startPending() {
        val submissions = pending.toMap()
        pending.clear()
        submissions.forEach { (id, input) ->
            jobs[id] = scope.launch(start = CoroutineStart.LAZY) {
                var rendered: Bitmap? = null
                try {
                    val task = _tasks.value.first { it.id == id }
                    withTimeout(300_000) {
                        update(id, "正在上传并生成图片…")
                        val reference = client.generate(input.config, input.source, 0, input.prompt)
                        update(id, "正在下载生成图片…")
                        val image = client.fetch(reference)
                        rendered = image
                        if (task.style == AiPhotoStyle.MEMORY_ARCHIVE) {
                            val stitched = withContext(Dispatchers.Default) { aiStitchMemoryArchive(input.source, image) }
                            rendered = stitched
                            image.recycle()
                        }
                        update(id, "正在保存到相册…")
                        // Publish a completed image even if Android stops the service during the local commit.
                        val record = withContext(NonCancellable + Dispatchers.IO) {
                            aiSaveToGallery(app, task.sourceName, task.style.title, requireNotNull(rendered), id)
                        }
                        finish(id, "AI 图片已保存到相册", record)
                    }
                } catch (error: TimeoutCancellationException) {
                    finish(id, "等待超时，服务商可能已受理并计费；请检查控制台")
                } catch (error: CancellationException) {
                    finish(id, "任务已中断，服务商已受理的请求可能仍会计费")
                    throw error
                } catch (error: Exception) {
                    finish(id, (error as? AiImageException)?.message ?: "生成或保存失败，请检查网络、权限和存储空间")
                } finally {
                    rendered?.recycle()
                    input.source.recycle()
                    jobs.remove(id)
                }
            }
            jobs[id]?.start()
        }
    }

    private fun update(id: String, message: String) {
        _tasks.value = _tasks.value.map { if (it.id == id) it.copy(message = message) else it }
    }
    private fun finish(id: String, message: String, record: DownloadRecord? = null) {
        val task = _tasks.value.find { it.id == id } ?: return
        if (!task.active) return
        _tasks.value = _tasks.value.map { if (it.id == id) it.copy(active = false, message = message, failed = record == null, saved = record) else it }
        _events.tryEmit("${task.sourceName}：$message")
        AiPhotoService.notifyResult(app, task, message, record)
    }
    fun stopActive() {
        pending.forEach { (id, input) -> input.source.recycle(); finish(id, "后台任务已中断，请检查服务商控制台") }
        pending.clear()
        jobs.values.toList().forEach { it.cancel() }
    }

    companion object {
        @Volatile private var shared: AiPhotoController? = null
        fun shared(context: Context): AiPhotoController = shared ?: synchronized(this) {
            shared ?: AiPhotoController(context).also { shared = it }
        }
    }
}

internal fun aiSubmissionError(tasks: List<AiPhotoTask>, photoKey: String): String? = when {
    tasks.any { it.active && it.photoKey == photoKey } -> "该照片已有 AI 任务处理中"
    tasks.count { it.active } >= 3 -> "已有 3 个任务处理中，请等待任务完成"
    else -> null
}

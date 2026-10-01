package com.yaoyihan.nikonconnect

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** Local monitor rendering only. Never passed to capture, downloads or camera parameter writes. */
data class RemotePreviewColor(
    val entry: LutEntry? = null,
    val lut: CubeLut? = null,
    val intensity: Float = 1f,
    val loading: Boolean = false,
    val message: String? = null,
)

/** This controller has no camera or image-saving dependency. Loading a preset cannot submit a camera command. */
internal class RemotePreviewColorController(private val scope: CoroutineScope, private val supportsGpu: Boolean,
    private val load: suspend (LutEntry) -> CubeLut) {
    val state = MutableStateFlow(RemotePreviewColor())
    private var job: Job? = null
    private val generation = AtomicLong(0)

    fun select(entry: LutEntry?) {
        job?.cancel()
        val request = generation.incrementAndGet()
        val intensity = state.value.intensity
        if (entry == null) { state.value = RemotePreviewColor(intensity = intensity); return }
        if (!supportsGpu) {
            state.value = RemotePreviewColor(intensity = intensity, message = "手机不支持实时预设渲染，保持原始画面")
            return
        }
        state.value = RemotePreviewColor(entry = entry, intensity = intensity, loading = true)
        job = scope.launch {
            try {
                val lut = load(entry)
                if (request == generation.get()) state.update { it.copy(lut = lut, loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (request == generation.get()) state.value = RemotePreviewColor(intensity = state.value.intensity,
                    message = "预设加载失败，保持原始画面：${error.message ?: "文件无法读取"}")
            }
        }
    }

    fun setIntensity(value: Float) {
        if (value.isFinite()) state.update { it.copy(intensity = value.coerceIn(0f, 1f)) }
    }
}

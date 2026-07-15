package com.yaoyihan.nikonconnect.lut

import android.content.Context
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SingleColorLut
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.FrameworkMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.yaoyihan.nikonconnect.CubeLut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.floor
import kotlin.math.roundToInt

@UnstableApi
object VideoLutExporter {
    suspend fun export(
        context: Context,
        input: Uri,
        output: File,
        lut: CubeLut?,
        intensity: Float,
        clockwiseQuarterTurns: Int,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.Main.immediate) {
        output.delete()
        suspendCancellableCoroutine { continuation ->
            var progressJob: Job? = null
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    progressJob?.cancel()
                    onProgress(100)
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException,
                ) {
                    progressJob?.cancel()
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
            }
            val videoEffects = buildList<Effect> {
                if (lut != null) add(SingleColorLut.createFromCube(lut.toMedia3Cube(intensity)))
                val rotation = ((clockwiseQuarterTurns % 4) + 4) % 4
                if (rotation != 0) {
                    add(ScaleAndRotateTransformation.Builder().setRotationDegrees(-rotation * 90f).build())
                }
            }
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(input))
                .setEffects(Effects(emptyList(), videoEffects))
                .build()
            val transformer = Transformer.Builder(context.applicationContext)
                // Android's MediaMuxer streams encoded samples and writes a standard MP4 index,
                // avoiding both whole-file buffering and the zero-duration fragmented MP4 issue.
                .setMuxerFactory(FrameworkMuxer.Factory())
                .addListener(listener)
                .build()
            continuation.invokeOnCancellation {
                progressJob?.cancel()
                transformer.cancel()
                output.delete()
            }
            try {
                transformer.start(edited, output.absolutePath)
                progressJob = CoroutineScope(continuation.context).launchProgress(transformer, onProgress)
            } catch (error: Exception) {
                output.delete()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    private fun CoroutineScope.launchProgress(transformer: Transformer, onProgress: (Int) -> Unit): Job =
        launch {
            val holder = ProgressHolder()
            while (isActive) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress.coerceIn(0, 99))
                }
                delay(250)
            }
        }
}

internal fun CubeLut.toMedia3Cube(intensity: Float): Array<Array<IntArray>> {
    val amount = intensity.coerceIn(0f, 1f)
    val edge = size - 1
    val graded = FloatArray(3)
    return Array(size) { red ->
        Array(size) { green ->
            IntArray(size) { blue ->
                val sourceR = red.toFloat() / edge
                val sourceG = green.toFloat() / edge
                val sourceB = blue.toFloat() / edge
                sample(sourceR, sourceG, sourceB, graded)
                val outputR = (sourceR + (graded[0] - sourceR) * amount).coerceIn(0f, 1f)
                val outputG = (sourceG + (graded[1] - sourceG) * amount).coerceIn(0f, 1f)
                val outputB = (sourceB + (graded[2] - sourceB) * amount).coerceIn(0f, 1f)
                (0xFF shl 24) or
                    ((outputR * 255f).roundToInt() shl 16) or
                    ((outputG * 255f).roundToInt() shl 8) or
                    (outputB * 255f).roundToInt()
            }
        }
    }
}

private fun CubeLut.sample(red: Float, green: Float, blue: Float, result: FloatArray) {
    val edge = size - 1
    fun coordinate(value: Float, channel: Int) =
        (((value - domainMin[channel]) / (domainMax[channel] - domainMin[channel])) * edge).coerceIn(0f, edge.toFloat())
    val r = coordinate(red, 0); val g = coordinate(green, 1); val b = coordinate(blue, 2)
    val r0 = floor(r).toInt(); val g0 = floor(g).toInt(); val b0 = floor(b).toInt()
    val r1 = minOf(r0 + 1, edge); val g1 = minOf(g0 + 1, edge); val b1 = minOf(b0 + 1, edge)
    val tx = r - r0; val ty = g - g0; val tz = b - b0
    val plane = size * size
    for (channel in 0..2) {
        fun value(rr: Int, gg: Int, bb: Int) = values[(bb * plane + gg * size + rr) * 3 + channel]
        val c00 = value(r0, g0, b0) + (value(r1, g0, b0) - value(r0, g0, b0)) * tx
        val c10 = value(r0, g1, b0) + (value(r1, g1, b0) - value(r0, g1, b0)) * tx
        val c01 = value(r0, g0, b1) + (value(r1, g0, b1) - value(r0, g0, b1)) * tx
        val c11 = value(r0, g1, b1) + (value(r1, g1, b1) - value(r0, g1, b1)) * tx
        result[channel] = (c00 + (c10 - c00) * ty) + ((c01 + (c11 - c01) * ty) - (c00 + (c10 - c00) * ty)) * tz
    }
}

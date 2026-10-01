package com.yaoyihan.nikonconnect

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

data class NormalizedFocusPoint(val x: Float, val y: Float)
data class FocusImageBounds(val left: Float, val top: Float, val width: Float, val height: Float)

fun focusImageBounds(viewWidth: Int, viewHeight: Int, imageWidth: Int, imageHeight: Int): FocusImageBounds? {
    if (minOf(viewWidth, viewHeight, imageWidth, imageHeight) <= 0) return null
    val scale = min(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
    val width = imageWidth * scale; val height = imageHeight * scale
    return FocusImageBounds((viewWidth - width) / 2, (viewHeight - height) / 2, width, height)
}

fun focusPointFromTap(x: Float, y: Float, viewWidth: Int, viewHeight: Int,
    imageWidth: Int, imageHeight: Int): NormalizedFocusPoint? {
    if (!x.isFinite() || !y.isFinite()) return null
    val bounds = focusImageBounds(viewWidth, viewHeight, imageWidth, imageHeight) ?: return null
    if (x < bounds.left || y < bounds.top || x >= bounds.left + bounds.width || y >= bounds.top + bounds.height) return null
    return NormalizedFocusPoint((x - bounds.left) / bounds.width, (y - bounds.top) / bounds.height)
}

/** Nikon GetLiveViewImg's big-endian header uses a whole-image AF coordinate space. */
data class LiveViewFocusGeometry(
    val jpegWidth: Int, val jpegHeight: Int,
    val wholeWidth: Int, val wholeHeight: Int,
    val displayWidth: Int, val displayHeight: Int,
    val centerX: Int, val centerY: Int,
) {
    fun cameraPoint(point: NormalizedFocusPoint): Pair<Int, Int> {
        require(point.x.isFinite() && point.y.isFinite() && point.x in 0f..1f && point.y in 0f..1f) { "对焦位置无效" }
        val x = centerX - displayWidth / 2.0 + point.x * displayWidth
        val y = centerY - displayHeight / 2.0 + point.y * displayHeight
        return x.roundToInt().coerceIn(0, wholeWidth - 1) to y.roundToInt().coerceIn(0, wholeHeight - 1)
    }

    companion object {
        fun parse(data: ByteArray, jpegOffset: Int): LiveViewFocusGeometry? {
            // Unknown/truncated layouts keep preview available but do not send guessed AF coordinates.
            if (jpegOffset < 50 || jpegOffset > data.size) return null
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            fun word(offset: Int) = buffer.getShort(offset).toInt() and 0xffff
            val geometry = LiveViewFocusGeometry(word(8), word(10), word(12), word(14),
                word(16), word(18), word(20), word(22))
            if (minOf(geometry.jpegWidth, geometry.jpegHeight, geometry.wholeWidth, geometry.wholeHeight,
                    geometry.displayWidth, geometry.displayHeight) <= 0) return null
            if (geometry.displayWidth > geometry.wholeWidth || geometry.displayHeight > geometry.wholeHeight) return null
            if (geometry.centerX - geometry.displayWidth / 2.0 < -.5 ||
                geometry.centerY - geometry.displayHeight / 2.0 < -.5 ||
                geometry.centerX + geometry.displayWidth / 2.0 > geometry.wholeWidth + .5 ||
                geometry.centerY + geometry.displayHeight / 2.0 > geometry.wholeHeight + .5) return null
            return geometry
        }
    }
}

data class CameraLiveViewFrame(val jpeg: ByteArray, val focusGeometry: LiveViewFocusGeometry?, val focusStatus: Int?)
enum class RemoteFocusPhase { IDLE, FOCUSING, FOCUSED, UNCONFIRMED, FAILED }
data class RemoteFocusState(
    val point: NormalizedFocusPoint? = null,
    val phase: RemoteFocusPhase = RemoteFocusPhase.IDLE,
    val message: String = "",
) {
    val inProgress: Boolean get() = phase == RemoteFocusPhase.FOCUSING
}

data class RemoteFocusResult(val phase: RemoteFocusPhase, val message: String)

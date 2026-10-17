@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.yaoyihan.nikonconnect.lut

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import kotlin.math.ceil
import kotlin.math.floor
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.yaoyihan.nikonconnect.CubeLut
import com.yaoyihan.nikonconnect.photoSwipe
import com.yaoyihan.nikonconnect.photoContentScale

@Composable
fun LutPreviewSurface(
    bitmap: Bitmap,
    lut: CubeLut?,
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    rotation: Int = 0,
    previous: (() -> Unit)? = null,
    next: (() -> Unit)? = null,
    dismissDrag: ((Offset) -> Unit)? = null,
    dismissEnd: ((Float, Boolean) -> Unit)? = null,
    onZoomChanged: (Float) -> Unit = {},
    onSingleTap: (() -> Unit)? = null,
    visible: Boolean = true,
) {
    val context = LocalContext.current
    val view = remember { LutGlView(context) }
    var readyImage by remember(view) { mutableStateOf<Bitmap?>(null) }
    val cover = remember(bitmap) { Animatable(1f) }
    SideEffect { view.onImageRendered = { image -> if (image === bitmap) readyImage = image } }
    LaunchedEffect(bitmap, readyImage === bitmap) {
        if (readyImage === bitmap) cover.animateTo(0f, tween(180))
    }
    var zoom by remember(bitmap, rotation) { mutableFloatStateOf(1f) }
    SideEffect { onZoomChanged(zoom) }
    var offset by remember(bitmap, rotation) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val sourceWidth = if (rotation % 180 == 0) bitmap.width else bitmap.height
    val sourceHeight = if (rotation % 180 == 0) bitmap.height else bitmap.width
    val state = rememberTransformableState { zoomChange, pan, _ ->
        val nextZoom = (zoom * zoomChange).coerceIn(1f, 12f)
        val fitScale = if (viewport == IntSize.Zero) 1f else minOf(viewport.width.toFloat() / sourceWidth, viewport.height.toFloat() / sourceHeight)
        val maxX = ((sourceWidth * fitScale * nextZoom - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((sourceHeight * fitScale * nextZoom - viewport.height) / 2f).coerceAtLeast(0f)
        offset = if (nextZoom == 1f) Offset.Zero else Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
        zoom = nextZoom
    }
    DisposableEffect(view) {
        view.onResume()
        onDispose { view.onImageRendered = null; view.onPause() }
    }
    Box(modifier.onSizeChanged { viewport = it }
            .photoSwipe(bitmap, zoom == 1f, previous, next, dismissDrag, dismissEnd)
            .pointerInput(bitmap, rotation) {
                detectTapGestures(onTap = { onSingleTap?.invoke() }, onDoubleTap = { zoom = if (zoom > 1f) 1f else 3f; offset = Offset.Zero })
            }
            .transformable(state, canPan = { zoom > 1f })) {
        AndroidView(
            factory = { view },
            modifier = Modifier.matchParentSize(),
            update = {
                // Hide the separate native surface before its window is removed, avoiding a clear-frame flash.
                it.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                it.setImage(bitmap)
                it.setLut(lut)
                it.setIntensity(intensity)
                it.setImageRotation(rotation)
                it.setTransform(zoom, offset.x, offset.y)
                // Clip the native surface to the actual image so its black clear area never becomes a moving rectangle.
                if (viewport != IntSize.Zero) {
                    val fit = minOf(viewport.width.toFloat() / sourceWidth, viewport.height.toFloat() / sourceHeight)
                    val halfWidth = sourceWidth * fit * zoom / 2f
                    val halfHeight = sourceHeight * fit * zoom / 2f
                    val centerX = viewport.width / 2f + offset.x
                    val centerY = viewport.height / 2f + offset.y
                    it.clipBounds = Rect(floor(centerX - halfWidth).toInt().coerceAtLeast(0), floor(centerY - halfHeight).toInt().coerceAtLeast(0), ceil(centerX + halfWidth).toInt().coerceAtMost(viewport.width), ceil(centerY + halfHeight).toInt().coerceAtMost(viewport.height))
                }
            },
        )
        if (cover.value > 0f) Image(bitmap.asImageBitmap(), null, Modifier.matchParentSize().graphicsLayer { alpha = cover.value; rotationZ = rotation.toFloat() }, contentScale = photoContentScale(rotation))
    }
}

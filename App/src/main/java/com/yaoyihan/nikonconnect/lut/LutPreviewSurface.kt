package com.yaoyihan.nikonconnect.lut

import android.graphics.Bitmap
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.yaoyihan.nikonconnect.CubeLut

@Composable
fun LutPreviewSurface(
    bitmap: Bitmap,
    lut: CubeLut?,
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    rotation: Int = 0,
) {
    val context = LocalContext.current
    val view = remember { LutGlView(context) }
    var zoom by remember(bitmap, rotation) { mutableFloatStateOf(1f) }
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
        onDispose { view.onPause() }
    }
    AndroidView(
        factory = { view },
        modifier = modifier.onSizeChanged { viewport = it }.transformable(state),
        update = {
            it.setImage(bitmap)
            it.setLut(lut)
            it.setIntensity(intensity)
            it.setImageRotation(rotation)
            it.setTransform(zoom, offset.x, offset.y)
        },
    )
}

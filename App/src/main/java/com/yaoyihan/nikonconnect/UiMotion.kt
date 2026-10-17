package com.yaoyihan.nikonconnect

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Indeterminate loading stays on the photo and never reports invented transfer progress. */
@Composable
internal fun PhotoDiagonalLoading(active: Boolean, bitmap: Bitmap?, rotation: Int, modifier: Modifier = Modifier) {
    val visibility = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) { delay(100); visibility.animateTo(1f, tween(120)) }
        else visibility.animateTo(0f, tween(160))
    }
    if (bitmap == null || visibility.value <= 0f) return
    val scan = rememberInfiniteTransition(label = "photo loading")
    val phase by scan.animateFloat(0f, 1f, infiniteRepeatable(tween(1450, easing = LinearEasing)), label = "diagonal scan")
    val shift = with(LocalDensity.current) { (-28).dp.toPx() }
    Canvas(modifier.clearAndSetSemantics { }) {
        val iw = if (rotation % 180 == 0) bitmap.width else bitmap.height
        val ih = if (rotation % 180 == 0) bitmap.height else bitmap.width
        val fit = minOf(size.width / iw, size.height / ih)
        val width = iw * fit
        val height = ih * fit
        val left = (size.width - width) / 2f
        val top = (size.height - height) / 2f + shift
        val band = width * .22f
        val position = ((width + height + band * 4f) * phase - band * 2f) / 2f
        val start = Offset(left + position - band, top + position - band)
        val end = Offset(left + position + band, top + position + band)
        val alpha = visibility.value
        clipRect(left, top, left + width, top + height) {
            drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .06f * alpha), Color.White.copy(alpha = .23f * alpha), Color.White.copy(alpha = .06f * alpha), Color.Transparent), start, end))
        }
    }
}

internal fun mediaCardRatio(width: Int, height: Int): Float =
    if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(.65f, 1.6f) else .8f

@Composable
internal fun Modifier.bridgeEntrance(animate: Boolean, index: Int, initial: Boolean = false): Modifier {
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    val distance = with(LocalDensity.current) { (if (initial) 8.dp else 24.dp).toPx() }
    LaunchedEffect(Unit) {
        if (animate) {
            delay((index.coerceAtLeast(0) % 6) * if (initial) 60L else 50L)
            progress.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
        }
    }
    return graphicsLayer { alpha = progress.value; translationY = (1f - progress.value) * distance }
}

@Composable
internal fun BridgeSkeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val sweep by transition.animateFloat(-1f, 2f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "sweep")
    val base = BridgeWhite.copy(alpha = .07f)
    val light = BridgeWhite.copy(alpha = .16f)
    Canvas(modifier.clearAndSetSemantics { }) {
        drawRect(base)
        val x = sweep * size.width
        drawRect(Brush.linearGradient(listOf(base.copy(alpha = 0f), light, base.copy(alpha = 0f)),
            start = Offset(x - size.width * .6f, 0f), end = Offset(x + size.width * .2f, size.height)))
    }
}

@Composable
internal fun BridgeThumbnail(bitmap: Bitmap?, modifier: Modifier = Modifier, animate: Boolean = true, contentScale: ContentScale = ContentScale.Crop) {
    val initiallyReady = remember { bitmap != null || !animate }
    val reveal = remember { Animatable(if (initiallyReady) 1f else 0f) }
    LaunchedEffect(bitmap != null) { if (bitmap != null) reveal.animateTo(1f, tween(320)) }
    Box(modifier) {
        if (bitmap == null || reveal.value < 1f) BridgeSkeleton(Modifier.fillMaxSize().graphicsLayer { alpha = if (bitmap == null) 1f else 1f - reveal.value })
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer {
            alpha = reveal.value
            scaleX = 1f + .04f * (1f - reveal.value); scaleY = scaleX
            if (Build.VERSION.SDK_INT >= 31) {
                val radius = 8f * (1f - reveal.value)
                renderEffect = if (radius > .1f) BlurEffect(radius, radius) else null
            }
        }, contentScale = contentScale)
    }
}

@Composable
internal fun BridgeLoadingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "loading dots")
    Row(modifier.height(28.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        repeat(3) { index ->
            val bounce by transition.animateFloat(0f, 1f,
                infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 140)), label = "dot$index")
            val distance = with(LocalDensity.current) { 4.dp.toPx() }
            Box(Modifier.size(6.dp).graphicsLayer { translationY = -distance * bounce; alpha = .4f + .6f * bounce }
                .background(BridgeEmber, CircleShape))
        }
    }
}

internal fun Modifier.bridgePress(source: MutableInteractionSource): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .9f else 1f }
}

internal fun Modifier.bridgeShake(attempt: Int): Modifier = composed {
    val shift = remember { Animatable(0f) }
    val distance = with(LocalDensity.current) { 4.dp.toPx() }
    LaunchedEffect(attempt) {
        if (attempt > 0) {
            for (offset in listOf(-1f, 1f, -.6f, .6f, 0f)) shift.animateTo(offset, tween(45))
        } else shift.snapTo(0f)
    }
    graphicsLayer { translationX = shift.value * distance }
}

@Composable
internal fun BridgeDownloadProgress(
    progress: Float,
    completed: Boolean,
    indeterminate: Boolean,
    modifier: Modifier,
    color: Color = BridgeEmber,
    strokeWidth: Dp = 5.dp,
    content: @Composable () -> Unit,
) {
    val shownProgress by animateFloatAsState(if (completed) 1f else progress, tween(160), label = "download progress")
    // 先走满进度环，再描出对勾；系统关闭动画时直接呈现完成状态。
    val finish by animateFloatAsState(if (completed) 1f else 0f,
        tween(300, delayMillis = if (completed) 160 else 0, easing = FastOutSlowInEasing), label = "download complete")
    Box(modifier.semantics { if (completed) stateDescription = "下载完成，已保存到手机" }, contentAlignment = androidx.compose.ui.Alignment.Center) {
        val ring = Modifier.matchParentSize().graphicsLayer { alpha = 1f - finish }
        if (indeterminate && !completed) CircularProgressIndicator(ring, color = color, strokeWidth = strokeWidth, trackColor = BridgeWhite.copy(alpha = .2f))
        else CircularProgressIndicator(progress = { shownProgress }, modifier = ring, color = color, strokeWidth = strokeWidth, trackColor = BridgeWhite.copy(alpha = .2f))
        Box(Modifier.graphicsLayer { alpha = 1f - finish }) { content() }
        Canvas(Modifier.matchParentSize().graphicsLayer {
            alpha = finish
            val scale = 1f + .05f * kotlin.math.sin(finish * kotlin.math.PI).toFloat()
            scaleX = scale; scaleY = scale
        }) {
            val a = Offset(size.width * .27f, size.height * .51f)
            val b = Offset(size.width * .44f, size.height * .67f)
            val c = Offset(size.width * .75f, size.height * .34f)
            drawLine(color, a, a + (b - a) * (finish / .35f).coerceIn(0f, 1f), strokeWidth.toPx(), StrokeCap.Round)
            if (finish > .35f) drawLine(color, b, b + (c - b) * ((finish - .35f) / .65f), strokeWidth.toPx(), StrokeCap.Round)
        }
    }
}

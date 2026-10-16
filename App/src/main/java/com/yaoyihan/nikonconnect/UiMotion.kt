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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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

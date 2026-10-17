package com.yaoyihan.nikonconnect

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import androidx.core.view.WindowCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal data class PhotoPreviewSource(val bounds: Rect, val bitmap: Bitmap, val crop: Boolean, val reveal: () -> Unit = {})

/** Only mounted, fully visible tiles are valid return destinations. Never cache a screen rect. */
internal class PhotoPreviewSources {
    internal class Tile(val view: View) {
        var coordinates: LayoutCoordinates? = null
        var bitmap: Bitmap? = null
        var crop = false
        var returning by mutableStateOf(false)
    }
    internal val tiles = mutableMapOf<String, Tile>()

    fun find(key: String): PhotoPreviewSource? {
        val tile = tiles[key] ?: return null
        val coordinates = tile.coordinates?.takeIf { it.isAttached } ?: return null
        val bitmap = tile.bitmap?.takeUnless { it.isRecycled } ?: return null
        val visible = coordinates.boundsInWindow()
        if (visible.width < coordinates.size.width - 2 || visible.height < coordinates.size.height - 2) return null
        val origin = coordinates.positionInWindow() + tile.view.windowScreenOrigin()
        return PhotoPreviewSource(Rect(origin, androidx.compose.ui.geometry.Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())), bitmap, tile.crop) { tile.returning = true }
    }
}

internal fun Modifier.photoPreviewSource(sources: PhotoPreviewSources, key: String, bitmap: Bitmap?, crop: Boolean, hidden: Boolean): Modifier = composed {
    val view = LocalView.current
    val tile = remember(sources, key, view) { PhotoPreviewSources.Tile(view) }
    SideEffect { tile.bitmap = bitmap; tile.crop = crop; if (!hidden) tile.returning = false }
    DisposableEffect(sources, key, tile) {
        sources.tiles[key] = tile
        onDispose { if (sources.tiles[key] === tile) sources.tiles.remove(key) }
    }
    this.onGloballyPositioned { tile.coordinates = it }.graphicsLayer { alpha = if (hidden && !tile.returning) 0f else 1f }
}

@Composable
internal fun preparePhotoPreviewWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = AndroidColor.TRANSPARENT
            window.navigationBarColor = AndroidColor.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.attributes = window.attributes.apply { windowAnimations = 0 }
        }
        onDispose { }
    }
}

@Composable
internal fun rememberPhotoDetailMotion(source: (() -> PhotoPreviewSource?)?, dismiss: () -> Unit): PhotoDetailMotion {
    val scope = rememberCoroutineScope()
    val latestSource by rememberUpdatedState(source)
    val latestDismiss by rememberUpdatedState(dismiss)
    val motion = remember { PhotoDetailMotion(scope, source?.invoke(), { latestSource?.invoke() }, { latestDismiss() }) }
    val dock = LocalBridgeDockTransition.current
    DisposableEffect(dock, motion) {
        dock?.owner = motion
        onDispose { if (dock?.owner === motion) dock.owner = null }
    }
    LaunchedEffect(motion) { motion.open() }
    return motion
}

internal class PhotoDetailMotion(
    private val scope: CoroutineScope,
    val initialSource: PhotoPreviewSource?,
    private val currentSource: () -> PhotoPreviewSource?,
    private val dismiss: () -> Unit,
) {
    val progress = Animatable(0f)
    private val dockProgress = Animatable(0f)
    private var opening: Job? = null
    var viewport by mutableStateOf(IntSize.Zero)
    var screenOrigin by mutableStateOf(Offset.Zero)
    var entered by mutableStateOf(false)
    var closing by mutableStateOf(false)
        private set
    var handedOff by mutableStateOf(false)
        private set
    private var returnSource by mutableStateOf<PhotoPreviewSource?>(null)
    private var drag by mutableStateOf(Offset.Zero)
    private var dragActive = false
    private var dragStart = Offset.Zero
    private var settle: Job? = null
    val gestureEnabled get() = entered && !closing
    val controlsAlpha get() = (1f - drag.y / (viewport.height * .3f).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val chromeAlpha get() = progress.value * progress.value * controlsAlpha
    // Both button rows share one independent clock; photo loading never gates the swap.
    val dockSwapProgress get() = dockProgress.value
    val backdropAlpha get() = progress.value * (1f - drag.y / (viewport.height * .7f).coerceAtLeast(1f)).coerceIn(.15f, 1f)
    private val source get() = if (closing) returnSource else initialSource

    fun open() {
        if (closing || opening != null) return
        opening = scope.launch {
            launch {
                progress.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
                entered = true
            }
            dockProgress.animateTo(1f, tween(BridgeDockSwapDuration, easing = FastOutSlowInEasing))
        }
    }

    fun close(shared: Boolean = true) {
        if (closing) return
        closing = true
        returnSource = if (shared) currentSource() else null
        // Let the real tile draw underneath the returning image before the dialog is removed.
        returnSource?.reveal?.invoke()
        opening?.cancel()
        settle?.cancel()
        scope.launch {
            val reset = launch { resetDrag() }
            coroutineScope {
                launch { progress.animateTo(0f, tween(280, easing = FastOutSlowInEasing)) }
                // Reverse from the current row position, including an interrupted opening.
                dockProgress.animateTo(0f, tween((BridgeDockSwapDuration * dockProgress.value).roundToInt(), easing = FastOutSlowInEasing))
            }
            reset.cancel()
            handedOff = true
            // Both windows draw the handoff before removing the transparent dialog window.
            withFrameNanos { }
            withFrameNanos { }
            dismiss()
        }
    }

    fun dragTo(offset: Offset) {
        if (!gestureEnabled) return
        if (!dragActive) {
            settle?.cancel()
            dragStart = drag
            dragActive = true
        }
        drag = Offset(dragStart.x + offset.x * .35f, (dragStart.y + offset.y).coerceAtLeast(0f))
    }

    fun release(velocity: Float, cancelled: Boolean, shared: Boolean = true) {
        dragActive = false
        if (!gestureEnabled) return
        val threshold = viewport.height * .16f
        if (!cancelled && (drag.y > threshold || (drag.y > 24f && velocity > 1100f))) close(shared)
        else {
            settle?.cancel()
            settle = scope.launch { resetDrag() }
        }
    }

    private suspend fun resetDrag() {
        Animatable(drag, Offset.VectorConverter).animateTo(Offset.Zero, spring(dampingRatio = .9f, stiffness = 380f)) { drag = value }
    }

    @Composable
    fun rootModifier(): Modifier {
        val view = LocalView.current
        return Modifier.onGloballyPositioned { coordinates ->
            viewport = coordinates.size
            screenOrigin = coordinates.positionInWindow() + view.windowScreenOrigin()
        }
    }

    @Composable
    fun clipModifier(): Modifier {
        val radius = with(LocalDensity.current) { 10.dp.toPx() }
        return Modifier.graphicsLayer {
            val p = progress.value
            val from = source?.bounds?.translate(-screenOrigin)
            clip = true
            shape = GenericShape { size, _ ->
                val full = Rect(Offset.Zero, size)
                val rect = from?.let { androidx.compose.ui.geometry.lerp(it, full, p) } ?: full
                addRoundRect(RoundRect(rect, CornerRadius(radius * (1f - p))))
            }
            alpha = if (source == null) p else if (closing) (p / .18f).coerceIn(0f, 1f) else 1f
        }
    }

    @Composable
    fun imageModifier(bitmap: Bitmap?, rotation: Int, stage: () -> Rect? = { null }): Modifier {
        val imageOffset = with(LocalDensity.current) { (-28).dp.toPx() }
        return Modifier.graphicsLayer {
            val p = progress.value
            val from = source
            val image = bitmap
            val width = viewport.width.toFloat().coerceAtLeast(1f)
            val height = viewport.height.toFloat().coerceAtLeast(1f)
            val startRect = from?.bounds?.translate(-screenOrigin)
            val destination = stage() ?: Rect(0f, imageOffset, width, height + imageOffset)
            val targetScale = if (image != null) {
                val iw = (if (rotation % 180 == 0) image.width else image.height).toFloat()
                val ih = (if (rotation % 180 == 0) image.height else image.width).toFloat()
                minOf(destination.width / iw, destination.height / ih) / minOf(width / iw, height / ih)
            } else 1f
            val initialScale = if (image != null && startRect != null) {
                val iw = (if (rotation % 180 == 0) image.width else image.height).toFloat()
                val ih = (if (rotation % 180 == 0) image.height else image.width).toFloat()
                val fit = minOf(width / iw, height / ih)
                val start = if (from.crop) maxOf(startRect.width / iw, startRect.height / ih) else minOf(startRect.width / iw, startRect.height / ih)
                start / fit
            } else 1f
            val sharedScale = initialScale + (targetScale - initialScale) * p
            val dragScale = (1f - drag.y / height * .3f).coerceAtLeast(.75f)
            scaleX = sharedScale * dragScale
            scaleY = scaleX
            val startX = (startRect?.center?.x ?: destination.center.x) - width / 2f
            val startY = (startRect?.center?.y ?: destination.center.y) - height / 2f - imageOffset * initialScale
            translationX = startX * (1f - p) + (destination.center.x - width / 2f) * p + drag.x
            translationY = startY * (1f - p) + (destination.center.y - height / 2f - imageOffset * targetScale) * p + drag.y
        }
    }
}

private fun View.windowScreenOrigin(): Offset {
    val screen = IntArray(2).also(::getLocationOnScreen)
    val window = IntArray(2).also(::getLocationInWindow)
    return Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
}

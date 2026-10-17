package com.yaoyihan.nikonconnect

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal fun bridgeBackgroundColors(theme: AppColorTheme): List<Color> = when (theme) {
    AppColorTheme.DARKROOM_ORANGE -> listOf(Color(0xFFD98B60), Color(0xFFA18B80), Color(0xFF706972))
    AppColorTheme.NIKON_YELLOW -> listOf(Color(0xFFBAA044), Color(0xFF999078), Color(0xFF696D68))
    AppColorTheme.PROFESSIONAL_GRAY -> listOf(Color(0xFF85919C), Color(0xFF7C858D), Color(0xFF555E68))
    AppColorTheme.DEEP_BLUE -> listOf(Color(0xFF548FBF), Color(0xFF7B92A5), Color(0xFF596876))
}

// A smooth backdrop needs only translucent diffusion to read as frosted glass.
// Keep text outside any blur layer and avoid offscreen captures during scrolling.
private val DefaultGlassShape = RoundedCornerShape(24.dp)
private val GlassFillColors = listOf(Color.White.copy(alpha = .28f), Color.White.copy(alpha = .16f), Color.White.copy(alpha = .22f))
private val GlassEdgeColors = listOf(Color.White.copy(alpha = .65f), Color.White.copy(alpha = .22f), Color.White.copy(alpha = .07f), Color.White.copy(alpha = .30f))

internal fun Modifier.bridgeGlass(shape: Shape = DefaultGlassShape): Modifier =
    clip(shape)
        .drawWithCache {
            // Scrolling changes placement, not size: reuse shaders and the border outline.
            val fill = Brush.linearGradient(GlassFillColors)
            val edge = Brush.linearGradient(GlassEdgeColors)
            val width = 1.dp.toPx().coerceAtMost(size.minDimension)
            val outline = shape.createOutline(Size((size.width - width).coerceAtLeast(0f), (size.height - width).coerceAtLeast(0f)), layoutDirection, this)
            val stroke = Stroke(width)
            onDrawWithContent {
                drawRect(fill)
                drawContent()
                if (width > 0f) translate(width / 2f, width / 2f) { drawOutline(outline, edge, style = stroke) }
            }
        }

@Composable
internal fun Modifier.bridgeBackground(): Modifier = background(BridgeBackground)

internal class BridgeBackdrop(val layer: GraphicsLayer) {
    var position by mutableStateOf(Offset.Zero)
}

internal val LocalBridgeColorTheme = staticCompositionLocalOf { AppColorTheme.DARKROOM_ORANGE }
internal val LocalBridgeBackdrop = staticCompositionLocalOf<BridgeBackdrop?> { null }

/** Keep native slider input/accessibility; share the round handle and theme color. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BridgeSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    // 拖动位置直接跟手，只给圆形手柄轻微的按压反馈。
    val scale by animateFloatAsState(if (enabled && (pressed || dragged)) 1.08f else 1f,
        tween(180), label = "slider handle")
    val accent = if (enabled) BridgeEmber else BridgeWhite.copy(alpha = .32f)
    val rail = BridgeWhite.copy(alpha = if (enabled) .22f else .1f)
    val edge = BridgeWhite.copy(alpha = if (enabled) .8f else .2f)
    Slider(value, onValueChange, modifier, enabled = enabled, valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished, interactionSource = interaction,
        thumb = {
            Box(Modifier.size(20.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .background(accent, CircleShape).border(1.5.dp, edge, CircleShape))
        },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                val fraction = ((state.value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                val start = if (layoutDirection == LayoutDirection.Rtl) size.width else 0f
                val end = size.width - start
                drawLine(rail, Offset(start, center.y), Offset(end, center.y), size.height, StrokeCap.Round)
                if (fraction > 0f) drawLine(accent, Offset(start, center.y),
                    Offset(start + (end - start) * fraction, center.y), size.height, StrokeCap.Round)
            }
        })
}

@Composable
internal fun FloatingMediaPage(header: @Composable ColumnScope.() -> Unit, content: @Composable (Dp) -> Unit) {
    val backdrop = LocalBridgeBackdrop.current
    var headerHeight by remember { mutableIntStateOf(0) }
    val headerInset = with(LocalDensity.current) { headerHeight.toDp() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().bridgeBackdropSource(backdrop).bridgeBackground()) { content(headerInset) }
        BridgeProgressiveBackdrop(backdrop, Modifier.fillMaxWidth().height(headerInset + 36.dp).align(Alignment.TopCenter))
        // 照片和模糊铺进状态栏，安全间距只作用于前景标题和按钮。
        Column(Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(top = 8.dp), content = header)
    }
}

internal fun Modifier.bridgeBackdropSource(backdrop: BridgeBackdrop?): Modifier =
    if (backdrop == null || Build.VERSION.SDK_INT < 31) this else
        onGloballyPositioned { backdrop.position = it.positionInRoot() }
            .drawWithContent {
                // 复用绘制指令供悬浮栏采样，不逐帧截屏或解码照片。
                backdrop.layer.record { this@drawWithContent.drawContent() }
                drawLayer(backdrop.layer)
            }

/** Blur only the sampled photo behind the panel; text and controls stay sharp. */
@Composable
internal fun Modifier.bridgeFrostedPanel(backdrop: BridgeBackdrop?, tint: Color, opacity: Float = 1f,
    snapshot: ImageBitmap? = null, snapshotPosition: Offset = Offset.Zero): Modifier {
    val layer = rememberGraphicsLayer()
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return onGloballyPositioned { coordinates[0] = it }.drawWithCache {
        val radius = 22.dp.toPx()
        val inset = radius * 2f
        val wash = Brush.verticalGradient(listOf(tint.copy(alpha = .60f * opacity), tint.copy(alpha = .78f * opacity)))
        onDrawBehind {
            if (Build.VERSION.SDK_INT >= 31 && (snapshot != null || (backdrop != null && backdrop.layer.size.width > 0))) {
                val position = coordinates[0]?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
                val offset = (if (snapshot != null) snapshotPosition else backdrop!!.position) - position
                layer.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                layer.compositingStrategy = CompositingStrategy.Offscreen
                layer.record(size = IntSize((size.width + inset * 2).roundToInt(), (size.height + inset * 2).roundToInt())) {
                    translate(offset.x + inset, offset.y + inset) {
                        if (snapshot != null) drawImage(snapshot) else drawLayer(backdrop!!.layer)
                    }
                }
                translate(-inset, -inset) { drawLayer(layer) }
                drawRect(wash)
            } else drawRect(tint.copy(alpha = .94f * opacity))
        }
    }
}

@Composable
internal fun BridgeProgressiveBackdrop(backdrop: BridgeBackdrop?, modifier: Modifier, bottom: Boolean = false, tint: Color = BridgeSurface) {
    var position by remember { mutableStateOf(Offset.Zero) }
    // 三层由轻到重的模糊交叠，边缘渐隐；文字和按钮在独立的前景层。
    val layers = List(3) { rememberGraphicsLayer() }
    Box(modifier.clipToBounds().onGloballyPositioned { position = it.positionInRoot() }.drawWithCache {
        fun mask(start: Float) = Brush.verticalGradient(
            *(if (bottom) arrayOf(0f to Color.Transparent, start to Color.Transparent, 1f to Color.White)
            else arrayOf(0f to Color.White, (1f - start) to Color.Transparent, 1f to Color.Transparent))
        )
        val masks = listOf(mask(0f), mask(.28f), mask(.55f))
        val wash = Brush.verticalGradient(
            if (bottom) listOf(Color.Transparent, tint.copy(alpha = .12f), tint.copy(alpha = .46f))
            else listOf(tint.copy(alpha = .46f), tint.copy(alpha = .12f), Color.Transparent)
        )
        val bounds = androidx.compose.ui.geometry.Rect(Offset.Zero, size)
        val paint = androidx.compose.ui.graphics.Paint()
        val radii = List(3) { (4 + it * 7).dp.toPx() }
        onDrawBehind {
            if (backdrop != null && Build.VERSION.SDK_INT >= 31 && backdrop.layer.size.width > 0) {
                layers.forEachIndexed { index, layer ->
                    val radius = radii[index]
                    // 模糊层只分配栏区域，额外留出采样边距以免裁切出硬边。
                    val inset = radius * 2
                    val offset = backdrop.position - position
                    layer.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                    layer.compositingStrategy = CompositingStrategy.Offscreen
                    layer.record(size = IntSize((size.width + inset * 2).roundToInt(), (size.height + inset * 2).roundToInt())) {
                        translate(offset.x + inset, offset.y + inset) { drawLayer(backdrop.layer) }
                    }
                    // 先模糊，再在另一离屏层内蒙版，避免把蒙版本身一起模糊。
                    drawContext.canvas.saveLayer(bounds, paint)
                    translate(-inset, -inset) { drawLayer(layer) }
                    drawRect(masks[index], blendMode = BlendMode.DstIn)
                    drawContext.canvas.restore()
                }
            }
            drawRect(wash)
        }
    })
}

internal fun Modifier.bridgeColorBloom(theme: AppColorTheme): Modifier = drawWithCache {
    val (cool, warm) = when (theme) {
        AppColorTheme.DARKROOM_ORANGE -> Color(0xFF9DD6C1) to Color(0xFFC2A2DC)
        AppColorTheme.NIKON_YELLOW -> Color(0xFF83C5BA) to Color(0xFFC1A3D7)
        AppColorTheme.PROFESSIONAL_GRAY -> Color(0xFF98D0D2) to Color(0xFFE1B69C)
        AppColorTheme.DEEP_BLUE -> Color(0xFF9BD7BF) to Color(0xFFEAB599)
    }
    val left = Brush.radialGradient(listOf(cool.copy(alpha = .66f), Color.Transparent),
        center = Offset(size.width * .06f, size.height * .94f), radius = size.width * .92f)
    val right = Brush.radialGradient(listOf(warm.copy(alpha = .60f), Color.Transparent),
        center = Offset(size.width * .96f, size.height * .75f), radius = size.width * .8f)
    onDrawBehind { drawRect(left); drawRect(right) }
}

internal val BridgeGlassEdge = BorderStroke(1.dp, Brush.linearGradient(listOf(
    Color.White.copy(alpha = .76f), Color.White.copy(alpha = .15f),
    Color.White.copy(alpha = .06f), Color.White.copy(alpha = .38f))))


/** Native sheets use a frozen background so graphics layers never cross window boundaries. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BridgeGlassBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val backdrop = LocalBridgeBackdrop.current
    val theme = LocalBridgeColorTheme.current
    var snapshot by remember(backdrop) { mutableStateOf<ImageBitmap?>(null) }
    var snapshotPosition by remember(backdrop) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(backdrop) {
        if (backdrop != null) {
            withFrameNanos { }
            if (backdrop.layer.size.width > 0) {
                snapshotPosition = backdrop.position
                snapshot = runCatching { backdrop.layer.toImageBitmap() }.getOrNull()
            }
        }
    }
    val shape = RoundedCornerShape(28.dp)
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState,
        modifier = Modifier.padding(horizontal = 12.dp), shape = shape,
        containerColor = Color.Transparent, contentColor = BridgeWhite,
        scrimColor = Color.Black.copy(alpha = .18f), dragHandle = null) {
        Column(Modifier.fillMaxWidth().testTag("bridgeGlassSheet").clip(shape).background(BridgeBackground)
            .bridgeFrostedPanel(null, BridgeFrost, opacity = .5f, snapshot = snapshot, snapshotPosition = snapshotPosition)
            .bridgeColorBloom(theme).border(BridgeGlassEdge, shape)) {
            Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 4.dp).background(BridgeWhite.copy(alpha = .6f), RoundedCornerShape(2.dp)))
            }
            content()
        }
    }
}

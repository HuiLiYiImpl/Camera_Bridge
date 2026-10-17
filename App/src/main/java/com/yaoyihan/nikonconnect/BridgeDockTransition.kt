package com.yaoyihan.nikonconnect

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.roundToInt

internal val BridgeDockHeight = 64.dp
internal val BridgeDockBottomGap = 6.dp
internal val BridgeDockSideGap = 16.dp
internal val BridgeDockRadius = 32.dp
internal const val BridgeDockSwapDuration = 320

/** Keep the outgoing button data, so both rows can be drawn in the same window. */
internal class BridgeDockTransition {
    var items by mutableStateOf<List<BridgeDockItem>>(emptyList())
    var selected by mutableStateOf("")
    var bounds by mutableStateOf<Rect?>(null)
    var owner by mutableStateOf<PhotoDetailMotion?>(null)
}
internal val LocalBridgeDockTransition = staticCompositionLocalOf<BridgeDockTransition?> { null }

internal data class BridgeDockItem(val id: String, val title: String, val icon: ImageVector,
    val enabled: Boolean = true, val badge: Boolean = false, val action: Boolean = false, val click: () -> Unit)

/** Navigation and photo tools share one fixed glass shell and the same button transitions. */
@Composable
internal fun BridgeDockBar(items: List<BridgeDockItem>, selected: String, modifier: Modifier = Modifier,
    frost: Color = Color.Transparent, snapshot: BridgeDockTransition? = null,
    previous: BridgeDockTransition? = null, contentOffset: () -> Float = { 0f }) {
    SideEffect {
        if (snapshot != null && snapshot.owner == null) {
            snapshot.items = items
            snapshot.selected = selected
        }
    }
    Surface(modifier.height(BridgeDockHeight).background(frost, RoundedCornerShape(BridgeDockRadius)).bridgeGlass(RoundedCornerShape(BridgeDockRadius)),
        shape = RoundedCornerShape(BridgeDockRadius), color = Color.Transparent) {
        Box(Modifier.padding(6.dp).fillMaxSize().clip(RoundedCornerShape(26.dp))) {
            // Compose both rows before the first animation frame. No cross-window drawing layers.
            if (previous != null && previous.items.isNotEmpty()) {
                BridgeDockButtons(previous.items, previous.selected,
                    Modifier.fillMaxSize().clearAndSetSemantics { }.graphicsLayer {
                        translationY = 24.dp.toPx() * (contentOffset() + 1f)
                        alpha = -contentOffset()
                    }, interactive = false)
            }
            BridgeDockButtons(items, selected,
                Modifier.fillMaxSize().graphicsLayer {
                    translationY = 24.dp.toPx() * contentOffset()
                    alpha = 1f + contentOffset()
                })
        }
    }
}

@Composable
private fun BridgeDockButtons(items: List<BridgeDockItem>, selected: String, modifier: Modifier, interactive: Boolean = true) {
    val selectedIndex = items.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    val transition = updateTransition(selectedIndex, label = "dock selection")
    val weights = items.indices.map { index -> transition.animateFloat(transitionSpec = { tween(280) }, label = "dock width $index") { if (it == index) 2f else 1f }.value }
    val position = transition.animateFloat(transitionSpec = { tween(280) }, label = "dock capsule") { it.toFloat() }
    val shape = RoundedCornerShape(26.dp)
    BoxWithConstraints(modifier) {
        val unit = maxWidth / (items.size + 1)
        // A visual highlight has no Surface pointer handler or semantics of its own.
        Box(Modifier.width(unit * 2f).fillMaxHeight().graphicsLayer { translationX = unit.toPx() * position.value }
            .bridgeGlass(shape).background(BridgeWhite.copy(alpha = .1f), shape))
        Row(Modifier.fillMaxSize().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { index, item -> key(item.id) {
                val interaction = remember { MutableInteractionSource() }
                val pressed = interaction.collectIsPressedAsState()
                val pressColor = BridgeWhite.copy(alpha = .12f)
                val active = index == selectedIndex
                Row(Modifier.width(unit * weights[index]).fillMaxHeight().bridgePress(interaction).clip(shape)
                    // Observe press only while drawing. Rebuilding this subcomposition on DOWN
                    // replaces its selectable node before UP and cancels the click.
                    .drawBehind { if (pressed.value) drawRect(pressColor) }
                    .selectable(active, enabled = item.enabled && interactive, interactionSource = interaction, indication = null, role = if (item.action) Role.Button else Role.Tab, onClick = item.click),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Icon(item.icon, item.title, Modifier.size(23.dp), tint = BridgeWhite.copy(alpha = if (!item.enabled) .68f else if (active) 1f else .68f))
                        if (item.badge) Surface(Modifier.align(Alignment.TopEnd).offset(3.dp, (-3).dp).size(7.dp), shape = CircleShape, color = BridgeEmber) { }
                    }
                    AnimatedVisibility(active, enter = fadeIn(tween(160, delayMillis = 70)) + expandHorizontally(tween(240)), exit = fadeOut(tween(90)) + shrinkHorizontally(tween(180))) {
                        Text(item.title, Modifier.padding(start = 7.dp), color = BridgeWhite.copy(alpha = if (item.enabled) 1f else .68f), style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } }
        }
    }
}

internal fun BridgeDockTransition.localBounds(motion: PhotoDetailMotion): Rect? = bounds?.translate(-motion.screenOrigin)
    ?.takeIf { motion.viewport.height > 0 && it.width > 0f && it.top >= 0f && it.bottom <= motion.viewport.height + 1f }

/** Use the actual activity bar's screen rect, independent of dialog inset consumption. */
@Composable
internal fun BridgeDockPlacement(transition: BridgeDockTransition?, motion: PhotoDetailMotion, fallbackBottom: Float, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
        val side = BridgeDockSideGap.toPx()
        val height = BridgeDockHeight.toPx()
        val rect = transition?.localBounds(motion) ?: Rect(side, constraints.maxHeight - fallbackBottom - height, constraints.maxWidth - side, constraints.maxHeight - fallbackBottom)
        val child = measurables.single().measure(Constraints.fixed(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)))
        layout(constraints.maxWidth, constraints.maxHeight) { child.place(rect.left.roundToInt(), rect.top.roundToInt()) }
    }
}

@Composable
internal fun Modifier.captureBridgeDock(transition: BridgeDockTransition?): Modifier {
    if (transition == null) return this
    val view = LocalView.current
    DisposableEffect(transition, view) { onDispose { transition.bounds = null } }
    return onGloballyPositioned { coordinates ->
        val screen = IntArray(2).also(view::getLocationOnScreen)
        val window = IntArray(2).also(view::getLocationInWindow)
        val position = coordinates.positionInWindow() + Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
        transition.bounds = Rect(position, androidx.compose.ui.geometry.Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
    }.drawWithContent {
        if (transition.owner == null || transition.owner?.handedOff == true) drawContent()
    }
}

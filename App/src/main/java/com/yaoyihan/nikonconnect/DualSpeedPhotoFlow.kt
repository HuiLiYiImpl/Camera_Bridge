package com.yaoyihan.nikonconnect

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

// 中段保持 1.5:1；临近边界按剩余距离分配位移，让两列自然收齐。
internal fun dualSpeedScrollStep(distance: Float, leftRemaining: Float, rightRemaining: Float, edgeDistance: Float): Pair<Float, Float> {
    val left = leftRemaining.coerceAtLeast(0f)
    val right = rightRemaining.coerceAtLeast(0f)
    val sum = left + right
    if (sum <= 0f || distance == 0f) return 0f to 0f
    val edge = (1f - minOf(left, right) / edgeDistance.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val blend = edge * edge * (3f - 2f * edge)
    val leftRate = 1.5f + (2.5f * left / sum - 1.5f) * blend
    val rightRate = 1f + (2.5f * right / sum - 1f) * blend
    return minOf(abs(distance) * leftRate, left) * distance.sign to
        minOf(abs(distance) * rightRate, right) * distance.sign
}

private fun LazyListState.remainingDistance(forward: Boolean): Float {
    if (!(if (forward) canScrollForward else canScrollBackward)) return 0f
    val info = layoutInfo
    val items = info.visibleItemsInfo
    val pitch = (items.map { it.size }.average().takeIf { it.isFinite() } ?: info.viewportSize.height.toDouble())
        .toFloat() + info.mainAxisItemSpacing
    if (!forward) return (firstVisibleItemIndex * pitch + firstVisibleItemScrollOffset).coerceAtLeast(1f)
    val last = items.lastOrNull() ?: return pitch.coerceAtLeast(1f)
    return ((info.totalItemsCount - last.index - 1) * pitch + last.offset + last.size +
        info.afterContentPadding - info.viewportEndOffset).coerceAtLeast(1f)
}

@Composable
internal fun DualSpeedPhotoFlow(
    itemCount: Int,
    key: (Int) -> Any,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    leftState: LazyListState = rememberLazyListState(),
    rightState: LazyListState = rememberLazyListState(),
    footer: (@Composable () -> Unit)? = null,
    itemContent: @Composable (Int) -> Unit,
) {
    val edgeDistance = with(LocalDensity.current) { 480.dp.toPx() }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollableState { drag ->
        val distance = -drag
        val (left, right) = dualSpeedScrollStep(distance,
            leftState.remainingDistance(distance > 0), rightState.remainingDistance(distance > 0), edgeDistance)
        val consumedLeft = leftState.dispatchRawDelta(left)
        val consumedRight = rightState.dispatchRawDelta(right)
        val fraction = maxOf(if (left != 0f) abs(consumedLeft / left) else 0f,
            if (right != 0f) abs(consumedRight / right) else 0f).coerceIn(0f, 1f)
        drag * fraction
    }
    val atEnd by remember { derivedStateOf { !leftState.canScrollForward && !rightState.canScrollForward } }
    fun position(): Float = listOf(leftState, rightState).sumOf { state ->
        val firstSize = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == state.firstVisibleItemIndex }?.size ?: 1
        (state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset.toFloat() / firstSize.coerceAtLeast(1)).toDouble()
    }.toFloat()
    val footerSpace = if (footer == null) 0.dp else 64.dp
    Box(modifier.clipToBounds()) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp).scrollable(scroll, Orientation.Vertical).semantics {
            verticalScrollAxisRange = ScrollAxisRange(value = { position() },
                maxValue = { if (atEnd) position() else itemCount.toFloat() })
            scrollBy { _, y -> scope.launch { scroll.scrollBy(-y) }; true }
        },
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(2) { column ->
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = if (column == 0) leftState else rightState,
                    userScrollEnabled = false, verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = contentPadding.calculateTopPadding(),
                        bottom = contentPadding.calculateBottomPadding() + footerSpace)) {
                    items(count = (itemCount + 1 - column) / 2, key = { key(it * 2 + column) }) { row ->
                        itemContent(row * 2 + column)
                    }
                }
            }
        }
        if (footer != null && atEnd) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .padding(bottom = contentPadding.calculateBottomPadding()).height(footerSpace), contentAlignment = Alignment.Center) { footer() }
    }
}

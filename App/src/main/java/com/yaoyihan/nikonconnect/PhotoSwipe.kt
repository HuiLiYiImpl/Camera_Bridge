package com.yaoyihan.nikonconnect

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
internal fun Modifier.photoSwipe(
    key: Any,
    enabled: Boolean,
    previous: (() -> Unit)?,
    next: (() -> Unit)?,
): Modifier {
    val onPrevious by rememberUpdatedState(previous)
    val onNext by rememberUpdatedState(next)
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    if (!enabled || (previous == null && next == null)) return this
    return pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var multiplePointers = false
            var swiping = false
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) multiplePointers = true
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!swiping && change.isConsumed) multiplePointers = true
                val distance = change.position.x - down.position.x
                val vertical = change.position.y - down.position.y
                if (!multiplePointers && abs(distance) > viewConfiguration.touchSlop && abs(distance) > abs(vertical)) {
                    swiping = true
                    change.consume()
                }
                if (!change.pressed) {
                    if (swiping && !multiplePointers) {
                        if (distance > threshold) onPrevious?.invoke()
                        else if (distance < -threshold) onNext?.invoke()
                    }
                    break
                }
            }
        }
    }
}

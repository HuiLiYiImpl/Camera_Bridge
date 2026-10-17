package com.yaoyihan.nikonconnect

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
internal fun Modifier.photoSwipe(
    key: Any,
    enabled: Boolean,
    previous: (() -> Unit)?,
    next: (() -> Unit)?,
    dismissDrag: ((Offset) -> Unit)? = null,
    dismissEnd: ((Float, Boolean) -> Unit)? = null,
): Modifier {
    val onPrevious by rememberUpdatedState(previous)
    val onNext by rememberUpdatedState(next)
    val onDismissDrag by rememberUpdatedState(dismissDrag)
    val onDismissEnd by rememberUpdatedState(dismissEnd)
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    if (!enabled || (previous == null && next == null && dismissDrag == null)) return this
    return onGloballyPositioned { coordinates[0] = it }.pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            fun rootPosition(position: Offset): Offset = coordinates[0]?.takeIf { it.isAttached }?.localToRoot(position) ?: position
            val start = rootPosition(down.position)
            var multiplePointers = false
            var axis = 0 // Lock once: horizontal browsing or downward dismissal.
            var ended = false
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, start)
            try { while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) multiplePointers = true
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val position = rootPosition(change.position)
                velocity.addPosition(change.uptimeMillis, position)
                if (axis == 0 && change.isConsumed) multiplePointers = true
                if (multiplePointers && axis == 2) {
                    onDismissEnd?.invoke(0f, true)
                    axis = 0
                }
                val distance = position.x - start.x
                val vertical = position.y - start.y
                if (!multiplePointers && axis == 0) {
                    if (abs(distance) > viewConfiguration.touchSlop && abs(distance) > abs(vertical)) axis = 1
                    else if (vertical > viewConfiguration.touchSlop && vertical > abs(distance) && onDismissDrag != null) axis = 2
                }
                if (!multiplePointers && axis != 0) {
                    change.consume()
                    if (axis == 2) onDismissDrag?.invoke(Offset(distance, vertical.coerceAtLeast(0f)))
                }
                if (!change.pressed) {
                    ended = true
                    if (axis == 1 && !multiplePointers) {
                        if (distance > threshold) onPrevious?.invoke()
                        else if (distance < -threshold) onNext?.invoke()
                    } else if (axis == 2) onDismissEnd?.invoke(velocity.calculateVelocity().y, multiplePointers)
                    break
                }
            } } finally { if (!ended && axis == 2) onDismissEnd?.invoke(0f, true) }
        }
    }
}

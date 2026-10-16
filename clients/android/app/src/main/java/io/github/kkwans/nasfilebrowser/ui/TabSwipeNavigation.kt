package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Children own consumed gestures; vertical scrolling and system edges keep priority. */
@Composable internal fun Modifier.swipeTabs(
    index: Int, pages: Int, enabled: Boolean = true, edge: Dp = 0.dp, onSelect: (Int) -> Unit,
): Modifier {
    val select by rememberUpdatedState(onSelect)
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    val edgePixels = with(LocalDensity.current) { edge.toPx() }
    return pointerInput(index, pages, enabled, edgePixels) {
        if (!enabled || pages < 2) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.x < edgePixels || down.position.x > size.width - edgePixels) return@awaitEachGesture
            var horizontal = false
            var distance = 0f
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) break
                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                if (pointer.isConsumed) break
                if (!pointer.pressed) {
                    if (horizontal && abs(distance) >= threshold) {
                        val target = index + if (distance < 0) 1 else -1
                        if (target in 0 until pages) select(target)
                    }
                    break
                }
                val delta = pointer.position - down.position
                if (!horizontal) {
                    if (maxOf(abs(delta.x), abs(delta.y)) <= viewConfiguration.touchSlop) continue
                    if (abs(delta.y) >= abs(delta.x)) break
                    val target = index + if (delta.x < 0) 1 else -1
                    if (target !in 0 until pages) break
                    horizontal = true
                }
                pointer.consume()
                distance = delta.x
            }
        }
    }
}


@Composable internal fun Modifier.secondaryClick(onOpen: () -> Unit): Modifier {
    val open by rememberUpdatedState(onOpen)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (currentEvent.buttons.isSecondaryPressed) { down.consume(); open() }
        }
    }
}

/*
 * Copyright 2026 Fileway contributors.
 *
 * Portions of the placement algorithm are adapted from
 * androidx.compose.material3.internal.DropdownMenuPositionProvider in the
 * AndroidX Jetpack Compose source tree. The original source is Copyright 2023
 * The Android Open Source Project and is licensed under the Apache License,
 * Version 2.0. The license text is available at:
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

/**
 * The placement candidates used by Material's DropdownMenu, for a Dialog
 * window whose top-left origin is controlled by WindowManager.
 *
 * The returned offset is relative to the same window as [anchorBounds]. Menu
 * size must be the measured, bounded content size; callers must not position a
 * menu from an unmeasured height.
 */
internal fun filewayMenuPosition(
    anchorBounds: IntRect,
    availableBounds: IntRect,
    menuSize: IntSize,
    layoutDirection: LayoutDirection,
): IntOffset {
    val maxX = (availableBounds.right - menuSize.width).coerceAtLeast(availableBounds.left)
    val maxY = (availableBounds.bottom - menuSize.height).coerceAtLeast(availableBounds.top)
    val xCandidates = if (layoutDirection == LayoutDirection.Ltr) {
        listOf(anchorBounds.left, anchorBounds.right - menuSize.width)
    } else {
        listOf(anchorBounds.right - menuSize.width, anchorBounds.left)
    }
    val x = xCandidates.firstOrNull { it in availableBounds.left..maxX }
        ?: xCandidates.first().coerceIn(availableBounds.left, maxX)
    val yCandidates = listOf(anchorBounds.bottom, anchorBounds.top - menuSize.height,
        anchorBounds.top - menuSize.height / 2, maxY)
    val y = yCandidates.firstOrNull { it in availableBounds.top..maxY }
        ?: anchorBounds.bottom.coerceIn(availableBounds.top, maxY)
    return IntOffset(x, y)
}

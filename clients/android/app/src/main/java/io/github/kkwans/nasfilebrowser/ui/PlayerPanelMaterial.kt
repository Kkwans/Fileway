package io.github.kkwans.nasfilebrowser.ui

import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.util.function.Consumer

/**
 * Android's cross-window compositor blurs live video, including SurfaceView.
 * RenderNode/Compose backdrop capture cannot capture that separate surface.
 * https://source.android.com/docs/core/display/window-blurs
 *
 * Blur-behind intentionally covers the underlying window, not the form text.
 * Keep the existing dialog/IME geometry and the player's Surface untouched.
 */
@Composable internal fun rememberPlayerPanelBlur(window: Window?): Boolean {
    val manager = remember(window) { window?.context?.getSystemService(WindowManager::class.java) }
    var enabled by remember(window) {
        mutableStateOf(Build.VERSION.SDK_INT >= 31 && manager?.isCrossWindowBlurEnabled == true)
    }
    val radius = with(LocalDensity.current) { 24.dp.roundToPx().coerceAtMost(120) }
    DisposableEffect(window, manager, radius) {
        if (window == null || manager == null || Build.VERSION.SDK_INT < 31) {
            onDispose { }
        } else {
            val oldFlags = window.attributes.flags
            val oldRadius = window.attributes.blurBehindRadius
            val oldDim = window.attributes.dimAmount
            var attached = true
            val listener = Consumer<Boolean> { available ->
                if (!attached) return@Consumer
                enabled = available
                val attributes = window.attributes
                attributes.flags = if (available) attributes.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    else attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                attributes.setBlurBehindRadius(if (available) radius else 0)
                attributes.dimAmount = if (available) .12f else .32f
                window.attributes = attributes
            }
            manager.addCrossWindowBlurEnabledListener(window.context.mainExecutor, listener)
            onDispose {
                attached = false
                manager.removeCrossWindowBlurEnabledListener(listener)
                val attributes = window.attributes
                attributes.flags = (attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()) or
                    (oldFlags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes.setBlurBehindRadius(oldRadius)
                attributes.dimAmount = oldDim
                window.attributes = attributes
            }
        }
    }
    return enabled
}

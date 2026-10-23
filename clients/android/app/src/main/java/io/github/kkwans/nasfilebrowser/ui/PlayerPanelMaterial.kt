package io.github.kkwans.nasfilebrowser.ui

import android.os.Build
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import java.util.function.Consumer

/**
 * Android's cross-window compositor blurs live video, including SurfaceView.
 * RenderNode/Compose backdrop capture cannot capture that separate surface.
 * https://source.android.com/docs/core/display/window-blurs
 *
 * The dialog window must match the panel bounds. Background blur affects only
 * that window; FLAG_BLUR_BEHIND would incorrectly blur the whole video.
 * The player's Surface remains untouched.
 */
@Composable internal fun rememberPlayerPanelBlur(
    window: Window?, landscape: Boolean, width: Int, height: Int, right: Int, top: Int,
): Boolean {
    val manager = remember(window) { window?.context?.getSystemService(WindowManager::class.java) }
    var enabled by remember(window) {
        mutableStateOf(Build.VERSION.SDK_INT >= 31 && manager?.isCrossWindowBlurEnabled == true)
    }
    val radius = with(LocalDensity.current) { 24.dp.roundToPx().coerceAtMost(120) }
    val corner = with(LocalDensity.current) { 12.dp.toPx() }
    val background = remember(window, corner) {
        GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = corner }
    }
    SideEffect {
        window?.let {
            WindowCompat.setDecorFitsSystemWindows(it, false)
            it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            val attributes = it.attributes
            attributes.gravity = if (landscape) Gravity.TOP or Gravity.END else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            attributes.x = if (landscape) right else 0
            // Floating windows' parent frame already excludes the IME. Adding
            // its height here would move the editor upward a second time.
            attributes.y = if (landscape) top else 0
            attributes.width = width
            attributes.height = if (landscape) height else WindowManager.LayoutParams.WRAP_CONTENT
            attributes.flags = attributes.flags and (WindowManager.LayoutParams.FLAG_BLUR_BEHIND or WindowManager.LayoutParams.FLAG_DIM_BEHIND).inv()
            attributes.dimAmount = 0f
            it.attributes = attributes
        }
    }
    DisposableEffect(window, manager, radius) {
        if (window == null || manager == null || Build.VERSION.SDK_INT < 31) {
            onDispose { }
        } else {
            val oldBackground = window.decorView.background
            window.setBackgroundDrawable(background)
            var attached = true
            val listener = Consumer<Boolean> { available ->
                if (!attached) return@Consumer
                enabled = available
                window.setBackgroundBlurRadius(if (available) radius else 0)
            }
            manager.addCrossWindowBlurEnabledListener(window.context.mainExecutor, listener)
            onDispose {
                attached = false
                manager.removeCrossWindowBlurEnabledListener(listener)
                window.setBackgroundBlurRadius(0)
                window.setBackgroundDrawable(oldBackground)
            }
        }
    }
    return enabled
}

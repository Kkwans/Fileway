package io.github.kkwans.nasfilebrowser.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme
import java.util.function.Consumer

/** Native compositor backdrop blur, restricted to the actual floating window.
 * Callers must size their Window to the surface, never use a fullscreen shell.
 * Includes SurfaceView; no frame capture or rendering-surface replacement.
 * https://source.android.com/docs/core/display/window-blurs
 */
@Composable internal fun rememberFilewayWindowBlur(window: Window?): Boolean {
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
            it.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND or WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            it.setDimAmount(0f)
        }
    }
    DisposableEffect(window, manager, radius, background) {
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

/** Compose once inside a Dialog, including inside Material's button slot. */
@Composable internal fun FilewayDialogGlass(onBlurEnabled: (Boolean) -> Unit) {
    val view = LocalView.current
    val enabled = rememberFilewayWindowBlur((view.parent as? DialogWindowProvider)?.window)
    val notify by rememberUpdatedState(onBlurEnabled)
    SideEffect { notify(enabled) }
}

@Composable internal fun filewayGlassColor(blurred: Boolean): ComposeColor {
    val surface = FilewayTheme.colors.surface
    return surface.copy(alpha = if (!blurred) 1f else if (surface.luminance() > .5f) .78f else .64f)
}

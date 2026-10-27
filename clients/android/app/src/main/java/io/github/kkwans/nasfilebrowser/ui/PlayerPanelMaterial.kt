package io.github.kkwans.nasfilebrowser.ui

import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat

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
    window: Window?, landscape: Boolean, width: Int, right: Int, top: Int,
): Boolean {
    SideEffect {
        window?.let {
            WindowCompat.setDecorFitsSystemWindows(it, false)
            val attributes = it.attributes
            attributes.flags = attributes.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            // Keep the bounded floating window, but remove WindowManager's separate
            // system-bar fitting. The panel body already owns its one safe inset.
            attributes.fitFilewayKeyboard(edgeToEdge = true)
            attributes.gravity = if (landscape) Gravity.TOP or Gravity.END else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            attributes.x = if (landscape) right else 0
            // Modern native fitting owns status/cutout/IME bounds; only legacy
            // windows still need the caller's top offset.
            attributes.y = if (landscape && android.os.Build.VERSION.SDK_INT < 30) top else 0
            attributes.width = width
            // Let the fitted parent constrain the Compose panel when IME opens.
            attributes.height = WindowManager.LayoutParams.WRAP_CONTENT
            it.attributes = attributes
        }
    }
    return rememberFilewayWindowBlur(window, bottomAttached = !landscape)
}

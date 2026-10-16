package io.github.kkwans.nasfilebrowser.ui

import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Applies to each focused player window, including its settings dialog.
 * Visibility is changed on entry/focus recovery, never on every recomposition:
 * an intentional edge swipe must remain free to reveal transient system bars.
 */
@Composable
internal fun PlayerWindowBars(window: Window?, immersive: Boolean, darkSurface: Boolean = true) {
    DisposableEffect(window, immersive, darkSurface) {
        val decor = window?.decorView
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val oldBehavior = controller?.systemBarsBehavior
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        fun apply() {
            controller ?: return
            controller.isAppearanceLightStatusBars = !darkSurface
            controller.isAppearanceLightNavigationBars = !darkSurface
            if (immersive) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else controller.show(WindowInsetsCompat.Type.systemBars())
        }
        apply()
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) apply() }
        decor?.viewTreeObserver?.addOnWindowFocusChangeListener(focus)
        onDispose {
            decor?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focus)
            if (immersive) controller?.show(WindowInsetsCompat.Type.systemBars())
            oldBehavior?.let { controller?.systemBarsBehavior = it }
            oldStatus?.let { controller?.isAppearanceLightStatusBars = it }
            oldNavigation?.let { controller?.isAppearanceLightNavigationBars = it }
        }
    }
}

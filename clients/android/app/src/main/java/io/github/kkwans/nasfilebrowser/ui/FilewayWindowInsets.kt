package io.github.kkwans.nasfilebrowser.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/** Fit the focused window against IME insets in WindowManager. Unlike an Activity
 * snapshot, this follows the dialog's keyboard without a resize/offset feedback loop. */
internal fun WindowManager.LayoutParams.fitFilewayKeyboard(edgeToEdge: Boolean) {
    @Suppress("DEPRECATION")
    val adjustment = if (Build.VERSION.SDK_INT <= 30) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
    softInputMode = (softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or adjustment
    if (Build.VERSION.SDK_INT >= 30) {
        val fitted = if (edgeToEdge) 0 else getFitInsetsTypes()
        setFitInsetsTypes(fitted or android.view.WindowInsets.Type.ime() or
            android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout() or
            android.view.WindowInsets.Type.captionBar())
    }
}

@Composable internal fun FilewayDialogKeyboardFit() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        window?.let {
            val attributes = it.attributes
            val previousMode = attributes.softInputMode
            val previousFits = if (Build.VERSION.SDK_INT >= 30) attributes.getFitInsetsTypes() else 0
            attributes.fitFilewayKeyboard(edgeToEdge = false)
            if (previousMode != attributes.softInputMode ||
                Build.VERSION.SDK_INT >= 30 && previousFits != attributes.getFitInsetsTypes()) it.attributes = attributes
        }
    }
}

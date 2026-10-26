package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.ComponentDialog
import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.graphics.Rect
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayPlayerPanelTheme
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme

private tailrec fun Context.feedbackActivity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.feedbackActivity()
    else -> null
}

/** A bounded compositor window. Set input flags BEFORE show so an ongoing gesture keeps its owner. */
@Composable internal fun PlayerFeedbackWindow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val owner = LocalContext.current.feedbackActivity() ?: return
    val parent = rememberCompositionContext()
    val latest by rememberUpdatedState(content)
    val margin = with(LocalDensity.current) { 12.dp.roundToPx() }
    var anchor by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    // ComponentDialog installs ViewTree lifecycle/saved-state owners before Compose attaches.
    val dialog = remember(owner) { ComponentDialog(owner).apply {
        window?.let { window ->
            window.requestFeature(android.view.Window.FEATURE_NO_TITLE)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.setDimAmount(0f)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.attributes = window.attributes.apply {
                gravity = Gravity.TOP or Gravity.LEFT
                windowAnimations = 0
                alpha = 0f // Never expose a default-position window before its first measurement.
                if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            }
        }
    } }
    // This slot is the picture centre for every feedback type, including timeline dragging.
    Box(modifier.size(1.dp).onGloballyPositioned {
        anchor = it.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
        dialog.window?.decorView?.invalidate()
    })
    DisposableEffect(dialog, parent) {
        val view = ComposeView(owner).apply {
            setParentCompositionContext(parent)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setContent {
                val blurred = rememberFilewayWindowBlur(dialog.window)
                FilewayPlayerPanelTheme(blurred) {
                    val shape = RoundedCornerShape(12.dp)
                    Box(Modifier.widthIn(max = 288.dp).clip(shape).background(FilewayTheme.status.mediaSurface)
                        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha=.20f), Color.White.copy(alpha=.06f))), shape)) { latest() }
                }
            }
        }
        dialog.setContentView(view)
        val window = dialog.window
        val decor = window?.decorView
        val decorOrigin = IntArray(2)
        val ownerOrigin = IntArray(2)
        // Same native-origin translation and pre-draw gate as FilewayMenu. Layout callbacks
        // alone run too late to prevent the default-position surface flashing on screen.
        val placement = ViewTreeObserver.OnPreDrawListener {
            val point = anchor
            if (window == null || decor == null || point == null || decor.width <= 0 || decor.height <= 0) {
                false
            } else {
                val safe = Rect().also(owner.window.decorView::getWindowVisibleDisplayFrame)
                owner.window.decorView.getLocationOnScreen(ownerOrigin)
                decor.getLocationOnScreen(decorOrigin)
                val desiredX = (ownerOrigin[0] + point.x.toInt() - decor.width / 2)
                    .coerceIn(safe.left + margin, (safe.right - decor.width - margin).coerceAtLeast(safe.left + margin))
                val desiredY = (ownerOrigin[1] + point.y.toInt() - decor.height / 2)
                    .coerceIn(safe.top + margin, (safe.bottom - decor.height - margin).coerceAtLeast(safe.top + margin))
                val attributes = window.attributes
                val x = desiredX - (decorOrigin[0] - attributes.x)
                val y = desiredY - (decorOrigin[1] - attributes.y)
                if (attributes.x != x || attributes.y != y) {
                    // Keep both the material and its buffer invisible until the native
                    // origin confirms the requested position on a subsequent pre-draw.
                    attributes.x = x; attributes.y = y; attributes.alpha = 0f
                    window.attributes = attributes
                    decor.requestLayout()
                    false
                } else if (attributes.alpha != 1f) {
                    attributes.alpha = 1f
                    window.attributes = attributes
                    decor.invalidate()
                    false
                } else true
            }
        }
        decor?.viewTreeObserver?.addOnPreDrawListener(placement)
        dialog.show()
        window?.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        onDispose {
            decor?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(placement)
            dialog.dismiss(); view.disposeComposition()
        }
    }
}

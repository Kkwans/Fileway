package io.github.kkwans.nasfilebrowser.ui

import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.graphics.Rect
import android.view.Gravity
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
@Composable internal fun PlayerFeedbackWindow(modifier: Modifier = Modifier, above: Boolean = false, content: @Composable () -> Unit) {
    val owner = LocalContext.current.feedbackActivity() ?: return
    val parent = rememberCompositionContext()
    val latest by rememberUpdatedState(content)
    val margin = with(LocalDensity.current) { 12.dp.roundToPx() }
    var anchor by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    val dialog = remember(owner) { Dialog(owner).apply {
        window?.let { window ->
            window.requestFeature(android.view.Window.FEATURE_NO_TITLE)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.setDimAmount(0f)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.attributes = window.attributes.apply { gravity = Gravity.TOP or Gravity.LEFT; windowAnimations = 0 }
        }
    } }
    fun position() {
        val point = anchor ?: return
        val window = dialog.window ?: return
        val safe = Rect().also(owner.window.decorView::getWindowVisibleDisplayFrame)
        val width = window.decorView.width
        val height = window.decorView.height
        val maxX = (safe.right - width - margin).coerceAtLeast(safe.left + margin)
        val maxY = (safe.bottom - height - margin).coerceAtLeast(safe.top + margin)
        window.attributes = window.attributes.apply {
            x = (point.x.toInt() - width / 2).coerceIn(safe.left + margin, maxX)
            y = (point.y.toInt() - if (above) height + margin else height / 2).coerceIn(safe.top + margin, maxY)
        }
    }
    Box(modifier.size(1.dp).onGloballyPositioned {
        anchor = it.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
        position()
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
                        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha=.20f), Color.White.copy(alpha=.06f))), shape)
                        .onGloballyPositioned { position() }) { latest() }
                }
            }
        }
        dialog.setContentView(view)
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        position()
        onDispose { dialog.dismiss(); view.disposeComposition() }
    }
    SideEffect { position() }
}

package io.github.kkwans.nasfilebrowser.ui

import android.view.Gravity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme

/** One object's foreground. The details owner survives while its child window is shown. */
internal enum class FileOperationDestination { DETAILS, RENAME, TAGS, CHECKSUM, FAVORITE, TRASH }
internal class FileOperationForeground {
    var onClosed: () -> Unit = {}
    fun close() { onClosed(); onClosed = {} }
    var destination by mutableStateOf(FileOperationDestination.DETAILS)
        private set
    var revision by mutableIntStateOf(0)
        private set
    var returnAction by mutableStateOf<String?>(null)
        private set
    fun open(destination: FileOperationDestination, action: String) {
        returnAction = action
        this.destination = destination
        revision++
    }
    fun back() { destination = FileOperationDestination.DETAILS; revision++ }
}
internal val LocalFileOperationForeground = staticCompositionLocalOf<FileOperationForeground?> { null }

@Composable internal fun FileOperationSheet(
    title: String,
    onDismiss: () -> Unit,
    canDismiss: Boolean = true,
    back: Boolean = false,
    footer: (@Composable RowScope.() -> Unit)? = null,
    stableHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) = FileOperationSurface(title, onDismiss, canDismiss, back, sheet = true, footer, stableHeight, content)

@Composable internal fun FileOperationDialog(
    title: String,
    onDismiss: () -> Unit,
    canDismiss: Boolean = true,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) = FileOperationSurface(title, onDismiss, canDismiss, back = false, sheet = false, footer, false, content)

/** Operation windows are bounded native floating dialogs. The body owns its one scroll container. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun FileOperationSurface(
    title: String, onDismiss: () -> Unit, canDismiss: Boolean, back: Boolean, sheet: Boolean,
    footer: (@Composable RowScope.() -> Unit)?, stableHeight: Boolean, content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val configuration = LocalConfiguration.current
    val hostSize = LocalWindowInfo.current.containerSize
    val safeDrawing = WindowInsets.safeDrawing
    val safeLeft = safeDrawing.getLeft(density, layoutDirection)
    val safeRight = safeDrawing.getRight(density, layoutDirection)
    val safeTop = safeDrawing.getTop(density)
    val safeBottom = safeDrawing.getBottom(density)
    val imeBottom = WindowInsets.ime.getBottom(density)
    val hostWidth = hostSize.width.takeIf { it > 0 } ?: with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val hostHeight = hostSize.height.takeIf { it > 0 } ?: with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val horizontalMargin = with(density) { if (sheet) 0.dp.roundToPx() else 24.dp.roundToPx() }
    val maxWidth = with(density) { (if (sheet) 640.dp else 440.dp).roundToPx() }
    val surfaceWidth = (hostWidth - safeLeft - safeRight - horizontalMargin * 2)
        .coerceAtLeast(1)
        .coerceAtMost(maxWidth)
    val surfaceHeight = (hostHeight - safeTop - maxOf(safeBottom, imeBottom)).coerceAtLeast(1)
    val surfaceWidthDp = with(density) { surfaceWidth.toDp() }
    val surfaceHeightDp = with(density) { surfaceHeight.toDp() }
    // Freeze the first complete framework measurement, not the eventual network result.
    // Width/inset/font changes get a fresh layout; data changes only scroll inside it.
    var firstHeight by remember(surfaceWidth, surfaceHeight, density.fontScale, density.density) { mutableStateOf<Int?>(null) }
    val heldHeight = firstHeight?.let { with(density) { it.toDp() } }
    Dialog(onDismissRequest = { if (canDismiss) onDismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = true, dismissOnBackPress = false,
        dismissOnClickOutside = canDismiss,
    )) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val view = LocalView.current
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        var blurred by remember { mutableStateOf(false) }
        FilewayDialogGlass { blurred = it }
        val glassColor = filewayGlassColor(blurred)
        val lightNavigation = sheet && glassColor.luminance() > .5f
        SideEffect {
            dialogWindow?.let { window ->
                val attributes = window.attributes
                attributes.gravity = if (sheet) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
                attributes.x = 0
                // The parent frame is already inset by the IME. Adding imeBottom
                // here would move a floating window twice.
                attributes.y = 0
                attributes.width = surfaceWidth
                attributes.height = WindowManager.LayoutParams.WRAP_CONTENT
                attributes.flags = attributes.flags and
                    (WindowManager.LayoutParams.FLAG_BLUR_BEHIND or WindowManager.LayoutParams.FLAG_DIM_BEHIND).inv()
                attributes.dimAmount = 0f
                window.attributes = attributes
            }
        }
        DisposableEffect(view, lightNavigation) {
            // Dialog owns a separate Window; Activity edge-to-edge flags do not apply here.
            dialogWindow?.let {
                @Suppress("DEPRECATION")
                it.navigationBarColor = android.graphics.Color.TRANSPARENT
                it.isNavigationBarContrastEnforced = false
                WindowCompat.getInsetsController(it, view).isAppearanceLightNavigationBars = lightNavigation
            }
            onDispose { /* Only this dialog's window was changed; it is destroyed on close. */ }
        }
        val imeVisible = WindowInsets.isImeVisible
        var imeDismissRequested by remember { mutableStateOf(false) }
        LaunchedEffect(imeVisible) {
            if (imeVisible && ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
                imeDismissRequested = false
        }
        fun leave() { if (canDismiss) { focus.clearFocus(); keyboard?.hide(); onDismiss() } }
        fun requestBack() {
            val nativeIme = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())
            if (nativeIme == true || imeVisible && !imeDismissRequested) {
                imeDismissRequested = true; focus.clearFocus(); keyboard?.hide()
            } else leave()
        }
        BackHandler { requestBack() }
        Surface(
            Modifier.requiredWidth(surfaceWidthDp)
                .then(if (stableHeight && heldHeight != null) Modifier.height(minOf(heldHeight, surfaceHeightDp)) else Modifier.heightIn(max = surfaceHeightDp))
                .onSizeChanged { if (stableHeight && firstHeight == null && it.height > 0) firstHeight = it.height }
                .filewayGlassFrame(if (sheet) RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp) else RoundedCornerShape(12.dp)),
            color = glassColor,
            shape = if (sheet) RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp) else RoundedCornerShape(12.dp),
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(start = if (back) 4.dp else 20.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (back) FilewayIconButton(::requestBack, enabled = canDismiss, modifier = Modifier.size(48.dp)) {
                        FilewayIcon(R.drawable.ic_arrow_back, "返回文件详情", Modifier.size(22.dp))
                    }
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (!back) FilewayIconButton(::leave, enabled = canDismiss, modifier = Modifier.size(48.dp)) {
                        FilewayIcon(R.drawable.ic_close, "关闭$title", Modifier.size(22.dp))
                    }
                }
                Column(Modifier.weight(1f, fill = stableHeight && firstHeight != null).fillMaxWidth().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                if (footer != null) {
                    HorizontalDivider(Modifier.padding(top = 12.dp), color = FilewayTheme.colors.outlineVariant)
                    Row(Modifier.fillMaxWidth()
                        .then(if (sheet) Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically,
                        content = footer)
                } else {
                    Spacer(Modifier.height(12.dp))
                    if (sheet) Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
    }
}

/** Full last component first; the selectable full path never loses its target to ellipsis. */
@Composable internal fun FileOperationLocation(label: String, path: String, showBasename: Boolean = true) {
    FilewayGlassSection(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = FilewayTheme.text.metadata, color = FilewayTheme.colors.onSurfaceVariant)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showBasename) Text(path.trimEnd('/').substringAfterLast('/').ifEmpty { "根目录" }, style = MaterialTheme.typography.titleMedium)
                    Text(path, style = FilewayTheme.text.metadata, color = FilewayTheme.colors.onSurfaceVariant)
                }
            }
        }
    }
}

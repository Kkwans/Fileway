package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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
    footer: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) = FileOperationSurface(title, onDismiss, canDismiss, back, sheet = true, footer, content)

@Composable internal fun FileOperationDialog(
    title: String,
    onDismiss: () -> Unit,
    canDismiss: Boolean = true,
    footer: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) = FileOperationSurface(title, onDismiss, canDismiss, back = false, sheet = false, footer, content)

/** Detail panel anchored below the status bar; shares body/footer and dismissal rules. */
@Composable internal fun FileOperationTopSheet(title: String, onDismiss: () -> Unit,
    footer: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit,
) = FileOperationSurface(title, onDismiss, canDismiss = true, back = false, sheet = false, footer, content, top = true)

/** Only operation windows consume system/IME insets. The body owns its one scroll container. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun FileOperationSurface(
    title: String, onDismiss: () -> Unit, canDismiss: Boolean, back: Boolean, sheet: Boolean,
    footer: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit, top: Boolean = false,
) {
    Dialog(onDismissRequest = { if (canDismiss) onDismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false,
        dismissOnClickOutside = canDismiss,
    )) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val view = LocalView.current
        val lightNavigation = sheet && FilewayTheme.colors.surface.luminance() > .5f
        DisposableEffect(view, lightNavigation) {
            // Dialog owns a separate Window; Activity edge-to-edge flags do not apply here.
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.let {
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
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(
                (if (sheet) WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal) else WindowInsets.safeDrawing)
                    .union(WindowInsets.ime))
                .pointerInput(canDismiss) { detectTapGestures { leave() } },
            contentAlignment = if (top) Alignment.TopCenter else if (sheet) Alignment.BottomCenter else Alignment.Center,
        ) {
            Surface(
                Modifier.padding(start = if (sheet) 0.dp else 24.dp, end = if (sheet) 0.dp else 24.dp,
                    top = 12.dp, bottom = if (sheet) 0.dp else 12.dp)
                    .widthIn(max = if (sheet) 640.dp else 440.dp).fillMaxWidth()
                    .heightIn(max = (maxHeight - (if (sheet) 12.dp else 24.dp)).coerceAtLeast(0.dp))
                    .pointerInput(Unit) { detectTapGestures { /* The surface consumes taps on its empty space. */ } },
                color = FilewayTheme.colors.surface,
                shape = if (sheet) RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp) else RoundedCornerShape(12.dp),
                tonalElevation = 0.dp,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (back) IconButton(::requestBack, enabled = canDismiss, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_arrow_back), "返回文件详情", Modifier.size(22.dp))
                        }
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        if (!back) IconButton(::leave, enabled = canDismiss, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_close), "关闭$title", Modifier.size(22.dp))
                        }
                    }
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                    HorizontalDivider(Modifier.padding(top = 12.dp), color = FilewayTheme.colors.outlineVariant)
                    Row(Modifier.fillMaxWidth()
                        .then(if (sheet) Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically,
                        content = footer)
                }
            }
        }
    }
}

/** Full last component first; the selectable full path never loses its target to ellipsis. */
@Composable internal fun FileOperationLocation(label: String, path: String) {
    Surface(color = FilewayTheme.colors.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = FilewayTheme.text.metadata, color = FilewayTheme.colors.onSurfaceVariant)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(path.trimEnd('/').substringAfterLast('/').ifEmpty { "根目录" }, style = MaterialTheme.typography.titleMedium)
                    Text(path, style = FilewayTheme.text.metadata, color = FilewayTheme.colors.onSurfaceVariant)
                }
            }
        }
    }
}

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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme

/** One object's foreground. The details owner survives while its child window is shown. */
internal enum class FileOperationDestination { DETAILS, RENAME, TAGS, CHECKSUM, FAVORITE, TRASH }
internal class FileOperationForeground {
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

/** Only operation windows consume system/IME insets. The body owns its one scroll container. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun FileOperationSurface(
    title: String, onDismiss: () -> Unit, canDismiss: Boolean, back: Boolean, sheet: Boolean,
    footer: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = { if (canDismiss) onDismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false,
        dismissOnClickOutside = canDismiss,
    )) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val view = LocalView.current
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
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                .pointerInput(canDismiss) { detectTapGestures { leave() } },
            contentAlignment = if (sheet) Alignment.BottomCenter else Alignment.Center,
        ) {
            Surface(
                Modifier.padding(horizontal = if (sheet) 0.dp else 24.dp, vertical = 12.dp)
                    .widthIn(max = if (sheet) 640.dp else 440.dp).fillMaxWidth()
                    .heightIn(max = (maxHeight - 24.dp).coerceAtLeast(0.dp))
                    .pointerInput(Unit) { detectTapGestures { /* The surface consumes taps on its empty space. */ } },
                color = FilewayTheme.colors.surface,
                shape = RoundedCornerShape(if (sheet) 20.dp else 12.dp),
                tonalElevation = 0.dp,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        IconButton(if (back) ::requestBack else ::leave, enabled = canDismiss, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(if (back) R.drawable.ic_arrow_back else R.drawable.ic_close),
                                if (back) "返回文件详情" else "关闭$title", Modifier.size(22.dp))
                        }
                    }
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                    HorizontalDivider(Modifier.padding(top = 12.dp), color = FilewayTheme.colors.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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

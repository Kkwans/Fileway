package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ShellController
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

@Composable internal fun ShellScreen(controller: ShellController, onClose: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var menu by remember(state.scope) { mutableStateOf(false) }
    var copyMessage by remember(state.scope) { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val ime = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var following by remember(state.scope) { mutableStateOf(true) }
    var location by remember(state.scope) { mutableStateOf(false) }
    fun leave() { controller.close(); onClose() }
    fun back() { if (ime) { keyboard?.hide(); focus.clearFocus() } else leave() }
    BackHandler { back() }
    LaunchedEffect(list, state.scope) {
        snapshotFlow { Triple(list.isScrollInProgress, list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1, list.layoutInfo.totalItemsCount) }
            .collect { (scrolling, last, total) -> if (scrolling) following = total == 0 || last >= total - 1 }
    }
    LaunchedEffect(state.output.lines.size, state.remoteDropped, state.output.dropped) {
        if (following && state.output.lines.isNotEmpty()) list.scrollToItem(state.output.lines.lastIndex)
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()) {
            FilewayTopAppBar("运行命令", null, R.drawable.ic_arrow_back, "关闭命令输出", ::back, actions = {
                IconButton(controller::refresh, enabled = !state.running && !state.loading) { Icon(painterResource(R.drawable.ic_refresh), "刷新命令权限") }
            })
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("工作目录 · ${libraryBasename(state.directory.path)}", Modifier.weight(1f), style = FilewayTheme.text.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton({ location = true }) { Text("完整路径") }
            }
            Text("单次非交互命令；断开输出后，服务端进程可能仍在运行。", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton({ menu = true }, enabled = state.commands.isNotEmpty() && !state.running && !state.loading) { Text("选择允许的命令") }
                    DropdownMenu(menu, { menu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                        state.commands.take(256).forEach { command -> DropdownMenuItem({ Text(command) }, { menu = false; controller.input(command + " ") }) }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(controller::clearOutput, enabled = !state.running && state.output.lines.isNotEmpty()) { Text("清空显示") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(state.input, controller::input, Modifier.weight(1f), label = { Text("命令及参数") }, singleLine = true, enabled = !state.running && !state.loading,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
                Button(controller::run, enabled = !state.running && !state.loading && state.enabled && state.execute && state.commands.isNotEmpty() && state.input.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("运行") }
            }
            state.configurationMessage?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            (state.error?.takeUnless { it == state.configurationMessage } ?: state.message)?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = if (state.error == null || state.error == state.configurationMessage) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
            if (state.running || state.loading) TextButton(controller::disconnect, modifier = Modifier.padding(horizontal = 8.dp)) { Text("断开输出") }
            val dropped = state.remoteDropped + state.output.dropped
            if (dropped > 0) Text("$dropped 行较早输出超出显示缓冲，已省略。", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize().semantics { contentDescription = "命令输出内容" }, state = list, contentPadding = PaddingValues(16.dp)) {
                    itemsIndexed(state.output.lines) { _, line -> Text(line, Modifier.fillMaxWidth(), style = FilewayTheme.text.technical) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({
                    copyMessage = try { clipboard.setText(AnnotatedString(state.output.lines.joinToString("\n"))); "已复制当前显示的输出" }
                        catch (_: Exception) { "无法复制，请选择需要的输出文字" }
                }, enabled = state.output.lines.isNotEmpty()) { Text("复制输出") }
                if (!following && state.output.lines.isNotEmpty()) TextButton({ following = true; scope.launch { list.scrollToItem(state.output.lines.lastIndex) } }) { Text("回到最新") }
                copyMessage?.let { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    if (location) FileOperationDialog("工作目录", { location = false }, footer = {
        TextButton({ location = false }) { Text("关闭") }
    }) { FileOperationLocation("运行位置", state.directory.path) }
}

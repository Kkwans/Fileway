package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.DocumentEditController
import io.github.kkwans.nasfilebrowser.app.FileCategory
import io.github.kkwans.nasfilebrowser.data.documentLineEnding
import io.github.kkwans.nasfilebrowser.data.isMarkdownDocument
import io.github.kkwans.nasfilebrowser.data.isCsvDocument
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar

@Composable internal fun DocumentEditorScreen(controller: DocumentEditController, onClose: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val file = state.file ?: return
    val document = state.document ?: return
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    var more by remember(state.scope, file.wirePath) { mutableStateOf(false) }
    var discard by remember(state.scope, file.wirePath) { mutableStateOf(false) }
    var reload by remember(state.scope, file.wirePath) { mutableStateOf(false) }
    var copied by remember(state.scope, file.wirePath) { mutableStateOf<String?>(null) }
    val busy = state.saving || state.loading
    val monospace = FileCategory.CODE.matches(file.name, false) || FileCategory.CONFIG.matches(file.name, false) || isMarkdownDocument(file) || isCsvDocument(file)
    LaunchedEffect(state.draft) { copied = null }
    val ending = remember(document) { documentLineEnding(document.text).label }
    val saveStatus = when {
        state.unknownWrite && state.acknowledged -> " · 已确认保存，后续待恢复"
        state.unknownWrite -> " · 保存结果待核对"
        state.dirty -> " · 未保存"
        else -> ""
    }
    fun leave() {
        if (ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
            focus.clearFocus(); keyboard?.hide(); return
        }
        if (busy) return
        if (state.dirty || state.unknownWrite) discard = true
        else { controller.close(); onClose() }
    }
    BackHandler { leave() }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()) {
            FilewayTopAppBar(file.name, saveStatus.trimStart(' ', '·'), R.drawable.ic_arrow_back, "关闭文本编辑", ::leave, actions = {
                FilewayTextButton(controller::save, enabled = !busy && state.dirty && !state.unknownWrite && !state.conflict,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.saving) "正在保存" else "保存") }
                Box {
                    IconButton({ more = true }) { Icon(painterResource(R.drawable.ic_more_actions), "编辑操作") }
                    FilewayMenu(more, { more = false }, items = listOf(
                        FilewayMenuEntry("copy", "复制草稿", R.drawable.ic_copy, enabled = state.draft.length <= 131_072),
                        FilewayMenuEntry("reload", "重新读取服务器版本", R.drawable.ic_refresh, enabled = !busy && !state.unknownWrite),
                    )) { action ->
                        more = false
                        when (action.key) {
                            "copy" -> copied = try { clipboard.setText(AnnotatedString(state.draft)); "已复制草稿" } catch (_: Exception) { "无法复制，请选择文字后复制" }
                            "reload" -> {
                                focus.clearFocus(); keyboard?.hide()
                                if (state.dirty) reload = true else controller.reload()
                            }
                        }
                    }
                }
            })
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${document.encoding}${if (document.bom) " BOM" else ""} · $ending", Modifier.weight(1f).padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                copied?.let { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            (state.error ?: state.notice)?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.unknownWrite) FilewayTextButton(controller::verifySave, enabled = !busy, modifier = Modifier.padding(horizontal = 8.dp)) { Text(if (state.acknowledged) "核对并恢复刷新" else "核对保存结果") }
            OutlinedTextField(state.draft, controller::edit, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                label = { Text("文本内容") }, readOnly = busy || state.unknownWrite,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
        }
    }
    if (discard) FilewayAlertDialog(onDismissRequest = { discard = false }, title = { Text(
        if (state.unknownWrite && state.acknowledged) "退出已确认保存的文档？" else if (state.unknownWrite) "退出待核对的保存？" else "放弃未保存的更改？") },
        text = { Text(if (state.unknownWrite && state.acknowledged) "服务器已确认保存，后续内容核对或本地刷新尚未完成。当前草稿会丢弃，请重新打开服务器文件核对。"
            else if (state.unknownWrite) "服务器可能已经保存。返回后请重新读取核对，当前草稿会丢弃。" else "当前草稿会丢弃，服务器文件不会改变。") },
        confirmButton = { FilewayTextButton({ discard = false; controller.close(discard = true); onClose() }) { Text("丢弃草稿并返回") } },
        dismissButton = { FilewayTextButton({ discard = false }) { Text(if (state.unknownWrite) "保留草稿" else "继续编辑") } })
    if (reload) FilewayAlertDialog(onDismissRequest = { reload = false }, title = { Text("丢弃草稿并重新读取？") },
        text = { Text("将读取服务器当前版本并替换编辑器中的草稿。需要保留的内容请先复制。") },
        confirmButton = { FilewayTextButton({ reload = false; controller.reload() }) { Text("丢弃草稿并读取") } },
        dismissButton = { FilewayTextButton({ reload = false }) { Text("取消") } })
}

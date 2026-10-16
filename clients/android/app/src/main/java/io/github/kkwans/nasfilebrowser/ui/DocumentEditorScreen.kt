package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.DocumentEditController
import io.github.kkwans.nasfilebrowser.data.documentLineEnding
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar

@Composable internal fun DocumentEditorScreen(controller: DocumentEditController, onClose: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val file = state.file ?: return
    val document = state.document ?: return
    val clipboard = LocalClipboardManager.current
    var discard by remember(state.scope, file.wirePath) { mutableStateOf(false) }
    var reload by remember(state.scope, file.wirePath) { mutableStateOf(false) }
    var copied by remember(state.scope, file.wirePath) { mutableStateOf<String?>(null) }
    val busy = state.saving || state.loading
    val ending = remember(document) { documentLineEnding(document.text).label }
    val saveStatus = when {
        state.unknownWrite && state.acknowledged -> " · 已确认保存，后续待恢复"
        state.unknownWrite -> " · 保存结果待核对"
        state.dirty -> " · 未保存"
        else -> ""
    }
    fun leave() {
        if (busy) return
        if (state.dirty || state.unknownWrite) discard = true
        else { controller.close(); onClose() }
    }
    BackHandler { leave() }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()) {
            FilewayTopAppBar(file.name, saveStatus.trimStart(' ', '·'), R.drawable.ic_arrow_back, "关闭文本编辑", ::leave, actions = {
                TextButton(controller::save, enabled = !busy && state.dirty && !state.unknownWrite && !state.conflict,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.saving) "正在保存" else "保存") }
            })
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${document.encoding}${if (document.bom) " BOM" else ""} · $ending$saveStatus", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({
                    copied = try { clipboard.setText(AnnotatedString(state.draft)); "已复制草稿" } catch (_: Exception) { "无法复制，请选择文字后复制" }
                }, enabled = state.draft.length <= 131_072) { Text("复制草稿") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            (state.error ?: state.notice)?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            copied?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.unknownWrite) TextButton(controller::verifySave, enabled = !busy, modifier = Modifier.padding(horizontal = 8.dp)) { Text(if (state.acknowledged) "核对并恢复刷新" else "核对保存结果") }
            else TextButton({ if (state.dirty) reload = true else controller.reload() }, enabled = !busy, modifier = Modifier.padding(horizontal = 8.dp)) { Text("重新读取服务器版本") }
            OutlinedTextField(state.draft, controller::edit, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                label = { Text("文本内容") }, readOnly = busy || state.unknownWrite, textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(
        if (state.unknownWrite && state.acknowledged) "退出已确认保存的文档？" else if (state.unknownWrite) "退出待核对的保存？" else "放弃未保存的更改？") },
        text = { Text(if (state.unknownWrite && state.acknowledged) "服务器已确认保存，后续内容核对或本地刷新尚未完成。当前草稿会丢弃，请重新打开服务器文件核对。"
            else if (state.unknownWrite) "服务器可能已经保存。返回后请重新读取核对，当前草稿会丢弃。" else "当前草稿会丢弃，服务器文件不会改变。") },
        confirmButton = { TextButton({ discard = false; controller.close(discard = true); onClose() }) { Text("丢弃草稿并返回") } },
        dismissButton = { TextButton({ discard = false }) { Text(if (state.unknownWrite) "保留草稿" else "继续编辑") } })
    if (reload) AlertDialog(onDismissRequest = { reload = false }, title = { Text("丢弃草稿并重新读取？") },
        text = { Text("将读取服务器当前版本并替换编辑器中的草稿。需要保留的内容请先复制。") },
        confirmButton = { TextButton({ reload = false; controller.reload() }) { Text("丢弃草稿并读取") } },
        dismissButton = { TextButton({ reload = false }) { Text("取消") } })
}

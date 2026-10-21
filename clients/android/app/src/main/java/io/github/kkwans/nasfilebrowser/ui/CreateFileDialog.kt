package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.app.DocumentEditController
import io.github.kkwans.nasfilebrowser.data.DirectoryCrumb
import io.github.kkwans.nasfilebrowser.data.createdDocumentTarget
import io.github.kkwans.nasfilebrowser.data.renameNameError

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CreateFileDialog(controller: DocumentEditController, onVerifyDirectory: (DirectoryCrumb) -> Unit, onClose: () -> Unit = {}) {
    val state by controller.state.collectAsStateWithLifecycle()
    val draft = state.creation ?: return
    val busy = state.saving || state.loading
    val nameError = renameNameError(draft.name)
    val target = remember(draft.parent, draft.name) { runCatching { createdDocumentTarget(draft.parent, draft.name) }.getOrNull() }
    fun dismiss() { if (!busy && !state.unknownWrite) { controller.closeCreation(); onClose() } }
    FileOperationSheet("新建文件", ::dismiss, canDismiss = !busy && !state.unknownWrite, footer = {
        if (!state.unknownWrite) {
            FilewayTextButton(::dismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
            FilewayButton(controller::create, enabled = !busy && nameError == null && target != null, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.saving) "正在创建" else "创建文件") }
        }
    }) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FileOperationLocation("创建位置", draft.parent.path)
                Text("UTF-8 · 同名文件或文件夹不会被覆盖", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.unknownWrite) {
                    SelectionContainer { Text("${if (state.acknowledged) "已确认创建" else "待核对文件"}：${draft.target?.path ?: draft.name}", style = MaterialTheme.typography.bodyMedium) }
                    Text(if (state.acknowledged) "服务器已确认创建，后续核对或本地刷新尚未完成。面板保留本次名称和内容，不会重复创建。"
                        else "请先核对结果，面板保留本次名称和内容，不会重复提交。", style = MaterialTheme.typography.bodyMedium)
                    FilewayTextButton(controller::verifyCreation, enabled = !busy) { Text(if (state.acknowledged) "核对并恢复刷新" else "核对创建结果") }
                    FilewayTextButton({ controller.closeCreation(verifyUnknown = true); onVerifyDirectory(draft.parent); onClose() }, enabled = !busy) { Text(if (state.acknowledged) "返回原目录查看" else "返回原目录核对") }
                } else {
                    OutlinedTextField(draft.name, controller::createName, modifier = Modifier.fillMaxWidth(), label = { Text("文件名") }, singleLine = true,
                        enabled = !busy, isError = draft.name.isNotEmpty() && nameError != null,
                        supportingText = { Text(if (draft.name.isNotEmpty() && nameError != null) nameError else "例如 notes.txt 或 README.md") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                    target?.let { SelectionContainer { Text("将创建：${it.path}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    OutlinedTextField(draft.content, controller::createContent, modifier = Modifier.fillMaxWidth(), label = { Text("初始内容（可留空）") },
                        enabled = !busy, minLines = 4, maxLines = 8, textStyle = MaterialTheme.typography.bodyMedium)
                }
            }
    }
}

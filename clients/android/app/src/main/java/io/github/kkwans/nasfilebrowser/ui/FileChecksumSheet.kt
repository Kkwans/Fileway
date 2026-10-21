package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.app.FileChecksumController
import io.github.kkwans.nasfilebrowser.data.ChecksumAlgorithm
import io.github.kkwans.nasfilebrowser.ui.designsystem.*

@Composable internal fun FileChecksumSheet(controller: FileChecksumController, onDismiss: () -> Unit = controller::close, back: Boolean = false) {
    val state by controller.state.collectAsStateWithLifecycle()
    val file = state.file ?: return
    val clipboard = LocalClipboardManager.current
    var copied by remember(state.result) { mutableStateOf(false) }
    val digestStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    FileOperationSheet("文件校验", onDismiss, back = back, footer = {
        FilewayTextButton(onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (back) "返回详情" else "关闭") }
        FilewayButton(if (state.busy) controller::cancel else controller::calculate, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (state.busy) "取消计算" else if (state.result != null) "重新计算" else "开始计算")
        }
    }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SelectionContainer { Text(file.name, style = FilewayTheme.text.fileName) }
            Text("在服务器读取并计算摘要，不下载文件内容。大文件需要更长时间。", style = FilewayTheme.text.metadata, color = FilewayTheme.colors.onSurfaceVariant)
            Surface(shape = MaterialTheme.shapes.small, color = FilewayTheme.colors.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(4.dp)) {
                    ChecksumAlgorithm.entries.forEach { algorithm ->
                        Surface(Modifier.weight(1f).heightIn(min = 48.dp)
                            .selectable(state.algorithm == algorithm, enabled = !state.busy, role = Role.RadioButton) { controller.algorithm(algorithm) },
                            color = if (state.algorithm == algorithm) FilewayTheme.colors.primaryContainer else FilewayTheme.colors.surfaceContainerLow,
                            contentColor = if (state.algorithm == algorithm) FilewayTheme.colors.onPrimaryContainer else FilewayTheme.colors.onSurfaceVariant,
                            shape = MaterialTheme.shapes.small) {
                            Box(Modifier.padding(horizontal = 4.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                                Text(algorithm.label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
            OutlinedTextField(state.expected, controller::expected, Modifier.fillMaxWidth(), label = { Text("预期校验值（可选）") },
                textStyle = digestStyle, minLines = 2, maxLines = 4, isError = state.expectedInvalid,
                supportingText = { Text(if (state.expectedInvalid) "请输入 ${state.algorithm.digits} 位十六进制值" else "粘贴已有校验值可直接比较") })
            if (state.busy) FilewayStatusBanner("正在计算 ${state.algorithm.label}…", FilewayStatusKind.LOADING)
            state.error?.let { FilewayStatusBanner(it, FilewayStatusKind.ERROR) }
            state.result?.let { digest ->
                OutlinedTextField(digest, {}, Modifier.fillMaxWidth(), label = { Text("实际校验值") },
                    readOnly = true, textStyle = digestStyle, minLines = 2, maxLines = 4)
                state.matches?.let { matches -> FilewayStatusBanner(if (matches) "校验值一致" else "校验值不一致",
                    if (matches) FilewayStatusKind.SUCCESS else FilewayStatusKind.ERROR) }
                FilewayOutlinedButton({ clipboard.setText(AnnotatedString(digest)); copied = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (copied) "已复制" else "复制校验值")
                }
            }
        }
    }
}

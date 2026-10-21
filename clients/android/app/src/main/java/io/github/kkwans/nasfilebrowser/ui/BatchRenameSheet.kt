package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.FileOperationsController
import io.github.kkwans.nasfilebrowser.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun BatchRenameSheet(controller: FileOperationsController, onVerify: (DirectoryCrumb) -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val draft = state.batchRename ?: return
    val rows = remember(draft.files, draft.options, draft.overrides) { draft.rows }
    val changed = rows.count { it.changed }
    val enabled = !state.changing && !draft.unknownExecution
    var rules by remember(state.scope) { mutableStateOf(false) }
    var confirm by remember(state.scope, draft.options, draft.overrides, draft.reviewed) { mutableStateOf(false) }
    val previewScroll = rememberLazyListState()
    val names = remember(draft.files) { draft.files.groupingBy { it.name }.eachCount() }
    if (!confirm) FileOperationSheet("批量重命名", { controller.closeBatchRename() }, canDismiss = enabled, footer = {
        if (draft.unknownExecution) FilewayButton({ controller.closeBatchRename(verifyUnknown = true); onVerify(draft.parent) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("刷新原目录核对并关闭") }
        else {
            FilewayTextButton({ controller.closeBatchRename() }, enabled = !state.changing, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
            key(draft.reviewed, state.changing) {
                FilewayButton({ if (draft.reviewed) confirm = true else controller.checkBatchRename() },
                    enabled = enabled && rows.none { it.error != null } && changed > 0, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (state.changing) { if (draft.reviewed) "正在重命名" else "正在检查" }
                        else if (draft.reviewed) "确认重命名 $changed 项" else "检查变更")
                }
            }
        }
    }) {
            Text("已选 ${draft.files.size} 项 · $changed 项待修改", style = MaterialTheme.typography.bodyMedium)
            if (state.changing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (draft.unknownExecution) {
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(min = 72.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { SelectionContainer { Text(draft.parent.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    item { draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
                    item { Text("此批操作已锁定提交。刷新后请检查实际名称，再重新选择文件开始新的操作。", style = MaterialTheme.typography.bodyMedium) }
                }
            } else {
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(min = 96.dp).semantics { contentDescription = "批量重命名变更预览" }, state = previewScroll,
                    verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FileOperationLocation("原目录", draft.parent.path)
                            draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("命名规则", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Box {
                                    FilewayTextButton({ rules = true }, enabled = enabled) { Text(draft.options.rule.label) }
                                    DropdownMenu(rules, { rules = false }) {
                                        BatchRenameRule.entries.forEach { rule -> DropdownMenuItem({ Text(rule.label) }, {
                                            rules = false; controller.batchRenameOptions(draft.options.copy(rule = rule))
                                        }) }
                                    }
                                }
                            }
                            when (draft.options.rule) {
                                BatchRenameRule.PREFIX, BatchRenameRule.SUFFIX -> OutlinedTextField(draft.options.text,
                                    { controller.batchRenameOptions(draft.options.copy(text = it)) }, modifier = Modifier.fillMaxWidth(),
                                    label = { Text(if (draft.options.rule == BatchRenameRule.PREFIX) "前缀" else "后缀（扩展名前）") }, enabled = enabled, singleLine = true)
                                BatchRenameRule.REPLACE -> {
                                    OutlinedTextField(draft.options.search, { controller.batchRenameOptions(draft.options.copy(search = it)) },
                                        modifier = Modifier.fillMaxWidth(), label = { Text("查找文字") }, enabled = enabled, singleLine = true)
                                    OutlinedTextField(draft.options.replacement, { controller.batchRenameOptions(draft.options.copy(replacement = it)) },
                                        modifier = Modifier.fillMaxWidth(), label = { Text("替换为（留空表示删除）") }, enabled = enabled, singleLine = true)
                                }
                                BatchRenameRule.NUMBER -> {
                                    OutlinedTextField(draft.options.text, { controller.batchRenameOptions(draft.options.copy(text = it)) },
                                        modifier = Modifier.fillMaxWidth(), label = { Text("基础名称") }, enabled = enabled, singleLine = true)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        OutlinedTextField(draft.options.start, { controller.batchRenameOptions(draft.options.copy(start = it)) }, modifier = Modifier.weight(1f),
                                            label = { Text("起始序号") }, enabled = enabled, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                        OutlinedTextField(draft.options.padding, { controller.batchRenameOptions(draft.options.copy(padding = it)) }, modifier = Modifier.weight(1f),
                                            label = { Text("补零位数（1–8）") }, enabled = enabled, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                    }
                                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(draft.options.preserveExtension, enabled = enabled, role = Role.Checkbox) {
                                        controller.batchRenameOptions(draft.options.copy(preserveExtension = it))
                                    }, verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(draft.options.preserveExtension, null, enabled = enabled)
                                        Text("保留扩展名", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Text("按打开面板时的文件列表顺序编号。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text("按预览中的完整新名称保存；规则即时更新，逐项输入会保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (draft.overrides.isNotEmpty()) FilewayTextButton(controller::resetBatchRenameNames, enabled = enabled) { Text("取消逐项调整，重新应用规则") }
                            HorizontalDivider()
                            Text(if (draft.reviewed) "检查通过 · 请核对新名称" else "变更预览 · 尚未执行", style = MaterialTheme.typography.titleMedium)
                            Text("未修改项目会跳过；同名冲突不会覆盖。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    itemsIndexed(rows, key = { _, row -> batchRenameSourceWire(row.file) }) { index, row ->
                        val wire = batchRenameSourceWire(row.file)
                        val error = row.error ?: draft.serverErrors[wire]
                        val serverName = draft.reviewedChanges.firstOrNull { batchRenameSourceWire(it.file) == wire }?.target?.name
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(painterResource(if (row.file.directory) R.drawable.ic_folder else R.drawable.ic_edit), null,
                                    Modifier.padding(top = 2.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                SelectionContainer { Text("${index + 1}. ${row.file.name}", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium) }
                            }
                            if ((names[row.file.name] ?: 0) > 1 || resourceWireBytes(wire).toString(Charsets.UTF_8) != row.file.path)
                                SelectionContainer { Text("原始路径：$wire", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            OutlinedTextField(row.newName, { controller.batchRenameName(wire, it) }, modifier = Modifier.fillMaxWidth(),
                                label = { Text("第 ${index + 1} 项的新名称") }, enabled = enabled, isError = error != null,
                                supportingText = { Text(error ?: if (!row.changed) "跳过：名称没有变化" else if (serverName != null && serverName != row.newName) "服务端实际名称：$serverName" else "新名称") })
                            HorizontalDivider()
                        }
                    }
                }
            }
    }
    if (confirm && draft.reviewed && !draft.unknownExecution) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("执行这 $changed 项重命名？") },
        text = { Text("将一次提交预览中的全部变更。同名冲突会停止执行，已有内容不会被覆盖。名称变更会同步到当前账号的服务端记录。") },
        confirmButton = { FilewayTextButton({ confirm = false; controller.executeBatchRename() }, enabled = enabled) { Text("确认执行") } },
        dismissButton = { FilewayTextButton({ confirm = false }) { Text("返回预览") } })
}

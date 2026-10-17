package io.github.kkwans.nasfilebrowser.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.upload.UploadConflict

@Composable internal fun UploadSelectionDialog(model: ClientModel) {
    val state by model.uploads.state.collectAsStateWithLifecycle()
    if (!state.selecting || !state.busy && state.drafts.isEmpty() && state.error == null) return
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val files = state.drafts.filter { it.existingIdentity == null || it.choice != UploadConflict.SKIP }
    AlertDialog(onDismissRequest = { if (!state.busy) model.uploads.cancelSelection() },
        title = { Text(if (state.busy && state.drafts.isEmpty()) "读取本机文件" else "上传到服务器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.targetLabel, style = MaterialTheme.typography.bodySmall)
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (state.drafts.isEmpty()) "已找到 ${state.scanned} 个文件" else "正在建立上传任务", style = MaterialTheme.typography.bodySmall)
                }
                if (state.drafts.isNotEmpty()) {
                    Text("${files.size} 个文件 · ${readableSize(files.sumOf { it.source.size })}", style = MaterialTheme.typography.titleMedium)
                    LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.drafts, key = { it.targetWire }) { item ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.source.name, style = MaterialTheme.typography.bodyMedium)
                                if (item.source.relativeDirectory.isNotEmpty()) Text(item.source.relativeDirectory, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(readableSize(item.source.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (item.existingIdentity != null) {
                                    Text("服务器已有同名${if (item.existingIdentity == "directory") "文件夹" else "文件"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    UploadConflict.entries.forEach { choice ->
                                        val allowed = !state.busy && (choice != UploadConflict.REPLACE || state.canReplace && item.existingIdentity != "directory")
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(item.choice == choice, { model.uploads.conflict(item.source.uri, choice) }, enabled = allowed)
                                            Text(choice.label, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Text("本机原文件保留。上传绑定当前服务器和账号，之后切换浏览连接不会改变这些任务的目标。", style = MaterialTheme.typography.bodySmall)
                    if (files.any { it.existingIdentity != null && it.choice == UploadConflict.REPLACE }) Text("已选择覆盖同名文件，原服务器文件内容将被替换。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                FilewayToast(state.notice)
            }
        }, confirmButton = {
            TextButton({
                model.uploads.submit()
                if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }, enabled = !state.busy && state.drafts.isNotEmpty()) { Text(if (state.error == null) "开始上传" else "添加剩余上传") }
        }, dismissButton = {
            TextButton({ model.uploads.cancelSelection() }, enabled = !state.busy) { Text("取消") }
        })
}

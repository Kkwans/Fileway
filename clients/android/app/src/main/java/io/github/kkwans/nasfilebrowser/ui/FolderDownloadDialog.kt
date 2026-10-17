package io.github.kkwans.nasfilebrowser.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.app.ClientModel

@Composable internal fun FolderDownloadDialog(model: ClientModel) {
    val state by model.downloads.state.collectAsStateWithLifecycle()
    if (!state.folderRequest) return
    val plan = state.folderPlan
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    FileOperationSheet(if (state.preparing) "读取文件夹" else "下载到本机", model.downloads::cancelFolderDownloads,
        canDismiss = !state.busy || state.preparing, footer = {
            TextButton(model.downloads::cancelFolderDownloads, enabled = !state.busy || state.preparing, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
            if (plan != null) Button({
                model.downloads.confirmFolderDownloads()
                if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }, enabled = !state.busy && plan.entries.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.error == null) "开始下载" else "添加剩余下载") }
        }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.preparing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("已找到 ${state.scannedFiles} 个文件。确认前不会建立下载任务。")
            } else if (plan != null) {
                Text("${plan.entries.size} 个文件 · ${readableSize(plan.bytes)}", style = MaterialTheme.typography.titleMedium)
                plan.roots.take(6).forEach { Text(it.name, style = MaterialTheme.typography.bodyMedium) }
                if (plan.roots.size > 6) Text("另有 ${plan.roots.size - 6} 项", style = MaterialTheme.typography.bodySmall)
                Text("保留文件夹层级。图片和视频可以直接在 App 中打开，下载中的视频支持边下边播。", style = MaterialTheme.typography.bodySmall)
                FileOperationLocation("保存位置", downloadDirectoryLabel(state.tree))
                if (plan.emptyDirectories > 0) Text("${plan.emptyDirectories} 个空文件夹不建立下载记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                FilewayToast(state.notice)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } else state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

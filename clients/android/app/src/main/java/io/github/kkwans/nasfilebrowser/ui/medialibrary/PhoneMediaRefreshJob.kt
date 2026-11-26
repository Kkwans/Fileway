package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.data.MediaLibraryRepository
import io.github.kkwans.nasfilebrowser.data.MediaFailureKind
import io.github.kkwans.nasfilebrowser.data.MediaMetadataRefreshJob
import io.github.kkwans.nasfilebrowser.data.MediaMetadataRefreshJobResult
import io.github.kkwans.nasfilebrowser.data.MediaMetadataRefreshLibraryPreview
import io.github.kkwans.nasfilebrowser.data.MediaMetadataRefreshUndoResult
import io.github.kkwans.nasfilebrowser.data.MediaOperation
import io.github.kkwans.nasfilebrowser.data.MediaReadResult
import io.github.kkwans.nasfilebrowser.data.MediaWriteResult
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySpace
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun PhoneMediaRefreshJobSection(repository: MediaLibraryRepository, libraryId: String,
    preview: MediaMetadataRefreshLibraryPreview?, dirty: Boolean, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    var job by remember(repository, libraryId) { mutableStateOf<MediaMetadataRefreshJob?>(null) }
    var pending by remember(repository, libraryId) { mutableStateOf<MediaOperation?>(null) }
    var busy by remember(repository, libraryId) { mutableStateOf(false) }
    var error by remember(repository, libraryId) { mutableStateOf<String?>(null) }
    var confirmUndo by remember(repository, libraryId) { mutableStateOf(false) }
    LaunchedEffect(repository, libraryId) {
        pending = repository.pendingMetadataRefreshJobOperation(libraryId)
        when (val result = repository.latestMetadataRefreshJob(libraryId)) {
            is MediaReadResult.Success -> if (result.scope == repository.scope) job = result.value
            is MediaReadResult.Failure -> if (result.scope == repository.scope && result.error.kind != MediaFailureKind.NOT_FOUND) {
                error = providerProblem(result.error)
            }
        }
    }
    LaunchedEffect(repository, libraryId, job?.jobId) {
        var current = job
        while (current != null && current.status in setOf("queued", "running") && isActive) {
            delay(1_500)
            when (val result = repository.metadataRefreshJob(libraryId, current.jobId)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    current = result.value; job = current
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    error = providerProblem(result.error); break
                }
            }
        }
    }
    suspend fun poll(id: String) {
        when (val result = repository.metadataRefreshJob(libraryId, id)) {
            is MediaReadResult.Success -> if (result.scope == repository.scope) job = result.value
            is MediaReadResult.Failure -> if (result.scope == repository.scope) error = providerProblem(result.error)
        }
    }
    fun refreshProgress() {
        val current = job ?: return
        if (busy || !enabled) return
        busy = true; error = null
        scope.launch {
            try { poll(current.jobId) } finally { busy = false }
        }
    }
    fun submit(block: suspend () -> MediaWriteResult<Any>) {
        if (busy || !enabled) return
        busy = true; error = null
        scope.launch {
            try {
                when (val result = block()) {
                    is MediaWriteResult.Success -> if (result.scope == repository.scope) {
                        repository.forgetResolvedOperation(result.operation); pending = null
                        val id = when (val value = result.value) {
                            is MediaMetadataRefreshJob -> value.jobId
                            is MediaMetadataRefreshJobResult -> value.jobId
                            is MediaMetadataRefreshUndoResult -> value.jobId
                            else -> null
                        }
                        if (id != null) poll(id)
                    }
                    is MediaWriteResult.Failure -> if (result.scope == repository.scope) {
                        pending = result.operation.takeIf { result.error.mutationMayHaveCommitted }
                        error = providerProblem(result.error)
                    }
                }
            } finally { busy = false }
        }
    }
    fun start() {
        val currentPreview = preview ?: return
        if (busy || !enabled || dirty || !currentPreview.canRefresh || currentPreview.languageMismatchItems == 0) return
        val operation = pending ?: repository.newOperation()
        submit { if (pending != null) repository.retryOperation(operation) else repository.enqueueMetadataRefreshJob(operation, libraryId, currentPreview.policyRevision) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FilewaySpace.small)) {
        if (busy) MediaLoading("正在处理资料刷新")
        pending?.let {
            MediaProblemCard(MediaProblem("刷新操作待确认", "确认上次操作，避免重复提交。", "确认"), {
                submit { repository.retryOperation(it) }
            })
        }
        if (dirty) Text("请先保存资料设置，再开始刷新。", style = PhoneMediaType.secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        job?.let { current ->
            Text(refreshJobStatusLabel(current), style = PhoneMediaType.itemTitle)
            Text("共 ${current.total} 条；已刷新 ${current.refreshed}，已是目标资料 ${current.alreadyCurrent}，保留 ${current.preserved}，失败 ${current.failed}，待处理 ${current.pending}。",
                style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            current.failureCode.takeIf(String::isNotBlank)?.let { Text("原因：$it", style = PhoneMediaType.secondary,
                color = MaterialTheme.colorScheme.error) }
            when {
                current.status in setOf("queued", "running") -> Button(onClick = {
                    submit { repository.cancelMetadataRefreshJob(repository.newOperation(), libraryId, current.jobId, current.generation) }
                }, enabled = enabled && !busy && pending == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消刷新", style = PhoneMediaType.action) }
                current.status in setOf("failed", "canceled") || current.status == "completed" && current.failed > 0 -> Button(onClick = {
                    submit { repository.retryMetadataRefreshJob(repository.newOperation(), libraryId, current.jobId, current.generation) }
                }, enabled = enabled && !busy && pending == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重试刷新", style = PhoneMediaType.action) }
                current.refreshed > 0 && current.undone == 0L -> TextButton(onClick = { confirmUndo = true },
                    enabled = enabled && !busy && pending == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("撤销本次刷新", style = PhoneMediaType.action) }
                current.undone > 0 -> Text("本次刷新已撤销。", style = PhoneMediaType.body)
            }
            if (current.status == "completed" && current.failed == 0L && preview != null && preview.canRefresh && preview.languageMismatchItems > 0) {
                TextButton(onClick = ::start, enabled = enabled && !busy && !dirty && pending == null,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("开始新一轮刷新", style = PhoneMediaType.action) }
            }
            TextButton(onClick = ::refreshProgress, enabled = enabled && !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("刷新进度", style = PhoneMediaType.action) }
        } ?: run {
            if (preview != null) Button(onClick = ::start, enabled = enabled && !dirty && preview.canRefresh && preview.languageMismatchItems > 0 && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("开始刷新资料", style = PhoneMediaType.action) }
            if (preview?.languageMismatchItems == 0) Text("没有需要刷新的条目。", style = PhoneMediaType.secondary)
        }
        error?.let { message ->
            if (preview == null && pending == null && job == null) Text(message, style = PhoneMediaType.secondary,
                color = MaterialTheme.colorScheme.error)
            else MediaProblemCard(MediaProblem("资料刷新未完成", message, "重试"), {
                when {
                    pending != null -> submit { repository.retryOperation(requireNotNull(pending)) }
                    job != null -> refreshProgress()
                    else -> start()
                }
            })
        }
    }
    if (confirmUndo) AlertDialog(
        onDismissRequest = { confirmUndo = false }, title = { Text("撤销本次资料刷新？") },
        text = { Text("将恢复刷新前的 TMDB 文档与审计；人工修改、锁字段和观看记录会保留。若条目已有后续修改，整批撤销会被拒绝。") },
        confirmButton = { TextButton(onClick = {
            confirmUndo = false
            job?.let { current -> submit { repository.undoMetadataRefreshJob(repository.newOperation(), libraryId, current.jobId, current.generation) } }
        }) { Text("撤销刷新") } },
        dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text("取消") } },
    )
}

internal fun refreshJobStatusLabel(job: MediaMetadataRefreshJob): String = when (job.status) {
    "queued" -> "等待刷新"
    "running" -> "正在刷新"
    "completed" -> if (job.failed > 0) "刷新完成，部分条目失败" else "刷新完成"
    "failed" -> "刷新失败"
    "canceled" -> "刷新已取消"
    "interrupted" -> "刷新已中断"
    else -> "刷新状态未知"
}

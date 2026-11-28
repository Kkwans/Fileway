package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.serverTime
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PhoneMediaScanHistory(
    repository: MediaLibraryRepository,
    libraryId: String,
    reviewScanId: String?,
    onSelect: (String?) -> Unit,
    onBack: () -> Unit,
    onReviewItem: (MediaCatalogRef) -> Unit,
    onCatalogChanged: () -> Unit,
    reviewInitiallyOpen: Boolean = false,
    onBrowse: () -> Unit,
) {
    var scans by remember(repository, libraryId) { mutableStateOf(emptyList<MediaScan>()) }
    var next by remember(repository, libraryId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<MediaFailure?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun load(cursor: String? = null) {
        busy = true
        problem = null
        try {
            when (val result = repository.listScans(libraryId, cursor = cursor)) {
                is MediaReadResult.Success -> {
                    scans = (if (cursor == null) result.value.items else scans + result.value.items).distinctBy { it.id }
                    next = result.value.nextCursor
                }
                is MediaReadResult.Failure -> {
                    if (result.error.invalidatesVisibleData()) { scans = emptyList(); next = null }
                    problem = result.error
                }
            }
        } finally { busy = false }
    }
    LaunchedEffect(repository, libraryId, reviewScanId) {
        if (reviewScanId == null) load()
    }
    if (reviewScanId != null) {
        PhoneMediaScanProgress(repository, libraryId, reviewScanId, onBack, { onSelect(null) }, onReviewItem, onCatalogChanged,
            reviewInitiallyOpen = reviewInitiallyOpen, onBrowse = onBrowse)
    } else {
        ScanHistoryPage("扫描记录", onBack, refresh = { scope.launch { load() } }, busy = busy) {
            if (problem != null) item {
                MediaProblemCard(MediaProblem("无法读取扫描记录", providerProblem(problem!!), "刷新"), { scope.launch { load() } })
            }
            if (busy && scans.isEmpty()) item { MediaLoading("正在读取扫描记录") }
            if (!busy && problem == null && scans.isEmpty()) item { Text("这里还没有扫描任务。") }
            items(scans, key = { it.id }) { scan ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    MediaSettingsAction(scan.sourceLabel ?: "媒体文件夹",
                        mediaScanHistorySubtitle(scan), R.drawable.ic_folder,
                        navigation = false, trailingText = mediaScanHistoryActionLabel(scan.status)) { onSelect(scan.id) }
                }
            }
            if (next != null) item {
                TextButton(onClick = { val cursor = next; scope.launch { load(cursor) } }, enabled = !busy) { Text("加载更多") }
            }
        }
    }
}

@Composable
private fun PhoneMediaScanProgress(repository: MediaLibraryRepository, libraryId: String, scanId: String,
    onBack: () -> Unit, onHistory: () -> Unit, onReviewItem: (MediaCatalogRef) -> Unit, onCatalogChanged: () -> Unit,
    reviewInitiallyOpen: Boolean = false, onBrowse: () -> Unit) {
    var scan by remember(repository, scanId) { mutableStateOf<MediaScan?>(null) }
    var metadata by remember(repository, scanId) { mutableStateOf<MediaMetadataJob?>(null) }
    var problem by remember(repository, scanId) { mutableStateOf<String?>(null) }
    var reviewProblem by remember(repository, scanId) { mutableStateOf<String?>(null) }
    var items by remember(repository, scanId) { mutableStateOf(emptyList<MediaMetadataReviewItem>()) }
    var next by remember(repository, scanId) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var changing by remember { mutableStateOf(false) }
    var paging by remember { mutableStateOf(false) }
    var reviewVisible by rememberSaveable(scanId) { mutableStateOf(reviewInitiallyOpen) }
    val scope = rememberCoroutineScope()
    suspend fun loadReview(cursor: String? = null) {
        paging = true; reviewProblem = null
        try {
            when (val result = repository.metadataReview(libraryId, scanId, cursor = cursor)) {
                is MediaReadResult.Success -> {
                    items = (if (cursor == null) result.value.items else items + result.value.items).distinctBy { it.ref }
                    next = result.value.nextCursor
                }
                is MediaReadResult.Failure -> {
                    if (result.error.invalidatesVisibleData()) { items = emptyList(); next = null }
                    reviewProblem = providerProblem(result.error)
                }
            }
        } finally { paging = false }
    }
    LaunchedEffect(repository, scanId, refresh) {
        busy = true; problem = null
        try {
            do {
                val previous = scan
                when (val result = repository.getScan(libraryId, scanId)) {
                    is MediaReadResult.Success -> scan = result.value
                    is MediaReadResult.Failure -> {
                        if (result.error.invalidatesVisibleData()) { scan = null; metadata = null; items = emptyList(); next = null }
                        problem = providerProblem(result.error)
                        break
                    }
                }
                if (scan?.status == MediaScanStatus.COMPLETED) {
                    if (previous?.status != MediaScanStatus.COMPLETED) onCatalogChanged()
                    when (val result = repository.metadataJob(libraryId, scanId)) {
                        is MediaReadResult.Success -> {
                            val old = metadata
                            metadata = result.value
                            if ((old?.matched ?: 0) < result.value.matched) onCatalogChanged()
                        }
                        is MediaReadResult.Failure -> {
                            if (result.error.kind == MediaFailureKind.NOT_FOUND || result.error.invalidatesVisibleData()) {
                                metadata = null; items = emptyList(); next = null
                            }
                            if (result.error.kind != MediaFailureKind.NOT_FOUND) problem = providerProblem(result.error)
                        }
                    }
                }
                busy = false
                if (problem != null || (scan?.status !in activeScanStates && metadata?.status !in activeScanStates)) break
                delay(2_500)
            } while (true)
        } finally { busy = false }
    }
    val reviewCount = metadata?.review ?: 0
    LaunchedEffect(repository, scanId, reviewVisible, reviewCount, refresh) {
        if (reviewVisible && metadata != null) loadReview()
    }
    val resultTitle = if (scan?.status == MediaScanStatus.COMPLETED) "扫描结果" else "扫描进度"
    ScanHistoryPage(resultTitle, onBack, refresh = { refresh++ }, busy = busy || changing) {
        scan?.let { entry -> item {
            MediaSettingsGroup(if (entry.status == MediaScanStatus.COMPLETED) "目录扫描结果" else "目录扫描") {
                ScanProgressText(directoryStatus(entry.status), "${mediaScanModeLabel(entry.mode)} · 已处理 ${entry.processed} 项")
                mediaDirectoryScanAction(entry.status)?.let { action ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            changing = true
                            scope.launch {
                                val operation = repository.newOperation()
                                try {
                                    val result = if (action == MediaDirectoryScanAction.CANCEL) {
                                        repository.cancelScan(operation, libraryId, scanId, entry.generation)
                                    } else repository.retryScan(operation, libraryId, scanId, entry.generation)
                                    when (result) {
                                        is MediaWriteResult.Success -> if (result.scope == repository.scope) {
                                            repository.forgetResolvedOperation(operation)
                                            refresh++
                                        }
                                        is MediaWriteResult.Failure -> if (result.scope == repository.scope) {
                                            if (!result.error.mutationMayHaveCommitted) repository.forgetResolvedOperation(operation)
                                            problem = if (result.error.mutationMayHaveCommitted) "操作结果请以刷新后的状态为准。"
                                                else providerProblem(result.error)
                                        }
                                    }
                                } finally { changing = false }
                            }
                        }, enabled = !changing && !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (changing) "正在处理…" else if (action == MediaDirectoryScanAction.CANCEL) "取消扫描" else "重试扫描",
                                style = PhoneMediaType.action)
                        }
                    }
                }
                if (entry.status == MediaScanStatus.COMPLETED && metadata == null) {
                    MediaSettingsAction("浏览已收录影片", "返回媒体库并筛选当前媒体库", R.drawable.ic_library, action = onBrowse)
                }
                if (metadata == null) MediaSettingsAction("历史记录", "仅查看本次结果时，可在这里打开过往扫描", R.drawable.ic_search, action = onHistory)
            }
        } }
        metadata?.let { job -> item {
            MediaSettingsGroup("本次扫描结果") {
                MediaScanResultSummaryCard(job)
                if (job.status in activeScanStates || job.status in setOf(MediaScanStatus.FAILED, MediaScanStatus.CANCELED)) {
                    val cancel = job.status in activeScanStates
                    TextButton(onClick = {
                        changing = true
                        scope.launch {
                            try {
                                when (val result = repository.changeMetadataJob(libraryId, scanId, job.generation, cancel)) {
                                    is MediaReadResult.Success -> refresh++
                                    is MediaReadResult.Failure -> problem = "操作结果请以刷新后的状态为准。"
                                }
                            } finally { changing = false }
                        }
                    }, enabled = !changing && !busy, modifier = Modifier.padding(horizontal = 8.dp)) { Text(if (cancel) "取消补全" else "重试补全") }
                }
                if (reviewCount > 0 && !reviewVisible) MediaSettingsAction("待确认", "$reviewCount 项记录，打开仍待匹配的影片",
                    R.drawable.ic_search, action = { reviewVisible = true })
                if (scan?.status == MediaScanStatus.COMPLETED) MediaSettingsAction("浏览已收录影片", "返回媒体库查看本次扫描结果",
                    R.drawable.ic_library, action = onBrowse)
                MediaSettingsAction("历史记录", "查看过往扫描", R.drawable.ic_search, action = onHistory)
            }
        } }
        if (busy && scan == null) item { MediaLoading("正在读取扫描进度") }
        problem?.let { message -> item { MediaProblemCard(MediaProblem("无法更新状态", message, "刷新"), { refresh++ }) } }
        if (reviewVisible) {
            item { MediaHeading("待确认") }
            if (items.isEmpty() && !paging && reviewProblem == null) item { Text("没有仍待确认的影片。") }
            reviewProblem?.let { error -> item {
                MediaProblemCard(MediaProblem("无法读取待确认条目", error, "重试"), { scope.launch { loadReview() } })
            } }
            items(items, key = { it.ref.itemId }) { item ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    MediaSettingsAction(item.title.ifBlank { "待确认影片" }, reviewReason(item.reason), R.drawable.ic_video) { onReviewItem(item.ref) }
                }
            }
            if (next != null) item {
                TextButton(onClick = { val cursor = next; scope.launch { loadReview(cursor) } }, enabled = !paging) { Text("加载更多") }
            }
        }
    }
}

@Composable
private fun ScanHistoryPage(title: String, onBack: () -> Unit, refresh: () -> Unit, busy: Boolean,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        FilewayTopAppBar(title, leadingIcon = R.drawable.ic_arrow_back, leadingDescription = "返回", onLeadingClick = onBack, actions = {
            TextButton(onClick = refresh, enabled = !busy) { Text("刷新") }
        })
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
private fun ScanProgressText(title: String, details: String) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = PhoneMediaType.itemTitle)
        Text(details, style = PhoneMediaType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal data class MediaScanResultSummary(
    val total: Long, val completed: Long, val matched: Long, val review: Long,
    val skipped: Long, val failed: Long, val pending: Long,
)

internal fun mediaScanResultSummary(job: MediaMetadataJob): MediaScanResultSummary {
    val total = job.matched + job.review + job.skipped + job.failed + job.pending
    return MediaScanResultSummary(total, total - job.pending, job.matched, job.review, job.skipped, job.failed, job.pending)
}

@Composable
private fun MediaScanResultSummaryCard(job: MediaMetadataJob) {
    val summary = mediaScanResultSummary(job)
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(metadataStatus(job), style = PhoneMediaType.itemTitle)
                Text("已完成 ${summary.completed} / ${summary.total} 项", style = PhoneMediaType.secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (summary.pending > 0) Text("待处理 ${summary.pending}", style = PhoneMediaType.action,
                color = MaterialTheme.colorScheme.primary)
        }
        if (summary.total > 0) LinearProgressIndicator(
            progress = { summary.completed.toFloat() / summary.total.toFloat() },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.small),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScanResultStat("已匹配", summary.matched, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            ScanResultStat("待确认", summary.review, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
            ScanResultStat("已跳过", summary.skipped, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
            ScanResultStat("失败", summary.failed, MaterialTheme.colorScheme.error, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ScanResultStat(label: String, value: Long, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value.toString(), style = PhoneMediaType.sectionTitle, color = color)
        Text(label, style = PhoneMediaType.badge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

internal fun mediaScanModeLabel(mode: MediaScanMode): String = when (mode) {
    MediaScanMode.INCREMENTAL -> "增量扫描"
    MediaScanMode.FULL_FILL -> "全量补齐"
    MediaScanMode.FULL_COVER -> "全量覆盖"
}

internal fun mediaScanModeDescription(mode: MediaScanMode): String = when (mode) {
    MediaScanMode.INCREMENTAL -> "复用未变化视频的技术信息，只处理新增或变化内容；不会移除已消失的条目。"
    MediaScanMode.FULL_FILL -> "遍历全部目录补齐新增和变化内容；不会移除已消失的条目。"
    MediaScanMode.FULL_COVER -> "遍历全部目录更新内容，并将确认已消失的条目标记为缺失。"
}

internal enum class MediaDirectoryScanAction { CANCEL, RETRY }

internal fun mediaDirectoryScanAction(status: MediaScanStatus): MediaDirectoryScanAction? = when (status) {
    MediaScanStatus.QUEUED, MediaScanStatus.RUNNING -> MediaDirectoryScanAction.CANCEL
    MediaScanStatus.FAILED, MediaScanStatus.CANCELED, MediaScanStatus.INTERRUPTED -> MediaDirectoryScanAction.RETRY
    else -> null
}

private val activeScanStates = setOf(MediaScanStatus.QUEUED, MediaScanStatus.RUNNING, MediaScanStatus.INTERRUPTED)
internal fun mediaScanHistoryActionLabel(status: MediaScanStatus): String =
    if (status == MediaScanStatus.COMPLETED) "查看结果" else "查看进度"

internal fun mediaScanHistorySubtitle(scan: MediaScan): String = buildList {
    add(directoryStatus(scan.status))
    add(mediaScanModeLabel(scan.mode))
    add("已处理 ${scan.processed} 项")
    if (scan.updatedAt > 0) add(serverTime(scan.updatedAt))
}.joinToString(" · ")

private fun directoryStatus(status: MediaScanStatus) = when (status) {
    MediaScanStatus.QUEUED -> "扫描排队中"
    MediaScanStatus.RUNNING -> "正在扫描"
    MediaScanStatus.COMPLETED -> "扫描完成"
    MediaScanStatus.FAILED -> "扫描失败"
    MediaScanStatus.CANCELED -> "扫描已取消"
    MediaScanStatus.INTERRUPTED -> "等待恢复扫描"
    MediaScanStatus.UNKNOWN -> "扫描状态未知"
}
private fun metadataStatus(job: MediaMetadataJob) = when (job.status) {
    MediaScanStatus.QUEUED -> if (job.failureCode == "rate_limited") "TMDB 限流，等待重试" else "等待补全"
    MediaScanStatus.RUNNING -> "正在补全资料"
    MediaScanStatus.COMPLETED -> "补全处理完毕"
    MediaScanStatus.FAILED -> when (job.failureCode) {
        "configuration_changed", "provider_unauthorized" -> "请检查 TMDB 连接后重试"
        "source_changed" -> "媒体来源已变化"
        else -> "补全暂时失败"
    }
    MediaScanStatus.CANCELED -> "补全已取消"
    MediaScanStatus.INTERRUPTED -> "等待恢复补全"
    MediaScanStatus.UNKNOWN -> "补全状态未知"
}
private fun reviewReason(reason: String) = when (reason) {
    "typed_identifier_required" -> "需要按片名确认作品"
    "identifier_conflict", "entity_type_conflict", "year_conflict", "ambiguous_evidence" -> "识别依据有冲突"
    "identifier_not_found" -> "TMDB 未找到此编号"
    else -> "请检查影片信息"
}

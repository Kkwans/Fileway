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
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.SettingsAction
import io.github.kkwans.nasfilebrowser.ui.SettingsGroup
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
        PhoneMediaScanProgress(repository, libraryId, reviewScanId, { onSelect(null) }, onReviewItem, onCatalogChanged)
    } else {
        ScanHistoryPage("扫描记录", onBack, refresh = { scope.launch { load() } }, busy = busy) {
            if (problem != null) item {
                MediaProblemCard(MediaProblem("无法读取扫描记录", providerProblem(problem!!), "刷新"), { scope.launch { load() } })
            }
            if (busy && scans.isEmpty()) item { MediaLoading("正在读取扫描记录") }
            if (!busy && problem == null && scans.isEmpty()) item { Text("这里还没有扫描任务。") }
            items(scans, key = { it.id }) { scan ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    SettingsAction(scan.sourceLabel ?: "媒体文件夹",
                        "${directoryStatus(scan.status)} · 已处理 ${scan.processed} 项", R.drawable.ic_folder) { onSelect(scan.id) }
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
    onBack: () -> Unit, onReviewItem: (MediaCatalogRef) -> Unit, onCatalogChanged: () -> Unit) {
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
    var reviewVisible by rememberSaveable(scanId) { mutableStateOf(false) }
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
    ScanHistoryPage("扫描进度", onBack, refresh = { refresh++ }, busy = busy || changing) {
        scan?.let { entry -> item {
            SettingsGroup("目录扫描") {
                ScanProgressText(directoryStatus(entry.status), "已处理 ${entry.processed} 项")
            }
        } }
        metadata?.let { job -> item {
            SettingsGroup("TMDB 资料补全") {
                val details = listOf("已匹配 ${job.matched}", "待确认 ${job.review}", "待处理 ${job.pending}") +
                    listOfNotNull(job.skipped.takeIf { it > 0 }?.let { "已跳过 $it" }, job.failed.takeIf { it > 0 }?.let { "失败 $it" })
                ScanProgressText(metadataStatus(job), details.joinToString(" · "))
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
                if (reviewCount > 0 && !reviewVisible) SettingsAction("待确认", "$reviewCount 项记录，打开仍待匹配的影片",
                    R.drawable.ic_search, action = { reviewVisible = true })
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
                    SettingsAction(item.title.ifBlank { "待确认影片" }, reviewReason(item.reason), R.drawable.ic_video) { onReviewItem(item.ref) }
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
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val activeScanStates = setOf(MediaScanStatus.QUEUED, MediaScanStatus.RUNNING, MediaScanStatus.INTERRUPTED)
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

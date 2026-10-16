package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.app.ClientState
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.mediaKind
import io.github.kkwans.nasfilebrowser.app.mediaKey
import io.github.kkwans.nasfilebrowser.app.MediaKind
import io.github.kkwans.nasfilebrowser.app.FileLayout
import io.github.kkwans.nasfilebrowser.app.RecentSection
import io.github.kkwans.nasfilebrowser.app.TaskCenterSection
import io.github.kkwans.nasfilebrowser.app.clientPageStateKey
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.data.PlaybackSnapshot
import io.github.kkwans.nasfilebrowser.data.ProgressSync
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ClientApp(model: ClientModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(state.notice, state.previewScope) {
        state.notice?.takeIf { it.isNotBlank() }?.let { message ->
            android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show()
            model.consumeNotice(message, state.previewScope)
        }
    }
    val recent by model.recent.collectAsStateWithLifecycle()
    val search by model.search.state.collectAsStateWithLifecycle()
    val document by model.documents.state.collectAsStateWithLifecycle()
    val documentEdit by model.documentEdits.state.collectAsStateWithLifecycle()
    val archive by model.archives.state.collectAsStateWithLifecycle()
    val archiveEntry by model.archiveEntry.state.collectAsStateWithLifecycle()
    val shell by model.shell.state.collectAsStateWithLifecycle()
    val pageState = key(state.previewScope) { rememberSaveableStateHolder() }
    // Local queue filters/scroll survive connecting or changing the server.
    val localTaskState = rememberSaveableStateHolder()
    val activity = LocalActivity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    fun requestBack() {
        if (imeVisible) { keyboard?.hide(); focus.clearFocus() }
        else if (!model.back()) activity?.finish()
    }
    // Register before every route/early return, so an external picker result
    // survives Activity/process recreation and restores its original task.
    var uploadSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var uploadSourceGeneration by rememberSaveable { mutableLongStateOf(0L) }
    var uploadSourceRestart by rememberSaveable { mutableStateOf(false) }
    val uploadSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = uploadSourceId; val generation = uploadSourceGeneration; val restart = uploadSourceRestart; uploadSourceId = null
        if (id != null) {
            model.openUploads()
            if (uri == null) model.uploads.sourceSelectionCanceled()
            else if (restart) model.uploads.prepareRestart(id, generation, uri)
            else model.uploads.reselectSource(id, generation, uri)
        }
    }
    BackHandler(state.connected || state.image != null || state.selected != null || state.tab != "files") { requestBack() }
    BackHandler(state.startupPending) { model.cancel() }
    if (state.connected) LibraryTheme { FileTransferSheet(model); CreateDirectoryDialog(model); FolderDownloadDialog(model); UploadSelectionDialog(model)
        CreateFileDialog(model.documentEdits, model::verifyDocumentDirectory)
    }
    if (state.startupPending) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(state.stage.ifBlank { "正在打开文件库" }, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = model::cancel) { Text("取消") }
            }
        }
        return
    }
    if (state.image != null) { ImageScreen(model, state.image!!); return }
    if (state.selected != null) {
        if (state.selected!!.mediaKind() == MediaKind.AUDIO) AudioScreen(model, state.selected!!) else PlayerScreen(model, state.selected!!)
        return
    }
    if (documentEdit.file != null) { LibraryTheme { DocumentEditorScreen(model.documentEdits, {}) }; return }
    if (document.file != null) { LibraryTheme {
        DocumentPreviewScreen(model.documents, model::closeTemporaryContent, model::download,
            document.file!!.downloadId.isEmpty() && state.permissions.download && !model.isTemporaryContent(document.file!!),
            onEdit = if (document.file!!.downloadId.isEmpty() && state.permissions.modify && !model.isTemporaryContent(document.file!!)) {
                file, text -> model.documentEdits.open(file, text, state.previewScope)
            } else null, imageCache = model.cache)
    }; return }
    if (archive.file != null) { LibraryTheme {
        ArchiveScreen(model.archives, ::requestBack, { model.closeArchive(); model.showFileTask() },
            { directory -> model.archives.close(); model.verifyDocumentDirectory(directory) },
            onOpenEntry = { entry -> model.openArchiveEntry(entry, state.previewScope) })
        if (archiveEntry.archive?.mediaKey == archive.file?.mediaKey && (archiveEntry.preparing || archiveEntry.canceling || archiveEntry.error != null))
            AlertDialog(onDismissRequest = { if (archiveEntry.preparing) model.archiveEntry.cancel() else model.archiveEntry.dismissError() },
                title = { Text(if (archiveEntry.error != null) "包内文件未能打开" else "正在准备包内文件") }, text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(archiveEntry.error ?: archiveEntry.stage)
                        if (archiveEntry.preparing) {
                            val progress = archiveEntry.progress
                            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (archiveEntry.size > 0) Text("${readableSize(archiveEntry.processedBytes)} / ${readableSize(archiveEntry.size)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }, confirmButton = { TextButton({ if (archiveEntry.preparing) model.archiveEntry.cancel() else model.archiveEntry.dismissError() }, enabled = !archiveEntry.canceling) {
                    Text(if (archiveEntry.preparing) "取消准备" else "关闭")
                } })
    }; return }
    if (state.tab == "account" && state.connected) { LibraryTheme {
        AccountSettingsScreen(model.accountSettings, ::requestBack, model::disconnect)
    }; return }
    if (state.tab == "admin-users" && state.connected) { LibraryTheme {
        AdminUsersScreen(model.adminUsers, ::requestBack, model::disconnect)
    }; return }
    if (state.tab == "server-settings" && state.connected) { LibraryTheme {
        ServerSettingsScreen(model.serverSettings, ::requestBack, model::disconnect)
    }; return }
    if (shell.open && state.connected) { LibraryTheme { ShellScreen(model.shell, model::finishShellNavigation) }; return }
    if (state.tab == "updates") { LibraryTheme { AppUpdatesScreen(model) }; return }
    val taskGroup = TaskCenterSection.forRoute(state.tab)
    FilewayAppShell(model, state, ::requestBack) {
    if (taskGroup != null) {
        LibraryTheme {
            TaskCenterScaffold(model, taskGroup) {
                val holder = if (taskGroup == TaskCenterSection.DOWNLOADS || taskGroup == TaskCenterSection.UPLOADS) localTaskState else pageState
                holder.SaveableStateProvider("taskcenter/${taskGroup.route}") {
                    when (taskGroup) {
                        TaskCenterSection.DOWNLOADS -> DownloadsScreen(model)
                        TaskCenterSection.UPLOADS -> UploadsScreen(model) { row, restart ->
                            uploadSourceId = row.id; uploadSourceGeneration = row.generation; uploadSourceRestart = restart
                            try { uploadSource.launch(arrayOf("*/*")) }
                            catch (_: Exception) { uploadSourceId = null; model.uploads.reportError("无法打开系统文件选择器，原任务保留") }
                        }
                        TaskCenterSection.BACKGROUND -> if (state.connected) ServerTasksScreen(model, state) else TaskConnectionGuide(model::showConnection)
                        TaskCenterSection.HISTORY -> OperationHistoryContent(model.operationHistory, state.connected, model::showConnection)
                    }
                }
            }
        }
    } else if (!state.connected && state.tab !in setOf("recent", "settings", "about")) {
        Column(Modifier.fillMaxSize().statusBarsPadding().consumeWindowInsets(WindowInsets.statusBars)) {
            FilewayPageHeader("连接服务器")
            Box(Modifier.weight(1f)) { ConnectionScreen(model, state) }
        }
    } else {
    val searchShown = state.searchDisplayed && search.open
    val pageKey = when {
        // Keep input focus and selection alive; FileCollection owns layout/filter anchors.
        searchShown -> clientPageStateKey("search", search.baseWirePath)
        state.tab == "files" -> clientPageStateKey("files", state.wirePath)
        state.tab == "library" -> clientPageStateKey("library", state.librarySection.name)
        else -> state.tab
    }
    pageState.SaveableStateProvider(pageKey) {
        LibraryTheme {
            when {
                searchShown -> SearchScreen(model, state)
                state.tab == "files" -> BrowserScreen(model, state)
                state.tab == "cache-images" -> CacheGalleryScreen(model)
                state.tab == "settings" -> SettingsScreen(model, state, onBack = ::requestBack)
                state.tab == "about" -> SettingsScreen(model, state, openAbout = true, onBack = ::requestBack)
                state.tab == "library" -> when (state.librarySection) {
                    io.github.kkwans.nasfilebrowser.app.LibrarySection.FAVORITES -> FavoritesScreen(model, state)
                    io.github.kkwans.nasfilebrowser.app.LibrarySection.TAGS -> TagsScreen(model, state)
                    // Compatibility only: old task routes render in the unified center.
                    io.github.kkwans.nasfilebrowser.app.LibrarySection.TASKS -> TaskCenterScaffold(model, TaskCenterSection.BACKGROUND) { ServerTasksScreen(model, state) }
                    io.github.kkwans.nasfilebrowser.app.LibrarySection.TRASH -> TrashScreen(model, state)
                    io.github.kkwans.nasfilebrowser.app.LibrarySection.TOOLS -> StorageToolsScreen(model, state)
                }
                else -> RecentScreen(model, state, recent)
            }
        }
    }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RecentScreen(model: ClientModel, state: ClientState, recent: List<PlaybackSnapshot>) {
    val colors = MaterialTheme.colorScheme
    var details by remember(state.previewScope) { mutableStateOf<ResourceRef?>(null) }
    var detailsActionsEnabled by remember(state.previewScope) { mutableStateOf(true) }
    LaunchedEffect(state.previewScope, state.connected, state.recentSection) {
        if (state.connected && state.recentSection == RecentSection.PLAYBACK) while (isActive) {
            model.refreshRecentPlayback(); delay(15_000)
        }
    }
    DisposableEffect(state.previewScope, state.recentSection) {
        onDispose { model.stopRecentPlaybackRefresh(state.previewScope) }
    }
    MaterialTheme(colorScheme = colors) {
        Scaffold(containerColor = colors.background, bottomBar = { ClientNavigation(model, "recent") }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
                .swipeTabs(state.recentSection.ordinal, RecentSection.entries.size) { model.recentSection(RecentSection.entries[it]) }) {
                FilewayPageHeader("最近")
                PrimaryTabRow(selectedTabIndex = state.recentSection.ordinal, containerColor = colors.background) {
                    RecentSection.entries.forEach { section ->
                        Tab(selected = section == state.recentSection, onClick = { model.recentSection(section) },
                            modifier = Modifier.semantics { contentDescription = "${section.label}分组" }, text = { Text(section.label) })
                    }
                }
                val recentState = rememberSaveableStateHolder()
                recentState.SaveableStateProvider(state.recentSection.name) {
                    if (state.recentSection == RecentSection.ACCESS) {
                        val access by model.recentAccess.state.collectAsStateWithLifecycle()
                        val sourceScope = access.scope
                        Column(Modifier.fillMaxSize()) {
                            state.error?.let { message ->
                                Text(message, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = colors.error)
                            }
                            if (state.busy) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text(state.stage, Modifier.weight(1f).padding(horizontal = 12.dp))
                                TextButton(model::cancel) { Text("取消") }
                            }
                            RecentAccessContent(model.recentAccess, state.connected, model::showConnection,
                                onLocation = { model.openRecentAccessLocation(it, sourceScope) },
                                onDetails = { detailsActionsEnabled = false; details = it.resource() }) { model.openRecentAccess(it, sourceScope) }
                        }
                    } else Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (state.connected && state.recentPlaybackError == null) "云端最近播放" else "最近播放", style = MaterialTheme.typography.titleMedium, color = colors.onBackground, modifier = Modifier.weight(1f))
                            IconButton({ model.refreshRecentPlayback(force = true) }, enabled = state.connected && !state.recentPlaybackLoading) {
                                Icon(painterResource(R.drawable.ic_refresh), "刷新云端播放记录", Modifier.size(22.dp))
                            }
                        }
                        if (state.recentPlaybackLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.recentPlaybackError?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = colors.error) }
                        state.error?.let { message ->
                            Surface(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = colors.errorContainer, shape = RoundedCornerShape(10.dp)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) { Text(message, color = colors.onErrorContainer); if (state.connected) TextButton(onClick = model::retry) { Text("重试") } }
                            }
                        }
                        if (state.busy) {
                            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text(state.stage, modifier = Modifier.weight(1f)); TextButton(onClick = model::cancel) { Text("取消") } }
                        }
                        LazyColumn(Modifier.weight(1f).semantics { contentDescription = "最近播放列表" }, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (recent.isEmpty() && !state.busy) item {
                                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = colors.background) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(painterResource(R.drawable.ic_history), null, Modifier.size(24.dp), tint = colors.primary)
                                        Text("还没有播放记录", style = MaterialTheme.typography.titleMedium)
                                        Text("从文件页打开视频，观看进度会保存在这里。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                        TextButton(onClick = { model.tab("files") }) { Text("浏览文件") }
                                    }
                                }
                            }
                            items(recent, key = { it.resourceKey }) { snapshot ->
                                val file = ResourceRef(snapshot.path, snapshot.wirePath, snapshot.name, false, if (mediaKind(snapshot.name) == MediaKind.AUDIO) "audio" else "video", 0)
                                FileEntry(model, file, FileLayout.DETAIL, enabled = !state.busy,
                                    open = { model.openRecent(snapshot) }, details = { detailsActionsEnabled = true; details = file }, metadata = { RecentProgress(snapshot) })
                            }
                        }
                    }
                }
            }
            details?.let { file -> FileDetailsDialog(file, showSize = false, openEnabled = !state.busy,
                actions = if (detailsActionsEnabled) { { FileActions(model, file) { details = null } } } else null,
                onLocation = { details = null; model.openContainingDirectory(file) }, onDismiss = { details = null }) }
        }
    }
}

@Composable private fun RecentProgress(snapshot: PlaybackSnapshot) {
    val colors = MaterialTheme.colorScheme
    Text("${if (snapshot.sync == ProgressSync.IDENTITY_CHANGED) "原记录 " else ""}${clock(snapshot.positionMs)} / ${if (snapshot.durationMs > 0) clock(snapshot.durationMs) else "时长待确认"}",
        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant)
    Text(when (snapshot.sync) {
            ProgressSync.SYNCED -> "已同步"
            ProgressSync.PENDING -> "待同步"
            ProgressSync.IDENTITY_CHANGED -> "文件已变化"
            ProgressSync.UNSUPPORTED -> "仅本机保存"
        }, style = MaterialTheme.typography.labelSmall, color = if (snapshot.sync == ProgressSync.IDENTITY_CHANGED) colors.error else colors.onSurfaceVariant)
    if (snapshot.durationMs > 0 && snapshot.sync != ProgressSync.IDENTITY_CHANGED) {
        LinearProgressIndicator(progress = { (snapshot.positionMs.toDouble() / snapshot.durationMs).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(3.dp), color = colors.primary,
            trackColor = colors.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
    }
}

internal fun clock(ms: Long): String { val s = ms.coerceAtLeast(0) / 1000; return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60) }

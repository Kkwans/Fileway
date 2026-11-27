package io.github.kkwans.nasfilebrowser.ui.adaptive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ClientState
import io.github.kkwans.nasfilebrowser.app.FileCategory
import io.github.kkwans.nasfilebrowser.app.MediaKind
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.mediaKey
import io.github.kkwans.nasfilebrowser.app.mediaKind
import io.github.kkwans.nasfilebrowser.data.DocumentPreviewKind
import io.github.kkwans.nasfilebrowser.data.PlaybackSnapshot
import io.github.kkwans.nasfilebrowser.data.documentPreviewKind
import io.github.kkwans.nasfilebrowser.data.isBrowsableArchive
import io.github.kkwans.nasfilebrowser.ui.ArchiveScreen
import io.github.kkwans.nasfilebrowser.ui.AudioScreen
import io.github.kkwans.nasfilebrowser.ui.DocumentEditorScreen
import io.github.kkwans.nasfilebrowser.ui.DocumentPreviewScreen
import io.github.kkwans.nasfilebrowser.ui.FileActions
import io.github.kkwans.nasfilebrowser.ui.FileDetailsBody
import io.github.kkwans.nasfilebrowser.ui.FileOperationDestination
import io.github.kkwans.nasfilebrowser.ui.FileOperationForeground
import io.github.kkwans.nasfilebrowser.ui.FilewayAlertDialog
import io.github.kkwans.nasfilebrowser.ui.FilewayButton
import io.github.kkwans.nasfilebrowser.ui.FilewayIcon
import io.github.kkwans.nasfilebrowser.ui.FilewayIconButton
import io.github.kkwans.nasfilebrowser.ui.FilewayOutlinedButton
import io.github.kkwans.nasfilebrowser.ui.FilewayTextButton
import io.github.kkwans.nasfilebrowser.ui.ImageScreen
import io.github.kkwans.nasfilebrowser.ui.LocalFileOperationForeground
import io.github.kkwans.nasfilebrowser.ui.MediaThumbnail
import io.github.kkwans.nasfilebrowser.ui.PlayerScreen
import io.github.kkwans.nasfilebrowser.ui.clock
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import io.github.kkwans.nasfilebrowser.ui.fileArtworkIcon
import io.github.kkwans.nasfilebrowser.ui.fileTypeLabel
import io.github.kkwans.nasfilebrowser.ui.readableSize

/**
 * Pure policy for deciding how a single tap in a tablet dual-pane file list behaves.
 * Unit-tested directly without Android dependencies.
 */
enum class TabletClickAction {
    /** Single-tapping a directory navigates directly into it. */
    ENTER_DIRECTORY,

    /** Single-tapping a video opens a 16:9 poster stage + resume/play controls + metadata in the right pane. */
    STAGE_VIDEO,

    /** Single-tapping an image, document, audio, or archive opens it embedded in the right workspace. */
    OPEN_EMBEDDED,

    /** Opens the file directly into fullscreen immersive mode (used when user prefers direct fullscreen video). */
    OPEN_FULLSCREEN,

    /** Non-previewable files display the structured inspector and action panel on the right. */
    INSPECT_FILE,
}

object TabletWorkspacePolicy {
    fun actionForSingleTap(
        name: String,
        directory: Boolean,
        type: String,
        mediaKind: MediaKind?,
        documentKind: DocumentPreviewKind,
        isArchive: Boolean,
    ): TabletClickAction = when {
        directory -> TabletClickAction.ENTER_DIRECTORY
        mediaKind == MediaKind.VIDEO -> TabletClickAction.STAGE_VIDEO
        mediaKind == MediaKind.IMAGE || mediaKind == MediaKind.AUDIO -> TabletClickAction.OPEN_EMBEDDED
        isArchive -> TabletClickAction.OPEN_EMBEDDED
        documentKind != DocumentPreviewKind.OTHER -> TabletClickAction.OPEN_EMBEDDED
        else -> TabletClickAction.INSPECT_FILE
    }

    fun actionFor(file: ResourceRef): TabletClickAction = actionForSingleTap(
        name = file.name,
        directory = file.directory,
        type = file.type,
        mediaKind = file.mediaKind(),
        documentKind = documentPreviewKind(file),
        isArchive = isBrowsableArchive(file),
    )
}

data class TabletBatchAction(
    val key: String,
    val label: String,
    val icon: Int,
    val enabled: Boolean,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

data class TabletBatchSummary(
    val title: String,
    val items: List<ResourceRef>,
    val actions: List<TabletBatchAction>,
    val onSelectAll: () -> Unit,
    val onInvert: () -> Unit,
    val onExit: () -> Unit,
    val onRemoveFromSelection: ((ResourceRef) -> Unit)? = null,
)

data class TabletQuickAction(
    val label: String,
    val icon: Int,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

data class TabletDirectoryOverview(
    val title: String,
    val path: String,
    val totalCount: Int,
    val folderCount: Int,
    val imageCount: Int,
    val videoCount: Int,
    val documentCount: Int,
    val quickActions: List<TabletQuickAction>,
)

@Stable
class FilewayWorkspaceHost {
    var inspectedFile by mutableStateOf<ResourceRef?>(null)
    var inspectedOpenAction by mutableStateOf<(() -> Unit)?>(null)
    var inspectedLocationAction by mutableStateOf<(() -> Unit)?>(null)
    var inspectedUnassignAction by mutableStateOf<(() -> Unit)?>(null)
    var batchSummary by mutableStateOf<TabletBatchSummary?>(null)
    var directoryOverview by mutableStateOf<TabletDirectoryOverview?>(null)
    var activeViewerKey by mutableStateOf<String?>(null)

    val focusedFileKey: String?
        get() = activeViewerKey ?: inspectedFile?.mediaKey

    fun inspect(
        file: ResourceRef,
        onOpen: (() -> Unit)? = null,
        onLocation: (() -> Unit)? = null,
        onUnassign: (() -> Unit)? = null,
    ) {
        inspectedFile = file
        inspectedOpenAction = onOpen
        inspectedLocationAction = onLocation
        inspectedUnassignAction = onUnassign
    }

    fun clearInspection() {
        inspectedFile = null
        inspectedOpenAction = null
        inspectedLocationAction = null
        inspectedUnassignAction = null
    }
}

val LocalFilewayWorkspaceHost = staticCompositionLocalOf<FilewayWorkspaceHost?> { null }

/**
 * Dismisses any currently active embedded viewer (except dirty document edits) so clicking
 * a new item in the left list smoothly replaces the right workspace.
 */
internal fun dismissEmbeddedViewersForSwitch(model: ClientModel) {
    val state = model.state.value
    if (state.image != null) model.closeImage()
    if (state.selected != null) model.leavePlayer()
    if (model.documents.state.value.file != null) {
        model.documents.close()
        model.closeTemporaryContent()
    }
    if (model.archives.state.value.file != null) model.closeArchive()
}

/**
 * Unified right-side detail/preview/batch workspace for tablet EXPANDED/LARGE dual-pane mode.
 * Shares the exact same Compose slot when toggling fullscreen so video surfaces and zoom state
 * remain alive with zero flicker.
 */
@Composable
internal fun FileDetailWorkspace(
    model: ClientModel,
    state: ClientState,
    host: FilewayWorkspaceHost,
    splitState: FilewaySplitWorkspaceState,
    onRequestBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val document by model.documents.state.collectAsStateWithLifecycle()
    val documentEdit by model.documentEdits.state.collectAsStateWithLifecycle()
    val archive by model.archives.state.collectAsStateWithLifecycle()
    val archiveEntry by model.archiveEntry.state.collectAsStateWithLifecycle()
    val recent by model.recent.collectAsStateWithLifecycle()

    val isFullscreen = splitState.detailFullscreen
    val expandLeft: (() -> Unit)? = if (splitState.leftCollapsed && !isFullscreen) {
        { splitState.expandLeftPane() }
    } else {
        null
    }
    val toggleFullscreen: () -> Unit = { splitState.setDetailFullscreen(!splitState.detailFullscreen) }

    val editFile = documentEdit.file
    val imageFile = state.image
    val selectedFile = state.selected
    val docFile = document.file
    val archiveFile = archive.file
    val activeKey = when {
        editFile != null -> editFile.mediaKey
        imageFile != null -> imageFile.mediaKey
        selectedFile != null -> selectedFile.mediaKey
        docFile != null -> docFile.mediaKey
        archiveFile != null -> archiveFile.mediaKey
        else -> null
    }
    host.activeViewerKey = activeKey

    // Auto-exit fullscreen if every viewer is closed
    val hasActiveViewer = activeKey != null
    if (!hasActiveViewer && isFullscreen) {
        splitState.setDetailFullscreen(false)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when {
            editFile != null -> {
                DocumentEditorScreen(
                    controller = model.documentEdits,
                    onClose = { splitState.setDetailFullscreen(false) },
                    embedded = !isFullscreen,
                    onToggleFullscreen = toggleFullscreen,
                    onExpandLeftPane = expandLeft,
                )
            }

            imageFile != null -> {
                ImageScreen(
                    model = model,
                    file = imageFile,
                    embedded = !isFullscreen,
                    onToggleFullscreen = toggleFullscreen,
                    onExpandLeftPane = expandLeft,
                )
            }

            selectedFile != null -> {
                if (selectedFile.mediaKind() == MediaKind.AUDIO) {
                    AudioScreen(
                        model = model,
                        file = selectedFile,
                        embedded = !isFullscreen,
                        onToggleFullscreen = toggleFullscreen,
                        onExpandLeftPane = expandLeft,
                    )
                } else {
                    PlayerScreen(
                        model = model,
                        file = selectedFile,
                        embedded = !isFullscreen,
                        onToggleFullscreen = toggleFullscreen,
                        onExpandLeftPane = expandLeft,
                    )
                }
            }

            docFile != null -> {
                DocumentPreviewScreen(
                    controller = model.documents,
                    onClose = {
                        splitState.setDetailFullscreen(false)
                        model.closeTemporaryContent()
                    },
                    onDownload = model::download,
                    canDownload = docFile.downloadId.isEmpty() && state.permissions.download && !model.isTemporaryContent(docFile),
                    onEdit = if (docFile.downloadId.isEmpty() && state.permissions.modify && !model.isTemporaryContent(docFile)) {
                        { file, text -> model.documentEdits.open(file, text, state.previewScope) }
                    } else null,
                    imageCache = model.cache,
                    embedded = !isFullscreen,
                    onToggleFullscreen = toggleFullscreen,
                    onExpandLeftPane = expandLeft,
                )
            }

            archiveFile != null -> {
                Box(Modifier.fillMaxSize()) {
                    ArchiveScreen(
                        controller = model.archives,
                        onBack = {
                            splitState.setDetailFullscreen(false)
                            model.closeArchive()
                        },
                        onOpenTasks = {
                            splitState.setDetailFullscreen(false)
                            model.closeArchive()
                            model.showFileTask()
                        },
                        onOpenDirectory = { directory ->
                            splitState.setDetailFullscreen(false)
                            model.archives.close()
                            model.verifyDocumentDirectory(directory)
                        },
                        onOpenEntry = { entry -> model.openArchiveEntry(entry, state.previewScope) },
                        embedded = !isFullscreen,
                        onToggleFullscreen = toggleFullscreen,
                        onExpandLeftPane = expandLeft,
                    )
                    if (archiveEntry.archive?.mediaKey == archive.file?.mediaKey &&
                        (archiveEntry.preparing || archiveEntry.canceling || archiveEntry.error != null)
                    ) {
                        FilewayAlertDialog(
                            onDismissRequest = { if (archiveEntry.preparing) model.archiveEntry.cancel() else model.archiveEntry.dismissError() },
                            title = { Text(if (archiveEntry.error != null) "包内文件未能打开" else "正在准备包内文件") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(archiveEntry.error ?: archiveEntry.stage)
                                    if (archiveEntry.preparing) {
                                        val progress = archiveEntry.progress
                                        if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                                        if (archiveEntry.size > 0) {
                                            Text(
                                                "${readableSize(archiveEntry.processedBytes)} / ${readableSize(archiveEntry.size)}",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                FilewayTextButton(
                                    { if (archiveEntry.preparing) model.archiveEntry.cancel() else model.archiveEntry.dismissError() },
                                    enabled = !archiveEntry.canceling,
                                ) {
                                    Text(if (archiveEntry.preparing) "取消准备" else "关闭")
                                }
                            },
                        )
                    }
                }
            }

            host.batchSummary != null -> {
                BatchSelectionPane(
                    summary = host.batchSummary!!,
                    onExpandLeftPane = expandLeft,
                )
            }

            host.inspectedFile != null -> {
                val file = host.inspectedFile!!
                val snapshot = remember(recent, file.mediaKey) {
                    recent.firstOrNull { it.resourceKey == file.mediaKey || it.path == file.path }
                }
                FileInspectorPane(
                    model = model,
                    state = state,
                    file = file,
                    playbackSnapshot = snapshot,
                    onOpen = host.inspectedOpenAction ?: { model.open(file) },
                    onOpenFullscreen = {
                        splitState.setDetailFullscreen(true)
                        (host.inspectedOpenAction ?: { model.open(file) }).invoke()
                    },
                    onLocation = host.inspectedLocationAction,
                    onUnassign = host.inspectedUnassignAction,
                    onClose = { host.clearInspection() },
                    onExpandLeftPane = expandLeft,
                )
            }

            else -> {
                DirectoryOverviewPane(
                    overview = host.directoryOverview,
                    onExpandLeftPane = expandLeft,
                )
            }
        }
    }
}

@Composable
private fun FileInspectorPane(
    model: ClientModel,
    state: ClientState,
    file: ResourceRef,
    playbackSnapshot: PlaybackSnapshot?,
    onOpen: () -> Unit,
    onOpenFullscreen: () -> Unit,
    onLocation: (() -> Unit)?,
    onUnassign: (() -> Unit)?,
    onClose: () -> Unit,
    onExpandLeftPane: (() -> Unit)?,
) {
    val colors = FilewayTheme.colors
    val kind = file.mediaKind()
    val foreground = remember(file.mediaKey) { FileOperationForeground() }
    DisposableEffect(foreground) { onDispose { foreground.close() } }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End)),
    ) {
        FilewayTopAppBar(
            title = if (kind == MediaKind.VIDEO) "视频预览" else if (file.directory) "文件夹详情" else "文件详情",
            subtitle = fileTypeLabel(file),
            leadingIcon = if (onExpandLeftPane != null) R.drawable.ic_menu else null,
            leadingDescription = "展开文件列表",
            onLeadingClick = onExpandLeftPane,
            actions = {
                if (kind == MediaKind.VIDEO || kind == MediaKind.IMAGE || documentPreviewKind(file) != DocumentPreviewKind.OTHER || isBrowsableArchive(file)) {
                    FilewayIconButton(onClick = onOpenFullscreen, enabled = !state.busy) {
                        FilewayIcon(R.drawable.art_fullscreen_on, "全屏打开", tint = colors.onSurfaceVariant)
                    }
                }
                FilewayIconButton(onClick = onClose) {
                    FilewayIcon(R.drawable.ic_close, "关闭详情", tint = colors.onSurfaceVariant)
                }
            },
        )
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Visual preview stage card (adaptive horizontal/vertical hero for video, clean card for others)
                if (kind == MediaKind.VIDEO) {
                    VideoPosterStageCard(
                        model = model,
                        file = file,
                        snapshot = playbackSnapshot,
                        enabled = !state.busy,
                        onPlayEmbedded = onOpen,
                        onPlayFullscreen = onOpenFullscreen,
                    )
                } else {
                    FileHeroHeaderCard(
                        model = model,
                        file = file,
                        enabled = !state.busy,
                        onOpen = onOpen,
                        onLocation = onLocation,
                        onUnassign = onUnassign,
                    )
                }

                CompositionLocalProvider(LocalFileOperationForeground provides foreground) {
                    FileDetailsBody(
                        file = file,
                        openEnabled = !state.busy,
                        model = model,
                        actions = {
                            FileActions(model, file) {
                                onClose()
                            }
                        },
                        showNameField = false,
                        scrollable = false,
                        tabletCards = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoPosterStageCard(
    model: ClientModel,
    file: ResourceRef,
    snapshot: PlaybackSnapshot?,
    enabled: Boolean,
    onPlayEmbedded: () -> Unit,
    onPlayFullscreen: () -> Unit,
) {
    val colors = FilewayTheme.colors
    val hasResume = snapshot != null && snapshot.positionMs > 5_000L
    val resumeFraction = if (snapshot != null && snapshot.durationMs > 0L) {
        (snapshot.positionMs.toFloat() / snapshot.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    @Composable
    fun posterThumbnail(modifier: Modifier) {
        Box(
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "播放视频", onClick = onPlayEmbedded),
        ) {
            MediaThumbnail(
                model = model,
                file = file,
                modifier = Modifier.fillMaxSize(),
                showStatusText = false,
                contentScale = ContentScale.Crop,
                naturalAspect = false,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.12f), Color.Black.copy(alpha = 0.52f)),
                        ),
                    ),
            )
            Surface(
                modifier = Modifier.align(Alignment.Center).size(52.dp),
                shape = CircleShape,
                color = colors.primary.copy(alpha = 0.92f),
                contentColor = Color.White,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    FilewayIcon(R.drawable.art_play, null, Modifier.size(26.dp), tint = Color.White)
                }
            }
            if (hasResume) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                    contentColor = Color.White,
                ) {
                    val total = if (snapshot.durationMs > 0) " / ${clock(snapshot.durationMs)}" else ""
                    Text(
                        "续播 · ${clock(snapshot.positionMs)}$total",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (resumeFraction > 0f) {
                    LinearProgressIndicator(
                        progress = { resumeFraction },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                        color = colors.primary,
                        trackColor = Color.White.copy(alpha = 0.25f),
                    )
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            val useHorizontalHero = maxWidth >= 480.dp
            if (useHorizontalHero) {
                val posterWidth = (maxWidth * 0.42f).coerceIn(210.dp, 280.dp)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    posterThumbnail(Modifier.width(posterWidth).aspectRatio(16f / 9f))
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            file.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(fileTypeLabel(file))
                                if (file.size > 0) {
                                    append(" · ")
                                    append(readableSize(file.size))
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilewayButton(
                                onClick = onPlayEmbedded,
                                enabled = enabled,
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                            ) {
                                FilewayIcon(R.drawable.art_play, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (hasResume) "继续播放" else "右侧播放", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            FilewayOutlinedButton(
                                onClick = onPlayFullscreen,
                                enabled = enabled,
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                            ) {
                                FilewayIcon(R.drawable.art_fullscreen_on, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("全屏播放", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        posterThumbnail(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 210.dp)
                                .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true),
                        )
                    }
                    Text(
                        file.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilewayButton(
                            onClick = onPlayEmbedded,
                            enabled = enabled,
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) {
                            FilewayIcon(R.drawable.art_play, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (hasResume) "继续播放" else "右侧播放")
                        }
                        FilewayOutlinedButton(
                            onClick = onPlayFullscreen,
                            enabled = enabled,
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) {
                            FilewayIcon(R.drawable.art_fullscreen_on, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("全屏播放")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileHeroHeaderCard(
    model: ClientModel,
    file: ResourceRef,
    enabled: Boolean,
    onOpen: () -> Unit,
    onLocation: (() -> Unit)?,
    onUnassign: (() -> Unit)?,
) {
    val colors = FilewayTheme.colors
    val kind = file.mediaKind()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (kind == MediaKind.IMAGE) {
                        MediaThumbnail(model, file, Modifier.fillMaxSize(), showStatusText = false, contentScale = ContentScale.Crop)
                    } else {
                        FilewayIcon(
                            fileArtworkIcon(file.name, file.directory, file.type),
                            null,
                            Modifier.size(30.dp),
                            tint = if (file.directory) colors.primary else colors.onSurfaceVariant,
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(fileTypeLabel(file))
                            if (!file.directory) {
                                append(" · ")
                                append(readableSize(file.size))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilewayButton(
                    onClick = onOpen,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                ) {
                    Text(if (file.directory) "进入文件夹" else "打开预览")
                }
                if (onLocation != null) {
                    FilewayOutlinedButton(
                        onClick = onLocation,
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) {
                        Text("所在目录")
                    }
                }
                if (onUnassign != null) {
                    FilewayOutlinedButton(
                        onClick = onUnassign,
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) {
                        Text("取消打标")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchSelectionPane(
    summary: TabletBatchSummary,
    onExpandLeftPane: (() -> Unit)?,
) {
    val colors = FilewayTheme.colors
    val totalBytes = remember(summary.items) { summary.items.filterNot { it.directory }.sumOf { it.size.coerceAtLeast(0L) } }
    val folderCount = remember(summary.items) { summary.items.count { it.directory } }
    val fileCount = summary.items.size - folderCount

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End)),
    ) {
        FilewayTopAppBar(
            title = summary.title,
            subtitle = "已选 ${summary.items.size} 项",
            leadingIcon = if (onExpandLeftPane != null) R.drawable.ic_menu else null,
            leadingDescription = "展开文件列表",
            onLeadingClick = onExpandLeftPane,
            actions = {
                FilewayTextButton(onClick = summary.onSelectAll) { Text("全选") }
                FilewayTextButton(onClick = summary.onInvert) { Text("反选") }
                FilewayIconButton(onClick = summary.onExit) {
                    FilewayIcon(R.drawable.ic_close, "退出多选", tint = colors.onSurfaceVariant)
                }
            },
        )
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (summary.items.isEmpty()) "尚未勾选项目" else "已选 $fileCount 个文件，$folderCount 个文件夹 · ${readableSize(totalBytes)}",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurface,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            summary.actions.forEach { action ->
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (!action.enabled) colors.surfaceContainer.copy(alpha = 0.45f)
                                    else if (action.destructive) colors.errorContainer.copy(alpha = 0.65f)
                                    else colors.surfaceContainer,
                                ) {
                                    Row(
                                        Modifier.heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        val tint = when {
                                            !action.enabled -> colors.onSurface.copy(alpha = 0.38f)
                                            action.destructive -> colors.error
                                            else -> colors.primary
                                        }
                                        FilewayIcon(action.icon, null, Modifier.size(20.dp), tint = tint)
                                        Text(
                                            action.label,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (!action.enabled) colors.onSurface.copy(alpha = 0.38f)
                                            else if (action.destructive) colors.error
                                            else colors.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (summary.items.isNotEmpty()) {
                    Text("已选清单", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 12.dp),
                    ) {
                        items(summary.items, key = { it.mediaKey }) { item ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = colors.surface,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    FilewayIcon(
                                        fileArtworkIcon(item.name, item.directory, item.type),
                                        null,
                                        Modifier.size(20.dp),
                                        tint = if (item.directory) colors.primary else colors.onSurfaceVariant,
                                    )
                                    Text(
                                        item.name,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!item.directory) {
                                        Text(
                                            readableSize(item.size),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                    }
                                    summary.onRemoveFromSelection?.let { remove ->
                                        FilewayIconButton(onClick = { remove(item) }, modifier = Modifier.size(36.dp)) {
                                            FilewayIcon(R.drawable.ic_close, "取消选择 ${item.name}", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DirectoryOverviewPane(
    overview: TabletDirectoryOverview?,
    onExpandLeftPane: (() -> Unit)?,
) {
    val colors = FilewayTheme.colors
    val tabletConfig = LocalTabletLayoutConfig.current
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End)),
    ) {
        if (onExpandLeftPane != null) {
            FilewayTopAppBar(
                title = overview?.title ?: "工作区",
                leadingIcon = R.drawable.ic_menu,
                leadingDescription = "展开文件列表",
                onLeadingClick = onExpandLeftPane,
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Surface(
                    modifier = Modifier.size(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = colors.primary.copy(alpha = 0.10f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        FilewayIcon(R.drawable.ic_folder, null, Modifier.size(32.dp), tint = colors.primary)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        overview?.title?.ifBlank { "根目录" } ?: "快速预览工作台",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (overview != null && overview.path.isNotBlank()) {
                        Text(
                            overview.path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (overview != null && overview.totalCount > 0) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OverviewStatChip("共 ${overview.totalCount} 项")
                        if (overview.folderCount > 0) OverviewStatChip("文件夹 ${overview.folderCount}")
                        if (overview.imageCount > 0) OverviewStatChip("图片 ${overview.imageCount}")
                        if (overview.videoCount > 0) OverviewStatChip("视频 ${overview.videoCount}")
                        if (overview.documentCount > 0) OverviewStatChip("文档 ${overview.documentCount}")
                    }
                }
                if (overview != null && overview.quickActions.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            overview.quickActions.chunked(2).forEach { rowActions ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    rowActions.forEach { action ->
                                        FilewayOutlinedButton(
                                            onClick = action.onClick,
                                            enabled = action.enabled,
                                            modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                                        ) {
                                            FilewayIcon(action.icon, null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    if (rowActions.size == 1) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
                if (tabletConfig.showWorkspaceHints) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(
                            "点选左侧文件即可在此直接预览与管理；左右拖动中间分割线可自由调节栏宽。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            WorkspaceShortcutChip("Ctrl + F", "搜索")
                            WorkspaceShortcutChip("F11", "全屏")
                            WorkspaceShortcutChip("双击分割线", "复位")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceShortcutChip(keyLabel: String, actionLabel: String) {
    val colors = FilewayTheme.colors
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = colors.surfaceContainer.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                keyLabel,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurface,
            )
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OverviewStatChip(label: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = FilewayTheme.colors.surfaceContainer,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = FilewayTheme.colors.onSurfaceVariant,
        )
    }
}

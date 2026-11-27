package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.mediaKey
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySelectionRow

@Composable internal fun AudioScreen(
    model: ClientModel,
    file: ResourceRef,
    embedded: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    onExpandLeftPane: (() -> Unit)? = null,
) {
    val hostColors = FilewayTheme.colors
    if (embedded) {
        Surface(Modifier.fillMaxSize(), color = hostColors.background, contentColor = hostColors.onBackground) {
            AudioContent(model, file, embedded = true, onToggleFullscreen = onToggleFullscreen, onExpandLeftPane = onExpandLeftPane)
        }
    } else {
        FilewayTheme(darkTheme = true) {
            io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaTheme(
                io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayPlayerColors, fullscreen = false) {
                Surface(Modifier.fillMaxSize(), color = FilewayTheme.status.mediaCanvas, contentColor = FilewayTheme.status.mediaContent) {
                    AudioContent(model, file, embedded = false, onToggleFullscreen = onToggleFullscreen, onExpandLeftPane = onExpandLeftPane)
                }
            }
        }
    }
}

@Composable private fun AudioContent(
    model: ClientModel,
    file: ResourceRef,
    embedded: Boolean,
    onToggleFullscreen: (() -> Unit)?,
    onExpandLeftPane: (() -> Unit)?,
) {
    val state by model.player.state.collectAsStateWithLifecycle()
    val client by model.state.collectAsStateWithLifecycle()
    val queue = client.mediaQueue
    var dragging by remember(file) { mutableStateOf<Float?>(null) }
    var menu by remember(file) { mutableStateOf<String?>(null) }
    val registerDarkCanvas = LocalFilewayDarkCanvasOwner.current
    val canvasOwner = remember { Any() }
    val colors = MaterialTheme.colorScheme
    val media = FilewayTheme.status
    val containerBg = if (embedded) colors.background else media.mediaCanvas
    val contentFg = if (embedded) colors.onBackground else media.mediaContent
    val secondaryFg = if (embedded) colors.onSurfaceVariant else media.mediaSecondary
    val cardSurface = if (embedded) colors.surface else media.mediaSurface
    DisposableEffect(registerDarkCanvas, canvasOwner, embedded) {
        registerDarkCanvas(canvasOwner, !embedded)
        onDispose { registerDarkCanvas(canvasOwner, false) }
    }
    BackHandler(enabled = menu == null && !embedded) {
        if (onToggleFullscreen != null) onToggleFullscreen() else model.leavePlayer()
    }
    val topSides = if (embedded) WindowInsetsSides.Top + WindowInsetsSides.End else WindowInsetsSides.Top + WindowInsetsSides.Horizontal
    Scaffold(
        containerColor = containerBg,
        contentColor = contentFg,
        contentWindowInsets = if (embedded) WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
        else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(topSides))) {
                io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar(
                    "音频播放",
                    leadingIcon = if (onExpandLeftPane != null) R.drawable.ic_menu else if (embedded) R.drawable.ic_close else R.drawable.ic_arrow_back,
                    leadingDescription = if (onExpandLeftPane != null) "展开文件列表" else if (!embedded && onToggleFullscreen != null) "退出全屏" else "关闭音频",
                    onLeadingClick = {
                        when {
                            onExpandLeftPane != null -> onExpandLeftPane()
                            !embedded && onToggleFullscreen != null -> onToggleFullscreen()
                            else -> model.leavePlayer()
                        }
                    },
                    actions = {
                        if (onToggleFullscreen != null) {
                            FilewayIconButton(onToggleFullscreen) {
                                FilewayIcon(
                                    if (embedded) R.drawable.art_fullscreen_on else R.drawable.art_fullscreen_off,
                                    if (embedded) "全屏播放音频" else "退出全屏",
                                )
                            }
                        }
                        FilewayIconButton({ menu = "queue" }, enabled = queue != null) { FilewayIcon(R.drawable.ic_playlist_play, "音频播放列表") }
                        if (onExpandLeftPane != null) {
                            FilewayIconButton(model::leavePlayer) { FilewayIcon(R.drawable.ic_close, "关闭音频") }
                        }
                    },
                )
                if (embedded) {
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(if (embedded) 20.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = cardSurface,
                    border = if (embedded) BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)) else null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth().padding(if (embedded) 20.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Surface(Modifier.size(64.dp), shape = RoundedCornerShape(14.dp), color = colors.primary.copy(alpha = if (embedded) 0.12f else 0.22f)) {
                                Box(contentAlignment = Alignment.Center) { FilewayIcon(R.drawable.ic_audio, null, Modifier.size(32.dp), tint = colors.primary) }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(file.name, color = contentFg, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (client.busy) client.stage else if (state.waitingForBuffer) "正在缓冲…" else if (state.playing) "正在播放" else "已暂停",
                                    color = secondaryFg,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (client.busy || state.waitingForBuffer) LinearProgressIndicator(Modifier.fillMaxWidth())
                        (client.error ?: state.error)?.let { message ->
                            Text(message, color = colors.error); FilewayOutlinedButton(model::retryPlayback, Modifier.fillMaxWidth()) { Text("重试播放") }
                        }
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            PlayerSlider(
                                dragging ?: state.positionMs.toFloat(),
                                state.durationMs.coerceAtLeast(1).toFloat(),
                                "音频播放进度",
                                !client.busy && state.seekable,
                                { dragging = it },
                                { dragging?.let { model.player.seek(it.toLong()) }; dragging = null },
                                description = "${audioTime(state.positionMs)} / ${audioTime(state.durationMs)}",
                                bufferedValue = state.bufferedPositionMs.toFloat(),
                                mediaSurface = !embedded,
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(audioTime(dragging?.toLong() ?: state.positionMs), color = secondaryFg, style = FilewayTheme.text.clock)
                                Text(audioTime(state.durationMs), color = secondaryFg, style = FilewayTheme.text.clock)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            FilewayIconButton(model::previousMedia, Modifier.size(48.dp), enabled = queue?.hasPrevious == true) { FilewayIcon(R.drawable.ic_skip_previous, "上一首") }
                            FilledIconButton(
                                { model.player.toggle() },
                                Modifier.size(64.dp),
                                enabled = !client.busy && state.error == null,
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                            ) {
                                FilewayIcon(if (state.playing) R.drawable.art_pause else R.drawable.art_play, if (state.playing) "暂停音频" else "播放音频", Modifier.size(28.dp))
                            }
                            FilewayIconButton(model::nextMedia, Modifier.size(48.dp), enabled = queue?.hasNext == true) { FilewayIcon(R.drawable.ic_skip_next, "下一首") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilewayOutlinedButton({ menu = "speed" }, Modifier.weight(1f)) { Text("${state.rate}× 倍速") }
                            FilewayOutlinedButton({ menu = "audio" }, Modifier.weight(1f), enabled = state.audio.any { it.id >= 0 }) {
                                FilewayIcon(R.drawable.ic_audio, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("音轨")
                            }
                        }
                    }
                }
                if (embedded && queue != null && queue.items.size > 1) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(
                                "播放队列 · ${queue.items.size} 首",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onSurface,
                            )
                            queue.items.forEachIndexed { index, item ->
                                val selected = index == queue.index
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !client.busy, role = Role.Button) { model.navigateMedia(index) }
                                        .background(if (selected) colors.primary.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    FilewayIcon(
                                        if (selected) R.drawable.art_play else R.drawable.ic_audio,
                                        null,
                                        Modifier.size(18.dp),
                                        tint = if (selected) colors.primary else colors.onSurfaceVariant,
                                    )
                                    Text(
                                        item.name,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (selected) colors.primary else colors.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (menu != null) FileOperationSheet(title = when (menu) { "queue" -> "播放列表 · ${queue?.items?.size ?: 0} 首"; "speed" -> "播放速度"; else -> "音轨" },
        onDismiss = { menu = null }) {
        state.operationError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
        when (menu) {
            "queue" -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                itemsIndexed(queue?.items.orEmpty(), key = { index, item -> "${item.mediaKey}/$index" }) { index, item ->
                    FilewaySelectionRow(item.name, selected = index == queue?.index, enabled = !client.busy) {
                        model.navigateMedia(index); menu = null
                    }
                }
            }
            "speed" -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { rate -> item(key = rate) {
                    FilewaySelectionRow("$rate×", selected = state.rate == rate, enabled = !client.busy) { model.player.rate(rate) }
                } }
            }
            "audio" -> PlayerTrackList(state.audio.filter { it.id >= 0 }, state.selectedAudio, state.pendingAudio, subtitle = false,
                modifier = Modifier.weight(1f, fill = false)) { model.player.audio(it) }
        }
    }
}

private fun audioTime(value: Long): String {
    val seconds = value.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}

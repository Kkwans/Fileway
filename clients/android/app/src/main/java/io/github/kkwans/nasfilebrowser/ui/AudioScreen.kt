package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySelectionRow

@Composable internal fun AudioScreen(model: ClientModel, file: ResourceRef) {
    FilewayTheme(darkTheme = true) {
        io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaTheme(
            io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayPlayerColors, fullscreen = false) {
        Surface(Modifier.fillMaxSize(), color = FilewayTheme.status.mediaCanvas, contentColor = FilewayTheme.status.mediaContent) {
            AudioContent(model, file)
        }
        }
    }
}

@Composable private fun AudioContent(model: ClientModel, file: ResourceRef) {
    val state by model.player.state.collectAsStateWithLifecycle()
    val client by model.state.collectAsStateWithLifecycle()
    val queue = client.mediaQueue
    var dragging by remember(file) { mutableStateOf<Float?>(null) }
    var menu by remember(file) { mutableStateOf<String?>(null) }
    val activity = LocalActivity.current
    val colors = MaterialTheme.colorScheme
    val media = FilewayTheme.status
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false; controller?.isAppearanceLightNavigationBars = false
        onDispose { oldStatus?.let { controller?.isAppearanceLightStatusBars = it }; oldNavigation?.let { controller?.isAppearanceLightNavigationBars = it } }
    }
    BackHandler(enabled = menu == null) { model.leavePlayer() }
    Column(Modifier.fillMaxSize().background(media.mediaCanvas).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(model::leavePlayer, Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_arrow_back), "关闭音频", tint = media.mediaContent) }
            Text("正在播放", Modifier.weight(1f), color = media.mediaContent, style = MaterialTheme.typography.titleMedium)
            IconButton({ menu = "queue" }, Modifier.size(48.dp), enabled = queue != null) {
                Icon(painterResource(R.drawable.ic_playlist_play), "音频播放列表", tint = media.mediaContent)
            }
        }
        Box(Modifier.size(112.dp).background(media.mediaSurface, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_audio), null, Modifier.size(52.dp), tint = colors.primary)
        }
        Text(file.name, color = media.mediaContent, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (client.busy || state.waitingForBuffer) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), color = colors.primary, strokeWidth = 2.dp)
            Text(if (client.busy) client.stage else "正在缓冲 ${state.buffering.toInt()}%", color = media.mediaContent)
        }
        (client.error ?: state.error)?.let { message ->
            Text(message, color = colors.error); FilewayOutlinedButton(model::retryPlayback) { Text("重试播放") }
        }
        Column(Modifier.fillMaxWidth()) {
            PlayerSlider(dragging ?: state.positionMs.toFloat(), state.durationMs.coerceAtLeast(1).toFloat(), "音频播放进度", !client.busy && state.seekable,
                { dragging = it }, { dragging?.let { model.player.seek(it.toLong()) }; dragging = null },
                description = "${audioTime(state.positionMs)} / ${audioTime(state.durationMs)}", bufferedValue = state.bufferedPositionMs.toFloat())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(audioTime(dragging?.toLong() ?: state.positionMs), color = media.mediaContent, style = FilewayTheme.text.clock)
                Text(audioTime(state.durationMs), color = media.mediaSecondary, style = FilewayTheme.text.clock)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(model::previousMedia, Modifier.size(56.dp), enabled = queue?.hasPrevious == true) {
                Icon(painterResource(R.drawable.ic_skip_previous), "上一首")
            }
            FilledIconButton({ model.player.toggle() }, Modifier.size(72.dp), enabled = !client.busy && state.error == null,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary)) {
                Icon(painterResource(if (state.playing) R.drawable.art_pause else R.drawable.art_play), if (state.playing) "暂停音频" else "播放音频", Modifier.size(36.dp))
            }
            IconButton(model::nextMedia, Modifier.size(56.dp), enabled = queue?.hasNext == true) {
                Icon(painterResource(R.drawable.ic_skip_next), "下一首")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            FilewayTextButton({ menu = "speed" }) { Text("${state.rate}× 倍速") }
            FilewayTextButton({ menu = "audio" }, enabled = state.audio.any { it.id >= 0 }) { Text("音轨") }
        }
        Text("离开应用时暂停播放", color = media.mediaSecondary, style = MaterialTheme.typography.bodySmall)
    }
    if (menu != null) FileOperationSheet(title = when (menu) { "queue" -> "播放列表 · ${queue?.items?.size ?: 0} 首"; "speed" -> "播放速度"; else -> "音轨" },
        onDismiss = { menu = null }, footer = { FilewayTextButton({ menu = null }) { Text("关闭") } }) {
        state.operationError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
        when (menu) {
            "queue" -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                itemsIndexed(queue?.items.orEmpty()) { index, item -> ListItem(headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Text("${index + 1}") }, trailingContent = { if (index == queue?.index) Icon(painterResource(R.drawable.ic_audio), "当前音频") },
                    modifier = Modifier.clickable { model.navigateMedia(index); menu = null }) }
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

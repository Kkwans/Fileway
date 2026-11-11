package io.github.kkwans.fileway.tv

import android.view.KeyEvent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.kkwans.fileway.design.R as Art
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun TvUpdateScreen(model: TvModel, onBack: () -> Unit) {
    val state by model.updates.state.collectAsStateWithLifecycle()
    val interactive = LocalTvInteractive.current
    val activity = LocalActivity.current
    val checkFocus = remember { FocusRequester() }
    var notes by remember { mutableStateOf<TvUpdateManifest?>(null) }
    var returnToNotes by remember { mutableStateOf<FocusRequester?>(null) }

    LaunchedEffect(interactive) {
        if (interactive) {
            withFrameNanos {}
            checkFocus.requestFocus()
        }
    }
    LaunchedEffect(state.phase) {
        if (interactive && state.phase == TvUpdatePhase.ERROR && state.offers.isEmpty()) {
            withFrameNanos {}
            checkFocus.requestFocus()
        }
    }
    LaunchedEffect(returnToNotes) {
        returnToNotes?.let {
            withFrameNanos {}
            it.requestFocus()
            returnToNotes = null
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TvToolbarAction("返回设置", Art.drawable.fileway_ic_arrow_back, onBack)
            Text("应用更新", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TvButton(
                when {
                    state.transferring -> "取消下载"
                    state.phase == TvUpdatePhase.WAITING_INSTALL -> "结束等待"
                    state.phase == TvUpdatePhase.CHECKING -> "正在检查…"
                    state.phase == TvUpdatePhase.INSTALLING -> "正在准备安装…"
                    state.phase == TvUpdatePhase.DISCONNECTED -> "重新连接"
                    else -> "检查更新"
                },
                {
                    when {
                        state.transferring -> model.updates.cancelDownload()
                        state.phase == TvUpdatePhase.WAITING_INSTALL -> model.updates.resetPendingInstall()
                        state.phase == TvUpdatePhase.DISCONNECTED -> model.reconnect()
                        !state.busy -> model.updates.check()
                    }
                },
                Modifier.focusRequester(checkFocus),
            )
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 12.dp)) {
            item("status") { TvUpdateStatus(state) }
            items(state.offers, key = { it.manifest.channel }) { offer ->
                val selected = state.selected == offer
                val manifest = offer.manifest
                val notesFocus = remember { FocusRequester() }
                val action = when {
                    selected && state.transferring -> "取消下载"
                    selected && state.phase == TvUpdatePhase.READY -> "安装更新"
                    selected && state.phase == TvUpdatePhase.WAITING_INSTALL -> "重新发起安装"
                    selected && state.phase == TvUpdatePhase.INSTALLING -> "正在准备安装…"
                    selected && state.phase == TvUpdatePhase.ERROR -> "重试下载"
                    else -> "下载此版本"
                }
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, TvShape).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${manifest.channel.title} · ${tvSize(manifest.size)}", color = LocalTvSurfaces.current.secondaryText,
                        style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        TvUpdateVersion("当前版本", BuildConfig.VERSION_NAME, Modifier.weight(1f))
                        TvUpdateVersion("目标版本", manifest.versionName, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvButton(action, {
                            when {
                                selected && state.transferring -> model.updates.cancelDownload()
                                selected && state.phase in setOf(TvUpdatePhase.READY, TvUpdatePhase.WAITING_INSTALL) ->
                                    activity?.let(model.updates::install)
                                !state.busy && state.phase != TvUpdatePhase.WAITING_INSTALL -> model.updates.download(offer)
                            }
                        }, enabled = selected || !state.busy && state.phase != TvUpdatePhase.WAITING_INSTALL)
                        if (manifest.notes.isNotBlank()) TvButton("发布说明", { notes = manifest },
                            Modifier.focusRequester(notesFocus))
                    }
                }
                if (notes == manifest) {
                    TvUpdateNotes(manifest) {
                        notes = null
                        returnToNotes = notesFocus
                    }
                }
            }
        }
    }
}

@Composable
private fun TvUpdateVersion(label: String, version: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = LocalTvSurfaces.current.secondaryText)
        Text(version, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TvUpdateStatus(state: TvUpdateState) {
    var waiting by remember(state.phase) { mutableStateOf(false) }
    LaunchedEffect(state.phase) {
        if (state.busy) {
            delay(220)
            waiting = true
        }
    }
    val title = when (state.phase) {
        TvUpdatePhase.DISCONNECTED -> "连接服务器后可检查更新"
        TvUpdatePhase.CHECKING -> "正在检查更新".takeIf { waiting }
        TvUpdatePhase.PREPARING -> "服务器正在准备更新"
        TvUpdatePhase.DOWNLOADING -> "正在下载到电视"
        TvUpdatePhase.VERIFYING -> "正在校验安装包".takeIf { waiting }
        TvUpdatePhase.READY -> "安装包已通过校验，等待安装"
        TvUpdatePhase.INSTALLING -> "正在为系统安装准备文件".takeIf { waiting }
        else -> null
    }
    val message = state.message
    if (title == null && message == null && state.offers.isEmpty() && !state.busy) {
        Text("当前版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
    }
    if (title != null || message != null) {
        Column(Modifier.fillMaxWidth().background(LocalTvSurfaces.current.chrome, TvShape).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            title?.let {
                Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge)
            }
            if (state.phase == TvUpdatePhase.PREPARING || state.phase == TvUpdatePhase.DOWNLOADING) {
                val total = state.total
                Text(
                    if (total == null) "已接收 ${tvSize(state.received)}"
                    else "${tvSize(state.received)} / ${tvSize(total)}",
                    style = MaterialTheme.typography.bodySmall, color = LocalTvSurfaces.current.secondaryText,
                )
                if (total != null) {
                    val fraction = (state.received.toDouble() / total).toFloat().coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(TvShape).background(LocalTvSurfaces.current.outline)
                        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }) {
                        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
            message?.let {
                Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.phase == TvUpdatePhase.ERROR) MaterialTheme.colorScheme.error else LocalTvSurfaces.current.secondaryText)
            }
        }
    }
}

@Composable
private fun TvUpdateNotes(manifest: TvUpdateManifest, dismiss: () -> Unit) {
    TvPanel("发布说明", dismiss) {
        val scroll = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val step = with(LocalDensity.current) { 80.dp.toPx() }
        val paragraphs = remember(manifest.notes) {
            manifest.notes.lineSequence().chunked(16).map { it.joinToString("\n") }.toList()
        }
        LazyColumn(
            Modifier.fillMaxSize().tvRemoteKeys { event ->
                val distance = when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> -step
                    KeyEvent.KEYCODE_DPAD_DOWN -> step
                    else -> return@tvRemoteKeys false
                }
                scope.launch { scroll.scrollBy(distance) }
                true
            }.focusable(),
            state = scroll,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("${manifest.channel.title} ${manifest.versionName}", style = MaterialTheme.typography.titleMedium) }
            items(paragraphs) { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

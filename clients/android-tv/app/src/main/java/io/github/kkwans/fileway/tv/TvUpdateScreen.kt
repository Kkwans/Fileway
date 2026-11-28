package io.github.kkwans.fileway.tv

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.kkwans.fileway.design.R as Art
import kotlinx.coroutines.delay

@Composable
internal fun TvUpdateScreen(model: TvModel, onBack: () -> Unit) {
    val state by model.updates.state.collectAsStateWithLifecycle()
    val interactive = LocalTvInteractive.current
    val activity = LocalActivity.current
    var pendingDownload by remember { mutableStateOf<TvUpdateOffer?>(null) }
    val downloadPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val offer = pendingDownload
        pendingDownload = null
        if (offer != null) model.updates.download(offer)
        else if (!granted) model.updates.publicDownloadDenied()
    }
    fun publicStorageGranted() = Build.VERSION.SDK_INT >= 29 ||
        activity?.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    val checkFocus = remember { FocusRequester() }
    var directoryDraft by remember(state.directory) { mutableStateOf(state.directory) }
    var directoryError by remember { mutableStateOf<String?>(null) }

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

    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
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
        LazyColumn(
            Modifier.fillMaxSize().widthIn(max = 960.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item("status") { TvUpdateStatus(state) }
            item("directory") {
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, TvShape).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("安装包目录", style = MaterialTheme.typography.titleMedium)
                    Text("默认保存到 Documents/Fileway。可填写 Documents 或 Download 下的自定义目录；安装不依赖文件选择器。",
                        style = MaterialTheme.typography.bodyMedium, color = LocalTvSurfaces.current.secondaryText)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        TvField("目录", directoryDraft, { directoryDraft = it; directoryError = null },
                            Modifier.weight(1f))
                        TvButton("应用目录", {
                            if (model.updates.setDirectory(directoryDraft)) directoryError = null
                            else directoryError = "请输入 Documents 或 Download 下的有效目录"
                        }, enabled = !state.busy)
                    }
                    directoryError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            items(state.offers, key = { it.manifest.channel }) { offer ->
                val selected = state.selected == offer
                val manifest = offer.manifest
                val action = when {
                    selected && state.transferring -> "取消下载"
                    selected && state.phase == TvUpdatePhase.READY -> "安装更新"
                    selected && state.phase == TvUpdatePhase.WAITING_INSTALL -> "重新发起安装"
                    selected && state.phase == TvUpdatePhase.INSTALLING -> "正在准备安装…"
                    selected && state.phase == TvUpdatePhase.ERROR -> "重试下载"
                    else -> "下载此版本"
                }
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, TvShape).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        TvUpdateVersion("当前版本", BuildConfig.VERSION_NAME, Modifier.weight(1f))
                        TvUpdateVersion("目标版本", manifest.versionName, Modifier.weight(1f))
                        TvUpdateVersion("安装包", tvSize(manifest.size), Modifier.weight(1f))
                    }
                    if (manifest.notes.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("更新日志", style = MaterialTheme.typography.titleSmall)
                            TvUpdateNotesInline(manifest.notes)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${manifest.channel.title} · ${tvSize(manifest.size)}",
                            style = MaterialTheme.typography.bodySmall, color = LocalTvSurfaces.current.secondaryText)
                        Box(Modifier.weight(1f))
                        TvButton(action, {
                            when {
                                selected && state.transferring -> model.updates.cancelDownload()
                                selected && state.phase in setOf(TvUpdatePhase.READY, TvUpdatePhase.WAITING_INSTALL) ->
                                    activity?.let(model.updates::install)
                                !state.busy && state.phase != TvUpdatePhase.WAITING_INSTALL -> {
                                    if (publicStorageGranted()) model.updates.download(offer)
                                    else if (pendingDownload == null) {
                                        pendingDownload = offer
                                        downloadPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    }
                                }
                            }
                        }, enabled = selected && state.transferring || !state.busy && (selected || state.phase != TvUpdatePhase.WAITING_INSTALL))
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
private fun TvUpdateNotesInline(notes: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        notes.lineSequence().filter(String::isNotBlank).forEach { line ->
            val heading = line.trimStart().startsWith("#")
            val bullet = line.trimStart().startsWith("-") || line.trimStart().startsWith("*")
            Text(
                if (bullet) "• ${line.trimStart().drop(1).trim()}" else line,
                style = if (heading) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                fontWeight = if (heading) FontWeight.SemiBold else FontWeight.Normal,
                color = if (heading) MaterialTheme.colorScheme.onSurface else LocalTvSurfaces.current.secondaryText,
            )
        }
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
        Column(Modifier.fillMaxWidth().background(LocalTvSurfaces.current.chrome, TvShape).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

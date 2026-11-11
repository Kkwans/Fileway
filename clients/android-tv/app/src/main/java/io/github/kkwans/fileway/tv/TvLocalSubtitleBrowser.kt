package io.github.kkwans.fileway.tv

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.kkwans.fileway.design.R as Art
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

private class LocalPage(val directory: TvLocalSubtitleStorage.Node? = null, val offset: Int = 0) {
    var data by mutableStateOf<TvLocalSubtitleStorage.Page?>(null)
    var error by mutableStateOf<String?>(null)
    var focusKey: String? = null
}

private class LocalBrowserLifetime { var active = true }

@Composable
internal fun ColumnScope.TvLocalSubtitleBrowser(
    media: TvMediaController,
    focus: TvPlayerPanelFocus,
    onBack: () -> Unit,
    onBrowserBack: ((() -> Unit)?) -> Unit,
) {
    val mediaState by media.state.collectAsStateWithLifecycle()
    val nativeState by media.player.state.collectAsStateWithLifecycle()
    val epoch = media.controlEpoch
    val picker = LocalTvSubtitlePicker.current
    val latestBack by rememberUpdatedState(onBack)
    val browser = remember(epoch) { LocalBrowserLifetime() }
    var history by remember(epoch) { mutableStateOf(listOf(LocalPage())) }
    var refresh by remember(epoch) { mutableIntStateOf(0) }
    var pickerBusy by remember(epoch) { mutableStateOf(false) }
    var selected by remember(epoch) { mutableStateOf<TvLocalSubtitleOperation?>(null) }
    var message by remember(epoch) { mutableStateOf<String?>(null) }
    var confirmation by remember(epoch) { mutableStateOf<TvLocalSubtitleStorage.Node?>(null) }
    var showImportError by remember(epoch) { mutableStateOf(false) }
    val latestOperation by rememberUpdatedState(selected)
    val location = history.last()
    val fence = media.localSubtitleFence()

    fun reload(key: String? = null) {
        location.data = null
        location.error = null
        focus.retarget(key)
        refresh++
    }

    fun select(node: TvLocalSubtitleStorage.Node, expected: TvLocalSubtitleFence?) {
        message = null
        selected = expected?.let { media.selectLocalSubtitle(node, it) }
        showImportError = selected != null
        if (selected == null) message = "播放内容已变化，请重新选择字幕"
        else focus.retarget("cancel-import")
    }

    fun open(mode: TvLocalPickerMode) {
        if (picker == null) {
            message = "电视文件授权界面尚未就绪，请返回后重试"
            return
        }
        pickerBusy = true
        message = null
        showImportError = false
        picker.open(mode, { browser.active }) { outcome ->
            pickerBusy = false
            val trigger = when (mode) {
                TvLocalPickerMode.TREE -> "grant-tree"
                TvLocalPickerMode.FILE -> "pick-file"
                TvLocalPickerMode.LEGACY_READ -> "grant-shared"
            }
            focus.retarget(trigger)
            when (outcome) {
                is TvLocalPickerOutcome.Message -> message = outcome.text
                is TvLocalPickerOutcome.Folder -> {
                    message = if (outcome.persistent) "文件夹已授权" else "文件夹本次可用，系统未提供持久读取权限"
                    reload()
                }
                TvLocalPickerOutcome.LegacyGranted -> reload("shared")
                is TvLocalPickerOutcome.File -> { location.focusKey = trigger; select(outcome.node, outcome.fence) }
            }
        }
    }

    fun push(page: LocalPage, from: String) {
        if (history.size >= 48) {
            message = "当前浏览层级过深，可以从系统直接授权字幕所在文件夹"
            return
        }
        location.focusKey = from
        history = history + page
        message = null
        showImportError = false
        focus.retarget(null)
    }

    fun previous() {
        val current = selected
        when {
            current != null -> {
                current.cancel()
                selected = null
                showImportError = false
                focus.retarget(location.focusKey)
            }
            confirmation != null -> {
                confirmation = null
                focus.retarget("revoke")
            }
            history.size > 1 -> {
                history = history.dropLast(1)
                message = null
                showImportError = false
                focus.retarget(history.last().focusKey)
            }
            else -> latestBack()
        }
    }

    DisposableEffect(browser) {
        onDispose { browser.active = false; latestOperation?.cancel?.invoke() }
    }
    DisposableEffect(browser, history.size, selected, confirmation) {
        onBrowserBack(if (history.size > 1 || selected != null || confirmation != null) ::previous else null)
        onDispose { onBrowserBack(null) }
    }
    LaunchedEffect(location, refresh, fence?.account) {
        if (location.data != null || fence == null) return@LaunchedEffect
        location.error = null
        try {
            location.data = withTimeout(12_000) {
                val directory = location.directory
                if (directory == null) media.localSubtitles.roots(fence.account)
                else media.localSubtitles.page(fence.account, directory, location.offset)
            }
        } catch (_: TimeoutCancellationException) { location.error = "读取目录超时，请重试" }
        catch (failure: TvLocalSubtitleFailure) { location.error = failure.message }
    }
    LaunchedEffect(selected) {
        val current = selected ?: return@LaunchedEffect
        val applied = current.result.await()
        if (selected === current) {
            selected = null
            if (applied) {
                onBrowserBack(null)
                latestBack()
            } else {
                focus.retarget(location.focusKey)
                if (media.state.value.subtitleError == null) message = "字幕选择已取消，可重新选择"
            }
        }
    }

    val data = location.data
    val directory = location.directory
    val importing = selected != null
    val busy = pickerBusy || importing || fence == null
    val targetToRevoke = confirmation
    val error = location.error ?: if (showImportError) mediaState.subtitleError
        ?: nativeState.operationError?.takeIf { it.contains("字幕") } else null
    val showPreparing = delayedBusy(importing)
    val showSystemPicker = delayedBusy(pickerBusy)
    val showReading = delayedBusy(data == null && location.error == null && fence != null)
    val status = when {
        importing && showPreparing -> "正在准备字幕…"
        pickerBusy && showSystemPicker -> "等待电视系统选择…"
        data == null && location.error == null && fence != null && showReading -> "正在读取目录…"
        fence == null -> "视频尚未准备好，请稍后重试"
        else -> message
    }
    directory?.let { Text(it.label, Modifier.padding(horizontal = 12.dp), style = tvTextStyle(19, 26)) }
    status?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = tvTextStyle(16, 22), color = Color.White.copy(alpha = .8f)) }
    error?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = tvTextStyle(16, 22), color = MaterialTheme.colorScheme.error) }

    fun choice(key: String, title: String, detail: String? = null, enabled: Boolean = !busy,
        folder: Boolean = false, action: () -> Unit) = TvPlayerRow.Choice(
        key, title, enabled = enabled, supporting = detail, icon = if (folder) Art.drawable.fileway_ic_folder else null,
        destination = folder, selectable = false, action = action,
    )

    val rows = buildList<TvPlayerRow> {
        if (targetToRevoke != null) {
            add(TvPlayerRow.Heading("confirm-label", "撤销后需要重新授权才能浏览此文件夹"))
            add(choice("keep", "保留授权") { previous() })
            add(choice("confirm", "撤销读取授权") {
                val owner = fence?.account
                val grant = targetToRevoke.grantId
                if (owner != null && grant != null && media.revokeLocalSubtitleTree(owner, grant)) {
                    confirmation = null
                    history = listOf(LocalPage())
                    message = "已撤销文件夹读取授权"
                    showImportError = false
                    focus.retarget("grant-tree")
                } else message = "当前文件夹授权已变化，请返回重试"
            })
        } else {
            if (importing) add(choice("cancel-import", "取消载入", enabled = true) { previous() })
            if (directory == null) {
                add(choice("grant-tree", "授权文件夹", "电视系统只读授权") { open(TvLocalPickerMode.TREE) })
                add(choice("pick-file", "选择单个文件", "仅用于当前视频") { open(TvLocalPickerMode.FILE) })
                if (tvLegacySubtitleStorage && !media.localSubtitles.hasLegacyRead) {
                    add(choice("grant-shared", "允许读取共享存储", "Android 9 · 需要在电视上确认") { open(TvLocalPickerMode.LEGACY_READ) })
                }
            }
            if (location.error != null) add(choice("retry", "重试") { reload("retry") })
            data?.rows?.forEach { node ->
                add(choice(node.id, node.label, node.detail, folder = node.directory) {
                    if (node.directory) push(LocalPage(node), node.id)
                    else {
                        location.focusKey = node.id
                        select(node, media.localSubtitleFence())
                    }
                })
            }
            if (directory != null && data?.rows?.isEmpty() == true) {
                add(TvPlayerRow.Heading("empty", if (data.next != null) "本页没有可用字幕或文件夹" else "没有可用字幕或文件夹"))
            }
            data?.next?.let { next -> add(choice("more", "查看更多文件") { push(LocalPage(directory, next), "more") }) }
            if (directory != null && location.offset == 0 && directory.detail != null && directory.grantId != null) {
                add(choice("revoke", "撤销此文件夹授权") { confirmation = directory; focus.retarget("keep") })
            }
        }
    }
    TvPlayerChoiceList(rows, focus, Modifier.weight(1f))
}

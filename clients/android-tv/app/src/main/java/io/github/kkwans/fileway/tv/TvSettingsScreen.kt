package io.github.kkwans.fileway.tv

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import io.github.kkwans.fileway.design.R as Art
import io.github.kkwans.nasfilebrowser.data.VideoDecodePolicy
import kotlinx.coroutines.*

private enum class TvSettingsSection(val title: String, val icon: Int, val subtitle: String) {
    INTERFACE("界面", Art.drawable.fileway_ic_settings, "主题、文件布局与显示区域"),
    PLAYBACK("播放", Art.drawable.fileway_ic_play_arrow, "遥控操作、播放控件与解码方式"),
    CACHE("视频缓存", Art.drawable.fileway_ic_storage, "预缓冲容量、保留时间与清理"),
    REMOTE("手机遥控", Art.drawable.fileway_ic_phone, "配对设备与连接管理"),
    DEVICE("设备信息", Art.drawable.fileway_ic_info, "显示、音频与视频解码能力"),
    UPDATES("应用更新", Art.drawable.fileway_ic_update, "检查并安装新版本"),
    ACCOUNT("登录信息", Art.drawable.fileway_ic_person, "当前服务器、账号与版本"),
}

@Composable
internal fun TvSettingsScreen(model: TvModel, onBack: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val controllers by remember(model) { model.remote.store.dao.controllers() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val cache by model.media.cache.state.collectAsStateWithLifecycle()
    val host by model.remote.state.collectAsStateWithLifecycle()
    val decoder by model.media.playbackPreferences.videoDecodePolicy.collectAsStateWithLifecycle()
    val interactive = LocalTvInteractive.current
    var section by remember { mutableStateOf(TvSettingsSection.INTERFACE) }
    var cacheInput by remember { mutableStateOf((cache.limitBytes / TvVideoCache.MIB).toString()) }
    val cacheLimitMib = cacheInput.toLongOrNull()
    val cacheLimitValid = cacheLimitMib != null && cacheLimitMib in TvVideoCache.MIN_MIB..TvVideoCache.MAX_MIB
    val cacheLimitError = when {
        cacheInput.isBlank() -> "请输入缓存容量"
        cacheLimitMib == null -> "请输入有效数字"
        !cacheLimitValid -> "容量需在 ${TvVideoCache.MIN_MIB}–${TvVideoCache.MAX_MIB} MB 之间"
        else -> null
    }
    val body = remember { FocusRequester() }
    val navigation = remember { TvSettingsSection.entries.associateWith { FocusRequester() } }
    val scope = rememberCoroutineScope()
    var clearConfirm by remember { mutableStateOf(false) }
    var cacheError by remember { mutableStateOf<String?>(null) }

    fun cacheOperation(action: suspend () -> Unit) {
        scope.launch {
            cacheError = null
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                cacheError = failure.message ?: "TV 缓存操作未完成"
            }
        }
    }

    LaunchedEffect(cache.limitBytes) { cacheInput = (cache.limitBytes / TvVideoCache.MIB).toString() }
    if (clearConfirm) Dialog(onDismissRequest = { clearConfirm = false }) {
        Surface(shape = TvShape) {
            Column(Modifier.widthIn(max = 440.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("清理视频缓存？", style = MaterialTheme.typography.titleLarge)
                Text("已使用 " + tvSize(cache.usedBytes) + "。当前播放继续，后台预缓冲暂停。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton("取消", { clearConfirm = false })
                    TvButton("清理", {
                        clearConfirm = false
                        cacheOperation { model.media.cache.clear() }
                    })
                }
            }
        }
    }
    var updatesOpen by rememberSaveable { mutableStateOf(false) }
    var returnToUpdates by remember { mutableStateOf(false) }
    if (updatesOpen) {
        val close = { updatesOpen = false; returnToUpdates = true }
        BackHandler(enabled = interactive, onBack = close)
        TvUpdateScreen(model, close)
        return
    }
    LaunchedEffect(Unit) { model.media.cache.inspect() }
    val settingsNavigation = rememberLazyListState()
    LaunchedEffect(returnToUpdates, interactive) {
        if (returnToUpdates && interactive) {
            settingsNavigation.scrollToItem(TvSettingsSection.UPDATES.ordinal)
            withFrameNanos {}
            navigation.getValue(TvSettingsSection.UPDATES).requestFocus()
            returnToUpdates = false
        }
    }
    val density = LocalDensity.current
    Row(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)
            .focusRestorer(navigation.getValue(TvSettingsSection.INTERFACE)).focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.width(168.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TvOptionRow(
                "返回", onBack,
                Modifier.focusProperties { right = body }.semantics { contentDescription = "返回浏览" },
                icon = Art.drawable.fileway_ic_arrow_back, showSelection = false, dense = true,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("设置", style = MaterialTheme.typography.titleLarge)
                Text("界面与设备", style = MaterialTheme.typography.bodySmall, color = LocalTvSurfaces.current.secondaryText)
            }
            LazyColumn(state = settingsNavigation, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(TvSettingsSection.entries, key = { it.name }) { category ->
                    TvOptionRow(category.title, {
                        if (category == TvSettingsSection.UPDATES) updatesOpen = true
                        else {
                            section = category
                            scope.launch { withFrameNanos {}; body.requestFocus() }
                        }
                    }, Modifier.focusRequester(navigation.getValue(category)).focusProperties { right = body },
                        icon = category.icon, selected = section == category, showSelection = false, dense = true)
                }
            }
        }
        key(section) {
            val list = rememberLazyListState()
            val reading = if (section == TvSettingsSection.DEVICE) Modifier.tvRemoteKeys { event ->
                val direction = when (event.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 1
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> -1
                    else -> 0
                }
                if (direction == 0) false else {
                    scope.launch { list.scrollBy(direction * 72 * density.density) }
                    true
                }
            }.focusProperties { canFocus = interactive }.focusable() else Modifier
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight().widthIn(max = 840.dp).focusRequester(body)
                    .focusProperties { left = navigation.getValue(section) }.focusGroup().then(reading),
                state = list,
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
            ) {
                item(key = "header") { TvSettingsHeader(section) }
                when (section) {
                    TvSettingsSection.INTERFACE -> {
                        item(key = "theme") {
                            TvSettingsGroup("主题") {
                                TvSettingsChoices("外观", listOf("深色" to state.dark, "浅色" to !state.dark)) { label ->
                                    model.setDark(label == "深色")
                                }
                            }
                        }
                        item(key = "layout") {
                            TvSettingsGroup("文件布局") {
                                TvSettingsChoices("每行显示", (2..6).map { "${it}列" to (state.gridColumns == it) }) { label ->
                                    label.removeSuffix("列").toIntOrNull()?.let(model::setColumns)
                                }
                                TvSettingsStepper("遥控器详情长按", "${state.detailsHoldMillis / 1000.0} 秒",
                                    { model.setDetailsHold(state.detailsHoldMillis - 250) },
                                    { model.setDetailsHold(state.detailsHoldMillis + 250) },
                                    decreaseEnabled = state.detailsHoldMillis > 500,
                                    increaseEnabled = state.detailsHoldMillis < 2000)
                            }
                        }
                        item(key = "display") {
                            TvSettingsGroup("显示区域") {
                                Text("四周安全边距 ${state.safePercent}%", style = MaterialTheme.typography.bodyLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TvButton("减小", { model.setSafePercent(state.safePercent - 1) }, enabled = state.safePercent > 0)
                                    TvButton("增大", { model.setSafePercent(state.safePercent + 1) }, enabled = state.safePercent < 8)
                                    TvButton("恢复 5%", { model.setSafePercent(5) })
                                }
                            }
                        }
                    }
                    TvSettingsSection.PLAYBACK -> {
                        item(key = "remote") {
                            TvSettingsGroup("遥控操作") {
                                TvSettingsStepper("跳转", "${state.seekStepSeconds} 秒",
                                    { model.setSeekStep(state.seekStepSeconds - 1) },
                                    { model.setSeekStep(state.seekStepSeconds + 1) },
                                    decreaseEnabled = state.seekStepSeconds > 3,
                                    increaseEnabled = state.seekStepSeconds < 30)
                                TvSettingsStepper("控件显示", "${state.controlsTimeoutSeconds} 秒",
                                    { model.setControlsTimeout(state.controlsTimeoutSeconds - 1) },
                                    { model.setControlsTimeout(state.controlsTimeoutSeconds + 1) },
                                    decreaseEnabled = state.controlsTimeoutSeconds > 2,
                                    increaseEnabled = state.controlsTimeoutSeconds < 30)
                            }
                        }
                        item(key = "hud") {
                            TvSettingsGroup("播放时显示") {
                                TvOptionRow("北京时间", { model.setClockAlways(!state.clockAlways) },
                                    value = if (state.clockAlways) "常驻" else "随控件显示", selected = state.clockAlways)
                                TvOptionRow("缓冲速度", { model.setSpeedAlways(!state.speedAlways) },
                                    value = if (state.speedAlways) "常驻" else "随控件显示", selected = state.speedAlways)
                            }
                        }
                        item(key = "decoder") {
                            TvSettingsGroup("解码方式") {
                                VideoDecodePolicy.entries.forEach { value ->
                                    TvOptionRow(value.label, { model.media.setVideoDecodePolicy(value) }, selected = decoder == value)
                                }
                            }
                        }
                    }
                    TvSettingsSection.CACHE -> {
                        item(key = "capacity") {
                            TvSettingsGroup("视频预缓冲") {
                                Text("已使用 ${tvSize(cache.usedBytes)} / ${tvSize(cache.limitBytes)}", style = MaterialTheme.typography.bodyLarge)
                                TvSettingsChoices("缓存容量", listOf(500 to "500 MB", 1024 to "1 GB", 2048 to "2 GB", 4096 to "4 GB")
                                    .map { (mib, label) -> label to (cache.limitBytes == mib * TvVideoCache.MIB) }) { label ->
                                    val mib = when (label) {
                                        "500 MB" -> 500L
                                        "1 GB" -> 1024L
                                        "2 GB" -> 2048L
                                        else -> 4096L
                                    }
                                    cacheOperation { model.media.cache.setLimitConfirmed(mib) }
                                }
                                cache.retention?.let { retention ->
                                    TvSectionTitle("保留时间")
                                    TvCacheMinutesRow("回退窗口", retention.rewindMinutes, 30) { minutes ->
                                        cacheOperation { model.media.cache.setRetentionConfirmed(rewindMinutes = minutes) }
                                    }
                                    TvCacheMinutesRow("退出后保留", retention.exitMinutes, 1440) { minutes ->
                                        cacheOperation { model.media.cache.setRetentionConfirmed(exitMinutes = minutes) }
                                    }
                                }
                                TvField("自定义容量（MB）", cacheInput, { cacheInput = it }, options = KeyboardOptions(keyboardType = KeyboardType.Number))
                                cacheLimitError?.let { message ->
                                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                                }
                                TvButton("应用容量", { cacheLimitMib?.let { mib ->
                                    cacheOperation { model.media.cache.setLimitConfirmed(mib) }
                                } }, enabled = cacheLimitValid)
                            }
                        }
                        item(key = "maintenance") {
                            TvSettingsGroup("缓存维护") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TvButton("清理视频缓存", { clearConfirm = true }, enabled = !cache.clearing)
                                    if (cache.prefetchPaused) TvButton("恢复预缓冲", {
                                        cacheOperation { model.media.cache.resumePrefetch() }
                                    })
                                }
                                (cacheError ?: cache.feedback)?.let { message ->
                                    Text(message, style = MaterialTheme.typography.bodyMedium,
                                        color = if (cache.error != null) MaterialTheme.colorScheme.error else LocalTvSurfaces.current.secondaryText)
                                }
                            }
                        }
                    }
                    TvSettingsSection.REMOTE -> {
                        item(key = "remote") {
                            TvSettingsGroup("手机遥控") {
                                TvOptionRow("连接手机", model.remote::requestPairing, supporting = host.error,
                                    icon = Art.drawable.fileway_ic_phone, destination = true)
                                if (controllers.isEmpty()) Text("尚未配对手机", style = MaterialTheme.typography.bodyMedium,
                                    color = LocalTvSurfaces.current.secondaryText)
                                controllers.forEach { controller ->
                                    TvOptionRow("解除配对", { model.remote.revokeController(controller.id) }, supporting = controller.name)
                                }
                            }
                        }
                    }
                    TvSettingsSection.DEVICE -> {
                        item(key = "device") {
                            TvSettingsGroup("设备信息") {
                                var info by remember { mutableStateOf("正在读取…") }
                                val context = LocalContext.current
                                LaunchedEffect(interactive) { if (interactive) info = withContext(Dispatchers.IO) { tvDeviceInfo(context) } }
                                Text(info, style = MaterialTheme.typography.bodyMedium, color = LocalTvSurfaces.current.secondaryText)
                            }
                        }
                    }
                    TvSettingsSection.ACCOUNT -> {
                        item(key = "account") {
                            TvSettingsGroup("登录信息") {
                                Text(state.profile?.address.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                                Text(model.context?.api?.identity?.username.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                                TvOptionRow("退出登录", model::disconnect, icon = Art.drawable.fileway_ic_arrow_back)
                                Text("栖卷 TV ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall,
                                    color = LocalTvSurfaces.current.secondaryText)
                            }
                        }
                    }
                    TvSettingsSection.UPDATES -> Unit
                }
            }
        }
    }
}

@Composable private fun TvSettingsHeader(section: TvSettingsSection) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(section.title, style = MaterialTheme.typography.displaySmall)
        Text(section.subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalTvSurfaces.current.secondaryText)
    }
}

@Composable private fun TvSettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(TvShape).background(colors.surface)
            .border(1.dp, LocalTvSurfaces.current.outline, TvShape).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TvSectionTitle(title)
        content()
    }
}

@Composable private fun TvSettingsChoices(title: String, choices: List<Pair<String, Boolean>>, onChoose: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        FlowRow(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (label, selected) -> TvButton(label, { onChoose(label) }, selected = selected) }
        }
    }
}

@Composable private fun TvSettingsStepper(
    label: String,
    value: String,
    decrease: () -> Unit,
    increase: () -> Unit,
    decreaseEnabled: Boolean = true,
    increaseEnabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.labelMedium, color = LocalTvSurfaces.current.secondaryText)
        TvButton("减小", decrease, enabled = decreaseEnabled)
        TvButton("增大", increase, enabled = increaseEnabled)
    }
}

@Composable private fun TvCacheMinutesRow(label: String, value: Int, maximum: Int, save: (Int) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    TvOptionRow(label, { editing = true }, value = "$value 分钟", destination = true)
    if (editing) TvCacheMinutesDialog(label, value, maximum, onDismiss = { editing = false }) { minutes ->
        editing = false
        save(minutes)
    }
}

@Composable private fun TvCacheMinutesDialog(label: String, value: Int, maximum: Int, onDismiss: () -> Unit, save: (Int) -> Unit) {
    var input by remember { mutableStateOf(value.toString()) }
    val draft = input.toIntOrNull()?.takeIf { it in 0..maximum }
    val decrease = remember { FocusRequester() }
    val confirm = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        TvRemoteDialogKeys(onDismiss)
        Surface(shape = TvShape) {
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvButton("减小", { input = ((draft ?: value) - 1).coerceAtLeast(0).toString() },
                        Modifier.focusRequester(decrease).focusProperties { if (draft != null && draft != value) down = confirm })
                    Text(draft?.let { "$it 分钟" } ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    TvButton("增大", { input = ((draft ?: value) + 1).coerceAtMost(maximum).toString() },
                        Modifier.focusProperties { if (draft != null && draft != value) down = confirm })
                }
                TvField("分钟（0–$maximum）", input, { input = it.filter(Char::isDigit).take(4) },
                    options = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TvButton("取消", onDismiss)
                    TvButton("保存", { draft?.let(save) }, Modifier.focusRequester(confirm), enabled = draft != null && draft != value)
                }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos {}; decrease.requestFocus() }
    }
}

fun tvDeviceInfo(context: Context): String {
    val manager = context.getSystemService(ActivityManager::class.java)
    val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    val types = display?.hdrCapabilities?.supportedHdrTypes?.joinToString {
        when (it) {
            Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
            Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
            else -> "类型 $it"
        }
    }.orEmpty().ifEmpty { "未报告 HDR 类型" }
    val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder && it.supportedTypes.any { type -> type.startsWith("video/") } }
    return "${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}\n" +
        "ABI：${Build.SUPPORTED_ABIS.joinToString()}\nRAM：${memory.totalMem / 1048576} MB\n可用内存：${memory.availMem / 1048576} MB\n低内存设备：${if (manager.isLowRamDevice) "是" else "否"}\n" +
        "逻辑画布：${context.resources.configuration.screenWidthDp} × ${context.resources.configuration.screenHeightDp} dp\n显示 HDR 能力：$types\n音频扩展：${tvAudioExtensionInfo()}\n\n视频解码器\n${codecs.joinToString("\n") { it.name }}"
}

internal fun tvAudioExtensionInfo(): String {
    if (!BuildConfig.HAS_FFMPEG_AUDIO) return "未包含，仅使用系统解码器"
    return runCatching {
        val library = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
        if (library.getMethod("isAvailable").invoke(null) == true)
            "已加载 FFmpeg ${library.getMethod("getVersion").invoke(null)}"
        else "已包含，原生库加载失败"
    }.getOrElse { "已包含，加载检查失败" }
}

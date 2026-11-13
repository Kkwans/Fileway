package io.github.kkwans.fileway.tv

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import android.view.KeyEvent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import io.github.kkwans.fileway.design.R as Art
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import io.github.kkwans.nasfilebrowser.data.VideoDecodePolicy
import kotlinx.coroutines.*

private enum class TvSettingsSection(val title:String,val icon:Int) {
    INTERFACE("界面",Art.drawable.fileway_ic_settings),
    PLAYBACK("播放",Art.drawable.fileway_ic_play_arrow),
    CACHE("视频缓存",Art.drawable.fileway_ic_storage),
    REMOTE("手机遥控",Art.drawable.fileway_ic_phone),
    DEVICE("设备信息",Art.drawable.fileway_ic_info),
    UPDATES("应用更新",Art.drawable.fileway_ic_update),
    ACCOUNT("登录信息",Art.drawable.fileway_ic_person),
}

@Composable fun TvSettingsScreen(model: TvModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val controllers by remember(model) { model.remote.store.dao.controllers() }.collectAsStateWithLifecycle(initialValue=emptyList())
    val cache by model.media.cache.state.collectAsStateWithLifecycle()
    val host by model.remote.state.collectAsStateWithLifecycle()
    val decoder by model.media.playbackPreferences.videoDecodePolicy.collectAsStateWithLifecycle()
    val interactive=LocalTvInteractive.current
    var section by remember { mutableStateOf(TvSettingsSection.INTERFACE) }
    var cacheInput by remember { mutableStateOf((cache.limitBytes/1048576).toString()) }
    val body=remember { FocusRequester() }
    val navigation=remember { TvSettingsSection.entries.associateWith { FocusRequester() } }
    val scope=rememberCoroutineScope()
    var clearConfirm by remember { mutableStateOf(false) }
    var cacheError by remember { mutableStateOf<String?>(null) }
    fun cacheOperation(action: suspend () -> Unit) {
        scope.launch {
            cacheError = null
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { cacheError = failure.message ?: "TV 缓存操作未完成" }
        }
    }
    LaunchedEffect(cache.limitBytes) { cacheInput = (cache.limitBytes / TvVideoCache.MIB).toString() }
    if (clearConfirm) Dialog(onDismissRequest = { clearConfirm = false }) {
        Surface(shape = TvShape) {
            Column(Modifier.widthIn(max = 440.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("清理视频缓存？", style = MaterialTheme.typography.titleLarge)
                Text("已用 " + tvSize(cache.usedBytes) + "。当前播放继续，后台预缓冲暂停。")
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
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    val style=MaterialTheme.typography.bodyLarge
    val labelWidth = with(density) {
        TvSettingsSection.entries.maxOf { measurer.measure(AnnotatedString(it.title), style).size.width }.toDp()
    }
    // DenseListItem adds 12dp on each side and two 8dp gaps. Allow 2dp for rounding.
    val measuredWidth = labelWidth + maxOf(ListItemDefaults.IconSizeDense, TvOptionIconSize) + 42.dp
    Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
        LazyColumn(Modifier.width(measuredWidth),state=settingsNavigation,verticalArrangement=Arrangement.spacedBy(4.dp)) {
            items(TvSettingsSection.entries,key={it.name}) { category ->
                TvOptionRow(category.title,{
                    if (category == TvSettingsSection.UPDATES) updatesOpen = true
                    else {
                        section=category
                        scope.launch { withFrameNanos {};body.requestFocus() }
                    }
                },Modifier.focusRequester(navigation.getValue(category)).focusProperties { right=body },
                    icon=category.icon,selected=section==category,showSelection=false,dense=true)
            }
        }
        key(section) {
        val list=rememberLazyListState()
        val reading=if(section==TvSettingsSection.DEVICE)Modifier.tvRemoteKeys { event ->
            val direction=when(event.nativeKeyEvent.keyCode) { KeyEvent.KEYCODE_DPAD_DOWN->1;KeyEvent.KEYCODE_DPAD_UP->-1;else->0 }
            if(direction==0)false else { scope.launch { list.scrollBy(direction*72*density.density) };true }
        }.focusProperties { canFocus=interactive }.focusable() else Modifier
        LazyColumn(Modifier.weight(1f).fillMaxHeight().focusRequester(body)
            .focusProperties { left=navigation.getValue(section) }.focusGroup().then(reading),state=list,
            verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=12.dp)) {
            when(section) {
                TvSettingsSection.INTERFACE -> {
                    item { TvSectionTitle("主题") }
                    item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TvButton("深色",{model.setDark(true)},selected=state.dark)
                        TvButton("浅色",{model.setDark(false)},selected=!state.dark)
                    } }
                    item { TvSectionTitle("文件布局") }
                    item { Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        (2..6).forEach { columns -> TvButton("${columns}列",{model.setColumns(columns)},Modifier.weight(1f),selected=state.gridColumns==columns) }
                    } }
                    item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("遥控器详情长按 ${state.detailsHoldMillis / 1000.0} 秒",Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                        TvButton("减小",{model.setDetailsHold(state.detailsHoldMillis-250)},enabled=state.detailsHoldMillis>500)
                        TvButton("增大",{model.setDetailsHold(state.detailsHoldMillis+250)},enabled=state.detailsHoldMillis<2000)
                    } }
                    item { TvSectionTitle("显示区域") }
                    item { Text("四周安全边距 ${state.safePercent}%",style=MaterialTheme.typography.bodyLarge) }
                    item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TvButton("减小",{model.setSafePercent(state.safePercent-1)},enabled=state.safePercent>0)
                        TvButton("增大",{model.setSafePercent(state.safePercent+1)},enabled=state.safePercent<8)
                        TvButton("恢复 5%",{model.setSafePercent(5)})
                    } }
                }
                TvSettingsSection.PLAYBACK -> {
                    item { TvSectionTitle("遥控操作") }
                    item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("跳转 ${state.seekStepSeconds} 秒",Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                        TvButton("减小",{model.setSeekStep(state.seekStepSeconds-1)},enabled=state.seekStepSeconds>3)
                        TvButton("增大",{model.setSeekStep(state.seekStepSeconds+1)},enabled=state.seekStepSeconds<30)
                    } }
                    item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("控件显示 ${state.controlsTimeoutSeconds} 秒",Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                        TvButton("减小",{model.setControlsTimeout(state.controlsTimeoutSeconds-1)},enabled=state.controlsTimeoutSeconds>2)
                        TvButton("增大",{model.setControlsTimeout(state.controlsTimeoutSeconds+1)},enabled=state.controlsTimeoutSeconds<30)
                    } }
                    item { TvSectionTitle("播放时显示") }
                    item { TvOptionRow("北京时间",{model.setClockAlways(!state.clockAlways)},value=if(state.clockAlways)"常驻" else "随控件显示",selected=state.clockAlways) }
                    item { TvOptionRow("缓冲速度",{model.setSpeedAlways(!state.speedAlways)},value=if(state.speedAlways)"常驻" else "随控件显示",selected=state.speedAlways) }
                    item { TvSectionTitle("解码方式") }
                    items(VideoDecodePolicy.entries,key={it.name}) { value ->
                        TvOptionRow(value.label,{model.media.setVideoDecodePolicy(value)},selected=decoder==value)
                    }
                }
                TvSettingsSection.CACHE -> {
                    item { TvSectionTitle("视频预缓冲") }
                    item { Text("已使用 ${tvSize(cache.usedBytes)} / ${tvSize(cache.limitBytes)}",style=MaterialTheme.typography.bodyLarge) }
                    item { Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        listOf(500,1024,2048,4096).forEach { mib -> TvButton(if(mib<1024)"500 MB" else "${mib/1024} GB",{
                            cacheOperation { model.media.cache.setLimitConfirmed(mib.toLong()) }
                        },Modifier.weight(1f),selected=cache.limitBytes==mib*1048576L) }
                    } }
                    item { TvField("自定义容量（MB）",cacheInput,{cacheInput=it},options=KeyboardOptions(keyboardType=KeyboardType.Number)) }
                    item { TvButton("应用容量",{cacheInput.toLongOrNull()?.takeIf { it in TvVideoCache.MIN_MIB..TvVideoCache.MAX_MIB }?.let { mib ->
                        cacheOperation { model.media.cache.setLimitConfirmed(mib) }
                    }},enabled=cacheInput.toLongOrNull()?.let { it in TvVideoCache.MIN_MIB..TvVideoCache.MAX_MIB }==true) }
                    cache.retention?.let { retention ->
                        item { TvSectionTitle("保留时间") }
                        item {
                            TvCacheMinutesRow("回退窗口", retention.rewindMinutes, 30) { minutes ->
                                cacheOperation { model.media.cache.setRetentionConfirmed(rewindMinutes = minutes) }
                            }
                        }
                        item {
                            TvCacheMinutesRow("退出后保留", retention.exitMinutes, 1440) { minutes ->
                                cacheOperation { model.media.cache.setRetentionConfirmed(exitMinutes = minutes) }
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TvButton("清理视频缓存", { clearConfirm = true }, enabled = !cache.clearing)
                            if (cache.prefetchPaused) TvButton("恢复预缓冲", {
                                cacheOperation { model.media.cache.resumePrefetch() }
                            })
                        }
                    }
                    (cacheError ?: cache.feedback)?.let { message -> item {
                        Text(message, style = MaterialTheme.typography.bodyMedium,
                            color = if (cache.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                }
                TvSettingsSection.REMOTE -> {
                    item { TvOptionRow("连接手机",model.remote::requestPairing,supporting=host.error,icon=Art.drawable.fileway_ic_phone,destination=true) }
                    items(controllers,key={it.id}) { controller -> TvOptionRow("解除配对",{model.remote.revokeController(controller.id)},supporting=controller.name) }
                }
                TvSettingsSection.DEVICE -> {
                    item {
                        var info by remember { mutableStateOf("正在读取…") }
                        val context=LocalContext.current
                        LaunchedEffect(interactive) { if(interactive)info=withContext(Dispatchers.IO) { tvDeviceInfo(context) } }
                        Text(info,style=MaterialTheme.typography.bodyMedium)
                    }
                }
                TvSettingsSection.ACCOUNT -> {
                    item { Text(state.profile?.address.orEmpty(),style=MaterialTheme.typography.bodyLarge) }
                    item { Text(model.context?.api?.identity?.username.orEmpty(),style=MaterialTheme.typography.bodyLarge) }
                    item { TvOptionRow("退出登录",model::disconnect,icon=Art.drawable.fileway_ic_arrow_back) }
                    item { Text("栖卷 TV ${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.bodySmall,color=LocalTvSurfaces.current.secondaryText) }
                }
                TvSettingsSection.UPDATES -> Unit
            }
        }
        }
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
    Dialog(onDismissRequest = onDismiss) {
        TvRemoteDialogKeys(onDismiss)
        Surface(shape = TvShape) {
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvButton("减小", { input = ((draft ?: value) - 1).coerceAtLeast(0).toString() }, Modifier.focusRequester(decrease))
                    Text(draft?.let { "$it 分钟" } ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    TvButton("增大", { input = ((draft ?: value) + 1).coerceAtMost(maximum).toString() })
                }
                TvField("分钟（0–$maximum）", input, { input = it.filter(Char::isDigit).take(4) },
                    options = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TvButton("取消", onDismiss)
                    TvButton("保存", { draft?.let(save) }, enabled = draft != null && draft != value)
                }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos {}; decrease.requestFocus() }
    }
}

fun tvDeviceInfo(context: Context): String {
    val manager=context.getSystemService(ActivityManager::class.java)
    val memory=ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    val display=context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    val types=display?.hdrCapabilities?.supportedHdrTypes?.joinToString{when(it){Display.HdrCapabilities.HDR_TYPE_HDR10->"HDR10";Display.HdrCapabilities.HDR_TYPE_HLG->"HLG";Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION->"Dolby Vision";else->"类型 $it"}}.orEmpty().ifEmpty{"未报告 HDR 类型"}
    val codecs=MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter{!it.isEncoder&&it.supportedTypes.any{type->type.startsWith("video/")}}
    return "${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}\n"+
        "ABI：${Build.SUPPORTED_ABIS.joinToString()}\nRAM：${memory.totalMem/1048576} MB\n可用内存：${memory.availMem/1048576} MB\n低内存设备：${if(manager.isLowRamDevice)"是" else "否"}\n"+
        "逻辑画布：${context.resources.configuration.screenWidthDp} × ${context.resources.configuration.screenHeightDp} dp\n显示 HDR 能力：$types\n音频扩展：${tvAudioExtensionInfo()}\n\n视频解码器\n${codecs.joinToString("\n"){it.name}}"
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

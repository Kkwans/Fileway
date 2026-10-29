package io.github.kkwans.fileway.tv

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
    LaunchedEffect(Unit) { model.media.cache.inspect() }
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    val style=MaterialTheme.typography.bodyLarge
    // Width follows the actual labels plus icon/check slots, including font scale.
    val measuredWidth=with(density) { TvSettingsSection.entries.maxOf { measurer.measure(AnnotatedString(it.title),style).size.width }.toDp() } + 66.dp
    Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
        LazyColumn(Modifier.width(measuredWidth),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            items(TvSettingsSection.entries,key={it.name}) { category ->
                TvOptionRow(category.title,{
                    section=category
                    scope.launch { withFrameNanos {};body.requestFocus() }
                },Modifier.focusRequester(navigation.getValue(category)).focusProperties { right=body },
                    icon=category.icon,selected=section==category,showSelection=false)
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
                    item { TvSectionTitle("显示区域") }
                    item { Text("四周安全边距 ${state.safePercent}%",style=MaterialTheme.typography.bodyLarge) }
                    item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TvButton("减小",{model.setSafePercent(state.safePercent-1)},enabled=state.safePercent>0)
                        TvButton("增大",{model.setSafePercent(state.safePercent+1)},enabled=state.safePercent<8)
                        TvButton("恢复 5%",{model.setSafePercent(5)})
                    } }
                }
                TvSettingsSection.PLAYBACK -> {
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
                        listOf(512,1024,2048,4096).forEach { mib -> TvButton(if(mib<1024)"512 MB" else "${mib/1024} GB",{
                            model.media.cache.setLimit(mib*1048576L);cacheInput=mib.toString()
                        },Modifier.weight(1f),selected=cache.limitBytes==mib*1048576L) }
                    } }
                    item { TvField("自定义容量（MB）",cacheInput,{cacheInput=it},options=KeyboardOptions(keyboardType=KeyboardType.Number)) }
                    item { TvButton("应用容量",{cacheInput.toLongOrNull()?.takeIf { it in 64..65536 }?.let { model.media.cache.setLimit(it*1048576) }},enabled=cacheInput.toLongOrNull()?.let { it in 64L..65536L }==true) }
                    cache.error?.let { message -> item { Text(message,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error) } }
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
            }
        }
        }
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

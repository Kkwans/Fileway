package io.github.kkwans.fileway.tv

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

@Composable fun TvSettingsScreen(model: TvModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var deviceOpen by remember{mutableStateOf(false)}
    LaunchedEffect(Unit){model.media.cache.inspect()}
    val cache by model.media.cache.state.collectAsStateWithLifecycle()
    var cacheInput by remember{mutableStateOf((cache.limitBytes/1048576).toString())}
    val decoder by model.media.playbackPreferences.videoDecodePolicy.collectAsStateWithLifecycle()
    LazyColumn(verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(bottom=16.dp)) {
        item{TvSectionTitle("界面")}
        item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){TvButton("深色",{model.setDark(true)},selected=state.dark);TvButton("浅色",{model.setDark(false)},selected=!state.dark)}}
        item{TvSectionTitle("文件布局")}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){(2..6).forEach{columns->TvButton("${columns}列",{model.setColumns(columns)},selected=state.gridColumns==columns)}}}
        item{TvSectionTitle("播放时显示")}
        item{TvButton("北京时间 · ${if(state.clockAlways)"常驻" else "随控件显示"}",{model.setClockAlways(!state.clockAlways)},Modifier.fillMaxWidth())}
        item{TvButton("缓冲速度 · ${if(state.speedAlways)"常驻" else "随控件显示"}",{model.setSpeedAlways(!state.speedAlways)},Modifier.fillMaxWidth())}
        item{TvSectionTitle("显示区域")}
        item{Text("四周安全边距 ${state.safePercent}%",style=MaterialTheme.typography.bodyMedium)}
        item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){TvButton("减小",{model.setSafePercent(state.safePercent-1)},enabled=state.safePercent>0);TvButton("增大",{model.setSafePercent(state.safePercent+1)},enabled=state.safePercent<8);TvButton("恢复 5%",{model.setSafePercent(5)})}}
        item{TvSectionTitle("视频预缓冲")}
        item{Text("${tvSize(cache.usedBytes)} / ${tvSize(cache.limitBytes)}",style=MaterialTheme.typography.bodyMedium)}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(512,1024,2048,4096).forEach{mib->TvButton(if(mib<1024)"512 MB" else "${mib/1024} GB",{model.media.cache.setLimit(mib*1048576L);cacheInput=mib.toString()},selected=cache.limitBytes==mib*1048576L)}}}
        item{TvField("自定义容量（MB）",cacheInput,{cacheInput=it},options=KeyboardOptions(keyboardType=KeyboardType.Number))}
        item{TvButton("应用容量",{cacheInput.toLongOrNull()?.takeIf{it in 64..65536}?.let{model.media.cache.setLimit(it*1048576)}},enabled=cacheInput.toLongOrNull()?.let{it in 64L..65536L}==true)}
        cache.error?.let{message->item{Text(message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}}
        item{TvSectionTitle("播放")}
        item{TvButton("设备与播放信息",{deviceOpen=true},Modifier.fillMaxWidth())}
        item{TvSectionTitle("解码方式")}
        items(VideoDecodePolicy.entries.size){index->val value=VideoDecodePolicy.entries[index];TvButton(value.label,{model.media.setVideoDecodePolicy(value)},Modifier.fillMaxWidth(),selected=decoder==value)}
        item{TvSectionTitle("登录信息")}
        item{Text(state.profile?.address.orEmpty(),style=MaterialTheme.typography.bodyMedium)}
        item{Text(model.context?.api?.identity?.username.orEmpty(),style=MaterialTheme.typography.bodyMedium)}
        item{TvButton("断开并退出登录",model::disconnect,Modifier.fillMaxWidth())}
        item{Text("栖卷 TV ${BuildConfig.VERSION_NAME} · API 28+",style=MaterialTheme.typography.bodySmall)}
    }
    if(deviceOpen)TvPanel("设备与播放信息",{deviceOpen=false}) {
        var info by remember{mutableStateOf("正在读取…")};val context=LocalContext.current
        LaunchedEffect(Unit){info=withContext(Dispatchers.IO){tvDeviceInfo(context)}}
        LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)){item{Text(info,style=MaterialTheme.typography.bodyMedium)}}
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
        "逻辑画布：${context.resources.configuration.screenWidthDp} × ${context.resources.configuration.screenHeightDp} dp\n显示 HDR 能力：$types\n\n视频解码器\n${codecs.joinToString("\n"){it.name}}"
}

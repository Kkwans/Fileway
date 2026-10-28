package io.github.kkwans.fileway.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import io.github.kkwans.fileway.design.R as Art

/** Wholphin PlaybackControls/Controller composition: title, timeline, then
 * settings | centered transport | captions/audio. Fileway renders its own
 * controls and icons; no upstream implementation or artwork is embedded. */
@Composable internal fun TvPlaybackControls(
    title: String, subtitle: String?, playing: Boolean, enabled: Boolean,
    previous: Boolean, next: Boolean, position: Long, duration: Long, buffered: Long,cachedRanges:List<TvCachedRange>,
    playFocus: FocusRequester, settingsFocus: FocusRequester,
    actions: MutableMap<String, FocusRequester>,
    onToggle: () -> Unit, onSeek: (Long) -> Unit, onPrevious: () -> Unit,
    onNext: () -> Unit, onPanel: (String) -> Unit, modifier: Modifier = Modifier,
) {
    val gradient = remember { Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha=.22f), Color.Black.copy(alpha=.76f))) }
    Column(modifier.fillMaxWidth().background(gradient).padding(start=48.dp,end=48.dp,top=42.dp,bottom=24.dp),
        verticalArrangement=Arrangement.spacedBy(4.dp)) {
        if(title.isNotBlank())Text(title,style=MaterialTheme.typography.titleLarge.copy(fontSize=26.sp,lineHeight=34.sp),maxLines=1,overflow=TextOverflow.Ellipsis,color=Color.White)
        subtitle?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=Color.White.copy(alpha=.72f),maxLines=1,overflow=TextOverflow.Ellipsis) }
        TvTimeline(position,duration,buffered,onSeek,Modifier.fillMaxWidth().focusProperties{down=playFocus},cachedRanges)
        Box(Modifier.fillMaxWidth().height(72.dp)) {
            Row(Modifier.align(Alignment.CenterStart).focusGroup(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                actions["设置"]=settingsFocus
                TvPlaybackAction("设置",Art.drawable.fileway_ic_settings,{onPanel("设置")},Modifier.focusRequester(settingsFocus))
                val queueFocus=remember{FocusRequester()};actions["队列"]=queueFocus
                TvPlaybackAction("队列",Art.drawable.fileway_ic_playlist_play,{onPanel("队列")},Modifier.focusRequester(queueFocus))
            }
            Row(Modifier.align(Alignment.Center).focusGroup(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TvPlaybackAction("上一项",Art.drawable.fileway_ic_skip_previous,onPrevious,enabled=previous&&enabled)
                TvPlaybackAction("后退 10 秒",Art.drawable.fileway_ic_replay_10,{onSeek(-10_000)},enabled=enabled)
                TvPlaybackAction(if(playing)"暂停" else "播放",if(playing)Art.drawable.fileway_ic_pause else Art.drawable.fileway_ic_play_arrow,onToggle,Modifier.focusRequester(playFocus),enabled=enabled,primary=true)
                TvPlaybackAction("前进 10 秒",Art.drawable.fileway_ic_forward_10,{onSeek(10_000)},enabled=enabled)
                TvPlaybackAction("下一项",Art.drawable.fileway_ic_skip_next,onNext,enabled=next&&enabled)
            }
            Row(Modifier.align(Alignment.CenterEnd).focusGroup(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("字幕" to Art.drawable.fileway_ic_subtitles,"音轨" to Art.drawable.fileway_ic_audio,"信息" to Art.drawable.fileway_ic_info).forEach{(name,icon)->
                    val focus=remember(name){FocusRequester()};actions[name]=focus
                    TvPlaybackAction(name,icon,{onPanel(name)},Modifier.focusRequester(focus))
                }
            }
        }
    }
}

@Composable internal fun TvPlaybackAction(label:String,icon:Int,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,primary:Boolean=false) {
    var focused by remember{mutableStateOf(false)}
    val shape=if(primary)CircleShape else RoundedCornerShape(12.dp)
    Column(modifier.width(48.dp).onFocusChanged{focused=it.hasFocus},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Button(onClick=onClick,enabled=enabled,modifier=Modifier.size(48.dp).semantics{contentDescription=label},
            contentPadding=PaddingValues(10.dp),shape=ButtonDefaults.shape(shape),scale=ButtonDefaults.scale(focusedScale=1f),
            colors=ButtonDefaults.colors(containerColor=Color.White.copy(alpha=if(primary).14f else .04f),contentColor=Color.White,
                focusedContainerColor=if(primary)Color.White else Color.White.copy(alpha=.18f),focusedContentColor=if(primary)Color(0xFF10161F) else Color.White,
                disabledContainerColor=Color.Transparent,disabledContentColor=Color.White.copy(alpha=.38f))) {
            Icon(painterResource(icon),null,Modifier.size(28.dp))
        }
        Text(if(focused)label else "",modifier=Modifier.requiredWidth(100.dp),style=MaterialTheme.typography.bodySmall.copy(fontSize=12.sp,lineHeight=16.sp),maxLines=1,color=Color.White,
            textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    }
}


@Composable internal fun BoxScope.TvPlaybackTelemetry(showTime:Boolean,showSpeed:Boolean,bytesPerSecond:Long) {
    var time by remember{mutableStateOf("")}
    LaunchedEffect(showTime) {
        if(showTime)while(true) {
            time=java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            kotlinx.coroutines.delay(60_000-System.currentTimeMillis()%60_000)
        }
    }
    val style=MaterialTheme.typography.bodySmall.copy(fontFeatureSettings="tnum",shadow=androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha=.7f),androidx.compose.ui.geometry.Offset(0f,1f),1f))
    if(showTime)Text(time,Modifier.align(Alignment.TopStart).padding(start=48.dp,top=22.dp),style=style,color=Color.White.copy(alpha=.85f))
    if(showSpeed)Text(tvSpeed(bytesPerSecond),Modifier.align(Alignment.TopEnd).padding(end=48.dp,top=22.dp),style=style,color=Color.White.copy(alpha=.85f))
}
internal fun tvSpeed(bytes:Long):String=if(bytes>=1_048_576)"%.1f MB/s".format(bytes/1_048_576.0) else "${bytes.coerceAtLeast(0)/1024} KB/s"

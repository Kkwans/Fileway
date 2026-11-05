package io.github.kkwans.fileway.tv

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import io.github.kkwans.fileway.design.R as Art
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayPalette
import io.github.kkwans.nasfilebrowser.ui.designsystem.drawMediaProgressTrack

/** Fileway phone landscape structure: time/track/time, transport, then media options. */
@Composable internal fun TvPlaybackControls(
    playing:Boolean,enabled:Boolean,seekable:Boolean,audio:Boolean,rate:Float,previous:Boolean,next:Boolean,
    position:Long,duration:Long,buffered:Long,cachedRanges:List<TvCachedRange>,
    playFocus:FocusRequester,actions:MutableMap<TvPlayerPage,FocusRequester>,
    onToggle:()->Unit,onSeek:(Long)->Unit,onSeekTo:(Long)->Unit,onPrevious:()->Unit,onNext:()->Unit,
    onPanel:(TvPlayerPage)->Unit,seekStepSeconds:Int=10,safePercent:Int=5,modifier:Modifier=Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val inset=(maxWidth*safePercent.coerceIn(0,8)/100).coerceAtLeast(16.dp)
        Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.42f),Color.Black.copy(alpha=.76f))))
            .padding(start=inset,end=inset,top=16.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            TvTimeline(position,duration,buffered,onSeek,Modifier.fillMaxWidth().focusProperties{down=playFocus},cachedRanges,onSeekTo,seekStepSeconds,seekable)
            Row(Modifier.fillMaxWidth().focusGroup(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                TvPlaybackAction("上一项",Art.drawable.fileway_ic_skip_previous,onPrevious,enabled=previous&&enabled)
                TvPlaybackAction(if(playing)"暂停"else"播放",if(playing)Art.drawable.fileway_ic_pause else Art.drawable.fileway_ic_play_arrow,onToggle,Modifier.focusRequester(playFocus),enabled=enabled,primary=true)
                TvPlaybackAction("下一项",Art.drawable.fileway_ic_skip_next,onNext,enabled=next&&enabled)
                Spacer(Modifier.weight(1f))
                val options=buildList {
                    add(TvPlayerPage.AUDIO to Art.drawable.fileway_ic_audio)
                    if(!audio)add(TvPlayerPage.SUBTITLE to Art.drawable.fileway_ic_subtitles)
                    add(TvPlayerPage.SPEED to Art.drawable.fileway_ic_speed)
                    add(TvPlayerPage.QUEUE to Art.drawable.fileway_ic_playlist_play)
                    add(TvPlayerPage.SETTINGS to Art.drawable.fileway_ic_settings)
                }
                options.forEach{(page,icon)->
                    val focus=remember(page){FocusRequester()};actions[page]=focus
                    TvPlaybackAction(if(page==TvPlayerPage.SPEED)"${rate}×"else when(page){TvPlayerPage.SETTINGS->"设置";TvPlayerPage.QUEUE->"列表";else->page.title},icon,{onPanel(page)},Modifier.focusRequester(focus))
                }
            }
        }
    }
}

/** Stable geometry; white-fill focus boxes are replaced by the phone's blue/graphite roles. */
@Composable internal fun TvPlaybackAction(label:String,icon:Int,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,primary:Boolean=false,compact:Boolean=false) {
    var focused by remember{mutableStateOf(false)}
    val interaction=remember{MutableInteractionSource()}
    val width=if(compact)48.dp else 56.dp
    Column(modifier.width(width).onFocusChanged{focused=it.hasFocus},horizontalAlignment=Alignment.CenterHorizontally) {
        Button(onClick=onClick,enabled=enabled,interactionSource=interaction,
            modifier=Modifier.size(if(primary)56.dp else 48.dp).tvTouch(onClick,enabled,interaction).semantics{contentDescription=label},
            contentPadding=PaddingValues(if(primary)12.dp else 10.dp),shape=ButtonDefaults.shape(TvShape),scale=ButtonDefaults.scale(focusedScale=1f),
            border=ButtonDefaults.border(focusedBorder=Border(androidx.compose.foundation.BorderStroke(2.dp,FilewayPalette.darkAccent),shape=TvShape)),
            colors=ButtonDefaults.colors(containerColor=Color.Transparent,contentColor=Color.White,
                focusedContainerColor=FilewayPalette.darkAccent.copy(alpha=.22f),focusedContentColor=Color.White,
                disabledContainerColor=Color.Transparent,disabledContentColor=Color.White.copy(alpha=.3f))) {
            Icon(painterResource(icon),null,Modifier.size(if(primary)32.dp else 26.dp))
        }
        if(!compact)Text(if(focused)label else "",Modifier.requiredWidth(104.dp),style=tvTextStyle(14,20),maxLines=1,color=Color.White,
            textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable internal fun TvTimeline(position:Long,duration:Long,buffered:Long,onSeekBy:(Long)->Unit,modifier:Modifier=Modifier,cachedRanges:List<TvCachedRange> = emptyList(),onSeekTo:(Long)->Unit={onSeekBy(it-position)},seekStepSeconds:Int=10,enabled:Boolean=true) {
    var focused by remember{mutableStateOf(false)}
    var preview by remember(duration){mutableStateOf<Long?>(null)}
    val seek by rememberUpdatedState(onSeekTo)
    val seekBy by rememberUpdatedState(onSeekBy)
    val shown=preview?:position
    Row(modifier,verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        val timeStyle=tvTextStyle(16,22).copy(fontFeatureSettings="tnum",color=Color.White.copy(alpha=.8f))
        Text(tvTime(shown),style=timeStyle)
        Canvas(Modifier.weight(1f).height(48.dp).onFocusChanged{focused=it.isFocused}
            .pointerInput(duration,enabled){if(duration>0&&enabled)detectTapGestures{point->seek((duration*(point.x/size.width.coerceAtLeast(1)).coerceIn(0f,1f)).toLong())}}
            .pointerInput(duration,enabled){if(duration>0&&enabled)detectHorizontalDragGestures(onDragStart={point->preview=(duration*(point.x/size.width.coerceAtLeast(1)).coerceIn(0f,1f)).toLong()},
                onHorizontalDrag={change,_->change.consume();preview=(duration*(change.position.x/size.width.coerceAtLeast(1)).coerceIn(0f,1f)).toLong()},
                onDragEnd={preview?.let(seek);preview=null},onDragCancel={preview=null})}
            .tvRemoteKeys{event->when(event.nativeKeyEvent.keyCode){
                KeyEvent.KEYCODE_DPAD_LEFT->{if(duration>0&&enabled)seekBy(-tvSeekDelta(event.nativeKeyEvent,seekStepSeconds));true}
                KeyEvent.KEYCODE_DPAD_RIGHT->{if(duration>0&&enabled)seekBy(tvSeekDelta(event.nativeKeyEvent,seekStepSeconds));true}
                else->false
            }}.semantics{contentDescription="播放进度";stateDescription="${tvTime(shown)} / ${tvTime(duration)}";
                if(!enabled)disabled();progressBarRangeInfo=ProgressBarRangeInfo(if(duration>0)(shown.toFloat()/duration).coerceIn(0f,1f)else 0f,0f..1f)
                setProgress{value->if(duration>0&&enabled&&value.isFinite()){seek((duration*value.coerceIn(0f,1f)).toLong());true}else false}}
            .focusProperties{canFocus=enabled}.focusable()) {
                drawMediaProgressTrack(if(duration>0)shown.toFloat()/duration else 0f,Color.White.copy(alpha=.24f),FilewayPalette.darkAccent,
                    buffered=if(duration>0)buffered.toFloat()/duration else null,bufferedColor=Color.White.copy(alpha=.55f),
                    cachedRanges=if(duration>0)cachedRanges.map{it.startMs.toFloat()/duration to it.endMs.toFloat()/duration}else emptyList(),downloadedColor=FilewayPalette.darkAccent.copy(alpha=.35f),
                    thickness=if(focused)4.dp else 3.dp,thumbRadius=if(duration<=0)0.dp else if(focused||preview!=null)6.dp else 4.dp)
            }
        Text(if(duration>0)tvTime(duration)else"--:--",style=timeStyle)
    }
}

@Composable internal fun BoxScope.TvPlaybackTitle(title:String,onBack:()->Unit,onInfo:()->Unit,infoFocus:FocusRequester) {
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().heightIn(min=112.dp)
        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.45f),Color.Transparent)))) {
        Row(Modifier.fillMaxWidth().padding(start=32.dp,end=40.dp,top=48.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TvPlaybackAction("返回文件",Art.drawable.fileway_ic_arrow_back,onBack,compact=true)
            Text(title,Modifier.weight(1f),style=tvTextStyle(22,30,androidx.compose.ui.text.font.FontWeight.Medium),color=Color.White,maxLines=2,overflow=TextOverflow.Ellipsis)
            TvPlaybackAction("播放信息",Art.drawable.fileway_ic_info,onInfo,Modifier.focusRequester(infoFocus),compact=true)
        }
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
    val style=tvTextStyle(16,22).copy(fontFeatureSettings="tnum",shadow=Shadow(Color.Black.copy(alpha=.7f),Offset(0f,1f),1f))
    if(showTime)Text(time,Modifier.align(Alignment.TopStart).padding(start=48.dp,top=20.dp),style=style,color=Color.White.copy(alpha=.9f))
    if(showSpeed)Text(tvSpeed(bytesPerSecond),Modifier.align(Alignment.TopEnd).padding(end=48.dp,top=20.dp),style=style,color=Color.White.copy(alpha=.9f))
}
internal fun tvSpeed(bytes:Long):String=if(bytes>=1_048_576)"%.1f MB/s".format(bytes/1_048_576.0)else"${bytes.coerceAtLeast(0)/1024} KB/s"

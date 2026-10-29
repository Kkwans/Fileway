package io.github.kkwans.fileway.tv

import android.view.KeyEvent as AndroidKey
import androidx.media3.common.C
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Scale
import io.github.kkwans.fileway.design.R as Art
import io.github.kkwans.nasfilebrowser.app.MediaKind
import io.github.kkwans.nasfilebrowser.app.mediaKind
import io.github.kkwans.nasfilebrowser.app.mediaKey
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.player.*
import kotlinx.coroutines.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun TvMediaScreen(model: TvModel) {
    val state by model.media.state.collectAsStateWithLifecycle()
    val file = state.file ?: return
    if (file.mediaKind() == MediaKind.IMAGE) { TvImageScreen(model); return }
    val player by model.media.player.state.collectAsStateWithLifecycle()
    val interfaceState by model.state.collectAsStateWithLifecycle()
    val cache by model.media.cache.state.collectAsStateWithLifecycle()
    val textAppearance by model.media.playbackPreferences.textSubtitleAppearance.collectAsStateWithLifecycle()
    val audio = file.mediaKind() == MediaKind.AUDIO
    var controls by remember(file.mediaKey) { mutableStateOf(true) }
    var panel by remember(file.mediaKey) { mutableStateOf<String?>(null) }
    var pulse by remember { mutableIntStateOf(0) }
    var seekTarget by remember(file.mediaKey) { mutableStateOf<Long?>(null) }
    var seekPulse by remember { mutableIntStateOf(0) }
    var resize by remember { mutableIntStateOf(0) }
    val stage = remember { FocusRequester() }; val play = remember { FocusRequester() }; val options = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val actions = remember { mutableMapOf<String, FocusRequester>() }
    var trigger by remember { mutableStateOf<FocusRequester?>(null) }
    val showPanel: (String) -> Unit = { name -> panel = name; trigger = actions[name] }
    val closePanel: () -> Unit = { panel = null }
    val backPanel: () -> Unit = { panel = if(panel in setOf("倍速","HDR")) "设置" else null }
    LaunchedEffect(panel) { if (panel == null && trigger != null) { withFrameNanos {}; trigger?.requestFocus() } }
    BackHandler {
        when { panel != null -> backPanel(); controls && !audio -> controls = false; else -> model.media.close() }
    }
    LaunchedEffect(controls,textAppearance,player.mediaGeneration) {
        model.media.player.textSubtitleAppearance(if(controls)textAppearance.copy(bottomPadding=maxOf(textAppearance.bottomPadding,.36f)) else textAppearance)
    }
    LaunchedEffect(controls, player.playing, panel, pulse) {
        if (controls && player.playing && panel == null && !audio) { delay(4500); controls = false }
    }
    LaunchedEffect(controls,state.busy,state.error,player.error) { withFrameNanos {}; when { state.error!=null||player.error!=null -> retry.requestFocus(); controls -> play.requestFocus(); else -> stage.requestFocus() } }
    LaunchedEffect(seekPulse) { val target = seekTarget; if(target != null) { delay(350); model.media.seek(target); seekTarget = null } }
    val requestSeek: (Long) -> Unit = { delta -> seekTarget = ((seekTarget ?: model.media.sourcePosition()) + delta).coerceIn(0,state.sourceDurationMs.coerceAtLeast(0)); seekPulse++; pulse++ }
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(stage).tvRemoteKeys { event ->
        run {
            pulse++
            when(event.nativeKeyEvent.keyCode) {
                AndroidKey.KEYCODE_MEDIA_PLAY_PAUSE -> { model.media.player.toggle(); true }
                AndroidKey.KEYCODE_MEDIA_PLAY -> { if(!player.playing) model.media.player.toggle(); true }
                AndroidKey.KEYCODE_MEDIA_PAUSE -> { model.media.player.pause(); true }
                AndroidKey.KEYCODE_MEDIA_NEXT -> {model.media.navigateQueue(1);true}
                AndroidKey.KEYCODE_MEDIA_PREVIOUS -> {model.media.navigateQueue(-1);true}
                AndroidKey.KEYCODE_MEDIA_FAST_FORWARD -> {requestSeek(10_000);true}
                AndroidKey.KEYCODE_MEDIA_REWIND -> {requestSeek(-10_000);true}
                AndroidKey.KEYCODE_DPAD_LEFT -> if(!controls && panel==null){requestSeek(-10_000);true}else false
                AndroidKey.KEYCODE_DPAD_RIGHT -> if(!controls && panel==null){requestSeek(10_000);true}else false
                AndroidKey.KEYCODE_DPAD_UP, AndroidKey.KEYCODE_DPAD_DOWN, AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER, AndroidKey.KEYCODE_NUMPAD_ENTER, AndroidKey.KEYCODE_BUTTON_A -> if(!controls && panel==null){controls=true;true}else false
                else -> false
            }
        }
    }.pointerInput(file.mediaKey) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.pressed }) pulse++
            }
        }
    }.focusable()) {
        if(!audio) AndroidView(factory={context->PlayerViewport(context).also(model.media.player::attach)},modifier=Modifier.fillMaxSize(),onRelease={model.media.player.detach(it)})
        else Row(Modifier.fillMaxSize().padding(start=48.dp,end=48.dp,top=80.dp,bottom=190.dp),horizontalArrangement=Arrangement.spacedBy(32.dp),verticalAlignment=Alignment.CenterVertically) {
            TvThumbnail(model,file,Modifier.size(220.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(state.info?.optString("title")?.ifBlank{file.name} ?: file.name,style=MaterialTheme.typography.titleLarge)
                state.info?.optString("artist")?.takeIf{it.isNotBlank()}?.let{Text(it,style=MaterialTheme.typography.titleMedium)}
                state.info?.optString("album")?.takeIf{it.isNotBlank()}?.let{Text(it,style=MaterialTheme.typography.bodyMedium)}

            }
        }
        Box(Modifier.matchParentSize().pointerInput(file.mediaKey) {
            detectTapGestures(onTap = { controls = !controls; pulse++ }, onDoubleTap = { model.media.player.toggle(); pulse++ })
        })
        TvPlaybackTelemetry(interfaceState.clockAlways||controls,!audio&&(interfaceState.speedAlways||controls),cache.bytesPerSecond)
        if(controls && state.error==null && player.error==null) {
            TvPlaybackControls(
                title=if(audio)"" else file.name,
                subtitle=state.notice?:player.operationError,
                playing=player.playing,enabled=!state.busy||player.durationMs>0,
                previous=state.queue?.hasPrevious==true,next=state.queue?.hasNext==true,
                position=seekTarget?:model.media.sourcePosition(),duration=state.sourceDurationMs,
                buffered=player.bufferedPositionMs+state.sourceOffsetMs,cachedRanges=cache.ranges,playFocus=play,settingsFocus=options,
                actions=actions,onToggle={model.media.player.toggle();pulse++},onSeek=requestSeek,
                onSeekTo={target->seekTarget=target.coerceIn(0,state.sourceDurationMs.coerceAtLeast(0));seekPulse++;pulse++},onExit=model.media::close,
                onPrevious={model.media.navigateQueue(-1)},onNext={model.media.navigateQueue(1)},onPanel=showPanel,
                modifier=Modifier.align(Alignment.BottomCenter),
            )
        }
        if(delayedBusy(state.busy||player.waitingForBuffer))Box(Modifier.align(Alignment.Center).background(Color(0xE61B1F27),RoundedCornerShape(12.dp)).padding(18.dp)) {
            Text(if(state.busy)"正在准备播放…" else player.phase,style=MaterialTheme.typography.bodyLarge)
        }
        if((player.error!=null||state.error!=null)&&!state.busy)Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha=.78f),RoundedCornerShape(12.dp)).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            LaunchedEffect(state.error,player.error) { withFrameNanos {}; retry.requestFocus() }
            Text(state.error?:player.error.orEmpty(),color=MaterialTheme.colorScheme.error)
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){TvButton("重试",model.media::retry,Modifier.focusRequester(retry));TvButton("原生播放",{model.media.switchMode(TvHdrMode.NATIVE)});if(state.sdrAvailable)TvButton("SDR 兼容",{model.media.switchMode(TvHdrMode.SDR)});TvButton("返回",model.media::close)}
        }
        seekTarget?.let{target->if(!controls)Text("跳转到 ${tvTime(target)}",Modifier.align(Alignment.BottomCenter).padding(30.dp).background(Color(0xE614181E),RoundedCornerShape(12.dp)).padding(14.dp),style=MaterialTheme.typography.titleMedium)}
    }
    panel?.let { name -> TvPanel(if(name=="队列") "队列 · ${state.queue?.items?.size ?: 0}" else name,closePanel,onBack=backPanel) {
        when(name) {
            "设置" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                item{TvButton("倍速 · ${player.rate}×",{panel="倍速"},Modifier.fillMaxWidth())}
                if(!audio) {
                    item{TvButton("HDR · ${state.mode.title}",{panel="HDR"},Modifier.fillMaxWidth())}
                    item{TvButton("画面 · ${listOf("适合","拉伸","裁切")[resize]}",{resize=(resize+1)%3;model.media.player.resizeMode(listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT,AspectRatioFrameLayout.RESIZE_MODE_FILL,AspectRatioFrameLayout.RESIZE_MODE_ZOOM)[resize])},Modifier.fillMaxWidth())}
                }
            }
            "字幕" -> TvSubtitlePanel(model,player,state)
            "音轨" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(state.compatible)item{TvButton("关闭",{model.media.selectSourceAudio(-1)},Modifier.fillMaxWidth(),selected=state.sourceAudioOff)}
                if(state.compatible)items(state.sourceAudio,key={it.index}){track->TvButton("${track.title} · ${track.codec}",{model.media.selectSourceAudio(track.index)},Modifier.fillMaxWidth(),selected=state.selectedSourceAudio==track.index)}
                else items(player.audio,key={it.id}){track->TvButton(track.title,{model.media.selectAudio(track.id)},Modifier.fillMaxWidth(),selected=player.selectedAudio==track.id,enabled=!state.busy)}
            }
            "倍速" -> TvSpeedPanel(model,player.rate)
            "HDR" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                items(TvHdrMode.entries){mode->TvButton(mode.title,{model.media.switchMode(mode)},Modifier.fillMaxWidth(),selected=state.mode==mode,enabled=!state.busy&&(mode!=TvHdrMode.SDR||state.sdrAvailable))}
                item{Text("当前路径：${if(state.compatible)"SDR 兼容" else "原生"}\n片源：${state.info?.optString("dynamicRange","未知")}\n输出信号：${tvOutputSignal(player)}",style=MaterialTheme.typography.bodySmall)}
            }
            "队列" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) { val queue=state.queue; if(queue!=null)items(queue.items.size){index->TvButton(queue.items[index].name,{model.media.navigateQueue(index-queue.index)},Modifier.fillMaxWidth(),selected=index==queue.index)} }
            "信息" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item{Text(file.name,style=MaterialTheme.typography.titleMedium)}
                item{Text(file.path,style=MaterialTheme.typography.bodyMedium)}
                item{Text("视频缓存 ${tvSize(cache.usedBytes)} / ${tvSize(cache.limitBytes)}${cache.error?.let{failure->"\n$failure"}.orEmpty()}",style=MaterialTheme.typography.bodyMedium)}
                item{Text("片源动态范围：${state.info?.optString("dynamicRange","未知")}\n请求模式：${state.mode.title}\n实际路径：${if(state.compatible)"SDR 兼容" else "原文件"}\n视频输出：${tvOutputSignal(player)}\n解码器：${player.videoDecoder}\n解码类型：${player.videoDecoderKind}\n视频画面：${player.width} × ${player.height}\n音频解码器：${player.audioDecoder}\n音频输出缓冲：${player.audioRenderedBuffers}\n音频跳过缓冲：${player.audioSkippedBuffers}",style=MaterialTheme.typography.bodyMedium)}
            }
        }
    } }
}

@Composable internal fun TvTimeline(position:Long,duration:Long,buffered:Long,onSeekBy:(Long)->Unit,modifier:Modifier=Modifier,cachedRanges:List<TvCachedRange> = emptyList(),onSeekTo:(Long)->Unit={onSeekBy(it-position)}) {
    var focused by remember{mutableStateOf(false)}
    var dragPosition by remember(duration){mutableStateOf<Long?>(null)}
    val seekTo by rememberUpdatedState(onSeekTo)
    val displayPosition=dragPosition?:position
    val accent=MaterialTheme.colorScheme.primary
    Column(modifier,verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Canvas(Modifier.fillMaxWidth().height(40.dp).onFocusChanged{focused=it.isFocused}
            .pointerInput(duration) { if(duration>0)detectTapGestures { point -> seekTo((duration*(point.x/size.width.coerceAtLeast(1)).coerceIn(0f,1f)).toLong()) } }
            .pointerInput(duration) { if(duration>0)detectHorizontalDragGestures(
                onDragStart={point->dragPosition=(duration*(point.x/size.width.coerceAtLeast(1)).coerceIn(0f,1f)).toLong()},
                onHorizontalDrag={change,_->change.consume();dragPosition=(duration*(change.position.x/size.width.coerceAtLeast(1)).coerceIn(0f,1f)).toLong()},
                onDragEnd={dragPosition?.let(seekTo);dragPosition=null},onDragCancel={dragPosition=null}) }
            .onPreviewKeyEvent{
            if(it.type==KeyEventType.KeyDown&&it.nativeKeyEvent.keyCode==AndroidKey.KEYCODE_DPAD_LEFT){onSeekBy(-10_000);true}
            else if(it.type==KeyEventType.KeyDown&&it.nativeKeyEvent.keyCode==AndroidKey.KEYCODE_DPAD_RIGHT){onSeekBy(10_000);true}else false
        }.semantics{contentDescription="播放进度 ${tvTime(displayPosition)}，总时长 ${tvTime(duration)}";progressBarRangeInfo=ProgressBarRangeInfo(if(duration>0)(displayPosition.toFloat()/duration).coerceIn(0f,1f)else 0f,0f..1f);setProgress{value->if(duration>0){seekTo((duration*value.coerceIn(0f,1f)).toLong());true}else false}}.focusable()) {
            val y=size.height/2;val width=if(focused)4.dp.toPx()else 2.dp.toPx()
            drawLine(Color.White.copy(alpha=.2f),Offset(0f,y),Offset(size.width,y),width)
            if(duration>0){
                for(range in cachedRanges)drawLine(Color.White.copy(alpha=.42f),Offset(size.width*(range.startMs.toFloat()/duration).coerceIn(0f,1f),y),Offset(size.width*(range.endMs.toFloat()/duration).coerceIn(0f,1f),y),width)
                drawLine(Color.White.copy(alpha=.35f),Offset(0f,y),Offset(size.width*(buffered.toFloat()/duration).coerceIn(0f,1f),y),width);val x=size.width*(displayPosition.toFloat()/duration).coerceIn(0f,1f);drawLine(accent,Offset(0f,y),Offset(x,y),width);drawCircle(accent,if(focused||dragPosition!=null)6.dp.toPx()else 4.dp.toPx(),Offset(x,y))}
        }
        Row(Modifier.fillMaxWidth()){Text(tvTime(displayPosition),style=MaterialTheme.typography.bodySmall);Spacer(Modifier.weight(1f));Text(tvTime(duration),style=MaterialTheme.typography.bodySmall)}
    }
}

@Composable private fun TvSpeedPanel(model:TvModel,rate:Float) {
    var custom by remember{mutableStateOf(rate.toString())}
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        items(listOf(.5f,.75f,1f,1.25f,1.5f,2f)){value->TvButton("${value}×",{model.media.player.rate(value)},Modifier.fillMaxWidth(),selected=rate==value)}
        item{TvField("自定义速度（0.1–5）",custom,{custom=it})}
        item{TvButton("应用",{parsePlaybackRate(custom)?.let(model.media.player::rate)},Modifier.fillMaxWidth(),enabled=parsePlaybackRate(custom)!=null)}
    }
}

@Composable private fun TvSubtitlePanel(model:TvModel,player:PlayerState,state:TvMediaState) {
    val appearance by model.media.playbackPreferences.textSubtitleAppearance.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var fontsOpen by remember{mutableStateOf(false)}
    var browserOpen by remember{mutableStateOf(false)}
    var delay by remember{mutableStateOf((player.subtitleDelayMs/1000.0).toString())}
    LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item{TvButton("关闭字幕",model.media::subtitlesOff,Modifier.fillMaxWidth(),selected=player.selectedSubtitle==-1&&state.selectedSourceSubtitle==-1)}
        if(state.sourceSubtitles)items(state.sourceText,key={it.index}){track->TvButton("${track.title} · ${track.codec}",{model.media.selectSourceSubtitle(track.index)},Modifier.fillMaxWidth(),selected=state.selectedSourceSubtitle==track.index)}
        else items(player.subtitles.filter{it.id>=0},key={it.id}){track->TvButton(track.title,{model.media.player.subtitle(track.id)},Modifier.fillMaxWidth(),selected=player.selectedSubtitle==track.id)}
        if(state.sidecars.isNotEmpty()) {
            item{TvSectionTitle("同目录外挂字幕")}
            items(state.sidecars,key={it.wirePath}){file->TvButton(file.name,{model.media.selectExternalSubtitle(file)},Modifier.fillMaxWidth())}
        }
        item{TvButton("浏览其他文件夹的字幕",{browserOpen=true},Modifier.fillMaxWidth())}
        item{TvSectionTitle("时间与外观")}
        item{Text("字幕延迟 ${player.subtitleDelayMs} ms",style=MaterialTheme.typography.bodyMedium)}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){TvButton("提前 100ms",{model.media.player.subtitleDelay(player.subtitleDelayMs-100)});TvButton("延后 100ms",{model.media.player.subtitleDelay(player.subtitleDelayMs+100)})}}
        item{TvField("精确延迟（秒，负数提前）",delay,{delay=it})}
        item{TvButton("应用延迟",{parseSubtitleDelayMillis(delay)?.let(model.media.player::subtitleDelay)},enabled=parseSubtitleDelayMillis(delay)!=null)}
        item{Text("字号 ${(appearance.scale*100).toInt()}%",style=MaterialTheme.typography.bodyMedium)}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){TvButton("缩小",{scope.launch{val value=appearance.copy(scale=(appearance.scale-.1f).coerceAtLeast(.5f));model.media.updateSubtitleAppearance(value)}});TvButton("放大",{scope.launch{val value=appearance.copy(scale=(appearance.scale+.1f).coerceAtMost(2f));model.media.updateSubtitleAppearance(value)}})}}
        item{TvButton(if(appearance.area==TextSubtitleArea.PICTURE)"位置：画面内" else "位置：整个画布",{scope.launch{val value=appearance.copy(area=if(appearance.area==TextSubtitleArea.PICTURE)TextSubtitleArea.STAGE else TextSubtitleArea.PICTURE);model.media.updateSubtitleAppearance(value)}},Modifier.fillMaxWidth())}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){TvButton("上移",{scope.launch{val value=appearance.copy(bottomPadding=(appearance.bottomPadding+.02f).coerceAtMost(.4f));model.media.updateSubtitleAppearance(value)}});TvButton("下移",{scope.launch{val value=appearance.copy(bottomPadding=(appearance.bottomPadding-.02f).coerceAtLeast(0f));model.media.updateSubtitleAppearance(value)}})}}
        item{TvButton("字幕字体",{SubtitleFontCatalog.request();fontsOpen=true},Modifier.fillMaxWidth())}
        item{TvButton("文字背景：${(appearance.backgroundOpacity*100).toInt()}%",{scope.launch{val value=appearance.copy(backgroundOpacity=if(appearance.backgroundOpacity>=.75f)0f else appearance.backgroundOpacity+.25f);model.media.updateSubtitleAppearance(value)}},Modifier.fillMaxWidth())}
        item{Text("ASS 保留片源样式；通用字体、字号与位置设置用于普通文字字幕。",style=MaterialTheme.typography.bodySmall)}
    }
    if(browserOpen)TvSubtitleBrowser(model,{browserOpen=false})
    if(fontsOpen)TvPanel("字幕字体",{fontsOpen=false}) {
        val fonts by SubtitleFontCatalog.state.collectAsStateWithLifecycle()
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){items(fonts.options,key={it.key}){font->TvButton(font.label,{scope.launch{val value=appearance.copy(fontKey=font.key);model.media.updateSubtitleAppearance(value)}},Modifier.fillMaxWidth(),selected=appearance.fontKey==font.key)}}
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun tvOutputSignal(player:PlayerState):String {
    val output=player.videoOutput?:return "尚未报告"
    val color=when(output.colorSpace){C.COLOR_SPACE_BT2020->"BT.2020";C.COLOR_SPACE_BT709->"BT.709";else->"色域未知"}
    val transfer=when(output.colorTransfer){C.COLOR_TRANSFER_ST2084->"PQ";C.COLOR_TRANSFER_HLG->"HLG";C.COLOR_TRANSFER_SDR->"SDR";else->"传递函数未知"}
    return "$color / $transfer${if(player.firstFrameRendered)"" else "（等待首帧）"}"
}

@Composable private fun TvImageScreen(model:TvModel) {
    val state by model.media.state.collectAsStateWithLifecycle();val file=state.file?:return
    var imageInfo by remember{mutableStateOf(false)}
    var controls by remember(file.wirePath){mutableStateOf(false)};var zoom by remember(file.wirePath){mutableFloatStateOf(1f)};var x by remember(file.wirePath){mutableFloatStateOf(0f)};var y by remember(file.wirePath){mutableFloatStateOf(0f)}
    val focus=remember{FocusRequester()}
    val imageAction=remember{FocusRequester()}
    BackHandler{when{controls->controls=false;zoom>1f->{zoom=1f;x=0f;y=0f};else->model.media.close()}}
    LaunchedEffect(controls){withFrameNanos{};if(controls)imageAction.requestFocus()else focus.requestFocus()}
    var aspect by remember(file.wirePath){mutableFloatStateOf(16f/9f)}
    val context=LocalContext.current
    val request=remember(state.imageUrl,zoom){state.imageUrl?.let { ImageRequest.Builder(context).data(it).scale(Scale.FIT).size((1920*zoom).toInt().coerceAtMost(3072),(1080*zoom).toInt().coerceAtMost(1728)).build() }}
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val viewportWidth=constraints.maxWidth.toFloat();val viewportHeight=constraints.maxHeight.toFloat()
        val baseWidth=minOf(viewportWidth,viewportHeight*aspect);val baseHeight=minOf(viewportHeight,viewportWidth/aspect)
        val maxPanX=((baseWidth*zoom-viewportWidth)/2).coerceAtLeast(0f);val maxPanY=((baseHeight*zoom-viewportHeight)/2).coerceAtLeast(0f)
        val remoteTransform by model.remoteGateway.imageTransform.collectAsStateWithLifecycle()
        LaunchedEffect(remoteTransform){if(remoteTransform.has("scale")){zoom=remoteTransform.getDouble("scale").toFloat();x=remoteTransform.optDouble("x").toFloat()*((baseWidth*zoom-viewportWidth)/2).coerceAtLeast(0f);y=remoteTransform.optDouble("y").toFloat()*((baseHeight*zoom-viewportHeight)/2).coerceAtLeast(0f)}}
        val stepX=viewportWidth*.05f;val stepY=viewportHeight*.05f
        Box(Modifier.fillMaxSize().focusRequester(focus).tvRemoteKeys{event->
        when(event.nativeKeyEvent.keyCode){
            AndroidKey.KEYCODE_DPAD_LEFT->if(zoom>1f&&!controls){x=(x+stepX).coerceAtMost(maxPanX);true}else if(!controls){model.media.navigateQueue(-1);true}else false
            AndroidKey.KEYCODE_DPAD_RIGHT->if(zoom>1f&&!controls){x=(x-stepX).coerceAtLeast(-maxPanX);true}else if(!controls){model.media.navigateQueue(1);true}else false
            AndroidKey.KEYCODE_DPAD_UP->if(zoom>1f&&!controls){y=(y+stepY).coerceAtMost(maxPanY);true}else if(!controls){controls=true;true}else false
            AndroidKey.KEYCODE_DPAD_DOWN->if(zoom>1f&&!controls){y=(y-stepY).coerceAtLeast(-maxPanY);true}else if(!controls){controls=true;true}else false
            AndroidKey.KEYCODE_DPAD_CENTER,AndroidKey.KEYCODE_ENTER,AndroidKey.KEYCODE_NUMPAD_ENTER,AndroidKey.KEYCODE_BUTTON_A->if(!controls){controls=true;true}else false
            else->false
        }
    } .focusable()) {
        request?.let{imageRequest->AsyncImage(model=imageRequest,imageLoader=model.imageLoader,onSuccess={val image=it.result.image;if(image.height>0)aspect=image.width.toFloat()/image.height},onError={model.media.imageFailed(file.mediaKey)},contentDescription=file.name,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().graphicsLayer{scaleX=zoom;scaleY=zoom;translationX=x.coerceIn(-maxPanX,maxPanX);translationY=y.coerceIn(-maxPanY,maxPanY)})}
        Box(Modifier.matchParentSize()
            .pointerInput(file.mediaKey) {
                detectTapGestures(onTap={controls=!controls},onDoubleTap={
                    if(zoom==1f){model.media.requestOriginalImage();zoom=2f}else{zoom=1f;x=0f;y=0f}
                    controls=false
                })
            }
            .pointerInput(file.mediaKey,viewportWidth,viewportHeight,aspect) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed=false)
                    var pan=Offset.Zero
                    var multiple=false
                    var moved=false
                    do {
                        val event=awaitPointerEvent()
                        multiple=multiple||event.changes.count{it.pressed}>1
                        val delta=event.calculatePan();pan+=delta
                        val scale=event.calculateZoom()
                        if(moved||(multiple&&scale!=1f)||pan.getDistance()>viewConfiguration.touchSlop) {
                            moved=true
                            if(multiple||zoom>1f) {
                                if(zoom==1f&&scale>1f)model.media.requestOriginalImage()
                                zoom=(zoom*scale).coerceIn(1f,3f)
                                val boundX=((baseWidth*zoom-viewportWidth)/2).coerceAtLeast(0f)
                                val boundY=((baseHeight*zoom-viewportHeight)/2).coerceAtLeast(0f)
                                x=(x+delta.x).coerceIn(-boundX,boundX);y=(y+delta.y).coerceIn(-boundY,boundY)
                            }
                            event.changes.forEach{it.consume()}
                        }
                    }while(event.changes.any{it.pressed})
                    if(moved) {
                        controls=false
                        if(!multiple&&zoom==1f&&kotlin.math.abs(pan.x)>maxOf(viewConfiguration.touchSlop*3,viewportWidth*.12f)&&kotlin.math.abs(pan.x)>kotlin.math.abs(pan.y))model.media.navigateQueue(if(pan.x<0)1 else -1)
                    }
                }
            })
        if(delayedBusy(state.busy))Text("正在读取图片…",Modifier.align(Alignment.Center))
        state.error?.let{Text(it,Modifier.align(Alignment.Center),color=MaterialTheme.colorScheme.error)}
        if(controls)Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.65f))))
            .padding(start=48.dp,end=48.dp,top=36.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(file.name,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                TvPlaybackAction("返回文件",Art.drawable.fileway_ic_arrow_back,model.media::close)
                Spacer(Modifier.width(12.dp))
                TvPlaybackAction("上一张",Art.drawable.fileway_ic_skip_previous,{model.media.navigateQueue(-1)},if(state.queue?.hasPrevious==true)Modifier.focusRequester(imageAction)else Modifier,enabled=state.queue?.hasPrevious==true)
                Spacer(Modifier.width(12.dp))
                TvPlaybackAction("下一张",Art.drawable.fileway_ic_skip_next,{model.media.navigateQueue(1)},if(state.queue?.hasPrevious!=true&&state.queue?.hasNext==true)Modifier.focusRequester(imageAction)else Modifier,enabled=state.queue?.hasNext==true)
                Spacer(Modifier.width(24.dp))
                TvPlaybackAction("放大",Art.drawable.fileway_ic_zoom_in,{if(zoom==1f)model.media.requestOriginalImage();zoom=(zoom+.5f).coerceAtMost(3f);controls=false},if(state.queue?.hasPrevious!=true&&state.queue?.hasNext!=true)Modifier.focusRequester(imageAction)else Modifier)
                Spacer(Modifier.width(12.dp))
                TvPlaybackAction("缩小",Art.drawable.fileway_ic_zoom_out,{zoom=(zoom-.5f).coerceAtLeast(1f);if(zoom==1f){x=0f;y=0f}},enabled=zoom>1f)
                Spacer(Modifier.width(12.dp))
                TvPlaybackAction("适合屏幕",Art.drawable.fileway_ic_fit_screen,{zoom=1f;x=0f;y=0f})
                Spacer(Modifier.width(24.dp))
                TvPlaybackAction("图片信息",Art.drawable.fileway_ic_info,{imageInfo=true})
                Spacer(Modifier.width(12.dp))
                TvPlaybackAction("关闭操作栏",Art.drawable.fileway_ic_close,{controls=false})
            }
        }

    }
    }
    if(imageInfo)TvPanel("图片信息",{imageInfo=false}){LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)){item{Text(file.name,style=MaterialTheme.typography.titleMedium)};item{Text(file.path,style=MaterialTheme.typography.bodyMedium)};item{Text(tvSize(file.size),style=MaterialTheme.typography.bodySmall)}}}
}

@Composable private fun TvSubtitleBrowser(model:TvModel,onClose:()->Unit) {
    val owner=model.context
    val playback by model.media.state.collectAsStateWithLifecycle()
    val file=playback.file
    var path by remember{mutableStateOf(file?.path?.substringBeforeLast('/')?.ifBlank{"/"}?:"/")}
    var wire by remember{mutableStateOf(file?.wirePath?.substringBeforeLast('/')?.ifBlank{"/"}?:"/")}
    var files by remember{mutableStateOf<List<io.github.kkwans.nasfilebrowser.app.ResourceRef>>(emptyList())}
    var busy by remember{mutableStateOf(true)};var error by remember{mutableStateOf<String?>(null)}
    var refresh by remember{mutableIntStateOf(0)}
    val first=remember{FocusRequester()}
    LaunchedEffect(owner?.owner,wire,refresh){
        if(owner==null)return@LaunchedEffect
        busy=true;error=null
        try{
            files=TvModel.directoryFiles(owner.api.request("GET","/api/resources$wire")).filter{it.directory||isExternalSubtitle(it.name)}
            busy=false
            if(files.isNotEmpty()){withFrameNanos{};first.requestFocus()}
        }catch(failure:Exception){if(failure is CancellationException)throw failure;busy=false;error=failure.message?:"目录读取失败"}
    }
    val parent:()->Unit={path=path.substringBeforeLast('/').ifBlank{"/"};wire=wire.substringBeforeLast('/').ifBlank{"/"}}
    TvPanel("选择外挂字幕",onClose,onBack={if(wire!="/")parent()else onClose()}){
        Text(path,style=MaterialTheme.typography.bodySmall)
        if(wire!="/")TvButton("上一级",parent,Modifier.fillMaxWidth())
        if(busy)Text("正在读取…",style=MaterialTheme.typography.bodyMedium)
        error?.let{Text(it,color=MaterialTheme.colorScheme.error);TvButton("重试",{refresh++})}
        if(!busy&&error==null&&files.isEmpty())Text("此目录没有字幕或子文件夹",style=MaterialTheme.typography.bodyMedium)
        LazyColumn(modifier=Modifier.focusRequester(first).focusGroup(),verticalArrangement=Arrangement.spacedBy(8.dp)){
            items(files.size){index->val entry=files[index]
                TvButton(entry.name,{if(entry.directory){path=entry.path;wire=entry.wirePath}else{model.media.selectExternalSubtitle(entry);onClose()}},
                    Modifier.fillMaxWidth(),enabled=!busy,
                    icon=if(entry.directory)Art.drawable.fileway_ic_folder else Art.drawable.fileway_ic_subtitles)
            }
        }
    }
}

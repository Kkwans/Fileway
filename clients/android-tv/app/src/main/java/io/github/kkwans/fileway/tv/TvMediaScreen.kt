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
import androidx.compose.ui.layout.onSizeChanged
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
    val fontCatalog by SubtitleFontCatalog.state.collectAsStateWithLifecycle()
    val audio = file.mediaKind() == MediaKind.AUDIO
    val mediaEpoch=model.media.controlEpoch
    var controls by remember(mediaEpoch) { mutableStateOf(audio) }
    var exitPrompt by remember(mediaEpoch) { mutableStateOf(false) }
    val pages=remember(model.sessionEpoch,file.mediaKey){mutableStateListOf<TvPlayerPage>()}
    val panel=pages.lastOrNull()
    val focusMemory=remember(model.sessionEpoch,file.mediaKey){mutableMapOf<TvPlayerPage,String>()}
    var restoring by remember(file.mediaKey){mutableStateOf(false)}
    var browserBack by remember(model.sessionEpoch,file.mediaKey){mutableStateOf<(() -> Unit)?>(null)}
    var stageHeight by remember{mutableFloatStateOf(1f)}
    var controlsHeight by remember{mutableFloatStateOf(0f)}
    var pulse by remember { mutableIntStateOf(0) }
    var seekTarget by remember(mediaEpoch) { mutableStateOf<Long?>(null) }
    var seekPulse by remember { mutableIntStateOf(0) }
    var resize by remember { mutableIntStateOf(0) }
    val stage = remember { FocusRequester() }; val play = remember { FocusRequester() }
    LaunchedEffect(player.mediaGeneration,resize){withFrameNanos{};model.media.player.resizeMode(listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT,AspectRatioFrameLayout.RESIZE_MODE_FILL,AspectRatioFrameLayout.RESIZE_MODE_ZOOM)[resize])}
    val retry = remember { FocusRequester() }
    val actions = remember(file.mediaKey) { mutableMapOf<TvPlayerPage, FocusRequester>() }
    var trigger by remember(file.mediaKey) { mutableStateOf<FocusRequester?>(null) }
    val showPanel: (TvPlayerPage) -> Unit = { page -> pages.clear();pages.add(page);trigger=actions[page];pulse++ }
    val closePanel: () -> Unit = { browserBack=null;pages.clear();controls=true;restoring=true;pulse++ }
    val backPanel: () -> Unit = { if(browserBack!=null)browserBack?.invoke() else if(pages.size>1){pages.removeAt(pages.lastIndex);pulse++}else closePanel() }
    val back: () -> Unit = {
        when { panel!=null->backPanel();exitPrompt->model.media.close();else->{if(!audio)controls=false;exitPrompt=true} }
    }
    BackHandler(onBack=back)
    LaunchedEffect(exitPrompt) { if(exitPrompt) { delay(2000);exitPrompt=false } }
    LaunchedEffect(controls,textAppearance,player.mediaGeneration,controlsHeight,stageHeight) {
        model.media.player.textSubtitleAppearance(if(controls)textAppearance.copy(bottomPadding=maxOf(textAppearance.bottomPadding,((controlsHeight+12f)/stageHeight).coerceIn(0f,.4f))) else textAppearance)
    }
    LaunchedEffect(controls, player.playing, panel, pulse,interfaceState.controlsTimeoutSeconds) {
        if (controls && player.playing && panel == null && !audio) { delay(interfaceState.controlsTimeoutSeconds*1000L); controls = false }
    }
    LaunchedEffect(controls,state.busy,state.error,player.error,panel) {
        if(panel!=null)return@LaunchedEffect
        withFrameNanos{}
        when {
            !state.busy&&(state.error!=null||player.error!=null)->retry.requestFocus()
            controls&&state.error==null&&player.error==null->{(if(restoring)trigger?:play else play).requestFocus();restoring=false}
            else->stage.requestFocus()
        }
    }
    LaunchedEffect(player.seekError) { if(player.seekError!=null)controls=true }
    LaunchedEffect(mediaEpoch,seekPulse) { val target = seekTarget; if(target != null) { delay(350); model.media.seek(target); seekTarget = null } }
    val requestSeek: (Long) -> Unit = { delta -> seekTarget = ((seekTarget ?: model.media.sourcePosition()) + delta).coerceIn(0,state.sourceDurationMs.coerceAtLeast(0)); seekPulse++; pulse++ }
    Box(Modifier.fillMaxSize().onSizeChanged{stageHeight=it.height.toFloat().coerceAtLeast(1f)}.background(Color.Black).focusRequester(stage).tvRemoteKeys { event ->
        run {
            pulse++
            when(event.nativeKeyEvent.keyCode) {
                AndroidKey.KEYCODE_MEDIA_PLAY_PAUSE -> { if(event.nativeKeyEvent.repeatCount==0)model.media.player.toggle(); true }
                AndroidKey.KEYCODE_MEDIA_PLAY -> { if(!player.playing) model.media.player.toggle(); true }
                AndroidKey.KEYCODE_MEDIA_PAUSE -> { model.media.player.pause(); true }
                AndroidKey.KEYCODE_MEDIA_NEXT -> {model.media.navigateQueue(1);true}
                AndroidKey.KEYCODE_MEDIA_PREVIOUS -> {model.media.navigateQueue(-1);true}
                AndroidKey.KEYCODE_MEDIA_FAST_FORWARD -> {requestSeek(tvSeekDelta(event.nativeKeyEvent,interfaceState.seekStepSeconds));true}
                AndroidKey.KEYCODE_MEDIA_REWIND -> {requestSeek(-tvSeekDelta(event.nativeKeyEvent,interfaceState.seekStepSeconds));true}
                AndroidKey.KEYCODE_DPAD_LEFT -> if(!controls && panel==null){requestSeek(-tvSeekDelta(event.nativeKeyEvent,interfaceState.seekStepSeconds));true}else false
                AndroidKey.KEYCODE_DPAD_RIGHT -> if(!controls && panel==null){requestSeek(tvSeekDelta(event.nativeKeyEvent,interfaceState.seekStepSeconds));true}else false
                AndroidKey.KEYCODE_DPAD_DOWN -> if(!controls && panel==null){controls=true;true}else false
                AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER, AndroidKey.KEYCODE_NUMPAD_ENTER, AndroidKey.KEYCODE_BUTTON_A -> if(!controls && panel==null){if(event.nativeKeyEvent.repeatCount==0)model.media.player.toggle();true}else false
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
        if(controls) {
            val infoFocus=remember{FocusRequester()};actions[TvPlayerPage.INFO]=infoFocus
            TvPlaybackTitle(if(audio)"音乐"else file.name,onBack=back,onInfo={showPanel(TvPlayerPage.INFO)},infoFocus=infoFocus)
        }
        TvPlaybackTelemetry(interfaceState.clockAlways||controls,!audio&&(interfaceState.speedAlways||controls),cache.bytesPerSecond)
        if(controls && state.error==null && player.error==null) {
            TvPlaybackControls(
                playing=player.playing,enabled=!state.busy||player.durationMs>0,seekable=model.media.canSeek,audio=audio,rate=player.rate,
                previous=state.queue?.hasPrevious==true,next=state.queue?.hasNext==true,
                position=seekTarget?:model.media.sourcePosition(),duration=state.sourceDurationMs,
                buffered=player.bufferedPositionMs+state.sourceOffsetMs,cachedRanges=cache.ranges,playFocus=play,
                actions=actions,onToggle={model.media.player.toggle();pulse++},onSeek=requestSeek,
                onSeekTo={target->seekTarget=target.coerceIn(0,state.sourceDurationMs.coerceAtLeast(0));seekPulse++;pulse++},
                onPrevious={model.media.navigateQueue(-1)},onNext={model.media.navigateQueue(1)},onPanel=showPanel,
                modifier=Modifier.align(Alignment.BottomCenter).onSizeChanged{controlsHeight=it.height.toFloat()},
                seekStepSeconds=interfaceState.seekStepSeconds,safePercent=interfaceState.safePercent,
            )
        }
        if(delayedBusy(state.subtitleLoading||player.subtitleLoading))Text("正在加载字幕…",Modifier.align(Alignment.CenterEnd).padding(32.dp),style=MaterialTheme.typography.bodySmall,color=Color.White)
        (state.subtitleError?:player.seekError?:state.subtitleNotice?:state.notice?:player.operationError)?.let{notice->
            Text(notice,Modifier.align(Alignment.BottomStart).padding(start=48.dp,end=48.dp,bottom=if(controls)156.dp else 24.dp),style=MaterialTheme.typography.bodySmall,color=Color.White,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        if(delayedBusy(state.busy||((player.waitingForBuffer||player.seeking)&&player.seekError==null)))Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha=.48f),RoundedCornerShape(12.dp)).padding(horizontal=20.dp,vertical=14.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(if(state.busy)"正在准备播放…" else if(player.seeking)"正在跳转…" else "正在缓冲…",style=MaterialTheme.typography.bodyLarge)
            if(!state.busy&&player.waitingForBuffer)Text("缓冲 ${player.buffering.toInt()}%",style=MaterialTheme.typography.bodySmall,color=Color.White.copy(alpha=.8f))
        }
        if((player.error!=null||state.error!=null)&&!state.busy)Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha=.78f),RoundedCornerShape(12.dp)).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            LaunchedEffect(state.error,player.error) { withFrameNanos {}; retry.requestFocus() }
            Text(state.error?:player.error.orEmpty(),color=MaterialTheme.colorScheme.error)
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){TvButton("重试",model.media::retry,Modifier.focusRequester(retry));TvButton("原生播放",{model.media.switchMode(TvHdrMode.NATIVE)});if(state.sdrAvailable)TvButton("SDR 兼容",{model.media.switchMode(TvHdrMode.SDR)});TvButton("返回",model.media::close)}
        }
        seekTarget?.let{target->if(!controls)Text("跳转到 ${tvTime(target)}",Modifier.align(Alignment.BottomCenter).padding(30.dp).background(Color(0xE614181E),RoundedCornerShape(12.dp)).padding(14.dp),style=MaterialTheme.typography.titleMedium)}
        if(exitPrompt)Text("再按一次返回退出播放",Modifier.align(Alignment.BottomCenter).padding(bottom=28.dp).background(Color.Black.copy(alpha=.6f),TvShape).padding(horizontal=20.dp,vertical=12.dp),style=MaterialTheme.typography.bodyLarge,color=Color.White)
    }
    panel?.let { page ->
        val labels=when(page) {
            TvPlayerPage.AUDIO->if(state.compatible)state.sourceAudio.map{it.title}else player.audio.map{it.title}
            TvPlayerPage.SUBTITLE->state.sourceText.map{it.title}+player.subtitles.map{it.title}+state.sidecars.map{it.name}
            TvPlayerPage.QUEUE->state.queue?.items?.map{it.name}.orEmpty()
            TvPlayerPage.FONT->fontCatalog.options.map{it.label}
            TvPlayerPage.APPEARANCE->listOf("字号","垂直位置","文字背景","位置参考",fontCatalog.resolve(textAppearance.fontKey).label,"恢复默认外观")
            TvPlayerPage.SETTINGS->listOf("播放速度","画面比例","动态范围","应用音量","播放信息")
            TvPlayerPage.SPEED->listOf("自定义速度（0.1–5）","1.25×")
            else->listOf(page.title)
        }
        TvPlayerPanel(if(page==TvPlayerPage.QUEUE)"播放列表 · ${state.queue?.items?.size?:0}"else page.title,labels,pages.size>1,backPanel,closePanel) {
            TvPlayerMenuContent(model,page,state,player,cache,resize,onResize={index->resize=index;model.media.player.resizeMode(listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT,AspectRatioFrameLayout.RESIZE_MODE_FILL,AspectRatioFrameLayout.RESIZE_MODE_ZOOM)[index])},
                onNavigate={next->browserBack=null;pages.add(next);pulse++},onBack=backPanel,onClose=closePanel,onBrowserBack={browserBack=it},initialKey=focusMemory[page],onFocused={focusMemory[page]=it})
        }
    }
}

internal fun tvSeekDelta(event:AndroidKey,stepSeconds:Int):Long {
    val held=(event.eventTime-event.downTime).coerceAtLeast(0)
    val multiplier=when { held>=6000->16;held>=3500->8;held>=2000->4;held>=1000->2;else->1 }
    return stepSeconds.coerceIn(3,30)*1000L*multiplier
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
    val mediaEpoch=model.media.controlEpoch
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
        LaunchedEffect(mediaEpoch,zoom,x,y,maxPanX,maxPanY) {
            model.remoteGateway.reportImageView(mediaEpoch,zoom,if(maxPanX>0)(x/maxPanX).coerceIn(-1f,1f)else 0f,if(maxPanY>0)(y/maxPanY).coerceIn(-1f,1f)else 0f)
        }
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
        request?.let{imageRequest->AsyncImage(model=imageRequest,imageLoader=model.imageLoader,onSuccess={val image=it.result.image;if(image.height>0)aspect=image.width.toFloat()/image.height;model.media.imageReady(file.mediaKey,mediaEpoch)},onError={model.media.imageFailed(file.mediaKey,mediaEpoch)},contentDescription=file.name,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().graphicsLayer{scaleX=zoom;scaleY=zoom;translationX=x.coerceIn(-maxPanX,maxPanX);translationY=y.coerceIn(-maxPanY,maxPanY)})}
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

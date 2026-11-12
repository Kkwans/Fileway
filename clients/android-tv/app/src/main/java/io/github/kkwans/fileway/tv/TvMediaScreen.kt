package io.github.kkwans.fileway.tv

import android.view.KeyEvent as AndroidKey
import androidx.media3.common.C
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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

private val PlayerFeedbackSurface = Color(0xFF262626)

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
    val mediaEpoch=model.media.controlEpoch
    var fontNotice by remember(mediaEpoch) { mutableStateOf<String?>(null) }
    LaunchedEffect(mediaEpoch, player.subtitleFontNotice) {
        fontNotice = player.subtitleFontNotice
        if (fontNotice != null) { delay(5_000); fontNotice = null }
    }
    var controls by remember(mediaEpoch) { mutableStateOf(audio) }
    var exitPrompt by remember(mediaEpoch) { mutableStateOf(false) }
    val pages = remember(model.sessionEpoch, file.mediaKey) { mutableStateListOf<TvPlayerPage>() }
    val panel = pages.lastOrNull()
    var quickPanel by remember(mediaEpoch) { mutableStateOf(false) }
    val quickAnchors = remember(mediaEpoch) { mutableStateMapOf<TvPlayerPage, Rect>() }
    var returnToHud by remember(mediaEpoch) { mutableStateOf(false) }
    var lastMenu by remember(mediaEpoch) { mutableStateOf(TvPlayerPage.SETTINGS) }
    val focusMemory = remember(model.sessionEpoch, file.mediaKey) { mutableMapOf<TvPlayerPage, String>() }
    var restoring by remember(file.mediaKey) { mutableStateOf(false) }
    var browserBack by remember(model.sessionEpoch, file.mediaKey) { mutableStateOf<(() -> Unit)?>(null) }
    var capturedBack by remember(file.mediaKey) { mutableStateOf(false) }
    var stageHeight by remember{mutableFloatStateOf(1f)}
    var controlsHeight by remember{mutableFloatStateOf(0f)}
    var pulse by remember { mutableIntStateOf(0) }
    var seekTarget by remember(mediaEpoch) { mutableStateOf<Long?>(null) }
    var seekPulse by remember { mutableIntStateOf(0) }
    val resize by model.media.ratioIndex.collectAsStateWithLifecycle()
    val stage = remember { FocusRequester() }
    val play = remember { FocusRequester() }
    LaunchedEffect(player.mediaGeneration, resize) {
        withFrameNanos {}
        model.media.player.resizeMode(listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM)[resize])
    }
    val retry = remember { FocusRequester() }
    val actions = remember(file.mediaKey) { TvPlayerPage.entries.associateWith { FocusRequester() } }
    var trigger by remember(file.mediaKey) { mutableStateOf<FocusRequester?>(null) }
    fun showPanel(page: TvPlayerPage, quick: Boolean = false, origin: FocusRequester? = null) {
        browserBack = null
        focusMemory.clear()
        pages.clear()
        pages.add(page)
        quickPanel = quick
        returnToHud = controls
        if (!quick && page in listOf(TvPlayerPage.QUEUE, TvPlayerPage.SETTINGS, TvPlayerPage.SPEED, TvPlayerPage.SUBTITLE, TvPlayerPage.AUDIO, TvPlayerPage.INFO)) lastMenu = page
        trigger = if (returnToHud) origin ?: actions.getValue(if (quick) page else TvPlayerPage.SETTINGS) else null
        exitPrompt = false
        pulse++
    }
    fun navigatePanel(page: TvPlayerPage) {
        browserBack = null
        if (page == TvPlayerPage.SPEED) {
            pages.clear()
            lastMenu = page
        }
        pages.add(page)
        pulse++
    }
    fun switchPanelTab(page: TvPlayerPage) {
        browserBack = null
        pages.clear()
        pages.add(page)
        lastMenu = page
        pulse++
    }
    fun selectSubtitleSection(page: TvPlayerPage) {
        browserBack = null
        while (pages.size > 1) pages.removeAt(pages.lastIndex)
        if (page == TvPlayerPage.SUBTITLE) {
            // An explicit group activation enters the choices; Back restores the group itself.
            if (focusMemory[page]?.startsWith("group/") == true) focusMemory.remove(page)
        } else {
            focusMemory[TvPlayerPage.SUBTITLE] = tvSubtitleGroupKey(page)
            if (focusMemory[page]?.startsWith("group/") == true) focusMemory.remove(page)
            pages.add(page)
        }
        pulse++
    }
    fun closePanel() {
        browserBack = null
        pages.clear()
        controls = returnToHud
        restoring = returnToHud
        quickPanel = false
        pulse++
    }
    fun traceBack(source: String, action: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("FilewayTvBack",
            "source=$source action=$action panel=${pages.lastOrNull()?.name ?: "none"} depth=${pages.size} hud=$controls exitPrompt=$exitPrompt")
    }
    fun backPanel(source: String) {
        val browser = browserBack
        when {
            pages.isEmpty() -> traceBack(source, "dialog-already-closed")
            browser != null -> { traceBack(source, "parent-directory"); browser() }
            pages.size > 1 -> { traceBack(source, "parent-page"); pages.removeAt(pages.lastIndex); pulse++ }
            else -> { traceBack(source, if (returnToHud) "restore-hud" else "return-to-video"); closePanel() }
        }
    }
    fun back(source: String) {
        when {
            pages.isNotEmpty() -> backPanel(source)
            controls -> { traceBack(source, "hide-hud"); controls = false; exitPrompt = false }
            exitPrompt -> { traceBack(source, "exit-player"); model.media.close() }
            else -> { traceBack(source, "arm-exit"); exitPrompt = true }
        }
    }
    BackHandler { back("activity-dispatcher") }
    LaunchedEffect(exitPrompt) { if(exitPrompt) { delay(2000);exitPrompt=false } }
    LaunchedEffect(controls,panel,textAppearance,player.mediaGeneration,controlsHeight,stageHeight) {
        model.media.player.textSubtitleAppearance(if(controls&&(panel==null||quickPanel))textAppearance.copy(bottomPadding=maxOf(textAppearance.bottomPadding,((controlsHeight+12f)/stageHeight).coerceIn(0f,.4f))) else textAppearance)
    }
    LaunchedEffect(controls, player.playing, panel, pulse,interfaceState.controlsTimeoutSeconds) {
        if (controls && player.playing && panel == null && !audio) { delay(interfaceState.controlsTimeoutSeconds*1000L); controls = false }
    }
    val playEnabled = !state.busy || player.durationMs > 0
    val hudVisible = controls && (panel == null || quickPanel) && state.error == null && player.error == null
    LaunchedEffect(hudVisible, panel, state.busy, state.error, player.error) {
        if (panel != null) return@LaunchedEffect
        withFrameNanos {}
        when {
            !state.busy && (state.error != null || player.error != null) -> retry.requestFocus()
            hudVisible -> {
                val fallback = if (playEnabled) play else actions.getValue(TvPlayerPage.SETTINGS)
                (if (restoring) trigger ?: fallback else fallback).requestFocus()
                restoring = false
            }
            else -> stage.requestFocus()
        }
    }
    LaunchedEffect(player.seekError) { if(player.seekError!=null)controls=true }
    LaunchedEffect(mediaEpoch,seekPulse) { val target = seekTarget; if(target != null) { delay(350); model.media.seek(target); seekTarget = null } }
    val requestSeek: (Long) -> Unit = { delta -> seekTarget = ((seekTarget ?: model.media.sourcePosition()) + delta).coerceIn(0,state.sourceDurationMs.coerceAtLeast(0)); seekPulse++; pulse++ }
    Box(Modifier.fillMaxSize().onSizeChanged{stageHeight=it.height.toFloat().coerceAtLeast(1f)}.background(Color.Black).focusRequester(stage)
        .focusProperties { canFocus = panel == null && !hudVisible }
        .onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            if (BuildConfig.DEBUG && key.keyCode in listOf(AndroidKey.KEYCODE_DPAD_UP, AndroidKey.KEYCODE_DPAD_DOWN)) {
                android.util.Log.d("FilewayTvInput", "dpad key=${key.keyCode} action=${key.action} hud=$controls panel=${pages.isNotEmpty()}")
            }
            if (BuildConfig.DEBUG && key.keyCode == AndroidKey.KEYCODE_DPAD_CENTER && key.repeatCount == 0) {
                android.util.Log.d("FilewayTvInput", "center action=${key.action} hud=$controls panel=${pages.isNotEmpty()}")
            }
            if (key.keyCode != AndroidKey.KEYCODE_BACK) return@onPreviewKeyEvent false
            when (event.type) {
                KeyEventType.KeyDown -> (pages.isEmpty() || capturedBack).also { capturedBack = it }
                KeyEventType.KeyUp -> {
                    val handled = capturedBack
                    capturedBack = false
                    if (handled && !event.nativeKeyEvent.isCanceled) back("activity-key")
                    handled
                }
                else -> false
            }
        }.tvRemoteKeys { event ->
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
                AndroidKey.KEYCODE_DPAD_UP -> if(!controls && panel==null){controls=true;true}else false
                AndroidKey.KEYCODE_DPAD_DOWN -> if(!controls && panel==null){showPanel(lastMenu);true}else false
                AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER, AndroidKey.KEYCODE_NUMPAD_ENTER, AndroidKey.KEYCODE_BUTTON_A -> {
                    if (controls || panel != null) false else {
                        if (event.nativeKeyEvent.repeatCount == 0) {
                            val nativePlayer = model.media.player
                            val before = if (BuildConfig.DEBUG) "${nativePlayer.wantsPlay}/${nativePlayer.state.value.playing}" else null
                            nativePlayer.toggle()
                            if (before != null) android.util.Log.d("FilewayTvInput",
                                "hidden toggle wantsPlay/playing $before -> ${nativePlayer.wantsPlay}/${nativePlayer.state.value.playing}")
                        }
                        true
                    }
                }
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
        if (hudVisible) {
            TvPlaybackControls(
                playing = player.playing, enabled = playEnabled, seekable = model.media.canSeek, audio = audio, rate = player.rate,
                previous = state.queue?.hasPrevious == true, next = state.queue?.hasNext == true,
                position = seekTarget ?: model.media.sourcePosition(), duration = state.sourceDurationMs,
                buffered = player.bufferedPositionMs + state.sourceOffsetMs, cachedRanges = cache.ranges, playFocus = play,
                actions = actions, onToggle = { model.media.player.toggle(); pulse++ }, onSeek = requestSeek,
                onSeekTo = { target -> seekTarget = target.coerceIn(0, state.sourceDurationMs.coerceAtLeast(0)); seekPulse++; pulse++ },
                onPrevious = { model.media.navigateQueue(-1) }, onNext = { model.media.navigateQueue(1) },
                onQuick = { showPanel(it, quick = true) },
                onQuickAnchor = { page, bounds -> if (quickAnchors[page] != bounds) quickAnchors[page] = bounds },
                onMenu = { showPanel(lastMenu, origin = it) },
                onSettings = { showPanel(TvPlayerPage.SETTINGS) }, activeQuick = panel.takeIf { quickPanel },
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { controlsHeight = it.height.toFloat() },
                seekStepSeconds = interfaceState.seekStepSeconds, safePercent = interfaceState.safePercent,
            )
        }
        TvPlaybackHeader(
            title = file.name.takeIf { (hudVisible || panel != null) },
            showTime = interfaceState.clockAlways || controls || panel != null,
            showSpeed = !audio && (interfaceState.speedAlways || controls || panel != null),
            bytesPerSecond = cache.bytesPerSecond, safePercent = interfaceState.safePercent,
        )
        if (panel == null && delayedBusy(state.subtitleLoading || player.subtitleLoading)) {
            Text(state.subtitleLoadDetail ?: "正在加载字幕…",
                Modifier.align(Alignment.CenterEnd).padding(32.dp)
                    .background(PlayerFeedbackSurface.copy(alpha = .94f), TvShape).padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = Color.White)
        }
        (state.subtitleError?:player.seekError?:state.subtitleNotice?:fontNotice?:state.notice?:player.operationError)?.let{notice->
            val inSubtitlePanel=panel==TvPlayerPage.SUBTITLE||panel==TvPlayerPage.EXTERNAL||panel==TvPlayerPage.LOCAL_SUBTITLE
            val shownInSubtitlePanel=notice==state.subtitleError||notice==state.subtitleNotice||notice==player.subtitleFontNotice||
                (notice==player.operationError&&notice.contains("字幕"))
            if(!inSubtitlePanel||!shownInSubtitlePanel) {
                val noticeBottom = if (hudVisible) with(LocalDensity.current) { controlsHeight.toDp() } else 24.dp
                val placement=if(panel==null)Modifier.align(Alignment.BottomStart).padding(start=48.dp,end=48.dp,bottom=noticeBottom)
                    else Modifier.align(Alignment.TopStart).padding(start=48.dp,end=48.dp,top=96.dp)
                Text(notice,placement.background(PlayerFeedbackSurface.copy(alpha=.94f),TvShape).padding(horizontal=12.dp,vertical=8.dp),
                    style=MaterialTheme.typography.bodySmall,color=Color.White,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
        }
        if(delayedBusy(state.busy||((player.waitingForBuffer||player.seeking)&&player.seekError==null)))Column(Modifier.align(Alignment.Center).background(PlayerFeedbackSurface.copy(alpha=.86f),TvShape).padding(horizontal=20.dp,vertical=14.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(if(state.busy)"正在准备播放…" else if(player.seeking)"正在跳转…" else "正在缓冲…",style=MaterialTheme.typography.bodyLarge)
            if(!state.busy&&player.waitingForBuffer)Text("缓冲 ${player.buffering.toInt()}%",style=MaterialTheme.typography.bodySmall,color=Color.White.copy(alpha=.8f))
        }
        if((player.error!=null||state.error!=null)&&!state.busy)Column(Modifier.align(Alignment.Center).background(PlayerFeedbackSurface.copy(alpha=.97f),TvShape).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            LaunchedEffect(state.error,player.error) { withFrameNanos {}; retry.requestFocus() }
            Text(state.error?:player.error.orEmpty(),color=MaterialTheme.colorScheme.error)
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){TvButton("重试",model.media::retry,Modifier.focusRequester(retry));TvButton("原生播放",{model.media.switchMode(TvHdrMode.NATIVE)});if(state.sdrAvailable)TvButton("SDR 兼容",{model.media.switchMode(TvHdrMode.SDR)});TvButton("返回",model.media::close)}
        }
        seekTarget?.let{target->if(!controls)Text("跳转到 ${tvTime(target)}",Modifier.align(Alignment.BottomCenter).padding(30.dp).background(PlayerFeedbackSurface.copy(alpha=.94f),TvShape).padding(14.dp),style=MaterialTheme.typography.titleMedium)}
        if(exitPrompt)Text("再按一次返回退出播放",Modifier.align(Alignment.BottomCenter).padding(bottom=28.dp).background(PlayerFeedbackSurface.copy(alpha=.94f),TvShape).padding(horizontal=20.dp,vertical=12.dp),style=MaterialTheme.typography.bodyLarge,color=Color.White)
    }
    panel?.let { page ->
        val quickLabels = remember(page, state.queue, state.sourceAudio, state.sourceText, state.sidecars,
            player.audio, player.subtitles, state.compatible) {
            when (page) {
                TvPlayerPage.QUEUE -> state.queue?.items.orEmpty().map { it.name }
                TvPlayerPage.SPEED -> listOf("0.5×", "0.75×", "1.0×", "1.25×", "1.5×", "2.0×")
                TvPlayerPage.AUDIO -> if (state.compatible) state.sourceAudio.flatMap { listOf(it.title, "${it.language} · ${it.codec}") }
                    else player.audio.flatMap { listOf(it.title, "${it.language} · ${it.codec}") }
                TvPlayerPage.SUBTITLE -> listOf("关闭字幕") + state.sourceText.map { it.title } +
                    player.subtitles.map { it.title } + state.sidecars.map { it.name }
                else -> emptyList()
            }
        }
        TvPlayerPanel(
            page = page, safePercent = interfaceState.safePercent,
            child = pages.size > 1, audio = audio,
            quick = quickPanel, hudHeight = with(LocalDensity.current) { controlsHeight.toDp() },
            quickAnchor = quickAnchors[page], quickLabels = quickLabels,
            parentPage = pages.firstOrNull() ?: page,
            initialKey = focusMemory[page], onFocused = { key ->
                val tab = if (page in setOf(TvPlayerPage.SUBTITLE, TvPlayerPage.SUBTITLE_SOURCE, TvPlayerPage.SUBTITLE_ADJUST))
                    TvPlayerPage.SUBTITLE else page
                if (!key.startsWith("header/tab/") || key == "header/tab/${tab.name}") focusMemory[page] = key
            },
            onTab = ::switchPanelTab, onSubtitleSection = ::selectSubtitleSection,
            onBack = ::backPanel, onClose = ::closePanel,
        ) { focus ->
            TvPlayerMenuContent(
                model, page, state, player, cache, resize, quickPanel, focus,
                onResize = model.media::setRatio,
                onNavigate = ::navigatePanel, onBack = { backPanel("dialog-choice") }, onClose = ::closePanel,
                onExit = { traceBack("settings-exit", "exit-player"); model.media.close() },
                onBrowserBack = { browserBack = it },
            )
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
                TvImageAction("返回文件",Art.drawable.fileway_ic_arrow_back,model.media::close)
                Spacer(Modifier.width(12.dp))
                TvImageAction("上一张",Art.drawable.fileway_ic_skip_previous,{model.media.navigateQueue(-1)},if(state.queue?.hasPrevious==true)Modifier.focusRequester(imageAction)else Modifier,enabled=state.queue?.hasPrevious==true)
                Spacer(Modifier.width(12.dp))
                TvImageAction("下一张",Art.drawable.fileway_ic_skip_next,{model.media.navigateQueue(1)},if(state.queue?.hasPrevious!=true&&state.queue?.hasNext==true)Modifier.focusRequester(imageAction)else Modifier,enabled=state.queue?.hasNext==true)
                Spacer(Modifier.width(24.dp))
                TvImageAction("放大",Art.drawable.fileway_ic_zoom_in,{if(zoom==1f)model.media.requestOriginalImage();zoom=(zoom+.5f).coerceAtMost(3f);controls=false},if(state.queue?.hasPrevious!=true&&state.queue?.hasNext!=true)Modifier.focusRequester(imageAction)else Modifier)
                Spacer(Modifier.width(12.dp))
                TvImageAction("缩小",Art.drawable.fileway_ic_zoom_out,{zoom=(zoom-.5f).coerceAtLeast(1f);if(zoom==1f){x=0f;y=0f}},enabled=zoom>1f)
                Spacer(Modifier.width(12.dp))
                TvImageAction("适合屏幕",Art.drawable.fileway_ic_fit_screen,{zoom=1f;x=0f;y=0f})
                Spacer(Modifier.width(24.dp))
                TvImageAction("图片信息",Art.drawable.fileway_ic_info,{imageInfo=true})
                Spacer(Modifier.width(12.dp))
                TvImageAction("关闭操作栏",Art.drawable.fileway_ic_close,{controls=false})
            }
        }

    }
    }
    if(imageInfo)TvPanel("图片信息",{imageInfo=false}){LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)){item{Text(file.name,style=MaterialTheme.typography.titleMedium)};item{Text(file.path,style=MaterialTheme.typography.bodyMedium)};item{Text(tvSize(file.size),style=MaterialTheme.typography.bodySmall)}}}
}

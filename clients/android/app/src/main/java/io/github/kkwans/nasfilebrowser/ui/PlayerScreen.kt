package io.github.kkwans.nasfilebrowser.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Display
import android.view.accessibility.AccessibilityManager
import android.os.SystemClock
import android.media.AudioManager
import android.provider.Settings
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import io.github.kkwans.nasfilebrowser.data.parsePlaybackRate
import io.github.kkwans.nasfilebrowser.data.TextSubtitleAppearance
import io.github.kkwans.nasfilebrowser.data.VideoDecodePolicy
import io.github.kkwans.nasfilebrowser.data.isExternalSubtitle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.roundToInt
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.mediaKey
import io.github.kkwans.nasfilebrowser.player.SeekGestureAccumulator
import io.github.kkwans.nasfilebrowser.player.SeekDragSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import io.github.kkwans.nasfilebrowser.player.PlayerViewport
import io.github.kkwans.nasfilebrowser.download.DownloadIndex
import io.github.kkwans.nasfilebrowser.download.DownloadRuntime
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme

private enum class PlayerSheet { AUDIO, SUBTITLE, SUBTITLE_ADJUST, SUBTITLE_FONT, SPEED, SPEED_EDIT, VOLUME, BRIGHTNESS, SOURCE, EXTERNAL, QUEUE, MORE }
private enum class PlayerDisplayMode { AUTOMATIC, PORTRAIT_FULLSCREEN, LANDSCAPE_FULLSCREEN }
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun PlayerScreen(model: ClientModel, file: ResourceRef) {
    val state by model.player.state.collectAsStateWithLifecycle()
    val client by model.state.collectAsStateWithLifecycle()
    val downloads by model.downloads.state.collectAsStateWithLifecycle()
    val queue = client.mediaQueue
    val liveState by rememberUpdatedState(state)
    val holdRate by model.playbackPreferences.holdRate.collectAsStateWithLifecycle()
    val savedSubtitleAppearance by model.playbackPreferences.textSubtitleAppearance.collectAsStateWithLifecycle()
    var subtitlePreview by remember(savedSubtitleAppearance) { mutableStateOf(savedSubtitleAppearance) }
    LaunchedEffect(subtitlePreview) { model.player.textSubtitleAppearance(subtitlePreview) }
    val liveHoldRate by rememberUpdatedState(holdRate)
    var holding by remember(file) { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()
    var customRate by remember { mutableStateOf("") }
    var editingHoldRate by remember { mutableStateOf(false) }
    var savingHoldRate by remember { mutableStateOf(false) }
    var speedEditRevision by remember { mutableIntStateOf(0) }
    var speedError by remember { mutableStateOf<String?>(null) }
    var changingDecoder by remember { mutableStateOf(false) }
    var subtitleOffset by remember(file) { mutableStateOf("") }
    var subtitleOffsetError by remember(file) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val download = downloads.items.firstOrNull { it.id == file.downloadId }
    val playbackSourceLabel = when {
        file.downloadId.isEmpty() -> client.serverLabel
        download == null -> "本机下载 · 下载状态待确认"
        download.complete -> "本机下载 · 离线读取"
        else -> "本机下载 · 未下载片段按需读取服务器"
    }
    val downloadIndex by produceState<DownloadIndex.Info?>(null, download?.id, download?.complete, client.busy) {
        val item = download
        value = if (item == null || item.complete) null else withContext(Dispatchers.IO) {
            runCatching { DownloadIndex.get(context).info(item) }.getOrNull()
        }
    }
    val downloadedBytes = download?.let { item ->
        (if (item.complete) item.expectedSize else maxOf(item.downloaded, DownloadRuntime.get(context).prefix(item))).coerceIn(0, item.expectedSize.coerceAtLeast(0))
    } ?: 0L
    val downloadedUntil = download?.let { item ->
        if (item.complete && state.durationMs > 0) state.durationMs
        else downloadIndex?.takeIf { it.points.isNotEmpty() }?.availableMs(downloadedBytes)
    }
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val maximumVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    var mediaVolume by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var gestureForeground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val gestureEdge = with(LocalDensity.current) { 24.dp.toPx() }
    var draggingVertical by remember(file) { mutableStateOf(false) }
    var dragLeft by remember(file) { mutableStateOf(false) }
    var dragStart by remember(file) { mutableFloatStateOf(0f) }
    val landscapeOrientation = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var displayMode by rememberSaveable { mutableStateOf(PlayerDisplayMode.AUTOMATIC) }
    val fullscreen = landscapeOrientation || displayMode != PlayerDisplayMode.AUTOMATIC
    val view = LocalView.current
    val accessibility = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    var exploration by remember { mutableStateOf(accessibility.isTouchExplorationEnabled) }
    var visible by remember(file) { mutableStateOf(true) }
    var touchLocked by remember(file) { mutableStateOf(false) }
    var playerPosition by remember { mutableStateOf(Offset.Zero) }
    var touchLockPosition by remember { mutableStateOf(Offset.Zero) }
    val gestureSeek = remember(file) { SeekGestureAccumulator() }
    var gestureMessage by remember(file) { mutableStateOf<String?>(null) }
    var interaction by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var expandedTitle by remember(file) { mutableStateOf(false) }
    var seek by remember(file) { mutableStateOf<Float?>(null) }
    var pictureSeek by remember(file, state.mediaGeneration) { mutableStateOf<Long?>(null) }
    var dragSession by remember(file, state.mediaGeneration) { mutableStateOf<SeekDragSession?>(null) }
    val seekPreview = rememberSeekPreview(model, file, state.mediaGeneration, seek != null || pictureSeek != null)
    val showControls = visible && pictureSeek == null && !touchLocked
    var showRequest by remember(file) { mutableStateOf(false) }
    val feedback = remember { SnackbarHostState() }
    var documentGeneration by rememberSaveable { mutableStateOf<Long?>(null) }
    var readingDocument by remember(file) { mutableStateOf(false) }
    val subtitleDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val requestedGeneration = documentGeneration
        documentGeneration = null
        if (uri != null && requestedGeneration == model.player.state.value.mediaGeneration) {
            uiScope.launch {
                readingDocument = true
                try {
                    // Only the URI selected by the user is read. No storage-wide
                    // permission or persisted URI grant is needed for this session.
                    val name = withContext(Dispatchers.IO) {
                        require(uri.scheme == "content")
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                        } ?: error("Missing subtitle filename")
                    }
                    if (requestedGeneration != model.player.state.value.mediaGeneration) return@launch
                    if (!isExternalSubtitle(name)) {
                        feedback.showSnackbar("请选择 SRT、VTT、ASS、SSA、TTML/DFXP、SAMI/SMI 或 SUP 字幕")
                    } else if (!model.player.addSubtitle(uri.toString(), name)) {
                        feedback.showSnackbar("视频仍在加载，请稍后重试添加字幕")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (requestedGeneration == model.player.state.value.mediaGeneration) feedback.showSnackbar("无法读取所选字幕，请重新选择文件")
                } finally { readingDocument = false }
            }
        }
    }
    LaunchedEffect(state.operationError) {
        state.operationError?.let { message -> feedback.showSnackbar(message); model.player.clearOperationError(message) }
    }
    LaunchedEffect(client.busy, file) {
        showRequest = false
        if (client.busy) { delay(200); showRequest = true }
    }
    LaunchedEffect(client.progressStatus) {
        if (client.progressStatus == "续播保存失败，请重试" &&
            feedback.showSnackbar("续播未能保存", actionLabel = "重试", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
            model.retryProgress()
        }
    }
    val blocked = state.error != null || client.error != null || client.busy || !state.playing || state.phase != "正在播放"
    val liveBlocked by rememberUpdatedState(blocked)
    fun touch() { visible = true; interaction++ }
    fun lockTouch() { touchLocked = true; sheet = null; gestureSeek.reset() }
    fun unlockTouch() { touchLocked = false; touch() }
    fun changeDisplay(mode: PlayerDisplayMode) {
        touch()
        sheet = null
        displayMode = mode
        context.activity()?.requestedOrientation = if (mode == PlayerDisplayMode.LANDSCAPE_FULLSCREEN)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }
    BackHandler(fullscreen && !touchLocked) { changeDisplay(PlayerDisplayMode.AUTOMATIC) }
    BackHandler(touchLocked) { unlockTouch() }
    LaunchedEffect(exploration) { if (exploration) touchLocked = false }
    LaunchedEffect(gestureMessage, interaction) { if (gestureMessage != null) { delay(1_000); gestureMessage = null } }
    DisposableEffect(accessibility) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { exploration = it }
        accessibility.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility.removeTouchExplorationStateChangeListener(listener) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> gestureForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(visible, interaction, sheet, seek != null, pictureSeek != null, blocked, exploration, draggingVertical) {
        if (blocked || exploration || sheet != null || seek != null || pictureSeek != null || draggingVertical) visible = true
        else if (visible) {
            val timeout = accessibility.getRecommendedTimeoutMillis(3000,
                AccessibilityManager.FLAG_CONTENT_CONTROLS or AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_ICONS)
            delay(timeout.toLong())
            visible = false
        }
    }
    val window = context.activity()?.window
    var brightness by remember(window) { mutableFloatStateOf(window?.attributes?.screenBrightness ?: -1f) }
    fun actualBrightness(): Float = brightness.takeIf { it >= 0f }
        ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f).coerceIn(.02f, 1f)
    fun changeBrightness(value: Float) {
        window?.let { owner ->
            brightness = if (value < 0) -1f else value.coerceIn(.02f, 1f)
            owner.attributes = owner.attributes.apply { screenBrightness = brightness }
        }
    }
    fun changeVolume(value: Float) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value.roundToInt().coerceIn(0, maximumVolume), 0)
        mediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }
    LaunchedEffect(audioManager, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { mediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC); delay(500) }
        }
    }
    DisposableEffect(window) {
        val previous = window?.attributes?.screenBrightness
        val owner = context.activity()
        val oldStream = owner?.volumeControlStream
        owner?.volumeControlStream = AudioManager.STREAM_MUSIC
        onDispose {
            if (window != null && previous != null) window.attributes = window.attributes.apply { screenBrightness = previous }
            if (owner != null && oldStream != null) owner.volumeControlStream = oldStream
        }
    }
    DisposableEffect(window, model.player) {
        val oldKeep = view.keepScreenOn
        onDispose {
            model.player.detach()
            view.keepScreenOn = oldKeep
        }
    }
    SideEffect { view.keepScreenOn = state.playing }
    PlayerWindowBars(window, fullscreen && !exploration)
    DisposableEffect(context) {
        val owner = context.activity()
        val original = owner?.requestedOrientation
        onDispose { original?.let { owner.requestedOrientation = it } }
    }
    ClientTheme(darkTheme = true) {
        CompositionLocalProvider(LocalContentColor provides FilewayTheme.status.mediaContent) {
            // Fullscreen media ignores changing system-bar visibility. Insets
            // protect only the overlay controls, never resize the video Surface.
            BoxWithConstraints(Modifier.fillMaxSize().background(FilewayTheme.status.mediaCanvas)
                .then(if (fullscreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
                .onGloballyPositioned { playerPosition = it.positionInRoot() }) {
                val landscape = maxWidth > maxHeight
                val controlInsets = if (exploration) WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
                    else WindowInsets.displayCutout.union(WindowInsets.navigationBarsIgnoringVisibility).union(WindowInsets.captionBar)
                val headerDensity = LocalDensity.current
                var fullscreenHeaderHeight by remember { mutableStateOf(56.dp) }
                val controlPadding = if (landscape) 20.dp else 12.dp
                val downloadCoverageHeight = with(headerDensity) { 16.sp.toDp() }
                val portraitControlsReserve = (if (maxWidth - controlPadding * 2 >= 600.dp) 96.dp else 144.dp) +
                    if (download != null) downloadCoverageHeight else 0.dp
                val portraitStageHeight = maxWidth / (16f / 9f) + portraitControlsReserve
                Column(Modifier.fillMaxSize().then(if (touchLocked) Modifier.clearAndSetSemantics {} else Modifier)) {
                    if (!fullscreen) Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayerIcon(R.drawable.ic_arrow_back, "返回文件", model::leavePlayer)
                        Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        PlayerIcon(R.drawable.ic_more_vert, "更多播放选项", { touch(); sheet = PlayerSheet.MORE })
                    }
                    // Portrait keeps transport below the picture. Fullscreen
                    // uses the entire viewport with controls over the video;
                    // hiding the HUD never changes the native surface size.
                    val stage = if (fullscreen) Modifier.weight(1f) else Modifier.fillMaxWidth().height(portraitStageHeight)
                    Box(stage.background(Color.Black)) {
                        Box(Modifier.fillMaxSize().padding(bottom = if (fullscreen) 0.dp else portraitControlsReserve)) {
                            AndroidView(factory = { PlayerViewport(it).also(model.player::attach) }, modifier = Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().semantics {
                                contentDescription = "视频画面"
                                onClick("显示播放控制") { touch(); true }
                            }.playerGestures(Triple(file.mediaKey, state.mediaGeneration, landscapeOrientation),
                                gestureForeground && !exploration && !touchLocked && !holding && !client.busy && sheet == null, gestureEdge,
                                start = { left ->
                                    dragLeft = left; draggingVertical = true; touch()
                                    dragStart = if (left) actualBrightness() else audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maximumVolume
                                }, change = { delta ->
                                    val next = (dragStart + delta).coerceIn(0f, 1f)
                                    if (dragLeft) {
                                        changeBrightness(next)
                                        gestureMessage = "亮度 ${(brightness * 100).roundToInt()}%"
                                    } else if (!audioManager.isVolumeFixed) {
                                        changeVolume(next * maximumVolume)
                                        gestureMessage = "媒体音量 ${(mediaVolume * 100f / maximumVolume).roundToInt()}%"
                                    } else gestureMessage = "此设备使用固定音量"
                                    interaction++
                                }, finish = { draggingVertical = false; touch() },
                                seekStart = {
                                    val current = model.player.state.value
                                    if (!current.seekable || current.durationMs <= 0 || model.state.value.selected != file) false
                                    else {
                                        gestureSeek.reset()
                                        dragSession = SeekDragSession(current.mediaGeneration, current.positionMs, current.durationMs)
                                        pictureSeek = dragSession?.startMs
                                        touch()
                                        true
                                    }
                                }, seekChange = { fraction -> pictureSeek = dragSession?.preview(fraction) },
                                seekFinish = { released ->
                                    val current = model.player.state.value
                                    val target = dragSession?.finish(current.mediaGeneration, released,
                                        current.seekable && !model.state.value.busy && model.state.value.selected == file)
                                    dragSession = null; pictureSeek = null; touch()
                                    target?.let(model.player::seek)
                                })
                                .pointerInput(file, exploration, touchLocked, gestureForeground, landscapeOrientation) {
                                detectTapGestures(
                                    onTap = { if (!liveBlocked && !exploration) visible = !visible else touch(); interaction++ },
                                    onDoubleTap = { point ->
                                        if (!exploration && !touchLocked && !model.state.value.busy && model.state.value.selected == file) {
                                            touch()
                                            val delta = when {
                                                point.x < size.width / 3f -> -10_000L
                                                point.x > size.width * 2f / 3f -> 10_000L
                                                else -> 0L
                                            }
                                            if (delta == 0L) { gestureSeek.reset(); model.togglePlayback() }
                                            else if (liveState.seekable && liveState.durationMs > 0) {
                                                val target = gestureSeek.next(liveState.positionMs, liveState.durationMs, delta, SystemClock.elapsedRealtime())
                                                model.player.seek(target)
                                                gestureMessage = (if (delta < 0) "后退" else "快进") + " · " + clock(target)
                                            }
                                        }
                                    },
                                    onLongPress = {},
                                    onPress = {
                                        if (!exploration && !touchLocked && model.state.value.selected == file && liveState.playing && !model.state.value.busy) coroutineScope {
                                            var temporaryEpoch: Long? = null
                                            val hold = launch {
                                                delay(viewConfiguration.longPressTimeoutMillis)
                                                if (model.state.value.selected == file && liveState.playing && !model.state.value.busy) {
                                                    temporaryEpoch = model.player.beginTemporaryRate(liveHoldRate)
                                                    holding = temporaryEpoch != null
                                                }
                                            }
                                            try { tryAwaitRelease() }
                                            finally {
                                                hold.cancel()
                                                temporaryEpoch?.let(model.player::restoreRate)
                                                holding = false
                                            }
                                        }
                                    })
                            })
                            if (holding) Text("${holdRate}× 倍速播放", Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
                                .clip(RoundedCornerShape(8.dp)).background(FilewayTheme.status.mediaSurface).padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 13.sp)
                            gestureMessage?.let { message -> Text(message, Modifier.align(Alignment.Center)
                                .clip(RoundedCornerShape(8.dp)).background(FilewayTheme.status.mediaSurface).padding(12.dp), fontSize = 14.sp) }
                            pictureSeek?.let { target -> dragSession?.let { drag ->
                                PictureSeekFeedback(target, drag.startMs, drag.durationMs, seekPreview.takeIf { fullscreen }, Modifier.align(Alignment.Center))
                            } }
                            val failure = if (client.busy) null else client.error ?: state.error
                            if (failure != null) {
                                Column(Modifier.align(Alignment.Center).widthIn(max = 360.dp).padding(20.dp)
                                    .clip(RoundedCornerShape(10.dp)).background(FilewayTheme.status.mediaSurface).padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(failure, style = MaterialTheme.typography.bodyMedium, color = FilewayTheme.colors.error)
                                    PlayerLabel("重试", "重试播放") {
                                        model.retryPlayback()
                                    }
                                }
                            } else if (client.busy) {
                                Row(Modifier.align(Alignment.Center).clip(RoundedCornerShape(10.dp)).background(FilewayTheme.status.mediaSurface).padding(start = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (showRequest) CircularProgressIndicator(Modifier.size(18.dp), color = FilewayTheme.colors.primary, strokeWidth = 2.dp)
                                    Text("正在加载", fontSize = 13.sp)
                                    PlayerLabel("取消", "取消播放请求") { model.cancel() }
                                }
                            } else if (state.waitingForBuffer) {
                                Row(Modifier.align(Alignment.Center).clip(RoundedCornerShape(8.dp)).background(Color(0xCC151515)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CircularProgressIndicator(Modifier.size(18.dp), color = FilewayTheme.colors.primary, strokeWidth = 2.dp)
                                    Text("${state.phase} ${state.buffering.toInt()}%", fontSize = 13.sp)
                                    PlayerLabel("取消", "取消播放等待") { model.leavePlayer() }
                                }
                            }
                            if (showControls || touchLocked) Box(Modifier.align(AbsoluteAlignment.CenterLeft)
                                .then(if (fullscreen) Modifier.windowInsetsPadding(controlInsets.only(WindowInsetsSides.Left)) else Modifier)
                                .absolutePadding(left = 8.dp)) {
                                // Retain this anchor while locked, including after a real
                                // window rotation. The topmost unlock uses these same bounds.
                                PlayerLockIcon(R.drawable.ic_lock_open, "锁定触控", "触控未锁定", false, ::lockTouch,
                                    enabled = !exploration,
                                    modifier = Modifier.alpha(if (touchLocked) 0f else 1f)
                                        .onGloballyPositioned { touchLockPosition = it.positionInRoot() })
                            }
                        }
                        Text(client.downloadBytesPerSecond?.let { speed ->
                            when {
                                speed >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f MB/s", speed / 1_000_000.0)
                                speed >= 1000 -> String.format(java.util.Locale.ROOT, "%.1f KB/s", speed / 1000.0)
                                else -> "$speed B/s"
                            }
                        } ?: "— B/s", color = Color.White, fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.TopEnd).then(if (fullscreen) Modifier.windowInsetsPadding(controlInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) else Modifier)
                                .padding(top = if (fullscreen && showControls) fullscreenHeaderHeight else 8.dp, end = 12.dp)
                                .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp)
                                .semantics { contentDescription = "实际网络下载速度" })
                        if (showControls && fullscreen) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                            .windowInsetsPadding(controlInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                            Row(Modifier.fillMaxWidth()
                                .onSizeChanged { fullscreenHeaderHeight = with(headerDensity) { it.height.toDp() } }
                                .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                val deviceTime = rememberPlayerClock()
                                PlayerIcon(R.drawable.ic_arrow_back, "退出全屏并返回详情", { changeDisplay(PlayerDisplayMode.AUTOMATIC) })
                                Text(file.name, style = FilewayTheme.text.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                                Text(deviceTime, color = Color.White, fontSize = 12.sp, lineHeight = 16.sp,
                                    maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp).clearAndSetSemantics {
                                        contentDescription = "设备时间"
                                        stateDescription = deviceTime
                                    })
                                PlayerIcon(R.drawable.ic_more_vert, "更多播放选项", { touch(); sheet = PlayerSheet.MORE })
                            }
                        }
                        if (showControls) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                            .then(if (fullscreen) Modifier.windowInsetsPadding(controlInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)) else Modifier)
                            .padding(horizontal = controlPadding)) {
                            SeekPreview(seekPreview, seek?.toLong())
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(clock((seek ?: state.positionMs.toFloat()).toLong()), color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp, lineHeight = 16.sp,
                                    fontFamily = FontFamily.Monospace, maxLines = 1)
                                PlayerSlider(seek ?: state.positionMs.toFloat().coerceIn(0f, state.durationMs.toFloat().coerceAtLeast(1f)), state.durationMs.toFloat().coerceAtLeast(1f),
                                    "播放进度", state.seekable && state.durationMs > 0,
                                    { gestureSeek.reset(); seek = it; touch() }, { seek?.let { model.player.seek(it.toLong()) }; seek = null; touch() },
                                    clock((seek ?: state.positionMs.toFloat()).toLong()) + "，共 " + clock(state.durationMs),
                                    downloadedValue = downloadedUntil?.toFloat(), bufferedValue = state.bufferedPositionMs.toFloat(), modifier = Modifier.weight(1f))
                                Text(if (state.durationMs > 0) clock(state.durationMs) else "--:--", color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp, lineHeight = 16.sp,
                                    fontFamily = FontFamily.Monospace, maxLines = 1)
                            }
                            download?.let { item ->
                                val percent = if (item.expectedSize > 0) (downloadedBytes.toDouble() * 100 / item.expectedSize).toInt().coerceIn(0, 100) else 0
                                val coverage = if (item.complete) "已全部下载" else downloadedUntil?.let { "可离线播放至 ${clock(it)}" } ?: "可播放范围暂未知"
                                Row(Modifier.fillMaxWidth().heightIn(min = downloadCoverageHeight).padding(horizontal = 8.dp).semantics {
                                    contentDescription = "文件下载进度"
                                    stateDescription = "已下载 $percent%，$coverage"
                                    progressBarRangeInfo = ProgressBarRangeInfo(percent / 100f, 0f..1f)
                                }, verticalAlignment = Alignment.CenterVertically) {
                                    Text("已下载 $percent%", color = FilewayTheme.status.mediaSecondary, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                    Text(coverage, color = FilewayTheme.status.mediaSecondary, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1)
                                }
                            }
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val transport: @Composable RowScope.() -> Unit = {
                                    PlayerIcon(if (state.playing) R.drawable.art_pause else R.drawable.art_play, if (state.playing) "暂停播放" else "开始播放", { touch(); model.togglePlayback() }, !client.busy)
                                    PlayerIcon(R.drawable.ic_skip_next, "下一个视频", { touch(); model.nextMedia() }, queue?.hasNext == true)
                                }
                                val options: @Composable RowScope.() -> Unit = {
                                    val audioTitle = state.audio.firstOrNull { it.id == state.selectedAudio }?.title ?: "暂无音轨"
                                    val subtitleTitle = state.subtitles.firstOrNull { it.id == state.selectedSubtitle }?.title ?: "关闭"
                                    PlayerIcon(R.drawable.ic_audio, "选择音轨", { touch(); sheet = PlayerSheet.AUDIO }, state.audio.isNotEmpty(),
                                        description = (if (state.pendingAudio != null) "正在切换，当前音轨：" else "当前音轨：") + audioTitle)
                                    PlayerIcon(R.drawable.ic_subtitles, "选择字幕", { touch(); sheet = PlayerSheet.SUBTITLE }, !client.busy,
                                        description = (if (state.pendingSubtitle != null) "正在切换，当前字幕：" else "当前字幕：") + subtitleTitle)
                                    PlayerLabel("${state.rate}×", "播放速度") { touch(); sheet = PlayerSheet.SPEED }
                                    PlayerIcon(R.drawable.ic_playlist_play, "播放列表", { touch(); sheet = PlayerSheet.QUEUE }, queue != null,
                                        description = queue?.let { "第 ${it.index + 1} 项，共 ${it.items.size} 项" } ?: "无播放列表")
                                }
                                val display: @Composable RowScope.() -> Unit = {
                                    if (!fullscreen) PlayerLabel("竖屏", "竖屏全屏") { changeDisplay(PlayerDisplayMode.PORTRAIT_FULLSCREEN) }
                                    PlayerIcon(if (fullscreen) R.drawable.art_fullscreen_off else R.drawable.art_fullscreen_on, if (fullscreen) "退出全屏" else "横屏全屏", {
                                        changeDisplay(if (fullscreen) PlayerDisplayMode.AUTOMATIC else PlayerDisplayMode.LANDSCAPE_FULLSCREEN)
                                    })
                                }
                                if (maxWidth >= 600.dp) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    transport()
                                    Spacer(Modifier.weight(1f))
                                    options()
                                    display()
                                } else Column(Modifier.fillMaxWidth()) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        transport()
                                        Spacer(Modifier.weight(1f))
                                        display()
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { options() }
                                }
                            }
                        }
                    }
                    if (!fullscreen) Column(Modifier.weight(1f).semantics { contentDescription = "播放详情" }.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(file.name, style = FilewayTheme.text.appBar,
                                maxLines = if (expandedTitle) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button,
                                    onClickLabel = if (expandedTitle) "收起完整名称" else "展开完整名称") { expandedTitle = !expandedTitle; touch() })
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(playbackSourceLabel, Modifier.weight(1f), color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (state.width > 0) Text("${state.width} × ${state.height}", color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(queue?.let { "播放列表 · ${it.index + 1} / ${it.items.size}" } ?: "单个视频",
                                style = FilewayTheme.text.metadata, color = FilewayTheme.status.mediaSecondary)
                            PlayerLabel("播放信息", "播放来源") { touch(); sheet = PlayerSheet.SOURCE }
                        }
                    }
                }
                if (sheet == null) SnackbarHost(feedback, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp)
                    .then(if (touchLocked) Modifier.clearAndSetSemantics {} else Modifier))
                if (touchLocked) {
                    // The topmost input layer covers controls as well as the picture. System Back unlocks first.
                    Box(Modifier.fillMaxSize()
                        .clearAndSetSemantics { contentDescription = "触控已锁定" }
                        .pointerInput(Unit) { detectTapGestures(onTap = {}, onDoubleTap = {}, onLongPress = {}) })
                    PlayerLockIcon(R.drawable.ic_lock, "解除触控锁定", "触控已锁定", true, ::unlockTouch,
                        modifier = Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset {
                            val position = touchLockPosition - playerPosition
                            IntOffset(position.x.roundToInt(), position.y.roundToInt())
                        })
                }
                if (sheet != null) PlayerPanel(landscape, fullscreen && !exploration, when (sheet) { PlayerSheet.AUDIO -> "音轨"; PlayerSheet.SUBTITLE -> "字幕"; PlayerSheet.SUBTITLE_ADJUST -> "字幕调整"; PlayerSheet.SUBTITLE_FONT -> "字幕字体"; PlayerSheet.SPEED -> "播放速度"; PlayerSheet.SPEED_EDIT -> if (editingHoldRate) "长按倍速" else "自定义倍速"; PlayerSheet.VOLUME -> "媒体系统音量"; PlayerSheet.BRIGHTNESS -> "窗口亮度"; PlayerSheet.EXTERNAL -> "外挂字幕"; PlayerSheet.QUEUE -> "播放列表"; PlayerSheet.MORE -> "更多播放选项"; else -> "播放来源" }, { sheet = null; touch() }, back = if (sheet == PlayerSheet.SUBTITLE_ADJUST) {
                    { sheet = PlayerSheet.SUBTITLE; touch() }
                } else if (sheet == PlayerSheet.SUBTITLE_FONT) {
                    { sheet = PlayerSheet.SUBTITLE_ADJUST; touch() }
                } else if (sheet == PlayerSheet.SPEED_EDIT) {
                    { sheet = PlayerSheet.SPEED; speedError = null; touch() }
                } else null, feedback = feedback, backLabel = when (sheet) { PlayerSheet.SPEED_EDIT -> "返回播放速度"; PlayerSheet.SUBTITLE_FONT -> "返回字幕调整"; else -> "返回字幕轨道" }) {
                    when (sheet) {
                        PlayerSheet.MORE -> Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SourceField("文件", file.name)
                            HorizontalDivider(color = FilewayTheme.colors.outlineVariant)
                            val actions = listOf(
                                Triple("媒体系统音量", "${(mediaVolume * 100f / maximumVolume).roundToInt()}%", PlayerSheet.VOLUME),
                                Triple("窗口亮度", if (brightness < 0) "跟随系统" else "${(brightness * 100).roundToInt()}%", PlayerSheet.BRIGHTNESS),
                                Triple("播放来源", "文件与播放信息", PlayerSheet.SOURCE))
                            actions.forEach { (label, value, target) ->
                                fun open() { touch(); sheet = target }
                                ListItem(headlineContent = { Text(label, fontSize = 14.sp) },
                                    supportingContent = { Text(value, fontSize = 12.sp) },
                                    trailingContent = { Icon(painterResource(R.drawable.ic_arrow_forward), null, Modifier.size(20.dp)) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = Color.White,
                                        supportingColor = FilewayTheme.status.mediaSecondary, trailingIconColor = FilewayTheme.status.mediaSecondary),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = ::open).clearAndSetSemantics {
                                        contentDescription = label
                                        stateDescription = value
                                        role = Role.Button
                                        onClick { open(); true }
                                    })
                            }
                        }
                        PlayerSheet.QUEUE -> queue?.let { snapshot ->
                            PlayerQueuePanel(model, snapshot, state.playing, state.durationMs, choose = { index ->
                                model.navigateMedia(index); sheet = null; touch()
                            })
                        }
                        PlayerSheet.AUDIO, PlayerSheet.SUBTITLE -> {
                            val audio = sheet == PlayerSheet.AUDIO
                            PlayerTrackList(
                                tracks = if (audio) state.audio else state.subtitles,
                                selected = if (audio) state.selectedAudio else state.selectedSubtitle,
                                pending = if (audio) state.pendingAudio else state.pendingSubtitle,
                                subtitle = !audio,
                                footer = if (audio) null else { {
                                    PlayerSubtitleHomeActions(
                                        downloadLocal = file.downloadId.isNotEmpty(),
                                        loading = state.subtitleLoading,
                                        readingDocument = readingDocument,
                                        onAdjust = { sheet = PlayerSheet.SUBTITLE_ADJUST; touch() },
                                        onServerSubtitle = { sheet = PlayerSheet.EXTERNAL; touch() },
                                        onLocalSubtitle = {
                                            documentGeneration = model.player.state.value.mediaGeneration
                                            touch()
                                            try { subtitleDocument.launch(arrayOf("*/*")) }
                                            catch (_: android.content.ActivityNotFoundException) {
                                                documentGeneration = null
                                                uiScope.launch { feedback.showSnackbar("此设备没有可用的文件选择器") }
                                            }
                                        },
                                    )
                                } },
                            ) {
                                if (audio) model.player.audio(it) else model.player.subtitle(it)
                                touch()
                            }
                        }
                        PlayerSheet.SUBTITLE_ADJUST -> PlayerSubtitleAdjustments(
                            delayMs = state.subtitleDelayMs,
                            codec = state.subtitles.firstOrNull { it.id == state.selectedSubtitle }?.codec.orEmpty(),
                            offsetText = subtitleOffset,
                            onOffsetText = { subtitleOffset = it; subtitleOffsetError = null },
                            offsetError = subtitleOffsetError,
                            onDelay = model.player::subtitleDelay,
                            onApplyOffset = {
                                val seconds = subtitleOffset.trim().toDoubleOrNull()
                                if (seconds == null || !seconds.isFinite() || seconds !in -600.0..600.0) subtitleOffsetError = "请输入 -600 到 600 秒"
                                else { model.player.subtitleDelay((seconds * 1000).toLong()); subtitleOffsetError = null }
                            },
                            appearance = subtitlePreview,
                            onAppearance = { subtitlePreview = it },
                            onChooseFont = { sheet = PlayerSheet.SUBTITLE_FONT; touch() },
                            onSaveAppearance = {
                                val value = subtitlePreview
                                uiScope.launch {
                                    try { model.playbackPreferences.saveTextSubtitleAppearance(value) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { feedback.showSnackbar("字幕外观保存失败，请重试") }
                                }
                            },
                        )
                        PlayerSheet.SUBTITLE_FONT -> PlayerSubtitleFontPicker(subtitlePreview) {
                            subtitlePreview = it; sheet = PlayerSheet.SUBTITLE_ADJUST; touch()
                        }
                        PlayerSheet.EXTERNAL -> NasSubtitlePicker(model, file, chosen = { sheet = PlayerSheet.SUBTITLE; touch() })
                        PlayerSheet.SPEED -> PlayerSpeedHome(
                            rate = state.rate, holdRate = holdRate,
                            onPreset = { model.player.rate(it); sheet = null; touch() },
                            onEdit = { hold ->
                                speedEditRevision += 1
                                editingHoldRate = hold
                                customRate = (if (hold) holdRate else state.rate).toString()
                                speedError = null
                                sheet = PlayerSheet.SPEED_EDIT
                                touch()
                            },
                        )
                        PlayerSheet.SPEED_EDIT -> PlayerSpeedEditor(
                            value = customRate, hold = editingHoldRate, error = speedError,
                            saving = savingHoldRate,
                            onValue = { customRate = it; speedError = null },
                            onApply = {
                                val value = parsePlaybackRate(customRate)
                                if (value == null) speedError = "请输入 0.1–5 之间的倍速"
                                else if (!editingHoldRate) {
                                    model.player.rate(value); sheet = null; touch()
                                } else if (!savingHoldRate) {
                                    savingHoldRate = true
                                    val editRevision = speedEditRevision
                                    uiScope.launch {
                                        try {
                                            model.playbackPreferences.saveHoldRate(value)
                                            if (sheet == PlayerSheet.SPEED_EDIT && editingHoldRate && speedEditRevision == editRevision) sheet = PlayerSheet.SPEED
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) {
                                            if (speedEditRevision == editRevision) speedError = "长按倍速未能保存，请重试"
                                            else feedback.showSnackbar("长按倍速未能保存，请重试")
                                        }
                                        finally { savingHoldRate = false }
                                    }
                                }
                            },
                        )
                        PlayerSheet.VOLUME -> Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("${(mediaVolume * 100f / maximumVolume).roundToInt()}%", fontSize = 32.sp, fontWeight = FontWeight.Medium)
                            PlayerSlider(mediaVolume.toFloat(), maximumVolume.toFloat(), "媒体系统音量", !audioManager.isVolumeFixed,
                                { changeVolume(it) }, description = "${mediaVolume}，共 $maximumVolume 档")
                            Text(if (audioManager.isVolumeFixed) "此设备使用固定音量。" else "与设备媒体音量键同步，右侧上下滑动也可调整。", fontSize = 13.sp, color = FilewayTheme.status.mediaSecondary)
                            Spacer(Modifier.height(8.dp))
                        }
                        PlayerSheet.BRIGHTNESS -> Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(if (brightness < 0) "跟随系统" else "${(brightness * 100).roundToInt()}%", fontSize = 28.sp)
                            PlayerSlider(actualBrightness() * 100, 100f, "窗口亮度", window != null, { changeBrightness(it / 100) })
                            Text("仅调整当前播放窗口，退出后恢复。左侧上下滑动也可调整。", fontSize = 13.sp, color = FilewayTheme.status.mediaSecondary)
                            TextButton(onClick = { changeBrightness(-1f) }) { Text("恢复系统亮度") }
                        }
                        PlayerSheet.SOURCE -> LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            item { SourceField("文件", file.name) }
                            item { SourceField(if (file.downloadId.isEmpty()) "服务器与账号" else "来源", playbackSourceLabel) }
                            item { SourceField("路径", file.path) }
                            item { SourceField("播放方式", "原生播放 · Media3") }
                            item { SourceField("画质", "原画 · 直接读取原文件") }
                            if (state.width > 0) item { SourceField("源分辨率", "${state.width} × ${state.height}") }
                            item { SourceField("视频编码", state.sourceVideoCodec) }
                            item { SourceField("请求的视频解码策略", state.videoDecodePolicy.label) }
                            item { SourceField("实际视频解码方式", state.videoDecoderKind) }
                            item { SourceField("实际视频解码器", state.videoDecoder) }
                            item {
                                Column {
                                    Text("切换策略会保存进度并重新打开当前视频，后续视频也使用此设置。", style = MaterialTheme.typography.bodySmall, color = FilewayTheme.status.mediaSecondary)
                                    VideoDecodePolicy.entries.forEach { policy ->
                                        Choice(policy.label, when (policy) {
                                            VideoDecodePolicy.AUTO -> "优先硬件，解码器故障时尝试可用的软件解码"
                                            VideoDecodePolicy.HARDWARE -> "只使用硬件视频解码器"
                                            VideoDecodePolicy.SOFTWARE -> "使用设备提供的软件视频解码器"
                                        }, state.videoDecodePolicy == policy, enabled = !changingDecoder && !client.busy && state.videoDecodePolicy != policy) {
                                            changingDecoder = true
                                            uiScope.launch {
                                                try { model.changeVideoDecoder(policy); sheet = null }
                                                catch (cancelled: CancellationException) { throw cancelled }
                                                catch (_: Exception) { feedback.showSnackbar("无法切换解码策略，请重试") }
                                                finally { changingDecoder = false }
                                            }
                                        }
                                    }
                                }
                            }
                            item { SourceField("实际音频解码器", state.audioDecoder) }
                            state.decoderRecovery?.let { recovery -> item { SourceField("解码恢复", recovery) } }
                            item { SourceField("片源动态范围", state.sourceDynamicRange) }
                            item { SourceField("片源色彩空间", state.sourceColorSpace) }
                            item {
                                val supported = view.display?.hdrCapabilities?.supportedHdrTypes
                                val names = supported?.map { type -> when (type) {
                                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                                    Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                                    else -> "类型 $type"
                                } }
                                SourceField("当前屏幕 HDR 能力", names?.joinToString(" / ")?.ifEmpty { "系统未报告 HDR 支持" } ?: "未知")
                            }
                            item { SourceField("实际 HDR 输出", "尚未确认；片源标记和屏幕支持不代表已启用 HDR") }
                            item { SourceField("引擎报告的缓冲位置", if (state.bufferedPositionMs > 0) clock(state.bufferedPositionMs) else "未知") }
                            if (state.durationMs > 0) item { SourceField("全片时长", clock(state.durationMs)) }
                            client.progressStatus?.let { item { SourceField("续播", it) } }
                        }
                        null -> Unit
                    }
                }
            }
        }
    }
}


@Composable private fun PlayerLockIcon(icon: Int, label: String, state: String, locked: Boolean, click: () -> Unit,
    enabled: Boolean = true, modifier: Modifier = Modifier) {
    IconButton(onClick = click, enabled = enabled, modifier = modifier.size(48.dp)
        .background(Color.Black.copy(alpha = .28f), CircleShape).clearAndSetSemantics {
            contentDescription = label
            stateDescription = state
            selected = locked
            role = Role.Button
            if (enabled) onClick { click(); true } else disabled()
        }) {
        Icon(painterResource(icon), null, Modifier.size(22.dp),
            tint = if (!enabled) Color.White.copy(alpha = .38f) else if (locked) FilewayTheme.colors.primary else Color.White.copy(alpha = .94f))
    }
}

@Composable private fun PlayerIcon(icon: Int, label: String, click: () -> Unit, enabled: Boolean = true, description: String? = null) {
    IconButton(onClick = click, enabled = enabled, modifier = Modifier.size(48.dp).clearAndSetSemantics {
        contentDescription = label
        description?.let { stateDescription = it }
        role = Role.Button
        if (enabled) onClick { click(); true } else disabled()
    }) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = Color.White.copy(alpha = if (enabled) 0.94f else 0.38f))
    }
}
@Composable private fun PlayerLabel(text: String, label: String, enabled: Boolean = true, click: () -> Unit) {
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = click).clearAndSetSemantics {
        contentDescription = label
        stateDescription = text
        role = Role.Button
        if (enabled) onClick { click(); true } else disabled()
    }.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = if (enabled) 0.94f else 0.38f))
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PlayerSlider(value: Float, maximum: Float, label: String, enabled: Boolean, change: (Float) -> Unit, finish: () -> Unit = {}, description: String = "${value.toInt()}%", downloadedValue: Float? = null, modifier: Modifier = Modifier, bufferedValue: Float? = null) {
    // Preserve Material's drag and keyboard handling. Export one named range
    // with meaningful time/volume state, rather than split label/range nodes
    // and the default raw millisecond number. Accessibility uses the same
    // change/finish callbacks as direct manipulation.
    val accent = FilewayTheme.colors.primary
    val secondary = FilewayTheme.status.mediaSecondary
    val contentColor = FilewayTheme.status.mediaContent
    Box(modifier.fillMaxWidth().clearAndSetSemantics {
        contentDescription = label
        stateDescription = description
        progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, maximum), 0f..maximum)
        if (!enabled) disabled()
        setProgress { target ->
            val next = target.coerceIn(0f, maximum)
            if (!enabled || !next.isFinite() || next == value) false else { change(next); finish(); true }
        }
    }) {
        Slider(value = value.coerceIn(0f, maximum), onValueChange = change, onValueChangeFinished = finish, valueRange = 0f..maximum, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        // Equal slot heights give track and thumb the same vertical origin;
        // the visible 3dp line and 10dp circle are drawn around that centre.
        thumb = { Canvas(Modifier.width(10.dp).height(48.dp)) {
            drawCircle(if (enabled) accent else secondary, radius = 5.dp.toPx(), center = center)
        } },
        track = { Canvas(Modifier.fillMaxWidth().height(48.dp)) {
            val y = size.height / 2
            drawLine(contentColor.copy(alpha = 0.24f), Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), StrokeCap.Round)
            downloadedValue?.let { available ->
                drawLine(accent.copy(alpha = .35f), Offset(0f, y), Offset(size.width * (available / maximum).coerceIn(0f, 1f), y), 3.dp.toPx(), StrokeCap.Round)
            }
            bufferedValue?.takeIf { it.isFinite() }?.let { buffered ->
                drawLine(secondary.copy(alpha = .8f), Offset(0f, y), Offset(size.width * (buffered / maximum).coerceIn(0f, 1f), y), 3.dp.toPx(), StrokeCap.Round)
            }
            drawLine(if (enabled) accent else secondary, Offset(0f, y), Offset(size.width * (value / maximum).coerceIn(0f, 1f), y), 3.dp.toPx(), StrokeCap.Round)
        } })
    }
}
@Composable private fun DetailAction(icon: Int, title: String, value: String, label: String, enabled: Boolean = true, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = click).clearAndSetSemantics {
        contentDescription = label
        stateDescription = value
        role = Role.Button
        if (enabled) onClick { click(); true } else disabled()
    }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = FilewayTheme.status.mediaSecondary)
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(value, modifier = Modifier.weight(1f), fontSize = 13.sp, color = FilewayTheme.status.mediaSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Icon(painterResource(R.drawable.ic_arrow_forward), null, Modifier.size(18.dp), tint = FilewayTheme.status.mediaSecondary)
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PlayerPanel(landscape: Boolean, immersive: Boolean, title: String, dismiss: () -> Unit, back: (() -> Unit)? = null, feedback: SnackbarHostState, backLabel: String = "返回字幕轨道", content: @Composable () -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(dismissOnBackPress = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val keyboard = LocalSoftwareKeyboardController.current
        val imeVisible = WindowInsets.isImeVisible
        val dialogView = LocalView.current
        var imeDismissRequested by remember { mutableStateOf(false) }
        LaunchedEffect(imeVisible) {
            // Compose visibility can briefly bounce false -> true while IME
            // hides. Only a genuinely visible native IME starts a new cycle.
            if (imeVisible && androidx.core.view.ViewCompat.getRootWindowInsets(dialogView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true) {
                imeDismissRequested = false
            }
        }
        // This dialog owns its Back dispatcher and IME insets. Keep an edit's
        // first Back local to the keyboard, then return through the panel.
        BackHandler {
            val rootIme = androidx.core.view.ViewCompat.getRootWindowInsets(dialogView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            // Native insets hide before Compose's IME animation finishes. The
            // first Back still belongs to that IME; later Back must not be eaten
            // by its old visible snapshot. Keep this acknowledgement across pages.
            val hideIme = when (rootIme) {
                true -> true
                false -> imeVisible && !imeDismissRequested
                null -> imeVisible && !imeDismissRequested
            }
            if (hideIme) { imeDismissRequested = true; keyboard?.hide() }
            else (back ?: dismiss)()
        }
        val panelWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        PlayerWindowBars(panelWindow, immersive)
        SideEffect {
            @Suppress("DEPRECATION")
            panelWindow?.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= 29) panelWindow?.isNavigationBarContrastEnforced = false
        }
        val panelInsets = if (immersive)
            WindowInsets.displayCutout.union(WindowInsets.navigationBarsIgnoringVisibility).union(WindowInsets.captionBar)
            else WindowInsets.safeDrawing
        BoxWithConstraints(Modifier.fillMaxSize()
            .windowInsetsPadding(panelInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).imePadding()
            .semantics { contentDescription = "播放设置" }) {
            Box(Modifier.fillMaxSize().clickable(onClick = dismiss))
            val panel = if (landscape) Modifier.align(Alignment.CenterEnd).width(maxWidth.coerceAtMost(360.dp)).fillMaxHeight()
                else Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = if (imeVisible || back != null) maxHeight else maxHeight * 0.85f)
            Column(panel.clip(if (landscape) RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp) else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(FilewayTheme.status.mediaSurface).pointerInput(Unit) { detectTapGestures(onTap = {}) }
                .windowInsetsPadding(panelInsets.only(WindowInsetsSides.Bottom)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (back != null) PlayerIcon(R.drawable.ic_arrow_back, backLabel, back)
                    Text(title, style = FilewayTheme.text.appBar, modifier = Modifier.weight(1f))
                    PlayerIcon(R.drawable.art_close, "关闭播放设置", dismiss)
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Color(0xFF323236))
                Box(Modifier.weight(1f, fill = false).fillMaxWidth()) { content() }
            }
            // The active dialog owns feedback while open; keep the same state
            // so failed track requests remain visible and accessible above it.
            SnackbarHost(feedback, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(panelInsets.only(WindowInsetsSides.Bottom)).padding(16.dp))
        }
    }
}
@Composable private fun Choice(title: String, detail: String, selected: Boolean, enabled: Boolean = true, choose: () -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (selected) FilewayTheme.colors.primary.copy(alpha = 0.10f) else Color.Transparent)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = choose).alpha(if (enabled) 1f else .38f).heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, color = if (selected) FilewayTheme.colors.primary else FilewayTheme.status.mediaContent)
            if (detail.isNotBlank()) Text(detail, fontSize = 12.sp, color = FilewayTheme.status.mediaSecondary)
        }
        if (selected) Icon(painterResource(R.drawable.art_check), null, Modifier.size(20.dp), tint = FilewayTheme.colors.primary)
    }
}
@Composable private fun SourceField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, color = FilewayTheme.status.mediaSecondary)
        SelectionContainer { Text(value, fontSize = 15.sp, lineHeight = 22.sp) }
    }
}

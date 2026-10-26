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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.ui.unit.Dp
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

private enum class PlayerSheet { AUDIO, SUBTITLE, SUBTITLE_ADJUST, SUBTITLE_FONT, SPEED, SPEED_EDIT, SPEED_PRESETS, SOURCE, DECODER, EXTERNAL, QUEUE }
private enum class PlayerDisplayMode { AUTOMATIC, PORTRAIT_FULLSCREEN, LANDSCAPE_FULLSCREEN }
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun PlayerScreen(model: ClientModel, file: ResourceRef) {
    val pictureInPicture = LocalPlayerPictureInPicture.current
    val pipMode = pictureInPicture?.let { it.active || it.preparing } == true
    val artwork = rememberPlayerArtwork(model, file)
    val pageColors = artwork.scheme
    val state by model.player.state.collectAsStateWithLifecycle()
    val client by model.state.collectAsStateWithLifecycle()
    val downloads by model.downloads.state.collectAsStateWithLifecycle()
    val queue = client.mediaQueue
    val liveState by rememberUpdatedState(state)
    val holdRate by model.playbackPreferences.holdRate.collectAsStateWithLifecycle()
    val ratePresets by model.playbackPreferences.ratePresets.collectAsStateWithLifecycle()
    val savedSubtitleAppearance by model.playbackPreferences.textSubtitleAppearance.collectAsStateWithLifecycle()
    // A save acknowledgement must not replace edits made while the write was in flight.
    var subtitlePreview by remember { mutableStateOf(savedSubtitleAppearance) }
    var savingSubtitle by remember { mutableStateOf(false) }
    LaunchedEffect(subtitlePreview) { model.player.textSubtitleAppearance(subtitlePreview) }
    val liveHoldRate by rememberUpdatedState(holdRate)
    var holding by remember(file) { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()
    var customRate by remember { mutableStateOf("") }
    var editingHoldRate by remember { mutableStateOf(false) }
    var savingHoldRate by remember { mutableStateOf(false) }
    var speedEditRevision by remember { mutableIntStateOf(0) }
    var savingPresets by remember { mutableStateOf(false) }
    var presetError by remember { mutableStateOf<String?>(null) }
    var speedError by remember { mutableStateOf<String?>(null) }
    var changingDecoder by remember { mutableStateOf(false) }
    var subtitleOffset by remember(file) { mutableStateOf("") }
    var subtitleOffsetError by remember(file) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val download = downloads.items.firstOrNull { it.id == file.downloadId }
    val playbackSourceLabel = when {
        file.downloadId.isEmpty() -> "服务器原文件"
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
    var gestureRevision by remember(file) { mutableIntStateOf(0) }
    var dragLeft by remember(file) { mutableStateOf(false) }
    var dragStart by remember(file) { mutableFloatStateOf(0f) }
    val landscapeOrientation = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var displayMode by rememberSaveable { mutableStateOf(PlayerDisplayMode.AUTOMATIC) }
    val fullscreen = pipMode || landscapeOrientation || displayMode != PlayerDisplayMode.AUTOMATIC
    val view = LocalView.current
    val accessibility = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    var exploration by remember { mutableStateOf(accessibility.isTouchExplorationEnabled) }
    var visible by remember(file) { mutableStateOf(true) }
    var touchLocked by remember(file) { mutableStateOf(false) }
    var unlockVisible by remember(file) { mutableStateOf(false) }
    var lockInteraction by remember(file) { mutableIntStateOf(0) }
    var playerPosition by remember { mutableStateOf(Offset.Zero) }
    var touchLockPosition by remember { mutableStateOf(Offset.Zero) }
    val gestureSeek = remember(file) { SeekGestureAccumulator() }
    var gestureMessage by remember(file) { mutableStateOf<String?>(null) }
    var gestureMeter by remember(file) { mutableStateOf<Pair<Boolean, Float>?>(null) }
    var interaction by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var seek by remember(file) { mutableStateOf<Float?>(null) }
    var timelineStart by remember(file) { mutableLongStateOf(0L) }
    var pictureSeek by remember(file, state.mediaGeneration) { mutableStateOf<Long?>(null) }
    var dragSession by remember(file, state.mediaGeneration) { mutableStateOf<SeekDragSession?>(null) }
    val seekPreview = rememberSeekPreview(model, file, state.mediaGeneration, seek != null || pictureSeek != null)
    val showControls = !pipMode && (!fullscreen || visible && sheet == null) && pictureSeek == null && !touchLocked
    val controlsEnabled = !client.busy && !touchLocked
    var showRequest by remember(file) { mutableStateOf(false) }
    var showBuffering by remember(file) { mutableStateOf(false) }
    // Transfer statistics describe actual upstream reads, including prebuffering
    // while playback is smooth. Buffering state must not hide real activity.
    val networkStatus = client.downloadBytesPerSecond?.takeIf { it > 0 }?.let { speed ->
        when {
            speed >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f MB/s", speed / 1_000_000.0)
            speed >= 1000 -> String.format(java.util.Locale.ROOT, "%.1f KB/s", speed / 1000.0)
            else -> "$speed B/s"
        }
    }
    val feedback = remember { SnackbarHostState() }
    var subtitleFooterHeight by remember { mutableIntStateOf(0) }
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
        if (client.busy) { delay(350); showRequest = true }
    }
    LaunchedEffect(state.waitingForBuffer, file) {
        showBuffering = false
        if (state.waitingForBuffer) { delay(350); showBuffering = true }
    }
    LaunchedEffect(client.progressStatus) {
        if (client.progressStatus == "续播保存失败，请重试" &&
            feedback.showSnackbar("续播未能保存", actionLabel = "重试", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
            model.retryProgress()
        }
    }
    val blocked = state.error != null || client.error != null || client.busy
    val liveBlocked by rememberUpdatedState(blocked)
    fun touch() { visible = true; interaction++ }
    fun cancelGesture() {
        gestureRevision++
        gestureSeek.reset(); seek = null; pictureSeek = null; dragSession = null; draggingVertical = false
        gestureMessage = null; gestureMeter = null
        if (holding) model.player.restoreRate(model.player.state.value.mediaGeneration)
        holding = false
    }
    fun lockTouch() {
        cancelGesture(); sheet = null; touchLocked = true; unlockVisible = true; lockInteraction++
    }
    fun unlockTouch() { touchLocked = false; unlockVisible = false; touch() }
    fun toggleUnlock() { unlockVisible = !unlockVisible; lockInteraction++ }
    fun changeDisplay(mode: PlayerDisplayMode) {
        touch()
        sheet = null
        displayMode = mode
        context.activity()?.requestedOrientation = if (mode == PlayerDisplayMode.LANDSCAPE_FULLSCREEN)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }
    BackHandler(fullscreen && !touchLocked) { changeDisplay(PlayerDisplayMode.AUTOMATIC) }
    BackHandler(touchLocked) { unlockTouch() }
    LaunchedEffect(exploration) { if (exploration) { cancelGesture(); unlockTouch() } }
    LaunchedEffect(touchLocked, unlockVisible, lockInteraction, exploration) {
        if (touchLocked && unlockVisible && !exploration) {
            val timeout = accessibility.getRecommendedTimeoutMillis(3000,
                AccessibilityManager.FLAG_CONTENT_CONTROLS or AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_ICONS)
            delay(timeout.toLong()); unlockVisible = false
        }
    }
    LaunchedEffect(sheet, touchLocked, gestureForeground) {
        if (sheet != null || touchLocked || !gestureForeground) cancelGesture()
    }
    LaunchedEffect(pipMode) { if (pipMode) { cancelGesture(); sheet = null; visible = false } }
    LaunchedEffect(gestureMessage, gestureMeter, interaction, draggingVertical) { if (!draggingVertical && (gestureMessage != null || gestureMeter != null)) { delay(1_000); gestureMessage = null; gestureMeter = null } }
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
    LaunchedEffect(visible, interaction, sheet, seek != null, pictureSeek != null, blocked, state.playing, exploration, draggingVertical) {
        if (blocked || exploration || sheet != null || seek != null || pictureSeek != null || draggingVertical) visible = true
        else if (visible && state.playing) {
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
    PlayerWindowBars(window, fullscreen && !exploration, fullscreen || pageColors.background.luminance() < .5f)
    DisposableEffect(context) {
        val owner = context.activity()
        val original = owner?.requestedOrientation
        onDispose { original?.let { owner.requestedOrientation = it } }
    }
    io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaTheme(pageColors, fullscreen) {
        CompositionLocalProvider(LocalContentColor provides if (fullscreen) FilewayTheme.status.mediaContent else pageColors.onBackground) {
            // Fullscreen media ignores changing system-bar visibility. Insets
            // protect only the overlay controls, never resize the video Surface.
            // Paint first, then inset only interactive content. One atmosphere extends
            // behind both transparent system bars instead of leaving fixed graphite strips.
            Box(Modifier.fillMaxSize().background(if (fullscreen) FilewayTheme.status.mediaCanvas else pageColors.background)) {
                if (!fullscreen) PlayerAmbient(artwork.atmosphere, state.playing && gestureForeground, Modifier.fillMaxSize())
            BoxWithConstraints(Modifier.fillMaxSize()
                .then(if (fullscreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)))
                .onGloballyPositioned { playerPosition = it.positionInRoot() }) {
                val landscape = maxWidth > maxHeight
                val controlInsets = if (exploration) WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
                    else WindowInsets.displayCutout.union(WindowInsets.captionBar)
                val headerDensity = LocalDensity.current
                val controlPadding = if (landscape) 20.dp else 12.dp
                val downloadCoverageHeight = with(headerDensity) { 16.sp.toDp() }
                val portraitControlsReserve = (if (maxWidth - controlPadding * 2 >= 600.dp) 96.dp else 144.dp) +
                    if (download != null) downloadCoverageHeight else 0.dp
                val portraitStageHeight = maxWidth / (16f / 9f) + portraitControlsReserve
                Column(Modifier.fillMaxSize().then(if (touchLocked) Modifier.clearAndSetSemantics {} else Modifier)) {
                    if (!fullscreen) Column(Modifier.fillMaxWidth().drawBehind {
                        // Mirror the lower 72dp blend through the existing status/title area.
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black),
                            startY = size.height - 72.dp.toPx(), endY = size.height))
                    }.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayerIcon(R.drawable.ic_arrow_back, "返回文件", model::leavePlayer)
                        Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        networkStatus?.let { PlayerNetworkStatus(it) }
                        PlayerIcon(R.drawable.ic_info, "播放信息", { touch(); sheet = PlayerSheet.SOURCE })
                    }
                    FilewaySessionNotice(model)
                    }
                    // Portrait keeps transport below the picture. Fullscreen
                    // uses the entire viewport with controls over the video;
                    // hiding the HUD never changes the native surface size.
                    val stage = if (fullscreen) Modifier.weight(1f) else Modifier.fillMaxWidth().height(portraitStageHeight)
                    CompositionLocalProvider(LocalContentColor provides FilewayTheme.status.mediaContent) {
                    Box(stage.background(if (fullscreen) Color.Black else Color.Transparent)) {
                        if (!fullscreen) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(portraitControlsReserve)
                            .background(Brush.verticalGradient(listOf(Color.Black, Color.Transparent),
                                endY = with(headerDensity) { 72.dp.toPx() })))
                        Box(Modifier.fillMaxSize().padding(bottom = if (fullscreen) 0.dp else portraitControlsReserve).background(Color.Black)) {
                            AndroidView(factory = { PlayerViewport(it).also(model.player::attach) }, modifier = Modifier.fillMaxSize()
                                .onGloballyPositioned { coordinates ->
                                    val rectangle = coordinates.localToWindow(Offset.Zero)
                                    pictureInPicture?.videoBounds(android.graphics.Rect(rectangle.x.roundToInt(), rectangle.y.roundToInt(),
                                        (rectangle.x + coordinates.size.width).roundToInt(), (rectangle.y + coordinates.size.height).roundToInt()))
                                })
                            if (!fullscreen) {
                                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(16.dp)
                                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .38f), Color.Transparent))))
                                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(16.dp)
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .38f)))))
                            }
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
                                        gestureMessage = null; gestureMeter = true to brightness
                                    } else if (!audioManager.isVolumeFixed) {
                                        changeVolume(next * maximumVolume)
                                        gestureMessage = null; gestureMeter = false to (mediaVolume.toFloat() / maximumVolume)
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
                                                gestureMeter = null
                                                gestureMessage = (if (delta < 0) "−" else "+") + "10 s"
                                            }
                                        }
                                    },
                                    onLongPress = {},
                                    onPress = {
                                        if (!exploration && !touchLocked && model.state.value.selected == file && liveState.playing && !model.state.value.busy) coroutineScope {
                                            val pressRevision = gestureRevision
                                            var temporaryEpoch: Long? = null
                                            val hold = launch {
                                                delay(viewConfiguration.longPressTimeoutMillis)
                                                if (pressRevision == gestureRevision && model.state.value.selected == file && liveState.playing && !model.state.value.busy &&
                                                    !touchLocked && sheet == null && gestureForeground && !exploration) {
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
                            val feedbackSeek = seek?.toLong() ?: pictureSeek
                            val feedbackMeter = gestureMeter
                            val feedbackMessage = gestureMessage
                            if (!pipMode) when {
                                feedbackSeek != null -> PictureSeekFeedback(feedbackSeek,
                                    if (seek != null) timelineStart else dragSession?.startMs ?: state.positionMs,
                                    state.durationMs, seekPreview, Modifier.align(Alignment.Center))
                                holding -> PlayerActionFeedback("${holdRate}× 倍速播放", R.drawable.ic_fast, Modifier.align(Alignment.Center))
                                feedbackMeter != null -> PlayerLevelFeedback(feedbackMeter.first, feedbackMeter.second, Modifier.align(Alignment.Center))
                                feedbackMessage != null -> PlayerActionFeedback(feedbackMessage,
                                    when { feedbackMessage.startsWith("−") -> R.drawable.ic_skip_back; feedbackMessage.startsWith("+") -> R.drawable.ic_skip_forward; else -> R.drawable.art_volume },
                                    Modifier.align(Alignment.Center))
                            }
                            val failure = if (client.busy) null else client.error ?: state.error
                            if (failure != null && pipMode) {
                                Text("播放中断", color = Color.White, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .7f), RoundedCornerShape(8.dp)).padding(8.dp))
                            } else if (failure != null) {
                                Column(Modifier.align(Alignment.Center).widthIn(max = 360.dp).padding(20.dp)
                                    .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .68f)).padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(failure, style = MaterialTheme.typography.bodyMedium, color = FilewayTheme.status.mediaContent)
                                    if (fullscreen) FilewaySessionNotice(model)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        PlayerLabel("重试", "重试播放") { model.retryPlayback() }
                                    }
                                }
                            } else if (client.busy && showRequest || !client.busy && state.waitingForBuffer && showBuffering) {
                                // The ring stays at the picture's centre across request/buffer phases.
                                // Cancellation is a separate target and never offsets that centre.
                                Box(Modifier.align(Alignment.Center).size(width = 144.dp, height = 56.dp), contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(48.dp).background(Color.Black.copy(alpha = .35f), CircleShape)
                                        .semantics { contentDescription = if (client.busy) "正在加载视频" else "正在缓冲" },
                                        contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(Modifier.size(28.dp), color = FilewayTheme.status.mediaContent, strokeWidth = 2.dp)
                                    }
                                    if (client.busy && !pipMode) FilewayIconButton(model::cancel, Modifier.align(Alignment.CenterEnd).size(48.dp).background(Color.Black.copy(alpha = .35f), CircleShape)) {
                                        FilewayIcon(R.drawable.ic_close, "取消播放请求", Modifier.size(20.dp), tint = FilewayTheme.status.mediaContent)
                                    }
                                }
                            }
                            if (!pipMode && (showControls || touchLocked)) Box(Modifier.align(AbsoluteAlignment.CenterLeft)
                                .then(if (fullscreen) Modifier.windowInsetsPadding(controlInsets.only(WindowInsetsSides.Left)) else Modifier)
                                .absolutePadding(left = if (fullscreen) 4.dp else 8.dp)) {
                                // Retain this anchor while locked, including after a real
                                // window rotation. The topmost unlock uses these same bounds.
                                PlayerLockIcon(R.drawable.ic_lock_open, "锁定触控", "触控未锁定", false, ::lockTouch,
                                    enabled = !exploration,
                                    modifier = Modifier.alpha(if (touchLocked) 0f else 1f)
                                        .onGloballyPositioned { touchLockPosition = it.positionInRoot() })
                            }
                        }
                        androidx.compose.animation.AnimatedVisibility(showControls && fullscreen,
                            enter = fadeIn(tween(140)), exit = fadeOut(tween(if (!fullscreen || sheet != null || touchLocked) 0 else 140)),
                            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                            .windowInsetsPadding(controlInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                            Row(Modifier.fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                val deviceTime = rememberPlayerClock()
                                PlayerIcon(R.drawable.ic_arrow_back, "退出全屏并返回详情", { changeDisplay(PlayerDisplayMode.AUTOMATIC) })
                                Text(file.name, style = FilewayTheme.text.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                                networkStatus?.let { PlayerNetworkStatus(it) }
                                Text(deviceTime, color = Color.White, fontSize = 12.sp, lineHeight = 16.sp,
                                    maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp).clearAndSetSemantics {
                                        contentDescription = "设备时间"
                                        stateDescription = deviceTime
                                    })
                                PlayerIcon(R.drawable.ic_info, "播放信息", { touch(); sheet = PlayerSheet.SOURCE })
                            }
                        }
                        }
                        androidx.compose.animation.AnimatedVisibility(!fullscreen || showControls,
                            enter = fadeIn(tween(140)), exit = fadeOut(tween(if (sheet != null || touchLocked) 0 else 140)),
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        MaterialTheme(colorScheme = if (fullscreen) MaterialTheme.colorScheme else pageColors) {
                        CompositionLocalProvider(LocalContentColor provides if (fullscreen) FilewayTheme.status.mediaContent else pageColors.onBackground) {
                        Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(if (fullscreen) listOf(Color.Transparent, Color(0xE6000000)) else listOf(Color.Transparent, Color.Transparent)))
                            .then(if (fullscreen) Modifier.windowInsetsPadding(controlInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)) else Modifier)
                            .padding(start = controlPadding, end = controlPadding, bottom = if (fullscreen) 8.dp else 0.dp)
                            .alpha(if (!fullscreen && touchLocked) .55f else 1f)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(clock((seek ?: pictureSeek?.toFloat() ?: state.positionMs.toFloat()).toLong()), color = LocalContentColor.current.copy(alpha = .72f), fontSize = 12.sp, lineHeight = 16.sp,
                                    fontFamily = FontFamily.Monospace, maxLines = 1,
                                    modifier = Modifier.offset(y = if (fullscreen) 4.dp else 0.dp))
                                Box(Modifier.weight(1f)) {
                                PlayerSlider(seek ?: pictureSeek?.toFloat() ?: state.positionMs.toFloat().coerceIn(0f, state.durationMs.toFloat().coerceAtLeast(1f)), state.durationMs.toFloat().coerceAtLeast(1f),
                                    "播放进度", controlsEnabled && state.seekable && state.durationMs > 0,
                                    { if (seek == null) timelineStart = state.positionMs; gestureSeek.reset(); seek = it; touch() }, { seek?.let { model.player.seek(it.toLong()) }; seek = null; touch() },
                                    clock((seek ?: pictureSeek?.toFloat() ?: state.positionMs.toFloat()).toLong()) + "，共 " + clock(state.durationMs),
                                    downloadedValue = downloadedUntil?.toFloat(), bufferedValue = state.bufferedPositionMs.toFloat(), mediaSurface = fullscreen,
                                    visualTrackOffset = if (fullscreen) 4.dp else 0.dp, modifier = Modifier.fillMaxWidth())
                                }
                                Text(if (state.durationMs > 0) clock(state.durationMs) else "--:--", color = LocalContentColor.current.copy(alpha = .72f), fontSize = 12.sp, lineHeight = 16.sp,
                                    fontFamily = FontFamily.Monospace, maxLines = 1,
                                    modifier = Modifier.offset(y = if (fullscreen) 4.dp else 0.dp))
                            }
                            download?.let { item ->
                                val percent = if (item.expectedSize > 0) (downloadedBytes.toDouble() * 100 / item.expectedSize).toInt().coerceIn(0, 100) else 0
                                val coverage = if (item.complete) "已全部下载" else downloadedUntil?.let { "可离线播放至 ${clock(it)}" } ?: "可播放范围暂未知"
                                Row(Modifier.fillMaxWidth().heightIn(min = downloadCoverageHeight).padding(horizontal = 8.dp).semantics {
                                    contentDescription = "文件下载进度"
                                    stateDescription = "已下载 $percent%，$coverage"
                                    progressBarRangeInfo = ProgressBarRangeInfo(percent / 100f, 0f..1f)
                                }, verticalAlignment = Alignment.CenterVertically) {
                                    Text("已下载 $percent%", color = LocalContentColor.current.copy(alpha = .72f), fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                    Text(coverage, color = LocalContentColor.current.copy(alpha = .72f), fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1)
                                }
                            }
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val transport: @Composable RowScope.() -> Unit = {
                                    PlayerIcon(if (state.playing) R.drawable.art_pause else R.drawable.art_play, if (state.playing) "暂停播放" else "开始播放", { touch(); model.togglePlayback() }, controlsEnabled)
                                    PlayerIcon(R.drawable.ic_skip_next, "下一个视频", { touch(); model.nextMedia() }, controlsEnabled && queue?.hasNext == true)
                                }
                                val options: @Composable RowScope.() -> Unit = {
                                    val audioTitle = state.audio.firstOrNull { it.id == state.selectedAudio }?.title ?: "暂无音轨"
                                    val subtitleTitle = state.subtitles.firstOrNull { it.id == state.selectedSubtitle }?.title ?: "关闭"
                                    PlayerIcon(R.drawable.ic_audio, "选择音轨", { touch(); sheet = PlayerSheet.AUDIO }, controlsEnabled && state.audio.isNotEmpty(),
                                        description = (if (state.pendingAudio != null) "正在切换，当前音轨：" else "当前音轨：") + audioTitle)
                                    PlayerIcon(R.drawable.ic_subtitles, "选择字幕", { touch(); sheet = PlayerSheet.SUBTITLE }, controlsEnabled,
                                        description = (if (state.pendingSubtitle != null) "正在切换，当前字幕：" else "当前字幕：") + subtitleTitle)
                                    PlayerLabel("${state.rate}×", "播放速度", controlsEnabled) { touch(); sheet = PlayerSheet.SPEED }
                                    PlayerIcon(R.drawable.ic_playlist_play, "播放列表", { touch(); sheet = PlayerSheet.QUEUE }, controlsEnabled && queue != null,
                                        description = queue?.let { "第 ${it.index + 1} 项，共 ${it.items.size} 项" } ?: "无播放列表")
                                    PlayerIcon(R.drawable.ic_settings, "视频解码器", { touch(); sheet = PlayerSheet.DECODER }, controlsEnabled && !changingDecoder)
                                }
                                val display: @Composable RowScope.() -> Unit = {
                                    if (pictureInPicture?.supported == true) PlayerIcon(R.drawable.ic_pip, "画中画", {
                                        cancelGesture(); sheet = null
                                        if (!pictureInPicture.enter()) uiScope.launch { feedback.showSnackbar("暂时无法进入画中画，请检查系统画中画权限") }
                                    }, controlsEnabled && state.firstFrameRendered)
                                    if (!fullscreen) PlayerIcon(R.drawable.ic_portrait_fullscreen, "竖屏全屏", { changeDisplay(PlayerDisplayMode.PORTRAIT_FULLSCREEN) }, !touchLocked)
                                    PlayerIcon(if (fullscreen) R.drawable.art_fullscreen_off else R.drawable.art_fullscreen_on, if (fullscreen) "退出全屏" else "横屏全屏", {
                                        changeDisplay(if (fullscreen) PlayerDisplayMode.AUTOMATIC else PlayerDisplayMode.LANDSCAPE_FULLSCREEN)
                                    }, !touchLocked)
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
                        }
                        }
                    }
                    }
                    if (!fullscreen) LazyColumn(Modifier.weight(1f).semantics { contentDescription = "播放详情" }
                        .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item(key = "video-information") { PlayerInformation(model, file, client.previewScope, state) }
                        if (queue?.hasNext != true) item(key = "track-information") {
                            PlayerTrackInformation(state, controlsEnabled,
                                audio = { touch(); sheet = PlayerSheet.AUDIO }, subtitles = { touch(); sheet = PlayerSheet.SUBTITLE })
                        }
                        queue?.takeIf { it.hasNext }?.let { snapshot ->
                            val upcoming = snapshot.items.drop(snapshot.index + 1)
                            item(key = "upcoming-header") { Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("接下来", style = MaterialTheme.typography.titleSmall)
                                Text("${upcoming.size} 项", style = FilewayTheme.text.metadata, color = pageColors.onSurfaceVariant)
                            }
                            }
                            itemsIndexed(upcoming, key = { _, next -> next.mediaKey }) { offset, next ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(12.dp))
                                    .background(pageColors.surface).clickable(enabled = !client.busy && !touchLocked, role = Role.Button) {
                                    model.navigateMedia(snapshot.index + offset + 1); touch()
                                }.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    MediaThumbnail(model, next, Modifier.width(80.dp).aspectRatio(16f / 9f), showStatusText = false)
                                    Text(next.name, Modifier.weight(1f), style = FilewayTheme.text.fileName,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                if (sheet == null) SnackbarHost(feedback, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp)
                    .then(if (touchLocked) Modifier.clearAndSetSemantics {} else Modifier))
                if (touchLocked && !pipMode) {
                    // The topmost input layer covers controls as well as the picture. System Back unlocks first.
                    Box(Modifier.fillMaxSize()
                        .clearAndSetSemantics {
                            contentDescription = "触控已锁定"
                            onClick("显示或隐藏解锁按钮") { toggleUnlock(); true }
                        }
                        .pointerInput(Unit) { detectTapGestures(onTap = { toggleUnlock() }) })
                    androidx.compose.animation.AnimatedVisibility(unlockVisible, enter = fadeIn(tween(140)), exit = fadeOut(tween(140)),
                        modifier = Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset {
                            val position = touchLockPosition - playerPosition
                            IntOffset(position.x.roundToInt(), position.y.roundToInt())
                        }) {
                        PlayerLockIcon(R.drawable.ic_lock, "解除触控锁定", "触控已锁定", true, ::unlockTouch,
                            enabled = unlockVisible)
                    }
                }
                if (sheet != null && !pipMode) io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaTheme(
                    pageColors, fullscreen) { PlayerPanel(landscape, fullscreen && !exploration, when (sheet) { PlayerSheet.AUDIO -> "音轨"; PlayerSheet.SUBTITLE -> "字幕"; PlayerSheet.SUBTITLE_ADJUST -> "字幕调整"; PlayerSheet.SUBTITLE_FONT -> "字幕字体"; PlayerSheet.SPEED -> "播放速度"; PlayerSheet.SPEED_PRESETS -> "倍速预设"; PlayerSheet.SPEED_EDIT -> if (editingHoldRate) "长按倍速" else "自定义倍速"; PlayerSheet.EXTERNAL -> "外挂字幕"; PlayerSheet.QUEUE -> "播放列表"; PlayerSheet.DECODER -> "视频解码器"; else -> "播放信息" }, { sheet = null; touch() }, back = if (sheet == PlayerSheet.SUBTITLE_ADJUST) {
                    { sheet = PlayerSheet.SUBTITLE; touch() }
                } else if (sheet == PlayerSheet.SUBTITLE_FONT) {
                    { sheet = PlayerSheet.SUBTITLE_ADJUST; touch() }
                } else if (sheet == PlayerSheet.SPEED_EDIT || sheet == PlayerSheet.SPEED_PRESETS) {
                    { sheet = PlayerSheet.SPEED; speedError = null; touch() }
                } else null, feedback = feedback, backLabel = when (sheet) { PlayerSheet.SPEED_EDIT, PlayerSheet.SPEED_PRESETS -> "返回播放速度"; PlayerSheet.SUBTITLE_FONT -> "返回字幕调整"; else -> "返回字幕轨道" },
                    headerDetail = if (sheet == PlayerSheet.QUEUE) queue?.let { "${it.index + 1} / ${it.items.size}" } else null,
                    feedbackBottomInset = if (sheet == PlayerSheet.SUBTITLE_ADJUST) with(LocalDensity.current) { subtitleFooterHeight.toDp() } else 0.dp) {
                    when (sheet) {
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
                                header = if (audio) null else { {
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
                                val delay = io.github.kkwans.nasfilebrowser.data.parseSubtitleDelayMillis(subtitleOffset)
                                if (delay == null) subtitleOffsetError = "请输入 -600 到 600 秒"
                                else { model.player.subtitleDelay(delay); subtitleOffsetError = null }
                            },
                            appearance = subtitlePreview,
                            onAppearance = { subtitlePreview = it },
                            onChooseFont = { sheet = PlayerSheet.SUBTITLE_FONT; touch() },
                            canSave = subtitlePreview != savedSubtitleAppearance,
                            saving = savingSubtitle,
                            onFooterHeight = { subtitleFooterHeight = it },
                            onSaveAppearance = {
                                if (!savingSubtitle) {
                                    savingSubtitle = true
                                    val value = subtitlePreview
                                    uiScope.launch {
                                        val success = try { model.playbackPreferences.saveTextSubtitleAppearance(value); true }
                                            catch (cancelled: CancellationException) { throw cancelled }
                                            catch (_: Exception) { false }
                                            finally { savingSubtitle = false }
                                        feedback.showSnackbar(if (success) "字幕外观已保存到本机" else "字幕外观保存失败，请重试")
                                    }
                                }
                            },
                        )
                        PlayerSheet.SUBTITLE_FONT -> PlayerSubtitleFontPicker(subtitlePreview) {
                            subtitlePreview = it; sheet = PlayerSheet.SUBTITLE_ADJUST; touch()
                        }
                        PlayerSheet.EXTERNAL -> NasSubtitlePicker(model, file, chosen = { sheet = PlayerSheet.SUBTITLE; touch() })
                        PlayerSheet.SPEED -> PlayerSpeedHome(
                            rate = state.rate, holdRate = holdRate, presets = ratePresets,
                            onPreset = { model.player.rate(it); sheet = null; touch() },
                            onEditPresets = {
                                speedEditRevision += 1
                                presetError = null
                                sheet = PlayerSheet.SPEED_PRESETS
                                touch()
                            },
                            onEdit = { hold ->
                                speedEditRevision += 1
                                editingHoldRate = hold
                                customRate = (if (hold) holdRate else state.rate).toString()
                                speedError = null
                                sheet = PlayerSheet.SPEED_EDIT
                                touch()
                            },
                        )
                        PlayerSheet.SPEED_PRESETS -> PlayerSpeedPresetsEditor(
                            presets = ratePresets, saving = savingPresets, saveError = presetError,
                            onSave = { values ->
                                if (!savingPresets) {
                                    savingPresets = true
                                    presetError = null
                                    val editRevision = speedEditRevision
                                    uiScope.launch {
                                        try {
                                            model.playbackPreferences.saveRatePresets(values)
                                            if (sheet == PlayerSheet.SPEED_PRESETS && speedEditRevision == editRevision) sheet = PlayerSheet.SPEED
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) {
                                            if (sheet == PlayerSheet.SPEED_PRESETS && speedEditRevision == editRevision) presetError = "倍速预设未能保存，请重试"
                                            else feedback.showSnackbar("倍速预设未能保存，请重试")
                                        } finally { savingPresets = false }
                                    }
                                }
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
                        PlayerSheet.DECODER -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("当前解码器", style = FilewayTheme.text.metadata, color = FilewayTheme.status.mediaSecondary)
                                    SelectionContainer {
                                        Text(state.videoDecoder.ifBlank { "引擎尚未报告" }, style = MaterialTheme.typography.bodyMedium,
                                            fontFamily = FontFamily.Monospace)
                                    }
                                }
                                if (state.videoDecoderKind.isNotBlank()) Text(state.videoDecoderKind,
                                    Modifier.clip(MaterialTheme.shapes.medium).background(FilewayTheme.colors.surfaceContainerHigh)
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    style = FilewayTheme.text.metadata, color = FilewayTheme.status.mediaContent)
                            }
                            VideoDecodePolicy.entries.forEach { policy ->
                                Choice(policy.label, when (policy) {
                                    VideoDecodePolicy.AUTO -> "解码故障时尝试可用的软件解码器"
                                    VideoDecodePolicy.HARDWARE -> "不自动回退到软件解码"
                                    VideoDecodePolicy.SOFTWARE -> "使用设备提供的软件视频解码器"
                                }, state.videoDecodePolicy == policy, enabled = !changingDecoder && !client.busy) {
                                    if (state.videoDecodePolicy != policy) {
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
                            Text(if (changingDecoder) "正在切换解码器…" else "切换将保留进度并重新打开视频，后续视频沿用此策略。",
                                Modifier.padding(horizontal = 24.dp), style = FilewayTheme.text.metadata, color = FilewayTheme.status.mediaSecondary)
                        }
                        PlayerSheet.SOURCE -> LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { SourceField("文件", file.name) }
                            item { SourceField("读取来源", playbackSourceLabel) }
                            item { SourceField("路径", file.path) }
                            if (file.downloadId.isEmpty() && !model.isTemporaryContent(file)) item {
                                FilewayOutlinedButton({ sheet = null; model.openContainingDirectory(file) }, Modifier.fillMaxWidth(),
                                    enabled = client.connected && !client.busy) {
                                    FilewayIcon(R.drawable.ic_folder, null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp)); Text("打开所在目录")
                                }
                            }
                            item { SourceField("播放方式", "原生播放 · Media3") }
                            item { SourceField("画质", "原画 · 直接读取原文件") }
                            if (state.width > 0) item { SourceField("源分辨率", "${state.width} × ${state.height}") }
                            item { SourceField("视频编码", state.sourceVideoCodec) }
                            item { SourceField("实际视频解码方式", state.videoDecoderKind) }
                            item { SourceField("实际视频解码器", state.videoDecoder) }
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
    }
}


@Composable private fun PlayerLockIcon(icon: Int, label: String, state: String, locked: Boolean, click: () -> Unit,
    enabled: Boolean = true, modifier: Modifier = Modifier) {
    FilewayIconButton(onClick = click, enabled = enabled, modifier = modifier.size(48.dp)
        .background(Color.Black.copy(alpha = .28f), CircleShape).clearAndSetSemantics {
            contentDescription = label
            stateDescription = state
            selected = locked
            role = Role.Button
            if (enabled) onClick { click(); true } else disabled()
        }) {
        FilewayIcon(icon, null, Modifier.size(22.dp),
            tint = if (!enabled) Color.White.copy(alpha = .38f) else if (locked) FilewayTheme.colors.primary else Color.White.copy(alpha = .94f))
    }
}

@Composable private fun PlayerIcon(icon: Int, label: String, click: () -> Unit, enabled: Boolean = true, description: String? = null) {
    FilewayIconButton(onClick = click, enabled = enabled, modifier = Modifier.size(48.dp).clearAndSetSemantics {
        contentDescription = label
        description?.let { stateDescription = it }
        role = Role.Button
        if (enabled) onClick { click(); true } else disabled()
    }) {
        FilewayIcon(icon, null, Modifier.size(22.dp), tint = LocalContentColor.current.copy(alpha = if (enabled) 0.94f else 0.38f))
    }
}
@Composable private fun PlayerLabel(text: String, label: String, enabled: Boolean = true, click: () -> Unit) {
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Button, onClick = click).clearAndSetSemantics {
        contentDescription = label
        stateDescription = text
        role = Role.Button
        if (enabled) onClick { click(); true } else disabled()
    }.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = LocalContentColor.current.copy(alpha = if (enabled) 0.94f else 0.38f))
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PlayerSlider(value: Float, maximum: Float, label: String, enabled: Boolean, change: (Float) -> Unit, finish: () -> Unit = {}, description: String = "${value.toInt()}%", downloadedValue: Float? = null, modifier: Modifier = Modifier, bufferedValue: Float? = null, mediaSurface: Boolean = true, minimum: Float = 0f, visualTrackOffset: Dp = 0.dp) {
    // Preserve Material's drag and keyboard handling. Export one named range
    // with meaningful time/volume state, rather than split label/range nodes
    // and the default raw millisecond number. Accessibility uses the same
    // change/finish callbacks as direct manipulation.
    val accent = FilewayTheme.colors.primary
    val secondary = if (mediaSurface) FilewayTheme.status.mediaSecondary else MaterialTheme.colorScheme.onSurfaceVariant
    val contentColor = if (mediaSurface) FilewayTheme.status.mediaContent else MaterialTheme.colorScheme.onSurface
    val span = maximum - minimum
    Box(modifier.fillMaxWidth().clearAndSetSemantics {
        contentDescription = label
        stateDescription = description
        progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(minimum, maximum), minimum..maximum)
        if (!enabled) disabled()
        setProgress { target ->
            val next = target.coerceIn(minimum, maximum)
            if (!enabled || !next.isFinite() || next == value) false else { change(next); finish(); true }
        }
    }) {
        Slider(value = value.coerceIn(minimum, maximum), onValueChange = change, onValueChangeFinished = finish, valueRange = minimum..maximum, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        // Equal slot heights give track and thumb the same vertical origin;
        // the visible 3dp line and 10dp circle are drawn around that centre.
        thumb = { Canvas(Modifier.width(10.dp).height(48.dp)) {
            drawCircle(if (enabled) accent else secondary, radius = 5.dp.toPx(), center = center + Offset(0f, visualTrackOffset.toPx()))
        } },
        track = { Canvas(Modifier.fillMaxWidth().height(48.dp)) {
            val y = size.height / 2 + visualTrackOffset.toPx()
            drawLine(contentColor.copy(alpha = 0.24f), Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), StrokeCap.Round)
            downloadedValue?.let { available ->
                drawLine(accent.copy(alpha = .35f), Offset(0f, y), Offset(size.width * ((available - minimum) / span).coerceIn(0f, 1f), y), 3.dp.toPx(), StrokeCap.Round)
            }
            bufferedValue?.takeIf { it.isFinite() }?.let { buffered ->
                drawLine(secondary.copy(alpha = .8f), Offset(0f, y), Offset(size.width * ((buffered - minimum) / span).coerceIn(0f, 1f), y), 3.dp.toPx(), StrokeCap.Round)
            }
            drawLine(if (enabled) accent else secondary, Offset(0f, y), Offset(size.width * ((value - minimum) / span).coerceIn(0f, 1f), y), 3.dp.toPx(), StrokeCap.Round)
        } })
    }
}
@Composable private fun PlayerNetworkStatus(value: String) {
    Text(value, Modifier.padding(horizontal = 8.dp).semantics { contentDescription = "实际网络读取速度 $value" },
        style = FilewayTheme.text.metadata, fontFamily = FontFamily.Monospace,
        color = FilewayTheme.status.mediaSecondary, maxLines = 1)
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PlayerPanel(landscape: Boolean, immersive: Boolean, title: String, dismiss: () -> Unit, back: (() -> Unit)? = null, feedback: SnackbarHostState, backLabel: String = "返回字幕轨道", headerDetail: String? = null, feedbackBottomInset: Dp = 0.dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val viewport = LocalWindowInfo.current.containerSize
    val configuration = LocalConfiguration.current
    // Read the activity's insets before entering the bounded dialog. Insets from
    // a window already moved above the IME would make its position oscillate.
    val panelInsets = if (immersive)
        WindowInsets.displayCutout.union(WindowInsets.navigationBarsIgnoringVisibility).union(WindowInsets.captionBar)
        else WindowInsets.safeDrawing
    val left = panelInsets.getLeft(density, direction)
    val right = panelInsets.getRight(density, direction)
    val top = panelInsets.getTop(density)
    val bottom = panelInsets.getBottom(density)
    val imeBottom = WindowInsets.ime.getBottom(density)
    val viewportWidth = viewport.width.takeIf { it > 0 } ?: with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val viewportHeight = viewport.height.takeIf { it > 0 } ?: with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val panelWidth = (viewportWidth - left - right).coerceAtLeast(1).let {
        if (landscape) minOf(it, with(density) { 360.dp.roundToPx() }) else it
    }
    val availableHeight = (viewportHeight - top - imeBottom).coerceAtLeast(1)
    val panelHeight = if (landscape || imeBottom > 0 || back != null) availableHeight else (availableHeight * .85f).roundToInt()
    // A floating native window is required for region-bounded background blur.
    // The material helper then explicitly applies edge-to-edge and panel gravity.
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(dismissOnBackPress = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
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
        val panelBlurred = rememberPlayerPanelBlur(panelWindow, landscape, panelWidth, panelHeight, right, top)
        PlayerWindowBars(panelWindow, immersive, FilewayTheme.status.mediaSurface.luminance() < .5f)
        SideEffect {
            @Suppress("DEPRECATION")
            panelWindow?.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= 29) panelWindow?.isNavigationBarContrastEnforced = false
        }
        Box(Modifier.requiredWidth(with(density) { panelWidth.toDp() })
            .heightIn(max = with(density) { panelHeight.toDp() })
            .semantics { contentDescription = "播放设置" }) {
            val panel = Modifier.fillMaxWidth().then(if (landscape) Modifier.fillMaxHeight() else Modifier)
            val panelShape = if (landscape) RoundedCornerShape(12.dp) else RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
            io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayPlayerPanelTheme(panelBlurred) {
            CompositionLocalProvider(LocalContentColor provides FilewayTheme.status.mediaContent) {
            Column(panel.clip(panelShape).background(FilewayTheme.status.mediaSurface)
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .18f), Color.White.copy(alpha = .04f))), panelShape)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                .padding(bottom = with(density) { (if (imeBottom > 0) 0 else bottom).toDp() }), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = if (back != null) 4.dp else 24.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (back != null) PlayerIcon(R.drawable.ic_arrow_back, backLabel, back)
                    Text(title, style = FilewayTheme.text.appBar, modifier = Modifier.weight(1f))
                    headerDetail?.let { Text(it, Modifier.padding(horizontal = 8.dp), style = FilewayTheme.text.metadata,
                        fontFamily = FontFamily.Monospace, color = FilewayTheme.status.mediaSecondary, maxLines = 1) }
                    PlayerIcon(R.drawable.art_close, "关闭播放设置", dismiss)
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                Box(Modifier.weight(1f, fill = false).fillMaxWidth()) { content() }
            }
            }
            }
            // The active dialog owns feedback while open; keep the same state
            // so failed track requests remain visible and accessible above it.
            SnackbarHost(feedback, Modifier.align(if (landscape) Alignment.BottomEnd else Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = with(density) { (if (imeBottom > 0) 0 else bottom).toDp() })
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = feedbackBottomInset + 16.dp))
        }
    }
}
@Composable private fun Choice(title: String, detail: String, selected: Boolean, enabled: Boolean = true, choose: () -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) FilewayTheme.colors.primary.copy(alpha = 0.10f) else Color.Transparent)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = choose).alpha(if (enabled) 1f else .38f).heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, color = if (selected) FilewayTheme.colors.primary else FilewayTheme.status.mediaContent)
            if (detail.isNotBlank()) Text(detail, style = FilewayTheme.text.metadata, color = FilewayTheme.status.mediaSecondary)
        }
        Box(Modifier.size(20.dp)) {
            if (selected) FilewayIcon(R.drawable.art_check, null, Modifier.size(20.dp), tint = FilewayTheme.colors.primary)
        }
    }
}
@Composable private fun SourceField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = FilewayTheme.text.metadata, color = FilewayTheme.status.mediaSecondary)
        SelectionContainer { Text(value.ifBlank { "尚未提供" }, fontSize = 15.sp, lineHeight = 22.sp) }
    }
}

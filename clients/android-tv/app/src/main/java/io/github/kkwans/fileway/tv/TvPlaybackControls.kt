package io.github.kkwans.fileway.tv

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.tv.material3.*
import io.github.kkwans.fileway.design.R as Art
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayPalette
import io.github.kkwans.nasfilebrowser.ui.designsystem.drawMediaProgressTrack

private val PlaybackScrim = Color.Black
private val PlaybackFocus = TvPlayerDesign.raised

/** The phone player's seeker and action groups, adapted to TV focus. */
@Composable internal fun TvPlaybackControls(
    playing: Boolean, enabled: Boolean, seekable: Boolean, audio: Boolean,
    rate: Float, previous: Boolean, next: Boolean, position: Long, duration: Long,
    buffered: Long, cachedRanges: List<TvCachedRange>, playFocus: FocusRequester, timeline: FocusRequester,
    actions: Map<TvPlayerPage, FocusRequester>, onToggle: () -> Unit, onSeek: (Long) -> Unit,
    onSeekTo: (Long) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onQuick: (TvPlayerPage) -> Unit, onQuickAnchor: (TvPlayerPage, Rect) -> Unit,
    onMenu: (FocusRequester) -> Unit, onSettings: () -> Unit, activeQuick: TvPlayerPage?, interactive: Boolean,
    seekStepSeconds: Int, safePercent: Int, modifier: Modifier = Modifier,
) {
    val previousFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val settingsFocus = actions.getValue(TvPlayerPage.SETTINGS)
    val queueFocus = actions.getValue(TvPlayerPage.QUEUE)
    val speedFocus = actions.getValue(TvPlayerPage.SPEED)
    val subtitleFocus = actions.getValue(TvPlayerPage.SUBTITLE)
    val audioFocus = actions.getValue(TvPlayerPage.AUDIO)
    val hasPrevious = previous && enabled
    val hasNext = next && enabled
    var returnFromTimeline by remember { mutableStateOf<FocusRequester?>(null) }
    fun actionFocus(requester: FocusRequester, leftTarget: FocusRequester, rightTarget: FocusRequester): Modifier = Modifier
        .focusRequester(requester)
        .focusProperties {
            if (seekable) up = timeline
            left = leftTarget
            right = rightTarget
        }
        .onPreviewKeyEvent { event ->
            when {
                seekable && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                    returnFromTimeline = requester
                    val success = timeline.requestFocus()
                    if (BuildConfig.DEBUG) android.util.Log.d("FilewayTvFocus", "hud-up play=${requester === playFocus} requested=$success")
                    success
                }
                event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.nativeKeyEvent.repeatCount == 0) onMenu(requester)
                    true
                }
                else -> false
            }
        }
    BoxWithConstraints(modifier.fillMaxWidth().then(if (!interactive) Modifier.clearAndSetSemantics { } else Modifier)) {
        val inset = tvPlayerSafeInset(maxWidth, safePercent, 16.dp)
        val bottom = tvPlayerSafeInset(maxHeight, safePercent, 12.dp)
        val controlsWidth = maxOf(maxWidth - inset * 2, 432.dp)
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(
                    0f to Color.Transparent, .40f to PlaybackScrim.copy(alpha = .80f),
                    1f to PlaybackScrim.copy(alpha = .92f),
                ))
                .padding(start = inset, end = inset, top = 22.dp, bottom = bottom),
        ) {
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(), enabled = interactive)) {
                Box(Modifier.width(controlsWidth)) {
                    TvPlayerMainFrame(
                        seeker = {
                            Box(Modifier.fillMaxWidth().then(if (activeQuick != null)
                                Modifier.alpha(0f).clearAndSetSemantics { } else Modifier)) {
                                TvTimeline(position, duration, buffered, onSeek, Modifier.fillMaxWidth(), cachedRanges,
                                    onSeekTo, seekStepSeconds, seekable && interactive, Modifier.focusRequester(timeline)
                                        .focusProperties { down = returnFromTimeline ?: playFocus }
                                        .onPreviewKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown &&
                                                event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                                                val target = returnFromTimeline ?: playFocus
                                                val success = target.requestFocus()
                                                if (BuildConfig.DEBUG) android.util.Log.d("FilewayTvFocus", "timeline-down play=${target === playFocus} requested=$success")
                                                success
                                            } else false
                                        })
                            }
                        },
                        leftActions = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup()) {
                                TvHudAction("设置", Art.drawable.fileway_ic_settings, onSettings,
                                    actionFocus(settingsFocus, FocusRequester.Cancel, queueFocus), interactive)
                                TvHudAction("列表", Art.drawable.fileway_ic_playlist_play, { onQuick(TvPlayerPage.QUEUE) },
                                    actionFocus(queueFocus, settingsFocus, if (hasPrevious) previousFocus else if (enabled) playFocus else speedFocus)
                                        .onGloballyPositioned { onQuickAnchor(TvPlayerPage.QUEUE, it.boundsInWindow()) },
                                    interactive, active = activeQuick == TvPlayerPage.QUEUE)
                            }
                        },
                        transport = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup()) {
                                TvHudAction("上一项", Art.drawable.fileway_ic_skip_previous, onPrevious,
                                    actionFocus(previousFocus, queueFocus, playFocus), interactive, enabled = hasPrevious)
                                TvHudAction(if (playing) "暂停" else "播放",
                                    if (playing) Art.drawable.fileway_ic_pause else Art.drawable.fileway_ic_play_arrow,
                                    onToggle, actionFocus(playFocus, if (hasPrevious) previousFocus else queueFocus,
                                        if (hasNext) nextFocus else speedFocus), interactive, enabled = enabled, primary = true)
                                TvHudAction("下一项", Art.drawable.fileway_ic_skip_next, onNext,
                                    actionFocus(nextFocus, playFocus, speedFocus), interactive, enabled = hasNext)
                            }
                        },
                        rightActions = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup()) {
                                val pages = buildList {
                                    add(Triple(TvPlayerPage.SPEED, "倍速 ${rate}×", Art.drawable.fileway_ic_speed))
                                    if (!audio) add(Triple(TvPlayerPage.SUBTITLE, "字幕", Art.drawable.fileway_ic_subtitles))
                                    add(Triple(TvPlayerPage.AUDIO, "音轨", Art.drawable.fileway_ic_audio_track))
                                }
                                for ((page, label, icon) in pages) {
                                    val left = when (page) {
                                        TvPlayerPage.SPEED -> if (hasNext) nextFocus else if (enabled) playFocus else queueFocus
                                        TvPlayerPage.SUBTITLE -> speedFocus
                                        else -> if (audio) speedFocus else subtitleFocus
                                    }
                                    val right = when (page) {
                                        TvPlayerPage.SPEED -> if (audio) audioFocus else subtitleFocus
                                        TvPlayerPage.SUBTITLE -> audioFocus
                                        else -> FocusRequester.Cancel
                                    }
                                    TvHudAction(label, icon, { onQuick(page) }, actionFocus(actions.getValue(page), left, right)
                                        .onGloballyPositioned { onQuickAnchor(page, it.boundsInWindow()) },
                                        interactive, active = activeQuick == page)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable private fun TvHudAction(
    label: String, icon: Int, onClick: () -> Unit, modifier: Modifier, interactive: Boolean,
    enabled: Boolean = true, primary: Boolean = false, active: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val shape = if (primary) CircleShape else RoundedCornerShape(12.dp)
    val size = if (primary) 56.dp else 48.dp
    Column(modifier.width(56.dp).onFocusChanged {
        if (BuildConfig.DEBUG && it.hasFocus && !focused) android.util.Log.d("FilewayTvFocus", "hud-focus $label")
        focused = it.hasFocus
    },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Button(
                onClick = onClick, enabled = enabled && interactive, interactionSource = interaction,
                modifier = Modifier.size(size).focusProperties { canFocus = interactive && enabled }
                    .tvTouch(onClick, enabled && interactive, interaction)
                    .semantics { contentDescription = label },
                contentPadding = PaddingValues(0.dp),
                shape = ButtonDefaults.shape(shape),
                scale = ButtonDefaults.scale(focusedScale = 1f),
                border = ButtonDefaults.border(focusedBorder = if (interactive) Border(BorderStroke(2.dp, TvPlayerDesign.focus), shape = shape) else Border.None),
                colors = ButtonDefaults.colors(
                    containerColor = if (primary || active) Color.White.copy(alpha = .10f) else Color.Transparent, contentColor = Color.White,
                    focusedContainerColor = TvPlayerDesign.raised,
                    focusedContentColor = Color.White,
                    disabledContainerColor = if (active) TvPlayerDesign.raised else Color.Transparent,
                    disabledContentColor = if (enabled) TvPlayerDesign.secondary else TvPlayerDesign.muted,
                ),
            ) {
                Box(Modifier.fillMaxWidth().heightIn(min = size), contentAlignment = Alignment.Center) {
                    Icon(painterResource(icon), null, Modifier.size(if (primary) 28.dp else 24.dp))
                }
            }
        }
        Box(Modifier.height(24.dp).requiredWidth(120.dp), contentAlignment = Alignment.Center) {
            if (focused && interactive) Text(label, style = tvTextStyle(16, 24), color = TvPlayerDesign.text, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun tvPlayerSafeInset(dimension: Dp, safePercent: Int, minimum: Dp) =
    ((dimension.value * safePercent.coerceIn(0, 8) / 400).roundToInt() * 4).dp.coerceAtLeast(minimum)

@Composable internal fun TvPlaybackAction(
    label: String, icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    showLabel: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val size = if (showLabel) Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp) else Modifier.size(48.dp)
    Button(
        onClick = onClick, enabled = enabled, interactionSource = interaction,
        modifier = modifier.then(size).tvTouch(onClick, enabled, interaction).semantics { contentDescription = label },
        contentPadding = if (showLabel) PaddingValues(horizontal = 10.dp, vertical = 4.dp) else PaddingValues(0.dp),
        shape = ButtonDefaults.shape(TvShape), scale = ButtonDefaults.scale(focusedScale = 1f),
        border = ButtonDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, TvPlayerDesign.focus), shape = TvShape),
        ),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = PlaybackFocus.copy(alpha = .96f),
            focusedContentColor = Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color.White.copy(alpha = .34f),
        ),
    ) {
        Row(Modifier.heightIn(min = if (showLabel) 40.dp else 48.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, Modifier.size(24.dp))
            if (showLabel) {
                Spacer(Modifier.width(6.dp))
                Text(label, style = tvTextStyle(16, 22), maxLines = 1)
            }
        }
    }
}

/** Keep the existing compact image toolbar and its focused-label slot. */
@Composable internal fun TvImageAction(
    label: String, icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.width(if (LocalTvCompact.current) 48.dp else 56.dp).onFocusChanged { focused = it.hasFocus },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TvPlaybackAction(label, icon, onClick, enabled = enabled, showLabel = false)
        Text(
            if (focused) label else "", Modifier.requiredWidth(104.dp), style = tvTextStyle(14, 20),
            maxLines = 1, color = Color.White, textAlign = TextAlign.Center,
        )
    }
}

@Composable private fun TvTimeline(
    position: Long, duration: Long, buffered: Long, onSeekBy: (Long) -> Unit, modifier: Modifier,
    cachedRanges: List<TvCachedRange>, onSeekTo: (Long) -> Unit, seekStepSeconds: Int, enabled: Boolean,
    trackModifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var preview by remember(duration) { mutableStateOf<Long?>(null) }
    val seek by rememberUpdatedState(onSeekTo)
    val seekBy by rememberUpdatedState(onSeekBy)
    val shown = preview ?: position
    val timeStyle = tvTextStyle(18, 24).copy(fontFeatureSettings = "tnum", color = TvPlayerDesign.text)
    val measure = rememberTextMeasurer()
    val elapsed = tvTime(shown)
    val total = if (duration > 0) tvTime(duration) else "--:--"
    val widest = if (duration >= 3_600_000) "00:00:00" else "00:00"
    val timeWidth = with(LocalDensity.current) {
        maxOf(measure.measure(widest, timeStyle, maxLines = 1).size.width,
            measure.measure(total, timeStyle, maxLines = 1).size.width).toDp() + 2.dp
    }
    val ranges = remember(duration, cachedRanges) {
        if (duration > 0) cachedRanges.map { it.startMs.toFloat() / duration to it.endMs.toFloat() / duration }
        else emptyList()
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(elapsed, Modifier.widthIn(min = timeWidth), style = timeStyle, textAlign = TextAlign.Start, maxLines = 1)
        Canvas(trackModifier.weight(1f).height(48.dp).onFocusChanged { focused = it.isFocused }
            .pointerInput(duration, enabled) {
                if (duration > 0 && enabled) detectTapGestures { point ->
                    seek((duration * (point.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)).toLong())
                }
            }
            .pointerInput(duration, enabled) {
                if (duration > 0 && enabled) detectHorizontalDragGestures(
                    onDragStart = { point -> preview = (duration * (point.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)).toLong() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        preview = (duration * (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)).toLong()
                    },
                    onDragEnd = { preview?.let(seek); preview = null },
                    onDragCancel = { preview = null },
                )
            }
            .tvRemoteKeys { event ->
                val direction = when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> -1
                    KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                    else -> 0
                }
                if (direction != 0 && duration > 0 && enabled) seekBy(direction * tvSeekDelta(event.nativeKeyEvent, seekStepSeconds))
                direction != 0
            }.semantics {
                contentDescription = "播放进度"
                stateDescription = "$elapsed / $total"
                if (!enabled) disabled()
                progressBarRangeInfo = ProgressBarRangeInfo(
                    if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f, 0f..1f,
                )
                setProgress { value ->
                    if (duration > 0 && enabled && value.isFinite()) {
                        seek((duration * value.coerceIn(0f, 1f)).toLong())
                        true
                    } else false
                }
            }.focusProperties { canFocus = enabled }.focusable()) {
                val progress = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f
                val thickness = if (focused) 4.dp else 3.dp
                // A narrow static underlay keeps the track legible over bright frames.
                drawLine(Color.Black.copy(alpha = .4f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                    strokeWidth = (thickness + 2.dp).toPx(), cap = StrokeCap.Round)
                if (duration > 0) {
                    drawCircle(Color.Black.copy(alpha = .4f), (if (focused || preview != null) 8.dp else 6.dp).toPx(),
                        Offset(size.width * progress, size.height / 2))
                }
                drawMediaProgressTrack(progress,
                    Color.White.copy(alpha = .36f), FilewayPalette.darkAccent,
                    buffered = if (duration > 0) buffered.toFloat() / duration else null,
                    bufferedFrom = if (duration > 0) position.toFloat() / duration else 0f,
                    bufferedColor = Color.White.copy(alpha = .68f),
                    cachedRanges = ranges, downloadedColor = FilewayPalette.darkAccent.copy(alpha = .5f),
                    thickness = thickness,
                    thumbRadius = if (duration <= 0) 0.dp else if (focused || preview != null) 6.dp else 4.dp)
            }
        Text(total, Modifier.widthIn(min = timeWidth), style = timeStyle, textAlign = TextAlign.End, maxLines = 1)
    }
}

/** Title, Beijing time and actual transfer speed share the reference's top line. */
@Composable internal fun BoxScope.TvPlaybackHeader(
    title: String?, showTime: Boolean, showSpeed: Boolean, bytesPerSecond: Long, safePercent: Int,
) {
    var time by remember { mutableStateOf("") }
    LaunchedEffect(showTime) {
        if (showTime) while (true) {
            time = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            kotlinx.coroutines.delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    if (title == null && !showTime && !showSpeed) return
    val telemetry = tvTextStyle(18, 24).copy(fontFeatureSettings = "tnum")
    val backdrop = Modifier.background(Brush.verticalGradient(
        0f to PlaybackScrim.copy(alpha = .80f), .60f to PlaybackScrim.copy(alpha = .70f), 1f to Color.Transparent,
    ))
    BoxWithConstraints(Modifier.align(Alignment.TopCenter).fillMaxWidth().then(backdrop)) {
        val horizontal = tvPlayerSafeInset(maxWidth, safePercent, 16.dp)
        val top = tvPlayerSafeInset(maxHeight, safePercent, 12.dp)
        Row(
            Modifier.fillMaxWidth().padding(start = horizontal, end = horizontal, top = top, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showTime) {
                Text(time, Modifier.widthIn(min = 64.dp), style = tvTextStyle(20, 28, FontWeight.Medium).copy(fontFeatureSettings = "tnum"), color = TvPlayerDesign.text, maxLines = 1)
                if (title != null) Spacer(Modifier.width(16.dp))
            }
            if (title != null) Text(
                title, Modifier.weight(1f), style = tvTextStyle(20, 28, FontWeight.Medium),
                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            ) else Spacer(Modifier.weight(1f))
            if (showSpeed) {
                if (title != null) Spacer(Modifier.width(16.dp))
                Text(tvSpeed(bytesPerSecond), style = telemetry, color = Color.White.copy(alpha = .9f), maxLines = 1)
            }
        }
    }
}
internal fun tvSpeed(bytes:Long):String=if(bytes>=1_048_576)"%.1f MB/s".format(bytes/1_048_576.0)else"${bytes.coerceAtLeast(0)/1024} KB/s"

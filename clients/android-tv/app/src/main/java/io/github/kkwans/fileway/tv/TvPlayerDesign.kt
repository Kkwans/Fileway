package io.github.kkwans.fileway.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.*
import io.github.kkwans.fileway.design.R as Art
import kotlinx.coroutines.delay

/** Media surfaces are independent of the ordinary page theme and the video pixels. */
internal object TvPlayerDesign {
    val surface = Color(0xEB17191D)
    val raised = Color(0xFF25282C)
    val selected = Color(0xFF23354C)
    val text = Color(0xFFF3F4F6)
    val secondary = Color(0xFFBBC1CA)
    val muted = Color(0xFFA2AAB5)
    val accent = Color(0xFF8FB8FF)
    val feedback = Color(0xD617191D)
    val divider = Color(0xFF353A42)
    val outline = Color(0xFF7B8490)
    val focus = Color(0xFFF3F4F6)
}

@Composable internal fun playerFocusColor(focused: Boolean): Color = animateColorAsState(
    if (focused) TvPlayerDesign.focus else Color.Transparent, tween(100), label = "player-focus",
).value

/** A new media/seek operation and lifecycle stop both invalidate the pending delay. */
@Composable internal fun delayedPlayerFeedback(busy: Boolean, mediaGeneration: Long, operation: Int): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var visible by remember(mediaGeneration, operation) { mutableStateOf(false) }
    DisposableEffect(lifecycle, mediaGeneration, operation) {
        val observer = LifecycleEventObserver { _, _ ->
            started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!started) visible = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(busy, started, mediaGeneration, operation) {
        visible = false
        if (busy && started) { delay(400); visible = true }
    }
    return busy && started && visible
}

@Composable internal fun TvPlaybackFeedback(title: String, modifier: Modifier = Modifier, icon: Int? = null, detail: String? = null) {
    Row(modifier.widthIn(max = 480.dp).background(TvPlayerDesign.feedback, TvShape)
        .border(1.dp, Color.White.copy(alpha = .12f), TvShape).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(painterResource(icon), null, Modifier.size(24.dp), tint = TvPlayerDesign.text)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = tvTextStyle(18, 28, FontWeight.Medium), color = TvPlayerDesign.text)
            if (!detail.isNullOrBlank()) Text(detail, style = tvTextStyle(16, 24).copy(fontFeatureSettings = "tnum"),
                color = TvPlayerDesign.secondary)
        }
    }
}

@Composable internal fun TvPausedPlayback(onResume: () -> Unit, pending: Boolean, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Button(onClick = onResume, interactionSource = interaction,
        modifier = modifier.size(72.dp).border(2.dp, playerFocusColor(focused), CircleShape)
            .tvTouch(onResume, interaction = interaction)
            .semantics { contentDescription = if (pending) "正在恢复播放" else "继续播放" },
        contentPadding = PaddingValues(0.dp), shape = ButtonDefaults.shape(CircleShape),
        scale = ButtonDefaults.scale(focusedScale = 1f), border = ButtonDefaults.border(focusedBorder = Border.None),
        colors = ButtonDefaults.colors(containerColor = TvPlayerDesign.feedback, focusedContainerColor = TvPlayerDesign.raised,
            contentColor = TvPlayerDesign.text, focusedContentColor = TvPlayerDesign.text)) {
        Icon(painterResource(Art.drawable.fileway_ic_play_arrow), null, Modifier.size(32.dp))
    }
}

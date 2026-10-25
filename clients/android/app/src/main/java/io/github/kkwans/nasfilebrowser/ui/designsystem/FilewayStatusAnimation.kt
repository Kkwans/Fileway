package io.github.kkwans.nasfilebrowser.ui.designsystem

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.*
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.ui.LocalFilewayPageActive

internal enum class FilewayStatusAnimation(val asset: String, val fallback: Int) {
    CONNECTION("connection-ready", R.drawable.ic_link),
    COMPLETE("transfer-complete", R.drawable.ic_check),
    EMPTY_FAVORITES("favorites-empty", R.drawable.ic_bookmark),
}

@Composable internal fun FilewayStatusIcon(status: FilewayStatusAnimation, eventKey: String,
    modifier: Modifier = Modifier.size(28.dp), play: Boolean = true) {
    FilewayAnimatedGlyph(status.asset, null, status.fallback, if (play) eventKey else null, modifier, FilewayTheme.colors.primary)
}

/** One renderer for status and interaction icons; original marker ranges and geometry are retained. */
@Composable internal fun FilewayAnimatedGlyph(asset: String?, markerName: String?, fallback: Int, trigger: String?,
    modifier: Modifier, tint: Color) {
    val composition = if (asset == null) null else rememberLottieComposition(LottieCompositionSpec.Asset("lordicon/$asset.json")).value
    val animation = rememberLottieAnimatable()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val allowed = lifecycle.isAtLeast(Lifecycle.State.RESUMED) && LocalFilewayPageActive.current && ValueAnimator.areAnimatorsEnabled()
    var lastTrigger by rememberSaveable(asset) { mutableStateOf<String?>(null) }
    var lastPulse by rememberSaveable(asset) { mutableStateOf<String?>(null) }
    var ready by remember(asset) { mutableStateOf(false) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(trigger, allowed) {
        if (composition == null && trigger != null && trigger != lastPulse) {
            lastPulse = trigger
            if (allowed) { scale.snapTo(.88f); scale.animateTo(1f, tween(180)) }
        }
    }
    LaunchedEffect(composition, trigger, allowed) {
        val source = composition ?: return@LaunchedEffect
        val marker = markerName?.let(source::getMarker)
        val rest = marker?.let { (it.startFrame / source.endFrame).coerceIn(0f,1f) } ?: 1f
        val clip = marker?.let { LottieClipSpec.Frame(it.startFrame.toInt(), (it.startFrame + it.durationFrames).toInt()-1) }
        if (!ready) { animation.snapTo(source, rest); ready = true }
        if (trigger == null || trigger == lastTrigger || !allowed) {
            if (trigger != null) lastTrigger = trigger
            animation.snapTo(source, rest)
            return@LaunchedEffect
        }
        lastTrigger = trigger
        animation.animate(source, iterations=1, clipSpec=clip, initialProgress=if(marker == null) 0f else rest,
            cancellationBehavior=LottieCancellationBehavior.Immediately)
        animation.snapTo(source, rest)
    }
    val color = if (tint == Color.Unspecified) FilewayTheme.colors.onSurface else tint.copy(alpha=1f)
    val properties = rememberLottieDynamicProperties(
        rememberLottieDynamicProperty(LottieProperty.STROKE_COLOR,color.toArgb(),"**"),
        rememberLottieDynamicProperty(LottieProperty.COLOR,color.toArgb(),"**"),
    )
    if (composition == null || !ready) Icon(painterResource(fallback), null, modifier.scale(scale.value), tint=tint)
    else LottieAnimation(composition, progress={animation.progress},modifier=modifier.alpha(tint.alpha),dynamicProperties=properties)
}

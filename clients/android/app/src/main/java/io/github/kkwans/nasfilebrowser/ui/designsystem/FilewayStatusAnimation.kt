package io.github.kkwans.nasfilebrowser.ui.designsystem

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.*
import io.github.kkwans.nasfilebrowser.R
import kotlinx.coroutines.CancellationException

internal enum class FilewayStatusAnimation(val asset: String, val fallback: Int) {
    CONNECTION("connection-ready", R.drawable.ic_link),
    COMPLETE("transfer-complete", R.drawable.ic_check),
    EMPTY_FAVORITES("favorites-empty", R.drawable.ic_bookmark),
}

/** Assets are privately injected into signed builds; public clones retain native fallbacks. */
@Composable internal fun FilewayStatusIcon(status: FilewayStatusAnimation, eventKey: String, modifier: Modifier = Modifier.size(28.dp), play: Boolean = true) {
    val composition by rememberLottieComposition(LottieCompositionSpec.Asset("lordicon/${status.asset}.json"))
    val animation = rememberLottieAnimatable()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var consumed by rememberSaveable(status, eventKey) { mutableStateOf(false) }
    val allowed = lifecycle.isAtLeast(Lifecycle.State.RESUMED) && ValueAnimator.areAnimatorsEnabled()
    LaunchedEffect(composition, allowed, eventKey, play) {
        val source = composition ?: return@LaunchedEffect
        if (!allowed || !play || consumed) { animation.snapTo(source, 1f); return@LaunchedEffect }
        consumed = true
        try { animation.animate(source, iterations = 1, initialProgress = 0f, cancellationBehavior = LottieCancellationBehavior.Immediately) }
        catch (cancelled: CancellationException) { throw cancelled }
    }
    val color = FilewayTheme.colors.primary
    val properties = rememberLottieDynamicProperties(
        rememberLottieDynamicProperty(LottieProperty.STROKE_COLOR, color.toArgb(), "**"),
        rememberLottieDynamicProperty(LottieProperty.COLOR, color.toArgb(), "**"),
    )
    if (composition == null) Icon(painterResource(status.fallback), null, modifier, tint = color)
    else LottieAnimation(composition, progress = { if (!play || !allowed || consumed && !animation.isPlaying) 1f else animation.progress }, modifier = modifier, dynamicProperties = properties)
}

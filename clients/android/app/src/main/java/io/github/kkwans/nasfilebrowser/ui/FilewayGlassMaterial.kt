package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme

/** Acrylic-style material roles, shared by native windows and the Compose drawer. */
@Composable internal fun filewayGlassBorder(inner: Boolean = false): BorderStroke {
    val light = FilewayTheme.colors.surface.luminance() > .5f
    val colors = if (light) listOf(Color(0xFF8292A6).copy(alpha = if (inner) .18f else .36f),
        Color(0xFF8292A6).copy(alpha = if (inner) .10f else .20f))
    else listOf(Color.White.copy(alpha = if (inner) .13f else .24f),
        Color.White.copy(alpha = if (inner) .06f else .08f))
    return BorderStroke(1.dp, Brush.verticalGradient(colors))
}

@Composable internal fun Modifier.filewayGlassFrame(shape: Shape = RoundedCornerShape(12.dp)): Modifier =
    border(filewayGlassBorder(), shape)

@Composable internal fun filewayGlassLayerColor(): Color =
    if (FilewayTheme.colors.surface.luminance() > .5f) Color.White.copy(alpha = .40f)
    else Color.White.copy(alpha = .065f)

/** A translucent secondary layer; it reuses the already blurred backdrop. */
@Composable internal fun FilewayGlassSection(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, color = filewayGlassLayerColor(), shape = RoundedCornerShape(12.dp),
        border = filewayGlassBorder(inner = true), tonalElevation = 0.dp, content = content)
}

@Composable internal fun filewayDrawerGlassStyle(): HazeBlurStyle {
    val tint = FilewayTheme.colors.surface.copy(alpha =
        if (FilewayTheme.colors.surface.luminance() > .5f) .76f else .74f)
    val fallback = FilewayTheme.colors.surface
    return HazeBlurStyle {
        blurRadius(32.dp)
        noiseFactor(.025f)
        backgroundColor(fallback)
        colorEffects(listOf(HazeColorEffect.tint(tint)))
        fallbackColorEffect(HazeColorEffect.tint(fallback))
    }
}

package io.github.kkwans.nasfilebrowser.ui.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared phone/TV track rendering. Callers own input, focus and the source timeline. */
fun DrawScope.drawMediaProgressTrack(
    progress: Float,
    trackColor: Color,
    progressColor: Color,
    buffered: Float? = null,
    bufferedColor: Color = trackColor,
    downloaded: Float? = null,
    downloadedColor: Color = bufferedColor,
    cachedRanges: List<Pair<Float, Float>> = emptyList(),
    thickness: Dp = 3.dp,
    thumbRadius: Dp = 0.dp,
    yOffset: Dp = 0.dp,
) {
    if (size.width <= 0 || size.height <= 0) return
    fun fraction(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    val y = size.height / 2 + yOffset.toPx()
    val stroke = thickness.toPx()
    fun segment(start: Float, end: Float, color: Color) {
        val from = fraction(start); val to = fraction(end)
        if (to > from) drawLine(color, Offset(size.width * from, y), Offset(size.width * to, y), stroke, StrokeCap.Round)
    }
    segment(0f, 1f, trackColor)
    downloaded?.let { segment(0f, it, downloadedColor) }
    cachedRanges.forEach { (start, end) -> segment(start, end, downloadedColor) }
    buffered?.let { segment(0f, it, bufferedColor) }
    segment(0f, progress, progressColor)
    if (thumbRadius > 0.dp) drawCircle(progressColor, thumbRadius.toPx(), Offset(size.width * fraction(progress), y))
}

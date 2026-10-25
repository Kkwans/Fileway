package io.github.kkwans.nasfilebrowser.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.res.painterResource
import io.github.kkwans.nasfilebrowser.R
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.VideoSprite
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

/** Fetch at most once per playback generation, only after the first timeline drag. */
@Composable internal fun rememberSeekPreview(model: ClientModel, file: ResourceRef, generation: Long, dragging: Boolean): Pair<VideoSprite, Bitmap>? {
    var requested by remember(file, generation) { mutableStateOf(false) }
    var asset by remember(file, generation) { mutableStateOf<Pair<VideoSprite, Bitmap>?>(null) }
    LaunchedEffect(dragging, file, generation) { if (dragging) requested = true }
    LaunchedEffect(requested, file, generation) {
        if (!requested) return@LaunchedEffect
        try { asset = model.videoSprite(file) }
        catch (error: Exception) { if (error is CancellationException) throw error }
    }
    return asset
}

@Composable internal fun SeekPreview(asset: Pair<VideoSprite, Bitmap>?, positionMs: Long?, durationMs: Long, startMs: Long) {
    if (positionMs == null) return
    BoxWithConstraints(Modifier.fillMaxWidth().height(0.dp)) {
        val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else .5f
        PlayerFeedbackWindow(Modifier.offset(x = maxWidth * fraction), above = true) {
            SeekFeedbackContent(asset, positionMs, startMs, durationMs)
        }
    }
}

@Composable internal fun PictureSeekFeedback(targetMs: Long, startMs: Long, durationMs: Long,
    asset: Pair<VideoSprite, Bitmap>?, modifier: Modifier = Modifier) {
    PlayerFeedbackWindow(modifier) { SeekFeedbackContent(asset, targetMs, startMs, durationMs) }
}

@Composable private fun SeekFeedbackContent(asset: Pair<VideoSprite, Bitmap>?, target: Long, start: Long, duration: Long) {
    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SeekThumbnail(asset, target)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(clock(target), color = Color.White, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
            if (duration > 0) Text("/ ${clock(duration)}", color = Color.White.copy(alpha=.68f), fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilewayIcon(if (target < start) R.drawable.ic_skip_back else R.drawable.ic_skip_forward, null,
                Modifier.size(18.dp), tint = Color.White.copy(alpha=.72f))
            Text((if (target < start) "−" else "+") + clock(abs(target-start)), color = Color.White.copy(alpha=.72f), fontSize = 12.sp)
        }
    }
}

/** Stable preview bounds: asynchronous arrival never moves the window or the target-time row. */
@Composable private fun SeekThumbnail(asset: Pair<VideoSprite, Bitmap>?, positionMs: Long, width: Dp = 144.dp) {
    Box(Modifier.width(width).height(width * 9f / 16f).clip(RoundedCornerShape(12.dp))
        .background(Color.White.copy(alpha = .06f)), contentAlignment = Alignment.Center) {
        if (asset == null) {
            FilewayIcon(R.drawable.ic_image, null, Modifier.size(24.dp), tint = Color.White.copy(alpha = .24f))
        } else {
            val (sprite, bitmap) = asset
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            val (x, y) = sprite.tileAt(positionMs)
            Canvas(Modifier.matchParentSize().semantics { contentDescription = "当前拖动时间附近的视频缩略图" }) {
                val scale = minOf(size.width / sprite.width, size.height / sprite.height)
                val widthPx = (sprite.width * scale).toInt()
                val heightPx = (sprite.height * scale).toInt()
                drawImage(image, srcOffset = IntOffset(x, y), srcSize = IntSize(sprite.width, sprite.height),
                    dstOffset = IntOffset((size.width.toInt() - widthPx) / 2, (size.height.toInt() - heightPx) / 2),
                    dstSize = IntSize(widthPx, heightPx))
            }
        }
    }
}

/** Compact neutral glass feedback. Percent remains available to accessibility. */
@Composable internal fun PlayerLevelFeedback(brightness: Boolean, level: Float, modifier: Modifier = Modifier) {
    val amount = level.coerceIn(0f, 1f)
    PlayerFeedbackWindow(modifier) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilewayIcon(if (brightness) R.drawable.ic_theme_light else R.drawable.art_volume, null,
                Modifier.size(24.dp), tint = Color.White)
            LinearProgressIndicator(progress = { amount }, modifier = Modifier.width(104.dp).height(4.dp),
                color = Color.White, trackColor = Color.White.copy(alpha=.22f), gapSize = 0.dp, drawStopIndicator = {})
            Text("${(amount * 100).toInt()}%", Modifier.widthIn(min=36.dp), color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable internal fun PlayerActionFeedback(message: String, icon: Int, modifier: Modifier = Modifier) {
    PlayerFeedbackWindow(modifier) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilewayIcon(icon, null, Modifier.size(24.dp), tint = Color.White)
            Text(message, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

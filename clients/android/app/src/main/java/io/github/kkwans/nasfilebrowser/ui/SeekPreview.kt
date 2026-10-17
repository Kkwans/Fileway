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

@Composable internal fun SeekPreview(asset: Pair<VideoSprite, Bitmap>?, positionMs: Long?) {
    if (positionMs == null) return
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xE6202023)).padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SeekThumbnail(asset, positionMs)
            Text(clock(positionMs), color = Color.White, fontSize = 12.sp)
        }
    }
}

@Composable internal fun PictureSeekFeedback(targetMs: Long, startMs: Long, durationMs: Long,
    asset: Pair<VideoSprite, Bitmap>?, modifier: Modifier = Modifier) {
    val delta = targetMs - startMs
    val change = (if (delta < 0) "后退 " else "前进 ") + clock(abs(delta))
    Column(modifier.clearAndSetSemantics {
        contentDescription = "画面拖动进度"
        stateDescription = "${clock(targetMs)}，$change"
    }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SeekThumbnail(asset, targetMs, 96.dp)
        Column(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000))
            .padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(clock(targetMs), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                    lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.alignByBaseline())
                Text("/ ${clock(durationMs)}", color = Color(0xFFD1D6DD), fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, modifier = Modifier.alignByBaseline())
            }
            Text((if (delta < 0) "−" else "+") + clock(abs(delta)), color = Color(0xFFD1D6DD),
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
        }
    }
}

@Composable private fun SeekThumbnail(asset: Pair<VideoSprite, Bitmap>?, positionMs: Long, width: Dp = 144.dp) {
    asset?.let { (sprite, bitmap) ->
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        val (x, y) = sprite.tileAt(positionMs)
        Canvas(Modifier.width(width).aspectRatio(sprite.width.toFloat() / sprite.height).clip(RoundedCornerShape(6.dp))
            .semantics { contentDescription = "当前拖动时间附近的视频缩略图" }) {
            drawImage(image, srcOffset = IntOffset(x, y), srcSize = IntSize(sprite.width, sprite.height),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        }
    }
}

/** Compact neutral glass feedback. Percent remains available to accessibility. */
@Composable internal fun PlayerLevelFeedback(brightness: Boolean, level: Float, modifier: Modifier = Modifier) {
    val amount = level.coerceIn(0f, 1f)
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .62f))
        .padding(horizontal = 18.dp, vertical = 16.dp).clearAndSetSemantics {
            contentDescription = if (brightness) "亮度" else "音量"
            stateDescription = "${(amount * 100).toInt()}%"
        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(painterResource(if (brightness) R.drawable.ic_theme_light else R.drawable.art_volume), null,
            Modifier.size(26.dp), tint = Color.White)
        LinearProgressIndicator(progress = { amount }, modifier = Modifier.width(104.dp).height(4.dp),
            color = Color.White, trackColor = Color.White.copy(alpha = .22f))
    }
}

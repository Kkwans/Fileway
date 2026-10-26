package io.github.kkwans.nasfilebrowser.ui

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.androidpoet.mirage.MeshGradient
import io.androidpoet.mirage.core.ShaderFit
import io.androidpoet.mirage.core.SizingParams
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.mediaKey
import io.github.kkwans.nasfilebrowser.player.PlayerState
import io.github.kkwans.nasfilebrowser.player.videoPresentation
import kotlinx.coroutines.CancellationException

/** Paper Design / Mirage fluid mesh; never samples or replaces the native video surface. */
@Composable internal fun PlayerAmbient(colors: List<Color>, moving: Boolean, modifier: Modifier = Modifier) {
    val palette = colors.mapIndexed { index, color ->
        animateColorAsState(color, tween(1400), label = "artworkMesh$index").value
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        MeshGradient(modifier = modifier, colors = palette, distortion = .8f, swirl = .24f,
            speed = if (moving && ValueAnimator.areAnimatorsEnabled()) .48f else 0f,
            sizing = SizingParams(fit = ShaderFit.Cover))
    } else {
        // RuntimeShader is unavailable before API 33. Keep artwork and contrast without a frame loop.
        Box(modifier.background(Brush.linearGradient(palette)))
    }
}

@Composable internal fun PlayerInformation(model: ClientModel, file: ResourceRef, scope: String, state: PlayerState) {
    var metadata by remember(file.mediaKey, scope) { mutableStateOf(file) }
    var failed by remember(file.mediaKey, scope) { mutableStateOf(false) }
    var attempt by remember(file.mediaKey, scope) { mutableIntStateOf(0) }
    LaunchedEffect(file.mediaKey, scope, attempt) {
        metadata = file
        failed = false
        if (file.size <= 0 && file.downloadId.isEmpty() && !model.isTemporaryContent(file)) {
            try { metadata = model.resourceMetadata(file) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
    }
    PlayerInformationCard {
        Text("视频信息", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PlayerInformationField("分辨率", if (state.width > 0 && state.height > 0) "${state.width} × ${state.height}" else "—", Modifier.weight(1f))
            PlayerInformationField("时长", if (state.durationMs > 0) clock(state.durationMs) else "—", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PlayerInformationField("文件大小", if (metadata.size > 0) readableSize(metadata.size) else if (failed) "未获取 · 重试" else "—",
                Modifier.weight(1f).clickable(enabled = failed, role = Role.Button) { attempt++ })
            PlayerInformationField("视频格式", videoPresentation(file.name, state.sourceDynamicRange), Modifier.weight(1f))
        }
    }
}

@Composable internal fun PlayerTrackInformation(state: PlayerState, enabled: Boolean, audio: () -> Unit, subtitles: () -> Unit) {
    PlayerInformationCard {
        Text("音轨与字幕", style = MaterialTheme.typography.titleSmall)
        PlayerTrackInformationRow(R.drawable.ic_audio, "音轨",
            state.audio.firstOrNull { it.id == state.selectedAudio }?.let { it.title.ifBlank { it.language } }
                ?: if (state.audio.isEmpty()) "无音轨" else "默认音轨",
            enabled && state.audio.isNotEmpty(), audio)
        PlayerTrackInformationRow(R.drawable.ic_subtitles, "字幕",
            if (state.subtitleLoading) "正在加载" else state.subtitles.firstOrNull { it.id == state.selectedSubtitle }?.title ?: "关闭字幕",
            enabled, subtitles)
    }
}

@Composable private fun PlayerTrackInformationRow(icon: Int, title: String, value: String, enabled: Boolean, action: () -> Unit) {
    Surface(onClick = action, enabled = enabled, shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = .045f), border = BorderStroke(1.dp, Color.White.copy(alpha = .08f))) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilewayIcon(icon, null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            FilewayIcon(R.drawable.ic_arrow_forward, null, Modifier.size(20.dp))
        }
    }
}

@Composable internal fun PlayerInformationCard(modifier: Modifier = Modifier, contentPadding: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = .055f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .10f))) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable private fun PlayerInformationField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.heightIn(min = 48.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

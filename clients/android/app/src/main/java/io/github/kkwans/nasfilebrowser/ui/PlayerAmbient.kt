package io.github.kkwans.nasfilebrowser.ui

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.player.PlayerState
import kotlinx.coroutines.isActive
import kotlin.math.sin
import kotlin.math.cos

/** Draw-only cover atmosphere: never samples, filters or replaces the video surface. */
@Composable internal fun PlayerAmbient(accent: Color, moving: Boolean, modifier: Modifier = Modifier) {
    var phase by remember { mutableFloatStateOf(0f) }
    val enabled = moving && ValueAnimator.areAnimatorsEnabled()
    LaunchedEffect(enabled) {
        if (enabled) {
            var previous = 0L
            while (isActive) withFrameNanos { now ->
                if (previous != 0L) phase = (phase + (now-previous).coerceAtMost(100_000_000L) / 36_000_000_000f) % 1f
                previous = now
            }
        }
    }
    Canvas(modifier) {
        val base = Color(0xFF19191F)
        val color = lerp(base, accent, .32f)
        drawRect(base)
        val angle = phase * (Math.PI * 2).toFloat()
        drawRect(Brush.radialGradient(listOf(color.copy(alpha=.66f), Color.Transparent),
            center=Offset(size.width*(.22f+.12f*sin(angle)),size.height*(.27f+.09f*cos(angle))), radius=size.height*.75f))
        drawRect(Brush.radialGradient(listOf(lerp(base,accent,.20f).copy(alpha=.70f), Color.Transparent),
            center=Offset(size.width*(.84f-.10f*cos(angle)),size.height*(.84f+.08f*sin(angle))), radius=size.height*.64f))
    }
}

@Composable internal fun PlayerInformation(file: ResourceRef, state: PlayerState) {
    val surface = Color.White.copy(alpha=.055f)
    val border = BorderStroke(1.dp,Color.White.copy(alpha=.09f))
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),color=surface,border=border) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("视频信息",style=MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                PlayerInformationField("分辨率",if(state.width>0&&state.height>0) "${state.width} × ${state.height}" else "—",Modifier.weight(1f))
                PlayerInformationField("时长",if(state.durationMs>0) clock(state.durationMs) else "—",Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                PlayerInformationField("文件大小",if(file.size>0) readableSize(file.size) else "—",Modifier.weight(1f))
                PlayerInformationField("视频格式",state.sourceVideoCodec.takeUnless{it=="未知"}.orEmpty().ifBlank { file.name.substringAfterLast('.',"—").uppercase() },Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun PlayerInformationField(label:String,value:String,modifier:Modifier=Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,style=MaterialTheme.typography.bodyLarge)
    }
}

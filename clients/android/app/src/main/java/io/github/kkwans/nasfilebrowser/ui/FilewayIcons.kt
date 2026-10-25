package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayAnimatedGlyph

internal val LocalFilewayIconPulse = staticCompositionLocalOf { 0 }
internal val LocalFilewayNavigationPulse = staticCompositionLocalOf<MutableState<Pair<String,Int>>?> { null }

private fun animatedAsset(resource: Int): String? = when(resource) {
    R.drawable.ic_nav_files_077, R.drawable.ic_folder -> "nav-files"
    R.drawable.ic_nav_history_077, R.drawable.ic_history -> "nav-recent"
    R.drawable.ic_library -> "nav-library"
    R.drawable.ic_tasks -> "nav-tasks"
    R.drawable.ic_person -> "nav-my"
    R.drawable.ic_search -> "action-search"
    else -> null
}

/** One semantic tint and optical slot for both animated originals and Material Symbols fallbacks. */
@Composable internal fun FilewayIcon(resource: Int, contentDescription: String?, modifier: Modifier=Modifier.size(24.dp),
    tint: Color=LocalContentColor.current, eventKey: Int=LocalFilewayIconPulse.current) {
    val asset = animatedAsset(resource)
    val description = contentDescription
    val actualTint = if (tint == Color.Unspecified && asset != null) LocalContentColor.current else tint
    FilewayAnimatedGlyph(asset, if(asset == null) null else "default:hover-pinch",resource,
        if(eventKey > 0) eventKey.toString() else null,
        modifier.semantics { if(description != null) this.contentDescription=description },actualTint)
}

@Composable internal fun FilewayIconButton(onClick: () -> Unit, modifier: Modifier=Modifier, enabled: Boolean=true,
    colors: IconButtonColors=IconButtonDefaults.iconButtonColors(), interactionSource: MutableInteractionSource?=null,
    content: @Composable () -> Unit) {
    var pulse by remember { mutableIntStateOf(0) }
    CompositionLocalProvider(LocalFilewayIconPulse provides pulse) {
        androidx.compose.material3.IconButton(onClick={pulse++;onClick()},modifier=modifier,enabled=enabled,
            colors=colors,interactionSource=interactionSource,content=content)
    }
}

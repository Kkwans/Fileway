package io.github.kkwans.fileway.tv.medialibrary

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import coil3.ImageLoader
import coil3.compose.AsyncImage
import io.github.kkwans.fileway.tv.LocalTvInteractive
import io.github.kkwans.fileway.tv.tvTextStyle
import io.github.kkwans.fileway.tv.tvTouch
import io.github.kkwans.fileway.design.R as Art

internal data class MediaColors(
    val canvas: Color, val surface: Color, val raised: Color, val text: Color,
    val secondary: Color, val focus: Color, val accent: Color, val score: Color,
)

internal val LocalMediaColors = staticCompositionLocalOf {
    MediaColors(Color(0xFF17191B), Color(0xFF222427), Color(0xFF34373A), Color(0xFFF4F1EB),
        Color(0xFFC6C7CA), Color(0xFFF6F2E9), Color(0xFF3596E8), Color(0xFFF1C66D))
}

/** The library owns its neutral surfaces while reusing the app's licensed typography. */
@Composable
fun TvMediaTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (dark) MediaColors(
        Color(0xFF17191B), Color(0xFF222427), Color(0xFF34373A), Color(0xFFF4F1EB),
        Color(0xFFC6C7CA), Color(0xFFF6F2E9), Color(0xFF3596E8), Color(0xFFF1C66D),
    ) else MediaColors(
        Color(0xFFF3F2EF), Color(0xFFE8E7E3), Color(0xFFDADBD8), Color(0xFF202326),
        Color(0xFF51575B), Color(0xFF30373D), Color(0xFF176EB3), Color(0xFF825300),
    )
    val scheme = MaterialTheme.colorScheme.copy(
        background = colors.canvas, surface = colors.surface, onBackground = colors.text,
        onSurface = colors.text, primary = colors.accent,
    )
    CompositionLocalProvider(LocalMediaColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content)
    }
}

@Composable
internal fun MediaAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    primary: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalMediaColors.current
    MediaSurface(onClick, modifier.heightIn(min = 48.dp), enabled = enabled,
        selected = selected, container = if (primary) colors.text else colors.surface,
        contentColor = if (primary) colors.canvas else colors.text) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(22.dp))
            Text(label, style = tvTextStyle(18, 25, FontWeight.Medium))
        }
    }
}

@Composable
internal fun MediaSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    container: Color = LocalMediaColors.current.surface,
    contentColor: Color = LocalMediaColors.current.text,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalMediaColors.current
    val interactive = LocalTvInteractive.current && enabled
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    Surface(onClick = onClick, enabled = interactive, interactionSource = interaction,
        modifier = modifier.tvTouch(onClick, interactive, interaction)
            .focusProperties { canFocus = interactive }.semantics { this.selected = selected; role = Role.Button },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, colors.focus), shape = shape)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container, contentColor = contentColor,
            focusedContainerColor = container, focusedContentColor = contentColor,
            pressedContainerColor = colors.raised, pressedContentColor = colors.text,
            disabledContainerColor = container, disabledContentColor = colors.secondary),
        content = content)
}

@Composable
internal fun MediaArtwork(
    artwork: TvMediaArtwork?, imageLoader: ImageLoader, modifier: Modifier,
    fallbackIcon: Int = Art.drawable.fileway_ic_video,
    decorative: Boolean = false,
) {
    val colors = LocalMediaColors.current
    BoxWithConstraints(modifier.background(colors.surface), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        // Bound decoding to the displayed size; never decode the provider's full original.
        val width = with(density) { maxWidth.roundToPx() }.coerceIn(1, 1920)
        val height = with(density) { maxHeight.roundToPx() }.coerceIn(1, 1080)
        val request = remember(artwork?.request, width, height) {
            artwork?.request?.newBuilder()?.size(width, height)?.build()
        }
        var ready by remember(request) { mutableStateOf(false) }
        if (!ready && !decorative) Icon(painterResource(fallbackIcon), null, Modifier.size(36.dp), tint = colors.secondary)
        if (request != null) AsyncImage(model = request, imageLoader = imageLoader,
            contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            onSuccess = { ready = true }, onError = { ready = false })
    }
}

@Composable
internal fun MediaBackdrop(artwork: TvMediaArtwork?, imageLoader: ImageLoader, modifier: Modifier) {
    val colors = LocalMediaColors.current
    Box(modifier) {
        MediaArtwork(artwork, imageLoader, Modifier.matchParentSize(), decorative = true)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to colors.canvas.copy(alpha = .97f), .48f to colors.canvas.copy(alpha = .77f),
            1f to colors.canvas.copy(alpha = .16f))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Transparent, .6f to colors.canvas.copy(alpha = .16f), 1f to colors.canvas)))
    }
}

@Composable
internal fun MediaProgress(progress: Float?, modifier: Modifier = Modifier) {
    if (progress == null || !progress.isFinite()) return
    val colors = LocalMediaColors.current
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .25f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(colors.accent))
    }
}

@Composable
internal fun MediaPoster(item: TvMediaCard, imageLoader: ImageLoader, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalMediaColors.current
    Column(modifier.width(132.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        MediaSurface(onClick, Modifier.fillMaxWidth().aspectRatio(2f / 3f)
            .semantics { contentDescription = item.title }) {
            MediaArtwork(item.poster, imageLoader, Modifier.fillMaxSize())
            item.primaryRating?.let { rating ->
                Text("${rating.source.label} ${rating.valueLabel}", Modifier.align(Alignment.TopStart)
                    .padding(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xD9222427)).padding(horizontal = 6.dp, vertical = 3.dp),
                    style = tvTextStyle(14, 19, FontWeight.Medium), color = Color(0xFFF1C66D), maxLines = 2)
            }
            item.extentLabel?.takeIf(String::isNotBlank)?.let { label ->
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .85f)))).padding(8.dp)) {
                    Text(label, style = tvTextStyle(14, 20), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(item.title, style = tvTextStyle(17, 24, FontWeight.Medium), color = colors.text,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(item.yearLabel, item.availabilityLabel).filter(String::isNotBlank).joinToString(" · "),
            style = tvTextStyle(16, 22), color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun MediaShelf(
    shelf: TvMediaShelf, imageLoader: ImageLoader, onItem: (String) -> Unit,
    onRetry: ((String) -> Unit)? = null,
) {
    if (shelf.items.isEmpty() && !shelf.loading && shelf.error == null) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MediaSectionTitle(shelf.title)
        if (shelf.error != null) MediaStatus(shelf.error, onRetry = onRetry?.let { { it(shelf.id) } }, compact = true)
        if (shelf.items.isEmpty() && shelf.loading) MediaStatus("正在加载…", compact = true)
        if (shelf.items.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 40.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.focusGroup()) {
            items(shelf.items, key = { it.id }) { item -> MediaPoster(item, imageLoader, { onItem(item.id) }) }
        }
    }
}

@Composable
internal fun MediaSectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = tvTextStyle(24, 32, FontWeight.Medium), color = LocalMediaColors.current.text)
        if (action != null && onAction != null) MediaAction(action, onAction)
    }
}

@Composable
internal fun MediaStatus(message: String, onRetry: (() -> Unit)? = null, compact: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = if (compact) 10.dp else 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(message, style = tvTextStyle(18, 27), color = LocalMediaColors.current.secondary)
        if (onRetry != null) MediaAction("重试", onRetry, icon = Art.drawable.fileway_ic_refresh)
    }
}

/** Supply only destinations that are actually wired. Selection is not keyboard focus. */
@Composable
fun TvMediaTopBar(
    items: List<TvMediaNavigationItem>, selectedId: String?, onSelect: (String) -> Unit,
    onSearch: (() -> Unit)? = null, onSettings: (() -> Unit)? = null,
) {
    val colors = LocalMediaColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("栖卷", style = tvTextStyle(26, 34, FontWeight.Medium), color = colors.text)
        LazyRow(Modifier.weight(1f).focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(4.dp)) {
            items(items, key = { it.id }) { item ->
                Column {
                    MediaAction(item.label, { onSelect(item.id) }, selected = item.id == selectedId)
                    if (item.id == selectedId) Box(Modifier.align(Alignment.CenterHorizontally)
                        .width(28.dp).height(3.dp).background(colors.accent, RoundedCornerShape(2.dp)))
                    else Spacer(Modifier.height(3.dp))
                }
            }
        }
        if (onSearch != null) MediaAction("搜索", onSearch, icon = Art.drawable.fileway_ic_search)
        if (onSettings != null) MediaAction("设置", onSettings, icon = Art.drawable.fileway_ic_settings)
    }
}

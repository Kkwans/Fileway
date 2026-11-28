package io.github.kkwans.fileway.tv.medialibrary

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.*
import coil3.ImageLoader
import coil3.compose.AsyncImage
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaPalette
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaShape
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaSectionHeader
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaSectionHeaderStyle
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaSpace
import io.github.kkwans.fileway.tv.LocalTvInteractive
import io.github.kkwans.fileway.tv.tvTouch
import kotlinx.coroutines.delay
import io.github.kkwans.fileway.design.R as Art

internal data class TvMediaFocusBinding(
    val targetId: String?, val targetOrigin: String?,
    val onOpen: (String, String) -> Unit, val onRestored: () -> Unit,
)
internal val LocalTvMediaFocusBinding = staticCompositionLocalOf<TvMediaFocusBinding?> { null }

@Composable internal fun Modifier.tvMediaReturnFocus(id: String, origin: String): Modifier {
    val binding = LocalTvMediaFocusBinding.current
    val interactive = LocalTvInteractive.current
    val requester = remember(id, origin) { FocusRequester() }
    val bringIntoView = remember(id, origin) { BringIntoViewRequester() }
    val requested = binding?.let { it.targetId == id && it.targetOrigin == origin } == true
    LaunchedEffect(requested, interactive) {
        if (requested && interactive) {
            withFrameNanos { }
            runCatching { bringIntoView.bringIntoView() }
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) binding.onRestored.invoke()
        }
    }
    return if (requested) focusRequester(requester).bringIntoViewRequester(bringIntoView) else this
}

internal val LocalMediaColors = staticCompositionLocalOf { FilewayMediaPalette.dark }

/** The library owns its neutral surfaces while reusing the app's licensed typography. */
@Composable
fun TvMediaTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (dark) FilewayMediaPalette.dark else FilewayMediaPalette.light
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
    val emphasize = primary && enabled
    MediaSurface(onClick, modifier.heightIn(min = FilewayMediaSpace.Tv.remoteTarget), enabled = enabled,
        selected = selected, container = if (emphasize) colors.play else colors.raised,
        contentColor = if (emphasize) colors.onPlay else colors.text) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(24.dp))
            Text(label, style = MediaType.action)
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
    selectionContainer: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalMediaColors.current
    val interactive = LocalTvInteractive.current && enabled
    val interaction = remember { MutableInteractionSource() }
    val shape = MediaLayout.shape
    val surface = if (selected && selectionContainer) colors.selected else container
    Surface(onClick = onClick, enabled = interactive, interactionSource = interaction,
        modifier = modifier.tvTouch(onClick, interactive, interaction)
            .focusProperties { canFocus = interactive }.semantics { this.selected = selected; role = Role.Button },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(FilewayMediaShape.focusRing, colors.focus), shape = shape)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = surface, contentColor = contentColor,
            focusedContainerColor = surface, focusedContentColor = contentColor,
            pressedContainerColor = colors.raised, pressedContentColor = colors.text,
            disabledContainerColor = surface, disabledContentColor = colors.secondary),
        content = content)
}

@Composable
internal fun MediaArtwork(
    artwork: TvMediaArtwork?, imageLoader: ImageLoader, modifier: Modifier,
    fallbackIcon: Int = Art.drawable.fileway_ic_video,
    decorative: Boolean = false,
    fallbackTitle: String? = null,
) {
    val colors = LocalMediaColors.current
    BoxWithConstraints(modifier.background(if (decorative) colors.canvas else colors.surface), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        // Bound decoding to the displayed size; never decode the provider's full original.
        val width = with(density) { maxWidth.roundToPx() }.coerceIn(1, 1920)
        val height = with(density) { maxHeight.roundToPx() }.coerceIn(1, 1080)
        val request = remember(artwork?.request, width, height) {
            artwork?.request?.newBuilder()?.size(width, height)?.build()
        }
        var ready by remember(request) { mutableStateOf(false) }
        var failed by remember(request) { mutableStateOf(false) }
        if (!ready && !decorative && !fallbackTitle.isNullOrBlank()) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(colors.raised, colors.surface, colors.canvas))))
            Column(Modifier.fillMaxSize().padding(start = FilewayMediaSpace.Tv.content, top = FilewayMediaSpace.Tv.content,
                end = FilewayMediaSpace.Tv.content, bottom = 36.dp)
                .clearAndSetSemantics {}, verticalArrangement = Arrangement.SpaceBetween) {
                Text(if (artwork == null || failed) "暂无海报" else "正在加载", Modifier.align(Alignment.End),
                    style = MediaType.secondary, color = colors.secondary)
                Text(fallbackTitle, style = MediaType.itemTitle, color = colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        } else if (!ready && !decorative) Icon(painterResource(fallbackIcon), null, Modifier.size(32.dp), tint = colors.secondary)
        if (request != null) AsyncImage(model = request, imageLoader = imageLoader,
            contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            onSuccess = { ready = true; failed = false }, onError = { ready = false; failed = true })
    }
}

@Composable
internal fun MediaBackdrop(artwork: TvMediaArtwork?, imageLoader: ImageLoader, modifier: Modifier) {
    val colors = LocalMediaColors.current
    Box(modifier) {
        if (artwork == null) {
            Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(colors.canvas, colors.surface, colors.canvas))))
            return@Box
        }
        MediaArtwork(artwork, imageLoader, Modifier.matchParentSize(), decorative = true)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to colors.canvas.copy(alpha = .98f), .42f to colors.canvas.copy(alpha = .91f),
            .65f to colors.canvas.copy(alpha = .35f), 1f to colors.canvas.copy(alpha = .08f))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to colors.canvas.copy(alpha = .25f), .4f to Color.Transparent,
            .72f to colors.canvas.copy(alpha = .38f), 1f to colors.canvas)))
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
internal fun MediaPoster(item: TvMediaCard, imageLoader: ImageLoader, onClick: () -> Unit, modifier: Modifier = Modifier,
    width: Dp = 132.dp) {
    val colors = LocalMediaColors.current
    val focus = LocalTvMediaFocusBinding.current
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MediaSurface({ focus?.onOpen?.invoke(item.id, "poster"); onClick() }, Modifier.fillMaxWidth().aspectRatio(2f / 3f)
            .tvMediaReturnFocus(item.id, "poster")
            .semantics { contentDescription = item.title }) {
            MediaArtwork(item.poster, imageLoader, Modifier.fillMaxSize(), fallbackTitle = item.title)
            item.primaryRating?.let { rating ->
                Text("${rating.source.label} ${rating.valueLabel}", Modifier.align(Alignment.TopStart)
                    .padding(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xE617191D)).padding(horizontal = 6.dp, vertical = 3.dp),
                    style = MediaType.badge, color = Color(0xFFF1C875), maxLines = 2)
            }
            item.extentLabel?.takeIf(String::isNotBlank)?.let { label ->
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .85f)))).padding(8.dp)) {
                    Text(label, style = MediaType.secondary, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(item.title, style = MediaType.itemTitle, color = colors.text,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(item.yearLabel, item.availabilityLabel).filter(String::isNotBlank).joinToString(" · "),
            style = MediaType.secondary, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun MediaShelf(
    shelf: TvMediaShelf, imageLoader: ImageLoader, onItem: (String) -> Unit,
    onRetry: ((String) -> Unit)? = null,
    onMore: (() -> Unit)? = null,
) {
    if (!tvMediaShelfVisible(shelf)) return
    val listState = key(shelf.id) { rememberLazyListState() }
    TvMediaRowLoadMore(listState, shelf.hasMore, shelf.loading, shelf.error, onMore, empty = shelf.items.isEmpty())
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MediaSectionTitle(shelf.title)
        if (shelf.error != null) MediaStatus(shelf.error, onRetry = onRetry?.let { { it(shelf.id) } }, compact = true)
        if (shelf.loading && !shelf.hasMore) MediaLoadingStatus(if (shelf.items.isEmpty()) "正在加载…" else "正在加载更多…", compact = true)
        if (shelf.items.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = mediaCardWidth(maxWidth, 128.dp, 6)
            LazyRow(state = listState, contentPadding = MediaLayout.rowPadding,
                horizontalArrangement = Arrangement.spacedBy(MediaLayout.itemGap), modifier = Modifier.focusGroup()) {
                items(shelf.items, key = { it.id }) { item -> MediaPoster(item, imageLoader, { onItem(item.id) }, width = width) }
            }
        }
        if (shelf.hasMore && onMore != null) MediaAction(if (shelf.loading) "正在加载…" else "加载更多",
            onMore, Modifier.padding(horizontal = MediaLayout.pageInset), enabled = !shelf.loading)
    }
}

@Composable
internal fun TvMediaRowLoadMore(state: LazyListState, hasMore: Boolean, loading: Boolean,
    error: String?, onMore: (() -> Unit)?, empty: Boolean = false) {
    val currentOnMore by rememberUpdatedState(onMore)
    LaunchedEffect(state, hasMore, loading, error, onMore != null, empty) {
        if (!hasMore || loading || error != null || onMore == null) return@LaunchedEffect
        if (empty && tvMediaShouldLoadEmptyPage(hasMore, loading, error)) {
            currentOnMore?.invoke()
            return@LaunchedEffect
        }
        snapshotFlow {
            (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to state.layoutInfo.totalItemsCount
        }.collect { (lastVisible, itemCount) ->
            if (tvMediaShouldLoadMore(lastVisible, itemCount, hasMore, loading, error)) currentOnMore?.invoke()
        }
    }
}

@Composable
internal fun MediaSectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    FilewayMediaSectionHeader(
        title = title,
        modifier = Modifier.padding(horizontal = MediaLayout.pageInset),
        style = FilewayMediaSectionHeaderStyle(
            titleStyle = MediaType.sectionTitle,
            titleColor = LocalMediaColors.current.text,
            minHeight = 0.dp,
        ),
    ) {
        if (action != null && onAction != null) MediaQuietAction(action, onAction, icon = Art.drawable.fileway_ic_arrow_forward)
    }
}

@Composable
internal fun MediaStatus(message: String, onRetry: (() -> Unit)? = null, compact: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = MediaLayout.pageInset, vertical = if (compact) 12.dp else 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(message, style = MediaType.body, color = LocalMediaColors.current.secondary)
        if (onRetry != null) MediaAction("重试", onRetry, icon = Art.drawable.fileway_ic_refresh)
    }
}

/** The caller keeps this in the loading branch so completion/disposal cancels the pending cue. */
@Composable
internal fun MediaLoadingStatus(message: String, compact: Boolean = false) {
    var visible by remember(message) { mutableStateOf(false) }
    LaunchedEffect(message) { delay(300); visible = true }
    if (!visible) return
    val colors = LocalMediaColors.current
    Box(Modifier.fillMaxWidth().heightIn(min = if (compact) 88.dp else 360.dp)
        .padding(horizontal = MediaLayout.pageInset, vertical = 24.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MediaLoadingRing(if (compact) 28.dp else 44.dp)
            Text(message, style = if (compact) MediaType.body else MediaType.sectionTitle, color = colors.text)
        }
    }
}

@Composable private fun MediaLoadingRing(size: Dp) {
    val colors = LocalMediaColors.current
    val rotation = rememberInfiniteTransition(label = "media-loading").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing)),
        label = "media-loading-rotation",
    )
    Canvas(Modifier.size(size).rotate(rotation.value)) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2f
        drawArc(color = colors.secondary.copy(alpha = .18f), startAngle = 0f, sweepAngle = 360f,
            useCenter = false, topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(color = colors.accent, startAngle = 0f, sweepAngle = 270f,
            useCenter = false, topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** Supply only destinations that are actually wired. Selection is not keyboard focus. */
@Composable
fun TvMediaTopBar(
    items: List<TvMediaNavigationItem>, selectedId: String?, onSelect: (String) -> Unit,
    onSearch: (() -> Unit)? = null, onSettings: (() -> Unit)? = null,
) {
    val colors = LocalMediaColors.current
    Row(Modifier.fillMaxWidth().padding(start = MediaLayout.pageInset, end = MediaLayout.pageInset, top = 28.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("栖卷", style = MediaType.pageTitle, color = colors.text)
        LazyRow(Modifier.weight(1f).focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(MediaLayout.focusGutter)) {
            items(items, key = { it.id }) { item ->
                Column {
                    if (item.id == selectedId) Text(item.label, Modifier.heightIn(min = FilewayMediaSpace.Tv.remoteTarget)
                        .padding(horizontal = 12.dp, vertical = 12.dp), style = MediaType.action, color = colors.accent)
                    else MediaQuietAction(item.label, { onSelect(item.id) }, Modifier.tvMediaReturnFocus(item.id, "navigation"))
                    if (item.id == selectedId) Box(Modifier.align(Alignment.CenterHorizontally)
                        .width(28.dp).height(2.dp).background(colors.accent, RoundedCornerShape(2.dp)))
                    else Spacer(Modifier.height(2.dp))
                }
            }
        }
        if (onSearch != null) MediaIconAction("搜索", onSearch, Art.drawable.fileway_ic_search,
            modifier = Modifier.tvMediaReturnFocus("search", "navigation"))
        if (onSettings != null) MediaIconAction("设置", onSettings, Art.drawable.fileway_ic_settings)
    }
}

/** Quiet actions retain full native input/focus behavior without creating a wall of pills. */
@Composable
internal fun MediaQuietAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: Int? = null, selected: Boolean = false, enabled: Boolean = true) {
    MediaSurface(onClick, modifier.heightIn(min = FilewayMediaSpace.Tv.remoteTarget), enabled = enabled, selected = selected,
        container = Color.Transparent, selectionContainer = false) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(24.dp))
            Text(label, style = MediaType.action)
        }
    }
}

@Composable
internal fun MediaIconAction(label: String, onClick: () -> Unit, icon: Int, modifier: Modifier = Modifier,
    enabled: Boolean = true) {
    MediaSurface(onClick, modifier.size(48.dp).semantics { contentDescription = label }, enabled = enabled,
        container = Color.Transparent) {
        Icon(painterResource(icon), null, Modifier.align(Alignment.Center).size(24.dp))
    }
}

package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.SessionContext
import io.github.kkwans.nasfilebrowser.data.MediaLibraryRepository
import io.github.kkwans.nasfilebrowser.data.PreviewLease
import io.github.kkwans.nasfilebrowser.ui.FilewayIcon
import io.github.kkwans.nasfilebrowser.ui.FilewayIconButton
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaSectionHeader
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaSectionHeaderStyle
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaSpace
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusBanner
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusKind
import io.github.kkwans.nasfilebrowser.ui.designsystem.filewayTextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException

internal data class MediaArtworkBinding(val session: SessionContext, val repository: MediaLibraryRepository)
internal val LocalMediaArtworkBinding = staticCompositionLocalOf<MediaArtworkBinding?> { null }

internal object PhoneMediaType {
    val workTitle @Composable get() = filewayTextStyle(28, 36, FontWeight.SemiBold)
    val pageTitle @Composable get() = filewayTextStyle(24, 32, FontWeight.SemiBold)
    val sectionTitle @Composable get() = filewayTextStyle(20, 28, FontWeight.Medium)
    val itemTitle @Composable get() = filewayTextStyle(16, 24, FontWeight.Medium)
    val body @Composable get() = filewayTextStyle(16, 24)
    val action @Composable get() = filewayTextStyle(14, 20, FontWeight.Medium)
    val secondary @Composable get() = filewayTextStyle(14, 20)
    val badge @Composable get() = filewayTextStyle(12, 16, FontWeight.Medium)
}

internal data class PhoneMediaGridSpec(val columns: Int, val pageInset: Dp)

internal fun phoneMediaGridSpec(width: Dp): PhoneMediaGridSpec {
    val pageInset = if (width >= 600.dp) 24.dp else 16.dp
    val columns = if (width < 600.dp) 2 else {
        val minimumPoster = if (width >= 840.dp) 160.dp else 152.dp
        val maximumColumns = if (width >= 840.dp) 6 else 4
        ((width - pageInset * 2 + 12.dp) / (minimumPoster + 12.dp)).toInt().coerceIn(2, maximumColumns)
    }
    return PhoneMediaGridSpec(columns, pageInset)
}

/** Shares the project's 350ms delayed centered ring and placeholder geometry. Requests and
 * loader are supplied by the authorized image pipeline, never built from provider URLs here. */
@Composable internal fun MediaImage(artwork: MediaArtwork?, loader: ImageLoader, modifier: Modifier,
    description: String? = null, person: Boolean = false) {
    val binding = LocalMediaArtworkBinding.current
    val context = LocalContext.current
    var leasedRequest by remember(artwork?.catalog, artwork?.endpoint, binding) { mutableStateOf<ImageRequest?>(null) }
    var leaseFailed by remember(artwork?.catalog, artwork?.endpoint, binding) { mutableStateOf(false) }
    LaunchedEffect(artwork?.catalog, artwork?.endpoint, binding) {
        val owner = binding ?: return@LaunchedEffect
        val endpoint = artwork?.catalog?.let { owner.repository.artworkEndpoint(it) } ?: artwork?.endpoint ?: return@LaunchedEffect
        var lease: PreviewLease? = null
        try {
            lease = owner.session.api.assetLease(endpoint)
            leasedRequest = ImageRequest.Builder(context).data(lease.url)
                .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build()
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { leaseFailed = true }
        finally { leasedRequest = null; runCatching { lease?.release() } }
    }
    val request = artwork?.request ?: leasedRequest
    var loaded by remember(request) { mutableStateOf(false) }
    var failed by remember(request) { mutableStateOf(false) }
    var showLoading by remember(request) { mutableStateOf(false) }
    LaunchedEffect(request, loaded, failed) {
        showLoading = false
        if (request != null && !loaded && !failed) { delay(350); showLoading = true }
    }
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Box(modifier.clip(shape).background(Brush.linearGradient(listOf(
        colors.surfaceContainerHighest, colors.surfaceContainer, colors.surfaceContainerLow)))
        .border(1.dp, colors.outlineVariant.copy(alpha = .45f), shape),
        contentAlignment = Alignment.Center) {
        if (!loaded && !showLoading && (request == null || failed || leaseFailed)) {
            Box(Modifier.size(if (person) 52.dp else 60.dp)
                .background(colors.surface.copy(alpha = .58f), CircleShape)
                .border(1.dp, colors.outlineVariant.copy(alpha = .55f), CircleShape),
                contentAlignment = Alignment.Center) {
                FilewayIcon(if (person) R.drawable.ic_person else R.drawable.ic_video, null,
                    Modifier.size(if (person) 24.dp else 28.dp),
                    tint = colors.onSurfaceVariant.copy(alpha = .72f))
            }
        }
        request?.let {
            AsyncImage(model = request, imageLoader = loader, contentDescription = description,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                onSuccess = { loaded = true; failed = false }, onError = { failed = true })
        }
        if (showLoading) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
    }
}

@Composable internal fun MediaHeading(title: String, modifier: Modifier = Modifier,
    actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    FilewayMediaSectionHeader(
        title = title,
        modifier = modifier,
        style = FilewayMediaSectionHeaderStyle(
            titleStyle = PhoneDetailType.section,
            titleColor = MaterialTheme.colorScheme.onBackground,
            minHeight = FilewayMediaSpace.Phone.touchTarget,
        ),
    ) {
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction,
            modifier = Modifier.heightIn(min = FilewayMediaSpace.Phone.touchTarget)) { Text(actionLabel, style = PhoneMediaType.action) }
    }
}

@Composable internal fun MediaProblemCard(problem: MediaProblem, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(problem.title, style = PhoneMediaType.itemTitle)
        FilewayStatusBanner(message = problem.message, kind = FilewayStatusKind.WARNING,
            actionLabel = problem.recoveryLabel, onAction = if (problem.recoveryLabel != null) onRetry else null)
    }
}

@Composable internal fun MediaLoading(label: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 48.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(16.dp))
        Text(label, style = PhoneMediaType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun MediaSearchEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilewayIcon(R.drawable.ic_search, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("搜索影片、剧集、人物", Modifier.weight(1f), style = PhoneMediaType.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun MediaSearchPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilewayIcon(R.drawable.ic_search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("搜索", style = PhoneMediaType.action, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun MediaBackdrop(item: MediaPoster, loader: ImageLoader, modifier: Modifier = Modifier,
    progress: Float? = null) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(Brush.linearGradient(listOf(
        colors.surfaceContainerHighest, colors.surfaceContainer, colors.surfaceContainerLow)))) {
        if (item.backdrop != null) {
            MediaImage(item.backdrop, loader, Modifier.fillMaxSize())
        } else {
            item.poster?.let { MediaImage(it, loader, Modifier.align(Alignment.CenterEnd)
                .fillMaxHeight().aspectRatio(2f / 3f).padding(12.dp)) }
            if (item.poster == null) Box(Modifier.align(Alignment.CenterEnd).padding(18.dp).size(48.dp)
                .background(colors.surface.copy(alpha = .52f), CircleShape), contentAlignment = Alignment.Center) {
                FilewayIcon(R.drawable.ic_video, null, Modifier.size(24.dp), tint = colors.onSurfaceVariant.copy(alpha = .58f))
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color.Transparent, Color.Transparent, Color.Black.copy(alpha = .84f)))))
        item.primaryRating?.let { rating ->
            Surface(Modifier.align(Alignment.TopEnd).padding(10.dp),
                color = colors.surface.copy(alpha = .94f), shape = MaterialTheme.shapes.small) {
                Text(rating.value, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = PhoneMediaType.badge)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 16.dp)) {
            Text(item.title, color = Color.White, style = PhoneMediaType.itemTitle,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            mediaPosterSubtitle(item)?.let { subtitle ->
                Text(subtitle, color = Color.White.copy(alpha = .78f), style = PhoneMediaType.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        MediaProgress(progress, Modifier.align(Alignment.BottomStart))
    }
}

@Composable internal fun MediaIconAction(icon: Int, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    FilewayIconButton(onClick, Modifier.size(48.dp), enabled = enabled) { FilewayIcon(icon, label) }
}

internal fun mediaPosterSubtitle(item: MediaPoster): String? =
    item.subtitle.takeIf { it.isNotBlank() && it != item.title }

@Composable internal fun MediaPosterCard(item: MediaPoster, loader: ImageLoader, onClick: () -> Unit) {
    Card(onClick = onClick, shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)) {
        Box {
            MediaImage(item.poster, loader, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            item.primaryRating?.let { rating ->
                Surface(Modifier.align(Alignment.TopEnd).padding(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .96f), shape = MaterialTheme.shapes.small) {
                    Text(rating.value, Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        .semantics { contentDescription = listOfNotNull(rating.source, rating.metric, rating.value).joinToString(" ") },
                        style = PhoneMediaType.badge, maxLines = 1)
                }
            }
        }
        Text(item.title, Modifier.padding(top = 8.dp).fillMaxWidth(), style = PhoneMediaType.itemTitle,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        mediaPosterSubtitle(item)?.let { subtitle ->
            Text(subtitle, style = PhoneMediaType.secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        item.availability?.let { Text(it, style = PhoneMediaType.badge,
            color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable internal fun MediaProgress(progress: Float?, modifier: Modifier = Modifier) {
    // Unknown or invalid durations must not turn into a made-up percentage.
    if (progress != null && progress.isFinite()) LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) }, modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
}

@Composable internal fun MediaPaging(section: String, loading: Boolean, hasMore: Boolean,
    onAction: (PhoneMediaAction) -> Unit) {
    if (loading) MediaLoading("正在加载")
    else if (hasMore) TextButton(onClick = { onAction(PhoneMediaAction.LoadMore(section)) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("加载更多", style = PhoneMediaType.action) }
}

internal fun phoneMediaShouldLoadMore(lastVisibleIndex: Int, itemCount: Int, hasMore: Boolean,
    loading: Boolean, problem: Boolean = false): Boolean =
    hasMore && !loading && !problem && itemCount > 0 && lastVisibleIndex >= 0 &&
        lastVisibleIndex >= itemCount - 7

@Composable internal fun PhoneMediaGridLoadMore(state: LazyGridState, hasMore: Boolean, loading: Boolean,
    problem: Boolean, enabled: Boolean = true, onLoadMore: () -> Unit) {
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state, hasMore, loading, problem, enabled) {
        if (!enabled || !hasMore || loading || problem) return@LaunchedEffect
        snapshotFlow {
            (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to state.layoutInfo.totalItemsCount
        }.collect { (lastVisible, itemCount) ->
            if (phoneMediaShouldLoadMore(lastVisible, itemCount, hasMore, loading, problem)) currentOnLoadMore()
        }
    }
}

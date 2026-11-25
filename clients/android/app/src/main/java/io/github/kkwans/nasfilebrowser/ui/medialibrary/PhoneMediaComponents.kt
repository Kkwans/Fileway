package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/** Shares the project's 350ms delayed centered ring and placeholder geometry. Requests and
 * loader are supplied by the authorized image pipeline, never built from provider URLs here. */
@Composable internal fun MediaImage(artwork: MediaArtwork?, loader: ImageLoader, modifier: Modifier,
    description: String? = null, person: Boolean = false, fallbackTitle: String? = null) {
    val binding = LocalMediaArtworkBinding.current
    val context = LocalContext.current
    var leasedRequest by remember(artwork?.catalog, binding) { mutableStateOf<ImageRequest?>(null) }
    var leaseFailed by remember(artwork?.catalog, binding) { mutableStateOf(false) }
    LaunchedEffect(artwork?.catalog, binding) {
        val asset = artwork?.catalog ?: return@LaunchedEffect
        val owner = binding ?: return@LaunchedEffect
        var lease: PreviewLease? = null
        try {
            lease = owner.session.api.assetLease(owner.repository.artworkEndpoint(asset))
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
    Box(modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center) {
        if (!loaded && !fallbackTitle.isNullOrBlank()) {
            val colors = MaterialTheme.colorScheme
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(
                colors.surfaceContainerHighest, colors.surfaceContainer, colors.surfaceContainerLow))))
            Column(Modifier.fillMaxSize().padding(12.dp).clearAndSetSemantics {},
                verticalArrangement = Arrangement.SpaceBetween) {
                val unavailable = artwork == null || artwork.request == null && artwork.catalog == null || failed || leaseFailed
                Text(if (unavailable) "暂无海报" else "正在加载", style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant)
                Text(fallbackTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        } else if (!loaded) FilewayIcon(if (person) R.drawable.ic_person else R.drawable.ic_video, null,
            Modifier.size(if (showLoading) 18.dp else 32.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (showLoading) .22f else .4f))
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
    Row(modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(label, style = PhoneMediaType.secondary)
    }
}

@Composable internal fun MediaIconAction(icon: Int, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    FilewayIconButton(onClick, Modifier.size(48.dp), enabled = enabled) { FilewayIcon(icon, label) }
}

@Composable internal fun MediaPosterCard(item: MediaPoster, loader: ImageLoader, onClick: () -> Unit) {
    Card(onClick = onClick, shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)) {
        Box {
            MediaImage(item.poster, loader, Modifier.fillMaxWidth().aspectRatio(2f / 3f), fallbackTitle = item.title)
            item.primaryRating?.let { rating ->
                Surface(Modifier.align(Alignment.TopEnd).padding(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .96f), shape = MaterialTheme.shapes.small) {
                    Text(listOfNotNull(rating.source, rating.metric, rating.value).joinToString(" "),
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = PhoneMediaType.badge)
                }
            }
        }
        Text(item.title, Modifier.padding(top = 8.dp).fillMaxWidth(), style = PhoneMediaType.itemTitle,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (item.subtitle.isNotBlank()) Text(item.subtitle, style = PhoneMediaType.secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

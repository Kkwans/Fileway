package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.ui.FilewayIcon
import io.github.kkwans.nasfilebrowser.ui.FilewayIconButton
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusBanner
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusKind
import kotlinx.coroutines.delay

/** Shares the project's 350ms delayed centered ring and placeholder geometry. Requests and
 * loader are supplied by the authorized image pipeline, never built from provider URLs here. */
@Composable internal fun MediaImage(artwork: MediaArtwork?, loader: ImageLoader, modifier: Modifier,
    description: String? = null, person: Boolean = false) {
    var loaded by remember(artwork?.request) { mutableStateOf(false) }
    var failed by remember(artwork?.request) { mutableStateOf(false) }
    var showLoading by remember(artwork?.request) { mutableStateOf(false) }
    LaunchedEffect(artwork?.request, loaded, failed) {
        showLoading = false
        if (artwork?.request != null && !loaded && !failed) { delay(350); showLoading = true }
    }
    Box(modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center) {
        if (!loaded) FilewayIcon(if (person) R.drawable.ic_person else R.drawable.ic_video, null,
            Modifier.size(if (showLoading) 18.dp else 32.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (showLoading) .22f else .4f))
        artwork?.request?.let { request ->
            AsyncImage(model = request, imageLoader = loader, contentDescription = description,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                onSuccess = { loaded = true; failed = false }, onError = { failed = true })
        }
        if (showLoading) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
    }
}

@Composable internal fun MediaHeading(title: String, modifier: Modifier = Modifier,
    actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(actionLabel) }
    }
}

@Composable internal fun MediaProblemCard(problem: MediaProblem, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(problem.title, style = MaterialTheme.typography.titleMedium)
        FilewayStatusBanner(message = problem.message, kind = FilewayStatusKind.WARNING,
            actionLabel = problem.recoveryLabel, onAction = if (problem.recoveryLabel != null) onRetry else null)
    }
}

@Composable internal fun MediaLoading(label: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable internal fun MediaIconAction(icon: Int, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    FilewayIconButton(onClick, Modifier.size(48.dp), enabled = enabled) { FilewayIcon(icon, label) }
}

@Composable internal fun MediaPosterCard(item: MediaPoster, loader: ImageLoader, onClick: () -> Unit) {
    Card(onClick = onClick, shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)) {
        Box {
            MediaImage(item.poster, loader, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            item.primaryRating?.let { rating ->
                Surface(Modifier.align(Alignment.BottomStart).padding(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .96f), shape = MaterialTheme.shapes.small) {
                    Text(listOfNotNull(rating.source, rating.metric, rating.value).joinToString(" "),
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(item.title, Modifier.padding(top = 8.dp).fillMaxWidth(), style = MaterialTheme.typography.titleMedium,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(item.subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        item.availability?.let { Text(it, style = MaterialTheme.typography.labelSmall,
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
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("加载更多") }
}

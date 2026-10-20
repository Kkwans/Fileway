package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.Image as CoilImage
import coil3.compose.asPainter
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.mediaKey
import io.github.kkwans.nasfilebrowser.app.mediaKind
import io.github.kkwans.nasfilebrowser.app.MediaKind
import io.github.kkwans.nasfilebrowser.data.PreviewLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

internal val LocalNaturalPreviewAspects = staticCompositionLocalOf<MutableMap<String, Float>?> { null }

/** Every layout consumes the same uncropped bytes; only its viewport differs. */
@Composable internal fun MediaThumbnail(model: ClientModel, file: ResourceRef, modifier: Modifier, showStatusText: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop, naturalAspect: Boolean = false) {
    val client by model.state.collectAsStateWithLifecycle()
    val cache by model.cache.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val aspects = LocalNaturalPreviewAspects.current
    val previewKey = model.thumbnailKey(file)
    val cacheKey = previewKey + "/contain"
    val loader = model.previewImageLoader
    key(cache.revision, client.previewScope, file.mediaKey, file.size, file.modified) {
        var decoded by remember { mutableStateOf<CoilImage?>(loader.memoryCache?.get(MemoryCache.Key(cacheKey))?.image) }
        var failed by remember { mutableStateOf(false) }
        var aspect by remember { mutableFloatStateOf(decoded?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }
            ?: model.cache.previewAspect(previewKey) ?: aspects?.get(previewKey) ?: if (file.previewWidth > 0 && file.previewHeight > 0)
                file.previewWidth.toFloat() / file.previewHeight else if (file.mediaKind() == MediaKind.VIDEO) 16f / 9f else 1f) }
        var showLoading by remember { mutableStateOf(false) }
        LaunchedEffect(decoded, failed) { showLoading = false; if (decoded == null && !failed) { delay(350); showLoading = true } }
        LaunchedEffect(Unit) {
            if (decoded != null) return@LaunchedEffect
            var owned: PreviewLease? = null
            try {
                if (client.previewScope.isEmpty() && file.downloadId.isEmpty()) { failed = true; return@LaunchedEffect }
                owned = model.thumbnailPreview(file)
                val request = ImageRequest.Builder(context).data(owned.url).size(512, 512)
                    .memoryCacheKey(cacheKey).diskCacheKey(cacheKey)
                    .diskCachePolicy(if (cache.settings.thumbnailMB == 0L || model.isTemporaryContent(file)) CachePolicy.DISABLED else CachePolicy.ENABLED).build()
                val loaded = (loader.execute(request) as? SuccessResult)?.image
                if (loaded == null || loaded.width <= 0 || loaded.height <= 0) failed = true
                else {
                    val ratio = loaded.width.toFloat() / loaded.height
                    model.cache.rememberPreviewAspect(previewKey, ratio); aspects?.put(previewKey, ratio)
                    // Publish geometry and pixels together; never draw the image into the stale placeholder frame.
                    aspect = ratio; decoded = loaded; failed = false
                }
                awaitCancellation()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { runCatching { owned?.release() } }
        }
        val shape = RoundedCornerShape(12.dp)
        val frame = if (naturalAspect) modifier.aspectRatio(aspect.coerceIn(.2f, 5f)) else modifier
        BoxWithConstraints(frame.clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)
            .border(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f), shape).semantics {
                contentDescription = "${file.name} 预览"
                stateDescription = if (decoded != null) "预览已加载" else if (failed) "暂无预览" else "预览加载中"
            }, contentAlignment = Alignment.Center) {
            val compact = minOf(maxWidth, maxHeight) < 48.dp
            val ringSize = if (compact) 24.dp else 32.dp
            val placeholderSize = if (showLoading) { if (compact) 12.dp else 18.dp } else { if (compact) 24.dp else 36.dp }
            if (decoded == null) Icon(painterResource(if (file.mediaKind() == MediaKind.VIDEO) R.drawable.ic_video else R.drawable.ic_image),
                null, Modifier.size(placeholderSize), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (showLoading) .22f else .32f))
            val image = decoded
            if (image != null) Image(image.asPainter(context), null, Modifier.fillMaxSize(), contentScale = contentScale)
            if (showLoading && decoded == null && !failed) CircularProgressIndicator(Modifier.align(Alignment.Center).size(ringSize), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = .8f))
        }
    }
}

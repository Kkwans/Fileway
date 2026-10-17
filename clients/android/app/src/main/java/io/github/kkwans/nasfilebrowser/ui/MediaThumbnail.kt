package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.mediaKey
import io.github.kkwans.nasfilebrowser.data.PreviewLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

internal val LocalNaturalPreviewAspects = staticCompositionLocalOf<MutableMap<String, Float>?> { null }

/** NAS generates the preview; Coil fetches/decodes only the local capability. */
@Composable internal fun MediaThumbnail(model: ClientModel, file: ResourceRef, modifier: Modifier, showStatusText: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop, naturalAspect: Boolean = false) {
    val client by model.state.collectAsStateWithLifecycle()
    val cache by model.cache.state.collectAsStateWithLifecycle()
    val aspectCache = LocalNaturalPreviewAspects.current
    val aspectKey = remember(client.previewScope, file.mediaKey, file.size, file.modified) { model.thumbnailKey(file) }
    key(naturalAspect, cache.revision, client.previewScope, file.mediaKey, file.size, file.modified) {
        var asset by remember { mutableStateOf<PreviewLease?>(null) }
        var phase by remember { mutableStateOf("预览加载中") }
        val aspect = remember(file.previewWidth, file.previewHeight) {
            (if (file.previewWidth > 0 && file.previewHeight > 0)
                (file.previewWidth.toFloat() / file.previewHeight).coerceIn(.2f, 5f) else aspectCache?.get(aspectKey) ?: model.cache.previewAspect(aspectKey) ?:
                if (file.type == "video") 16f / 9f else 1f).also { aspectCache?.put(aspectKey, it) }
        }
        var showLoading by remember { mutableStateOf(false) }
        LaunchedEffect(phase) { showLoading = false; if (phase == "预览加载中") { delay(350); showLoading = true } }
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            var owned: PreviewLease? = null
            try {
                if (client.previewScope.isEmpty() && file.downloadId.isEmpty()) { phase = "暂无预览"; return@LaunchedEffect }
                owned = model.preview(file, contain = naturalAspect)
                asset = owned
                awaitCancellation()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { phase = "暂无预览" }
            finally { owned?.let { runCatching { it.release() } } }
        }
        val frame = if (naturalAspect) modifier.aspectRatio(aspect) else modifier
        Box(frame.background(MaterialTheme.colorScheme.surface).semantics {
            contentDescription = "${file.name} 预览"
            stateDescription = phase
        }, contentAlignment = Alignment.Center) {
            val lease = asset
            if (lease != null) {
                val request = remember(lease.url) { ImageRequest.Builder(context.applicationContext)
                    .data(lease.url).diskCacheKey(model.thumbnailKey(file) + (if (naturalAspect) "/contain" else "/crop")).memoryCacheKey(model.thumbnailKey(file) + (if (naturalAspect) "/contain" else "/crop"))
                    .diskCachePolicy(if (cache.settings.thumbnailMB == 0L) CachePolicy.DISABLED else CachePolicy.ENABLED).size(512, 512).build() }
                AsyncImage(model = request, imageLoader = model.previewImageLoader, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = contentScale,
                    onSuccess = { result ->
                        val loaded = result.result.image
                        if (naturalAspect && loaded.width > 0 && loaded.height > 0) {
                            val ratio = (loaded.width.toFloat() / loaded.height).coerceIn(.2f, 5f)
                            model.cache.rememberPreviewAspect(aspectKey, ratio)
                        }
                        phase = "预览已加载"
                    }, onError = { phase = "暂无预览" })
            }
            if (showLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f))
            if (phase == "暂无预览") {
                Icon(painterResource(if (file.type == "image" || file.name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")) R.drawable.ic_image else R.drawable.art_play), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (showStatusText && phase == "暂无预览") Text(phase, modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

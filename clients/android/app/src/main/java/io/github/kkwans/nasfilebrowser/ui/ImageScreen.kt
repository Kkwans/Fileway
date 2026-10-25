package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.memory.MemoryCache
import coil3.BitmapImage
import coil3.Image as CoilImage
import coil3.compose.asPainter
import coil3.network.HttpException
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.mediaKey
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import kotlin.math.abs

@Composable internal fun ImageScreen(model: ClientModel, file: ResourceRef) {
    FilewayTheme(darkTheme = true) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = FilewayTheme.status.mediaContent) {
            ImageViewerContent(model, file)
        }
    }
}

@Composable private fun ImageViewerContent(model: ClientModel, file: ResourceRef) {
    val media = FilewayTheme.status
    val client by model.state.collectAsStateWithLifecycle()
    val queue = client.mediaQueue
    key(client.previewScope, queue?.snapshotId ?: file.mediaKey) {
        val entries = queue?.items ?: listOf(file)
        val pager = rememberPagerState(initialPage = queue?.index ?: 0) { entries.size }
        val previews = remember { Semaphore(2) }
        var chrome by rememberSaveable { mutableStateOf(true) }
        var details by remember { mutableStateOf<ResourceRef?>(null) }
        var originalOverrides by rememberSaveable { mutableStateOf(emptySet<String>()) }
        val zoomedPages = remember { mutableStateMapOf<String, Boolean>() }
        val zoomed = zoomedPages[entries[pager.settledPage].mediaKey] == true
        val current = entries[pager.currentPage.coerceIn(entries.indices)]
        LaunchedEffect(queue?.index) {
            queue?.index?.let { if (pager.currentPage != it) pager.animateScrollToPage(it) }
        }
        LaunchedEffect(pager.settledPage) {
            model.navigateMedia(pager.settledPage)
        }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        var saving by remember { mutableStateOf(false) }
        Scaffold(containerColor = Color.Black) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).background(Color.Black)) {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1,
                    userScrollEnabled = !zoomed, key = { entries[it].mediaKey }) { index ->
                    ImagePage(model, entries[index], active = index == pager.settledPage,
                        sourceScope = client.previewScope,
                        previewEnabled = abs(index - pager.settledPage) <= 1, previews = previews, chrome = chrome,
                        onClick = { chrome = !chrome }, onZoom = { zoomedPages[entries[index].mediaKey] = it }, forceOriginal = entries[index].mediaKey in originalOverrides)
                }
                if (chrome) {
                    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = .55f)), verticalAlignment = Alignment.CenterVertically) {
                        FilewayIconButton(onClick = model::closeImage) { FilewayIcon(R.drawable.ic_arrow_back, "返回文件", tint = Color.White) }
                        Text(current.name, Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        FilewayIconButton({ details = current }) { FilewayIcon(R.drawable.ic_info, "图片详情", tint = Color.White) }
                        if (!model.isTemporaryContent(current)) FilewayIconButton({ saving = true; scope.launch {
                            val message = try { model.saveImageToAlbum(current); "已保存到相册" }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { error.message ?: "保存失败，请重试" }
                            finally { saving = false }
                            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                        } }, enabled = !saving) { FilewayIcon(R.drawable.ic_download, "保存原图到相册", tint = Color.White) }
                    }
                    Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp), color = Color.Black.copy(alpha = .5f),
                        contentColor = Color.White, shape = MaterialTheme.shapes.medium) {
                        Text("${pager.currentPage + 1} / ${entries.size}", Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        details?.let { FileDetailsDialog(it, model = model, actions = {
            FileActions(model, it) { details = null }
        }, additionalActions = {
            if (it.downloadId.isEmpty() && !model.isTemporaryContent(it)) FilewayTextButton({ originalOverrides = originalOverrides + it.mediaKey; details = null }) { Text("加载原图") }
        }, onDismiss = { details = null }) }
    }
}

@Composable private fun ImagePage(model: ClientModel, file: ResourceRef, active: Boolean, previewEnabled: Boolean,
    sourceScope: String, previews: Semaphore, chrome: Boolean, onClick: () -> Unit, onZoom: (Boolean) -> Unit, forceOriginal: Boolean = false) {
    val context = LocalContext.current
    val media = FilewayTheme.status
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val cache by model.cache.state.collectAsStateWithLifecycle()
    val local = file.downloadId.isNotEmpty()
    val temporarySource = model.isTemporaryContent(file)
    val account = if (local) "local-downloads" else model.cacheAccount()
    val memoryOriginal = remember(account, file.mediaKey, file.size, file.modified, cache.revision) {
        val key = model.cache.key(account, file.mediaKey, "${file.size}/${file.modified}/${ImageQuality.ORIGINAL.name}")
        model.cache.imageLoader.value.memoryCache?.get(MemoryCache.Key(key))?.image
    }
    var originalRequested by rememberSaveable(file.mediaKey) { mutableStateOf(memoryOriginal != null) }
    var originalCached by remember(file.mediaKey) { mutableStateOf(memoryOriginal != null) }
    var cacheChecked by remember(file.mediaKey) { mutableStateOf(local || memoryOriginal != null) }
    LaunchedEffect(file.mediaKey, cache.revision) {
        try { originalCached = memoryOriginal != null || !local && model.hasCachedOriginal(file); if (originalCached) originalRequested = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { originalCached = memoryOriginal != null } // Main image read owns the recoverable error surface.
        finally { cacheChecked = true }
    }
    val quality = if (local || originalRequested || forceOriginal) ImageQuality.ORIGINAL else cache.settings.imageQuality
    val activeNow by rememberUpdatedState(active)
    var preview by remember { mutableStateOf<CoilImage?>(memoryOriginal) }
    var previewStatus by remember { mutableStateOf("正在读取预览") }
    val latestPreview by rememberUpdatedState(preview)
    var asset by remember { mutableStateOf<ImagePageAsset?>(null) }
    var readingLease by remember { mutableStateOf<PreviewLease?>(null) }
    var readProgress by remember { mutableStateOf<Float?>(null) }
    var current by remember { mutableStateOf(file) }
    var attempt by remember { mutableIntStateOf(0) }
    var requestToken by remember { mutableLongStateOf(0) }
    var canceled by rememberSaveable { mutableStateOf(false) }
    var forceWorkingFile by remember(quality, cache.revision) { mutableStateOf(false) }
    var fetched by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf("正在读取图片信息") }
    val imageState = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 8f)))

    LaunchedEffect(previewEnabled, cacheChecked, originalCached, file.mediaKey, cache.revision, attempt) {
        if (!previewEnabled || !cacheChecked || originalCached || local) return@LaunchedEffect
        previewStatus = "正在读取预览"
        try {
            previews.withPermit {
                val lease = model.preview(file, contain = true)
                try {
                    val request = ImageRequest.Builder(context).data(lease.url).size(512, 512)
                        .memoryCacheKey(model.thumbnailKey(file) + "/contain").diskCacheKey(model.thumbnailKey(file) + "/contain")
                        .diskCachePolicy(if (cache.settings.thumbnailMB == 0L) CachePolicy.DISABLED else CachePolicy.ENABLED).build()
                    val result = model.previewImageLoader.execute(request)
                    if (result is SuccessResult) {
                        if ((preview?.let { it.width.toLong() * it.height } ?: 0) <= result.image.width.toLong() * result.image.height) preview = result.image
                        previewStatus = "预览图"
                    } else previewStatus = "暂无预览"
                } finally { lease.release() }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            previewStatus = if (error is ServiceException && error.status == 404) "暂无预览" else "预览暂不可用"
        }
    }

    LaunchedEffect(previewEnabled, cacheChecked, canceled, quality, cache.revision, forceWorkingFile, attempt) {
        val token = ++requestToken
        fetched = false; failure = null
        if (!previewEnabled || !cacheChecked || canceled) return@LaunchedEffect
        var owned: PreviewLease? = null
        var temporary: TemporaryImage? = null
        phase = "正在读取图片信息"
        try {
            val result = model.image(file, quality)
            owned = result.first; current = result.second
            readingLease = owned
            phase = if (local) "正在打开本机图片" else if (quality == ImageQuality.ORIGINAL) "正在读取原图" else "正在读取图片"
            if (!local && !owned.url.startsWith("file:") && (cache.settings.imageMB == 0L || forceWorkingFile)) {
                temporary = TemporaryImages.read(context, owned, if (quality == ImageQuality.ORIGINAL || quality == ImageQuality.HIGH) current.size else 0)
                if (!temporarySource && cache.settings.imageMB > 0 && quality == ImageQuality.ORIGINAL) {
                    val key = model.cache.key(account, current.mediaKey, "${current.size}/${current.modified}/${quality.name}")
                    model.cache.cacheImageFile(CachedImage(key, account, current.name, current.path, current.wirePath, current.modified, current.size, 0), temporary.file)
                }
            }
            asset = ImagePageAsset(owned, temporary, quality, cache.revision, attempt, token)
            awaitCancellation()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // A prefetched neighbour can become active without restarting this
            // effect. Keep its error so that page exposes retry instead of an
            // endless spinner; only the active page renders the error surface.
            if (requestToken == token) failure = imageFailure(error)
        } finally {
            if (readingLease === owned) readingLease = null
            if (asset?.lease === owned) asset = null
            withContext(NonCancellable + Dispatchers.IO) { temporary?.close() }
            owned?.release()
        }
    }
    LaunchedEffect(readingLease, active, fetched, canceled, failure) {
        readProgress = null
        val source = readingLease ?: return@LaunchedEffect
        if (local || source.url.startsWith("file:") || source.url.startsWith("content:")) return@LaunchedEffect
        if (!active || fetched || canceled || failure != null) return@LaunchedEffect
        while (true) {
            try {
                val stats = NativeTransport.call(JSONObject().put("op", "lease_stats").put("session", source.scope).put("url", source.url)) as JSONObject
                val total = stats.getLong("currentReadTotal")
                readProgress = if (total > 0) (stats.getLong("currentReadBytes").toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null
            } catch (cancelledRequest: CancellationException) { throw cancelledRequest }
            catch (_: Exception) { readProgress = null }
            delay(250)
        }
    }
    LaunchedEffect(active, imageState.zoomableState) {
        if (active) snapshotFlow { (imageState.zoomableState.zoomFraction ?: 0f) > .001f }.collect(onZoom)
    }
    val cacheKey = model.cache.key(account, current.mediaKey, "${current.size}/${current.modified}/${quality.name}")
    // A quality/retry change is composed before the previous effect finishes.
    // Never load its old lease under the new request's cache key or callbacks.
    val loaded = asset?.takeIf { !canceled && it.quality == quality &&
        it.cacheRevision == cache.revision && it.attempt == attempt && it.token == requestToken }
    val lease = loaded?.lease
    val token = requestToken
    val imageLoader = model.cache.imageLoader.value
    val data = loaded?.working?.file ?: lease?.url?.let { if (local || it.startsWith("file:")) android.net.Uri.parse(it) else it }
    val request = remember(data, cacheKey, token, attempt, quality, imageLoader) {
        ImageRequest.Builder(context).data(data).placeholder { latestPreview }.error { latestPreview }.fallback { latestPreview }
            .memoryCacheKey(cacheKey).diskCacheKey(cacheKey)
            .diskCachePolicy(if (temporarySource || cache.settings.imageMB == 0L) CachePolicy.DISABLED else CachePolicy.ENABLED)
            .apply { quality.pixels?.let { size(it, it) } }
            .listener(onSuccess = { _, result ->
                if (data != null) scope.launch {
                    if (requestToken != token) return@launch
                    if (data is String && result.diskCacheKey == null && result.image is BitmapImage && quality == ImageQuality.ORIGINAL) {
                        preview = result.image
                        forceWorkingFile = true
                    } else {
                        fetched = true; failure = null
                        if (quality != ImageQuality.ORIGINAL && result.image is BitmapImage) preview = result.image
                        if (data is String && !temporarySource) try {
                            model.cache.recordImage(CachedImage(cacheKey, account, current.name, current.path, current.wirePath, current.modified, current.size, 0))
                        } catch (error: Exception) { if (error is CancellationException) throw error }
                    }
                }
            }, onError = { _, result ->
                if (data != null) scope.launch { if (requestToken == token) failure = imageFailure(result.throwable) }
            }).build()
    }
    val ready = fetched && imageState.isImageDisplayed
    var recordedToken by remember(sourceScope, file.mediaKey) { mutableLongStateOf(-1L) }
    LaunchedEffect(active, ready, sourceScope, file.mediaKey, token) {
        if (activeNow && ready && fetched && imageState.isImageDisplayed && requestToken == token && !local && !canceled && failure == null && recordedToken != token) {
            model.recordRecentAccess(current, sourceScope)
            recordedToken = token
        }
    }
    val message = when {
        failure != null -> failure!! + if (preview != null) " · 仍可查看预览" else ""
        canceled -> if (preview != null) "已保留预览图" else "已取消读取"
        !ready -> if (preview != null) "预览图 · $phase" else phase
        !imageState.isImageDisplayedInFullQuality -> "正在加载细节"
        else -> if (quality in setOf(ImageQuality.LOW, ImageQuality.MEDIUM) &&
            file.name.substringAfterLast('.').lowercase(java.util.Locale.ROOT) in setOf("gif", "webp"))
            "预览图 · 如有动画请查看原图" else quality.label
    }
    Box(Modifier.fillMaxSize().semantics { contentDescription = "图片画面"; stateDescription = message }, contentAlignment = Alignment.Center) {
        if (!imageState.isImageDisplayed && !imageState.isPlaceholderDisplayed) {
            preview?.let { Image(it.asPainter(context), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            if (preview == null) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilewayIcon(R.drawable.ic_image, "图片预览", Modifier.size(36.dp), tint = media.mediaSecondary)
                Text(previewStatus, color = media.mediaSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        ZoomableAsyncImage(request, file.name, Modifier.fillMaxSize(), state = imageState, imageLoader = imageLoader,
            contentScale = ContentScale.Fit, onClick = { onClick() })
        var showLoading by remember { mutableStateOf(false) }
        LaunchedEffect(active, ready, failure, canceled) {
            showLoading = false
            if (active && !ready && failure == null && !canceled) { delay(350); showLoading = true }
        }
        if (showLoading) Surface(Modifier.align(Alignment.Center), color = Color.Black.copy(alpha = .35f), shape = MaterialTheme.shapes.medium) {
            Box(Modifier.padding(14.dp)) {
                val progress = readProgress
                if (progress == null) CircularProgressIndicator(Modifier.size(30.dp), color = Color.White, strokeWidth = 2.dp)
                else CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(30.dp), color = Color.White, strokeWidth = 2.dp)
            }
        }
        if (active && (failure != null || canceled)) Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp, start = 16.dp, end = 16.dp),
            color = Color.Black.copy(alpha = .65f), contentColor = Color.White, shape = MaterialTheme.shapes.medium) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                FilewayTextButton({ canceled = false; failure = null; attempt++ }) { Text("重试", color = Color.White) }
            }
        }
    }
}

private data class ImagePageAsset(val lease: PreviewLease, val working: TemporaryImage?, val quality: ImageQuality,
    val cacheRevision: Int, val attempt: Int, val token: Long)

private fun imageFailure(error: Throwable): String = when (error) {
    is ServiceException -> imageHttpFailure(error.status)
    is HttpException -> imageHttpFailure(error.response.code)
    is ImageWorkingLimitException -> error.message.orEmpty()
    is java.net.SocketTimeoutException -> "图片读取超时，可保留预览或重试"
    is java.net.ConnectException, is java.net.UnknownHostException -> "无法连接图片来源，请检查连接后重试"
    is java.io.EOFException, is java.net.ProtocolException -> "图片数据未完整接收，请重新加载"
    is android.graphics.ImageDecoder.DecodeException -> when (error.error) {
        android.graphics.ImageDecoder.DecodeException.SOURCE_INCOMPLETE -> "图片数据不完整，请重新加载"
        android.graphics.ImageDecoder.DecodeException.SOURCE_EXCEPTION -> "图片读取中断，请重试"
        else -> "图片数据损坏或格式不受支持，可保留预览或选择其他图片"
    }
    else -> "图片无法加载，请检查网络或文件格式后重试"
}

private fun imageHttpFailure(status: Int): String = when (status) {
    401 -> "登录已过期，请重新连接服务器"
    403 -> "没有读取这张图片的权限"
    404 -> "图片已不存在"
    410 -> "图片已被移除"
    429 -> "图片请求过于频繁，请稍后重试"
    502, 503, 504 -> "图片服务暂不可用，请稍后重试"
    else -> "图片读取失败"
}

package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.asPainter
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.CachedImage
import io.github.kkwans.nasfilebrowser.data.CachedImageLease
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import kotlinx.coroutines.*
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage

private enum class CacheImageOrder(val label: String) { NEWEST("时间：新到旧"), LARGEST("大小：大到小"), SMALLEST("大小：小到大") }

/** Local cache only. A pinned Coil snapshot stays alive for each displayed image. */
@Composable internal fun CacheGalleryScreen(model: ClientModel) {
    val cache by model.cache.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var images by remember { mutableStateOf(emptyList<CachedImage>()) }
    var order by remember { mutableStateOf(CacheImageOrder.NEWEST) }
    var sorting by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<List<CachedImage>?>(null) }
    var initialPage by remember { mutableIntStateOf(0) }
    fun toast(message: String) { android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show() }
    LaunchedEffect(refresh, cache.revision) {
        busy = true; error = null
        try {
            model.cache.awaitReady()
            val loaded = model.cache.cachedImages()
            images = loaded; selected = selected.intersect(loaded.map { it.key }.toSet())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "无法读取本机缓存，请刷新" }
        finally { busy = false }
    }
    val ordered = remember(images, order) { when (order) {
        CacheImageOrder.NEWEST -> images.sortedWith(compareByDescending<CachedImage> { it.cachedAt }.thenBy { it.key })
        CacheImageOrder.LARGEST -> images.sortedWith(compareByDescending<CachedImage> { it.bytes }.thenBy { it.key })
        CacheImageOrder.SMALLEST -> images.sortedWith(compareBy<CachedImage> { it.bytes }.thenBy { it.key })
    } }
    val snapshot = viewing
    if (snapshot != null) {
        CachedImageViewer(model, snapshot, initialPage) { viewing = null }
        return
    }
    BackHandler(selecting) { selecting = false; selected = emptySet() }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        FilewayRefresh(busy, { if (!busy) refresh++ }, Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
Column(Modifier.fillMaxSize()) {
            FilewayTopAppBar(if (selecting) "已选 ${selected.size} 张" else "图片缓存", leadingIcon = R.drawable.ic_arrow_back,
                leadingDescription = "返回我的", onLeadingClick = { model.back() }, actions = {
                    if (selecting) {
                        FilewayIconButton({ selected = if (selected.size == images.size) emptySet() else images.map { it.key }.toSet() }, enabled = !busy) {
                            FilewayIcon(R.drawable.ic_check, "全选或取消全选", Modifier.size(22.dp))
                        }
                        FilewayIconButton({
                            val targets = images.filter { it.key in selected }
                            if (targets.isNotEmpty()) {
                                busy = true
                                scope.launch {
                                    try {
                                        model.cache.removeImages(targets)
                                        selected = emptySet(); selecting = false; refresh++; toast("已删除 ${targets.size} 张缓存图片")
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { error = "删除未完成，请刷新后核对"; busy = false }
                                }
                            }
                        }, enabled = !busy && selected.isNotEmpty()) { FilewayIcon(R.drawable.ic_trash, "删除选中缓存", tint = MaterialTheme.colorScheme.error) }
                        FilewayIconButton({ selecting = false; selected = emptySet() }, enabled = !busy) { FilewayIcon(R.drawable.ic_close, "退出多选") }
                    } else {
                        Box {
                            FilewayIconButton({ sorting = true }) { FilewayIcon(R.drawable.ic_sort, "缓存排序") }
                            FilewayMenu(sorting, { sorting = false }, items = CacheImageOrder.entries.map {
                                FilewayMenuEntry(it.name, it.label, selected = it == order)
                            }, selection = FilewayMenuSelection.Single, onItemClick = { item ->
                                CacheImageOrder.entries.firstOrNull { it.name == item.key }?.let { order = it }
                                sorting = false
                            })
                        }
                        FilewayIconButton({ selecting = true }, enabled = !busy && images.isNotEmpty()) { FilewayIcon(R.drawable.ic_check, "多选缓存图片") }
                    }
                })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            if (ordered.isEmpty() && !busy) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("本机还没有图片缓存", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(3), Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalItemSpacing = 6.dp) {
                items(ordered, key = { it.key }) { image ->
                    val isSelected = image.key in selected
                    val ratio = if (image.width > 0 && image.height > 0) (image.width.toFloat() / image.height).coerceIn(.2f, 5f) else 1f
                    Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(MaterialTheme.shapes.medium)
                        .then(if (image.key in selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else Modifier)
                        .semantics { this.selected = isSelected; contentDescription = image.name }
                        .combinedClickable(enabled = !busy, role = if (selecting) Role.Checkbox else Role.Button, onClick = {
                            if (selecting) selected = if (image.key in selected) selected - image.key else selected + image.key
                            else { initialPage = ordered.indexOfFirst { it.key == image.key }; viewing = ordered.toList() }
                        }, onLongClick = { selecting = true; selected = selected + image.key })) {
                        CachedImageContent(model, image, image.account, viewer = false)
                    }
                }
            }
        }
}

    }
}

@Composable private fun CachedImageViewer(model: ClientModel, images: List<CachedImage>, initial: Int, onBack: () -> Unit) {
    val pager = rememberPagerState(initialPage = initial.coerceIn(images.indices), pageCount = { images.size })
    val zoomed = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var saving by remember { mutableStateOf(false) }
    val registerDarkCanvas = LocalFilewayDarkCanvasOwner.current
    val canvasOwner = remember { Any() }
    DisposableEffect(registerDarkCanvas, canvasOwner) {
        registerDarkCanvas(canvasOwner, true)
        onDispose { registerDarkCanvas(canvasOwner, false) }
    }
    BackHandler { onBack() }
    Scaffold(containerColor = Color.Black, contentColor = Color.White) { insets ->
        Box(Modifier.fillMaxSize().background(Color.Black).padding(insets).consumeWindowInsets(insets)) {
            HorizontalPager(pager, Modifier.fillMaxSize(),
                key = { images[it].key }, userScrollEnabled = zoomed[images[pager.settledPage].key] != true) { page ->
                CachedImageContent(model, images[page], images[page].account, viewer = true) { zoomed[images[page].key] = it }
            }
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = .55f)),
                verticalAlignment = Alignment.CenterVertically) {
                FilewayIconButton(onBack) { FilewayIcon(R.drawable.ic_arrow_back, "返回缓存图库", tint = Color.White) }
                Text("缓存图片", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                FilewayIconButton({
                    val image = images[pager.settledPage]
                    if (!saving) {
                        saving = true
                        scope.launch {
                            val message = try { model.cache.saveCachedImageToAlbum(image); "已保存到相册" }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { "保存失败，请重试" }
                            finally { saving = false }
                            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }, enabled = !saving) { FilewayIcon(R.drawable.ic_download, "保存缓存图片到相册",
                    tint = Color.White.copy(alpha = if (saving) .38f else 1f)) }
            }
            Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp), color = Color.Black.copy(alpha = .5f),
                contentColor = Color.White, shape = MaterialTheme.shapes.medium) {
                Text("${pager.settledPage + 1} / ${images.size}", Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable private fun CachedImageContent(model: ClientModel, image: CachedImage, account: String, viewer: Boolean, onZoom: (Boolean) -> Unit = {}) {
    var failed by remember(image.key, account, viewer) { mutableStateOf(false) }
    var decoded by remember(image.key, account, viewer) { mutableStateOf(false) }
    val lease by produceState<CachedImageLease?>(null, image.key, account) {
        try {
            model.cache.withCachedImage(image.key, account) { owned ->
                value = owned
                awaitCancellation()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val context = LocalContext.current
    val memoryKey = "cache-gallery/${if (viewer) "original" else "preview"}/${image.key}"
    val cached = remember(image.key, account, viewer, model.cache.imageLoader.value) {
        val memory = model.cache.imageLoader.value.memoryCache
        memory?.get(MemoryCache.Key(memoryKey))?.image
            ?: if (viewer) memory?.get(MemoryCache.Key(image.key))?.image else null
    }
    var showLoading by remember(image.key, account, viewer) { mutableStateOf(false) }
    LaunchedEffect(lease, failed, decoded, cached, image.key, account, viewer) {
        showLoading = false
        if (!decoded && cached == null && !failed) {
            delay(350)
            showLoading = true
        }
    }
    val request = remember(lease?.file, image.key, viewer) { ImageRequest.Builder(context)
        .data(lease?.file).diskCachePolicy(CachePolicy.DISABLED)
        .memoryCacheKey("cache-gallery/${if (viewer) "original" else "preview"}/${image.key}")
        .apply { if (!viewer) size(512, 1024) }
        .listener(onError = { _, _ -> failed = true; decoded = false }).build() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (cached != null) Image(cached.asPainter(context), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else if (!failed && !decoded && !viewer) FilewayIcon(R.drawable.ic_image, null, Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f))
        if (lease != null) {
            if (viewer) {
                val state = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 8f)))
                LaunchedEffect(state.zoomableState) { snapshotFlow { (state.zoomableState.zoomFraction ?: 0f) > .001f }.collect(onZoom) }
                LaunchedEffect(state) { snapshotFlow { state.isImageDisplayed }.collect { decoded = it } }
                ZoomableAsyncImage(request, "缓存图片", Modifier.fillMaxSize(), state = state,
                    imageLoader = model.cache.imageLoader.value, contentScale = ContentScale.Fit)
            } else AsyncImage(request, null, imageLoader = model.cache.imageLoader.value, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                onSuccess = { decoded = true; failed = false }, onError = { failed = true; decoded = false })
        }
        if (showLoading && !decoded && cached == null && !failed)
            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
        if (failed) { if (viewer) Text("缓存已不可用，请返回刷新", color = Color.White)
            else FilewayIcon(R.drawable.ic_image, "缓存不可用", Modifier.size(20.dp)) }
    }
}

package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
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
    val client by model.state.collectAsStateWithLifecycle()
    val cache by model.cache.state.collectAsStateWithLifecycle()
    val account = remember { model.cacheAccount() }
    val session = remember { client.previewScope }
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
    fun current() = model.state.value.connected && model.state.value.previewScope == session && model.cacheAccount() == account
    fun toast(message: String) { android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show() }
    LaunchedEffect(client.previewScope, client.connected) { if (!current()) model.back() }
    LaunchedEffect(account, session, refresh, cache.revision) {
        busy = true; error = null
        try {
            model.cache.awaitReady()
            val loaded = model.cache.cachedImages(account)
            if (current()) { images = loaded; selected = selected.intersect(loaded.map { it.key }.toSet()) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (current()) error = "无法读取本机缓存，请刷新" }
        finally { busy = false }
    }
    val ordered = remember(images, order) { when (order) {
        CacheImageOrder.NEWEST -> images.sortedWith(compareByDescending<CachedImage> { it.cachedAt }.thenBy { it.key })
        CacheImageOrder.LARGEST -> images.sortedWith(compareByDescending<CachedImage> { it.bytes }.thenBy { it.key })
        CacheImageOrder.SMALLEST -> images.sortedWith(compareBy<CachedImage> { it.bytes }.thenBy { it.key })
    } }
    val snapshot = viewing
    if (snapshot != null) {
        CachedImageViewer(model, snapshot, account, initialPage) { viewing = null }
        return
    }
    BackHandler(selecting) { selecting = false; selected = emptySet() }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        FilewayRefresh(busy, { if (!busy && current()) refresh++ }, Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
Column(Modifier.fillMaxSize()) {
            FilewayTopAppBar(if (selecting) "已选 ${selected.size} 张" else "图片缓存", leadingIcon = R.drawable.ic_arrow_back,
                leadingDescription = "返回我的", onLeadingClick = { model.back() }, actions = {
                    if (selecting) {
                        IconButton({ selected = if (selected.size == images.size) emptySet() else images.map { it.key }.toSet() }, enabled = !busy) {
                            Icon(painterResource(R.drawable.ic_check), "全选或取消全选", Modifier.size(22.dp))
                        }
                        IconButton({
                            val targets = selected.toSet()
                            if (current() && targets.isNotEmpty()) {
                                busy = true
                                scope.launch {
                                    try {
                                        model.cache.removeImages(targets, account)
                                        if (current()) { selected = emptySet(); selecting = false; refresh++; toast("已删除 ${targets.size} 张缓存图片") }
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { if (current()) { error = "删除未完成，请刷新后核对"; busy = false } }
                                }
                            }
                        }, enabled = !busy && selected.isNotEmpty()) { Icon(painterResource(R.drawable.ic_trash), "删除选中缓存", tint = MaterialTheme.colorScheme.error) }
                        IconButton({ selecting = false; selected = emptySet() }, enabled = !busy) { Icon(painterResource(R.drawable.ic_close), "退出多选") }
                    } else {
                        Box {
                            IconButton({ sorting = true }) { Icon(painterResource(R.drawable.ic_sort), "缓存排序") }
                            FilewayMenu(sorting, { sorting = false }, items = CacheImageOrder.entries.map {
                                FilewayMenuEntry(it.name, it.label, selected = it == order)
                            }, selection = FilewayMenuSelection.Single, onItemClick = { item ->
                                CacheImageOrder.entries.firstOrNull { it.name == item.key }?.let { order = it }
                                sorting = false
                            })
                        }
                        IconButton({ selecting = true }, enabled = !busy && images.isNotEmpty()) { Icon(painterResource(R.drawable.ic_check), "多选缓存图片") }
                    }
                })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            if (ordered.isEmpty() && !busy) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("当前账号还没有图片缓存", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(3), Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalItemSpacing = 6.dp) {
                items(ordered, key = { it.key }) { image ->
                    val ratio = if (image.width > 0 && image.height > 0) (image.width.toFloat() / image.height).coerceIn(.2f, 5f) else 1f
                    Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(6.dp))
                        .then(if (image.key in selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else Modifier)
                        .combinedClickable(enabled = !busy && current(), onClick = {
                            if (selecting) selected = if (image.key in selected) selected - image.key else selected + image.key
                            else { initialPage = ordered.indexOfFirst { it.key == image.key }; viewing = ordered.toList() }
                        }, onLongClick = { if (current()) { selecting = true; selected = selected + image.key } })) {
                        CachedImageContent(model, image, account, viewer = false)
                        if (selecting) Checkbox(image.key in selected, null, Modifier.align(Alignment.TopEnd))
                    }
                }
            }
        }
}

    }
}

@Composable private fun CachedImageViewer(model: ClientModel, images: List<CachedImage>, account: String, initial: Int, onBack: () -> Unit) {
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
                CachedImageContent(model, images[page], account, viewer = true) { zoomed[images[page].key] = it }
            }
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = .55f)),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(painterResource(R.drawable.ic_arrow_back), "返回缓存图库", tint = Color.White) }
                Text("缓存图片", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton({
                    val image = images[pager.settledPage]
                    if (model.cacheAccount() == account) {
                        saving = true
                        scope.launch {
                            val message = try { model.cache.saveCachedImageToAlbum(image); "已保存到相册" }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { "保存失败，请重试" }
                            finally { saving = false }
                            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }, enabled = !saving) { Icon(painterResource(R.drawable.ic_download), "保存缓存图片到相册",
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
    var failed by remember(image.key, account) { mutableStateOf(false) }
    val lease by produceState<CachedImageLease?>(null, image.key, account) {
        var owned: CachedImageLease? = null
        try {
            withContext(NonCancellable) { owned = model.cache.openCachedImage(image.key, account) }
            currentCoroutineContext().ensureActive(); value = owned
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { owned?.close() }
    }
    val context = LocalContext.current
    val request = remember(lease?.file, image.key, viewer) { ImageRequest.Builder(context)
        .data(lease?.file).diskCachePolicy(CachePolicy.DISABLED)
        .memoryCacheKey("cache-gallery/${if (viewer) "original" else "preview"}/${image.key}")
        .apply { if (!viewer) size(512, 1024) }.build() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (lease != null) {
            if (viewer) {
                val state = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 8f)))
                LaunchedEffect(state.zoomableState) { snapshotFlow { (state.zoomableState.zoomFraction ?: 0f) > .001f }.collect(onZoom) }
                ZoomableAsyncImage(request, "缓存图片", Modifier.fillMaxSize(), state = state,
                    imageLoader = model.cache.imageLoader.value, contentScale = ContentScale.Fit)
            } else AsyncImage(request, null, imageLoader = model.cache.imageLoader.value, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                onError = { failed = true })
        } else if (!failed) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        if (failed) { if (viewer) Text("缓存已不可用，请返回刷新", color = Color.White)
            else Icon(painterResource(R.drawable.ic_image), "缓存不可用", Modifier.size(20.dp)) }
    }
}

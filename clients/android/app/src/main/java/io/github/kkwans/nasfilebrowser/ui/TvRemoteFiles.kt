package io.github.kkwans.nasfilebrowser.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kkwans.fileway.remote.RemoteSnapshot
import io.github.kkwans.fileway.design.R as Art
import io.github.kkwans.nasfilebrowser.app.FileLayout
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.TvControlModel
import io.github.kkwans.nasfilebrowser.data.CompactGridSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

internal data class TvBrowsePage(val id: String, val title: String, val total: Int, val rows: List<ResourceRef>) {
    companion object {
        fun decode(value: JSONObject): TvBrowsePage {
            val entries = value.getJSONArray("files")
            val rows = (0 until entries.length()).map { index ->
                val row = entries.getJSONObject(index)
                val id = row.getString("id")
                val kind = row.optString("kind").lowercase(java.util.Locale.ROOT)
                ResourceRef(id, id, row.getString("name"), row.getBoolean("directory"), kind,
                    row.optLong("size"), row.optString("modified"), previewWidth = row.optInt("width"),
                    previewHeight = row.optInt("height"))
            }
            return TvBrowsePage(value.getString("browseId"), value.getString("title"), value.getInt("total"), rows)
        }
    }
}

internal class TvBrowseState {
    var pages by mutableStateOf<List<TvBrowsePage>>(emptyList())
    var category by mutableStateOf("FILES")
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

private val browseCategories = listOf("FILES" to "文件", "VIDEO" to "视频", "MUSIC" to "音乐",
    "IMAGES" to "图片", "FAVORITES" to "收藏", "RECENT" to "最近播放", "RECENT_ACCESS" to "最近访问")
private val previewRequests = Semaphore(2)
private val previewWire = Mutex()
private var lastPreviewCommandMs = 0L
private val remoteArtworkCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

private suspend fun reservePreviewCommand() = previewWire.withLock {
    val remaining = 100 - (SystemClock.elapsedRealtime() - lastPreviewCommandMs)
    if (remaining > 0) delay(remaining)
    lastPreviewCommandMs = SystemClock.elapsedRealtime()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TvRemoteFiles(model: TvControlModel, snapshot: RemoteSnapshot, state: TvBrowseState) {
    val scope = rememberCoroutineScope()
    val preferences = LocalContext.current.getSharedPreferences("tv-remote-file-layout", 0)
    var layout by remember { mutableStateOf(FileLayout.entries.firstOrNull {
        it.name == preferences.getString("layout", null)
    } ?: FileLayout.COVER) }
    var compact by remember { mutableStateOf(CompactGridSize.entries.firstOrNull {
        it.name == preferences.getString("compact", null)
    } ?: CompactGridSize.MEDIUM) }
    var details by remember { mutableStateOf<ResourceRef?>(null) }
    val page = state.pages.lastOrNull()

    suspend fun request(operation: String = "browse.list", payload: JSONObject, accept: suspend (JSONObject) -> Unit) {
        if (state.busy) return
        state.busy = true
        state.error = null
        try { accept(model.remoteRequest(snapshot, operation, payload)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { state.error = failure.message ?: "无法读取 TV 文件" }
        finally { state.busy = false }
    }

    fun root(category: String) = scope.launch {
        request(payload = JSONObject().put("category", category)) {
            state.pages = listOf(TvBrowsePage.decode(it))
            state.category = category
        }
    }
    LaunchedEffect(Unit) {
        if (state.pages.isEmpty() && state.error == null) {
            request(payload = JSONObject().put("category", state.category)) { state.pages = listOf(TvBrowsePage.decode(it)) }
        }
    }
    BackHandler(state.pages.size > 1 && !state.busy) { state.pages = state.pages.dropLast(1); state.error = null }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            browseCategories.forEach { (key, title) ->
                FilterChip(state.category == key, onClick = { root(key) }, enabled = !state.busy, label = { Text(title) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.pages.size > 1) FilewayIconButton(onClick = {
                state.pages = state.pages.dropLast(1); state.error = null
            }, enabled = !state.busy) { FilewayIcon(Art.drawable.fileway_ic_arrow_back, "上一级") }
            Text(state.pages.joinToString(" / ") { if (it.title == "/") "根目录" else it.title },
                Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            FileLayoutMenu(layout, compactGridSize = compact, onCompactGridSize = {
                compact = it; preferences.edit().putString("compact", it.name).apply()
            }) { layout = it; preferences.edit().putString("layout", it.name).apply() }
        }
        state.error?.let { message ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                FilewayTextButton(onClick = { root(state.category) }) { Text("刷新") }
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (page != null && page.rows.isEmpty() && !state.busy) {
            Text("此目录没有文件", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (page != null) FileCollection(page.rows, layout, Modifier.weight(1f), layout.collectionDescription(),
            page.id, ResourceRef::wirePath, compactGridSize = compact,
            footer = if (page.rows.size < page.total) ({
                FilewayOutlinedButton(onClick = {
                    scope.launch {
                        request(payload = JSONObject().put("browseId", page.id).put("offset", page.rows.size)) {
                            val next = TvBrowsePage.decode(it)
                            check(next.id == page.id && next.rows.isNotEmpty())
                            state.pages = state.pages.dropLast(1) + page.copy(rows = page.rows + next.rows)
                        }
                    }
                }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("更多文件") }
            }) else null) { row ->
            FileEntry(null, row, layout, enabled = !state.busy, open = {
                scope.launch {
                    val payload = JSONObject().put("browseId", page.id).put("id", row.wirePath)
                    if (row.directory) request(payload = payload) { state.pages = state.pages + TvBrowsePage.decode(it) }
                    else request("browse.open", payload) {
                        if (it.has("browseId")) {
                            val opened = model.remoteRequest(snapshot, "browse.list",
                                JSONObject().put("browseId", it.getString("browseId")))
                            state.pages = state.pages + TvBrowsePage.decode(opened)
                        }
                    }
                }
            }, details = { details = row }, compactGridSize = compact, singleTapOpen = true,
                remoteArtwork = { modifier, scale, natural ->
                    TvRemoteArtwork(model, snapshot, row.wirePath, row.name, modifier, scale, natural, page.id,
                        contentRevision = row.modified)
                })
        } else if (!state.busy && state.error == null) Text("TV 文件正在准备", Modifier.padding(16.dp))
    }
    details?.let { row ->
        AlertDialog(onDismissRequest = { details = null }, title = { Text(row.name) },
            text = { Text(if (row.directory) "TV 文件夹" else "在 TV 上打开的文件") },
            confirmButton = { FilewayTextButton(onClick = { details = null }) { Text("关闭") } })
    }
}

@Composable internal fun TvRemoteArtwork(model: TvControlModel, snapshot: RemoteSnapshot, id: String,
    name: String, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Fit,
    naturalAspect: Boolean = false, browseId: String? = null, contentRevision: String = "") {
    val media = snapshot.mediaEpoch.takeIf { browseId == null }
    val cacheKey = listOf(snapshot.connectionId, snapshot.sessionEpoch, id, contentRevision).joinToString("/")
    var bitmap by remember(cacheKey) { mutableStateOf(remoteArtworkCache.get(cacheKey)) }
    var loading by remember(snapshot.connectionId, snapshot.sessionEpoch, media, browseId, id) { mutableStateOf(true) }
    LaunchedEffect(snapshot.connectionId, snapshot.sessionEpoch, media, browseId, id) {
        try {
            if (bitmap != null || snapshot.capabilities.optInt("mediaPreview") == 0) return@LaunchedEffect
            bitmap = previewRequests.withPermit {
                val input = JSONObject().put("id", id)
                if (browseId != null) input.put("browseId", browseId)
                reservePreviewCommand()
                var response = model.remoteRequest(snapshot, "preview.open", input, mediaBound = browseId == null)
                val total = response.getInt("total")
                val token = response.getString("previewId")
                require(total in 1..256 * 1024)
                val output = ByteArrayOutputStream(total)
                while (true) {
                    require(response.getString("previewId") == token && response.getInt("total") == total)
                    val bytes = Base64.decode(response.getString("data"), Base64.DEFAULT)
                    require(bytes.isNotEmpty() && bytes.size <= 20 * 1024 && output.size() + bytes.size <= total)
                    output.write(bytes)
                    require(response.getInt("nextOffset") == output.size())
                    if (output.size() == total) break
                    reservePreviewCommand()
                    val next = JSONObject().put("previewId", token).put("offset", output.size())
                    if (browseId != null) next.put("browseId", browseId)
                    response = model.remoteRequest(snapshot, "preview.read", next, mediaBound = browseId == null)
                }
                withContext(Dispatchers.Default) {
                    val bytes = output.toByteArray()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth in 1..1024 && bounds.outHeight in 1..1024)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }?.also { remoteArtworkCache.put(cacheKey, it) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { bitmap = null }
        finally { loading = false }
    }
    val image = bitmap
    val box = if (naturalAspect) modifier.aspectRatio(image?.let { it.width.toFloat() / it.height } ?: 4f / 3f) else modifier
    Box(box.background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        if (image != null) Image(image.asImageBitmap(), name, Modifier.fillMaxSize(), contentScale = scale)
        else if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else FilewayIcon(Art.drawable.fileway_ic_video, null, Modifier.size(28.dp))
    }
}

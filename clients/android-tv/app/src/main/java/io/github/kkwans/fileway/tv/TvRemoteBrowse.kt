package io.github.kkwans.fileway.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.SessionHealthCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

/** Directory tokens refer to immutable, bounded TV-owned lists. The phone
 * never supplies a filesystem path, credential or preview URL. */
internal class TvRemoteBrowse(private val model: TvModel) {
    private data class Scope(val owner: SessionContext, val connection: String, val session: Long)
    private data class Page(val title: String, val files: List<ResourceRef>, val category: String)
    private data class Preview(val bytes: ByteArray, val width: Int, val height: Int,
        val media: Long?, val browse: String?, val expires: Long)

    private var current: Scope? = null
    private val pages = LinkedHashMap<String, Page>()
    private val previews = LinkedHashMap<String, Preview>()
    private val previewSlots = Semaphore(2)

    private suspend fun verify(scope: Scope, command: JSONObject) {
        check(model.remote.authorized(command) && model.context === scope.owner &&
            model.sessionEpoch == scope.session && scope.owner.api.sessionHealth.value?.code !in
            setOf(SessionHealthCode.EXPIRED, SessionHealthCode.ACCOUNT_CHANGED)) { "TV 登录或控制授权已变化" }
    }

    private fun rememberPage(page: Page): String {
        val id = UUID.randomUUID().toString()
        pages[id] = page
        if (pages.size > 16) pages.remove(pages.keys.first())
        return id
    }

    suspend fun execute(operation: String, command: JSONObject, payload: JSONObject): JSONObject {
        val scope = Scope(model.context ?: error("请先在 TV 登录"), command.getString("connectionId"),
            command.getLong("sessionEpoch"))
        verify(scope, command)
        if (current != scope) {
            current = scope
            pages.clear()
            previews.clear()
        }
        fun page() = pages[payload.getString("browseId")] ?: error("目录已变化，请刷新")
        fun file(rows: List<ResourceRef>) = rows.firstOrNull { remoteFileId(it) == payload.getString("id") }
            ?: error("文件已变化，请刷新")
        return when (operation) {
            "browse.list" -> {
                val id = if (payload.has("id") || !payload.has("browseId")) {
                    val parent = if (payload.has("id")) page() else null
                    val directory = parent?.let { file(it.files).also { row -> require(row.directory) } }
                    val category = parent?.category ?: payload.optString("category", "FILES")
                    val result = if (directory == null && category in setOf("FAVORITES", "RECENT", "RECENT_ACCESS")) {
                        when (category) {
                            "FAVORITES" -> model.favorites.state.value.items.filter { it.openable }
                                .map { ResourceRef(it.path, it.wirePath, it.name, false, "", 0) }
                            "RECENT" -> model.state.value.recentPlayback.map { ResourceRef(it.path, it.wirePath, it.name, false, "", 0) }
                            else -> tvRecentAccessResources(model.recentAccess.state.value.items)
                        }
                    } else {
                        val wire = directory?.wirePath ?: "/"
                        TvModel.directoryFiles(scope.owner.api.request("GET", "/api/resources$wire"))
                    }
                    verify(scope, command)
                    check(current == scope) { "TV 控制设备已变化" }
                    val kind = when (category) {
                        "VIDEO" -> MediaKind.VIDEO
                        "MUSIC" -> MediaKind.AUDIO
                        "IMAGES" -> MediaKind.IMAGE
                        else -> null
                    }
                    val visible = result.filter { it.directory || kind == null || it.mediaKind() == kind }
                    val sorted = if (category == "RECENT" || category == "RECENT_ACCESS") visible
                    else sort(visible, model.state.value.sortAscending)
                    rememberPage(Page(directory?.name?.take(240) ?: "/", sorted, category))
                } else payload.getString("browseId")
                val selected = pages[id] ?: error("目录已变化，请刷新")
                remoteBrowsePage(selected.files, payload.optInt("offset"), 32).put("browseId", id).put("title", selected.title)
            }
            "browse.open" -> {
                val directory = page()
                val rows = directory.files
                val choice = file(rows)
                val selected = if (directory.category == "FAVORITES" && directory.title == "/") {
                    val metadata = scope.owner.api.request("GET", "/api/resources" + choice.wirePath)
                    verify(scope, command)
                    check(current == scope) { "TV 控制设备已变化" }
                    choice.copy(directory = metadata.getBoolean("isDir"), type = metadata.optString("type"),
                        size = metadata.optLong("size"), modified = metadata.optString("modified"))
                } else choice
                if (selected.directory) {
                    val files = TvModel.directoryFiles(scope.owner.api.request("GET", "/api/resources" + selected.wirePath))
                    verify(scope, command)
                    check(current == scope) { "TV 控制设备已变化" }
                    val sorted = sort(files, model.state.value.sortAscending)
                    val id = rememberPage(Page(selected.name.take(240), sorted, directory.category))
                    return JSONObject().put("browseId", id)
                }
                require(selected.mediaKind() != null) { "TV 无法打开此文件类型" }
                model.open(selected, if (selected == choice) rows else rows.map { if (it == choice) selected else it })
                JSONObject().put("requested", true)
            }
            "preview.open" -> {
                val browseId = payload.optString("browseId").takeIf(String::isNotEmpty)
                val epoch = model.media.controlEpoch.takeUnless { browseId != null }
                if (epoch != null) check(command.getLong("mediaEpoch") == epoch) { "播放内容已变化" }
                val source = if (epoch == null) page().files else model.media.state.value.let {
                    it.queue?.items.orEmpty() + listOfNotNull(it.file)
                }
                val selected = file(source)
                require(selected.mediaKind() in setOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO))
                val preview = previewSlots.withPermit {
                    verify(scope, command)
                    val lease = scope.owner.api.preview(selected.path, selected.wirePath, contain = true)
                    try { readPreview(lease.url, epoch, browseId) } finally { lease.release() }
                }
                verify(scope, command)
                check(current == scope && (epoch == null || epoch == model.media.controlEpoch)) { "预览来源已变化" }
                val id = UUID.randomUUID().toString()
                previews[id] = preview
                if (previews.size > 8) previews.remove(previews.keys.first())
                chunk(id, preview, 0)
            }
            "preview.read" -> {
                val id = payload.getString("previewId")
                val preview = previews[id]?.takeIf { it.expires > SystemClock.elapsedRealtime() &&
                    it.browse == payload.optString("browseId").takeIf(String::isNotEmpty) &&
                    (it.media == null || it.media == model.media.controlEpoch) } ?: error("预览已过期")
                chunk(id, preview, payload.getInt("offset"))
            }
            else -> error("此操作尚不支持")
        }
    }

    private fun chunk(id: String, preview: Preview, offset: Int): JSONObject {
        require(offset in preview.bytes.indices)
        val end = minOf(offset + 20 * 1024, preview.bytes.size)
        return JSONObject().put("previewId", id).put("width", preview.width).put("height", preview.height)
            .put("total", preview.bytes.size).put("nextOffset", end)
            .put("data", Base64.encodeToString(preview.bytes, offset, end - offset, Base64.NO_WRAP))
    }

    private fun sort(rows: List<ResourceRef>, ascending: Boolean): List<ResourceRef> =
        rows.sortedWith(compareBy<ResourceRef> { !it.directory }.let { order ->
            if (ascending) order.thenBy { it.name.lowercase(Locale.ROOT) }
            else order.thenByDescending { it.name.lowercase(Locale.ROOT) }
        })

    private suspend fun readPreview(url: String, epoch: Long?, browseId: String?): Preview = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 5000
            readTimeout = 5000
        }
        val bytes = try {
            val deadline = SystemClock.elapsedRealtime() + 12_000
            check(connection.responseCode == 200) { "预览暂不可用" }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    check(SystemClock.elapsedRealtime() < deadline) { "读取预览超时" }
                    val size = input.read(buffer)
                    if (size < 0) break
                    require(output.size() + size <= 2 * 1024 * 1024) { "预览尺寸超出限制" }
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "预览无法读取" }
        val options = BitmapFactory.Options()
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > 800) {
            options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("预览无法读取")
        try {
            val output = ByteArrayOutputStream()
            for (quality in listOf(85, 65, 45)) {
                output.reset()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                if (output.size() <= 256 * 1024) {
                    return@withContext Preview(output.toByteArray(), bounds.outWidth, bounds.outHeight,
                        epoch, browseId, SystemClock.elapsedRealtime() + 45_000)
                }
            }
            error("预览尺寸超出限制")
        } finally { bitmap.recycle() }
    }
}

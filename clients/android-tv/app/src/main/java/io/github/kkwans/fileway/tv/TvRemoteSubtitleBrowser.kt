package io.github.kkwans.fileway.tv

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.core.TransportException
import io.github.kkwans.nasfilebrowser.data.SearchResult
import io.github.kkwans.nasfilebrowser.data.ScopedSubtitleEntry
import io.github.kkwans.nasfilebrowser.data.ScopedSubtitleResources
import io.github.kkwans.nasfilebrowser.data.ServiceException
import io.github.kkwans.nasfilebrowser.data.isExternalSubtitle
import io.github.kkwans.nasfilebrowser.data.recentAccessWireIdentity
import io.github.kkwans.nasfilebrowser.player.EXTERNAL_SUP_BYTES_LIMIT
import io.github.kkwans.nasfilebrowser.player.EXTERNAL_TEXT_BYTES_LIMIT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal const val TV_LOCAL_SUBTITLE_SCAN_LIMIT = 65_536
internal const val TV_LOCAL_SUBTITLE_SCAN_WINDOW = 4096

/** Main-confined session for the host's single active controller. NAS paths
 * and TV storage nodes stay behind connection-scoped opaque handles. */
internal class TvRemoteSubtitleBrowser(
    private val scope: CoroutineScope,
    private val source: () -> Source?,
    private val authorize: suspend (Peer) -> Boolean,
    private val elapsed: () -> Long,
    private val prepare: (ScopedSubtitleEntry, Long, Long, suspend () -> Boolean) -> Import?,
) {
    internal data class Peer(val controller: String, val connection: String)
    internal interface LocalNode {
        val label: String
        val directory: Boolean
        val key: Any
    }
    internal class LocalPage(val nodes: List<LocalNode>, val afterRow: List<Int>, val next: Int?,
        val anchors: Map<Int, Any> = emptyMap())
    internal class LocalSource(
        val revision: () -> Long,
        val list: suspend (LocalNode?, Int, Any?) -> LocalPage,
        val verify: suspend (LocalNode) -> Unit,
        val prepare: (LocalNode, Long, Long, suspend () -> Boolean) -> Import?,
    )
    internal data class Source(
        val owner: Any,
        val accountId: Long,
        val sessionEpoch: Long,
        val mediaEpoch: Long,
        val decoder: Long,
        val video: ResourceRef,
        val selection: Long?,
        val local: LocalSource? = null,
        val scoped: (() -> ScopedSubtitleResources)? = null,
    ) {
        fun same(other: Source?) = other != null && owner === other.owner && accountId == other.accountId &&
            sessionEpoch == other.sessionEpoch && mediaEpoch == other.mediaEpoch && decoder == other.decoder && video == other.video &&
            (scoped != null) == (other.scoped != null)
    }
    internal class Import(val await: suspend () -> TvRemoteSubtitleResult, val cancel: () -> Unit)

    private enum class Error(val message: String) {
        INVALID_REQUEST("字幕目录请求无效，请重新打开"),
        STALE_SCOPE("TV 登录、播放或控制连接已变化，请重新打开字幕目录"),
        NOT_AVAILABLE("请在 TV 打开服务器视频后重试"),
        SERVER_UPGRADE_REQUIRED("更新 TV 当前连接的服务器后，即可在任意获准目录选择字幕"),
        AUTH_REQUIRED("TV 登录已过期，请在电视重新连接"),
        ACCESS_DENIED("TV 当前账号无法访问此目录或读取字幕内容"),
        STORAGE_AUTH_REQUIRED("TV 没有可用的本机目录，请在电视上授权文件夹或检查存储"),
        STORAGE_REMOVED("TV 存储已移除或不可访问，请返回本机目录"),
        NOT_FOUND("目录或文件已移动或删除，请刷新或返回根目录"),
        DIRECTORY_TOO_LARGE("目录过大，请打开其他目录"),
        LOCAL_SCAN_LIMIT("已扫描 65,536 项，请在电视上授权更小的字幕文件夹"),
        DIRECTORY_CHANGED("目录内容已变化，请从第一页重新读取"),
        READ_FAILED("无法读取目录或确认文件，请重试"),
        TIMEOUT("读取超时，请重试"),
        FILE_CHANGED("字幕文件已变化，请刷新目录后重选"),
        FILE_VERSION_UNAVAILABLE("电视存储未提供字幕版本，请选择其他文件"),
        FILE_UNAVAILABLE("当前字幕文件已不可用，请重试或选择其他文件"),
        FORMAT_UNSUPPORTED("此文件无法作为外挂字幕读取"),
        FILE_TOO_LARGE("字幕文件超过 TV 支持的大小，请选择其他文件"),
        PLAYER_NOT_READY("视频或字幕状态已变化，请稍后重选"),
        SELECTION_FAILED("TV 未能加载此字幕，请重试或选择其他文件"),
        RESULT_UNKNOWN("字幕选择结果尚未确认，请查看 TV 当前轨道"),
    }
    private class Failure(val error: Error) : Exception()
    private class Directory(val wire: String, val name: String, val id: String = newId(), var nameVerified: Boolean = false,
        val local: LocalNode? = null)
    private sealed interface Item {
        val name: String
        val directory: Boolean
        val key: Any

        class Server(val scoped: ScopedSubtitleEntry) : Item {
            val file get() = scoped.file
            override val name get() = file.name
            override val directory get() = file.directory
            override val key get() = scoped
        }

        class Local(val node: LocalNode) : Item {
            override val name get() = node.label
            override val directory get() = node.directory
            override val key get() = node.key
        }
    }
    private class Entry(val item: Item, val id: String = newId())
    private class Row(val item: Item, val after: Int)
    private class Listing(val rows: List<Row>, val next: Int?, val signature: String? = null,
        val anchors: Map<Int, Any> = emptyMap())
    private class Read(val offset: Int) {
        var listing: Listing? = null
        var failure: Error? = null
        var job: Job? = null
    }
    private class Selection(val id: String = newId()) {
        var applied = false
        var failure: Error? = null
        var authorizationFailure: Error? = null
        var job: Job? = null
        var native: Import? = null
    }
    private class Browser(val owner: Source, val peer: Peer, var trail: List<Directory>, var touched: Long,
        val local: LocalSource? = null, val cursorMode: Boolean = false) {
        val id = newId()
        val directory get() = trail.last()
        val localRevision = local?.revision?.invoke()
        val entries = mutableMapOf<String, Entry>()
        val issued = linkedMapOf<Any, Entry>()
        val nextOffsets = mutableMapOf<Int, Any?>()
        val tokens = linkedMapOf<String, Int>()
        var stream: TvScopedSubtitleStream? = null
        var serverBytes = 0L
        var signature: String? = null
        var read: Read? = null
        var selection: Selection? = null
        var watcher: Job? = null
        fun clearEntries() { entries.clear(); issued.clear(); nextOffsets.clear(); tokens.clear(); signature = null; serverBytes = 0 }
        fun cancelRead() { read?.job?.cancel(); read = null }
        fun cancelSelection() {
            selection?.native?.cancel?.invoke()
            selection?.job?.cancel()
            selection = null
        }
    }
    private var active: Browser? = null
    val available: Boolean get() = source()?.scoped != null
    val serverUpgradeRequired: Boolean get() = source()?.let { it.scoped == null } == true
    val localAvailable: Boolean get() = source()?.local != null

    suspend fun execute(operation: String, command: JSONObject, payload: JSONObject): JSONObject = try {
        val peer = Peer(command.getString("controllerId"), command.getString("connectionId"))
        val sessionEpoch = command.getLong("sessionEpoch")
        val mediaEpoch = command.getLong("mediaEpoch")
        if (!authorized(peer)) fail(Error.STALE_SCOPE)
        val opening = operation == "subtitle.browse.open"
        val current = source() ?: fail(if (opening) Error.NOT_AVAILABLE else Error.STALE_SCOPE)
        if (current.sessionEpoch != sessionEpoch || current.mediaEpoch != mediaEpoch) fail(Error.STALE_SCOPE)
        if (opening) {
            val local = when (payload.optString("source", "server")) {
                "server" -> { if (current.scoped == null) fail(Error.SERVER_UPGRADE_REQUIRED); null }
                "tvLocal" -> current.local ?: fail(Error.NOT_AVAILABLE)
                else -> fail(Error.INVALID_REQUEST)
            }
            close(active)
            val trail = if (local == null) initialTrail(current.video) else listOf(Directory("", "TV 本机", nameVerified = true))
            val cursorMode = local == null && payload.optInt("cursorVersion") == 1
            val browser = Browser(current, peer, trail, elapsed(), local, cursorMode)
            active = browser
            resetStream(browser)
            watch(browser)
            read(browser, 0)
            outline(browser, "PENDING").put("offset", 0)
        } else {
            val browser = active?.takeIf { it.id == payload.getString("browserId") && it.peer == peer && it.owner.same(current) }
                ?: fail(Error.STALE_SCOPE)
            if (!fresh(browser)) { close(browser); fail(Error.STALE_SCOPE) }
            browser.touched = elapsed()
            when (operation) {
                "subtitle.browse.page" -> page(browser, payload)
                "subtitle.browse.select" -> select(browser, payload)
                "subtitle.browse.close" -> { close(browser); JSONObject().put("status", "CLOSED") }
                else -> fail(Error.INVALID_REQUEST)
            }
        }
    } catch (failure: Exception) {
        if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
        errorJson(if (failure is Failure) failure.error else Error.INVALID_REQUEST)
    }

    private fun watch(browser: Browser) {
        browser.watcher = scope.launch {
            while (fresh(browser)) {
                delay(750)
                if (!authorized(browser.peer)) break
            }
            close(browser)
        }
    }

    private fun close(browser: Browser?) {
        if (browser == null || active !== browser) return
        active = null
        browser.watcher?.cancel()
        browser.cancelRead()
        browser.cancelSelection()
        retireStream(browser)
    }

    private fun retireStream(browser: Browser) {
        val previous = browser.stream ?: return
        browser.stream = null
        scope.launch { try { withTimeoutOrNull(2_000) { previous.close() } } catch (_: Exception) { } }
    }

    private fun resetStream(browser: Browser) {
        retireStream(browser)
        if (browser.local == null) browser.stream = TvScopedSubtitleStream(
            checkNotNull(browser.owner.scoped).invoke(), browser.directory.wire, browser.cursorMode)
    }

    private fun fresh(browser: Browser): Boolean = active === browser && elapsed() - browser.touched < IDLE_MS &&
        browser.owner.same(source()) && browser.localRevision == browser.local?.revision?.invoke()

    private suspend fun authorized(peer: Peer): Boolean = try {
        withTimeoutOrNull(1_500) { authorize(peer) } == true
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        false
    }

    private suspend fun checkScope(browser: Browser) {
        if (!fresh(browser) || !authorized(browser.peer) || !fresh(browser)) fail(Error.STALE_SCOPE)
    }

    private fun page(browser: Browser, payload: JSONObject): JSONObject {
        val id = payload.optString("directoryId", browser.directory.id)
        val offset = if (browser.cursorMode) {
            if (payload.has("offset")) return outlineError(browser, Error.INVALID_REQUEST)
            if (payload.has("pageToken")) browser.tokens[payload.optString("pageToken")]
                ?: return outlineError(browser, Error.DIRECTORY_CHANGED)
            else 0
        } else if (payload.has("offset")) (payload.get("offset") as? Number)?.toString()?.toIntOrNull() else 0
        val limit = if (browser.cursorMode) Int.MAX_VALUE - MAX_PAGE_ROWS - 1 else if (browser.local == null) MAX_ROWS else TV_LOCAL_SUBTITLE_SCAN_LIMIT
        require(offset != null && offset in 0..limit)
        val refresh = payload.optBoolean("refresh")
        if (id != browser.directory.id) {
            val ancestor = browser.trail.indexOfFirst { it.id == id }
            val child = browser.entries[id]?.item?.takeIf { it.directory }
            if (ancestor < 0 && child == null) return outlineError(browser, Error.NOT_FOUND)
            if (ancestor < 0 && browser.trail.size > MAX_DEPTH) return outlineError(browser, Error.DIRECTORY_TOO_LARGE)
            browser.trail = if (ancestor >= 0) browser.trail.take(ancestor + 1)
            else browser.trail + when (val item = requireNotNull(child)) {
                is Item.Server -> Directory(item.file.wirePath, item.name, id, nameVerified = true)
                is Item.Local -> Directory("", item.name, id, nameVerified = true, local = item.node)
            }
            browser.cancelSelection()
            browser.cancelRead()
            browser.clearEntries()
            resetStream(browser)
        }
        if (refresh) {
            browser.cancelRead()
            browser.clearEntries()
            resetStream(browser)
        }
        if (browser.local != null && offset != 0 && offset !in browser.nextOffsets)
            return outlineError(browser, Error.DIRECTORY_CHANGED).put("offset", 0)
        val existing = browser.read?.takeIf { it.offset == offset }
        if (existing == null) read(browser, offset)
        val failure = existing?.failure
        if (failure != null) {
            browser.read = null
            return outlineError(browser, failure).put("offset", offset)
        }
        val listing = existing?.listing ?: return outline(browser, "PENDING").put("offset", offset)
        // A delivered read is consumed. A repeated page command must read the
        // directory again, since NAS access and content can change at any time.
        browser.read = null
        browser.directory.nameVerified = true
        if (browser.signature != null && browser.signature != listing.signature) {
            browser.clearEntries()
            if (offset != 0) return outlineError(browser, Error.DIRECTORY_CHANGED).put("offset", 0)
        }
        browser.signature = listing.signature
        val response = outline(browser, "READY").put("offset", offset)
        if (browser.cursorMode) {
            response.put("nextPageToken", "00000000-0000-0000-0000-000000000000")
            response.put("firstRetainedId", "00000000-0000-0000-0000-000000000000")
        }
        val items = mutableListOf<JSONObject>()
        var after = offset
        for (candidate in listing.rows) {
            val item = candidate.item
            val continuation = candidate.after
            val key = item.key
            val prior = browser.issued[key]
            if (browser.local != null && prior == null && browser.issued.size >= MAX_LOCAL_HANDLES)
                return outlineError(browser, Error.DIRECTORY_TOO_LARGE).put("offset", offset)
            val weight = if (prior == null && item is Item.Server) serverEntryBytes(item.scoped) else 0L
            if (weight > MAX_SERVER_HANDLES_BYTES || !browser.cursorMode && browser.local == null &&
                browser.serverBytes + weight > MAX_SERVER_HANDLES_BYTES) {
                browser.clearEntries()
                return outlineError(browser, Error.DIRECTORY_TOO_LARGE).put("offset", offset)
            }
            val entry = prior ?: Entry(item)
            val row = JSONObject().put("id", entry.id).put("name", label(item.name, MAX_LABEL_CHARS)).put("directory", item.directory)
            response.put("items", JSONArray(items + row)).put("nextOffset", continuation)
            if (wireBytes(response) > MAX_PAGE_BYTES) break
            items.add(row)
            browser.issued[key] = entry
            browser.entries[entry.id] = entry
            browser.serverBytes += weight
            while (browser.cursorMode && (browser.issued.size > MAX_CURSOR_HANDLES || browser.serverBytes > MAX_SERVER_HANDLES_BYTES)) {
                val first = browser.issued.entries.first()
                browser.issued.remove(first.key)
                browser.entries.remove(first.value.id)
                browser.serverBytes -= serverEntryBytes((first.value.item as Item.Server).scoped)
            }
            after = continuation
        }
        check(items.isNotEmpty() || listing.rows.isEmpty())
        val next = if (items.size < listing.rows.size) after else listing.next
        if (!browser.cursorMode && browser.local == null && next != null && next >= MAX_ROWS)
            return outlineError(browser, Error.DIRECTORY_TOO_LARGE).put("offset", offset)
        if (browser.local != null && next != null) {
            if (next !in browser.nextOffsets && browser.nextOffsets.size >= MAX_LOCAL_CONTINUATIONS) {
                browser.clearEntries()
                return outlineError(browser, Error.DIRECTORY_CHANGED).put("offset", 0)
            }
            browser.nextOffsets[next] = listing.anchors[next]
        }
        response.put("items", JSONArray(items))
        if (browser.cursorMode) {
            response.remove("nextOffset")
            response.put("firstRetainedId", browser.issued.values.firstOrNull()?.id ?: JSONObject.NULL)
            val token = next?.let { position ->
                browser.tokens.entries.firstOrNull { it.value == position }?.key ?: newId().also {
                    browser.tokens[it] = position
                    while (browser.tokens.size > MAX_CURSOR_TOKENS) browser.tokens.remove(browser.tokens.keys.first())
                }
            }
            return response.put("nextPageToken", token ?: JSONObject.NULL)
        }
        return response.put("nextOffset", next ?: JSONObject.NULL)
    }

    private fun read(browser: Browser, offset: Int) {
        browser.cancelRead()
        val directory = browser.directory
        val anchor = browser.nextOffsets[offset]
        val read = Read(offset)
        browser.read = read
        read.job = scope.launch {
            try {
                checkScope(browser)
                val listing = withTimeout(READ_MS) {
                    val local = browser.local
                    if (local != null) {
                        if (offset >= TV_LOCAL_SUBTITLE_SCAN_LIMIT) fail(Error.LOCAL_SCAN_LIMIT)
                        val result = local.list(directory.local, offset, anchor)
                        if (directory.local == null && offset == 0 && result.nodes.isEmpty() && result.next == null)
                            fail(Error.STORAGE_AUTH_REQUIRED)
                        check(result.nodes.size == result.afterRow.size && result.nodes.size <= MAX_PAGE_ROWS)
                        check(result.afterRow.zipWithNext().all { (a, b) -> a < b } &&
                            result.afterRow.all { it in (offset + 1)..TV_LOCAL_SUBTITLE_SCAN_LIMIT } &&
                            (result.next == null || result.next in (result.afterRow.lastOrNull() ?: offset + 1)..TV_LOCAL_SUBTITLE_SCAN_LIMIT))
                        val rows = result.nodes.zip(result.afterRow) { node, after -> Row(Item.Local(node), after) }
                        Listing(rows, result.next, anchors = result.anchors)
                    } else {
                        val result = checkNotNull(browser.stream).read(offset)
                        val rows = result.entries.mapIndexed { index, entry -> Row(Item.Server(entry), offset + index + 1) }
                        Listing(rows, result.next)
                    }
                }
                checkScope(browser)
                if (browser.read === read) read.listing = listing
            } catch (failure: Exception) {
                val error = readError(failure)
                if (browser.read === read) {
                    if (directory !== browser.trail.first() && error in setOf(Error.NOT_FOUND, Error.AUTH_REQUIRED, Error.ACCESS_DENIED, Error.STORAGE_REMOVED))
                        directory.nameVerified = false
                    if (error !in setOf(Error.TIMEOUT, Error.READ_FAILED, Error.LOCAL_SCAN_LIMIT)) {
                        browser.clearEntries()
                        if (browser.local == null) resetStream(browser)
                    }
                    read.failure = error
                }
            }
        }
    }

    private fun select(browser: Browser, payload: JSONObject): JSONObject {
        if (payload.has("operationId")) {
            require(!payload.has("fileId"))
            val selection = browser.selection?.takeIf { it.id == payload.getString("operationId") }
                ?: return errorJson(Error.RESULT_UNKNOWN)
            return selectionResponse(selection)
        }
        val item = browser.entries[payload.getString("fileId")]?.item ?: return errorJson(Error.NOT_FOUND)
        if (item.directory) return errorJson(Error.FORMAT_UNSUPPORTED)
        browser.cancelSelection()
        val revision = source()?.selection ?: return errorJson(Error.PLAYER_NOT_READY)
        val selection = Selection()
        browser.selection = selection
        selection.job = scope.launch {
            var applied = false
            try {
                verifySelection(browser, selection, item)
                if (source()?.selection != revision) fail(Error.PLAYER_NOT_READY)
                val authorized: suspend () -> Boolean = {
                    try {
                        verifySelection(browser, selection, item)
                        true
                    } catch (failure: Exception) {
                        selection.authorizationFailure = readError(failure)
                        false
                    }
                }
                val native = when (item) {
                    is Item.Server -> prepare(item.scoped, browser.owner.mediaEpoch, revision, authorized)
                    is Item.Local -> browser.local?.prepare?.invoke(item.node, browser.owner.mediaEpoch, revision, authorized)
                } ?: fail(Error.PLAYER_NOT_READY)
                selection.native = native
                val result = withTimeout(IMPORT_MS) { native.await() }
                applied = result == TvRemoteSubtitleResult.APPLIED
                checkScope(browser)
                if (browser.selection === selection) {
                    if (applied) selection.applied = true
                    else selection.failure = selection.authorizationFailure ?: when (result) {
                        TvRemoteSubtitleResult.ACCESS_DENIED -> Error.ACCESS_DENIED
                        TvRemoteSubtitleResult.UNAVAILABLE -> Error.FILE_UNAVAILABLE
                        TvRemoteSubtitleResult.TIMEOUT -> Error.TIMEOUT
                        TvRemoteSubtitleResult.CANCELLED, TvRemoteSubtitleResult.STALE -> Error.PLAYER_NOT_READY
                        else -> Error.SELECTION_FAILED
                    }
                }
            } catch (failure: Exception) {
                val error = if (applied || failure is TimeoutCancellationException && selection.native != null) Error.RESULT_UNKNOWN else readError(failure)
                if (browser.selection === selection) selection.failure = error
            } finally {
                if (!applied) selection.native?.cancel?.invoke()
                selection.native = null
            }
        }
        return selectionResponse(selection)
    }

    private suspend fun verifySelection(browser: Browser, selection: Selection, item: Item) {
        checkScope(browser)
        if (browser.selection !== selection) fail(Error.STALE_SCOPE)
        withTimeout(READ_MS) {
            when (item) {
                is Item.Local -> checkNotNull(browser.local).verify(item.node)
                is Item.Server -> {
                    val file = item.file
                    if (!isExternalSubtitle(file.name) || file.size <= 0) fail(Error.FORMAT_UNSUPPORTED)
                    val limit = if (file.name.endsWith(".sup", ignoreCase = true)) EXTERNAL_SUP_BYTES_LIMIT else EXTERNAL_TEXT_BYTES_LIMIT
                    if (file.size > limit) fail(Error.FILE_TOO_LARGE)
                    try { checkNotNull(browser.stream).verify(item.scoped) }
                    catch (failure: ServiceException) { if (failure.status == 409) fail(Error.FILE_CHANGED) else throw failure }
                }
            }
        }
        checkScope(browser)
        if (browser.selection !== selection) fail(Error.STALE_SCOPE)
    }

    private fun selectionResponse(selection: Selection): JSONObject = (selection.failure?.let(::errorJson)
        ?: JSONObject().put("status", if (selection.applied) "APPLIED" else "PENDING")).put("operationId", selection.id)

    private fun outline(browser: Browser, status: String) = JSONObject().put("status", status).put("browserId", browser.id)
        .put("directoryId", browser.directory.id).put("rootId", browser.trail.first().id)
        .put("breadcrumbs", JSONArray(browser.trail.map { directory ->
            val current = directory === browser.directory
            val name = if (directory.nameVerified) directory.name else if (current) "当前目录" else "上级目录"
            JSONObject().put("id", directory.id).put("name", label(name, if (current) MAX_LABEL_CHARS else 24))
        }))

    private fun outlineError(browser: Browser, error: Error) = outline(browser, "ERROR").put("code", error.name).put("message", error.message)

    private companion object {
        const val IDLE_MS = 120_000L
        const val READ_MS = 10_000L
        const val IMPORT_MS = 90_000L
        const val MAX_DEPTH = 64
        const val MAX_ROWS = 4096
        const val MAX_CURSOR_HANDLES = 256
        const val MAX_CURSOR_TOKENS = 4
        const val MAX_SERVER_HANDLES_BYTES = 1024L * 1024L
        const val MAX_LOCAL_HANDLES = 1024
        // A page consumes a visible handle or advances one scan window.
        const val MAX_LOCAL_CONTINUATIONS = MAX_LOCAL_HANDLES + TV_LOCAL_SUBTITLE_SCAN_LIMIT / TV_LOCAL_SUBTITLE_SCAN_WINDOW
        const val MAX_PATH_LENGTH = 16 * 1024
        const val MAX_PAGE_ROWS = 32
        const val MAX_PAGE_BYTES = 24 * 1024
        // Even worst-case JSON escaping must leave room for one row at maximum depth.
        const val MAX_LABEL_CHARS = 768

        fun newId(): String = UUID.randomUUID().toString()
        fun fail(error: Error): Nothing = throw Failure(error)
        fun errorJson(error: Error) = JSONObject().put("status", "ERROR").put("code", error.name).put("message", error.message)

        fun wireBytes(payload: JSONObject): Int {
            val json = payload.toString()
            var size = json.toByteArray(Charsets.UTF_8).size
            // Go's JSON envelope also escapes HTML characters and line separators in raw payloads.
            for (char in json) {
                size += when (char) {
                    '<', '>', '&' -> 5
                    '\u2028', '\u2029' -> 3
                    else -> 0
                }
            }
            return size
        }

        fun serverEntryBytes(entry: ScopedSubtitleEntry): Long {
            val file = entry.file
            // Account for UTF-16 strings plus conservative object/map overhead.
            return 256L + 2L * (file.path.length + file.wirePath.length + file.name.length +
                file.type.length + file.modified.length + (entry.version?.length ?: 0))
        }

        fun readError(failure: Exception): Error = when (failure) {
            is TimeoutCancellationException -> Error.TIMEOUT
            is CancellationException -> throw failure
            is Failure -> failure.error
            is TvScopedSubtitleStream.Changed -> Error.DIRECTORY_CHANGED
            is TvScopedSubtitleStream.ScanIncomplete -> Error.TIMEOUT
            is ServiceException -> when (failure.status) {
                401 -> Error.AUTH_REQUIRED
                403 -> Error.ACCESS_DENIED
                404 -> Error.NOT_FOUND
                409 -> Error.DIRECTORY_CHANGED
                413 -> Error.DIRECTORY_TOO_LARGE
                else -> Error.READ_FAILED
            }
            is TransportException -> if (failure.message == "service response exceeds size limit") Error.DIRECTORY_TOO_LARGE else Error.READ_FAILED
            is TvLocalSubtitleFailure -> when (failure.issue) {
                TvLocalSubtitleIssue.ACCESS_DENIED -> Error.ACCESS_DENIED
                TvLocalSubtitleIssue.STORAGE_REMOVED -> Error.STORAGE_REMOVED
                TvLocalSubtitleIssue.FILE_CHANGED -> Error.FILE_CHANGED
                TvLocalSubtitleIssue.DIRECTORY_CHANGED -> Error.DIRECTORY_CHANGED
                TvLocalSubtitleIssue.FILE_VERSION_UNAVAILABLE -> Error.FILE_VERSION_UNAVAILABLE
                TvLocalSubtitleIssue.READ_FAILED -> Error.READ_FAILED
            }
            else -> Error.READ_FAILED
        }

        fun label(value: String, max: Int): String {
            var end = value.length.coerceAtMost(max)
            if (end < value.length && value[end - 1].isHighSurrogate()) end--
            return buildString {
                for (index in 0 until end) append(value[index].let { if (it.isISOControl()) '\uFFFD' else it })
                if (end < value.length) append('…')
            }
        }

        fun checkedWire(path: String, wire: String): String {
            require(path.startsWith('/') && !path.startsWith("//") && path.length <= MAX_PATH_LENGTH)
            require(wire.isNotEmpty() || '\uFFFD' !in path)
            val result = wire.ifEmpty { SearchResult.encodePath(path) }
            require(result.length <= MAX_PATH_LENGTH && (result == "/" || !result.endsWith('/')))
            recentAccessWireIdentity(result)
            return result
        }

        fun initialTrail(video: ResourceRef): List<Directory> {
            val root = Directory("/", "根目录", nameVerified = true)
            return try {
                val wire = checkedWire(video.path, video.wirePath)
                val display = video.path.split('/').drop(1).dropLast(1)
                val encoded = wire.split('/').drop(1).dropLast(1)
                if (display.size != encoded.size || display.size > MAX_DEPTH) return listOf(root)
                buildList {
                    add(root)
                    for (index in encoded.indices) add(Directory(encoded.take(index + 1).joinToString("/", prefix = "/"), display[index]))
                }
            } catch (_: IllegalArgumentException) { listOf(root) }
            catch (_: IllegalStateException) { listOf(root) }
        }

    }
}

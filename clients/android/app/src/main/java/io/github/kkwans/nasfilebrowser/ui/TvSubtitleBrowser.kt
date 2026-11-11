package io.github.kkwans.nasfilebrowser.ui

import io.github.kkwans.fileway.remote.RemoteFailure
import io.github.kkwans.fileway.remote.RemoteResult
import io.github.kkwans.nasfilebrowser.app.TvSubtitleSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal data class TvSubtitleEntry(val id: String, val name: String, val directory: Boolean)
internal data class TvSubtitleDirectory(val id: String, val name: String)

internal data class TvSubtitleBrowserState(
    val trail: List<TvSubtitleDirectory> = emptyList(),
    val rows: List<TvSubtitleEntry> = emptyList(),
    val nextOffset: Int? = null,
    val nextPageToken: String? = null,
    val reading: Boolean = false,
    val preparing: Boolean = false,
    val pageError: TvSubtitleProblem? = null,
    val selectionError: TvSubtitleProblem? = null,
)

internal enum class TvSubtitleProblem(val text: String) {
    REOPEN("TV 播放、登录或字幕目录已变化，请重新打开字幕目录。"),
    NOT_CONNECTED("电视连接已断开，请重新连接后查看当前字幕。"),
    NOT_AVAILABLE("当前播放没有可用的 TV 字幕目录，请稍后重试。"),
    SERVER_UPGRADE_REQUIRED("更新 TV 当前连接的服务器后，即可在任意获准目录选择字幕。"),
    AUTH_REQUIRED("TV 登录已过期，请在电视重新连接服务器。"),
    ACCESS_DENIED("TV 当前账号无法访问此目录或读取字幕内容。"),
    STORAGE_AUTH_REQUIRED("TV 没有可读取的本机目录。请在电视的「字幕」→「添加字幕」→「本机字幕」中授权文件夹。"),
    STORAGE_REMOVED("TV 存储已断开，请检查连接后返回本机根目录。"),
    NOT_FOUND("目录或文件已移动或删除，请刷新目录。"),
    DIRECTORY_TOO_LARGE("当前服务返回的目录过大，请打开其他目录。"),
    DIRECTORY_CHANGED("目录内容已变化，请重新读取。"),
    LOCAL_SCAN_LIMIT("已达到本机目录扫描上限。可选择已找到的字幕，或在电视上授权更具体的文件夹。"),
    RESULT_NOT_RETAINED("这页结果已失效，请重新读取。"),
    READ_FAILED("无法读取目录，请重试。"),
    TIMEOUT("等待 TV 或服务器响应超时。"),
    FILE_CHANGED("字幕文件已变化，请刷新目录后重选。"),
    FILE_UNAVAILABLE("字幕文件已不可用，请刷新目录或选择其他文件。"),
    FORMAT_UNSUPPORTED("此文件无法作为外挂字幕读取。"),
    FILE_TOO_LARGE("字幕文件超过 TV 支持的大小，请选择其他文件。"),
    FILE_VERSION_UNAVAILABLE("TV 无法确认此文件的版本，请选择其他字幕文件。"),
    PLAYER_NOT_READY("视频或字幕状态已变化，请稍后重选。"),
    SELECTION_FAILED("TV 未能加载此字幕，请重试或选择其他文件。"),
    RESULT_UNKNOWN("字幕选择结果尚未确认，请返回字幕页查看 TV 当前轨道。");

    val resetBrowser: Boolean get() = this in setOf(
        REOPEN, NOT_CONNECTED, NOT_AVAILABLE, SERVER_UPGRADE_REQUIRED, AUTH_REQUIRED, STORAGE_AUTH_REQUIRED, STORAGE_REMOVED,
    )
    val refreshDirectory: Boolean get() = this in setOf(NOT_FOUND, FILE_CHANGED, FILE_UNAVAILABLE)

    fun messageFor(source: TvSubtitleSource): String =
        if (this == ACCESS_DENIED && source == TvSubtitleSource.LOCAL)
            "TV 无法访问此目录或文件，请在电视的「本机字幕」中检查读取授权。"
        else text
}

/** One screen-owned browser. Its caller serializes actions and binds all commands to one TV snapshot. */
internal class TvSubtitleBrowser(
    private val source: TvSubtitleSource,
    private val pollIntervalMs: Long = 1_200,
    private val serverCursor: Boolean = source == TvSubtitleSource.SERVER,
    private val command: suspend (String, JSONObject) -> RemoteResult,
) {
    private val mutable = MutableStateFlow(TvSubtitleBrowserState())
    val state = mutable.asStateFlow()
    private var browserId: String? = null
    private var failedPage: PageRequest? = null

    private data class PageRequest(val directoryId: String, val offset: Int, val refresh: Boolean = false, val token: String? = null)
    private class Failure(val problem: TvSubtitleProblem) : Exception()

    suspend fun open() {
        mutable.value = TvSubtitleBrowserState(reading = true)
        failedPage = null
        try {
            val response = withTimeoutOrNull(READ_TIMEOUT_MS) {
                val payload = JSONObject().put("source", source.wireName)
                if (serverCursor) payload.put("cursorVersion", 1)
                call("open", payload)
            } ?: throw Failure(TvSubtitleProblem.TIMEOUT)
            check(response.getString("status") == "PENDING" && response.getInt("offset") == 0)
            val handle = response.readId("browserId")
            browserId = handle
            val trail = response.readTrail(handle)
            mutable.value = mutable.value.copy(trail = trail)
            readPage(PageRequest(trail.last().id, 0), alreadyPending = true)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            showPageError(failure.problem(TvSubtitleProblem.READ_FAILED))
        } finally {
            mutable.value = mutable.value.copy(reading = false)
        }
    }

    suspend fun navigate(id: String) {
        val current = mutable.value
        if (current.trail.none { it.id == id } && current.rows.none { it.id == id && it.directory }) return
        readPage(PageRequest(id, 0))
    }

    suspend fun loadMore() {
        val current = mutable.value
        val directoryId = current.trail.lastOrNull()?.id ?: return
        if (serverCursor) {
            val token = current.nextPageToken ?: return
            readPage(PageRequest(directoryId, 0, token = token))
        } else {
            val offset = current.nextOffset ?: return
            readPage(PageRequest(directoryId, offset))
        }
    }

    suspend fun retry() {
        val page = failedPage
        if (browserId == null || page == null) open() else readPage(page)
    }

    suspend fun refresh() {
        val directoryId = mutable.value.trail.lastOrNull()?.id ?: return
        readPage(PageRequest(directoryId, 0, refresh = true))
    }

    private suspend fun readPage(request: PageRequest, alreadyPending: Boolean = false) {
        val handle = browserId ?: return
        val current = mutable.value
        val retainRows = request.offset > 0 || request.token != null
        mutable.value = current.copy(
            rows = if (retainRows) current.rows else emptyList(),
            nextOffset = if (retainRows) current.nextOffset else null,
            nextPageToken = if (retainRows) current.nextPageToken else null,
            reading = true,
            pageError = null,
            selectionError = current.selectionError?.takeIf { it == TvSubtitleProblem.RESULT_UNKNOWN },
        )
        var page = request
        try {
            val loaded = withTimeoutOrNull(READ_TIMEOUT_MS) {
                if (alreadyPending) delay(pollIntervalMs)
                while (true) {
                    val payload = JSONObject().put("browserId", handle).put("directoryId", page.directoryId)
                    if (serverCursor) page.token?.let { payload.put("pageToken", it) } else payload.put("offset", page.offset)
                    if (page.refresh) payload.put("refresh", true)
                    page = page.copy(refresh = false)
                    val response = call("page", payload, returnError = true)
                    val status = response.getString("status")
                    if (status == "ERROR") {
                        val problem = problemForCode(response.optString("code"), TvSubtitleProblem.READ_FAILED)
                        if (response.has("breadcrumbs")) {
                            val trail = response.readTrail(handle)
                            mutable.value = mutable.value.copy(trail = trail)
                            page = page.copy(directoryId = trail.last().id)
                        }
                        if (problem == TvSubtitleProblem.DIRECTORY_CHANGED && (page.offset > 0 || page.token != null)) {
                            mutable.value = mutable.value.copy(rows = emptyList(), nextOffset = null, nextPageToken = null)
                            page = PageRequest(page.directoryId, 0, refresh = true)
                            continue
                        }
                        throw Failure(problem)
                    }
                    val trail = response.readTrail(handle)
                    val responseOffset = if (source == TvSubtitleSource.LOCAL) response.readLocalOffset("offset")
                        else response.getInt("offset")
                    check(trail.last().id == page.directoryId && (serverCursor || responseOffset == page.offset))
                    mutable.value = mutable.value.copy(trail = trail)
                    when (status) {
                        "READY" -> {
                            publishPage(response, page)
                            return@withTimeoutOrNull true
                        }
                        "PENDING" -> delay(pollIntervalMs)
                        else -> throw Failure(TvSubtitleProblem.READ_FAILED)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
            if (loaded != true) throw Failure(TvSubtitleProblem.TIMEOUT)
            failedPage = null
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            val problem = failure.problem(TvSubtitleProblem.READ_FAILED)
            val mustRefresh = problem in setOf(TvSubtitleProblem.ACCESS_DENIED, TvSubtitleProblem.NOT_FOUND,
                TvSubtitleProblem.DIRECTORY_CHANGED, TvSubtitleProblem.DIRECTORY_TOO_LARGE)
            if (mustRefresh) {
                mutable.value = mutable.value.copy(rows = emptyList(), nextOffset = null, nextPageToken = null)
                page = PageRequest(page.directoryId, 0, refresh = true)
            }
            failedPage = page.takeUnless { problem == TvSubtitleProblem.LOCAL_SCAN_LIMIT }
            if (problem == TvSubtitleProblem.LOCAL_SCAN_LIMIT) mutable.value = mutable.value.copy(nextOffset = null)
            showPageError(problem)
        } finally {
            mutable.value = mutable.value.copy(reading = false)
        }
    }

    private fun publishPage(response: JSONObject, page: PageRequest) {
        val items = response.getJSONArray("items")
        check(items.length() <= MAX_PAGE_ROWS && response.has(if (serverCursor) "nextPageToken" else "nextOffset"))
        val rows = List(items.length()) { index ->
            val item = items.getJSONObject(index)
            TvSubtitleEntry(item.readId("id"), item.readName(), item.getBoolean("directory"))
        }
        val current = mutable.value
        val merged = current.rows + rows
        if (serverCursor) {
            check(page.token == null || page.token == current.nextPageToken)
            check(merged.map { it.id }.toSet().size == merged.size)
            val next = if (response.isNull("nextPageToken")) null else response.readId("nextPageToken")
            check(next == null || next != page.token)
            val retained = if (response.has("firstRetainedId")) {
                val first = if (response.isNull("firstRetainedId")) null else response.readId("firstRetainedId")
                if (first == null) { check(merged.isEmpty()); emptyList() }
                else merged.drop(merged.indexOfFirst { it.id == first }.also { check(it >= 0) })
            } else merged.takeLast(MAX_CURSOR_ROWS)
            check(retained.size <= MAX_CURSOR_ROWS)
            mutable.value = current.copy(rows = retained, nextPageToken = next)
        } else if (source == TvSubtitleSource.LOCAL) {
            // Local cursors count scanned provider entries, including rows that were filtered out.
            check(page.offset == 0 || page.offset == current.nextOffset)
            val next = if (response.isNull("nextOffset")) null else response.readLocalOffset("nextOffset")
            check(next == null || next > page.offset)
            val unique = merged.distinctBy { it.id }
            check(unique.size <= MAX_ROWS)
            mutable.value = current.copy(rows = unique, nextOffset = next)
        } else {
            check(current.rows.size == page.offset)
            check(merged.size <= MAX_ROWS && merged.map { it.id }.toSet().size == merged.size)
            val next = if (response.isNull("nextOffset")) null else response.getInt("nextOffset")
            check(next == null || (rows.isNotEmpty() && next == merged.size && next < MAX_ROWS))
            mutable.value = current.copy(rows = merged, nextOffset = next)
        }
    }

    suspend fun select(fileId: String): Boolean {
        val handle = browserId ?: return false
        val current = mutable.value
        if (current.reading || current.preparing || current.selectionError == TvSubtitleProblem.RESULT_UNKNOWN ||
            current.rows.none { it.id == fileId && !it.directory }) return false
        mutable.value = current.copy(preparing = true, selectionError = null)
        try {
            val applied = withTimeoutOrNull(SELECTION_TIMEOUT_MS) {
                // Send the file intent exactly once. Even a lost first reply cannot authorize a replay.
                var response = call("select", JSONObject().put("browserId", handle).put("fileId", fileId), selection = true)
                val operationId = response.readId("operationId")
                while (response.getString("status") == "PENDING") {
                    delay(pollIntervalMs)
                    response = call("select", JSONObject().put("browserId", handle).put("operationId", operationId),
                        selection = true)
                    check(response.readId("operationId") == operationId)
                }
                check(response.getString("status") == "APPLIED")
                true
            }
            if (applied == true) return true
            throw Failure(TvSubtitleProblem.RESULT_UNKNOWN)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            val problem = failure.problem(TvSubtitleProblem.RESULT_UNKNOWN)
            if (problem.resetBrowser) showPageError(problem)
            else mutable.value = mutable.value.copy(selectionError = problem)
            return false
        } finally {
            mutable.value = mutable.value.copy(preparing = false)
        }
    }

    suspend fun close() {
        val handle = browserId
        browserId = null
        failedPage = null
        mutable.value = TvSubtitleBrowserState()
        if (handle != null) {
            try {
                call("close", JSONObject().put("browserId", handle))
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                // The connection or session can be gone already. TV expires its own handle.
            }
        }
    }

    private fun showPageError(problem: TvSubtitleProblem) {
        mutable.value = if (problem.resetBrowser) {
            failedPage = null
            // Keep the handle only for best-effort cleanup or replacement by a new open.
            TvSubtitleBrowserState(pageError = problem)
        } else mutable.value.copy(pageError = problem)
    }

    private suspend fun call(
        operation: String,
        payload: JSONObject,
        selection: Boolean = false,
        returnError: Boolean = false,
    ): JSONObject {
        val result = command("subtitle.browse.$operation", payload)
        currentCoroutineContext().ensureActive()
        val fallback = if (selection) TvSubtitleProblem.RESULT_UNKNOWN else TvSubtitleProblem.READ_FAILED
        if (!result.completed) {
            val problem = if (result.uncertain) {
                if (selection) TvSubtitleProblem.RESULT_UNKNOWN else TvSubtitleProblem.TIMEOUT
            } else if (selection && result.code == "RESULT_NOT_RETAINED") {
                TvSubtitleProblem.RESULT_UNKNOWN
            } else problemForCode(result.code, fallback)
            throw Failure(problem)
        }
        val response = result.payload ?: throw Failure(fallback)
        if (!returnError && response.getString("status") == "ERROR")
            throw Failure(problemForCode(response.optString("code"), fallback))
        return response
    }

    private companion object {
        const val READ_TIMEOUT_MS = 18_000L
        const val SELECTION_TIMEOUT_MS = 115_000L
        const val MAX_PAGE_ROWS = 32
        const val MAX_ROWS = 4_096
        const val MAX_CURSOR_ROWS = 256
        const val MAX_LOCAL_SCAN_OFFSET = 65_536

        fun problemForCode(code: String, fallback: TvSubtitleProblem): TvSubtitleProblem = when (code) {
            "STALE_SCOPE", "SCOPE_CHANGED", "INVALID_REQUEST" -> TvSubtitleProblem.REOPEN
            else -> TvSubtitleProblem.entries.firstOrNull { it.name == code } ?: fallback
        }

        fun Exception.problem(fallback: TvSubtitleProblem): TvSubtitleProblem = when (this) {
            is Failure -> problem
            is RemoteFailure -> problemForCode(code, fallback)
            else -> fallback
        }

        fun JSONObject.readId(key: String): String = (get(key) as String).also {
            require(it.isNotBlank() && it.length <= 128 && it.none { char -> char.isISOControl() })
        }

        fun JSONObject.readName(): String = (get("name") as String).also {
            require(it.isNotEmpty() && it.length <= 1024 && it.none { char -> char.isISOControl() })
        }

        fun JSONObject.readLocalOffset(key: String): Int {
            val value = (get(key) as? Number)?.toString()?.toIntOrNull()
            require(value != null && value in 0..MAX_LOCAL_SCAN_OFFSET)
            return value
        }

        fun JSONObject.readTrail(handle: String): List<TvSubtitleDirectory> {
            check(readId("browserId") == handle)
            val crumbs = getJSONArray("breadcrumbs")
            check(crumbs.length() in 1..65)
            val trail = List(crumbs.length()) { index ->
                val crumb = crumbs.getJSONObject(index)
                TvSubtitleDirectory(crumb.readId("id"), crumb.readName())
            }
            check(trail.first().id == readId("rootId") && trail.last().id == readId("directoryId") &&
                trail.map { it.id }.toSet().size == trail.size)
            return trail
        }
    }
}

package io.github.kkwans.nasfilebrowser.ui

import io.github.kkwans.fileway.remote.RemoteResult
import io.github.kkwans.nasfilebrowser.app.TvSubtitleSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TvSubtitleBrowserTest {
    @Test fun browsesFromMovieDirectoryToAnotherBranchAndWaitsForApplied() = runBlocking {
        val tv = FakeTv(
            completed(outline("PENDING", "movie", 0)),
            completed(outline("PENDING", "movie", 0)),
            completed(page("movie", 0, null, file("same", "片名.srt"))),
            completed(page("root", 0, null, file("library", "字幕库", directory = true))),
            completed(page("library", 0, null, file("other", "其他语言 % #.ass"))),
            completed(operation("PENDING")),
            completed(operation("PENDING")),
            completed(operation("APPLIED")),
            completed(JSONObject().put("status", "CLOSED")),
        )
        tv.browser.open()
        assertEquals(listOf("root", "movie"), tv.browser.state.value.trail.map { it.id })
        tv.browser.navigate("root")
        tv.browser.navigate("library")
        assertEquals("其他语言 % #.ass", tv.browser.state.value.rows.single().name)
        assertTrue(tv.browser.select("other"))
        tv.browser.close()

        assertEquals("server", tv.calls.first().payload.getString("source"))
        assertEquals(listOf("movie", "movie", "root", "library"), tv.calls.filter { it.operation == "page" }
            .map { it.payload.getString("directoryId") })
        val selections = tv.calls.filter { it.operation == "select" }.map { it.payload }
        assertEquals("other", selections.first().getString("fileId"))
        assertTrue(selections.drop(1).all { !it.has("fileId") && it.getString("operationId") == "import" })
        assertEquals("close", tv.calls.last().operation)
        assertTrue(tv.browser.state.value.rows.isEmpty())
    }

    @Test fun changedDirectoryDropsOldPageAndRestartsFromZero() = runBlocking {
        val tv = FakeTv(
            completed(outline("PENDING", "movie", 0)),
            completed(page("movie", 0, 1, file("old", "旧.srt"))),
            completed(outline("ERROR", "movie", 0).put("code", "DIRECTORY_CHANGED")),
            completed(outline("PENDING", "movie", 0)),
            completed(page("movie", 0, null, file("new", "新.srt"))),
        )
        tv.browser.open()
        assertEquals(1, tv.browser.state.value.nextOffset)
        tv.browser.loadMore()

        assertEquals(listOf(0, 1, 0, 0), tv.calls.filter { it.operation == "page" }.map { it.payload.getInt("offset") })
        assertEquals(listOf("new"), tv.browser.state.value.rows.map { it.id })
        assertNull(tv.browser.state.value.nextOffset)
    }

    @Test fun pageRetryPreservesRowsAndRefreshOnlyStartsOnce() = runBlocking {
        val tv = FakeTv(
            completed(outline("PENDING", "movie", 0)),
            completed(page("movie", 0, 1, file("one", "第一条.srt"))),
            RemoteResult("read", "REJECTED", "RESULT_NOT_RETAINED"),
            completed(page("movie", 1, null, file("two", "第二条.vtt"))),
            completed(outline("PENDING", "movie", 0)),
            completed(page("movie", 0, null, file("new", "新.srt"))),
        )
        tv.browser.open()
        tv.browser.loadMore()
        assertEquals(TvSubtitleProblem.RESULT_NOT_RETAINED, tv.browser.state.value.pageError)
        assertEquals(listOf("one"), tv.browser.state.value.rows.map { it.id })
        tv.browser.retry()
        assertEquals(listOf("one", "two"), tv.browser.state.value.rows.map { it.id })
        tv.browser.refresh()

        val requests = tv.calls.filter { it.operation == "page" }.map { it.payload }
        assertEquals(listOf(0, 1, 1, 0, 0), requests.map { it.getInt("offset") })
        assertEquals(listOf(false, false, false, true, false), requests.map { it.optBoolean("refresh") })
        assertEquals(listOf("new"), tv.browser.state.value.rows.map { it.id })
    }

    @Test fun explicitSelectionFailureKeepsDirectoryAndUnknownWriteCannotReplay() = runBlocking {
        val tv = FakeTv(
            completed(outline("PENDING", "movie", 0)),
            completed(page("movie", 0, null, file("sub", "字幕.srt"))),
            completed(operation("PENDING")),
            completed(operation("ERROR").put("code", "FILE_CHANGED")),
            RemoteResult("write", "UNKNOWN", "RESULT_PENDING_TIMEOUT"),
        )
        tv.browser.open()
        assertFalse(tv.browser.select("sub"))
        assertEquals(TvSubtitleProblem.FILE_CHANGED, tv.browser.state.value.selectionError)
        assertEquals("sub", tv.browser.state.value.rows.single().id)

        assertFalse(tv.browser.select("sub"))
        assertEquals(TvSubtitleProblem.RESULT_UNKNOWN, tv.browser.state.value.selectionError)
        val callCount = tv.calls.size
        assertFalse(tv.browser.select("sub"))
        assertEquals(callCount, tv.calls.size)
    }

    @Test fun staleScopeClearsNamesAndRequiresNewOpen() = runBlocking {
        val tv = FakeTv(
            completed(outline("PENDING", "movie", 0)),
            completed(page("movie", 0, 1, file("private", "旧账号名称.srt"))),
            RemoteResult("read", "REJECTED", "STALE_SCOPE"),
            completed(outline("PENDING", "root", 0)),
            completed(page("root", 0, null)),
        )
        tv.browser.open()
        tv.browser.loadMore()
        assertEquals(TvSubtitleProblem.REOPEN, tv.browser.state.value.pageError)
        assertTrue(tv.browser.state.value.rows.isEmpty())
        assertTrue(tv.browser.state.value.trail.isEmpty())
        assertFalse(tv.browser.select("private"))
        tv.browser.retry()
        assertEquals(listOf("open", "page", "page", "open", "page"), tv.calls.map { it.operation })
        assertEquals("root", tv.browser.state.value.trail.single().id)
    }

    @Test fun localCapabilityAndAuthorizationAreIndependentOfServer() = runBlocking {
        val capabilities = JSONObject().put("subtitleBrowseLocal", 1)
        assertTrue(TvSubtitleSource.LOCAL.supportedBy(capabilities))
        assertFalse(TvSubtitleSource.SERVER.supportedBy(capabilities))
        assertFalse(TvSubtitleSource.LOCAL.supportedBy(JSONObject().put("subtitleBrowseServer", 1)))
        val content = JSONObject().put("subtitleStorageRevision", 3L)
        assertEquals(3L, TvSubtitleSource.LOCAL.storageRevision(content))
        assertNull(TvSubtitleSource.SERVER.storageRevision(content))
        assertNull(TvSubtitleSource.LOCAL.storageRevision(JSONObject()))
        val tv = FakeTv(
            completed(outline("PENDING", "root", 0)),
            completed(outline("ERROR", "root", 0).put("code", "STORAGE_AUTH_REQUIRED")),
            completed(outline("PENDING", "root", 0)),
            completed(page("root", 0, null, file("usb", "U 盘字幕", directory = true))),
            completed(page("usb", 0, null, file("sub", "字幕.ass"))),
            completed(operation("PENDING")),
            completed(operation("APPLIED")),
            source = TvSubtitleSource.LOCAL,
        )
        tv.browser.open()
        assertEquals(TvSubtitleProblem.STORAGE_AUTH_REQUIRED, tv.browser.state.value.pageError)
        assertTrue(tv.browser.state.value.rows.isEmpty())

        tv.browser.retry()
        tv.browser.navigate("usb")
        assertEquals("字幕.ass", tv.browser.state.value.rows.single().name)
        assertTrue(tv.browser.select("sub"))
        assertEquals(listOf("tvLocal", "tvLocal"), tv.calls.filter { it.operation == "open" }
            .map { it.payload.getString("source") })
    }

    @Test fun localUnversionedFileKeepsDirectoryAndRemovedStorageRequiresNewRoot() = runBlocking {
        val tv = FakeTv(
            completed(outline("PENDING", "root", 0)),
            completed(page("root", 0, null, file("usb", "U 盘字幕", directory = true))),
            completed(page("usb", 0, null, file("bad", "版本未知.srt"), file("sub", "字幕.ass"))),
            completed(operation("ERROR").put("code", "FILE_VERSION_UNAVAILABLE")),
            completed(operation("PENDING")),
            completed(operation("ERROR").put("code", "STORAGE_REMOVED")),
            completed(outline("PENDING", "root", 0)),
            completed(page("root", 0, null)),
            source = TvSubtitleSource.LOCAL,
        )
        tv.browser.open()
        tv.browser.navigate("usb")
        assertFalse(tv.browser.select("bad"))
        assertEquals(TvSubtitleProblem.FILE_VERSION_UNAVAILABLE, tv.browser.state.value.selectionError)
        assertEquals(listOf("bad", "sub"), tv.browser.state.value.rows.map { it.id })

        assertFalse(tv.browser.select("sub"))
        assertEquals(TvSubtitleProblem.STORAGE_REMOVED, tv.browser.state.value.pageError)
        assertTrue(tv.browser.state.value.rows.isEmpty())
        assertTrue(tv.browser.state.value.trail.isEmpty())
        assertFalse(tv.browser.select("sub"))

        tv.browser.retry()
        assertEquals("root", tv.browser.state.value.trail.single().id)
        assertEquals(listOf("tvLocal", "tvLocal"), tv.calls.filter { it.operation == "open" }
            .map { it.payload.getString("source") })
    }

    @Test fun localEmptyScanPageContinuesToSubtitleBeyondFilteredEntries() = runBlocking {
        val tv = FakeTv(
            completed(outline("PENDING", "root", 0)),
            completed(page("root", 0, null, file("usb", "U 盘字幕", directory = true))),
            completed(page("usb", 0, 256)),
            completed(outline("PENDING", "usb", 256)),
            completed(page("usb", 256, null, file("sub", "扫描后找到.srt"))),
            source = TvSubtitleSource.LOCAL,
        )
        tv.browser.open()
        tv.browser.navigate("usb")
        assertTrue(tv.browser.state.value.rows.isEmpty())
        assertEquals(256, tv.browser.state.value.nextOffset)
        assertNull(tv.browser.state.value.pageError)

        tv.browser.loadMore()
        assertEquals("扫描后找到.srt", tv.browser.state.value.rows.single().name)
        assertEquals(listOf(0, 0, 256, 256), tv.calls.filter { it.operation == "page" }.map { it.payload.getInt("offset") })
        assertNull(tv.browser.state.value.nextOffset)
    }

    @Test fun localByteTruncationAndDirectoryChangeUseTvContinuationsAndStableIds() = runBlocking {
        val tv = FakeTv(
            completed(outline("PENDING", "usb", 0)),
            // The TV can truncate the wire page before the scan window ends.
            completed(page("usb", 0, 91, file("first", "第一条.ass"), file("second", "第二条.ass"))),
            completed(page("usb", 91, 4_103, file("first", "第一条.ass"), file("third", "第三条.srt"))),
            completed(outline("ERROR", "usb", 0).put("code", "DIRECTORY_CHANGED")),
            completed(outline("PENDING", "usb", 0)),
            completed(page("usb", 0, null, file("fresh", "目录更新后.srt"))),
            source = TvSubtitleSource.LOCAL,
        )
        tv.browser.open()
        assertEquals(91, tv.browser.state.value.nextOffset)
        tv.browser.loadMore()
        assertEquals(listOf("first", "second", "third"), tv.browser.state.value.rows.map { it.id })
        assertEquals(4_103, tv.browser.state.value.nextOffset)

        tv.browser.loadMore()
        assertEquals(listOf("fresh"), tv.browser.state.value.rows.map { it.id })
        val pages = tv.calls.filter { it.operation == "page" }.map { it.payload }
        assertEquals(listOf(0, 91, 4_103, 0, 0), pages.map { it.getInt("offset") })
        assertEquals(listOf(false, false, false, true, false), pages.map { it.optBoolean("refresh") })
    }

    @Test fun localRejectsNonProgressingAndInvalidContinuationsWithoutPublishingTheirRows() = runBlocking {
        for (invalid in listOf(-1, 255, 256, 65_537, 257.5, 4_294_967_552L, "257")) {
            val tv = FakeTv(
                completed(outline("PENDING", "usb", 0)),
                completed(page("usb", 0, 256, file("existing", "已找到.srt"))),
                completed(page("usb", 256, null, file("invalid", "错误页.srt")).put("nextOffset", invalid)),
                completed(page("usb", 256, null, file("next", "下一页.srt"))),
                source = TvSubtitleSource.LOCAL,
            )
            tv.browser.open()
            tv.browser.loadMore()
            assertEquals("continuation=$invalid", TvSubtitleProblem.READ_FAILED, tv.browser.state.value.pageError)
            assertEquals(listOf("existing"), tv.browser.state.value.rows.map { it.id })
            assertEquals(256, tv.browser.state.value.nextOffset)

            tv.browser.retry()
            assertEquals(listOf(0, 256, 256), tv.calls.filter { it.operation == "page" }.map { it.payload.getInt("offset") })
            assertEquals(listOf("existing", "next"), tv.browser.state.value.rows.map { it.id })
        }
    }

    @Test fun localScanLimitStopsPagingAndPreservesIssuedRows() = runBlocking {
        val tv = FakeTv(
            completed(outline("PENDING", "usb", 0)),
            completed(page("usb", 0, 4_096, file("existing", "已找到.srt"))),
            completed(page("usb", 4_096, 65_536)),
            completed(outline("ERROR", "usb", 65_536).put("code", "LOCAL_SCAN_LIMIT")),
            completed(operation("APPLIED")),
            source = TvSubtitleSource.LOCAL,
        )
        tv.browser.open()
        tv.browser.loadMore()
        assertEquals(65_536, tv.browser.state.value.nextOffset)
        tv.browser.loadMore()
        assertEquals(TvSubtitleProblem.LOCAL_SCAN_LIMIT, tv.browser.state.value.pageError)
        assertNull(tv.browser.state.value.nextOffset)
        assertEquals(listOf("existing"), tv.browser.state.value.rows.map { it.id })
        assertTrue(tv.browser.select("existing"))
    }

    @Test fun serverStillRequiresOffsetToMatchDisplayedRowCount() = runBlocking {
        val tv = FakeTv(
            completed(outline("PENDING", "movie", 0)),
            completed(page("movie", 0, 256, file("sub", "不应显示.srt"))),
        )
        tv.browser.open()
        assertEquals(TvSubtitleProblem.READ_FAILED, tv.browser.state.value.pageError)
        assertTrue(tv.browser.state.value.rows.isEmpty())
        assertNull(tv.browser.state.value.nextOffset)
    }

    @Test fun scopedCursorSupportsEmptyPagesAndNeverSendsServerOffsets() = runBlocking {
        assertFalse(TvSubtitleSource.SERVER.supportedBy(JSONObject().put("subtitleBrowseServer", 1)))
        assertTrue(TvSubtitleSource.SERVER.supportedBy(JSONObject().put("subtitleBrowseServerCursor", 1)))
        val tv = FakeTv(
            completed(outline("PENDING", "movie", 0)),
            completed(cursorPage("movie", 0, "next")),
            completed(cursorPage("movie", 1, null, file("late", "字幕.srt"))),
            cursorMode = true,
        )
        tv.browser.open()
        assertTrue(tv.browser.state.value.rows.isEmpty())
        assertEquals("next", tv.browser.state.value.nextPageToken)
        tv.browser.loadMore()
        assertEquals("late", tv.browser.state.value.rows.single().id)
        assertNull(tv.browser.state.value.nextPageToken)
        assertEquals(1, tv.calls.first().payload.getInt("cursorVersion"))
        val pages = tv.calls.filter { it.operation == "page" }.map { it.payload }
        assertTrue(pages.all { !it.has("offset") })
        assertEquals("next", pages.last().getString("pageToken"))
    }

    @Test fun scopedCursorRetainsOnlyTheRecentSelectableWindow() = runBlocking {
        val responses = mutableListOf(completed(outline("PENDING", "movie", 0)))
        repeat(10) { page ->
            val entries = (0 until 32).map { i -> file("${page}_$i", "${page}_$i.srt") }.toTypedArray()
            responses += completed(cursorPage("movie", page * 32, if (page == 9) null else "token$page", *entries))
        }
        val tv = FakeTv(*responses.toTypedArray(), cursorMode = true)
        tv.browser.open()
        repeat(9) { tv.browser.loadMore() }
        assertEquals(256, tv.browser.state.value.rows.size)
        assertEquals("2_0", tv.browser.state.value.rows.first().id)
        assertEquals("9_31", tv.browser.state.value.rows.last().id)
    }

    @Test fun scopedCursorRemovesRowsEvictedByTvMemoryBudget() = runBlocking {
        val tv = FakeTv(
            completed(outline("PENDING", "movie", 0)),
            completed(cursorPage("movie", 0, "next", file("old", "旧.srt")).put("firstRetainedId", "old")),
            completed(cursorPage("movie", 1, null, file("new", "新.srt")).put("firstRetainedId", "new")),
            cursorMode = true,
        )
        tv.browser.open()
        tv.browser.loadMore()
        assertEquals(listOf("new"), tv.browser.state.value.rows.map { it.id })
        assertFalse(tv.browser.select("old"))
    }

    @Test fun scopedCursorRetriesWithoutLosingRowsAndReopensChangedDirectory() = runBlocking {
        val tv = FakeTv(
            completed(outline("PENDING", "movie", 0)),
            completed(cursorPage("movie", 0, "one", file("old", "旧.srt"))),
            RemoteResult("read", "REJECTED", "RESULT_NOT_RETAINED"),
            completed(cursorPage("movie", 1, "two", file("second", "第二.srt"))),
            completed(outline("ERROR", "movie", 0).put("code", "DIRECTORY_CHANGED")),
            completed(outline("PENDING", "movie", 0)),
            completed(cursorPage("movie", 0, null, file("new", "新.srt"))),
            cursorMode = true,
        )
        tv.browser.open()
        tv.browser.loadMore()
        assertEquals(listOf("old"), tv.browser.state.value.rows.map { it.id })
        tv.browser.retry()
        assertEquals(listOf("old", "second"), tv.browser.state.value.rows.map { it.id })
        tv.browser.loadMore()
        assertEquals(listOf("new"), tv.browser.state.value.rows.map { it.id })
        val requests = tv.calls.filter { it.operation == "page" }.map { it.payload }
        assertEquals("one", requests[1].getString("pageToken"))
        assertEquals("one", requests[2].getString("pageToken"))
        assertTrue(requests[4].getBoolean("refresh"))
    }

    @Test fun latePageAfterCancellationNeverPublishes() = runBlocking {
        val sent = mutableListOf<String>()
        val browser = TvSubtitleBrowser(TvSubtitleSource.SERVER, pollIntervalMs = 0) { operation, _ ->
            sent += operation
            when (operation) {
                "subtitle.browse.open" -> completed(outline("PENDING", "movie", 0))
                "subtitle.browse.close" -> completed(JSONObject().put("status", "CLOSED"))
                else -> try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    completed(page("movie", 0, null, file("late", "迟到.srt")))
                }
            }
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { browser.open() }
        job.cancelAndJoin()
        assertTrue(browser.state.value.rows.isEmpty())
        browser.close()
        assertEquals(listOf("subtitle.browse.open", "subtitle.browse.page", "subtitle.browse.close"), sent)
        assertTrue(browser.state.value.trail.isEmpty())
    }

    private class FakeTv(vararg responses: RemoteResult, source: TvSubtitleSource = TvSubtitleSource.SERVER, cursorMode: Boolean = false) {
        data class Call(val operation: String, val payload: JSONObject)
        private val queue = ArrayDeque(responses.toList())
        val calls = mutableListOf<Call>()
        val browser = TvSubtitleBrowser(source, pollIntervalMs = 0, serverCursor = cursorMode) { operation, payload ->
            calls += Call(operation.substringAfterLast('.'), payload)
            queue.removeFirst()
        }
    }

    private companion object {
        fun completed(payload: JSONObject) = RemoteResult("receipt", "COMPLETED", payload = payload)
        fun operation(status: String) = JSONObject().put("status", status).put("operationId", "import")
        fun file(id: String, name: String, directory: Boolean = false) =
            JSONObject().put("id", id).put("name", name).put("directory", directory)
        fun outline(status: String, directory: String, offset: Int): JSONObject {
            fun crumb(id: String, name: String) = JSONObject().put("id", id).put("name", name)
            val crumbs = JSONArray().put(crumb("root", "根目录"))
            if (directory != "root") crumbs.put(crumb(directory, if (directory == "movie") "电影" else "字幕库"))
            return JSONObject().put("status", status).put("browserId", "handle").put("rootId", "root")
                .put("directoryId", directory).put("breadcrumbs", crumbs).put("offset", offset)
        }
        fun page(directory: String, offset: Int, next: Int?, vararg files: JSONObject): JSONObject =
            outline("READY", directory, offset).put("items", JSONArray(files.toList())).put("nextOffset", next ?: JSONObject.NULL)
        fun cursorPage(directory: String, offset: Int, next: String?, vararg files: JSONObject): JSONObject =
            outline("READY", directory, offset).put("items", JSONArray(files.toList())).put("nextPageToken", next ?: JSONObject.NULL)
    }
}

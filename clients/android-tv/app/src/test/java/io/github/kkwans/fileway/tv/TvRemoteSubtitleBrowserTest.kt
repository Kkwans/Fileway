package io.github.kkwans.fileway.tv

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.ScopedSubtitleEntry
import io.github.kkwans.nasfilebrowser.data.ScopedSubtitleResources
import io.github.kkwans.nasfilebrowser.data.ServiceException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TvRemoteSubtitleBrowserTest {
    @Test fun scopedPagesHidePathsAndBindHandlesToTheRequestingPeer() = fixture {
        val path = "/剧集 #"
        val wire = "/%BE%E7%BC%AF%20%23"
        video = ResourceRef("$path/片.mkv", "$wire/%E7%89%87.mkv", "片.mkv", false, "video", 30)
        rows[wire] = listOf(entry("$path/其他", "$wire/%C6%E4%CB%FB", "其他", true),
            entry("$path/片.srt", "$wire/%E7%89%87.srt", "片.srt"))
        val opened = openCursor()
        val first = cursorPageReady(opened)
        assertEquals("READY", first.getString("status"))
        assertEquals("片.srt", first.getJSONArray("items").getJSONObject(1).getString("name"))
        assertFalse(first.toString().contains("wirePath"))
        assertFalse(first.toString().contains("$wire/"))
        assertFalse(first.toString().contains("$path/"))
        assertEquals("STALE_SCOPE", call("page", cursorRequest(opened), "other-peer", "connection").getString("code"))
        assertEquals("STALE_SCOPE", call("page", cursorRequest(opened), "peer", "new-connection").getString("code"))
        assertEquals("NOT_FOUND", call("select", handle(opened).put("fileId", "$wire/%E7%89%87.srt")).getString("code"))
        val child = first.getJSONArray("items").getJSONObject(0).getString("id")
        assertEquals("READY", cursorPageReady(opened, child).getString("status"))
        assertEquals("READY", cursorPageReady(opened, opened.getString("rootId")).getString("status"))
        assertEquals("NOT_FOUND", call("page", cursorRequest(opened, child)).getString("code"))
        assertTrue(endpoints.all { it == "/api/resource-directory-pages" })
    }

    @Test fun oldServerIsNotAdvertisedAndExplicitOpenFailsClosed() = fixture {
        scopedEnabled = false
        assertFalse(serverAvailable())
        assertEquals("SERVER_UPGRADE_REQUIRED", open().getString("code"))
        assertTrue(endpoints.isEmpty())
    }

    @Test fun sparseScanCanContinueWithoutExposingServerCursors() = fixture {
        pageHook = { wire, cursor ->
            val index = cursor?.takeLast(8)?.toInt(16) ?: 0
            val entries = if (index == 9) listOf(entry("/film/late.srt", "/film/late.srt", "late.srt")) else emptyList()
            val next = if (index < 9) "%032x".format(index + 1) else null
            JSONObject().put("version", 1).put("wirePath", wire).put("items", JSONArray(entries))
                .put("nextCursor", next ?: JSONObject.NULL)
        }
        val opened = openCursor()
        val first = cursorPageReady(opened)
        assertEquals(0, first.getJSONArray("items").length())
        val token = first.getString("nextPageToken")
        assertEquals(token, cursorPageReady(opened).getString("nextPageToken"))
        assertFalse(first.toString().contains("00000000000000000000000000000008"))
        val later = cursorPageReady(opened, token = token)
        assertEquals("late.srt", later.getJSONArray("items").getJSONObject(0).getString("name"))
        assertTrue(later.isNull("nextPageToken"))
    }

    @Test fun cursorHandlesLargeDirectoriesWithBoundedHandlesAndWirePages() = fixture {
        rows["/film"] = (0 until 4_103).map { i ->
            val name = "$i-中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&中<>&.srt"
            entry("/film/$name", "/film/$i.srt", name)
        }
        val opened = openCursor()
        var token: String? = null
        var count = 0
        var oldest = ""
        do {
            val page = cursorPageReady(opened, token = token)
            assertEquals("READY", page.getString("status"))
            val items = page.getJSONArray("items")
            assertTrue(items.length() in 1..32)
            val encoded = page.toString().replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
            assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= 24 * 1024)
            assertFalse(encoded.contains("/film/"))
            if (count == 0) oldest = items.getJSONObject(0).getString("id")
            count += items.length()
            token = if (page.isNull("nextPageToken")) null else page.getString("nextPageToken")
        } while (token != null)
        assertEquals(4_103, count)
        assertEquals("NOT_FOUND", call("select", handle(opened).put("fileId", oldest)).getString("code"))
    }

    @Test fun largeNamesBoundServerHandlesAndReportTheRetainedWindow() = fixture {
        rows["/film"] = (0 until 130).map { i ->
            val name = "$i-${"x".repeat(3_000)}.srt"
            entry("/film/$name", "/film/$i.srt", name)
        }
        val opened = openCursor()
        var token: String? = null
        var oldest = ""
        var retained = ""
        var count = 0
        do {
            val page = cursorPageReady(opened, token = token)
            assertEquals("READY", page.getString("status"))
            val items = page.getJSONArray("items")
            if (count == 0) oldest = items.getJSONObject(0).getString("id")
            count += items.length()
            retained = page.getString("firstRetainedId")
            token = if (page.isNull("nextPageToken")) null else page.getString("nextPageToken")
        } while (token != null)
        assertEquals(130, count)
        assertNotEquals(oldest, retained)
        assertEquals("NOT_FOUND", call("select", handle(opened).put("fileId", oldest)).getString("code"))
    }

    @Test fun pendingDirectoryDoesNotBlockCommandsAndOldScopeCannotGetNames() = fixture {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        pageHook = { _, _ ->
            try {
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
                error("unreachable")
            } finally { cancelled.complete(Unit) }
        }
        val opened = open()
        withTimeout(1_000) { started.await() }
        assertEquals("PENDING", call("page", pageRequest(opened)).getString("status"))
        owner = Any()
        epoch++
        assertEquals("STALE_SCOPE", call("page", pageRequest(opened)).getString("code"))
        withTimeout(2_000) { cancelled.await() }
        assertFalse(opened.toString().contains("private.srt"))
    }

    @Test fun changedDirectoryInvalidatesHandlesAndSelectionRechecksVersionAndPermission() = fixture {
        rows["/film"] = (0 until 33).map { i -> entry("/film/$i.srt", "/film/$i.srt", "$i.srt") }
        var verifyStatus = 409
        var allowedToCommit = CompletableDeferred<Unit>()
        var received: io.github.kkwans.nasfilebrowser.data.ScopedSubtitleEntry? = null
        var cancelled = false
        verifyHook = { _, _ -> if (verifyStatus != 0) throw ServiceException(verifyStatus, "private/token-and-path") }
        prepare = { entry, mediaEpoch, revision, authorized ->
            assertEquals(2L, mediaEpoch)
            assertEquals(3L, revision)
            received = entry
            TvRemoteSubtitleBrowser.Import({
                allowedToCommit.await()
                if (authorized()) TvRemoteSubtitleResult.APPLIED else TvRemoteSubtitleResult.ACCESS_DENIED
            }, { cancelled = true })
        }
        val opened = openCursor()
        val first = cursorPageReady(opened)
        val file = first.getJSONArray("items").getJSONObject(0).getString("id")
        val click = handle(opened).put("fileId", file)
        assertEquals("FILE_CHANGED", selectionReady(opened, call("select", click)).getString("code"))
        assertNull(received)
        verifyStatus = 0
        val pending = call("select", click)
        withTimeout(1_000) { while (received == null) delay(5) }
        assertEquals("/film/0.srt", received?.file?.wirePath)
        assertEquals("v".repeat(43), received?.version)
        verifyStatus = 403
        allowedToCommit.complete(Unit)
        val denied = selectionReady(opened, pending)
        assertEquals("ACCESS_DENIED", denied.getString("code"))
        assertFalse(denied.toString().contains("private/token-and-path"))
        assertTrue(cancelled)
        pageHook = { _, _ -> throw ServiceException(409, "private/token-and-path") }
        val changed = cursorPageReady(opened, token = first.getString("nextPageToken"))
        assertEquals("DIRECTORY_CHANGED", changed.getString("code"))
        assertEquals("NOT_FOUND", call("select", handle(opened).put("fileId", file)).getString("code"))
    }

    @Test fun v1CompatibilityUsesScopedPagesAndVersionedSelection() = fixture {
        rows["/film"] = (0 until 70).map { i -> entry("/film/$i.srt", "/film/$i.srt", "$i.srt") }
        var selectedVersion: String? = null
        prepare = { entry, _, _, allowed -> TvRemoteSubtitleBrowser.Import({
            selectedVersion = entry.version
            if (allowed()) TvRemoteSubtitleResult.APPLIED else TvRemoteSubtitleResult.ACCESS_DENIED
        }, {}) }
        val opened = open()
        var offset = 0
        var firstFile = ""
        do {
            val page = pageReady(opened, offset = offset)
            assertEquals("READY", page.getString("status"))
            val items = page.getJSONArray("items")
            if (offset == 0) firstFile = items.getJSONObject(0).getString("id")
            offset += items.length()
            if (!page.isNull("nextOffset")) assertEquals(offset, page.getInt("nextOffset"))
        } while (!page.isNull("nextOffset"))
        assertEquals(70, offset)
        assertEquals("APPLIED", selectionReady(opened, call("select", handle(opened).put("fileId", firstFile))).getString("status"))
        assertEquals("v".repeat(43), selectedVersion)
        assertTrue(endpoints.all { it == "/api/resource-directory-pages" })
    }

    @Test fun localPagingHidesDocumentIdsAndPreservesContinuationsAtTheWireBudget() = fixture {
        val folder = LocalNode("字幕盘", true, "content://private/tree/volume:folder")
        val files = (0 until 32).map { index ->
            LocalNode("$index-${"中<>&".repeat(125)}.srt", false, "content://private/document/volume:folder/track-$index")
        }
        val scanned = files.indices.map { (it + 1) * 3 }
        local = TvRemoteSubtitleBrowser.LocalSource(
            revision = { 1L },
            list = { node, offset, _ ->
                if (node == null) TvRemoteSubtitleBrowser.LocalPage(listOf(folder), listOf(1), null)
                else {
                    assertSame(folder, node)
                    val start = offset / 3
                    TvRemoteSubtitleBrowser.LocalPage(files.drop(start), scanned.drop(start), null)
                }
            },
            verify = { error("No selection") },
            prepare = { _, _, _, _ -> error("No preparation") },
        )
        val opened = openLocal()
        val root = pageReady(opened)
        val dir = root.getJSONArray("items").getJSONObject(0).getString("id")
        assertFalse(root.toString().contains("volume:folder"))
        assertFalse(root.toString().contains("content://"))
        var offset = 0
        do {
            val page = pageReady(opened, dir, offset)
            val rows = page.getJSONArray("items")
            val encoded = page.toString().replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
            assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= 24 * 1024)
            assertFalse(encoded.contains("content://"))
            offset += rows.length() * 3
            if (!page.isNull("nextOffset")) assertEquals(offset, page.getInt("nextOffset"))
        } while (!page.isNull("nextOffset"))
        assertEquals(32 * 3, offset)
        assertEquals("DIRECTORY_CHANGED", call("page", pageRequest(opened, dir, offset = 2)).getString("code"))
        assertEquals("STALE_SCOPE", call("page", pageRequest(opened), "peer", "new-connection").getString("code"))
    }

    @Test fun revokingLocalGrantCancelsStagedImportAndInvalidatesOldHandles() = fixture {
        val staged = CompletableDeferred<Unit>()
        val wait = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val grant = TvSubtitleStorageRevision()
        val node = LocalNode("片.srt", false, "content://private/document/film:old-track")
        local = TvRemoteSubtitleBrowser.LocalSource(
            revision = { grant.observe(false, emptySet()) },
            list = { _, _, _ -> TvRemoteSubtitleBrowser.LocalPage(listOf(node), listOf(1), null) },
            verify = {},
            prepare = { _, _, _, allowed ->
                TvRemoteSubtitleBrowser.Import({
                    staged.complete(Unit)
                    wait.await()
                    if (allowed()) TvRemoteSubtitleResult.APPLIED else TvRemoteSubtitleResult.STALE
                }, { cancelled.complete(Unit) })
            },
        )
        val opened = openLocal()
        val file = pageReady(opened).getJSONArray("items").getJSONObject(0).getString("id")
        val selected = call("select", handle(opened).put("fileId", file))
        assertEquals("PENDING", selected.getString("status"))
        withTimeout(1_000) { staged.await() }
        grant.invalidate()
        assertEquals("STALE_SCOPE", selectionReady(opened, selected).getString("code"))
        withTimeout(1_000) { cancelled.await() }
        val reopened = openLocal()
        val newFile = pageReady(reopened).getJSONArray("items").getJSONObject(0).getString("id")
        assertNotEquals(file, newFile)
        assertEquals("NOT_FOUND", call("select", handle(reopened).put("fileId", file)).getString("code"))
        owner = Any()
        epoch++
        assertEquals("STALE_SCOPE", call("page", pageRequest(reopened)).getString("code"))
    }

    @Test fun persistedReadRemovalInvalidatesDirectoryWithoutProjectingItsUri() = fixture {
        val grant = TvSubtitleStorageRevision()
        val uri = "content://private/tree/volume:captions"
        var reads = setOf(uri to 100L)
        val node = LocalNode("字幕文件夹", true, uri)
        fun revision() = grant.observe(false, reads)
        local = TvRemoteSubtitleBrowser.LocalSource(
            revision = ::revision,
            list = { _, _, _ -> TvRemoteSubtitleBrowser.LocalPage(listOf(node), listOf(1), null) },
            verify = { error("No selection") },
            prepare = { _, _, _, _ -> error("No preparation") },
        )
        val previous = revision()
        val opened = openLocal()
        assertFalse(pageReady(opened).toString().contains(uri))
        assertEquals(previous, revision())
        reads = emptySet()
        val stale = call("page", pageRequest(opened))
        assertEquals("STALE_SCOPE", stale.getString("code"))
        assertFalse(stale.toString().contains(uri))
        assertFalse(stale.has("items"))
        val afterRevoke = revision()
        assertTrue(afterRevoke > previous)
        reads = setOf(uri to 101L)
        val afterRegrant = revision()
        assertTrue(afterRegrant > afterRevoke)
        assertTrue(grant.observe(true, reads) > afterRegrant)
        val withLegacy = grant.observe(true, reads)
        assertTrue(grant.observe(false, reads) > withLegacy)
    }

    @Test fun localNoSourcesAndUnpluggedSourcesHaveRecoverableSanitizedErrors() = fixture {
        val folder = LocalNode("设备文件夹", true, "/storage/usb/private")
        var empty = true
        local = TvRemoteSubtitleBrowser.LocalSource(
            revision = { 1L },
            list = { node, _, _ ->
                if (node != null) throw TvLocalSubtitleFailure("/storage/usb/private", TvLocalSubtitleIssue.STORAGE_REMOVED)
                val roots = if (empty) emptyList() else listOf(folder)
                TvRemoteSubtitleBrowser.LocalPage(roots, roots.indices.map { it + 1 }, null)
            },
            verify = { error("No selection") },
            prepare = { _, _, _, _ -> error("No preparation") },
        )
        val opened = openLocal()
        assertEquals("STORAGE_AUTH_REQUIRED", pageReady(opened).getString("code"))
        empty = false
        val root = pageReady(opened)
        val dir = root.getJSONArray("items").getJSONObject(0).getString("id")
        val error = pageReady(opened, dir)
        assertEquals("STORAGE_REMOVED", error.getString("code"))
        assertFalse(error.toString().contains("/storage/usb/private"))
        assertEquals("READY", pageReady(opened, error.getString("rootId")).getString("status"))
    }

    @Test fun emptyScanPagesReachLateSubtitlesAndLimitKeepsIssuedFilesSelectable() = fixture {
        val folder = LocalNode("字幕盘", true, "content://private/tree/folder")
        val late = LocalNode("late.srt", false, "content://private/document/late")
        val start = TV_LOCAL_SUBTITLE_SCAN_WINDOW * 2
        fun anchor(offset: Int) = "content://private/document/scanned-$offset"
        local = TvRemoteSubtitleBrowser.LocalSource(
            revision = { 1L },
            list = { directory, offset, previous ->
                if (directory == null) TvRemoteSubtitleBrowser.LocalPage(listOf(folder), listOf(1), null)
                else {
                    assertEquals(if (offset == 0) null else anchor(offset), previous)
                    val next = offset + TV_LOCAL_SUBTITLE_SCAN_WINDOW
                    if (offset == start) TvRemoteSubtitleBrowser.LocalPage(listOf(late), listOf(offset + 1), next,
                        mapOf(offset + 1 to anchor(offset + 1), next to anchor(next)))
                    else TvRemoteSubtitleBrowser.LocalPage(emptyList(), emptyList(), next, mapOf(next to anchor(next)))
                }
            },
            verify = { assertSame(late, it) },
            prepare = { _, _, _, allowed -> TvRemoteSubtitleBrowser.Import({
                if (allowed()) TvRemoteSubtitleResult.APPLIED else TvRemoteSubtitleResult.STALE
            }, {}) },
        )
        val opened = openLocal()
        val folderId = pageReady(opened).getJSONArray("items").getJSONObject(0).getString("id")
        var offset = 0
        var fileId: String? = null
        while (offset < TV_LOCAL_SUBTITLE_SCAN_LIMIT) {
            val page = pageReady(opened, folderId, offset)
            assertFalse(page.toString().contains("content://private"))
            val rows = page.getJSONArray("items")
            if (offset == start) fileId = rows.getJSONObject(0).getString("id")
            else assertEquals(0, rows.length())
            offset += TV_LOCAL_SUBTITLE_SCAN_WINDOW
            assertEquals(offset, page.getInt("nextOffset"))
        }
        assertEquals("LOCAL_SCAN_LIMIT", pageReady(opened, folderId, offset).getString("code"))
        val click = call("select", handle(opened).put("fileId", requireNotNull(fileId)))
        assertEquals("APPLIED", selectionReady(opened, click).getString("status"))
    }

    @Test fun changedDirectoryRejectsOldContinuationAndRevokedGrantRejectsRefresh() = fixture {
        val grant = TvSubtitleStorageRevision()
        val old = LocalNode("old.srt", false, "/private/old.srt")
        val replacement = LocalNode("new.srt", false, "/private/new.srt")
        var current = old
        local = TvRemoteSubtitleBrowser.LocalSource(
            revision = { grant.observe(false, emptySet()) },
            list = { _, offset, previous ->
                if (offset != 0 && previous != current.key) throw TvLocalSubtitleFailure(
                    "private path from provider", TvLocalSubtitleIssue.DIRECTORY_CHANGED)
                TvRemoteSubtitleBrowser.LocalPage(listOf(current), listOf(offset + 1), offset + 1,
                    mapOf(offset + 1 to current.key))
            },
            verify = { error("Stale handle must not be selected") },
            prepare = { _, _, _, _ -> error("Stale handle must not be prepared") },
        )
        val opened = openLocal()
        val first = pageReady(opened)
        val staleFile = first.getJSONArray("items").getJSONObject(0).getString("id")
        current = replacement
        val changed = pageReady(opened, offset = first.getInt("nextOffset"))
        assertEquals("DIRECTORY_CHANGED", changed.getString("code"))
        assertFalse(changed.toString().contains("private path"))
        assertEquals("NOT_FOUND", call("select", handle(opened).put("fileId", staleFile)).getString("code"))
        assertEquals("new.srt", pageReady(opened).getJSONArray("items").getJSONObject(0).getString("name"))
        grant.invalidate()
        assertEquals("STALE_SCOPE", call("page", pageRequest(opened)).getString("code"))
    }

    private data class LocalNode(override val label: String, override val directory: Boolean, override val key: Any) :
        TvRemoteSubtitleBrowser.LocalNode

    private class Fixture(scope: CoroutineScope) {
        var owner: Any = Any()
        var epoch = 1L
        var video = ResourceRef("/film/video.mkv", "/film/video.mkv", "video.mkv", false, "video", 30)
        val rows = mutableMapOf<String, List<JSONObject>>()
        val endpoints = mutableListOf<String>()
        var scopedEnabled = true
        var pageHook: suspend (String, String?) -> JSONObject = { wire, cursor -> defaultPage(wire, cursor) }
        var verifyHook: suspend (String, String) -> Unit = { _, _ -> }
        var local: TvRemoteSubtitleBrowser.LocalSource? = null
        var prepare: (ScopedSubtitleEntry, Long, Long, suspend () -> Boolean) -> TvRemoteSubtitleBrowser.Import? = { _, _, _, _ -> null }
        private val browser = TvRemoteSubtitleBrowser(scope,
            { TvRemoteSubtitleBrowser.Source(owner, 7, epoch, 2, 9, video, 3, local,
                if (scopedEnabled) ({ scoped() }) else null) },
            { true }, { 0L }, { file, media, revision, authorized -> prepare(file, media, revision, authorized) })

        private fun scoped() = ScopedSubtitleResources(request = { method, endpoint, body ->
            assertEquals("POST", method)
            endpoints.add(endpoint)
            assertEquals("/api/resource-directory-pages", endpoint)
            val value = requireNotNull(body)
            when (value.getString("action")) {
                "page" -> pageHook(value.getString("wirePath"), value.optString("cursor").ifEmpty { null })
                "verify" -> {
                    val version = value.getString("version")
                    verifyHook(value.getString("wirePath"), version)
                    JSONObject().put("version", version)
                }
                "close" -> JSONObject()
                else -> error("Unexpected action")
            }
        }, asset = { error("selection should use a versioned player lease") })

        private fun defaultPage(wire: String, cursor: String?): JSONObject {
            val offset = cursor?.takeLast(8)?.toInt(16) ?: 0
            val entries = rows[wire].orEmpty()
            require(offset in 0..entries.size)
            val end = (offset + 32).coerceAtMost(entries.size)
            val next = if (end < entries.size) "%032x".format(end) else null
            return JSONObject().put("version", 1).put("wirePath", wire)
                .put("items", JSONArray(entries.subList(offset, end))).put("nextCursor", next ?: JSONObject.NULL)
        }

        fun serverAvailable() = browser.available

        suspend fun call(operation: String, payload: JSONObject, peer: String = "peer", connection: String = "connection") =
            browser.execute("subtitle.browse.$operation", JSONObject().put("controllerId", peer).put("connectionId", connection)
                .put("sessionEpoch", epoch).put("mediaEpoch", 2), payload)
        suspend fun open() = call("open", JSONObject())
        suspend fun openCursor() = call("open", JSONObject().put("source", "server").put("cursorVersion", 1))
        suspend fun openLocal() = call("open", JSONObject().put("source", "tvLocal"))
        fun handle(opened: JSONObject) = JSONObject().put("browserId", opened.getString("browserId"))
        fun pageRequest(opened: JSONObject, directory: String = opened.getString("directoryId"), offset: Int = 0) =
            handle(opened).put("directoryId", directory).put("offset", offset)
        fun cursorRequest(opened: JSONObject, directory: String = opened.getString("directoryId"), token: String? = null) =
            handle(opened).put("directoryId", directory).also { if (token != null) it.put("pageToken", token) }
        suspend fun pageReady(opened: JSONObject, directory: String = opened.getString("directoryId"), offset: Int = 0) =
            ready { call("page", pageRequest(opened, directory, offset)) }
        suspend fun cursorPageReady(opened: JSONObject, directory: String = opened.getString("directoryId"), token: String? = null) =
            ready { call("page", cursorRequest(opened, directory, token)) }
        suspend fun selectionReady(opened: JSONObject, pending: JSONObject) =
            ready { call("select", handle(opened).put("operationId", pending.getString("operationId"))) }
        private suspend fun ready(poll: suspend () -> JSONObject): JSONObject = withTimeout(2_000) {
            var response = poll()
            while (response.getString("status") == "PENDING") { delay(5); response = poll() }
            response
        }
    }

    private fun fixture(run: suspend Fixture.() -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try { Fixture(scope).run() } finally { scope.cancel() }
    }

    private fun entry(path: String, wire: String, name: String, directory: Boolean = false) =
        JSONObject().put("path", path).put("wirePath", wire).put("name", name).put("isDir", directory)
            .put("modified", "1").put("size", 30).put("version", if (directory) JSONObject.NULL else "v".repeat(43))
}

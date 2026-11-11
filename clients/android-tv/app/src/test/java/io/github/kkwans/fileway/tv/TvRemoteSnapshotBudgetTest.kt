package io.github.kkwans.fileway.tv

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TvRemoteSnapshotBudgetTest {
    @Test fun longFileNamesSurvivePreviewAndPaginationWithinTheNativeBudget() {
        val files = (0 until 43).map { index ->
            val path = "/owned-$index"
            val name = if (index == 0) "长文件名-" + "a".repeat(124) + "🌿.mkv" else "&".repeat(235) + "-$index.mkv"
            ResourceRef(path, path, name, false, "", 10)
        }
        val received = mutableListOf<JSONObject>()
        val pageSizes = mutableListOf<Int>()
        while (received.size < files.size) {
            val count = if (received.isEmpty()) 24 else 32
            val page = remoteBrowsePage(files, received.size, count)
            val rows = page.getJSONArray("files")
            assertEquals(files.size, page.getInt("total"))
            assertTrue("Pagination must advance", rows.length() in 1..count)
            assertTrue("Page must leave room for the native encoder", escapedAmpersandSize(page) <= 24 * 1024)
            pageSizes += rows.length()
            repeat(rows.length()) { received += rows.getJSONObject(it) }
        }
        assertTrue(pageSizes.first() < 24)
        assertEquals(files.map { it.name }, received.map { it.getString("name") })
        assertEquals(files.map(::remoteFileId), received.map { it.getString("id") })
    }

    @Test fun snapshotBudgetIncludesEscapesAddedByNativeEncoding() {
        val rows = JSONArray((0 until 40).map { JSONObject().put("id", it).put("name", "&".repeat(235)) })
        val root = JSONObject().put("content", JSONObject().put("files", rows).put("total", 40))
        assertTrue(root.toString().length < 44 * 1024)
        assertTrue(escapedAmpersandSize(root) > 44 * 1024)
        boundRemoteSnapshot(root)
        assertTrue(escapedAmpersandSize(root) <= 44 * 1024)
        assertEquals(40, root.getJSONObject("content").getInt("total"))
        assertEquals((0 until rows.length()).toList(), (0 until rows.length()).map { rows.getJSONObject(it).getInt("id") })
    }

    private fun escapedAmpersandSize(value: JSONObject): Int =
        value.toString().replace("&", "\\u0026").toByteArray(Charsets.UTF_8).size

    @Test fun longUnicodePreviewsCannotBreakTheControlChannelOrChangeSelection() {
        fun rows()=JSONArray((0 until 32).map{JSONObject().put("id",it).put("title","字幕样式".repeat(120))})
        val playback=JSONObject().put("audio",rows()).put("subtitles",rows()).put("sourceSubtitles",rows())
            .put("sidecars",rows()).put("audioCount",45).put("subtitleCount",71).put("selectedAudio",44).put("selectedSourceSubtitle",70)
        val root=JSONObject().put("content",JSONObject().put("files",rows()).put("total",900)
            .put("playback",playback).put("queue",JSONObject().put("items",rows()).put("count",600)))
        boundRemoteSnapshot(root)
        assertTrue(root.toString().toByteArray(Charsets.UTF_8).size<=44*1024)
        assertEquals(45,playback.getInt("audioCount"));assertEquals(71,playback.getInt("subtitleCount"))
        assertEquals(44,playback.getInt("selectedAudio"));assertEquals(70,playback.getInt("selectedSourceSubtitle"))
        assertEquals(900,root.getJSONObject("content").getInt("total"))
    }
    @Test fun ordinarySnapshotsRemainUnchanged() {
        val root=JSONObject().put("content",JSONObject().put("files",JSONArray().put(JSONObject().put("id",2).put("name","电影"))))
        val before=root.toString();boundRemoteSnapshot(root);assertEquals(before,root.toString())
    }
    @Test fun oversizedFailureTextIsBoundedWithoutRemovingRecoveryState() {
        val body=JSONObject().put("error","故障".repeat(20000)).put("notice","x".repeat(50000))
            .put("handoff",JSONObject().put("id","request-1").put("status","FAILED").put("message","x".repeat(50000)))
        val root=JSONObject().put("content",body);boundRemoteSnapshot(root)
        assertEquals("FAILED",body.getJSONObject("handoff").getString("status"))
        assertEquals("request-1",body.getJSONObject("handoff").getString("id"))
        assertTrue(body.getString("error").isNotEmpty())
    }
}

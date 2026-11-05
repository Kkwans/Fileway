package io.github.kkwans.fileway.tv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TvRemoteSnapshotBudgetTest {
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

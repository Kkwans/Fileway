package io.github.kkwans.fileway.tv

import io.github.kkwans.nasfilebrowser.data.PreviewLease
import io.github.kkwans.nasfilebrowser.data.ScopedSubtitleResources
import io.github.kkwans.nasfilebrowser.data.ServiceException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScopedSubtitleResourcesTest {
    private val version = "A".repeat(43)
    private val cursor = "B".repeat(32)

    private fun row(wire: String, name: String = "字幕.srt", directory: Boolean = false, size: Long = 4): JSONObject =
        JSONObject().put("name", name).put("path", "/目录/$name").put("wirePath", wire)
            .put("isDir", directory).put("size", size).put("modified", "2026-01-01T00:00:00Z")
            .also { if (!directory) it.put("version", version) }

    private fun page(vararg rows: JSONObject, next: String? = null): JSONObject = JSONObject()
        .put("version", 1).put("wirePath", "/%BE%E7%BC%AF")
        .put("items", JSONArray(rows.toList())).put("nextCursor", next ?: JSONObject.NULL)

    @Test fun preservesOpaqueWireAndUsesVersionedSameSessionEndpoint() = runBlocking {
        val requests = mutableListOf<Pair<String, JSONObject?>>()
        var endpoint = ""
        val api = ScopedSubtitleResources(
            request = { method, path, body ->
                requests += path to body
                when (path) {
                    "/api/client-capabilities" -> JSONObject().put("resourceSubtitlePagesVersion", 1)
                    "/api/resource-directory-pages" -> if (body?.optString("action") == "verify") JSONObject().put("version", version)
                        else page(row("/%BE%E7%BC%AF/%FF%25%2B.srt"), next = cursor)
                    else -> error("unexpected request $method")
                }
            },
            asset = { endpoint = it; PreviewLease("http://127.0.0.1/lease", "fixture") {} },
            nonce = "owned-browser-123456",
        )
        assertTrue(api.supported())
        val result = api.page("/%BE%E7%BC%AF")
        assertEquals(cursor, result.nextCursor)
        assertEquals("/%BE%E7%BC%AF/%FF%25%2B.srt", result.entries.single().file.wirePath)
        api.verify(result.entries.single())
        api.lease(result.entries.single()).release()
        assertEquals("/api/resource-subtitle-file/%BE%E7%BC%AF/%FF%25%2B.srt?version=$version", endpoint)
        assertFalse(endpoint.contains("token", ignoreCase = true))
        assertEquals("verify", requests.last().second?.getString("action"))
    }

    @Test fun rejectsForeignDuplicateAndMalformedRowsOrCursors() {
        fun rejected(value: JSONObject, input: String? = null) {
            try { ScopedSubtitleResources.parsePage("/%BE%E7%BC%AF", input, value); fail("invalid page was accepted") }
            catch (_: IllegalArgumentException) { }
        }
        rejected(page(row("/other/file.srt")))
        rejected(page(row("/%BE%E7%BC%AF/file.srt"), row("/%BE%E7%BC%AF/file.srt")))
        rejected(page(row("/%BE%E7%BC%AF/a%2Fb.srt")))
        rejected(page(row("/%BE%E7%BC%AF/file.srt").put("version", "bad")))
        rejected(page(next = cursor), input = cursor)
        val empty = ScopedSubtitleResources.parsePage("/%BE%E7%BC%AF", null, page(next = cursor))
        assertTrue(empty.entries.isEmpty())
        assertEquals(cursor, empty.nextCursor)
        val zero = ScopedSubtitleResources.parsePage("/%BE%E7%BC%AF", null, page(row("/%BE%E7%BC%AF/empty.srt", size = 0))).entries.single()
        try { ScopedSubtitleResources.contentEndpoint(zero); fail("empty subtitle was accepted for playback") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun oldServerNeverFallsBackToLegacyResourceEndpoints() = runBlocking {
        var calls = 0
        val api = ScopedSubtitleResources(
            request = { _, path, _ -> calls++; assertEquals("/api/client-capabilities", path); throw ServiceException(404, "missing") },
            asset = { error("legacy lease must not be used") }, nonce = "owned-browser-123456",
        )
        assertFalse(api.supported())
        assertEquals(1, calls)
    }
}

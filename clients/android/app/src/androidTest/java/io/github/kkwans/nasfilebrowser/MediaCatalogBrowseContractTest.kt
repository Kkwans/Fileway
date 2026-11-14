package io.github.kkwans.nasfilebrowser

import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.app.SessionContext
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic transport fixtures exercise the shared phone/TV repository. */
@RunWith(AndroidJUnit4::class)
class MediaCatalogBrowseContractTest {
    private fun isolatedContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "catalog-browse-${UUID.randomUUID()}")
        check(dir.mkdirs())
        return object : ContextWrapper(base) { override fun getFilesDir(): File = dir }
    }
    private fun token(sid: String = "", marker: String = "initial"): String {
        val payload = JSONObject().put("user", JSONObject().put("id", 7).put("username", "fixture")).put("sid", sid).put("marker", marker)
        return "header." + Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
    }
    private suspend fun bound(profile: ServerProfile, generation: Int = 1, initialToken: String = token(), native: suspend (JSONObject) -> Any?): SessionContext {
        val account = AccountRecord("fixture-account", profile.id, profile.sourceRevision, 7, "fixture", "fixture-ref", 0)
        return SessionContext(profile, account, NasSession.restore(profile, initialToken, 7, native), generation, "fixture-owner-$generation")
    }
    private fun reply(status: Int, value: JSONObject = JSONObject()) = JSONObject().put("status", status).put("body", value.toString())
    private fun actions(play: Boolean = false) = JSONObject().put("play", play).put("openDetails", true).put("editMetadata", true)
        .put("manageSources", true).put("manageMembers", true)
    private fun counts(series: String = "series_1", season: String? = null, state: String = "ready") = JSONObject().apply {
        val ready = state == "ready"
        put("collected", if (ready) if (season == "season_0") 0 else 2 else JSONObject.NULL)
        put("available", if (ready) if (season == "season_0") 0 else 1 else JSONObject.NULL)
        put("specials", if (ready) if (season == "season_0") 2 else 0 else JSONObject.NULL); put("unmappedFileCount", JSONObject.NULL)
        put("total", JSONObject.NULL); put("totalBasis", JSONObject.NULL); put("countState", state)
        if (ready) put("orderId", "order_1")
        put("endpoint", "/api/media-library/v1/libraries/lib_1/items/$series/episode-counts" + (season?.let { "?seasonId=$it" } ?: ""))
    }
    private fun card(id: String, kind: String, availability: String = "available", parentSeries: String = "series_1") = JSONObject().apply {
        put("ref", JSONObject().put("libraryId", "lib_1").put("itemId", id)); put("revision", 3)
        put("structureKind", kind); put("contentCategories", JSONArray()); put("matchState", if (kind == "video") "unmatched" else "local_confirmed")
        put("title", "Fixture $id"); put("originalTitle", JSONObject.NULL); put("year", JSONObject.NULL)
        put("displayMode", if (kind == "episode") "landscape" else if (kind == "video") "unmatched" else "poster")
        put("artwork", JSONObject.NULL); put("primaryRating", JSONObject.NULL); put("duration", JSONObject.NULL)
        put("episodes", when (kind) { "series" -> counts(id, state = "deferred"); "season" -> counts(parentSeries, id); else -> JSONObject.NULL })
        put("availability", availability); put("metadataState", "available"); put("firstAddedAt", 1)
        put("seriesUpdatedAt", JSONObject.NULL); put("size", if (kind == "video") 20 else JSONObject.NULL)
        put("canActions", actions(kind == "video" || kind == "episode")); put("actionReasons", JSONObject())
    }
    private fun page(items: JSONArray, state: String = "ready", next: String? = null, order: String? = null) = JSONObject().apply {
        put("items", items); put("pageState", state); put("hasMore", next != null); put("revision", "revision_1")
        next?.let { put("nextCursor", it) }; order?.let { put("orderId", it) }
    }
    private fun row(id: String, state: String = "ready", next: String? = null, items: JSONArray? = null) = JSONObject().apply {
        put("id", id); put("kind", id.replace('-', '_')); put("state", state); put("endpoint", "/api/media-library/v1/home/rows/$id")
        put("hasMore", next != null); put("revision", "revision_1"); next?.let { put("nextCursor", it) }; items?.let { put("items", it) }
    }
    private fun home() = JSONObject().put("schemaVersion", 1).put("state", "partial")
        .put("libraries", JSONArray().put(JSONObject().put("id", "lib_1").put("name", "Fixture").put("revision", 1).put("role", "owner").put("archived", false)))
        .put("rows", JSONArray().put(row("recently-added", "partial", "row +/=")))
        .put("capabilities", JSONObject().put("watchState", false)).put("canActions", JSONObject().put("createLibrary", true)).put("actionReasons", JSONObject())
    private fun detail() = JSONObject().put("item", JSONObject().put("id", "series_1").put("libraryId", "lib_1").put("kind", "series")
        .put("title", "Fixture series").put("revision", 3).put("createdAt", 1).put("sourceState", "available").put("metadataState", "restricted"))
        .put("metadataRevision", 0).put("fields", JSONObject()).put("credits", JSONArray()).put("ratings", JSONArray())
        .put("artwork", JSONArray()).put("externalIds", JSONArray()).put("issues", JSONArray()).put("candidates", JSONArray())
        .put("pageState", "partial_metadata").put("card", card("series_1", "series"))
    private fun variant() = JSONObject().put("itemId", "episode_1").put("editionId", "edition_1").put("variantId", "variant_1")
        .put("bindingId", "binding_1").put("sourceFileId", "file_1").put("editionName", "").put("isDefault", true)
        .put("sourceLabel", "Local").put("displayName", "fixture.mkv").put("contentType", "video/x-matroska")
        .put("size", 20).put("fileRevision", 4).put("identityEpoch", 1).put("weakIdentity", false)
        .put("durationSeconds", JSONObject.NULL).put("resolution", JSONObject.NULL)
    private fun resolution(includeEndpoint: Boolean = true) = JSONObject().put("itemId", "episode_1").put("editionId", "edition_1")
        .put("variantId", "variant_1").put("sourceFileId", "file_1").put("fileRevision", 4).put("identityEpoch", 1)
        .put("weakIdentity", false).put("canResumeAcrossOpen", false).put("resumeRequiresSameRevision", true)
        .put("displayName", "fixture.mkv").put("contentType", "video/x-matroska").put("size", 20)
        .put("durationSeconds", JSONObject.NULL).put("resolution", JSONObject.NULL).put("rangeSupported", true)
        .put("url", "/prefix/api/media-library/v1/streams/lease_fixture/original").put("expiresAt", 99_999_999_999L)
        .put("sessionId", "session_fixture").put("leaseId", "lease_fixture").put("leaseGeneration", 2)
        .put("renewAfter", 999L).put("resumeMode", "same_lease").put("timelineToken", "timeline_fixture")
        .apply { if (includeEndpoint) put("endpoint", "/api/media-library/v1/streams/lease_fixture/original") }

    @Test fun scopedBrowsePartialSeasonEpisodeAndPlaybackAreConnected() = runBlocking<Unit> {
        val seen = mutableListOf<String>()
        var resolutionBody = resolution()
        val profile = ServerProfile(name = "fixture", address = "https://example.invalid/prefix")
        val session = bound(profile) { command -> when (command.getString("op")) {
            "open" -> "catalog-fixture"
            "token" -> token()
            "request" -> {
                val endpoint = command.getString("endpoint"); seen += endpoint
                when {
                    endpoint == "/api/media-library/v1/home" -> reply(200, home())
                    endpoint.startsWith("/api/media-library/v1/home/rows/recently-added?") -> reply(200, row("recently-added", items = JSONArray().put(card("series_1", "series"))))
                    endpoint.startsWith("/api/media-library/v1/catalog/items?") && !endpoint.contains("cursor=") -> reply(200, page(JSONArray(), "partial", "opaque +/="))
                    endpoint.startsWith("/api/media-library/v1/catalog/items?") -> reply(200, page(JSONArray().put(card("series_1", "series")
                        .put("artwork", JSONObject().put("assetId", "art_1").put("revision", 3).put("role", "poster")
                            .put("endpoint", "/api/media-library/v1/libraries/lib_1/items/series_1/artwork/art_1")))))
                    endpoint.endsWith("/items/series_1") -> reply(200, JSONObject().put("pageState", "partial").put("nextCursor", "detail_cursor").put("hasMore", true))
                    endpoint.contains("/items/series_1?cursor=") -> reply(200, detail())
                    endpoint.contains("/seasons?") -> reply(200, page(JSONArray().put(JSONObject().put("seasonId", "season_0").put("number", 0)
                        .put("label", "特别篇").put("orderId", "order_1").put("card", card("season_0", "season"))), order = "order_1"))
                    endpoint.contains("/episodes?") -> reply(200, page(JSONArray().put(JSONObject().put("episodeId", "episode_1").put("seasonId", "season_0")
                        .put("seasonNumber", 0).put("episodeNumber", 1).put("orderId", "order_1").put("card", card("episode_1", "episode"))
                        .put("playback", JSONObject().put("playMode", "combined_file").put("independentStart", true)
                            .put("coveredEpisodeIds", JSONArray().put("episode_1").put("episode_2")).put("coverageState", "ready").put("label", "合并文件"))), order = "order_1"))
                    endpoint.contains("/episode-counts") -> reply(200, counts(season = "season_0"))
                    endpoint.contains("/variants?") -> reply(200, JSONObject().put("items", JSONArray().put(variant())))
                    endpoint.endsWith("/playback-resolutions") -> reply(200, resolutionBody)
                    else -> reply(404)
                }
            }
            else -> null
        } }
        val journal = MediaWriteJournal.open(isolatedContext())
        val repo = MediaLibraryRepository(session, { true }, journal)
        val shell = (repo.home() as MediaReadResult.Success).value
        assertEquals(MediaCatalogHomeState.PARTIAL, shell.state)
        assertEquals(MediaCatalogPageState.PARTIAL, shell.rows.single().state)
        assertTrue(shell.rows.single().items.isEmpty())
        val row = (repo.homeRow(MediaCatalogRowId.RECENTLY_ADDED, shell.rows.single().nextCursor) as MediaReadResult.Success).value
        assertEquals("series_1", row.items.single().ref.itemId)
        val query = MediaCatalogQuery(libraryId = "lib_1", limit = 2)
        val first = (repo.catalog(query) as MediaReadResult.Success).value
        assertEquals(MediaCatalogPageState.PARTIAL, first.state); assertTrue(first.items.isEmpty()); assertTrue(first.hasMore)
        val cursor = requireNotNull(first.nextCursor)
        assertFalse(cursor.toString().contains("opaque"))
        val second = (repo.catalog(query, cursor) as MediaReadResult.Success).value
        assertEquals(MediaCatalogPageState.READY, second.state); assertFalse(second.hasMore)
        val deferred = requireNotNull(second.items.single().episodes)
        assertEquals(MediaCatalogCountState.DEFERRED, deferred.state); assertNull(deferred.collected); assertTrue(deferred.hasEndpoint)
        val artwork = requireNotNull(second.items.single().artwork)
        assertEquals("/api/media-library/v1/libraries/lib_1/items/series_1/artwork/art_1", repo.artworkEndpoint(artwork))
        assertFalse(artwork.toString().contains("art_1"))
        assertTrue(seen.any { it.contains("cursor=opaque+%2B%2F%3D") })
        val requestsBefore = seen.size
        assertTrue(runCatching { repo.catalog(query.copy(limit = 3), cursor) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(requestsBefore, seen.size)
        val series = second.items.single().ref
        val detailCursor = (repo.catalogDetail(series) as MediaReadResult.Success).value as MediaCatalogDetail.Partial
        val detail = (repo.catalogDetail(series, detailCursor.nextCursor) as MediaReadResult.Success).value as MediaCatalogDetail.Complete
        assertEquals(MediaCatalogDetailState.PARTIAL_METADATA, detail.state); assertEquals(series, detail.card?.ref)
        val season = (repo.seasons(series, limit = 2) as MediaReadResult.Success).value.items.single()
        assertEquals(0L, season.number)
        val episode = (repo.episodes(series, season, limit = 2) as MediaReadResult.Success).value.items.single()
        val episodePlayback = requireNotNull(episode.playback)
        assertEquals(MediaCatalogPlaybackMode.COMBINED_FILE, episodePlayback.mode)
        assertFalse(episodePlayback.independentStart)
        assertEquals(listOf("episode_1", "episode_2"), episodePlayback.coveredEpisodes.map { it.itemId })
        assertEquals(2L, (repo.episodeCounts(series, season) as MediaReadResult.Success).value.specials)
        val source = (repo.playbackVariants(episode.ref) as MediaReadResult.Success).value.items.single()
        assertFalse(source.toString().contains("fixture.mkv"))
        val playback = (repo.resolvePlayback(source) as MediaReadResult.Success).value
        assertEquals("/api/media-library/v1/streams/lease_fixture/original", repo.playbackEndpoint(playback))
        assertEquals(2L, playback.leaseGeneration); assertEquals(999L, playback.renewAfter)
        assertFalse(playback.toString().contains("lease_fixture")); assertFalse(playback.toString().contains("timeline_fixture"))
        resolutionBody = resolution(includeEndpoint = false)
        assertNull((repo.resolvePlayback(source) as MediaReadResult.Success).value.endpoint)
        resolutionBody = resolution().put("endpoint", "https://example.invalid/api/media-library/v1/streams/lease_fixture/original")
        val invalidPlayback = (repo.resolvePlayback(source) as MediaReadResult.Failure).error
        assertEquals(MediaFailureKind.INVALID_RESPONSE, invalidPlayback.kind); assertTrue(invalidPlayback.mutationMayHaveCommitted)
        resolutionBody = resolution().put("leaseId", "different_lease")
        assertEquals(MediaFailureKind.INVALID_RESPONSE, (repo.resolvePlayback(source) as MediaReadResult.Failure).error.kind)
        assertTrue(seen.any { it.contains("cursor=row+%2B%2F%3D") })
        journal.close()
    }

    @Test fun unknownStatesScopeAndCursorFailuresDoNotBecomeEmptySuccess() = runBlocking<Unit> {
        var responseMode = "partial"
        val profile = ServerProfile(name = "fixture", address = "https://example.invalid")
        val session = bound(profile) { command -> when (command.getString("op")) {
            "open" -> "catalog-errors"
            "token" -> token()
            "request" -> when (responseMode) {
                "partial" -> reply(200, page(JSONArray(), "partial", "cursor_fixture"))
                "repeat" -> reply(200, page(JSONArray(), "partial", "cursor_fixture"))
                "expired" -> reply(410)
                "stale" -> reply(409)
                "permission" -> reply(403)
                "missing" -> reply(404)
                "rate" -> reply(429)
                "invalid" -> reply(422)
                "unknown_page" -> reply(200, page(JSONArray(), "new_server_state"))
                "unknown_card" -> reply(200, page(JSONArray().put(card("video_1", "video", "future_state"))))
                "offline_card" -> reply(200, page(JSONArray().put(card("video_1", "video", "offline"))))
                "unsafe_art" -> reply(200, page(JSONArray().put(card("video_1", "video").put("artwork", JSONObject().put("assetId", "art_1")
                    .put("revision", 3).put("role", "poster").put("endpoint", "https://example.invalid/unsafe")))))
                "bad_deferred" -> reply(200, page(JSONArray().put(card("series_1", "series").put("episodes", counts(state = "deferred").put("collected", 2)))))
                else -> reply(503)
            }
            else -> null
        } }
        val journal = MediaWriteJournal.open(isolatedContext())
        val repo = MediaLibraryRepository(session, { true }, journal)
        val query = MediaCatalogQuery(limit = 2)
        val cursor = requireNotNull((repo.catalog(query) as MediaReadResult.Success).value.nextCursor)
        responseMode = "repeat"
        assertEquals(MediaFailureKind.INVALID_RESPONSE, (repo.catalog(query, cursor) as MediaReadResult.Failure).error.kind)
        responseMode = "expired"
        assertEquals(MediaFailureKind.CURSOR_EXPIRED, (repo.catalog(query, cursor) as MediaReadResult.Failure).error.kind)
        responseMode = "stale"
        assertEquals(MediaFailureKind.CURSOR_STALE, (repo.catalog(query, cursor) as MediaReadResult.Failure).error.kind)
        responseMode = "permission"
        assertEquals(MediaFailureKind.FORBIDDEN, (repo.catalog(query) as MediaReadResult.Failure).error.kind)
        responseMode = "missing"
        assertEquals(MediaFailureKind.NOT_FOUND, (repo.catalog(query) as MediaReadResult.Failure).error.kind)
        responseMode = "rate"
        assertEquals(MediaFailureKind.RATE_LIMITED, (repo.catalog(query) as MediaReadResult.Failure).error.kind)
        responseMode = "invalid"
        assertEquals(MediaFailureKind.INVALID, (repo.catalog(query) as MediaReadResult.Failure).error.kind)
        responseMode = "unknown_page"
        assertEquals(MediaFailureKind.INVALID_RESPONSE, (repo.catalog(query) as MediaReadResult.Failure).error.kind)
        responseMode = "unknown_card"
        val unknown = (repo.catalog(query) as MediaReadResult.Success).value.items.single()
        assertEquals(MediaCatalogAvailability.UNKNOWN, unknown.availability)
        assertFalse(unknown.actions.can(MediaCatalogAction.PLAY))
        responseMode = "offline_card"
        val offline = (repo.catalog(query) as MediaReadResult.Success).value.items.single()
        assertEquals(MediaCatalogAvailability.OFFLINE, offline.availability); assertFalse(offline.actions.can(MediaCatalogAction.PLAY))
        responseMode = "unsafe_art"
        assertEquals(MediaFailureKind.INVALID_RESPONSE, (repo.catalog(query) as MediaReadResult.Failure).error.kind)
        responseMode = "bad_deferred"
        assertEquals(MediaFailureKind.INVALID_RESPONSE, (repo.catalog(query) as MediaReadResult.Failure).error.kind)
        responseMode = "offline"
        assertEquals(MediaFailureKind.UNAVAILABLE, (repo.catalog(query) as MediaReadResult.Failure).error.kind)
        val other = MediaLibraryRepository(bound(profile, 2) { command -> when (command.getString("op")) { "open" -> "other"; "token" -> token(); else -> null } }, { true }, journal)
        assertTrue(runCatching { other.catalog(query, cursor) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { other.seasons(repo.catalogRef("lib_1", "series_1")) }.exceptionOrNull() is IllegalArgumentException)
        journal.close()
    }

    @Test fun changedDeviceSessionCancelsAResultButOrdinaryRenewalDoesNot() = runBlocking<Unit> {
        var currentToken = token("device_a")
        var nextToken = token("device_a", "renewed")
        val profile = ServerProfile(name = "fixture", address = "https://example.invalid")
        val session = bound(profile, initialToken = currentToken) { command -> when (command.getString("op")) {
            "open" -> "device-catalog"
            "token" -> currentToken
            "request" -> { currentToken = nextToken; reply(200, home()) }
            else -> null
        } }
        val journal = MediaWriteJournal.open(isolatedContext())
        val repo = MediaLibraryRepository(session, { true }, journal)
        assertTrue(repo.home() is MediaReadResult.Success)
        nextToken = token("device_b")
        assertTrue(runCatching { repo.home() }.exceptionOrNull() is CancellationException)
        journal.close()
    }
}

package io.github.kkwans.nasfilebrowser

import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.app.SessionContext
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Independent synthetic sessions and test-only AtomicFile directories. */
@RunWith(AndroidJUnit4::class)
class MediaCatalogTransportContractTest {
    private fun testContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "media-catalog-test-${UUID.randomUUID()}")
        check(dir.mkdirs())
        return object : ContextWrapper(base) { override fun getFilesDir(): File = dir }
    }
    private fun token(): String {
        val payload = JSONObject().put("user", JSONObject().put("id", 7).put("username", "fixture"))
        return "header." + Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
    }
    private fun profile() = ServerProfile(name = "fixture", address = "https://example.invalid")
    private suspend fun bound(profile: ServerProfile, generation: Int = 1, native: suspend (JSONObject) -> Any?): SessionContext {
        val account = AccountRecord("fixture-account", profile.id, profile.sourceRevision, 7, "fixture", "fixture-ref", 0)
        return SessionContext(profile, account, NasSession.restore(profile, token(), 7, native), generation, "fixture-owner-$generation")
    }
    private fun reply(status: Int, value: JSONObject = JSONObject()) = JSONObject().put("status", status).put("body", value.toString())
    private fun item(kind: String = "video") = JSONObject().put("id", "item_1").put("libraryId", "lib_1").put("kind", kind)
        .put("title", "").put("revision", 7).put("createdAt", 1).put("sourceState", "available").put("metadataState", "restricted")
    private fun credit(id: String) = JSONObject().put("id", id).put("personId", "person_1").put("name", "Actor")
        .put("kind", "actor").put("role", "").put("roles", JSONArray()).put("position", 0)
    private fun detail(): JSONObject {
        val rating = JSONObject().put("rawSource", "douban").put("fields", JSONObject().put("value", JSONArray().put("8.6")))
            .put("provider", "douban").put("metric", "user_average").put("value", 8.6).put("scale", 10)
            .put("count", 125).put("countKind", "user_ratings").put("countPrecision", "lower_bound")
        return JSONObject().put("item", item("future_kind")).put("metadataRevision", 0)
            .put("fields", JSONObject().put("title", JSONObject().put("values", JSONArray()).put("source", "manual")
                .put("locked", true).put("explicitClear", true).put("revision", 7)))
            .put("credits", JSONArray().put(credit("credit_1"))).put("creditsNextCursor", "cursor:1")
            .put("ratings", JSONArray().put(JSONObject().put("id", "rating_1").put("scope", "movie").put("origin", "nfo")
                .put("importedAt", 1).put("rating", rating)))
            .put("artwork", JSONArray().put(JSONObject().put("id", "art_1").put("role", "poster").put("status", "remote_unavailable")
                .put("url", "https://example.invalid/not-a-fetch-target")))
            .put("externalIds", JSONArray().put(JSONObject().put("provider", "imdb").put("entityKind", "movie").put("value", "tt123")))
            .put("issues", JSONArray().put("context_required"))
            .put("candidates", JSONArray().put(JSONObject().put("code", "context_required").put("sourceKinds", JSONArray().put("movie"))))
            .put("issuesNextCursor", "candidate_1")
    }

    @Test fun detailAndRevisionBoundPagesPreserveSemanticsWithoutArtworkUrls() = runBlocking<Unit> {
        val seen = mutableListOf<String>()
        val profile = profile()
        val session = bound(profile) { command -> when (command.getString("op")) {
            "open" -> "fixture-catalog"
            "token" -> token()
            "request" -> {
                val endpoint = command.getString("endpoint"); seen += endpoint
                when {
                    endpoint.contains("/credits?") && endpoint.contains("revision=7") -> reply(200,
                        JSONObject().put("items", JSONArray().put(credit("credit_2"))).put("revision", 7))
                    endpoint.endsWith("/items/item_1") -> reply(200, detail())
                    endpoint.contains("/items?") -> reply(200, JSONObject().put("items", JSONArray().put(item())))
                    else -> reply(409)
                }
            }
            else -> null
        } }
        val journal = MediaWriteJournal.open(testContext())
        val repo = MediaLibraryRepository(session, { true }, journal)
        val list = repo.listItems("lib_1") as MediaReadResult.Success
        assertEquals(MediaItemKind.VIDEO, list.value.items.single().kind)
        val result = (repo.getItem("lib_1", "item_1") as MediaReadResult.Success).value
        assertEquals(MediaItemKind.UNKNOWN, result.item.kind)
        assertEquals(MediaItemMetadataState.RESTRICTED, result.item.metadataState)
        assertEquals("", result.item.title)
        assertTrue(result.fields.getValue("title").explicitClear)
        assertEquals(0L, result.metadataRevision)
        assertEquals("cursor:1", result.credits.nextCursor)
        assertEquals("candidate_1", result.candidates.nextCursor)
        val rating = result.ratings.items.single().rating
        assertEquals(MediaRatingMetric.USER_AVERAGE, rating.metric)
        assertEquals(8.6, requireNotNull(rating.value), 0.001)
        assertEquals(10.0, requireNotNull(rating.scale), 0.001)
        assertEquals(125L, rating.count)
        assertEquals("lower_bound", rating.countPrecision)
        assertEquals(MediaArtworkStatus.REMOTE_UNAVAILABLE, result.artwork.items.single().status)
        assertFalse(result.artwork.toString().contains("example.invalid"))
        val page = repo.listItemCredits("lib_1", "item_1", result.item.revision, cursor = result.credits.nextCursor) as MediaReadResult.Success
        assertEquals("credit_2", page.value.items.single().id)
        assertEquals(7L, page.value.revision)
        assertTrue(seen.any { it.contains("cursor=cursor%3A1") && it.contains("revision=7") })
        val conflict = repo.listItemCredits("lib_1", "item_1", 8, cursor = "cursor:1") as MediaReadResult.Failure
        assertEquals(MediaFailureKind.CONFLICT, conflict.error.kind)
        journal.close()
    }

    @Test fun sourceStatesRedactUnverifiedPathsAndAttestationReplaysExactly() = runBlocking<Unit> {
        val testContext = testContext()
        val profile = profile()
        val arrived = CompletableDeferred<Unit>()
        val pending = CompletableDeferred<Unit>()
        val wire = "/%ff/films%2b"
        fun source(id: String, access: String) = JSONObject().put("id", id).put("sourceId", "src_1").put("libraryId", "lib_1")
            .put("revision", 4).put("libraryRevision", 3).put("authorizationRevision", 1).put("access", access)
        val first = bound(profile) { command -> when (command.getString("op")) {
            "open" -> "fixture-source-first"
            "token" -> token()
            "request" -> if (command.getString("method") == "GET") reply(200, JSONObject().put("items", JSONArray()
                .put(source("binding_available", "available").put("wireRoot", wire).put("displayRoot", "/fixture"))
                .put(source("binding_offline", "offline").put("wireRoot", wire).put("displayRoot", "/fixture"))
                .put(source("binding_unverified", "unverified"))
                .put(source("binding_rebind", "rebind_required"))
                .put(source("binding_unknown", "future_state").put("wireRoot", "/must-not-leak").put("displayRoot", "/must-not-leak"))))
                else { arrived.complete(Unit); pending.await(); error("unexpected completion") }
            else -> null
        } }
        val journal = MediaWriteJournal.open(testContext)
        val repo = MediaLibraryRepository(first, { true }, journal)
        val sources = (repo.listSources("lib_1") as MediaReadResult.Success).value.items
        assertEquals(listOf(MediaSourceAccess.AVAILABLE, MediaSourceAccess.OFFLINE, MediaSourceAccess.UNVERIFIED,
            MediaSourceAccess.REBIND_REQUIRED, MediaSourceAccess.UNKNOWN), sources.map { it.access })
        assertNull(sources.last().wireRoot); assertNull(sources.last().displayRoot)
        assertNotNull(sources[1].wireRoot)
        val operation = repo.newOperation()
        val pendingWrite = async { repo.attestSource(operation, "lib_1", "binding_unverified", 4, MediaSourceRoot.fromWire(wire)) }
        arrived.await(); pendingWrite.cancelAndJoin(); journal.close()

        var sent: JSONObject? = null
        val second = bound(profile, 2) { command -> when (command.getString("op")) {
            "open" -> "fixture-source-second"
            "token" -> token()
            "request" -> {
                assertEquals("/api/media-library/v1/libraries/lib_1/sources/binding_unverified/attest", command.getString("endpoint"))
                sent = command.getJSONObject("body")
                reply(200, source("binding_unverified", "available").put("wireRoot", wire).put("displayRoot", "/fixture"))
            }
            else -> null
        } }
        val restored = MediaWriteJournal.open(testContext)
        val replay = MediaLibraryRepository(second, { true }, restored).retryOperation(operation)
        assertTrue(replay is MediaWriteResult.Success)
        assertEquals(operation.id, requireNotNull(sent).getString("operationId"))
        assertEquals(4L, requireNotNull(sent).getLong("expectedRevision"))
        assertEquals(wire, requireNotNull(sent).getString("wireRoot"))
        assertEquals("binding_unverified", restored.records(operation.account).single().receipt?.sourceBindingId)
        restored.close()
    }

    @Test fun conflictRetainsPatchForExactReplayAndUndoUsesConfirmedRevision() = runBlocking<Unit> {
        val testContext = testContext()
        val profile = profile()
        val first = bound(profile) { command -> when (command.getString("op")) {
            "open" -> "fixture-patch-first"
            "token" -> token()
            "request" -> reply(409)
            else -> null
        } }
        val journal = MediaWriteJournal.open(testContext)
        val repo = MediaLibraryRepository(first, { true }, journal)
        val operation = repo.newOperation()
        val fields = mapOf("title" to MediaMetadataFieldPatch(values = listOf("User title"), locked = true),
            "plot" to MediaMetadataFieldPatch(clear = true))
        val failed = repo.patchMetadata(operation, "lib_1", "item_1", 7, fields) as MediaWriteResult.Failure
        assertEquals(MediaFailureKind.CONFLICT, failed.error.kind)
        assertTrue(failed.error.mutationMayHaveCommitted)
        assertEquals(MediaWriteState.UNKNOWN, journal.records(operation.account).single().state)
        journal.close()
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val second = bound(profile, 2) { command -> when (command.getString("op")) {
            "open" -> "fixture-patch-second"
            "token" -> token()
            "request" -> {
                calls += command.getString("endpoint") to command.getJSONObject("body")
                reply(200, JSONObject().put("itemId", "item_1").put("revision", 8).put("metadataRevision", 8))
            }
            else -> null
        } }
        val restored = MediaWriteJournal.open(testContext)
        val current = MediaLibraryRepository(second, { true }, restored)
        val replay = current.retryOperation(operation) as MediaWriteResult.Success
        assertEquals(MediaMetadataReceipt("item_1", 8, 8), replay.value)
        val patch = calls.single().second
        assertEquals(operation.id, patch.getString("operationId"))
        assertEquals(7L, patch.getLong("expectedRevision"))
        assertEquals("User title", patch.getJSONObject("fields").getJSONObject("title").getJSONArray("values").getString(0))
        assertTrue(patch.getJSONObject("fields").getJSONObject("plot").getBoolean("clear"))
        val receipt = restored.records(operation.account).single().receipt
        assertEquals("item_1", receipt?.itemId)
        assertEquals(8L, receipt?.metadataRevision)
        val undo = current.undoMetadata(current.newOperation(), "lib_1", "item_1", 8, 8)
        assertTrue(undo is MediaWriteResult.Success)
        assertTrue(calls.last().first.endsWith("/metadata/undo"))
        assertEquals(8L, calls.last().second.getLong("targetRevision"))
        restored.close()
        val reloaded = MediaWriteJournal.open(testContext)
        assertEquals(2, reloaded.records(operation.account).count { it.state == MediaWriteState.ACKNOWLEDGED })
        assertTrue(reloaded.records(operation.account.copy(userId = 8)).isEmpty())
        reloaded.close()
    }

    @Test fun legacyPendingRecordSurvivesMetadataWriteAndCanStillReplay() = runBlocking<Unit> {
        val testContext = testContext()
        val profile = profile()
        val oldId = UUID.randomUUID().toString()
        val oldAccount = JSONObject().put("profileId", profile.id).put("sourceRevision", profile.sourceRevision)
            .put("accountKey", "fixture-account").put("userId", 7)
        val oldSession = JSONObject().put("owner", "old-owner").put("generation", 1)
        val oldRequest = JSONObject().put("kind", "CREATE_LIBRARY").put("body", JSONObject().put("operationId", oldId).put("name", "Old"))
        val oldEntry = JSONObject().put("account", oldAccount).put("original", oldSession).put("last", oldSession)
            .put("operationId", oldId).put("request", oldRequest).put("state", "UNKNOWN")
        File(testContext.filesDir, "media-library-writes.json").writeText(JSONObject().put("schema", 1).put("entries", JSONArray().put(oldEntry)).toString())
        var largePatchLength = 0
        val session = bound(profile, 2) { command -> when (command.getString("op")) {
            "open" -> "fixture-old-journal"
            "token" -> token()
            "request" -> if (command.getString("endpoint").endsWith("/metadata")) {
                largePatchLength = command.getJSONObject("body").getJSONObject("fields").getJSONObject("plot").getJSONArray("values").getString(0).length
                reply(200, JSONObject().put("itemId", "item_1").put("revision", 7).put("metadataRevision", 0))
            } else reply(201, JSONObject().put("id", "lib_old").put("name", "Old").put("revision", 1).put("role", "owner").put("archived", false))
            else -> null
        } }
        val journal = MediaWriteJournal.open(testContext)
        val repo = MediaLibraryRepository(session, { true }, journal)
        val legacyOperation = MediaOperation(repo.scope.account, oldId)
        assertEquals(MediaWriteState.UNKNOWN, journal.records(repo.scope.account).single().state)
        val result = repo.patchMetadata(repo.newOperation(), "lib_1", "item_1", 7,
            mapOf("plot" to MediaMetadataFieldPatch(values = listOf("x".repeat(12_000))))) as MediaWriteResult.Success
        assertEquals(12_000, largePatchLength)
        assertEquals(0L, result.value.metadataRevision)
        assertEquals(MediaWriteState.UNKNOWN, journal.records(repo.scope.account).single { it.operation == legacyOperation }.state)
        journal.close()
        val restored = MediaWriteJournal.open(testContext)
        val reconnected = MediaLibraryRepository(session, { true }, restored)
        assertTrue(reconnected.retryOperation(legacyOperation) is MediaWriteResult.Success)
        assertEquals(2, restored.records(repo.scope.account).count { it.state == MediaWriteState.ACKNOWLEDGED })
        restored.close()
    }

    @Test fun dynamicScanRuleAndFutureStatesRemainSafeUnknown() = runBlocking<Unit> {
        val profile = profile()
        var available = false
        val session = bound(profile) { command -> when (command.getString("op")) {
            "open" -> "fixture-future-state"
            "token" -> token()
            "request" -> if (command.getString("endpoint").endsWith("/scan-support")) {
                reply(200, JSONObject().put("localRulesRevision", 2).put("available", available).apply { if (!available) put("reason", "new_reason") })
            } else reply(200, JSONObject().put("id", "job_1").put("libraryId", "lib_1").put("status", "new_state")
                .put("generation", 1).put("attempt", 0).put("rulesRevision", 2).put("phase", "new_phase")
                .put("processed", 0).put("createdAt", 1).put("updatedAt", 1))
            else -> null
        } }
        val journal = MediaWriteJournal.open(testContext())
        val repo = MediaLibraryRepository(session, { true }, journal)
        val support = (repo.scanSupport() as MediaReadResult.Success).value
        assertEquals(2L, support.localRulesRevision)
        assertEquals(MediaScanUnavailableReason.UNKNOWN, support.reason)
        available = true
        assertTrue((repo.scanSupport() as MediaReadResult.Success).value.available)
        val scan = (repo.getScan("lib_1", "job_1") as MediaReadResult.Success).value
        assertEquals(MediaScanStatus.UNKNOWN, scan.status)
        assertEquals(MediaScanPhase.UNKNOWN, scan.phase)
        journal.close()
    }
}

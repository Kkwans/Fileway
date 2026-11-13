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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** All disk operations use a fresh test-only directory; production files are never opened. */
@RunWith(AndroidJUnit4::class)
class MediaLibraryTransportContractTest {
    private fun token(): String {
        val payload = JSONObject().put("user", JSONObject().put("id", 7).put("username", "fixture"))
        return "header." + Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
    }

    private fun fixtureContext(): Pair<Context, File> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "media-journal-test-${UUID.randomUUID()}")
        check(dir.mkdirs())
        return object : ContextWrapper(base) { override fun getFilesDir(): File = dir } to dir
    }

    private fun profile() = ServerProfile(name = "fixture", address = "https://example.invalid")
    private fun account(profile: ServerProfile) = AccountRecord("fixture-account", profile.id, profile.sourceRevision, 7, "fixture", "fixture-ref", 0)
    private suspend fun bound(profile: ServerProfile, generation: Int = 1, native: suspend (JSONObject) -> Any?): SessionContext {
        val session = NasSession.restore(profile, token(), 7, native)
        return SessionContext(profile, account(profile), session, generation, "fixture-owner-$generation")
    }
    private fun libraryBody() = JSONObject().put("id", "lib_fixture").put("name", "Films").put("revision", 1)
        .put("role", "owner").put("archived", false).toString()
    private fun reply(status: Int, body: String) = JSONObject().put("status", status).put("body", body)

    @Test fun canceledRequestSurvivesRestartAndReplaysOriginalOpaqueSource() = runBlocking {
        val (testContext, _) = fixtureContext()
        val profile = profile()
        val arrived = CompletableDeferred<Unit>()
        val pending = CompletableDeferred<Unit>()
        val first = bound(profile) { command -> when (command.getString("op")) {
            "open" -> "fixture-first"
            "request" -> { arrived.complete(Unit); pending.await(); error("unexpected completion") }
            "token" -> token()
            else -> null
        } }
        val journal = MediaWriteJournal.open(testContext)
        val repo = MediaLibraryRepository(first, { true }, journal)
        val operation = repo.newOperation()
        val originalWire = "/%ff/100%25%2b"
        val job = async { repo.createLocalSource(operation, "lib_fixture", 4, MediaSourceRoot.fromWire(originalWire)) }
        arrived.await()
        job.cancelAndJoin()

        journal.close()
        val restoredJournal = MediaWriteJournal.open(testContext)
        assertEquals(MediaWriteState.UNKNOWN, restoredJournal.records(repo.scope.account).single().state)
        val bodies = mutableListOf<JSONObject>()
        val second = bound(profile, 2) { command -> when (command.getString("op")) {
            "open" -> "fixture-second"
            "request" -> {
                assertEquals("POST", command.getString("method"))
                assertEquals("/api/media-library/v1/libraries/lib_fixture/sources/local", command.getString("endpoint"))
                bodies += command.getJSONObject("body")
                reply(201, JSONObject().put("id", "binding_fixture").put("sourceId", "src_fixture").put("libraryId", "lib_fixture")
                    .put("revision", 1).put("libraryRevision", 5).put("authorizationRevision", 1).put("access", "available")
                    .put("wireRoot", originalWire).put("displayRoot", "/fixture").toString())
            }
            "token" -> token()
            else -> null
        } }
        val reconnected = MediaLibraryRepository(second, { true }, restoredJournal)
        val replayed = reconnected.retryOperation(operation)
        assertTrue(replayed is MediaWriteResult.Success)
        assertEquals(operation.id, bodies.single().getString("operationId"))
        assertEquals(originalWire, bodies.single().getString("wireRoot"))
        assertEquals(4L, bodies.single().getLong("expectedRevision"))
        assertEquals(setOf("operationId", "wireRoot", "expectedRevision"), bodies.single().keys().asSequence().toSet())
        assertTrue(restoredJournal.records(repo.scope.account.copy(userId = 8)).isEmpty())
        restoredJournal.close()
        val record = MediaWriteJournal.open(testContext).records(repo.scope.account).single()
        assertEquals(MediaWriteState.ACKNOWLEDGED, record.state)
        assertEquals("binding_fixture", record.receipt?.sourceBindingId)
        assertEquals(first.owner, record.originalScope.owner)
        assertEquals(second.owner, record.lastScope.owner)
    }

    @Test fun confirmedHttpReceiptSurvivesCancellationDuringTokenSync() = runBlocking {
        val (testContext, _) = fixtureContext()
        val tokenRead = CompletableDeferred<Unit>()
        val waiting = CompletableDeferred<Unit>()
        val profile = profile()
        val session = bound(profile) { command -> when (command.getString("op")) {
            "open" -> "fixture-ack"
            "request" -> reply(201, libraryBody())
            "token" -> { tokenRead.complete(Unit); waiting.await(); token() }
            else -> null
        } }
        val journal = MediaWriteJournal.open(testContext)
        val repo = MediaLibraryRepository(session, { true }, journal)
        val operation = repo.newOperation()
        val job = async { repo.createLibrary(operation, "Films") }
        tokenRead.await()
        job.cancelAndJoin()
        journal.close()
        val record = MediaWriteJournal.open(testContext).records(repo.scope.account).single()
        assertEquals(MediaWriteState.ACKNOWLEDGED, record.state)
        assertEquals("lib_fixture", record.receipt?.libraryId)
        assertEquals(operation.id, record.operation.id)
    }

    @Test fun failedReceiptStorageDoesNotEraseKnownSuccess() = runBlocking {
        val (testContext, dir) = fixtureContext()
        val moved = File(dir.parentFile, "${dir.name}-moved")
        val blocked = File(dir.parentFile, "${dir.name}-blocker")
        val profile = profile()
        val session = bound(profile) { command -> when (command.getString("op")) {
            "open" -> "fixture-storage"
            "request" -> {
                check(dir.renameTo(moved))
                dir.writeText("isolated fixture obstruction")
                reply(201, libraryBody())
            }
            "token" -> token()
            else -> null
        } }
        val journal = MediaWriteJournal.open(testContext)
        val repo = MediaLibraryRepository(session, { true }, journal)
        val result = repo.createLibrary(repo.newOperation(), "Films")
        assertTrue(result is MediaWriteResult.Success && result.journalStorageFailed)
        assertEquals(MediaWriteState.ACKNOWLEDGED, journal.records(repo.scope.account).single().state)
        assertTrue(journal.records(repo.scope.account).single().storageFailed)
        // Only move this test's own fixture files; the original pending record remains intact.
        check(dir.renameTo(blocked)); check(moved.renameTo(dir))
        journal.close()
        assertEquals(MediaWriteState.UNKNOWN, MediaWriteJournal.open(testContext).records(repo.scope.account).single().state)
    }

    @Test fun readErrorsAndScopeChangesAreNotReportedAsUnsupported() = runBlocking {
        val (testContext, _) = fixtureContext()
        val profile = profile()
        var status = 200
        var current = true
        var delayRead = false
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = bound(profile) { command -> when (command.getString("op")) {
            "open" -> "fixture-read"
            "request" -> {
                if (delayRead) { arrived.complete(Unit); release.await() }
                reply(status, "{\"items\":[]}")
            }
            "token" -> token()
            else -> null
        } }
        val repo = MediaLibraryRepository(session, { current }, MediaWriteJournal.open(testContext))
        assertTrue(repo.listLibraries() is MediaReadResult.Success)
        status = 401
        assertEquals(MediaFailureKind.AUTHENTICATION, (repo.listLibraries() as MediaReadResult.Failure).error.kind)
        status = 503
        assertEquals(MediaFailureKind.UNAVAILABLE, (repo.listLibraries() as MediaReadResult.Failure).error.kind)
        status = 200; delayRead = true
        val read = async { repo.listLibraries() }
        arrived.await(); current = false; release.complete(Unit)
        try { read.await(); fail("stale response was delivered") } catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test fun futureJournalSchemaIsPreservedAndFailsClosed() = runBlocking {
        val (testContext, dir) = fixtureContext()
        val file = File(dir, "media-library-writes.json")
        val original = "{\"schema\":99,\"entries\":[]}"
        file.writeText(original)
        try { MediaWriteJournal.open(testContext); fail("future schema must not be cleared") } catch (_: MediaJournalException) { }
        assertEquals(original, file.readText())
    }
}

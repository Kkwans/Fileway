package io.github.kkwans.nasfilebrowser

import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackWriterTest {
    @Test fun slowNetworkDoesNotDelayLocalSavingAndCloseLeavesLatestPositionPending() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ClientDatabase::class.java).build()
        val stalled = CompletableDeferred<Unit>()
        try {
            val profile = ServerProfile(name = "Fixture", address = "http://fixture.example.test")
            val account = AccountRecord("${profile.id}/0/7", profile.id, 0, 7, "viewer", "fixture-ref", 0)
            database.profiles().saveProfile(profile); database.profiles().saveAccount(account)
            val history = PlaybackHistory(database.playback())
            val payload = JSONObject().put("user", JSONObject().put("id", 7).put("username", "viewer"))
            val token = "header." + Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
            val started = CompletableDeferred<Unit>()
            val api = NasSession.restore(profile, token, 7) { command ->
                when (command.getString("op")) {
                    "open" -> "handle"
                    "token" -> token
                    "request" -> { started.complete(Unit); stalled.await(); error("must cancel") }
                    else -> null
                }
            }
            val writer = PlaybackWriter(history, api)
            val first = PlaybackSnapshot(account.key, "/movie", "identity", "/movie.mkv", "/movie.mkv", "Movie", 1000, 10_000, 1, ProgressSync.PENDING)
            withTimeout(5000) { writer.submit(first).await(); started.await() }
            val latest = first.copy(positionMs = 8000, updatedAt = 2)
            withTimeout(5000) { writer.submit(latest).await() }
            assertEquals(latest, history.local(account, latest.resourceKey, latest.identity))
            withTimeout(5000) { writer.close() }
            assertEquals(latest, history.local(account, latest.resourceKey, latest.identity))
        } finally { stalled.cancel(); database.close() }
    }
}

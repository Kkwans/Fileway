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
class PlaybackHistoryTest {
    @Test fun identityAndAccountNamespacesDoNotReuseOldProgressAndZeroIsSaved() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ClientDatabase::class.java).build()
        try {
            val profile = ServerProfile(name = "Fixture", address = "http://fixture.example.test")
            database.profiles().saveProfile(profile)
            val one = AccountRecord("${profile.id}/0/1", profile.id, 0, 1, "one", "ref-one", 0)
            val two = one.copy(key = "${profile.id}/0/2", userId = 2, username = "two", credentialRef = "ref-two")
            database.profiles().saveAccount(one); database.profiles().saveAccount(two)
            val history = PlaybackHistory(database.playback())
            val snapshot = PlaybackSnapshot(one.key, "/video.mkv", "version-a", "/video.mkv", "/video.mkv", "Video", 5000, 10_000, 20, ProgressSync.PENDING)
            history.save(snapshot)
            assertNull(history.local(two, snapshot.resourceKey, snapshot.identity))
            assertNull(history.local(one, snapshot.resourceKey, "version-b"))
            assertEquals(5000L, PlaybackHistory.resume(snapshot, RemotePlayback("version-a", 3000, 10_000, 10, true)))
            assertEquals(3000L, PlaybackHistory.resume(snapshot.copy(sync = ProgressSync.SYNCED), RemotePlayback("version-a", 3000, 10_000, 30, true)))
            assertEquals(0L, PlaybackHistory.resume(snapshot, RemotePlayback("version-b", 0, 0, 0, false)))
            history.save(snapshot.copy(positionMs = 0, durationMs = 0, updatedAt = 30))
            assertEquals(0L, history.local(one, snapshot.resourceKey, snapshot.identity)?.positionMs)
            assertTrue(PlaybackHistory.wireMatchesPath("/目录/a+b.mkv", "/%E7%9B%AE%E5%BD%95/a%2Bb.mkv"))
            assertFalse(PlaybackHistory.wireMatchesPath("/�.mkv", "/%ed%a0%80.mkv"))
        } finally { database.close() }
    }

    @Test fun lateSyncCannotOverwriteANewerSnapshotAndReplacementResponseIsGuarded() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ClientDatabase::class.java).build()
        try {
            val profile = ServerProfile(name = "Fixture", address = "http://fixture.example.test")
            val account = AccountRecord("${profile.id}/0/7", profile.id, 0, 7, "viewer", "fixture-ref", 0)
            database.profiles().saveProfile(profile); database.profiles().saveAccount(account)
            val history = PlaybackHistory(database.playback())
            val payload = JSONObject().put("user", JSONObject().put("id", 7).put("username", "viewer"))
            val token = "header." + Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
            val waiting = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            var replacement = false
            val api = NasSession.restore(profile, token, 7) { command ->
                when (command.getString("op")) {
                    "open" -> "handle"
                    "token" -> token
                    "request" -> {
                        if (command.getString("method") == "PUT") {
                            assertEquals(1.5, command.getJSONObject("body").getDouble("position"), 0.0001)
                            waiting.complete(Unit); finish.await()
                        }
                        val id = if (replacement && command.getString("method") == "PUT") "version-b" else "version-a"
                        JSONObject().put("status", 200).put("body", JSONObject().put("identity", id).put("position", 1.5).put("duration", 10).put("updatedAt", 25).put("exists", true).toString())
                    }
                    else -> null
                }
            }
            val original = PlaybackSnapshot(account.key, "/video", "version-a", "/video.mkv", "/video.mkv", "Video", 1500, 10_000, 20, ProgressSync.PENDING)
            history.save(original)
            val sync = async { history.synchronize(api, original) }
            waiting.await()
            val newer = original.copy(positionMs = 8000, updatedAt = 30)
            history.save(newer); finish.complete(Unit); sync.await()
            assertEquals(newer, history.local(account, original.resourceKey, original.identity))
            replacement = true
            history.synchronize(api, original)
            val guard = history.local(account, original.resourceKey, "version-b")
            assertNotNull(guard)
            assertEquals(0L, PlaybackHistory.resume(guard, RemotePlayback("version-b", 1500, 10_000, 25, true)))
        } finally { database.close() }
    }
}

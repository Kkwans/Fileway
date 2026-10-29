package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.fileway.remote.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reuses the existing signed phone test component and actual trusted WSS
 * protocol. No new TV test installation, password access or server login. */
@RunWith(AndroidJUnit4::class)
class TvAudioCompanionProbeTest {
    @Test fun existingTrustedTvRendersOwnedTrueHdAndAac() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configFile = File(context.getExternalFilesDir(null), "fileway-tv-audio-probe.json")
        assumeTrue("Explicit owned audio probe configuration required", configFile.isFile)
        val config = JSONObject(configFile.readText())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val client = TvRemoteClient(context, scope)
        var ownerEpoch: Long? = null
        var ownedMediaEpoch: Long? = null
        var previousMode = "AUTO"
        var previousRoute = "HOME"
        val records = JSONArray()
        suspend fun state(predicate: (RemoteSnapshot) -> Boolean): RemoteSnapshot = withTimeout(25_000) {
            client.state.first { it.phase == RemotePhase.CONNECTED && it.snapshot?.let(predicate) == true }.snapshot!!
        }
        suspend fun command(name: String, body: JSONObject = JSONObject()): JSONObject? {
            check(client.state.value.snapshot?.sessionEpoch == ownerEpoch) { "TV source changed; refuse controlling another account" }
            val result = client.execute(name, body)
            check(result.completed) { "$name: ${result.code} ${result.payload?.optString("message")}" }
            return result.payload
        }
        suspend fun find(name: String): JSONObject? {
            var offset = 0
            while (offset < 10000) {
                val page = command("app.list", JSONObject().put("offset", offset).put("count", 32))!!
                val rows = page.getJSONArray("files")
                (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("name") == name }?.let { return it }
                if (rows.length() == 0) break
                offset += rows.length()
                if (offset >= page.getInt("total")) break
            }
            return null
        }
        suspend fun openFolder(name: String) {
            val folder = find(name) ?: error("Owned fixture folder unavailable: $name")
            check(folder.getBoolean("directory"))
            val before = client.state.value.snapshot!!.content.optString("directory")
            command("media.open", JSONObject().put("id", folder.getString("id")))
            state { it.scene == "BROWSE" && !it.content.optBoolean("busy") && it.content.optString("directory") != before }
        }
        try {
            val paired = client.store.dao.device(config.getString("deviceId")) ?: error("Existing pairing required")
            check(paired.trusted)
            val trusted = paired.descriptor()
            val found = withTimeout(15_000) { TvServiceDiscovery(context).scan().first { scan ->
                scan.devices.any { it.id == trusted.id && it.fingerprint == trusted.fingerprint }
            } }.devices.first { it.id == trusted.id }
            client.connect(trusted.at(found.address, found.port))
            val initial = state { it.scene == "BROWSE" && it.content.optBoolean("connected") && !it.content.optBoolean("busy") }
            ownerEpoch = initial.sessionEpoch
            previousRoute = initial.content.optString("route", "HOME")
            previousMode = initial.content.getJSONObject("playback").optString("hdrMode", "AUTO")
            command("app.navigate", JSONObject().put("route", "HOME"))
            command("app.navigate", JSONObject().put("route", "FILES"))
            state { it.content.optString("route") == "FILES" && !it.content.optBoolean("busy") && it.content.optString("directory") == "/" }
            if (find("volume2") != null) openFolder("volume2")
            val path = config.getJSONArray("fixtureSegments")
            for (i in 0 until path.length()) openFolder(path.getString(i))
            val file = find(config.getString("fixtureName")) ?: error("Owned multi-codec fixture unavailable")
            command("media.open", JSONObject().put("id", file.getString("id")))
            val opened = state { it.scene == "VIDEO" && it.content.getJSONObject("playback").optBoolean("firstFrameRendered") }
            ownedMediaEpoch = opened.mediaEpoch
            command("playback.setHdrMode", JSONObject().put("mode", "NATIVE"))
            state { it.content.getJSONObject("playback").optString("hdrMode") == "NATIVE" }
            command("subtitle.disable")
            for (mime in listOf("audio/true-hd", "audio/mp4a-latm")) {
                val tracks = client.state.value.snapshot!!.content.getJSONObject("playback").getJSONArray("audio")
                val track = (0 until tracks.length()).map { tracks.getJSONObject(it) }.single { it.optString("codec") == mime }
                command("playback.pause")
                command("audio.select", JSONObject().put("id", track.getInt("id")))
                command("playback.seekTo", JSONObject().put("positionMs", 2000))
                command("playback.play")
                val result = state {
                    check(it.sessionEpoch == ownerEpoch && it.mediaEpoch == ownedMediaEpoch)
                    val playback = it.content.getJSONObject("playback")
                    check(it.content.isNull("error")) { "TV playback failed: ${it.content.optString("error")}" }
                    playback.optInt("selectedAudio") == track.getInt("id") && playback.optBoolean("playing") &&
                        playback.optLong("positionMs") >= 2400 && playback.optInt("audioRenderedBuffers") > 5 &&
                        (mime != "audio/true-hd" || playback.optString("audioDecoder").startsWith("ffmpeg"))
                }.content.getJSONObject("playback")
                records.put(JSONObject().put("mime", mime).put("decoder", result.getString("audioDecoder"))
                    .put("renderedBuffers", result.getInt("audioRenderedBuffers")).put("positionMs", result.getLong("positionMs")))
            }
        } finally {
            withContext(NonCancellable) {
                val current = client.state.value.snapshot
                if (current?.sessionEpoch == ownerEpoch && current?.mediaEpoch == ownedMediaEpoch && ownedMediaEpoch != null) {
                    runCatching { command("playback.setHdrMode", JSONObject().put("mode", previousMode)) }
                    runCatching { command("playback.stop") }
                    runCatching { command("app.navigate", JSONObject().put("route", previousRoute)) }
                }
                client.disconnect()
                scope.cancel()
                configFile.delete()
                File(context.getExternalFilesDir(null), "tv-audio-companion-result.json").writeText(
                    JSONObject().put("records", records).put("scope", "Actual independent TV process, trusted phone remote transport, JNI and sink buffers; physical audibility/HDR not verified").toString(2))
            }
        }
    }
}

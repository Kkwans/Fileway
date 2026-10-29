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
class TvSubtitleCompanionProbeTest {
    @Test fun sourceAssFontAndSrtRenderWithOptionalCompatibleSeek() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configFile = File(context.getExternalFilesDir(null), "fileway-tv-subtitle-probe.json")
        assumeTrue("Explicit owned subtitle probe configuration required", configFile.isFile)
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
            command("playback.pause")
            command("subtitle.select", JSONObject().put("id", 6).put("source", true))
            state { val p = it.content.getJSONObject("playback")
                p.optInt("selectedSourceSubtitle") == 6 && p.optInt("selectedSubtitle", -1) >= 0 && !p.optBoolean("subtitleLoading") }
            command("playback.seekTo", JSONObject().put("positionMs", 2000))
            state { it.content.getJSONObject("playback").optLong("positionMs") in 1800..2300 }
            delay(800)
            fun capture(name: String): Pair<Int, Int> {
                val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                var red = 0; var blue = 0
                // The authored fixture has a black canvas. Only its attached
                // ASS font and vector event contribute these saturated colors.
                for (y in 0 until bitmap.height * 2 / 5 step 4) for (x in 0 until bitmap.width step 4) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = android.graphics.Color.red(pixel); val g = android.graphics.Color.green(pixel); val b = android.graphics.Color.blue(pixel)
                    if (r > 220 && g < 30 && b < 30) red++
                    if (b > 220 && r < 30 && g < 30) blue++
                }
                bitmap.recycle()
                return red to blue
            }
            val visible = capture("tv-subtitle-ass.png")
            assertTrue("Attached ASS font must actually render: $visible", visible.first > 100)
            assertTrue("ASS vector must actually render: $visible", visible.second > 100)
            records.put(JSONObject().put("stage", "source-ass-font").put("playback", client.state.value.snapshot!!.content.getJSONObject("playback")))
            command("subtitle.disable")
            state { it.content.getJSONObject("playback").optInt("selectedSubtitle", -1) == -1 }
            delay(400)
            val cleared = capture("tv-subtitle-off.png")
            assertEquals("ASS glyphs must clear", 0 to 0, cleared)
            command("playback.stop")
            state { it.scene == "BROWSE" }
            val video = find(config.getString("videoName")) ?: error("Owned timeline fixture unavailable")
            command("media.open", JSONObject().put("id", video.getString("id")))
            val timeline = state { it.scene == "VIDEO" && it.content.getJSONObject("playback").optBoolean("firstFrameRendered") }
            ownedMediaEpoch = timeline.mediaEpoch
            command("playback.pause")
            val sidecar = state { it.content.getJSONObject("playback").getJSONArray("sidecars").length() > 0 }
                .content.getJSONObject("playback").getJSONArray("sidecars")
            val subtitle = (0 until sidecar.length()).map { sidecar.getJSONObject(it) }.single { it.getString("name") == config.getString("subtitleName") }
            command("subtitle.selectNasFile", JSONObject().put("id", subtitle.getString("id")))
            command("playback.seekTo", JSONObject().put("positionMs", 121000))
            state { val p = it.content.getJSONObject("playback"); p.optLong("positionMs") in 120000..123000 && p.optInt("selectedSubtitle", -1) >= 0 && !p.optBoolean("subtitleLoading") }
            delay(800)
            capture("tv-subtitle-srt.png")
            records.put(JSONObject().put("stage", "native-source-srt").put("playback", client.state.value.snapshot!!.content.getJSONObject("playback")))
            // Explicit diagnostic opt-in; ordinary subtitle acceptance does not
            // silently expand into a server compatibility/transcoding journey.
            if (config.optBoolean("includeSdr")) {
                command("playback.setHdrMode", JSONObject().put("mode", "SDR"))
                val compatible = withTimeout(90_000) { client.state.first {
                    val snapshot = it.snapshot
                    snapshot?.let { check(it.content.isNull("error")) { "TV switch failed: ${it.content.optString("error")}" } }
                    snapshot?.scene == "VIDEO" && !snapshot.content.optBoolean("busy") && snapshot.content.getJSONObject("playback").let { p -> p.optBoolean("sdrActive") && p.optBoolean("firstFrameRendered") && !p.optBoolean("subtitleLoading") }
                }.snapshot!!.content.getJSONObject("playback") }
                records.put(JSONObject().put("stage", "sdr-source-timeline").put("playback", compatible))
                assertTrue("Original timeline must survive SDR switch: $compatible", compatible.optLong("positionMs") in 120000..124000)
                assertFalse("Pause intent survives SDR", compatible.optBoolean("playing"))
                assertTrue("Source SRT restored", compatible.optInt("selectedSubtitle", -1) >= 0)
                delay(800)
                capture("tv-subtitle-sdr.png")
            }
        } catch (failure: Throwable) {
            val last = client.state.value.snapshot?.content
            records.put(JSONObject().put("stage", "failure").put("error", last?.opt("error"))
                .put("notice", last?.opt("notice")).put("busy", last?.opt("busy"))
                .put("playback", last?.optJSONObject("playback")))
            throw failure
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
                File(context.getExternalFilesDir(null), "tv-subtitle-companion-result.json").writeText(
                    JSONObject().put("records", records).put("scope", "Actual independent TV source ASS/font, subtitle off and external SRT; optional SDR original-time check only when configured; inspect composed screenshots; Q1/HDR not verified").toString(2))
            }
        }
    }
}

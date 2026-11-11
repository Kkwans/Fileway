package io.github.kkwans.fileway.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.data.VideoDecodePolicy
import io.github.kkwans.nasfilebrowser.player.MediaResourcePolicy
import io.github.kkwans.nasfilebrowser.player.MediaSubtitleLayer
import io.github.kkwans.nasfilebrowser.player.NativePlayer
import io.github.kkwans.nasfilebrowser.player.PlayerViewport
import io.github.kkwans.nasfilebrowser.player.SourceSubtitleAssets
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Actual TV UI/player + authenticated broker + isolated Fileway fixture.
 * Generated media and software decoding do not prove projector/HDR behavior. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class TvProgressiveAssJourneyTest {
    @Test fun coldResumeUsesAuthenticatedProgressiveAssAndKeepsFontOnReselect() = runJourney(checkLoadFeedback = false)

    @Test fun showsActualBytesAndClearsFeedbackWhenCancelled() = runJourney(checkLoadFeedback = true)

    @Test fun embeddedFontsShareBudgetAndKeepSubtitleDrawing(): Unit = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Use the project-owned extracted font on an isolated TV", InstrumentationRegistry.getArguments().getString("ownedAssFontBudget") == "true")
        val context = ins.targetContext
        val output = requireNotNull(context.getExternalFilesDir(null))
        val font = File(output, "NfbFixtureBars.ttf").readBytes()
        assertEquals(1184, font.size)
        val root = File(context.cacheDir, "ass-font-budget-${System.nanoTime()}").apply { check(mkdir()) }
        val movie = File(root, "owned.mp4")
        ins.context.assets.open("media/tv-owned-source.mp4").use { input -> movie.outputStream().use { input.copyTo(it) } }
        val encoded = encodeAssFont(font)
        val script = File(root, "embedded.ass").apply { writeText("""[Script Info]
ScriptType: v4.00+
PlayResX: 640
PlayResY: 360
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Bars,NfbFixtureBars,60,&H000000FF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1
[Fonts]
fontname: NfbFixtureBars.ttf
$encoded
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.50,0:00:11.00,Bars,,0,0,0,,{\an7\pos(80,40)}I
Dialogue: 0,0:00:00.50,0:00:11.00,Bars,,0,0,0,,{\an7\pos(300,60)\1c&HFF0000&\p1}m 0 0 l 80 0 80 40 0 40{\p0}
""".replace("\n", "\r")) }
        val assets = SourceSubtitleAssets()
        val lease = assets.acquire("owned-font-stream", 16_384L) { script.copyTo(File(root, "stream.ass")) }
        val scenario = androidx.test.core.app.ActivityScenario.launch(TvActivity::class.java)
        var player: NativePlayer? = null
        var viewport: PlayerViewport? = null
        fun subtitleLayer(view: View): MediaSubtitleLayer? {
            if (view is MediaSubtitleLayer) return view
            return if (view is ViewGroup) (0 until view.childCount).firstNotNullOfOrNull { subtitleLayer(view.getChildAt(it)) } else null
        }
        fun red(pixel: Int) = Color.alpha(pixel) > 100 && Color.red(pixel) > 180 && Color.green(pixel) < 60 && Color.blue(pixel) < 60
        fun blue(pixel: Int) = Color.alpha(pixel) > 100 && Color.blue(pixel) > 180 && Color.red(pixel) < 60 && Color.green(pixel) < 60
        suspend fun frame(expectedBars: Boolean, name: String) = withTimeout(10_000) {
            while (true) {
                val visible = withContext(Dispatchers.Main) {
                    val view = subtitleLayer(requireNotNull(viewport)) ?: return@withContext false
                    if (view.width <= 0 || view.height <= 0) return@withContext false
                    val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(bitmap)
                        canvas.scale(320f / view.width, 180f / view.height)
                        view.draw(canvas)
                        val pixels = IntArray(320 * 180)
                        bitmap.getPixels(pixels, 0, 320, 0, 0, 320, 180)
                        var bars = 0
                        var previous = false
                        for (x in 0 until 140) {
                            val current = (0 until 180).count { y -> red(pixels[y * 320 + x]) } > 3
                            if (current && !previous) bars++
                            previous = current
                        }
                        val valid = pixels.count(::blue) > 150 && (bars == 2) == expectedBars
                        if (valid) File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        valid
                    } finally { bitmap.recycle() }
                }
                if (visible) break
                delay(40)
            }
        }
        suspend fun open(budget: Long): NativePlayer {
            scenario.onActivity { activity ->
                player?.release()
                viewport?.let { (it.parent as? ViewGroup)?.removeView(it) }
                viewport = PlayerViewport(activity).also { (activity.window.decorView as ViewGroup).addView(it, ViewGroup.LayoutParams(640, 360)) }
                player = NativePlayer(activity, MediaResourcePolicy.LowMemoryTv.copy(assFontBytes = budget)).also {
                    it.attach(requireNotNull(viewport))
                    it.open(Uri.fromFile(movie).toString(), 2_000, autoplay = false, videoDecodePolicy = VideoDecodePolicy.SOFTWARE)
                }
            }
            return requireNotNull(player).also { native ->
                withTimeout(25_000) { native.state.first { it.firstFrameRendered && abs(it.positionMs - 2_000L) < 250L } }
            }
        }
        try {
            val accepted = open(font.size.toLong())
            val acceptedGeneration = accepted.state.value.mediaGeneration
            withContext(Dispatchers.Main) {
                assertTrue(accepted.importSubtitle(Uri.fromFile(script).toString(), "owned.ass", "owned-full", true))
                assertTrue("An embedded font and a matching attachment share a registration", accepted.addSubtitleFont("NfbFixtureBars.ttf", font))
                assertFalse("Another registration would exceed the shared font budget", accepted.addSubtitleFont("other.ttf", font))
            }
            frame(true, "ass-font-budget-accepted.png")
            assertNull(accepted.state.value.subtitleFontNotice)
            assertEquals(acceptedGeneration, accepted.state.value.mediaGeneration)

            val limited = open(font.size.toLong() - 1)
            val limitedGeneration = limited.state.value.mediaGeneration
            withContext(Dispatchers.Main) {
                assertTrue(limited.importSubtitle(Uri.fromFile(script).toString(), "owned.ass", "owned-full", true))
            }
            withTimeout(5_000) { limited.state.first { it.subtitleFontNotice != null && !it.subtitleLoading } }
            frame(false, "ass-font-budget-full-fallback.png")
            withContext(Dispatchers.Main) { limited.subtitle(-1) }
            withTimeout(5_000) { limited.state.first { it.subtitleFontNotice == null && it.selectedSubtitle == -1 } }
            withContext(Dispatchers.Main) { assertTrue(limited.importAssStream(lease, "owned.ass", "owned-stream") {}) }
            withTimeout(5_000) { limited.state.first { it.subtitleFontNotice != null && !it.subtitleLoading } }
            frame(false, "ass-font-budget-stream-fallback.png")
            assertEquals(limitedGeneration, limited.state.value.mediaGeneration)
            assertFalse(limited.state.value.playing)
            assertTrue(abs(limited.state.value.positionMs - 2_000L) < 250L)
        } finally {
            withContext(NonCancellable) {
                scenario.onActivity {
                    player?.release()
                    viewport?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
                }
                scenario.close()
                lease.close()
                assets.close()
                script.delete()
                movie.delete()
                root.delete()
            }
        }
    }

    private fun encodeAssFont(data: ByteArray): String = buildString {
        var buffer = 0
        var bits = 0
        var lineLength = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 255)
            bits += 8
            while (bits >= 6) {
                bits -= 6
                append(((buffer shr bits) + 33).toChar())
                buffer = buffer and ((1 shl bits) - 1)
                if (++lineLength == 80) { append('\n'); lineLength = 0 }
            }
        }
        if (bits > 0) append(((buffer shl (6 - bits)) + 33).toChar())
    }

    private fun runJourney(checkLoadFeedback: Boolean): Unit = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation(); val args = InstrumentationRegistry.getArguments()
        assumeTrue("A throttled owned fixture is required for byte feedback", !checkLoadFeedback || args.getString("fixture_ass_progress") == "true")
        val url = requireNotNull(args.getString("fixture_url")); val username = requireNotNull(args.getString("fixture_username"))
        val password = requireNotNull(args.getString("fixture_password")); check(username == "tv-fixture")
        val device = UiDevice.getInstance(ins)
        device.executeShellCommand("am start --windowingMode 1 -n io.github.kkwans.fileway.tv/.TvActivity")
        val activity = withTimeout(20_000) {
            var found: TvActivity? = null
            while (found == null) {
                found = withContext(Dispatchers.Main) { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<TvActivity>().firstOrNull() }
                if (found == null) delay(50)
            }
            found
        }
        val model = withContext(Dispatchers.Main) { ViewModelProvider(activity)[TvModel::class.java] }
        var stage = "restore"
        suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
        suspend fun waitFor(timeout: Long = 20_000, predicate: () -> Boolean) = withTimeout(timeout) { while (!predicate()) delay(50) }
        val directory = File(ins.targetContext.getExternalFilesDir(null), "ass-api28-evidence").apply { mkdirs() }
        fun pixels(name: String, save: (Pair<Int, Int>) -> Boolean = { true }): Pair<Int, Int> {
            val bitmap = requireNotNull(ins.uiAutomation.takeScreenshot())
            try {
                var red = 0; var blue = 0
                for (y in 0 until bitmap.height * 2 / 5 step 4) for (x in 0 until bitmap.width step 4) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
                    if (r > 220 && g < 30 && b < 30) red++
                    if (b > 220 && r < 30 && g < 30) blue++
                }
                val result = red to blue
                if (save(result)) File(directory, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                return result
            } finally { bitmap.recycle() }
        }
        suspend fun visible(name: String, wanted: Boolean) = withTimeout(12_000) {
            while (true) {
                val ready: (Pair<Int, Int>) -> Boolean = { (red, blue) -> if (wanted) red > 50 && blue > 100 else red == 0 && blue == 0 }
                val result = pixels(name, ready)
                if (ready(result)) break
                delay(100)
            }
        }
        try {
            waitFor { !model.state.value.restoring }
            stage = "fixture login"
            if (model.state.value.connected) {
                val current = requireNotNull(model.context)
                check(current.profile.address.trimEnd('/') == url.trimEnd('/') && current.account.username == username) {
                    "Only the owned fixture session may be reused"
                }
                main { model.media.setVideoDecodePolicy(VideoDecodePolicy.SOFTWARE) }
            } else main { model.media.setVideoDecodePolicy(VideoDecodePolicy.SOFTWARE); model.login(url, username, password) }
            waitFor(30_000) {
                val login = model.state.value
                check(login.busy || login.connected || login.error == null) { "Fixture login failed: ${login.error}" }
                login.connected && !login.busy
            }
            waitFor { model.media.playbackPreferences.videoDecodePolicy.value == VideoDecodePolicy.SOFTWARE }
            stage = "find owned movie"
            main { model.navigate(TvRoute.FILES); model.browse("/Cinema", "/Cinema", false) }
            waitFor { model.state.value.path == "/Cinema" && !model.state.value.busy }
            val movie = model.state.value.files.single { it.name == "Fileway owned BT709 subtitle styles.mkv" }
            val owner = requireNotNull(model.context)
            withContext(Dispatchers.IO) {
                owner.api.action("PUT", "/api/media/playback", JSONObject().put("path", movie.path).put("position", 2.0).put("duration", 12.0))
            }
            stage = "cold resume"
            main { model.open(movie, listOf(movie)) }
            if (checkLoadFeedback) {
                waitFor(30_000) { model.media.state.value.let { it.info != null && it.sdrAvailable && !it.busy } }
                main { model.media.switchMode(TvHdrMode.SDR, targetMs = 2000) }
            }
            waitFor(30_000) {
                (!checkLoadFeedback || model.media.state.value.compatible) && model.media.player.state.value.firstFrameRendered
            }
            main { model.media.player.pause() }
            assertTrue("Fixture must expose the ASS streaming capability", model.media.state.value.sourceASSStreaming)
            val ass = model.media.state.value.sourceText.single { it.codec in setOf("ass", "ssa") }
            if (checkLoadFeedback) {
                val generation = model.media.player.state.value.mediaGeneration
                main { model.media.selectSourceSubtitle(ass.index) }
                stage = "waiting for source subtitle bytes"
                waitFor { model.media.state.value.subtitleLoadDetail == "正在等待字幕数据…" }
                stage = "received source subtitle bytes"
                waitFor { model.media.state.value.subtitleLoadDetail?.startsWith("已接收字幕 ") == true }
                stage = "show received source subtitle bytes"
                assertTrue("received bytes must be visible on TV", device.wait(Until.hasObject(By.textStartsWith("已接收字幕 ")), 2_000))
                main {
                    val playback = model.remoteGateway.snapshot().getJSONObject("content").getJSONObject("playback")
                    assertTrue(playback.getBoolean("subtitleLoading"))
                    assertTrue(playback.getString("subtitleLoadDetail").startsWith("已接收字幕 "))
                }
                pixels("subtitle-receiving.png")
                stage = "cancel subtitle reception"
                main { model.media.subtitlesOff() }
                waitFor { !model.media.state.value.subtitleLoading && !model.media.player.state.value.subtitleLoading }
                assertNull(model.media.state.value.subtitleLoadDetail)
                assertEquals(generation, model.media.player.state.value.mediaGeneration)
            }
            stage = "source subtitle"
            main { model.media.selectSourceSubtitle(ass.index) }
            waitFor(60_000) {
                val media = model.media.state.value
                check(media.subtitleError == null) { "Owned source subtitle failed: ${media.subtitleError}" }
                media.selectedSourceSubtitle == ass.index && !media.subtitleLoading &&
                    model.media.player.state.value.let { it.selectedSubtitle >= 0 && !it.subtitleLoading }
            }
            val native = model.media.player; val id = native.state.value.selectedSubtitle
            assertTrue("cold resume must use source ASS", native.importedSubtitleIdentity(id)?.sourceKey?.startsWith("source:") == true)
            assertTrue("source time retained", model.media.sourcePosition() in 1800..4500)
            stage = "font and vector"
            visible("source-ass.png", true)
            val generation = native.state.value.mediaGeneration
            stage = "off"
            main { model.media.subtitlesOff() }; waitFor { native.state.value.selectedSubtitle == -1 }; visible("off.png", false)
            stage = "reselect"
            main { model.media.selectSourceSubtitle(ass.index) }
            waitFor { model.media.state.value.selectedSourceSubtitle == ass.index && !model.media.state.value.subtitleLoading }
            visible("reselected.png", true)
            assertEquals(generation, native.state.value.mediaGeneration)
            assertTrue(abs(model.media.sourcePosition() - 2000) < 2500)
            if (checkLoadFeedback) return@runBlocking

            val originalPosition = model.media.sourcePosition()
            stage = "SDR compatibility"
            main { model.media.switchMode(TvHdrMode.SDR) }
            waitFor(90_000) {
                val media = model.media.state.value
                check(media.error == null && media.subtitleError == null) { "Owned compatibility failed: ${media.error ?: media.subtitleError}" }
                !media.busy && media.compatible && media.selectedSourceSubtitle == ass.index && !media.subtitleLoading &&
                    native.state.value.let { it.firstFrameRendered && !it.subtitleLoading }
            }
            assertTrue("window uses original timeline", model.media.state.value.sourceOffsetMs >= 1000)
            assertTrue("source position retained", abs(model.media.sourcePosition() - originalPosition) < 750)
            assertFalse("pause state retained", native.wantsPlay)
            visible("sdr-ass.png", true)

            stage = "return to original"
            main { model.media.switchMode(TvHdrMode.NATIVE) }
            waitFor(30_000) {
                val media = model.media.state.value
                check(media.error == null && media.subtitleError == null) { "Owned native restore failed: ${media.error ?: media.subtitleError}" }
                !media.busy && !media.compatible && media.selectedSourceSubtitle == ass.index && !media.subtitleLoading &&
                    native.state.value.let { it.firstFrameRendered && !it.subtitleLoading }
            }
            assertTrue(abs(model.media.sourcePosition() - originalPosition) < 750)
            assertFalse(native.wantsPlay)
            visible("native-restored.png", true)
        } catch (failure: Throwable) {
            val media = model.media.state.value; val player = model.media.player.state.value
            runCatching { pixels("failure.png") }
            throw AssertionError("TV ASS stage=$stage connected=${model.state.value.connected} loginError=${model.state.value.error} mediaError=${media.error} playerError=${player.error} " +
                "subtitleError=${media.subtitleError} loading=${media.subtitleLoading || player.subtitleLoading} subtitleLoadDetail=${media.subtitleLoadDetail} " +
                "source=${media.selectedSourceSubtitle} selected=${player.selectedSubtitle} position=${player.positionMs}", failure)
        } finally { withContext(NonCancellable + Dispatchers.Main) { model.media.closeAndSave(); activity.finish() } }
    }
}

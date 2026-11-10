package io.github.kkwans.fileway.tv

import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import io.github.kkwans.nasfilebrowser.data.VideoDecodePolicy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Actual TV UI/player + authenticated broker + isolated Fileway fixture.
 * Generated media and software decoding do not prove projector/HDR behavior. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class TvProgressiveAssJourneyTest {
    @Test fun coldResumeUsesAuthenticatedProgressiveAssAndKeepsFontOnReselect(): Unit = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation(); val args = InstrumentationRegistry.getArguments()
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
            waitFor(30_000) { model.media.player.state.value.firstFrameRendered }
            main { model.media.player.pause() }
            assertTrue("Fixture must expose the ASS streaming capability", model.media.state.value.sourceASSStreaming)
            val ass = model.media.state.value.sourceText.single { it.codec in setOf("ass", "ssa") }
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
            throw AssertionError("TV ASS stage=$stage connected=${model.state.value.connected} loginError=${model.state.value.error} mediaError=${media.error} " +
                "subtitleError=${media.subtitleError} loading=${media.subtitleLoading || player.subtitleLoading} " +
                "source=${media.selectedSourceSubtitle} selected=${player.selectedSubtitle} position=${player.positionMs}", failure)
        } finally { withContext(NonCancellable + Dispatchers.Main) { model.media.closeAndSave(); activity.finish() } }
    }
}

package io.github.kkwans.fileway.tv

import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.data.VideoDecodePolicy
import java.io.File
import java.net.URI
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Uses the authored black-frame, six-track PGS fixture with BT.709 metadata. */
@RunWith(AndroidJUnit4::class)
class TvSourcePgsJourneyTest {
    @Test fun pgsRetainsPixelsAndSourceTimeAcrossCompatibilitySwitch(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val runId = args.getString("ownedPgsModeRun")
        assumeTrue("Use the owned TV and generated PGS fixture", runId?.matches(Regex("[a-z0-9]+")) == true)
        val url = requireNotNull(args.getString("fixture_url"))
        val uri = URI(url)
        check(uri.scheme == "http" && uri.host == "10.0.2.2" && uri.port in 1..65535 && uri.rawPath.isEmpty())
        val context = instrumentation.targetContext
        val testHdr = args.getString("ownedPgsHdr") == "true"
        val prefs = context.getSharedPreferences("tv-playback", 0)
        val savedMode = prefs.getString("hdr_mode", null)
        val evidence = File(requireNotNull(context.getExternalFilesDir(null)), "tv-pgs-mode-$runId")
            .apply { check(mkdir()) }
        val scenario = ActivityScenario.launch(TvActivity::class.java)
        lateinit var tv: TvModel
        var activeModel: TvModel? = null
        var stage = "restore saved fixture account"
        var originalPolicy: VideoDecodePolicy? = null
        var hdrPgsVerified = false
        fun report(status: String) {
            val media = activeModel?.media?.state?.value
            val player = activeModel?.media?.player?.state?.value
            File(evidence, "result.json").writeText(JSONObject().put("status", status).put("stage", stage).put("hdrPgsVerified", hdrPgsVerified)
                .put("compatible", media?.compatible).put("selectedSourceSubtitle", media?.selectedSourceSubtitle)
                .put("positionMs", activeModel?.media?.sourcePosition() ?: 0)
                .put("decoderError", player?.error).put("subtitleError", media?.subtitleError).toString(2))
        }
        suspend fun waitFor(timeout: Long = 25_000, check: () -> Boolean) = withTimeout(timeout) {
            while (!check()) delay(50)
        }
        suspend fun capture(name: String, red: Boolean = false, green: Boolean = false) = withTimeout(15_000) {
            while (true) {
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    var reds = 0
                    var greens = 0
                    // The fixture draws an 80x30 rectangle at (200,160) in a 640x360 canvas.
                    for (y in bitmap.height * 40 / 100 until bitmap.height * 57 / 100 step 4) {
                        for (x in bitmap.width * 28 / 100 until bitmap.width * 47 / 100 step 4) {
                            val pixel = bitmap.getPixel(x, y)
                            if (Color.red(pixel) > 190 && Color.green(pixel) < 65 && Color.blue(pixel) < 65) reds++
                            if (Color.green(pixel) > 190 && Color.red(pixel) < 65 && Color.blue(pixel) < 65) greens++
                        }
                    }
                    if ((if (red) reds > 150 else reds < 10) && (if (green) greens > 150 else greens < 10)) {
                        File(evidence, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        break
                    }
                } finally { bitmap.recycle() }
                delay(120)
            }
        }
        fun ready() = tv.media.player.state.value.let { it.firstFrameRendered && !it.subtitleLoading && !it.seeking }
        fun at(ms: Long) = abs(tv.media.sourcePosition() - ms) < 550L
        fun healthy() {
            val error = tv.media.state.value.let { it.error ?: it.subtitleError } ?: tv.media.player.state.value.error
            check(error == null) { "Owned PGS journey failed at $stage: $error" }
        }
        try {
            scenario.onActivity { tv = ViewModelProvider(it)[TvModel::class.java].also { model -> activeModel = model } }
            waitFor(35_000) { !tv.state.value.restoring && tv.state.value.connected }
            val owner = requireNotNull(tv.context)
            check(owner.account.username == "tv-fixture" && owner.profile.address.trimEnd('/') == url)
            originalPolicy = tv.media.playbackPreferences.videoDecodePolicy.value
            scenario.onActivity { tv.media.setVideoDecodePolicy(VideoDecodePolicy.SOFTWARE); tv.navigate(TvRoute.FILES); tv.browse("/Cinema", "/Cinema", false) }
            waitFor { tv.state.value.path == "/Cinema" && !tv.state.value.busy }
            val movie = tv.state.value.files.single { it.name == "Fileway owned BT709 subtitle styles.mkv" }
            val hdr = if (testHdr) tv.state.value.files.single { it.name == "Fileway owned HDR10 PGS.mkv" } else null
            stage = "open owned native video"
            scenario.onActivity { tv.open(movie, listOf(movie)) }
            waitFor(35_000) { healthy(); ready() && tv.media.canSeek && !tv.media.state.value.compatible }
            val mediaEpoch = tv.media.controlEpoch
            scenario.onActivity { tv.media.pause(); tv.media.seek(2_000); tv.media.subtitlesOff() }
            waitFor { healthy(); ready() && at(2_000) && !tv.media.player.wantsPlay }
            assertTrue(tv.media.state.value.sourcePGSStreaming)
            val source = tv.media.state.value.sourceText.filter { it.codec == "hdmv_pgs_subtitle" }
            assertEquals(6, source.size)
            val red = source.single { it.title == "NFB PGS1" }
            val green = source.single { it.title == "NFB PGS2" }
            val track = tv.media.player.state.value.subtitles.single { it.title.contains("NFB PGS1") }
            stage = "select original PGS track"
            scenario.onActivity { tv.media.selectPlayerSubtitle(track.id) }
            waitFor { healthy(); ready() && tv.media.player.state.value.selectedSubtitle == track.id && !tv.media.state.value.subtitleLoading }
            scenario.onActivity { assertNotNull(tv.media.player.nativeSubtitleIdentity(track.id)) }
            capture("native-pgs", red = true)

            val originalPosition = tv.media.sourcePosition()
            stage = "switch to SDR with original PGS"
            scenario.onActivity { tv.media.switchMode(TvHdrMode.SDR) }
            waitFor(95_000) { healthy(); tv.media.state.value.compatible && !tv.media.state.value.busy &&
                ready() && !tv.media.state.value.subtitleLoading && tv.media.state.value.selectedSourceSubtitle == red.index }
            assertTrue(at(originalPosition))
            assertFalse(tv.media.player.wantsPlay)
            scenario.onActivity {
                val imported = tv.media.player.importedSubtitleIdentity(tv.media.player.state.value.selectedSubtitle)
                assertTrue(imported?.sourceKey?.let { it.startsWith("source:") && it.endsWith(":${red.index}") } == true)
            }
            capture("sdr-pgs", red = true)

            stage = "seek in SDR at the original source time"
            scenario.onActivity { tv.media.seek(6_000) }
            waitFor(35_000) { healthy(); ready() && at(6_000) }
            capture("sdr-after-seek", red = true)
            val beforeReturn = tv.media.sourcePosition()
            stage = "return to original video with PGS"
            scenario.onActivity { tv.media.switchMode(TvHdrMode.NATIVE) }
            waitFor(45_000) { healthy(); !tv.media.state.value.compatible && !tv.media.state.value.busy &&
                ready() && !tv.media.state.value.subtitleLoading && tv.media.state.value.selectedSourceSubtitle == red.index }
            assertTrue(at(beforeReturn))
            assertFalse(tv.media.player.wantsPlay)
            assertEquals(mediaEpoch, tv.media.controlEpoch)
            capture("native-restored-pgs", red = true)

            stage = "switch PGS track without replacing the video"
            val generation = tv.media.player.state.value.mediaGeneration
            scenario.onActivity { tv.media.selectSourceSubtitle(green.index) }
            waitFor(35_000) { healthy(); ready() && !tv.media.state.value.subtitleLoading && tv.media.state.value.selectedSourceSubtitle == green.index }
            capture("native-other-pgs", green = true)
            assertEquals(generation, tv.media.player.state.value.mediaGeneration)
            assertTrue(at(beforeReturn))
            stage = "PGS clear after seek"
            scenario.onActivity { tv.media.seek(11_300L) }
            waitFor(35_000) { healthy(); ready() && at(11_300L) }
            capture("native-pgs-clear")
            assertEquals(generation, tv.media.player.state.value.mediaGeneration)
            assertFalse(tv.media.player.wantsPlay)
            if (hdr != null) {
                stage = "enable automatic playback mode"
                scenario.onActivity { tv.media.subtitlesOff(); tv.media.switchMode(TvHdrMode.AUTO, targetMs = 2_000) }
                waitFor(35_000) { healthy(); tv.media.state.value.mode == TvHdrMode.AUTO && !tv.media.state.value.busy && ready() }
                stage = "open authored HDR10 source in automatic SDR fallback"
                scenario.onActivity { tv.open(hdr, listOf(hdr)) }
                waitFor(95_000) {
                    val media = tv.media.state.value
                    media.file?.name == hdr.name && media.info?.optString("dynamicRange") == "pq" &&
                        media.compatible && !media.busy && ready()
                }
                healthy()
                val hdrPgs = tv.media.state.value.sourceText.single { it.codec == "hdmv_pgs_subtitle" && it.title == "NFB PGS1" }
                val hdrEpoch = tv.media.controlEpoch
                val hdrGeneration = tv.media.player.state.value.mediaGeneration
                scenario.onActivity { tv.media.pause(); tv.media.seek(2_000); tv.media.selectSourceSubtitle(hdrPgs.index) }
                stage = "render original PGS over the HDR-to-SDR video"
                waitFor(40_000) { healthy(); ready() && at(2_000) && !tv.media.state.value.subtitleLoading &&
                    tv.media.state.value.selectedSourceSubtitle == hdrPgs.index }
                capture("hdr-source-sdr-pgs", red = true)
                scenario.onActivity { tv.media.seek(6_000) }
                waitFor(35_000) { healthy(); ready() && at(6_000) }
                capture("hdr-source-sdr-after-seek", red = true)
                assertEquals(hdrEpoch, tv.media.controlEpoch)
                assertEquals(hdrGeneration, tv.media.player.state.value.mediaGeneration)
                assertFalse(tv.media.player.wantsPlay)
                hdrPgsVerified = true
            }
            report("PASS")
        } catch (failure: Throwable) {
            runCatching {
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { File(evidence, "failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
            report("FAIL")
            throw AssertionError("Owned TV PGS stage $stage", failure)
        } finally {
            scenario.onActivity { activeModel?.media?.let { media -> media.close(); originalPolicy?.let(media::setVideoDecodePolicy) } }
            scenario.close()
            prefs.edit().putString("hdr_mode", savedMode).apply()
        }
    }
}

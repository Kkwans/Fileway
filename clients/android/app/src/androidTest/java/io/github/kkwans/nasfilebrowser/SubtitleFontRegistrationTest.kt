package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ViewGroup
import androidx.media3.common.Format
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import io.github.kkwans.nasfilebrowser.player.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SubtitleFontRegistrationTest {
    @Test fun lateAttachedFontRefreshesActualGlyphWithoutLosingClockAndDuplicateUsesNoBudget(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val media = instrumentation.context.assets.open("media/subtitle-fixture.mkv").use { it.readBytes() }
        var font: ByteArray? = null
        val extractor = MediaSubtitleExtractor(object : MediaSubtitleExtractor.Sink {
            override fun font(name: String, bytes: ByteArray) { if (name == "NfbFixtureBars.ttf") font = bytes }
            override fun format(format: Format) = Unit
            override fun dialogue(trackId: String, startMs: Long, durationMs: Long, packet: ByteArray) = Unit
        })
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = DiscardingTrackOutput()
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        val data = ByteArrayDataSource(media)
        try {
            data.open(DataSpec.Builder().setUri("memory://owned-subtitle-font").build())
            val input = DefaultExtractorInput(data, 0, media.size.toLong())
            val position = PositionHolder()
            var reads = 0
            while (font == null) {
                check(++reads < 1000)
                val result = extractor.read(input, position)
                // A first-cluster cue seek is legitimate after the attachment
                // was delivered. No further samples are needed for this test.
                check(font != null || result == Extractor.RESULT_CONTINUE) { "Owned attachment missing before media samples" }
            }
        } finally { data.close(); extractor.release() }
        val bytes = checkNotNull(font)
        UiDevice.getInstance(instrumentation).executeShellCommand("am start --windowingMode 1 -n io.github.kkwans.nasfilebrowser/.MainActivity")
        val activity = withTimeout(15_000) {
            var owner: MainActivity? = null
            while (owner == null) {
                owner = withContext(Dispatchers.Main) {
                    (ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED) + ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STARTED))
                        .filterIsInstance<MainActivity>().firstOrNull()
                }
                if (owner == null) delay(50)
            }
            owner
        }
        var layer: MediaSubtitleLayer? = null
        suspend fun pixels(): IntArray = withContext(Dispatchers.Main) {
            val view = checkNotNull(layer); val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap); canvas.scale(320f / view.width, 180f / view.height); view.draw(canvas)
                IntArray(320 * 180).also { bitmap.getPixels(it, 0, 320, 0, 0, 320, 180) }
            } finally { bitmap.recycle() }
        }
        fun red(p: Int) = Color.alpha(p) > 100 && Color.red(p) > 180 && Color.green(p) < 60 && Color.blue(p) < 60
        fun blue(p: Int) = Color.alpha(p) > 100 && Color.blue(p) > 180 && Color.red(p) < 60 && Color.green(p) < 60
        fun runs(values: IntArray): Int {
            var count = 0; var active = false
            for (x in 0 until 140) {
                val current = (0 until 180).count { y -> red(values[y * 320 + x]) } > 3
                if (current && !active) count++
                active = current
            }
            return count
        }
        suspend fun frame(test: (IntArray) -> Boolean): IntArray = withTimeout(5000) {
            var result = pixels()
            while (!test(result)) { delay(30); result = pixels() }
            result
        }
        suspend fun register(name: String): Boolean {
            val result = CompletableDeferred<Boolean>()
            checkNotNull(layer).externalFont(name, bytes) { result.complete(it) }
            return withTimeout(2000) { result.await() }
        }
        val ass = """[Script Info]
ScriptType: v4.00+
PlayResX: 640
PlayResY: 360
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Bars,NfbFixtureBars,60,&H000000FF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.50,0:00:11.00,Bars,,0,0,0,,{\an7\pos(80,40)}I
Dialogue: 0,0:00:00.50,0:00:11.00,Bars,,0,0,0,,{\an7\pos(300,60)\1c&HFF0000&\p1}m 0 0 l 80 0 80 40 0 40{\p0}
""".toByteArray()
        try {
            val ready = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) {
                layer = MediaSubtitleLayer(activity, MediaResourcePolicy.LowMemoryTv.copy(assFontBytes = bytes.size * 2L - 1)).also {
                    (activity.window.decorView as ViewGroup).addView(it, ViewGroup.LayoutParams(384, 216))
                    it.externalAss("external:owned-font", ass, { ready.completeExceptionally(AssertionError("ASS import failed")) }) { ready.complete(Unit) }
                    it.select("external:owned-font", null, 2000)
                }
            }
            withTimeout(5000) { ready.await() }
            withTimeout(5000) { while (layer!!.width == 0 || layer!!.height == 0) delay(20) }
            withContext(Dispatchers.Main) { layer!!.requestFrame(2000) }
            // FIFO acknowledgement proves the initial renderer work ran before
            // supplying a real font; do not assume a vendor system fallback.
            assertFalse(register(""))
            val before = pixels()
            assertNotEquals("Fixture family unexpectedly exists before registration", 2, runs(before))
            assertTrue(register("NfbFixtureBars.ttf"))
            assertTrue(register("NfbFixtureBars.ttf"))
            assertFalse("A second unique registration exceeded its media budget", register("Other.ttf"))
            val rendered = frame { runs(it) == 2 && it.count(::blue) > 150 }
            val output = File(instrumentation.targetContext.getExternalFilesDir(null), "fileway-font-registration.png")
            Bitmap.createBitmap(rendered, 320, 180, Bitmap.Config.ARGB_8888).let { bitmap ->
                try { output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
            withContext(Dispatchers.Main) { layer!!.requestFrame(12_000) }
            frame { it.none { p -> red(p) || blue(p) } }
            withContext(Dispatchers.Main) { layer!!.requestFrame(2000) }
            frame { runs(it) == 2 }
            withContext(Dispatchers.Main) { layer!!.select(null, null, 2000) }
            frame { it.none { p -> red(p) || blue(p) } }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { layer?.let { it.close(); (it.parent as? ViewGroup)?.removeView(it) } }
        }
    }
}

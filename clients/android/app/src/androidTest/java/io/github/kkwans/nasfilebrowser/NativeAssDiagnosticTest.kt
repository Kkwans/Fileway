package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import io.github.kkwans.nasfilebrowser.player.*
import java.io.File
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Private reflection is diagnostic only, on an authored fixture and the actual
 * subtitle worker. No user text, media paths, profiles or accounts are captured. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class NativeAssDiagnosticTest {
    @Test fun nativeAssCoverageDetectsColdHoleAndPreservesCollectedEvents(): Unit = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation(); val context = ins.targetContext
        UiDevice.getInstance(ins).executeShellCommand("am start --windowingMode 1 -n io.github.kkwans.nasfilebrowser/.MainActivity")
        val activity = withTimeout(15_000) {
            var owner: MainActivity? = null
            while (owner == null) {
                owner = withContext(Dispatchers.Main) { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull() }
                if (owner == null) delay(50)
            }
            owner
        }
        val movie = File.createTempFile("owned-ass-player-", ".mkv", context.cacheDir)
        ins.context.assets.open("media/subtitle-fixture.mkv").use { source -> movie.outputStream().use { source.copyTo(it) } }
        var player: NativePlayer? = null; var viewport: PlayerViewport? = null
        fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(owner)
        suspend fun capture(): JSONObject {
            val native = checkNotNull(player)
            val state = withContext(Dispatchers.Main) {
                val engine = field(native, "engine") as ExoPlayer
                val selected = engine.currentTracks.groups.flatMap { group -> (0 until group.length).filter { group.isTrackSelected(it) }.map { group.getTrackFormat(it) } }
                JSONObject().put("positionMs", native.state.value.positionMs).put("selected", native.state.value.selectedSubtitle)
                    .put("covered", native.nativeSubtitleCovered(native.state.value.selectedSubtitle))
                    .put("formats", JSONArray(selected.map { JSONObject().put("id", it.id).put("mime", it.sampleMimeType).put("codecs", it.codecs) }))
            }
            val layer = checkNotNull(field(native, "layer")) as MediaSubtitleLayer
            val worker = field(layer, "worker") as ExecutorService
            val details = withContext(Dispatchers.IO) { worker.submit<JSONObject> {
                val tracks = field(layer, "tracks") as Map<*, *>
                JSONObject().put("assId", field(layer, "assId")).put("textId", field(layer, "textId"))
                    .put("clockMs", (field(layer, "timeMs") as java.util.concurrent.atomic.AtomicLong).get())
                    .put("eventBytes", field(layer, "eventBytes"))
                    .put("fontBytes", field(layer, "fontBytes")).put("tracks", JSONArray(tracks.map { (id, track) ->
                        val events = track!!.javaClass.getMethod("getEvents").invoke(track) as? Array<*>
                        JSONObject().put("id", id).put("events", JSONArray(events.orEmpty().map { event ->
                            JSONObject().also { json -> event!!.javaClass.declaredFields.filter { it.type.isPrimitive }.forEach { f -> f.isAccessible = true; json.put(f.name, f.get(event)) } }
                        }))
                    }))
            }.get(3, java.util.concurrent.TimeUnit.SECONDS) }
            return state.put("layer", details)
        }
        suspend fun counts(): Pair<Int, Int> = withContext(Dispatchers.Main) {
            val view = checkNotNull(field(checkNotNull(player), "layer")) as MediaSubtitleLayer
            val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap); canvas.scale(320f / view.width, 180f / view.height); view.draw(canvas)
                val pixels = IntArray(320 * 180).also { bitmap.getPixels(it, 0, 320, 0, 0, 320, 180) }
                pixels.count { Color.alpha(it) > 100 && Color.red(it) > 180 && Color.blue(it) < 60 && Color.green(it) < 60 } to
                    pixels.count { Color.alpha(it) > 100 && Color.blue(it) > 180 && Color.red(it) < 60 && Color.green(it) < 60 }
            } finally { bitmap.recycle() }
        }
        try {
            withContext(Dispatchers.Main) {
                viewport = PlayerViewport(activity).also { (activity.window.decorView as ViewGroup).addView(it, ViewGroup.LayoutParams(384, 216)) }
                player = NativePlayer(activity, MediaResourcePolicy.LowMemoryTv).also { it.attach(viewport!!); it.open(Uri.fromFile(movie).toString(), 2000, autoplay = false) }
            }
            val native = player!!
            withTimeout(10_000) { while (!native.state.value.firstFrameRendered || native.state.value.subtitles.none { it.title.contains("ASS Attachment") }) delay(30) }
            withContext(Dispatchers.Main) { native.subtitle(native.state.value.subtitles.single { it.title.contains("ASS Attachment") }.id); native.seek(2000) }
            withTimeout(5000) { while (native.state.value.pendingSubtitle != null || native.state.value.seeking) delay(30) }
            delay(500)
            val details = capture()
            val cold = counts()
            details.put("redPixels", cold.first).put("bluePixels", cold.second)
            // Keep the raw cold-hole observation. The engine must expose it to
            // the controller, whose separate cold-TV test must restore the real
            // source ASS. A warm glyph alone cannot prove that product path.
            withContext(Dispatchers.Main) { native.open(Uri.fromFile(movie).toString(), 0, autoplay = false) }
            withTimeout(10_000) { while (!native.state.value.firstFrameRendered || native.state.value.bufferedPositionMs < 1000) delay(30) }
            withContext(Dispatchers.Main) { native.subtitle(native.state.value.subtitles.single { it.title.contains("ASS Attachment") }.id); native.seek(2000) }
            withTimeout(5000) { while (native.state.value.pendingSubtitle != null || native.state.value.seeking) delay(30) }
            delay(500)
            val warm = counts()
            val comparison = capture().put("redPixels", warm.first).put("bluePixels", warm.second)
            val result = JSONObject().put("coldMidCueStart", details).put("fromBeginningThenSeek", comparison)
            File(context.getExternalFilesDir(null), "fileway-native-ass-diagnostic.json").writeText(result.toString(2))
            assertFalse("Cold native packet hole was reported as covered: $result", details.getBoolean("covered"))
            assertTrue("Continuously collected prefix was needlessly discarded: $result", comparison.getBoolean("covered"))
            assertTrue("Previously collected ASS must remain seekable", warm.first > 30 && warm.second > 150)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { player?.release(); viewport?.let { (it.parent as? ViewGroup)?.removeView(it) } }
            movie.delete()
        }
    }
}

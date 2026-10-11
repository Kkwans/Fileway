package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.player.MediaSubtitleLayer
import io.github.kkwans.nasfilebrowser.player.ExternalPgs
import io.github.kkwans.nasfilebrowser.player.NativePlayer
import io.github.kkwans.nasfilebrowser.player.PlaybackTraceAction
import io.github.kkwans.nasfilebrowser.player.PlayerViewport
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Real file parsing and worker capacity failure; no profiles, accounts or database fixtures. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ExternalSubtitleFailureTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun <T> main(action: () -> T): T = withContext(Dispatchers.Main) { action() }
    private fun oversizedSrt(): String = buildString {
        fun time(ms: Int) = String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        repeat(20_001) { index ->
            append(index + 1).append('\n').append(time(index * 1000)).append(" --> ").append(time(index * 1000 + 500))
                .append("\nOWNED CAPTION ").append(index).append("\n\n")
        }
    }
    private suspend fun await(native: NativePlayer, stage: String, predicate: suspend () -> Boolean) {
        try {
            withTimeout(20_000) {
                while (true) {
                    check(native.state.value.error == null) { "Owned native playback failed at $stage" }
                    if (predicate()) break
                    delay(50)
                }
            }
        } catch (error: TimeoutCancellationException) {
            val state = native.state.value
            throw AssertionError("External failure stage=$stage, position=${state.positionMs}, playing=${state.playing}, " +
                "subtitleLoading=${state.subtitleLoading}, selected=${state.selectedSubtitle}, operationError=${state.operationError}", error)
        }
    }
    private suspend fun whitePixels(viewport: PlayerViewport): Int = main {
        val view = viewport.text
        check(view.width > 0 && view.height > 0 && view.isAttachedToWindow)
        val bitmap = Bitmap.createBitmap(384, 216, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); canvas.scale(384f / view.width, 216f / view.height); view.draw(canvas)
            val pixels = IntArray(384 * 216); bitmap.getPixels(pixels, 0, 384, 0, 0, 384, 216)
            pixels.count { Color.alpha(it) > 150 && Color.red(it) > 180 && Color.green(it) > 180 && Color.blue(it) > 180 }
        } finally { bitmap.recycle() }
    }

    @Test fun largeSupNativeEntrySeeksAcrossTheWholeIndexReselectsWhilePausedAndDeletesOnlyItsWorkFile(): Unit = runBlocking {
        val files = mutableListOf<File>()
        fun owned(extension: String) = File.createTempFile("owned-large-subtitle-", extension, context.cacheDir).also { files.add(it) }
        fun workFiles() = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("external-sup-") && it.extension == "sup" }.toSet()
        var player: NativePlayer? = null
        var ownedViewport: PlayerViewport? = null
        try {
            val movie = owned(".mkv"); val input = owned(".sup")
            withContext(Dispatchers.IO) {
                InstrumentationRegistry.getInstrumentation().context.assets.open("media/fixture.mkv").use { source -> movie.outputStream().use { source.copyTo(it) } }
                ExternalPgsTest.writeLargeSup(input)
            }
            assertTrue("Owned SUP must exceed the old compressed whole-track budget", input.length() > 32L * 1024 * 1024)
            val before = workFiles()
            activity.scenario.onActivity { owner ->
                val viewport = PlayerViewport(owner).also { ownedViewport = it }
                (owner.window.decorView as ViewGroup).addView(viewport, ViewGroup.LayoutParams(384, 216))
                player = NativePlayer(owner).also { it.attach(viewport); it.open(Uri.fromFile(movie).toString(), 2500, autoplay = false) }
            }
            val native = requireNotNull(player); val viewport = requireNotNull(ownedViewport)
            await(native, "large SUP initial paused frame") { native.state.value.let { it.firstFrameRendered && it.seekable && !it.playing && abs(it.positionMs - 2500) <= 250 } }
            main { native.subtitle(-1) }
            await(native, "large SUP initial off") { native.state.value.selectedSubtitle == -1 && native.state.value.pendingSubtitle == null && whitePixels(viewport) == 0 }
            val initial = native.state.value
            main { assertTrue(native.addSubtitle(Uri.fromFile(input).toString(), "owned-large.sup")) }
            await(native, "large SUP native import and real pixels") { native.state.value.let { state ->
                !state.subtitleLoading && state.operationError == null && state.subtitles.any { it.id == state.selectedSubtitle && it.title == "owned-large.sup" }
            } && whitePixels(viewport) > 20 }
            val id = native.state.value.selectedSubtitle
            val layer = main { NativePlayer::class.java.getDeclaredField("layer").apply { isAccessible = true }.get(native) as MediaSubtitleLayer }
            val worker = MediaSubtitleLayer::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(layer) as ExecutorService
            suspend fun timeline(): ExternalPgs.Timeline = withContext(Dispatchers.IO) {
                worker.submit<ExternalPgs.Timeline> {
                    val map = MediaSubtitleLayer::class.java.getDeclaredField("indexedPgs").apply { isAccessible = true }.get(layer) as Map<*, *>
                    val entry = requireNotNull(map["external:$id"])
                    entry.javaClass.getDeclaredField("timeline").apply { isAccessible = true }.get(entry) as ExternalPgs.Timeline
                }.get(5, TimeUnit.SECONDS)
            }
            val index = timeline()
            assertTrue("The actual native track must retain events beyond the legacy count cap", index.eventCount > 20_000)
            assertTrue("Only bounded index metadata belongs to the retained cue budget", index.retainedBytes <= 32L * 1024 * 1024)
            val ownedSpools = workFiles() - before
            assertEquals("One import must create one owned working file", 1, ownedSpools.size)
            main { native.subtitleDelay(1000) }
            await(native, "paused delay before first caption") { whitePixels(viewport) == 0 }
            main { native.subtitleDelay(0) }
            await(native, "paused delay restored") { whitePixels(viewport) > 20 }
            main { native.seek(10_500) }
            await(native, "real far index seek") { native.state.value.let { abs(it.positionMs - 10_500) <= 250 && it.phase != "正在跳转" } && whitePixels(viewport) > 20 }
            assertTrue(index.floorIndex(10_500_000) > 20_000)
            main { native.subtitleDelay(1000) }
            await(native, "far index delay reaches authored clear") { whitePixels(viewport) == 0 }
            main { native.subtitleDelay(0) }
            await(native, "far index delay restores pixels") { whitePixels(viewport) > 20 }
            main { native.seek(11_500) }
            await(native, "far authored clear") { native.state.value.let { abs(it.positionMs - 11_500) <= 250 && it.phase != "正在跳转" } && whitePixels(viewport) == 0 }
            main { native.seek(2500) }
            await(native, "return to first indexed caption") { native.state.value.let { abs(it.positionMs - 2500) <= 250 && it.phase != "正在跳转" } && whitePixels(viewport) > 20 }
            main { native.subtitle(-1) }
            await(native, "accepted SUP off") { native.state.value.selectedSubtitle == -1 && whitePixels(viewport) == 0 }
            assertTrue(ownedSpools.all { it.isFile })
            assertSame("Off must retain the same local index", index, timeline())
            main { native.subtitle(id) }
            await(native, "accepted SUP reselection") { native.state.value.selectedSubtitle == id && whitePixels(viewport) > 20 }
            assertSame(index, timeline())
            assertEquals(ownedSpools, workFiles() - before)
            assertFalse(native.state.value.playing)
            assertEquals(initial.mediaGeneration, native.state.value.mediaGeneration)
            assertEquals(initial.selectedAudio, native.state.value.selectedAudio)
            assertEquals(1, native.diagnosticSnapshot().count { it.action == PlaybackTraceAction.MEDIA_SET })
            main { native.release() }
            assertTrue("Media close must finish its owned index cleanup", withContext(Dispatchers.IO) { worker.awaitTermination(5, TimeUnit.SECONDS) })
            assertTrue("Media close must delete the exact newly owned work file", ownedSpools.none { it.exists() })
            assertTrue("The original user-selected input is never the owned spool", input.isFile)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                try { player?.release() } finally { ownedViewport?.let { (it.parent as? ViewGroup)?.removeView(it) } }
            }
            withContext(NonCancellable + Dispatchers.IO) { files.forEach { it.delete() } }
        }
    }

    @Test fun timelineOwnershipIsReclaimedForClosedLayerAndCancellationBeforeQueueOrReady(): Unit = runBlocking {
        for (stage in listOf("closed", "before-queue", "before-ready")) {
            val file = File.createTempFile("owned-subtitle-handoff-", ".sup", context.cacheDir)
            var layer: MediaSubtitleLayer? = null
            var unownedTimeline: ExternalPgs.Timeline? = null
            val unblock = CountDownLatch(1)
            try {
                withContext(Dispatchers.IO) { file.writeBytes(ExternalPgsTest.ownedSup()) }
                val timeline = withContext(Dispatchers.IO) { ExternalPgs.index(file, deleteOnClose = true).also { unownedTimeline = it } }
                val cancelled = AtomicBoolean()
                val ready = AtomicInteger(); val failed = AtomicInteger()
                activity.scenario.onActivity { owner ->
                    layer = MediaSubtitleLayer(owner).also { (owner.window.decorView as ViewGroup).addView(it, ViewGroup.LayoutParams(384, 216)) }
                }
                val current = requireNotNull(layer)
                val worker = MediaSubtitleLayer::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(current) as ExecutorService
                if (stage == "closed") main {
                    current.close()
                    current.externalPgsTimeline("external:owned", timeline, cancelled::get, { failed.incrementAndGet() }) { ready.incrementAndGet() }
                    unownedTimeline = null
                } else if (stage == "before-queue") {
                    val entered = CountDownLatch(1)
                    worker.execute { entered.countDown(); unblock.await() }
                    assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                    main {
                        current.externalPgsTimeline("external:owned", timeline, cancelled::get, { failed.incrementAndGet() }) { ready.incrementAndGet() }
                        unownedTimeline = null
                    }
                    cancelled.set(true); unblock.countDown()
                } else main {
                    // Keep the UI callback queued until attach has completed and
                    // the cancellation token is set. No timing sleep or retry.
                    current.externalPgsTimeline("external:owned", timeline, cancelled::get, { failed.incrementAndGet() }) { ready.incrementAndGet() }
                    unownedTimeline = null
                    worker.submit { cancelled.set(true) }.get(5, TimeUnit.SECONDS)
                }
                if (stage == "closed") assertTrue(withContext(Dispatchers.IO) { worker.awaitTermination(5, TimeUnit.SECONDS) })
                else {
                    main { /* Delivers the posted ready/cancel guard. */ }
                    withContext(Dispatchers.IO) { worker.submit(Runnable { }).get(5, TimeUnit.SECONDS) }
                }
                assertFalse("The layer must release the owned timeline at $stage", file.exists())
                assertEquals(stage, 0, ready.get()); assertEquals(stage, 0, failed.get())
                assertTrue(runCatching { timeline.floorIndex(2_500_000) }.exceptionOrNull() is CancellationException)
            } finally {
                unblock.countDown()
                withContext(NonCancellable + Dispatchers.Main) { layer?.let { it.close(); (it.parent as? ViewGroup)?.removeView(it) } }
                withContext(NonCancellable + Dispatchers.IO) { try { unownedTimeline?.close() } finally { file.delete() } }
            }
        }
    }

    @Test fun lateWorkerFailureAfterSubtitleOffIsIgnoredButCurrentFailureReportsAndValidFileRecovers(): Unit = runBlocking {
        val files = mutableListOf<File>()
        fun owned(extension: String) = File.createTempFile("owned-external-failure-", extension, context.cacheDir).also { files.add(it) }
        val unblock = CountDownLatch(1)
        var player: NativePlayer? = null
        var ownedViewport: PlayerViewport? = null
        try {
            val movie = owned(".mkv"); val large = owned(".srt"); val valid = owned(".srt")
            val captions = withContext(Dispatchers.Default) { oversizedSrt().toByteArray(Charsets.UTF_8) }
            assertTrue("Read must pass the 16 MiB source limit and fail the actual worker cue budget", captions.size in 1..16 * 1024 * 1024)
            withContext(Dispatchers.IO) {
                InstrumentationRegistry.getInstrumentation().context.assets.open("media/fixture.mkv").use { input -> movie.outputStream().use { input.copyTo(it) } }
                large.writeBytes(captions)
                valid.writeText("1\n00:00:00,000 --> 00:00:12,000\nOWNED VALID CAPTION\n\n")
            }
            activity.scenario.onActivity { owner ->
                val viewport = PlayerViewport(owner).also { ownedViewport = it }
                (owner.window.decorView as ViewGroup).addView(viewport, ViewGroup.LayoutParams(384, 216))
                player = NativePlayer(owner).also { it.attach(viewport); it.open(Uri.fromFile(movie).toString(), 2500, autoplay = false) }
            }
            val native = requireNotNull(player); val viewport = requireNotNull(ownedViewport)
            await(native, "attached first frame") { native.state.value.let { it.firstFrameRendered && it.seekable && !it.playing && abs(it.positionMs - 2500) <= 250 } }
            main { native.pause(); native.subtitle(-1) }
            await(native, "initial subtitles off") { native.state.value.selectedSubtitle == -1 && native.state.value.pendingSubtitle == null }
            assertEquals(0, whitePixels(viewport))
            val initial = native.state.value
            val layer = main { NativePlayer::class.java.getDeclaredField("layer").apply { isAccessible = true }.get(native) as MediaSubtitleLayer }
            val worker = MediaSubtitleLayer::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(layer) as ExecutorService
            val jobField = NativePlayer::class.java.getDeclaredField("externalJob").apply { isAccessible = true }
            suspend fun load(file: File, name: String): Job = main {
                assertTrue(native.addSubtitle(Uri.fromFile(file).toString(), name))
                requireNotNull(jobField.get(native) as? Job)
            }
            suspend fun drainWorkerAndMain() {
                withContext(Dispatchers.IO) { worker.submit(Runnable { }).get(5, TimeUnit.SECONDS) }
                main { /* All failure/ready posts preceding the worker barrier have been delivered. */ }
            }
            val entered = CountDownLatch(1)
            worker.execute { entered.countDown(); unblock.await() }
            assertTrue("Owned worker blocker must have started", withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val old = load(large, "owned-too-many-cues.srt")
            withTimeout(20_000) { old.join() }
            assertTrue("Parser completed and queued the still-blocked capacity check", old.isCompleted && native.state.value.subtitleLoading)
            assertNull(native.state.value.operationError)
            main { native.subtitle(-1) }
            unblock.countDown()
            drainWorkerAndMain()
            val afterLateFailure = native.state.value
            assertNull("A real late worker exception must not report against the explicit off choice", afterLateFailure.operationError)
            assertFalse(afterLateFailure.subtitleLoading); assertEquals(-1, afterLateFailure.selectedSubtitle)
            assertFalse(afterLateFailure.playing); assertEquals(initial.mediaGeneration, afterLateFailure.mediaGeneration)
            assertTrue(abs(afterLateFailure.positionMs - initial.positionMs) <= 250)
            assertEquals(0, whitePixels(viewport))

            val current = load(large, "owned-current-too-many-cues.srt")
            withTimeout(20_000) { current.join() }
            drainWorkerAndMain()
            await(native, "current worker capacity failure") { !native.state.value.subtitleLoading && native.state.value.operationError != null }
            assertEquals(-1, native.state.value.selectedSubtitle)
            assertFalse(native.state.value.playing)

            val good = load(valid, "owned-valid.srt")
            withTimeout(20_000) { good.join() }
            await(native, "valid external file after capacity failure") { native.state.value.let { state ->
                !state.subtitleLoading && state.operationError == null && state.subtitles.any { it.id == state.selectedSubtitle && it.title == "owned-valid.srt" }
            } && whitePixels(viewport) > 20 }
            assertFalse(native.state.value.playing); assertEquals(initial.mediaGeneration, native.state.value.mediaGeneration)
            assertEquals(initial.selectedAudio, native.state.value.selectedAudio)
            assertEquals(1, native.diagnosticSnapshot().count { it.action == PlaybackTraceAction.MEDIA_SET })
        } finally {
            unblock.countDown()
            withContext(NonCancellable + Dispatchers.Main) {
                try { player?.release() } finally { ownedViewport?.let { (it.parent as? ViewGroup)?.removeView(it) } }
            }
            withContext(NonCancellable + Dispatchers.IO) { files.forEach { it.delete() } }
        }
    }
}

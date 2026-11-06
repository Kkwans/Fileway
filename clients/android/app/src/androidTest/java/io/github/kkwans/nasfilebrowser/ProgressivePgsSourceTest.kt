package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.ViewGroup
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.player.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ProgressivePgsSourceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun directory() = File(context.cacheDir, "owned-pgs-stream-${System.nanoTime()}").apply { check(mkdirs()) }

    @Test fun incrementalBoundariesPreserveCropClearAndCommittedCoverage() = runBlocking {
        val root = directory(); val file = File(root, "owned.sup")
        val bytes = ExternalPgsTest.ownedSup()
        try {
            FileOutputStream(file).use { output ->
                ExternalPgs.IndexBuilder(file, 16L * 1024 * 1024).use { builder ->
                    var at = 0
                    while (at < bytes.size - 13) {
                        val count = minOf(7, bytes.size - 13 - at)
                        output.write(bytes, at, count); at += count
                        builder.advance(at.toLong())
                    }
                    assertEquals(2, builder.eventCount)
                    val prefix = builder.snapshot()
                    try {
                        assertFalse(prefix.complete)
                        assertTrue(prefix.covers(2_500_000)); assertFalse(prefix.covers(3_500_000))
                        val picture = prefix.decode(prefix.floorIndex(2_500_000)).cues.single().bitmap!!
                        assertEquals(64, picture.width); picture.recycle()
                        assertEquals(-1, prefix.floorIndex(3_500_000))
                        output.write(bytes, at, bytes.size - at)
                        builder.advance(bytes.size.toLong(), finished = true)
                        // A previous snapshot remains readable after the file grows.
                        val old = prefix.decode(prefix.floorIndex(2_500_000)).cues.single().bitmap!!; old.recycle()
                        builder.snapshot().use { full ->
                            assertTrue(full.complete); assertEquals(3, full.eventCount)
                            val crop = full.decode(full.floorIndex(3_500_000)).cues.single().bitmap!!
                            assertEquals(32, crop.width); assertEquals(6, crop.height); crop.recycle()
                            assertTrue(full.decode(full.floorIndex(4_500_000)).cues.isEmpty())
                        }
                    } finally { prefix.close() }
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun protocolPublishesBeforeCompletionAndReleasesSpoolAfterLastSnapshot() = runBlocking {
        val root = directory(); val server = StreamServer("normal")
        val latest = AtomicReference<ExternalPgs.Timeline?>(); val early = CountDownLatch(1)
        try {
            val job = async(Dispatchers.Default) {
                ProgressivePgsSource(root, 16L * 1024 * 1024) { 2_500_000 }.load(server.url, "owned-source") { timeline ->
                    latest.getAndSet(timeline)?.close()
                    if (!timeline.complete) early.countDown()
                }
            }
            assertTrue("No prefix until full extraction", early.await(5, TimeUnit.SECONDS))
            assertFalse(job.isCompleted)
            val snapshot = latest.get()!!
            val bitmap = snapshot.decode(snapshot.floorIndex(2_500_000)).cues.single().bitmap!!; bitmap.recycle()
            server.finish.countDown()
            val receipt = withTimeout(5000) { job.await() }
            assertEquals(ExternalPgsTest.ownedSup().size.toLong(), receipt.bytes)
            assertTrue(latest.get()!!.complete)
            assertEquals(1, root.listFiles()!!.size)
            latest.getAndSet(null)?.close()
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { latest.getAndSet(null)?.close(); server.close(); root.deleteRecursively() }
    }

    @Test fun badDigestDoesNotProduceACompleteTimeline() = runBlocking {
        val root = directory(); val server = StreamServer("bad-digest")
        val latest = AtomicReference<ExternalPgs.Timeline?>(); val early = CountDownLatch(1)
        try {
            val result = async(Dispatchers.Default) {
                runCatching { ProgressivePgsSource(root, 16L * 1024 * 1024) { 2_500_000 }.load(server.url, "owned-source") {
                    assertFalse(it.complete); latest.getAndSet(it)?.close(); early.countDown()
                } }
            }
            assertTrue(early.await(5, TimeUnit.SECONDS)); server.finish.countDown()
            assertTrue(withTimeout(5000) { result.await() }.isFailure)
            latest.getAndSet(null)?.close()
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { latest.getAndSet(null)?.close(); server.close(); root.deleteRecursively() }
    }

    @Test fun cancellationInterruptsStalledBodyWithoutWaitingForReadTimeout() = runBlocking {
        val root = directory(); val server = StreamServer("stall")
        try {
            val job = launch(Dispatchers.Default) { ProgressivePgsSource(root, 16L * 1024 * 1024) { 0 }.load(server.url, "owned-source") { it.close() } }
            assertTrue(server.started.await(5, TimeUnit.SECONDS))
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { server.close(); root.deleteRecursively() }
    }

    @Test fun nativePrefixesRenderOnSourceTimeAndOffCancelsUnverifiedImport() = runBlocking {
        val steps = File(context.getExternalFilesDir(null), "fileway-pgs-source-evidence").apply { mkdirs() }
        fun stage(name: String) { File(steps, "native-stage.json").writeText(JSONObject().put("stage", name).toString()) }
        stage("copy owned video")
        val root = directory(); val movie = File(root, "owned.mkv")
        InstrumentationRegistry.getInstrumentation().context.assets.open("media/fixture.mkv").use { input -> movie.outputStream().use { input.copyTo(it) } }
        stage("launch activity")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand("am start --windowingMode 1 -n io.github.kkwans.nasfilebrowser/.MainActivity")
        val activity = withTimeout(15000) {
            var found: MainActivity? = null
            while (found == null) {
                found = withContext(Dispatchers.Main) {
                    (ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED) +
                        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STARTED)).filterIsInstance<MainActivity>().firstOrNull()
                }
                if (found == null) delay(100)
            }
            found
        }
        stage("activity launched")
        var player: NativePlayer? = null; var view: PlayerViewport? = null
        val first = StreamServer("normal"); var second: StreamServer? = null
        val before = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("external-pgs-stream-") }.toSet()
        suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
        suspend fun waitFor(predicate: () -> Boolean) = withTimeout(10000) { while (!predicate()) delay(30) }
        suspend fun pixels(): Int = withContext(Dispatchers.Main) {
            val viewport = view!!
            val bitmap = Bitmap.createBitmap(384, 216, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap); canvas.scale(384f / viewport.text.width, 216f / viewport.text.height); viewport.text.draw(canvas)
                val values = IntArray(384 * 216); bitmap.getPixels(values, 0, 384, 0, 0, 384, 216)
                values.count { it ushr 24 > 150 && ((it shr 16) and 255) > 180 && ((it shr 8) and 255) > 180 && (it and 255) > 180 }
            } finally { bitmap.recycle() }
        }
        suspend fun visible(wanted: Boolean) = withTimeout(5000) { while ((pixels() > 20) != wanted) delay(30) }
        try {
            stage("create native player")
            withContext(Dispatchers.Main) {
                val owner = activity
                val viewport = PlayerViewport(owner); view = viewport
                (owner.window.decorView as ViewGroup).addView(viewport, ViewGroup.LayoutParams(384, 216))
                player = NativePlayer(owner, MediaResourcePolicy.LowMemoryTv).also {
                    it.attach(viewport); it.subtitleSourceOffset(1000)
                    it.open(Uri.fromFile(movie).toString(), 1500, autoplay = false)
                }
            }
            val native = player!!
            stage("wait paused video frame")
            waitFor { native.state.value.let { it.firstFrameRendered && it.seekable && abs(it.positionMs - 1500) < 250 } }
            main { native.subtitle(-1) }; visible(false)
            val epoch = native.state.value.mediaGeneration
            val ready = CountDownLatch(1)
            stage("start partial import")
            val pending = async(Dispatchers.Main) { native.importPgsStream(first.url, "owned-progressive.sup", "owned-streamed-pgs", "owned-source") { ready.countDown() } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); assertFalse(pending.isCompleted)
            stage("wait partial pixels")
            visible(true)
            val partialId = native.state.value.selectedSubtitle
            assertNull("Unverified prefix entered trusted identity map", native.importedSubtitleIdentity(partialId))
            assertFalse(native.state.value.subtitleLoading)
            // Local 2.5s + source offset 1s lies beyond the partial watermark 3s.
            main { native.seek(2500) }
            stage("seek beyond partial coverage")
            waitFor { abs(native.state.value.positionMs - 2500) < 250 && native.state.value.subtitleLoading }
            visible(false)
            main { native.seek(1500) }
            stage("seek back into known coverage")
            waitFor { abs(native.state.value.positionMs - 1500) < 250 && !native.state.value.subtitleLoading }
            visible(true)
            main { native.subtitle(-1) }
            stage("off cancels partial import")
            assertFalse(withTimeout(2000) { pending.await() }); visible(false)
            assertNull(native.importedSubtitleIdentity(partialId))
            withTimeout(2000) { while ((context.cacheDir.listFiles().orEmpty().toSet() - before).any { it.name.startsWith("external-pgs-stream-") }) delay(20) }
            second = StreamServer("normal").also { it.finish.countDown() }
            stage("complete import")
            assertTrue(withTimeout(10000) { withContext(Dispatchers.Main) { native.importPgsStream(second!!.url, "owned-complete.sup", "owned-streamed-pgs", "owned-source") {} } })
            val completeId = native.state.value.selectedSubtitle
            assertNotNull(native.importedSubtitleIdentity(completeId))
            main { native.subtitle(-1); assertTrue(native.selectImportedSubtitle("owned-streamed-pgs")) }
            visible(true)
            assertEquals(epoch, native.state.value.mediaGeneration)
            assertFalse(native.state.value.playing)
            assertEquals(1, native.diagnosticSnapshot().count { it.action == PlaybackTraceAction.MEDIA_SET })
            stage("PASS")
        } finally {
            stage("release native player")
            withContext(NonCancellable + Dispatchers.Main) { player?.release(); view?.let { (it.parent as? ViewGroup)?.removeView(it) } }
            stage("close owned fixture")
            first.close(); second?.close(); root.deleteRecursively()
            stage("closed")
        }
    }

    /** Generic opt-in for a user-provided SUP kept outside Git and the APK. */
    @Test fun privateSourceIndexesAndDecodesWithinTvBudget() = runBlocking {
        val base = context.getExternalFilesDir(null)!!
        val config = File(base, "fileway-pgs-source-diagnostic.json")
        assumeTrue("Private source opt-in required", config.isFile)
        val input = JSONObject(config.readText()); val file = File(base, input.getString("file"))
        require(file.parentFile?.canonicalFile == base.canonicalFile && file.isFile)
        val expected = input.getString("sha256")
        val digest = withContext(Dispatchers.IO) { file.inputStream().use { source ->
            val hash = MessageDigest.getInstance("SHA-256"); val data = ByteArray(65536)
            while (true) { val n = source.read(data); if (n < 0) break; hash.update(data, 0, n) }
            hash.digest().joinToString("") { "%02x".format(it) }
        } }
        assertEquals(expected, digest)
        withContext(Dispatchers.IO) {
            ExternalPgs.IndexBuilder(file, 16L * 1024 * 1024).use { builder ->
                builder.advance(file.length(), finished = true)
                builder.snapshot().use { timeline ->
                    val frame = timeline.decode(timeline.floorIndex(input.getLong("atMs") * 1000))
                    assertTrue(frame.cues.isNotEmpty())
                    val bitmap = frame.cues.first().bitmap!!
                    assertTrue(bitmap.width > 0 && bitmap.height > 0)
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val visible = pixels.count { it ushr 24 > 32 }
                    assertTrue("Decoded PGS bitmap is empty", visible > 100)
                    val evidence = File(base, "fileway-pgs-source-evidence").apply { mkdirs() }
                    File(evidence, "source-pgs.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    File(evidence, "result.json").writeText(JSONObject().put("status", "PASS").put("scope", "Actual SUP index and Media3 bitmap decode; not composed TV/HDR acceptance")
                        .put("events", timeline.eventCount).put("retainedBytes", timeline.retainedBytes).put("atMs", input.getLong("atMs")).put("visiblePixels", visible).toString(2))
                    frame.cues.forEach { it.bitmap?.recycle() }
                }
            }
        }
    }

    private class StreamServer(private val mode: String) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}/owned"
        val started = CountDownLatch(1); val finish = CountDownLatch(1)
        @Volatile private var socket: Socket? = null
        private val thread = Thread({
            try {
                listener.accept().use { peer ->
                    socket = peer
                    val request = peer.getInputStream().bufferedReader(Charsets.US_ASCII)
                    while (true) { if (request.readLine().isNullOrEmpty()) break }
                    val out = peer.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/x-fileway-media-asset+ndjson; version=1\r\nConnection: close\r\n\r\n".toByteArray())
                    fun send(frame: JSONObject) { out.write((frame.toString() + "\n").toByteArray()); out.flush() }
                    send(JSONObject().put("type", "start").put("identity", "owned-source").put("format", "sup"))
                    started.countDown()
                    val bytes = ExternalPgsTest.ownedSup(); val prefix = bytes.size - 13
                    if (mode != "stall") {
                        for (at in 0 until prefix step 7) send(JSONObject().put("type", "data").put("data", Base64.getEncoder().encodeToString(bytes.copyOfRange(at, minOf(at + 7, prefix)))))
                    }
                    finish.await(30, TimeUnit.SECONDS)
                    if (mode != "stall") {
                        send(JSONObject().put("type", "data").put("data", Base64.getEncoder().encodeToString(bytes.copyOfRange(prefix, bytes.size))))
                        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                        send(JSONObject().put("type", "complete").put("bytes", bytes.size).put("sha256", if (mode == "bad-digest") "0".repeat(64) else digest))
                    }
                }
            } catch (_: Exception) { }
        }, "Owned-PGS-stream").apply { isDaemon = true; start() }
        override fun close() { finish.countDown(); runCatching { socket?.close() }; listener.close(); thread.join(1000) }
    }
}

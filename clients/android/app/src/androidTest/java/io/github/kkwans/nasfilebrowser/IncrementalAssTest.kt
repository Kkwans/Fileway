package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.AssTexType
import io.github.peerless2012.ass.AssFrame
import io.github.peerless2012.ass.AssTrack
import io.github.kkwans.nasfilebrowser.player.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class IncrementalAssTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun directory() = File(context.cacheDir, "owned-ass-stream-${System.nanoTime()}").apply { check(mkdirs()) }

    @Test fun linesUtf8EmbeddedFontsAndOutOfOrderKeepOriginalBytes(): Unit {
        val header = "[Script Info]\r\nScriptType: v4.00+\r\n[Fonts]\r\nfontname: owned.ttf\r\nABCDEF\r\n"
        val events = "[Events]\r\nFormat: Layer, Start, End, Text\r\n" +
            "Dialogue: 0,0:00:00.50,0:00:09.00,中文\r\n" +
            "Dialogue: 0,0:00:05.00,0:00:06.00,later\r\n"
        val late = "Dialogue: 0,0:00:01.00,0:00:02.00,out of order"
        val parser = IncrementalAss(); val output = ByteArrayOutputStream()
        var fontUpdates = 0
        fun feed(bytes: ByteArray): AssStreamUpdate {
            val update = parser.append(bytes); output.write(update.data)
            if (update.fontsChanged) fontUpdates++
            return update
        }
        val first = header.toByteArray()
        first.forEach { feed(byteArrayOf(it)) }
        assertFalse(output.toString("UTF-8").contains("[Fonts]"))
        val eventBytes = events.toByteArray(Charsets.UTF_8)
        var latest: AssStreamUpdate? = null
        for (at in eventBytes.indices step 3) latest = feed(eventBytes.copyOfRange(at, minOf(at + 3, eventBytes.size)))
        assertEquals(1, fontUpdates)
        assertEquals(5_000L, latest!!.coveredUntilMs)
        assertTrue(output.toString("UTF-8").contains("中文"))
        val outOfOrder = feed((late + "\n").toByteArray())
        assertEquals(-1L, outOfOrder.coveredUntilMs)
        val final = parser.finish(); output.write(final.data)
        assertTrue(final.complete); assertEquals(Long.MAX_VALUE, final.coveredUntilMs)
        assertEquals(header + events + late + "\n", output.toString("UTF-8"))
    }

    @Test fun invalidUtf8AndTruncatedHeaderCannotBecomeComplete(): Unit {
        val parser = IncrementalAss(128)
        assertThrows(Exception::class.java) { parser.append(byteArrayOf(0xc3.toByte(), 10)) }
        val truncated = IncrementalAss(128)
        truncated.append("[Script Info]\nScriptType: v4.00+\n".toByteArray())
        assertThrows(IllegalStateException::class.java) { truncated.finish() }
    }

    @Test fun sharedGrowingAssetPinsReadersAndRejectsBadDigest(): Unit = runBlocking {
        val root = directory(); val store = SourceSubtitleAssets(1024 * 1024); val server = AssServer(false)
        val calls = AtomicInteger()
        try {
            val one = store.acquireGrowing("owned", EXTERNAL_TEXT_BYTES_LIMIT.coerceAtMost(1024 * 1024)) { writer ->
                calls.incrementAndGet(); ProgressiveAssSource.download(root, server.url, "owned-source", writer)
            }
            assertTrue(server.firstSent.await(5, TimeUnit.SECONDS))
            withTimeout(5000) { one.progress.first { it.bytes > 0 } }
            val two = store.acquireGrowing("owned", 1024 * 1024) { error("Joined reader started a second producer") }
            assertEquals(one.file, two.file); assertEquals(1, calls.get()); one.close()
            assertFalse(two.progress.value.complete); assertTrue(two.file.isFile)
            server.middle.countDown(); server.finish.countDown()
            withTimeout(5000) { two.progress.first { it.complete } }
            assertArrayEquals(server.bytes, two.file.readBytes())
            store.close(); assertTrue(two.file.isFile)
            two.close(); assertTrue(root.listFiles()!!.isEmpty())
        } finally { server.close(); store.close(); root.deleteRecursively() }

        val failedRoot = directory(); val failedStore = SourceSubtitleAssets(1024 * 1024); val bad = AssServer(true)
        try {
            val lease = failedStore.acquireGrowing("bad", 1024 * 1024) { writer ->
                ProgressiveAssSource.download(failedRoot, bad.url, "owned-source", writer)
            }
            assertTrue(bad.firstSent.await(5, TimeUnit.SECONDS)); bad.middle.countDown(); bad.finish.countDown()
            val state = withTimeout(5000) { lease.progress.first { it.failure != null } }
            assertFalse(state.complete); assertNotNull(state.failure)
            lease.close(); failedStore.close()
            withTimeout(2000) { while (failedRoot.listFiles()!!.isNotEmpty()) delay(10) }
        } finally { bad.close(); failedStore.close(); failedRoot.deleteRecursively() }
    }

    @Test fun actualPlayerShowsProvisionalPixelsClearsOnSeekAndDisorderThenCommits(): Unit = runBlocking {
        val root = directory(); val store = SourceSubtitleAssets(); val server = AssServer(false)
        val movie = File(root, "owned.mkv")
        instrumentation.context.assets.open("media/fixture.mkv").use { input -> movie.outputStream().use { input.copyTo(it) } }
        UiDevice.getInstance(instrumentation).executeShellCommand("am start --windowingMode 1 -n io.github.kkwans.nasfilebrowser/.MainActivity")
        val activity = withTimeout(15_000) {
            var found: MainActivity? = null
            while (found == null) {
                found = withContext(Dispatchers.Main) { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull() }
                if (found == null) delay(50)
            }
            found
        }
        var player: NativePlayer? = null; var viewport: PlayerViewport? = null
        val leases = mutableListOf<SourceSubtitleAssets.Lease>(); val servers = mutableListOf(server)
        var pgsServer: ProgressivePgsSourceTest.StreamServer? = null
        var stage = "create player"
        suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
        suspend fun waitFor(block: () -> Boolean) = withTimeout(10_000) { while (!block()) delay(30) }
        fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(owner)
        fun layer() = field(checkNotNull(player), "layer") as MediaSubtitleLayer
        suspend fun layerDetails(): String = withContext(Dispatchers.IO) {
            val view = withContext(Dispatchers.Main) { layer() }
            val worker = field(view, "worker") as java.util.concurrent.ExecutorService
            worker.submit<String> {
                val id = field(view, "assId") as String?
                val tracks = field(view, "tracks") as Map<*, *>
                val streams = field(view, "streamedAss") as Map<*, *>
                val selected = tracks[id]; val previous = field(view, "lastTrack")
                val at = (field(view, "timeMs") as java.util.concurrent.atomic.AtomicLong).get() - view.delayMs
                val rendered = if (selected != null && selected === previous)
                    (field(view, "renderer") as? AssRender)?.renderFrame(at, AssTexType.BITMAP_RGBA) else null
                val frame = field(view, "frame") as? AssFrame
                val info = JSONObject().put("assId", id).put("timeMs", at).put("lastNative", field(view, "lastNative") != null)
                    .put("frame", frame != null).put("viewWidth", view.width).put("viewHeight", view.height)
                    .put("frameChanged", frame?.changed).put("selectedIsLast", selected != null && selected === previous)
                    .put("nextChanged", rendered?.changed).put("nextImages", rendered?.images?.size)
                info.put("images", org.json.JSONArray(frame?.images.orEmpty().map { image ->
                    val bitmap = image.bitmap
                    val values = if (bitmap != null && !bitmap.isRecycled) IntArray(bitmap.width * bitmap.height).also {
                        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    } else intArrayOf()
                    JSONObject().put("x", image.x).put("y", image.y).put("width", image.w).put("height", image.h)
                        .put("color", image.color).put("bitmap", bitmap != null).put("opaque", values.count { Color.alpha(it) > 100 })
                        .put("red", values.count { Color.alpha(it) > 100 && Color.red(it) > 180 && Color.blue(it) < 60 && Color.green(it) < 60 })
                        .put("blue", values.count { Color.alpha(it) > 100 && Color.blue(it) > 180 && Color.red(it) < 60 && Color.green(it) < 60 })
                }))
                info.put("tracks", org.json.JSONArray(tracks.map { (key, value) ->
                    val events = (value as? AssTrack)?.getEvents().orEmpty()
                    JSONObject().put("id", key).put("events", org.json.JSONArray(events.map {
                        JSONObject().put("start", it.start).put("duration", it.duration).put("text", it.text.take(120))
                    }))
                }))
                info.put("streams", org.json.JSONArray(streams.map { (key, value) ->
                    JSONObject().put("id", key).put("complete", field(value!!, "complete")).put("covered", field(value, "coveredUntilMs"))
                }))
                info.toString()
            }.get(3, TimeUnit.SECONDS)
        }
        suspend fun colors(): Triple<Int, Int, Int> = withContext(Dispatchers.Main) {
            val view = layer(); val image = Bitmap.createBitmap(384, 216, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(image); canvas.scale(384f / view.width, 216f / view.height); view.draw(canvas)
                val pixels = IntArray(384 * 216); image.getPixels(pixels, 0, 384, 0, 0, 384, 216)
                Triple(
                    pixels.count { Color.alpha(it) > 100 && Color.red(it) > 180 && Color.blue(it) < 60 && Color.green(it) < 60 },
                    pixels.count { Color.alpha(it) > 100 && Color.blue(it) > 180 && Color.red(it) < 60 && Color.green(it) < 60 },
                    pixels.count { Color.alpha(it) > 100 && Color.green(it) > 180 && Color.red(it) < 60 && Color.blue(it) < 60 },
                )
            } finally { image.recycle() }
        }
        suspend fun visible(wanted: Boolean) = withTimeout(5000) {
            while (true) { val (red, blue) = colors(); if (if (wanted) red > 15 && blue > 100 else red == 0 && blue == 0) break; delay(30) }
        }
        suspend fun alternateVisible() = withTimeout(5000) {
            while (true) { val (red, blue, green) = colors(); if (red == 0 && blue > 100 && green > 15) break; delay(30) }
        }
        suspend fun saveFailure() = withContext(Dispatchers.Main) {
            val view = layer(); val image = Bitmap.createBitmap(384, 216, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(image); canvas.scale(384f / view.width, 216f / view.height); view.draw(canvas)
                File(context.getExternalFilesDir(null), "ass-incremental-layer-failure.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { image.recycle() }
        }
        try {
            main {
                viewport = PlayerViewport(activity).also { (activity.window.decorView as ViewGroup).addView(it, ViewGroup.LayoutParams(384, 216)) }
                player = NativePlayer(activity, MediaResourcePolicy.LowMemoryTv).also {
                    it.attach(viewport!!); it.subtitleSourceOffset(1000); it.open(Uri.fromFile(movie).toString(), 1500, autoplay = false)
                }
            }
            val native = player!!
            stage = "first video frame"
            waitFor { native.state.value.let { it.firstFrameRendered && it.seekable && abs(it.positionMs - 1500) < 250 } }
            main { native.subtitle(-1) }
            stage = "start ASS source"
            val lease = store.acquireGrowing("source", EXTERNAL_TEXT_BYTES_LIMIT) { writer ->
                ProgressiveAssSource.download(root, server.url, "owned-source", writer)
            }
            leases += lease
            val epoch = native.state.value.mediaGeneration
            val pending = async(Dispatchers.Main) { native.importAssStream(lease, "owned.ass", "owned-source") {} }
            stage = "select provisional ASS"
            waitFor { native.state.value.selectedSubtitle >= 0 && native.state.value.subtitleLoading }
            stage = "show provisional ASS"
            visible(true)
            val partialId = native.state.value.selectedSubtitle
            assertNull(native.importedSubtitleIdentity(partialId)); assertFalse(pending.isCompleted)
            stage = "seek outside provisional range"
            main { native.seek(5500) }
            waitFor { abs(native.state.value.positionMs - 5500) < 250 }; visible(false)
            stage = "seek back to provisional range"
            main { native.seek(1500) }
            waitFor { abs(native.state.value.positionMs - 1500) < 250 }; visible(true)
            stage = "clear provisional on disorder"
            server.middle.countDown(); assertTrue(server.middleSent.await(5, TimeUnit.SECONDS)); visible(false)
            assertTrue(native.state.value.subtitleLoading)
            stage = "commit and display ASS"
            server.finish.countDown(); assertTrue(withTimeout(10_000) { pending.await() }); visible(true)
            assertNotNull(native.importedSubtitleIdentity(native.state.value.selectedSubtitle))
            main { native.subtitle(-1); assertTrue(native.selectImportedSubtitle("owned-source")) }
            visible(true); assertEquals(epoch, native.state.value.mediaGeneration); assertFalse(native.state.value.playing)
            assertEquals(1, native.diagnosticSnapshot().count { it.action == PlaybackTraceAction.MEDIA_SET })
            val oldId = native.state.value.selectedSubtitle

            // A second selection can supersede a still-loading one. If it
            // fails, restore the last accepted track, not the retired prefix.
            stage = "select intermediate ASS"
            val intermediate = AssServer(false, alternate = true).also { servers += it }
            val intermediateLease = store.acquireGrowing("intermediate-source", EXTERNAL_TEXT_BYTES_LIMIT) { writer ->
                ProgressiveAssSource.download(root, intermediate.url, "owned-source", writer)
            }.also { leases += it }
            val superseded = async(Dispatchers.Main) { native.importAssStream(intermediateLease, "intermediate.ass", "intermediate-source") {} }
            waitFor { native.state.value.selectedSubtitle >= 0 && native.state.value.selectedSubtitle != oldId }
            val intermediateId = native.state.value.selectedSubtitle
            alternateVisible()

            // Cross-format switching follows the same last-confirmed rollback.
            stage = "switch partial ASS to PGS"
            val pgs = ProgressivePgsSourceTest.StreamServer("normal").also { pgsServer = it }
            val pgsPending = async(Dispatchers.Main) { native.importPgsStream(pgs.url, "middle.sup", "middle-pgs", "owned-source") {} }
            assertFalse(withTimeout(5000) { superseded.await() }); intermediateLease.close()
            waitFor { native.state.value.selectedSubtitle >= 0 && native.state.value.selectedSubtitle != oldId && native.state.value.selectedSubtitle != intermediateId }
            val pgsId = native.state.value.selectedSubtitle

            stage = "switch partial PGS to invalid ASS"
            val bad = AssServer(true).also { servers += it }
            val badLease = store.acquireGrowing("bad-source", EXTERNAL_TEXT_BYTES_LIMIT) { writer ->
                ProgressiveAssSource.download(root, bad.url, "owned-source", writer)
            }.also { leases += it }
            val failed = async(Dispatchers.Main) { native.importAssStream(badLease, "bad.ass", "bad-source") {} }
            assertFalse(withTimeout(5000) { pgsPending.await() })
            waitFor { native.state.value.selectedSubtitle >= 0 && native.state.value.selectedSubtitle != oldId && native.state.value.selectedSubtitle != pgsId }
            val badId = native.state.value.selectedSubtitle
            bad.middle.countDown(); bad.finish.countDown()
            stage = "restore accepted track after bad digest"
            assertFalse(withTimeout(10_000) { failed.await() })
            waitFor { native.state.value.selectedSubtitle == oldId && native.state.value.operationError != null }
            assertNull(native.importedSubtitleIdentity(badId)); visible(true)

            // Explicit subtitle off cancels the provisional selection without
            // restoring either the failed track or the previous one.
            stage = "select provisional ASS then off"
            val off = AssServer(false).also { servers += it }
            val offLease = store.acquireGrowing("off-source", EXTERNAL_TEXT_BYTES_LIMIT) { writer ->
                ProgressiveAssSource.download(root, off.url, "owned-source", writer)
            }.also { leases += it }
            val cancelled = async(Dispatchers.Main) { native.importAssStream(offLease, "off.ass", "off-source") {} }
            waitFor { native.state.value.selectedSubtitle >= 0 && native.state.value.selectedSubtitle != oldId }
            main { native.subtitle(-1) }
            assertFalse(withTimeout(5000) { cancelled.await() }); visible(false)
            assertEquals(-1, native.state.value.selectedSubtitle)
            offLease.close()
            withTimeout(2000) { while (offLease.file.exists()) delay(10) }
        } catch (failure: Throwable) {
            val snapshot = player?.state?.value
            val pixels = runCatching { colors() }.getOrNull()
            runCatching { saveFailure() }
            val details = runCatching { layerDetails() }.getOrElse { it.javaClass.simpleName }
            throw AssertionError("ASS stage=$stage pixels=$pixels selected=${snapshot?.selectedSubtitle} " +
                "loading=${snapshot?.subtitleLoading} position=${snapshot?.positionMs} error=${snapshot?.operationError} layer=$details", failure)
        } finally {
            leases.forEach { it.close() }; store.close(); pgsServer?.close(); servers.forEach { it.close() }
            withContext(NonCancellable + Dispatchers.Main) { player?.release(); viewport?.let { (it.parent as? ViewGroup)?.removeView(it) } }
            root.deleteRecursively()
        }
    }

    private class AssServer(private val badDigest: Boolean, alternate: Boolean = false) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}/owned"
        val firstSent = CountDownLatch(1); val middle = CountDownLatch(1); val middleSent = CountDownLatch(1); val finish = CountDownLatch(1)
        private val prefix = ("""
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 384
            PlayResY: 216
            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,sans-serif,44,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:00.50,0:00:11.00,Default,,0,0,0,,{\an7\pos(20,30)\c&H0000FF&}FILEWAY
            Dialogue: 0,0:00:00.50,0:00:11.00,Default,,0,0,0,,{\an7\pos(180,20)\c&HFF0000&\p1}m 0 0 l 80 0 80 50 0 50{\p0}
            Dialogue: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,future
        """.trimIndent().replace("c&H0000FF&", if (alternate) "c&H00FF00&" else "c&H0000FF&") + "\n").toByteArray()
        private val late = "Dialogue: 0,0:00:01.00,0:00:01.20,Default,,0,0,0,,late\n".toByteArray()
        val bytes = prefix + late
        @Volatile private var socket: Socket? = null
        private val thread = Thread({
            try {
                listener.accept().use { peer ->
                    socket = peer
                    val request = peer.getInputStream().bufferedReader(Charsets.US_ASCII)
                    while (!request.readLine().isNullOrEmpty()) { }
                    val out = peer.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/x-fileway-media-asset+ndjson; version=1\r\nConnection: close\r\n\r\n".toByteArray())
                    fun send(frame: JSONObject) { out.write((frame.toString() + "\n").toByteArray()); out.flush() }
                    fun data(bytes: ByteArray) = send(JSONObject().put("type", "data").put("data", Base64.getEncoder().encodeToString(bytes)))
                    send(JSONObject().put("type", "start").put("identity", "owned-source").put("format", "ass"))
                    // Split UTF-8 and lines at arbitrary network boundaries.
                    for (at in prefix.indices step 37) data(prefix.copyOfRange(at, minOf(at + 37, prefix.size)))
                    firstSent.countDown(); middle.await(30, TimeUnit.SECONDS)
                    data(late); middleSent.countDown(); finish.await(30, TimeUnit.SECONDS)
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    send(JSONObject().put("type", "complete").put("bytes", bytes.size).put("sha256", if (badDigest) "0".repeat(64) else digest))
                }
            } catch (_: Exception) { }
        }, "Owned-ASS-stream").apply { isDaemon = true; start() }
        override fun close() { middle.countDown(); finish.countDown(); runCatching { socket?.close() }; listener.close(); thread.join(1000) }
    }
}

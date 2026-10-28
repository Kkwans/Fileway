package io.github.kkwans.nasfilebrowser

import android.util.Base64
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.net.Uri
import android.view.PixelCopy
import android.view.accessibility.AccessibilityNodeInfo
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.player.NativePlayer
import io.github.kkwans.nasfilebrowser.player.PlayerViewport
import io.github.kkwans.nasfilebrowser.player.PlaybackTraceAction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.media3.common.MediaLibraryInfo
import android.media.AudioManager
import kotlin.math.roundToInt
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual Media3 -> localhost Go lease -> HTTP fixture, not a player mock. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class NativePlaybackTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun endedMkvReplaysOnOneToggleWithFreshPixelsAndTheSameSurfaceAndTracks(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File.createTempFile("owned-video-replay-", ".mkv", context.cacheDir)
        var player: NativePlayer? = null
        var ownedViewport: PlayerViewport? = null
        suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
        try {
            withContext(Dispatchers.IO) { instrumentation.context.assets.open("media/fixture.mkv").use { input -> file.outputStream().use { input.copyTo(it) } } }
            activity.scenario.onActivity { owner ->
                val viewport = PlayerViewport(owner).also { ownedViewport = it }
                (owner.window.decorView as ViewGroup).addView(viewport, ViewGroup.LayoutParams(384, 216))
                player = NativePlayer(context).also { it.attach(viewport) }
            }
            val native = requireNotNull(player)
            val viewport = requireNotNull(ownedViewport)
            suspend fun pixels(): IntArray? {
                val surface = viewport.video
                if (surface.width <= 0 || surface.height <= 0 || !surface.holder.surface.isValid) return null
                val bitmap = Bitmap.createBitmap(144, 81, Bitmap.Config.ARGB_8888)
                val copied = CompletableDeferred<Int>(); var requested = false
                try {
                    main { PixelCopy.request(surface, bitmap, { copied.complete(it) }, Handler(Looper.getMainLooper())); requested = true }
                    if (withTimeout(5000) { copied.await() } != PixelCopy.SUCCESS) return null
                    return IntArray(144 * 81).also { bitmap.getPixels(it, 0, 144, 0, 0, 144, 81) }
                } finally { if (!requested || copied.isCompleted) bitmap.recycle() else copied.invokeOnCompletion { bitmap.recycle() } }
            }
            suspend fun await(stage: String, predicate: suspend () -> Boolean) {
                try {
                    withTimeout(20_000) {
                        while (true) {
                            check(native.state.value.error == null) { "Replay video error at $stage: ${native.state.value.error}" }
                            if (predicate()) break
                            delay(50)
                        }
                    }
                } catch (error: TimeoutCancellationException) {
                    val state = native.state.value
                    throw AssertionError("Replay video timeout at $stage: phase=${state.phase}, playing=${state.playing}, position=${state.positionMs}, " +
                        "duration=${state.durationMs}, videoDecoder=${state.videoDecoder}, audioDecoder=${state.audioDecoder}, trace=${native.diagnosticSnapshot()}", error)
                }
            }
            fun hasPattern(frame: IntArray) = frame.count {
                maxOf(Color.red(it), Color.green(it), Color.blue(it)) - minOf(Color.red(it), Color.green(it), Color.blue(it)) > 30
            } > frame.size / 5
            main { native.rate(1.5f); native.open(Uri.fromFile(file).toString(), autoplay = true) }
            await("initial audio/video output") { native.state.value.let { it.playing && it.firstFrameRendered && it.seekable && it.selectedAudio >= 0 &&
                it.videoDecoder != "未知" && it.audioDecoder != "未知" } && pixels()?.let(::hasPattern) == true }
            val initial = native.state.value
            val surface = viewport.video.holder.surface
            main { native.seek((initial.durationMs - 250).coerceAtLeast(0)) }
            await("MKV reaches end") { native.state.value.let { it.phase == "播放完毕" && !it.playing && it.positionMs >= it.durationMs - 100 } }
            var endedPixels: IntArray? = null
            await("last decoded frame") { endedPixels = pixels(); endedPixels?.let(::hasPattern) == true }
            val finalFrame = requireNotNull(endedPixels)
            val trace = native.diagnosticSnapshot()
            assertEquals("Replay evidence requires one original media open", 1, trace.count { it.action == PlaybackTraceAction.MEDIA_SET })
            main { native.toggle() }
            await("one toggle restarts MKV at beginning") { native.state.value.let { it.playing && it.positionMs in 100..1500 } }
            await("replay produces pixels different from the ended frame") {
                pixels()?.let { frame -> hasPattern(frame) && frame.indices.count { index ->
                    val before = finalFrame[index]; val after = frame[index]
                    maxOf(kotlin.math.abs(Color.red(before) - Color.red(after)), kotlin.math.abs(Color.green(before) - Color.green(after)),
                        kotlin.math.abs(Color.blue(before) - Color.blue(after))) > 20
                } > frame.size / 50 } == true
            }
            val resumed = native.state.value.positionMs
            await("replayed audio/video clock advances") { native.state.value.let { it.playing && it.positionMs >= resumed + 250 } }
            val replayed = native.state.value
            assertEquals(initial.mediaGeneration, replayed.mediaGeneration)
            assertEquals(initial.selectedAudio, replayed.selectedAudio)
            assertEquals(initial.selectedSubtitle, replayed.selectedSubtitle)
            assertEquals(1.5f, replayed.rate, .001f)
            assertEquals(initial.audioDecoder, replayed.audioDecoder)
            assertEquals(initial.videoDecoder, replayed.videoDecoder)
            assertSame("Replay must retain the existing video surface", surface, viewport.video.holder.surface)
            for (action in listOf(PlaybackTraceAction.OPEN_REQUEST, PlaybackTraceAction.MEDIA_SET, PlaybackTraceAction.ATTACH,
                PlaybackTraceAction.DETACH, PlaybackTraceAction.PAUSE_REQUEST)) {
                assertEquals("Replay must not emit $action", trace.count { it.action == action }, native.diagnosticSnapshot().count { it.action == action })
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                player?.release()
                ownedViewport?.let { (it.parent as? ViewGroup)?.removeView(it) }
            }
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    @Test fun mkvResumeSeekPauseRecentAndReplacementUseRealNativePlayer() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val audioManager = instrumentation.targetContext.getSystemService(AudioManager::class.java)
        val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val arguments = InstrumentationRegistry.getArguments()
        arguments.getString("nfbFontScale")?.toFloat()?.let { expected ->
            assertEquals("The actual app must use the requested font scale", expected, instrumentation.targetContext.resources.configuration.fontScale, 0.01f)
        }
        val visualVariant = arguments.getString("nfbVisualVariant").orEmpty()
        require(visualVariant.matches(Regex("[a-z0-9-]{0,32}")))
        val media = instrumentation.context.assets.open("media/fixture.mkv").use { it.readBytes() }
        val source = Fixture(media)
        val database = ClientDatabase.get(instrumentation.targetContext)
        val previousSession = database.profiles().activeSession()
        val storage = ProfileStore(database, CredentialVault(instrumentation.targetContext))
        val profile = storage.save(ServerProfile(name = "Native playback fixture", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        val file = ResourceRef("/fixture.mkv", "/fixture.mkv", "Native playback fixture.mkv", false, "video", media.size.toLong())
        var checking = "initial decode and resume"
        suspend fun onMain(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
        suspend fun waitUntil(predicate: () -> Boolean) {
            try { withTimeout(25_000) { while (!predicate()) delay(100) } }
            catch (error: TimeoutCancellationException) {
                val state = model.player.state.value
                throw AssertionError("Native timeout during $checking: phase=${state.phase}, playing=${state.playing}, position=${state.positionMs}, duration=${state.durationMs}, seekable=${state.seekable}, video=${state.width}x${state.height}, error=${state.error}, clientBusy=${model.state.value.busy}, selected=${model.state.value.selected != null}, raw=${source.rawRequests.get()}, unexpected=${source.unexpected.get()}, trace=${model.player.diagnosticSnapshot()}", error)
            }
        }
        try {
            android.util.Log.i("FilewayNativeGate", "Media3=${MediaLibraryInfo.VERSION}")
            onMain { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "fixture", "fixture-only", "direct") }
            waitUntil { model.state.value.connected && !model.state.value.busy }
            onMain { model.open(file) }
            waitUntil { model.player.state.value.let { it.playing && it.seekable && it.durationMs in 11_500..12_500 && it.positionMs >= 2800 && it.width == 320 } }
            withTimeout(5000) {
                var picture = false
                while (!picture) {
                    val surfaces = mutableListOf<SurfaceView>()
                    activity.scenario.onActivity { owner ->
                        fun collect(view: View) {
                            if (view is SurfaceView && view.holder.surface.isValid) surfaces.add(view)
                            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                        }
                        collect(owner.window.decorView)
                    }
                    for (surface in surfaces) {
                        val bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
                        val completed = AtomicBoolean()
                        try {
                            val copied = suspendCancellableCoroutine<Int> { continuation ->
                                try {
                                    PixelCopy.request(surface, bitmap, { result ->
                                        completed.set(true)
                                        if (continuation.isActive) continuation.resumeWith(Result.success(result)) else bitmap.recycle()
                                    }, Handler(Looper.getMainLooper()))
                                } catch (_: IllegalArgumentException) {
                                    completed.set(true)
                                    continuation.resumeWith(Result.success(PixelCopy.ERROR_SOURCE_INVALID))
                                }
                            }
                            if (copied == PixelCopy.SUCCESS) {
                                val pixels = IntArray(64 * 36)
                                bitmap.getPixels(pixels, 0, 64, 0, 0, 64, 36)
                                // The owned pattern is colorful. A black output,
                                // subtitle-only surface or metadata cannot pass.
                                picture = picture || pixels.count { pixel ->
                                    val channels = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                                    channels.max() - channels.min() > 30
                                } > pixels.size / 5
                                if (picture) {
                                    activity.scenario.onActivity {
                                        val viewport = surface.parent as View
                                        assertTrue("The 16:9 fixture must fit the portrait viewport width", surface.width >= viewport.width * 0.95f)
                                    }
                                }
                            }
                        } finally { if (completed.get()) bitmap.recycle() }
                    }
                    if (!picture) delay(100)
                }
                assertTrue("The actual native Surface must contain the decoded pattern", picture)
            }
            assertTrue("Native audio track must be discovered", model.player.state.value.audio.any { it.id >= 0 })
            val device = UiDevice.getInstance(instrumentation)
            fun capture(name: String) {
                val suffix = if (visualVariant.isEmpty()) "" else "-$visualVariant"
                device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
                device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/$name$suffix.png")
            }
            assertTrue("Playing controls must hide after inactivity", device.wait(Until.gone(By.desc("暂停播放")), 6000))
            device.findObject(By.desc("视频画面")).click()
            assertTrue("A tap restores explicit playback controls", device.wait(Until.hasObject(By.desc("暂停播放")), 3000))
            onMain { model.player.seek(7000) }
            checking = "explicit seek"
            waitUntil { model.player.state.value.positionMs in 6500..9500 && model.player.state.value.phase != "正在跳转" }
            onMain { model.pausePlayback() }
            checking = "pause and remote checkpoint"
            waitUntil { !model.player.state.value.playing && source.position >= 6.5 }
            val account = storage.accounts(profile).single()
            val history = PlaybackHistory(ClientDatabase.get(instrumentation.targetContext).playback())
            val saved = withTimeout(10_000) { history.recent(account).first { entries -> entries.any { it.positionMs >= 6500 && it.sync == ProgressSync.SYNCED } }.first() }
            assertEquals("fixture-v1", saved.identity)
            assertTrue(source.rawRequests.get() > 0)
            assertEquals(0, source.unexpected.get())
            device.findObject(By.desc("播放速度")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("关闭播放设置")), 3000))
            device.findObject(By.text("1.25×")).click()
            waitUntil { model.player.state.value.rate == 1.25f }
            // At large font sizes this real action sits below the visible
            // details area. Scroll the native page, never bypass its UI.
            for (attempt in 0 until 4) {
                val target = device.findObject(By.desc("媒体系统音量"))
                if (target != null && target.visibleBounds.height() >= (48 * instrumentation.targetContext.resources.displayMetrics.density).toInt()) break
                device.findObject(By.desc("播放详情"))?.scroll(Direction.DOWN, 0.7f)
            }
            val volumeAction = device.findObject(By.desc("媒体系统音量"))
            assertNotNull("Volume must be reachable after scrolling", volumeAction)
            volumeAction.click()
            assertTrue(device.wait(Until.hasObject(By.desc("关闭播放设置")), 3000))
            fun adjustable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.contentDescription?.toString() == "媒体系统音量" && node.rangeInfo != null) return node
                for (index in 0 until node.childCount) node.getChild(index)?.let { child -> adjustable(child)?.let { return it } }
                return null
            }
            var slider: AccessibilityNodeInfo? = null
            val readyAt = android.os.SystemClock.uptimeMillis() + 5000
            while (slider == null && android.os.SystemClock.uptimeMillis() < readyAt) {
                instrumentation.uiAutomation.rootInActiveWindow?.let { slider = adjustable(it) }
                if (slider == null) delay(100)
            }
            if (slider == null) {
                fun inspect(node: AccessibilityNodeInfo) {
                    android.util.Log.i("NfbAcceptance", "class=${node.className}, desc=${node.contentDescription}, range=${node.rangeInfo}, actions=${node.actionList.map { it.id }}")
                    for (index in 0 until node.childCount) node.getChild(index)?.let(::inspect)
                }
                instrumentation.uiAutomation.rootInActiveWindow?.let(::inspect)
            }
            assertNotNull("The volume slider must expose an accessible range", slider)
            val maximumVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val preferredVolume = (maximumVolume * .35f).roundToInt()
            // A no-op setProgress correctly returns false. The CI emulator
            // starts at 5/15, exactly the rounded 35% target; exercise a change.
            val expectedVolume = if (preferredVolume != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) preferredVolume
                else if (preferredVolume < maximumVolume) preferredVolume + 1 else preferredVolume - 1
            val progress = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, expectedVolume.toFloat()) }
            assertTrue(slider!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, progress))
            waitUntil { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == expectedVolume }
            assertEquals(expectedVolume, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals("System volume must not alter the engine gain", 100, model.player.state.value.volume)
            assertEquals(1.25f, model.player.state.value.rate, 0.001f)
            capture("player-volume-sheet")
            device.findObject(By.desc("关闭播放设置")).click()
            device.findObject(By.desc("选择音轨")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("关闭播放设置")), 3000))
            capture("player-audio-sheet")
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.desc("开始播放")), 3000))
            capture("player-fixture")
            device.findObject(By.desc("横屏全屏")).click()
            // Android's first immersive entry presents its own onboarding. Do
            // not mistake that system overlay for app controls or disable it.
            if (device.wait(Until.hasObject(By.text("Got it")), 3000)) {
                device.findObject(By.text("Got it")).click()
                assertTrue("The system immersive prompt must be dismissed", device.wait(Until.gone(By.text("Got it")), 3000))
            }
            assertTrue("Fullscreen button must enter the actual landscape layout", device.wait(Until.hasObject(By.desc("退出全屏")), 5000))
            assertTrue("Rotation must preserve the paused source", !model.player.state.value.playing && model.state.value.selected == file)
            device.findObject(By.desc("选择音轨")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("关闭播放设置")), 3000))
            capture("player-landscape-audio")
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.desc("退出全屏")), 3000))
            capture("player-landscape")
            activity.scenario.onActivity { owner ->
                var videoSurface: SurfaceView? = null
                fun findVideo(view: View) {
                    if (view is SurfaceView && view.holder.surface.isValid) videoSurface = view
                    if (view is ViewGroup) for (index in 0 until view.childCount) findVideo(view.getChildAt(index))
                }
                findVideo(owner.window.decorView)
                val surface = requireNotNull(videoSurface)
                assertTrue("Landscape video must use the full available height rather than reserve a black transport strip", surface.height >= owner.window.decorView.height * .95f)
            }
            device.findObject(By.desc("退出全屏")).click()
            assertTrue("Exit fullscreen must restore portrait controls", device.wait(Until.hasObject(By.desc("横屏全屏")), 5000))
            onMain { model.leavePlayer(); model.tab("recent") }
            waitUntil { model.recent.value.any { it.resourceKey == file.wirePath } }
            assertTrue("Recent capture must show the actual recent page", device.wait(Until.hasObject(By.text("继续观看")), 5000))
            capture("recent-fixture")
            onMain { model.openRecent(saved) }
            checking = "recent reopen"
            waitUntil { model.player.state.value.playing && model.player.state.value.positionMs >= saved.positionMs - 1500 }
            onMain { model.pausePlayback() }
            checking = "recent pause and sync"
            waitUntil { !model.player.state.value.playing && model.state.value.progressStatus == "续播已同步" }
            checking = "cancel source confirmation"
            source.stallNextRead.set(true)
            onMain { model.togglePlayback() }
            assertTrue(withContext(Dispatchers.IO) { source.resumeRead.await(5, TimeUnit.SECONDS) })
            assertTrue(device.wait(Until.hasObject(By.desc("正在加载视频")), 3000))
            assertFalse(device.hasObject(By.desc("取消播放请求")))
            // Request cancellation is an internal lifecycle action, not a buffering HUD button.
            onMain { model.cancel() }
            assertFalse("Cancel must immediately leave the loading state", model.state.value.busy)
            source.releaseRead.countDown()
            delay(700)
            assertFalse("A canceled confirmation must not resume playback when its response arrives", model.player.state.value.playing)
            source.resumeRead = CountDownLatch(1)
            source.releaseRead = CountDownLatch(1)
            source.stallNextRead.set(true)
            onMain { model.togglePlayback() }
            assertTrue(withContext(Dispatchers.IO) { source.resumeRead.await(5, TimeUnit.SECONDS) })
            activity.scenario.moveToState(Lifecycle.State.CREATED)
            source.releaseRead.countDown()
            activity.scenario.moveToState(Lifecycle.State.RESUMED)
            delay(500)
            assertFalse("A late resume response must not play after backgrounding", model.player.state.value.playing)
            assertFalse(model.state.value.busy)
            source.identity = "fixture-v2"
            onMain { model.togglePlayback() }
            checking = "replacement rejection"
            waitUntil { model.state.value.selected == null && model.state.value.error?.contains("文件已变化") == true }
            assertFalse("Replacement must not resume an old media lease", model.player.state.value.playing)
        } catch (error: Throwable) {
            val device = UiDevice.getInstance(instrumentation)
            device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/native-playback-failure.png")
            // This test only uses owned fixture credentials/media. Restrict
            // diagnostics to this process rather than exporting system logs.
            runCatching {
                val output = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "native-playback-failure-logcat.txt")
                output.writeText(device.executeShellCommand("logcat -d --pid=${android.os.Process.myPid()} -t 2000"))
                // UiAutomation runs argv directly. Copy before UTP removes the
                // app-specific directory; do not rely on shell redirection.
                device.executeShellCommand("cp ${output.absolutePath} /sdcard/Download/nfb-client-acceptance/native-playback-failure-logcat.txt")
            }.onFailure { error.addSuppressed(it) }
            throw error
        } finally {
            withContext(NonCancellable) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                source.releaseRead.countDown()
                onMain { model.disconnect() }
                try { storage.remove(profile) } finally { source.close() }
                previousSession?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
            }
        }
    }

    private suspend fun fixtureReply(source: Fixture, method: String, endpoint: String, body: String? = null): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val connection = URL(source.url + endpoint).openConnection() as HttpURLConnection
            connection.connectTimeout = 3000; connection.readTimeout = 3000; connection.instanceFollowRedirects = false
            connection.requestMethod = method; connection.setRequestProperty("X-Auth", "owned-fixture")
            try {
                body?.toByteArray(Charsets.UTF_8)?.let { bytes ->
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.doOutput = true; connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                status to (stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty())
            } finally { connection.disconnect() }
        }

    @Test fun mediaFixtureRecentRecordsCanonicalOwnedPathsAndReturnsOrderedReadBack(): Unit = runBlocking {
        val name = "片段 +%?#.mkv"; val path = "/资料/$name"; val wire = SearchResult.encodePath(path)
        Fixture(byteArrayOf(1, 2, 3), videos = listOf("资料/$name", "literal%2F.mkv"), directories = listOf("资料")).use { source ->
            val metadata = fixtureReply(source, "GET", "/api/resources$wire?metadata=1")
            assertEquals(200, metadata.first)
            assertEquals(wire, JSONObject(metadata.second).getString("wirePath"))
            suspend fun record(body: JSONObject): RecentAccessEntry {
                val reply = fixtureReply(source, "POST", "/api/recent", body.toString())
                assertEquals(reply.second, 200, reply.first)
                return parseRecentAccessEntry(JSONObject(reply.second))
            }
            val first = record(JSONObject().put("path", path).put("wirePath", wire))
            assertEquals(path, first.path); assertEquals(wire, first.wirePath); assertEquals(name, first.name)
            assertFalse(first.isDir); assertTrue(first.openable)
            val root = record(JSONObject().put("wirePath", "/"))
            assertTrue(root.isDir); assertEquals("/", root.path); assertEquals("我的文件", root.name)
            val directory = record(JSONObject().put("path", "/资料"))
            assertTrue(directory.isDir); assertEquals("资料", directory.name)
            val all = fixtureReply(source, "GET", "/api/recent?limit=$RECENT_ACCESS_LIMIT")
            assertEquals(200, all.first)
            assertEquals(listOf(directory.path, root.path, first.path), parseRecentAccess(JSONArray(all.second)).map { it.path })
            val alias = Regex("%[0-9A-F]{2}").replace(wire) { it.value.lowercase() }
            val again = record(JSONObject().put("wirePath", alias))
            assertEquals(first.id, again.id); assertEquals(wire, again.wirePath); assertTrue(again.accessedAt > first.accessedAt)
            val limited = fixtureReply(source, "GET", "/api/recent?limit=1")
            assertEquals(200, limited.first)
            assertEquals(listOf(first.path), parseRecentAccess(JSONArray(limited.second)).map { it.path })
            val literal = record(JSONObject().put("path", "/literal%2F.mkv"))
            assertEquals("/literal%252F.mkv", literal.wirePath)
            assertEquals(literal.id, record(JSONObject().put("wirePath", literal.wirePath)).id)
            val latest = fixtureReply(source, "GET", "/api/recent")
            assertEquals(200, latest.first)
            assertEquals(4, parseRecentAccess(JSONArray(latest.second)).size)
            assertEquals(0, source.unexpected.get())
        }
        Fixture(byteArrayOf(1), videos = listOf(name)).use { source ->
            val listing = fixtureReply(source, "GET", "/api/resources/")
            assertEquals(200, listing.first)
            val item = JSONObject(listing.second).getJSONArray("items").getJSONObject(0)
            val target = recentAccessRecordTarget(ResourceRef(item.getString("path"), item.getString("wirePath"), name, false, "video", 1))
            assertEquals(SearchResult.encodePath("/$name"), target.wirePath)
            assertEquals(0, source.unexpected.get())
        }
    }

    @Test fun mediaFixtureRecentRejectsInvalidRequestsAndKeepsUnknownEndpointGuards(): Unit = runBlocking {
        Fixture(byteArrayOf(1, 2, 3)).use { source ->
            val invalid = listOf(
                Triple("PUT", "/api/recent", 405), Triple("DELETE", "/api/recent", 405),
                Triple("GET", "/api/recent?limit=0", 400), Triple("GET", "/api/recent?limit=no", 400),
                Triple("GET", "/api/recent?cursor=next", 400), Triple("POST", "/api/recent?limit=1", 400),
            )
            invalid.forEach { (method, endpoint, status) -> assertEquals(status, fixtureReply(source, method, endpoint).first) }
            val rejectedBodies = listOf(
                JSONObject().put("wirePath", "/missing.mkv").toString() to 404,
                JSONObject().put("path", "/missing.mkv").toString() to 404,
                JSONObject().put("path", "/other.mkv").put("wirePath", "/fixture.mkv").toString() to 400,
                JSONObject().put("wirePath", "/%2Ffixture.mkv").toString() to 400,
                JSONObject().put("wirePath", "/../fixture.mkv").toString() to 400,
                JSONObject().put("wirePath", "/fixture.mkv?other=1").toString() to 400,
                JSONObject().put("wirePath", 123).toString() to 400,
                JSONObject().put("path", JSONObject.NULL).toString() to 400,
                "{}" to 400,
                JSONObject().put("wirePath", "").toString() to 400,
                "{" to 400,
            )
            rejectedBodies.forEach { (body, status) -> assertEquals(status, fixtureReply(source, "POST", "/api/recent", body).first) }
            val rejected = invalid.size + rejectedBodies.size
            assertEquals("Invalid recent traffic must remain visible to existing media guards", rejected, source.unexpected.get())
            assertEquals(0, JSONArray(fixtureReply(source, "GET", "/api/recent?limit=100").second).length())
            listOf("/api/media/hls?path=/fixture.mkv", "/api/media/transcode?path=/fixture.mkv", "/api/recent/export").forEach { endpoint ->
                assertEquals(404, fixtureReply(source, "GET", endpoint).first)
            }
            assertEquals(rejected + 3, source.unexpected.get())
        }
    }

    internal class Fixture(private val media: ByteArray, private val subtitles: Map<String, ByteArray> = emptyMap(),
        private val videos: List<String> = listOf("fixture.mkv"), private val download: Boolean = false,
        private val directories: List<String> = emptyList()) : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val rawRequests = AtomicInteger()
        val subtitleRequests = AtomicInteger()
        val unexpected = AtomicInteger()
        val stallNextRead = AtomicBoolean()
        val stallNextSubtitle = AtomicBoolean()
        val subtitleRead = CountDownLatch(1)
        val releaseSubtitle = CountDownLatch(1)
        val subtitleReturned = CountDownLatch(1)
        @Volatile var resumeRead = CountDownLatch(1)
        @Volatile var releaseRead = CountDownLatch(1)
        @Volatile var position = 3.0
        @Volatile var identity = "fixture-v1"
        @Volatile private var updated = 1L
        private val recentResources = buildList {
            add(ResourceRef("/", "/", "我的文件", true, "", 0))
            directories.forEach { name -> add(ResourceRef("/$name", SearchResult.encodePath("/$name"), name.substringAfterLast('/'), true, "", 0)) }
            (videos + subtitles.keys).forEach { name -> add(ResourceRef("/$name", SearchResult.encodePath("/$name"), name.substringAfterLast('/'), false, "", 0)) }
        }.associateBy { recentAccessWireIdentity(recentAccessRecordTarget(it).wirePath) }
        private val recentEntries = linkedMapOf<String, RecentAccessEntry>()
        private var recentSequence = 0L
        private var recentTime = 0L
        private fun recentRow(entry: RecentAccessEntry): JSONObject = JSONObject().put("id", entry.id).put("path", entry.path)
            .put("wirePath", entry.wirePath).put("name", entry.name).put("isDir", entry.isDir)
            .put("accessedAt", entry.accessedAt).put("pathVerified", true).also { parseRecentAccessEntry(it) }
        /** Recognized requests still require fixture-owned byte identity; rejections keep the media traffic guard red. */
        private fun recentReply(method: String, endpoint: String, body: String): Pair<Int, String> {
            try {
                val uri = java.net.URI(endpoint)
                require(uri.rawPath == "/api/recent" && uri.rawFragment == null)
                if (method == "GET") {
                    require(body.isEmpty())
                    val limit = if (uri.rawQuery.isNullOrEmpty()) RECENT_ACCESS_LIMIT else {
                        val raw = requireNotNull(Regex("limit=([0-9]+)").matchEntire(uri.rawQuery)).groupValues[1]
                        requireNotNull(raw.toLongOrNull()).also { require(it > 0) }.coerceAtMost(RECENT_ACCESS_LIMIT.toLong()).toInt()
                    }
                    val rows = synchronized(recentEntries) {
                        recentEntries.values.sortedWith(compareByDescending<RecentAccessEntry> { it.accessedAt }.thenByDescending { it.id })
                            .take(limit).map(::recentRow)
                    }
                    return 200 to JSONArray(rows).toString()
                }
                if (method != "POST") return 405 to "{}"
                require(uri.rawQuery == null)
                val data = JSONObject(body)
                require(data.keys().asSequence().all { it in setOf("path", "wirePath") })
                fun field(name: String): String = if (!data.has(name)) "" else (data.get(name) as? String ?: error("Invalid recent field"))
                val wire = field("wirePath"); val suppliedPath = field("path")
                require(wire.isNotEmpty() || suppliedPath.isNotEmpty())
                val fromWire = if (wire.isEmpty()) null else recentResources[recentAccessWireIdentity(wire)]
                val path = suppliedPath.ifEmpty { fromWire?.path ?: return 404 to "{}" }
                val target = recentAccessRecordTarget(ResourceRef(path, wire, "Owned recent request", false, "", 0))
                val key = recentAccessWireIdentity(target.wirePath)
                val resource = recentResources[key] ?: return 404 to "{}"
                require(target.path == resource.path)
                return synchronized(recentEntries) {
                    recentTime = maxOf(System.currentTimeMillis(), recentTime + 1)
                    val entry = RecentAccessEntry(recentEntries[key]?.id ?: "fixture-recent-${++recentSequence}",
                        resource.path, resource.wirePath, resource.name, resource.directory, recentTime)
                    val row = recentRow(entry)
                    recentEntries[key] = entry
                    200 to row.toString()
                }
            } catch (_: Exception) { return 400 to "{}" }
        }
        private val acceptor = Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                Thread({ try { serve(socket) } catch (_: Exception) { /* canceled transport */ } finally { sockets.remove(socket); socket.close() } }, "nfb-native-media").apply { isDaemon = true; start() }
            }
        }, "nfb-native-source").apply { isDaemon = true; start() }
        val url = "http://127.0.0.1:${server.localPort}"
        private fun serve(socket: Socket) {
            socket.soTimeout = 15_000
            val input = socket.getInputStream().buffered()
            fun line(): String? {
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val value = input.read()
                    if (value < 0) return if (bytes.size() == 0) null else error("Incomplete fixture HTTP header")
                    if (value == 10) return bytes.toString("UTF-8").removeSuffix("\r")
                    check(bytes.size() < 8192); bytes.write(value)
                }
            }
            val request = (line() ?: return).split(' ')
            val endpoint = request[1]
            val headers = mutableMapOf<String, String>()
            while (true) {
                val header = line() ?: return
                if (header.isEmpty()) break
                headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
            }
            // Content-Length counts bytes. A character reader deadlocks on literal UTF-8 JSON paths.
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            check(length in 0..65_536)
            val body = ByteArray(length)
            var count = 0
            while (count < body.size) { val size = input.read(body, count, body.size - count); if (size < 0) return; count += size }
            fun send(bytes: ByteArray, type: String = "application/json", status: Int = 200, extra: String = "") {
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $status OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n$extra\r\n".toByteArray(Charsets.US_ASCII))
                    if (request[0] != "HEAD") write(bytes)
                    flush()
                }
            }
            if (endpoint != "/api/login" && headers["x-auth"].isNullOrEmpty()) { send(ByteArray(0), status = 403); return }
            when {
                endpoint == "/api/login" -> {
                    val payload = JSONObject().put("user", JSONObject().put("id", 71).put("username", "fixture")
                        .apply { if (download) put("perm", JSONObject().put("download", true)) })
                    val token = "header." + Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
                    send(token.toByteArray(), "text/plain")
                }
                endpoint == "/api/tags" -> send("[]".toByteArray())
                endpoint.substringBefore('?') == "/api/recent" -> {
                    val (status, json) = recentReply(request[0], endpoint, body.toString(Charsets.UTF_8))
                    if (status != 200) unexpected.incrementAndGet()
                    send(json.toByteArray(Charsets.UTF_8), status = status)
                }
                directories.isNotEmpty() && endpoint.startsWith("/api/resources/") -> {
                    val uri = java.net.URI(endpoint)
                    val path = uri.path.removePrefix("/api/resources").trimEnd('/').ifEmpty { "/" }
                    fun item(name: String, directory: Boolean): JSONObject = JSONObject().put("path", "/$name")
                        .put("wirePath", SearchResult.encodePath("/$name")).put("name", name.substringAfterLast('/'))
                        .put("isDir", directory).put("type", if (directory) "" else "video")
                        .put("size", if (directory) 0 else media.size).put("modified", "owned-download-v1")
                    val key = path.removePrefix("/")
                    if (uri.rawQuery == "metadata=1") {
                        val found = if (path == "/") item("", true) else if (key in directories) item(key, true) else if (key in videos) item(key, false) else null
                        send((found?.toString() ?: "{}").toByteArray(), status = if (found == null) 404 else 200)
                    } else {
                        val entries = directories.filter { it.substringBeforeLast('/', "") == key }.map { item(it, true) } +
                            videos.filter { it.substringBeforeLast('/', "") == key }.map { item(it, false) }
                        send(JSONObject().put("items", JSONArray(entries)).toString().toByteArray())
                    }
                }
                endpoint == "/api/resources/" -> {
                    val items = JSONArray()
                    videos.forEach { name -> items.put(JSONObject().put("path", "/$name").put("wirePath", SearchResult.encodePath("/$name"))
                        .put("name", if (name == "fixture.mkv") "Native playback fixture.mkv" else name).put("type", "video").put("size", media.size)) }
                    subtitles.forEach { (name, bytes) -> items.put(JSONObject().put("path", "/$name").put("wirePath", "/$name").put("name", name).put("size", bytes.size)) }
                    send(JSONObject().put("items", items).toString().toByteArray())
                }
                endpoint.startsWith("/api/raw/") && subtitles.containsKey(endpoint.removePrefix("/api/raw/")) -> {
                    subtitleRequests.incrementAndGet()
                    val stalled = stallNextSubtitle.compareAndSet(true, false)
                    try {
                        if (stalled) { subtitleRead.countDown(); check(releaseSubtitle.await(10, TimeUnit.SECONDS)) }
                        send(subtitles.getValue(endpoint.removePrefix("/api/raw/")), "text/plain; charset=utf-8")
                    } finally { if (stalled) subtitleReturned.countDown() }
                }
                download && endpoint.substringBefore('?').removePrefix("/api/resources/") in videos -> {
                    val path = endpoint.substringBefore('?').removePrefix("/api/resources")
                    send(JSONObject().put("path", path).put("wirePath", path).put("name", path.substringAfterLast('/')).put("isDir", false)
                        .put("type", "video").put("size", media.size).put("modified", "owned-download-v1").toString().toByteArray())
                }
                endpoint.substringBefore('?').removePrefix("/api/preview/thumb/") in videos -> send(ByteArray(0), status = 404)
                endpoint.startsWith("/api/media/playback") -> {
                    if (request[0] == "GET" && stallNextRead.compareAndSet(true, false)) { resumeRead.countDown(); releaseRead.await(10, TimeUnit.SECONDS) }
                    if (request[0] == "PUT") { position = JSONObject(String(body)).getDouble("position"); updated = System.currentTimeMillis() }
                    send(JSONObject().put("identity", identity).put("position", position).put("duration", 12.0).put("updatedAt", updated).put("exists", true).toString().toByteArray())
                }
                java.net.URI(endpoint).path.removePrefix("/api/raw/") in videos -> {
                    check(!endpoint.contains("inline=true"))
                    rawRequests.incrementAndGet()
                    val range = headers["range"]?.removePrefix("bytes=")
                    if (range == null) send(media, "video/x-matroska", extra = "Accept-Ranges: bytes\r\n")
                    else {
                        val start = range.substringBefore('-').toInt()
                        val end = range.substringAfter('-').toIntOrNull()?.coerceAtMost(media.lastIndex) ?: media.lastIndex
                        if (start > end) send(ByteArray(0), status = 416, extra = "Content-Range: bytes */${media.size}\r\n")
                        else send(media.copyOfRange(start, end + 1), "video/x-matroska", 206, "Accept-Ranges: bytes\r\nContent-Range: bytes $start-$end/${media.size}\r\n")
                    }
                }
                else -> { unexpected.incrementAndGet(); send(ByteArray(0), status = 404) }
            }
        }
        override fun close() { releaseSubtitle.countDown(); server.close(); sockets.forEach { runCatching { it.close() } }; acceptor.join(1000) }
    }
}

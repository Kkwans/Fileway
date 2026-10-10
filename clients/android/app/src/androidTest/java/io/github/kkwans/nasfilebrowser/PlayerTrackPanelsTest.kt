package io.github.kkwans.nasfilebrowser

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.BackendKind
import io.github.kkwans.nasfilebrowser.data.ClientDatabase
import io.github.kkwans.nasfilebrowser.data.CredentialVault
import io.github.kkwans.nasfilebrowser.data.ProfileStore
import io.github.kkwans.nasfilebrowser.data.ServerProfile
import io.github.kkwans.nasfilebrowser.player.MediaSubtitleLayer
import io.github.kkwans.nasfilebrowser.player.NativeTrack
import io.github.kkwans.nasfilebrowser.player.PlaybackTraceAction
import io.github.kkwans.nasfilebrowser.player.PlayerViewport
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Real panel navigation and engine readback; not audible/HDR output acceptance. */
@RunWith(AndroidJUnit4::class)
class PlayerTrackPanelsTest {
    private val trace = OwnedUiTraceRule()
    private val activity = ActivityScenarioRule(MainActivity::class.java)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(trace).around(activity)

    @Test fun subtitleHomeAdjustmentsAndConfirmedSelectionKeepTheNativeViewport(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val inputDriver = InstrumentationRegistry.getArguments().getString("nfbInputDriver") ?: "automation"
        require(inputDriver in listOf("automation", "shell"))
        val device = UiDevice.getInstance(instrumentation)
        val media = instrumentation.context.assets.open("media/subtitle-fixture.mkv").use { it.readBytes() }
        val source = NativePlaybackTest.Fixture(media)
        val database = ClientDatabase.get(instrumentation.targetContext)
        val previousSession = database.profiles().activeSession()
        val store = ProfileStore(database, CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "Track panel fixture", address = source.url))
        lateinit var model: ClientModel
        var originalRequestedOrientation: Int? = null
        activity.scenario.onActivity { host ->
            originalRequestedOrientation = host.requestedOrientation
            model = ViewModelProvider(host)[ClientModel::class.java]
        }
        suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
        val configuration = Configurator.getInstance()
        val oldIdle = configuration.getWaitForIdleTimeout()
        var stage = "connect"
        var observer: Job? = null
        val transitions = CopyOnWriteArrayList<Pair<Int, Int?>>()
        fun freshNodes() = freshAccessibilityBounds(instrumentation, Rect(0, 0, device.displayWidth, device.displayHeight))
        fun confirmedRow(marker: String) = freshNodes()?.any {
            it.visible && it.text?.contains(marker) == true && it.checkable && it.checked
        } == true
        fun homeShown() = freshNodes()?.let { nodes ->
            nodes.any { it.visible && it.description == "播放设置" } &&
                nodes.none { it.visible && it.description == "返回字幕轨道" } &&
                nodes.any { it.visible && it.description == "字幕调整" }
        } == true

        suspend fun find(label: String, predicate: (FreshAccessibilityNodeBounds) -> Boolean): FreshAccessibilityNodeBounds = withTimeout(5_000) {
            while (true) {
                freshNodes()?.singleOrNull { it.visible && predicate(it) }?.let { return@withTimeout it }
                delay(25)
            }
            @Suppress("UNREACHABLE_CODE") error("Missing panel control during $stage: $label")
        }
        suspend fun described(label: String) = find(label) { it.description == label }
        suspend fun absent(label: String) {
            stage = "absent-$label"
            withTimeout(5_000) {
                while (freshNodes()?.none { it.visible && it.description == label } != true) delay(25)
            }
        }
        suspend fun hasDescription(label: String) = described(label).description == label
        fun tap(bounds: Rect) {
            assertFalse("Panel control must have visible bounds during $stage", bounds.isEmpty)
            if (inputDriver == "shell") {
                // Same explicit real-input comparison as PlayerFullscreenTest.
                // UI/engine assertions below still require the actual result;
                // this is selected up front and never retries a rejected tap.
                OwnedUiTraceRule.trace("track-panel=$stage driver=shell bounds=$bounds")
                device.executeShellCommand("input touchscreen tap ${bounds.centerX()} ${bounds.centerY()}")
                return
            }
            OwnedTouchInput.tap(instrumentation, bounds, tracePrefix = "track-panel-$stage")
        }
        fun tap(node: FreshAccessibilityNodeBounds) {
            assertTrue("Panel control must be enabled during $stage", node.enabled)
            tap(node.bounds)
        }
        suspend fun click(label: String) {
            val node = described(label)
            assertTrue("$label must be enabled", node.enabled)
            assertTrue("$label must be clickable", node.clickable)
            OwnedUiTraceRule.trace("track-panel=$stage action=$label bounds=${node.bounds}")
            tap(node)
        }
        fun back(label: String) {
            OwnedUiTraceRule.trace("track-back=$label uptime=${SystemClock.uptimeMillis()}")
            // pressBack() reports an accessibility event, which IME dismissal may not emit.
            // Check actual key injection; the awaited UI state below verifies its effect.
            assertTrue("Real Back must be injected during $label", device.pressKeyCode(android.view.KeyEvent.KEYCODE_BACK))
            OwnedUiTraceRule.trace("track-back-return=$label uptime=${SystemClock.uptimeMillis()}")
        }
        fun activePanelError(node: android.view.accessibility.AccessibilityNodeInfo): Pair<Boolean, Boolean> {
            try {
                if (!node.refresh()) return false to false
                var panel = node.contentDescription?.toString() == "播放设置"
                val bounds = Rect().also(node::getBoundsInScreen)
                var error = node.text?.toString() == "轨道已变化，请重新选择" && node.isVisibleToUser &&
                    !bounds.isEmpty && bounds.left >= 0 && bounds.top >= 0 &&
                    bounds.right <= device.displayWidth && bounds.bottom <= device.displayHeight
                for (index in 0 until node.childCount) {
                    val result = node.getChild(index)?.let(::activePanelError) ?: continue
                    panel = panel || result.first; error = error || result.second
                }
                return panel to error
            } finally { node.recycle() }
        }
        suspend fun scrollToBounds(label: String, description: Boolean, towardEnd: Boolean): Rect = withTimeout(5_000) {
            while (true) {
                val nodes = freshNodes()
                val target = nodes?.singleOrNull { it.visible && it.enabled &&
                    if (description) it.description == label else it.checkable && it.text?.contains(label) == true }
                if (target != null) return@withTimeout Rect(target.bounds)
                val list = nodes?.singleOrNull { it.visible && it.enabled && it.scrollable && "播放设置" in it.ancestorDescriptions }
                if (list != null) {
                    val bounds = list.bounds
                    val top = bounds.top + bounds.height() / 5
                    val bottom = bounds.bottom - bounds.height() / 5
                    assertTrue("Real panel scroll must be injected during $stage", device.swipe(bounds.centerX(),
                        if (towardEnd) bottom else top, bounds.centerX(), if (towardEnd) top else bottom, 30))
                }
                delay(25)
            }
            @Suppress("UNREACHABLE_CODE") error("Panel item missing: $label")
        }
        suspend fun adjust() {
            tap(scrollToBounds("字幕调整", description = true, towardEnd = false))
        }
        suspend fun await(label: String, predicate: () -> Boolean) {
            stage = label
            try { withTimeout(5_000) { while (!predicate()) delay(25) } }
            catch (error: TimeoutCancellationException) {
                val state = model.player.state.value
                throw AssertionError("Track panel timeout during $stage: selected=${state.selectedSubtitle}, " +
                    "pending=${state.pendingSubtitle}, operationError=${state.operationError}; ${trace.snapshot()}", error)
            }
        }
        suspend fun keyboard(shown: Boolean) {
            stage = "ime-$shown"
            withTimeout(5_000) {
                while (true) {
                    val matches = withContext(Dispatchers.Main) {
                        WindowInspector.getGlobalWindowViews().any { view ->
                            view.isShown && view.hasWindowFocus() &&
                                ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == shown
                        }
                    }
                    if (matches) break
                    delay(25)
                }
            }
        }
        fun findViewport(view: View): PlayerViewport? {
            if (view is PlayerViewport) return view
            if (view is ViewGroup) for (index in 0 until view.childCount)
                findViewport(view.getChildAt(index))?.let { return it }
            return null
        }
        var trackedHolder: SurfaceHolder? = null
        var surfaceRevision = 0
        val surfaceCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceRevision++ }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { surfaceRevision++ }
            override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceRevision++ }
        }
        data class Geometry(val layers: List<View>, val bounds: List<Rect>, val surfaceRevision: Int)
        fun geometry(): Geometry {
            lateinit var viewport: PlayerViewport
            val bounds = mutableListOf<Rect>()
            val layers = mutableListOf<View>()
            var revision = -1
            activity.scenario.onActivity { owner ->
                viewport = findViewport(owner.window.decorView) ?: error("Native viewport missing during $stage")
                layers += listOf(viewport, viewport.video, viewport.text)
                fun collect(view: View) {
                    if (view is MediaSubtitleLayer) layers += view
                    if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                }
                collect(viewport)
                layers.forEach { layer -> bounds += Rect().also { assertTrue(layer.getGlobalVisibleRect(it)) } }
                assertTrue("Track panels must retain a valid native Surface", viewport.video.holder.surface.isValid)
                if (trackedHolder == null) {
                    trackedHolder = viewport.video.holder
                    trackedHolder?.addCallback(surfaceCallback)
                }
                assertSame("Track panels must keep the same Surface holder", trackedHolder, viewport.video.holder)
                revision = surfaceRevision
            }
            return Geometry(layers.toList(), bounds.toList(), revision)
        }
        suspend fun choose(track: NativeTrack) {
            stage = "choose-${track.title}"
            tap(scrollToBounds(track.title, description = false, towardEnd = true))
            await("confirmed-${track.title}") {
                model.player.state.value.let { it.selectedSubtitle == track.id && it.pendingSubtitle == null }
            }
            assertTrue("Choosing a track must keep its home panel open", hasDescription("播放设置"))
            await("checked-${track.title}") { confirmedRow(track.title) }
        }
        try {
            activity.scenario.onActivity { host -> host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT }
            await("fixture-portrait") {
                var portrait = false
                activity.scenario.onActivity { host ->
                    val decor = host.window.decorView
                    portrait = host.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                        decor.width > 0 && decor.width < decor.height
                }
                portrait
            }
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "fixture", "fixture-only", "direct") }
            withTimeout(15_000) { model.state.first { it.connected && !it.busy } }
            main { model.open(ResourceRef("/fixture.mkv", "/fixture.mkv", "Owned track panel fixture.mkv", false, "video", media.size.toLong())) }
            withTimeout(25_000) { model.player.state.first {
                it.playing && it.firstFrameRendered && it.seekable && it.width == 640 && it.height == 360 &&
                    it.subtitles.count { track -> track.id >= 0 } == 8
            } }
            main { model.player.pause() }
            configuration.setWaitForIdleTimeout(0)
            stage = "portrait-fullscreen"
            click("竖屏全屏")
            absent("播放详情")
            val before = geometry()
            val generation = model.player.state.value.mediaGeneration
            val mediaSets = model.player.diagnosticSnapshot().count { it.action == PlaybackTraceAction.MEDIA_SET }
            stage = "subtitle-home"
            click("选择字幕")
            described("字幕调整")
            described("选择外挂字幕")
            described("选择本地字幕")
            absent("文本字幕字号")
            tap(find("关闭字幕") { it.text == "关闭字幕" && it.checkable })
            await("off-confirmed") { model.player.state.value.let { it.selectedSubtitle == -1 && it.pendingSubtitle == null } }
            await("off-checked") { confirmedRow("关闭字幕") }
            assertEquals(before, geometry())

            stage = "stale-track-error"
            main { model.player.subtitle(Int.MAX_VALUE) }
            find("轨道错误") { it.text == "轨道已变化，请重新选择" }
            await("stale-error-in-active-dialog") {
                instrumentation.uiAutomation.rootInActiveWindow?.let(::activePanelError)?.let { it.first && it.second } == true
            }
            assertTrue("Track errors must remain in the active dialog", hasDescription("播放设置"))
            assertEquals("A rejected stale choice must retain the confirmed track", -1, model.player.state.value.selectedSubtitle)
            assertNull(model.player.state.value.pendingSubtitle)

            stage = "adjust-ime"
            adjust()
            described("返回字幕轨道")
            tap(find("偏移秒数") { it.className == "android.widget.EditText" })
            keyboard(true)
            assertEquals("IME must overlay the same native layers", before, geometry())
            back("dismiss-ime")
            keyboard(false)
            assertTrue("First Back only dismisses IME", hasDescription("返回字幕轨道"))
            back("adjustments-to-home")
            await("back-to-home") { homeShown() }
            assertTrue(hasDescription("播放设置"))
            // Reopen the IME in this same dialog to begin a new dismissal cycle.
            stage = "adjust-ime-reopen"
            adjust()
            tap(find("偏移秒数") { it.className == "android.widget.EditText" })
            keyboard(true)
            back("dismiss-reopened-ime")
            keyboard(false)
            assertTrue("Reopened IME Back must keep the adjustments page", hasDescription("返回字幕轨道"))
            back("reopened-adjustments-to-home")
            await("reopened-back-to-home") { homeShown() }
            assertTrue(hasDescription("播放设置"))
            back("home-to-player")
            absent("播放设置")
            click("选择字幕")
            adjust()
            click("返回字幕轨道")
            await("button-back-to-home") { homeShown() }
            adjust()
            click("关闭播放设置")
            absent("播放设置")
            assertEquals(before, geometry())

            stage = "text-selection"
            click("选择字幕")
            main {
                observer = launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
                    model.player.state.collect { transitions += it.selectedSubtitle to it.pendingSubtitle }
                }
            }
            val text = model.player.state.value.subtitles.single { it.title.contains("NFB Text") }
            choose(text)
            assertTrue("A request stays pending until engine confirmation", transitions.any { it.first == -1 && it.second == text.id })
            assertTrue("Engine readback clears the pending request", transitions.any { it.first == text.id && it.second == null })
            adjust()
            assertTrue(described("文本字幕字号").enabled)
            assertTrue(described("文本字幕底边距").enabled)
            click("返回字幕轨道")

            choose(model.player.state.value.subtitles.single { it.title.contains("NFB ASS Attachment") })
            adjust()
            find("ASS 作者样式提示") { it.text?.contains("ASS / SSA 使用作者字体") == true }
            absent("文本字幕字号")
            click("返回字幕轨道")
            choose(model.player.state.value.subtitles.single { it.title.contains("NFB PGS1") })
            adjust()
            find("位图字幕提示") { it.text?.contains("位图字幕使用片源图像") == true }
            absent("文本字幕字号")
            click("关闭播放设置")
            absent("播放设置")
            assertEquals("Track navigation must not recreate or resize native layers", before, geometry())
            assertEquals(generation, model.player.state.value.mediaGeneration)
            assertEquals("Selecting tracks must not reopen media", mediaSets,
                model.player.diagnosticSnapshot().count { it.action == PlaybackTraceAction.MEDIA_SET })
            assertFalse("Track changes preserve the pause intent", model.player.state.value.playing)
            assertTrue(source.rawRequests.get() > 0)
            assertEquals(0, source.unexpected.get())
        } catch (error: Throwable) {
            OwnedUiTraceRule.trace("track-panel-failure=$stage ${trace.snapshot()}")
            if (InstrumentationRegistry.getArguments().getString("nfbTraceUi") == "true") {
                device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
                device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/track-panel-failure-${SystemClock.uptimeMillis()}.png")
            }
            throw error
        } finally { withContext(NonCancellable) {
            observer?.cancelAndJoin()
            configuration.setWaitForIdleTimeout(oldIdle)
            try {
                main {
                    trackedHolder?.removeCallback(surfaceCallback)
                    model.leavePlayer()
                }
                await("fixture-player-disposed") {
                    var disposed = false
                    activity.scenario.onActivity { host ->
                        disposed = findViewport(host.window.decorView) == null &&
                            host.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    }
                    disposed
                }
            } finally {
                try {
                    originalRequestedOrientation?.let { original ->
                        activity.scenario.onActivity { host -> host.requestedOrientation = original }
                    }
                } finally {
                    try { main { model.disconnect() } }
                    finally {
                        try { store.remove(profile) }
                        finally {
                            try { source.close() }
                            finally {
                                previousSession?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
                            }
                        }
                    }
                }
            }
        } }
    }
}

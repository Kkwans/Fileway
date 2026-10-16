package io.github.kkwans.nasfilebrowser

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.inspector.WindowInspector
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.BackendKind
import io.github.kkwans.nasfilebrowser.data.ClientDatabase
import io.github.kkwans.nasfilebrowser.data.CredentialVault
import io.github.kkwans.nasfilebrowser.data.ProfileStore
import io.github.kkwans.nasfilebrowser.data.ServerProfile
import io.github.kkwans.nasfilebrowser.player.PlaybackTraceAction
import io.github.kkwans.nasfilebrowser.player.MediaSubtitleLayer
import io.github.kkwans.nasfilebrowser.player.PlayerViewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.Date

/** Real Activity/dialog windows and native Surface geometry, not just HUD semantics. */
@RunWith(AndroidJUnit4::class)
class PlayerFullscreenTest {
    private val trace = OwnedUiTraceRule()
    private val activity = ActivityScenarioRule(MainActivity::class.java)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(trace).around(activity)

    @Test fun portraitAndLandscapeKeepTheirSurfaceWhenControlsOrSystemBarsAppear() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val media = instrumentation.context.assets.open("media/fixture.mkv").use { it.readBytes() }
        val source = NativePlaybackTest.Fixture(media)
        val database = ClientDatabase.get(instrumentation.targetContext)
        val previousSession = database.profiles().activeSession()
        val store = ProfileStore(database, CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "Fullscreen fixture", address = source.url))
        lateinit var model: ClientModel
        var originalRequestedOrientation: Int? = null
        activity.scenario.onActivity { host ->
            originalRequestedOrientation = host.requestedOrientation
            model = ViewModelProvider(host)[ClientModel::class.java]
        }
        suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
        val inputDriver = InstrumentationRegistry.getArguments().getString("nfbInputDriver", "automation")
        require(inputDriver in setOf("automation", "shell"))
        val touchTool = InstrumentationRegistry.getArguments().getString("nfbTouchTool", "finger")
        require(touchTool in setOf("finger", "unknown"))
        var inspectDown = InstrumentationRegistry.getArguments().getString("nfbInspectDown") == "true"
        fun rejectedInput(stage: String) {
            activity.scenario.onActivity { host -> OwnedUiTraceRule.trace("rejected-input=$stage " +
                "requested=${host.requestedOrientation} focus=${host.hasWindowFocus()} task=${host.taskId}") }
            val input = device.executeShellCommand("dumpsys input").lines()
            val start = input.indexOfFirst { it.contains("Input Dispatcher State:") }
            if (start >= 0) {
                input.drop(start).takeWhile { it.trim() != "Windows:" }.take(60)
                    .forEach { OwnedUiTraceRule.trace("input-state=${it.trim()}") }
                // Identify only potential interceptors above this activity.
                // These are input-window identities/regions, never view text.
                val windows = input.drop(start).dropWhile { it.trim() != "Windows:" }.drop(1)
                    .takeWhile { !it.contains("RecentQueue:") }
                    .filter { it.trim().firstOrNull()?.isDigit() == true && it.contains(": name=") }.take(14)
                for (line in windows) {
                    OwnedUiTraceRule.trace("input-above=${line.trim()}")
                    if (line.contains("nasfilebrowser.MainActivity")) break
                }
                input.filter { it.contains("io.github.kkwans.nasfilebrowser") }.take(12)
                    .forEach { OwnedUiTraceRule.trace("input-owned=${it.trim()}") }
                input.dropWhile { !it.trim().startsWith("RecentQueue:") }.drop(1)
                    .takeWhile { it.trim().isNotEmpty() }.filter { it.contains("MotionEvent") }.take(10)
                    .forEach { OwnedUiTraceRule.trace("input-recent=${it.trim()}") }
            }
        }
        fun physicalTap(bounds: Rect) {
            OwnedUiTraceRule.trace("fullscreen-tap-driver=$inputDriver tool=$touchTool bounds=$bounds")
            if (inputDriver == "shell") {
                // Controlled comparison of two real input-injection paths on OEM devices.
                // Both hit the actual button; no semantic click, retry or model callback.
                // executeShellCommand uses Runtime.exec, not a shell parser.
                // Completion is transport evidence only; callers still verify
                // delivered touches and the resulting UI/IME state.
                device.executeShellCommand("input touchscreen tap ${bounds.centerX()} ${bounds.centerY()}")
                return
            }
            OwnedTouchInput.tap(instrumentation, bounds,
                toolType = if (touchTool == "finger") MotionEvent.TOOL_TYPE_FINGER else MotionEvent.TOOL_TYPE_UNKNOWN,
                tracePrefix = "fullscreen", rejected = ::rejectedInput,
                afterDown = { if (inspectDown) { inspectDown = false; rejectedInput("DOWN-observed-before-UP") } })
        }
        fun traceWindow(stage: String) {
            activity.scenario.onActivity { host ->
                val decor = host.window.decorView
                OwnedUiTraceRule.trace("fullscreen=$stage orientation=${host.resources.configuration.orientation} " +
                    "requested=${host.requestedOrientation} decor=${decor.width}x${decor.height} " +
                    "focus=${host.hasWindowFocus()} display=${decor.display?.displayId} flags=${host.window.attributes.flags} " +
                    "generation=${model.player.state.value.mediaGeneration}")
            }
        }
        fun click(label: String) {
            val action = device.wait(Until.findObject(By.desc(label)), 5_000) ?: error("Missing player action $label")
            assertTrue("Player action must be enabled: $label", action.isEnabled)
            assertTrue("Player action must be clickable: $label", action.isClickable)
            val bounds = action.visibleBounds
            assertFalse("Player action must have visible bounds: $label", bounds.isEmpty)
            OwnedUiTraceRule.trace("fullscreen-action=$label bounds=$bounds")
            traceWindow("before-$label")
            val touches = trace.deliveredTouches()
            val inputState = "screen=${device.displayWidth}x${device.displayHeight}, on=${device.isScreenOn}, rotation=${device.displayRotation}"
            OwnedUiTraceRule.trace("fullscreen-input=$label $inputState")
            // Reuse the checked event path exercised by PlayerGestureControlsTest.
            // This targets the same actual button, never a semantic/model shortcut.
            val started = SystemClock.uptimeMillis()
            physicalTap(bounds)
            OwnedUiTraceRule.trace("fullscreen-tap=$label elapsedMs=${SystemClock.uptimeMillis() - started}")
            traceWindow("after-$label")
            if (label in listOf("竖屏全屏", "横屏全屏", "退出全屏")) {
                assertTrue("Fullscreen tap must reach the owned Activity: $label; ${trace.snapshot()}",
                    trace.deliveredTouches() >= touches + 2)
            }
        }
        fun detailsGone() {
            val gone = device.wait(Until.gone(By.desc("播放详情")), 5_000)
            if (!gone) {
                OwnedUiTraceRule.trace("fullscreen-entry-failure ${trace.snapshot()}")
                traceWindow("details-remain")
                device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
                device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/fullscreen-entry-failure-${SystemClock.uptimeMillis()}.png")
            }
            assertTrue("Fullscreen must hide details: exit=${device.hasObject(By.desc("退出全屏"))}, screen=${device.displayWidth}x${device.displayHeight}", gone)
        }
        fun capture(stage: String) {
            if (InstrumentationRegistry.getArguments().getString("nfbTraceUi") != "true") return
            val name = "player075-$stage-${SystemClock.uptimeMillis()}"
            device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/$name.png")
            OwnedUiTraceRule.trace("fullscreen-capture=$name")
        }
        fun findViewport(view: View): PlayerViewport? {
            if (view is PlayerViewport) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) findViewport(view.getChildAt(index))?.let { return it }
            return null
        }
        fun geometry(): Pair<PlayerViewport, List<Rect>> {
            lateinit var viewport: PlayerViewport
            lateinit var rectangles: List<Rect>
            activity.scenario.onActivity { host ->
                viewport = findViewport(host.window.decorView) ?: error("Native viewport missing")
                val layers = mutableListOf<View>(viewport, viewport.video, viewport.text)
                fun collect(view: View) {
                    if (view is MediaSubtitleLayer) layers += view
                    if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                }
                collect(viewport)
                rectangles = layers.map { view ->
                    Rect().also { assertTrue("Native view must remain visible", view.getGlobalVisibleRect(it)) }
                }
                assertTrue("Fullscreen must keep a valid native Surface", viewport.video.holder.surface.isValid)
            }
            return viewport to rectangles
        }
        var stage = "entry"
        suspend fun awaitStage(name: String, ready: suspend () -> Boolean) {
            stage = name
            OwnedUiTraceRule.trace("fullscreen-stage=$stage")
            try { withTimeout(5_000) {
                while (!ready()) delay(50)
            } } catch (failure: TimeoutCancellationException) {
                val windows = withContext(Dispatchers.Main) {
                    WindowInspector.getGlobalWindowViews().filter { it.isShown }.map { root ->
                        val insets = ViewCompat.getRootWindowInsets(root)
                        "${root.javaClass.simpleName}:focus=${root.hasWindowFocus()}," +
                            "status=${insets?.isVisible(WindowInsetsCompat.Type.statusBars())}," +
                            "navigation=${insets?.isVisible(WindowInsetsCompat.Type.navigationBars())}," +
                            "ime=${insets?.isVisible(WindowInsetsCompat.Type.ime())}"
                    }
                }
                if (!stage.startsWith("real-shade")) capture("failure-$stage")
                throw AssertionError("Fullscreen wait failed at $stage; windows=$windows; ${trace.snapshot()}", failure)
            }
        }
        suspend fun hiddenFocusedWindow(name: String, systemHidden: (() -> Boolean)? = null) {
            awaitStage(name) {
                val ownedHidden = withContext(Dispatchers.Main) {
                    val focused = WindowInspector.getGlobalWindowViews().filter { it.isShown && it.hasWindowFocus() }
                    focused.isNotEmpty() && focused.all { root ->
                        ViewCompat.getRootWindowInsets(root)?.let {
                            !it.isVisible(WindowInsetsCompat.Type.statusBars()) && !it.isVisible(WindowInsetsCompat.Type.navigationBars())
                        } == true
                    }
                }
                ownedHidden && (systemHidden?.invoke() ?: true)
            }
        }
        suspend fun checkFullscreen() {
            hiddenFocusedWindow("entry-hidden")
            fun clockValue(node: AccessibilityNodeInfo): String? {
                try {
                    if (!node.refresh()) return null
                    if (node.contentDescription?.toString() == "设备时间")
                        return AccessibilityNodeInfoCompat.wrap(node).stateDescription?.toString()
                    for (index in 0 until node.childCount) {
                        node.getChild(index)?.let(::clockValue)?.let { return it }
                    }
                    return null
                } finally { node.recycle() }
            }
            awaitStage("device-clock-confirmed") {
                val expected = DateFormat.getTimeFormat(instrumentation.targetContext).format(Date())
                instrumentation.uiAutomation.rootInActiveWindow?.let(::clockValue) == expected
            }
            val clockBounds = (device.findObject(By.desc("设备时间")) ?: error("Device clock missing")).visibleBounds
            val speedBounds = (device.findObject(By.desc("实际网络下载速度")) ?: error("Throughput label missing")).visibleBounds
            assertFalse("Device time and throughput must not overlap", Rect.intersects(clockBounds, speedBounds))
            val timeline = (device.findObject(By.desc("播放进度")) ?: error("Timeline missing")).visibleBounds
            val play = (device.findObject(By.desc("开始播放")) ?: error("Play control missing")).visibleBounds
            assertFalse("Timeline must occupy a separate row from transport", Rect.intersects(timeline, play))
            assertFalse(device.hasObject(By.desc("上一个视频")))
            assertFalse(device.hasObject(By.desc("后退十秒")))
            assertFalse(device.hasObject(By.desc("快进十秒")))
            val before = geometry()
            assertTrue("Fullscreen viewport must occupy the full screen height", before.second.first().height() >= device.displayHeight * .98f)
            val generation = model.player.state.value.mediaGeneration
            val mediaSets = model.player.diagnosticSnapshot().count { it.action == PlaybackTraceAction.MEDIA_SET }
            val direction = if (device.displayWidth > device.displayHeight) "landscape" else "portrait"
            var displayId = -1
            activity.scenario.onActivity { host -> displayId = checkNotNull(host.window.decorView.display).displayId }
            val screenWidth = device.displayWidth
            val screenHeight = device.displayHeight
            val clientTransient = device.executeShellCommand("getprop persist.wm.debug.client_transient").trim()
            OwnedUiTraceRule.trace("fullscreen-transient-mode=$clientTransient display=$displayId")
            var lastSystemBars: SystemStatusBarSnapshot? = null
            fun systemStatusBar(): SystemStatusBarSnapshot {
                // Read raw display insets, before WM applies this app's fake-control visibility.
                // Keep only structural bar state: never persist a raw dump or notification content.
                val snapshot = checkNotNull(parseSystemStatusBarSnapshot(
                    device.executeShellCommand("dumpsys window displays"), displayId, screenWidth, screenHeight,
                )) { "Unsupported system-bar dump for display=$displayId size=${screenWidth}x$screenHeight" }
                if (snapshot != lastSystemBars) {
                    OwnedUiTraceRule.trace("fullscreen-system-status=$direction $snapshot")
                    lastSystemBars = snapshot
                }
                return snapshot
            }
            capture("$direction-controls")
            val touches = trace.deliveredTouches()
            physicalTap(Rect(device.displayWidth / 2 - 1, device.displayHeight / 2 - 1,
                device.displayWidth / 2 + 1, device.displayHeight / 2 + 1))
            assertTrue("Ordinary touch must reach the owned Activity", trace.deliveredTouches() >= touches + 2)
            hiddenFocusedWindow("ordinary-touch-hidden")
            assertEquals("Ordinary touch must not resize video", before, geometry())
            for (panel in listOf("选择字幕", "播放速度")) {
                click(panel)
                assertTrue(device.wait(Until.hasObject(By.desc("播放设置")), 5_000))
                hiddenFocusedWindow("panel-$panel-hidden")
                assertEquals("A focused settings window must not resize video", before, geometry())
                capture("$direction-${if (panel == "选择字幕") "subtitles" else "speed"}")
                if (panel == "选择字幕") {
                    click("字幕调整")
                    assertTrue(device.wait(Until.hasObject(By.desc("返回字幕轨道")), 5_000))
                    hiddenFocusedWindow("subtitle-adjustments-hidden")
                    assertEquals("Subtitle adjustments must preserve native layers", before, geometry())
                }
                if (panel == "播放速度") {
                    assertFalse("Speed presets must not open a keyboard", device.hasObject(By.clazz("android.widget.EditText")))
                    val edit = device.wait(Until.findObject(By.text("自定义播放速度")), 5_000)
                        ?: error("Custom speed entry missing")
                    edit.click()
                    assertTrue(device.wait(Until.hasObject(By.desc("返回播放速度")), 5_000))
                }
                val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
                    ?: error(if (panel == "选择字幕") "Subtitle offset input missing" else "Custom rate input missing")
                val bounds = field.visibleBounds
                physicalTap(bounds)
                suspend fun keyboard(shown: Boolean) = awaitStage("ime-$shown") {
                    withContext(Dispatchers.Main) {
                        WindowInspector.getGlobalWindowViews().any { view ->
                            view.isShown && view.hasWindowFocus() &&
                                ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == shown
                        }
                    }
                }
                keyboard(true)
                assertEquals("IME must not resize any video/subtitle layer", before, geometry())
                device.pressBack()
                keyboard(false)
                assertTrue("Back closes the keyboard before its panel", device.hasObject(By.desc("播放设置")))
                assertEquals(before, geometry())
                if (panel == "选择字幕") {
                    assertTrue("IME Back must keep the adjustments page", device.hasObject(By.desc("返回字幕轨道")))
                    device.pressBack()
                    awaitStage("subtitle-home-after-back") {
                        val nodes = freshAccessibilityBounds(instrumentation, Rect(0, 0, device.displayWidth, device.displayHeight))
                        nodes != null && nodes.any { it.visible && it.description == "播放设置" } &&
                            nodes.none { it.visible && it.description == "返回字幕轨道" } &&
                            nodes.any { it.visible && it.description == "字幕调整" }
                    }
                    hiddenFocusedWindow("subtitle-home-hidden")
                    assertEquals(before, geometry())
                }
                if (panel == "播放速度") {
                    assertTrue("IME Back must keep the speed editor", device.hasObject(By.desc("返回播放速度")))
                    device.pressBack()
                    assertTrue(device.wait(Until.hasObject(By.text("自定义播放速度")), 5_000))
                    assertFalse("Returning to presets removes the editor", device.hasObject(By.clazz("android.widget.EditText")))
                    assertEquals(before, geometry())
                }
                click("关闭播放设置")
                assertTrue(device.wait(Until.gone(By.desc("播放设置")), 5_000))
                hiddenFocusedWindow("panel-$panel-closed")
                assertEquals(before, geometry())
            }
            for (option in listOf("媒体系统音量", "窗口亮度", "播放来源")) {
                click("更多播放选项")
                assertTrue(device.wait(Until.hasObject(By.desc("播放设置")), 5_000))
                click(option)
                val content = when (option) {
                    "媒体系统音量" -> By.text(java.util.regex.Pattern.compile("与设备媒体音量键同步.*|此设备使用固定音量.*"))
                    "窗口亮度" -> By.text("恢复系统亮度")
                    else -> By.text("播放方式")
                }
                assertTrue("More must open its existing $option content", device.wait(Until.hasObject(content), 5_000))
                hiddenFocusedWindow("more-$option-hidden")
                assertEquals("More settings must not resize video", before, geometry())
                click("关闭播放设置")
                assertTrue(device.wait(Until.gone(By.desc("播放设置")), 5_000))
                hiddenFocusedWindow("more-$option-closed")
            }
            // Exercise real inset changes as well as hidden bars. Manual shade
            // gestures remain a separate real-device interaction acceptance.
            activity.scenario.onActivity { host ->
                WindowCompat.getInsetsController(host.window, host.window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
            awaitStage("system-bars-shown") {
                var shown = false
                activity.scenario.onActivity { host -> shown = ViewCompat.getRootWindowInsets(host.window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars()) == true }
                shown && systemStatusBar().visible
            }
            assertEquals("Visible transient system bars must overlay the same Surface", before, geometry())
            activity.scenario.onActivity { host ->
                WindowCompat.getInsetsController(host.window, host.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            }
            hiddenFocusedWindow("system-bars-hidden") { systemStatusBar().hidden }
            assertEquals(before, geometry())
            // A real top-edge gesture must remain available. Do not capture the
            // owner's notification shade; inspect only our native geometry.
            var cutouts = emptyList<Rect>()
            activity.scenario.onActivity { host ->
                val insets = checkNotNull(ViewCompat.getRootWindowInsets(host.window.decorView)) {
                    "Current Activity insets missing for top-edge gesture"
                }
                cutouts = insets.displayCutout?.boundingRects.orEmpty().map { Rect(it) }
            }
            val width = device.displayWidth
            val shadeX = listOf(width / 4, 3 * width / 4, width / 2)
                .firstOrNull { x -> cutouts.none { it.contains(x, 1) } }
                ?: error("No top-edge gesture candidate outside display cutouts: $cutouts")
            OwnedUiTraceRule.trace("fullscreen-shade-input x=$shadeX y=1 cutouts=$cutouts")
            assertTrue(device.swipe(shadeX, 1, shadeX, device.displayHeight / 5, 20))
            awaitStage("real-shade-revealed") {
                // A transient overlay deliberately leaves app root insets hidden and may keep focus.
                // The same raw source was calibrated visible -> hidden immediately before this swipe.
                systemStatusBar().visible
            }
            assertEquals("A real system shade gesture must overlay the same native layers", before, geometry())
            if (device.currentPackageName != instrumentation.targetContext.packageName) device.pressBack()
            hiddenFocusedWindow("real-shade-dismissed") { systemStatusBar().hidden }
            assertEquals(before, geometry())
            assertTrue("Closing system UI must keep fullscreen", device.hasObject(By.desc("退出全屏")))
            assertEquals(generation, model.player.state.value.mediaGeneration)
            assertEquals("Fullscreen and settings must not reopen media", mediaSets,
                model.player.diagnosticSnapshot().count { it.action == PlaybackTraceAction.MEDIA_SET })
            assertFalse("Layout changes must preserve the pause intent", model.player.state.value.playing)
        }
        try {
            // Establish only this Activity's initial direction without changing
            // the display's global rotation policy or entering fullscreen.
            activity.scenario.onActivity { host ->
                host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            awaitStage("fixture-portrait") {
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
            main { model.open(model.state.value.files.single()) }
            withTimeout(15_000) { model.player.state.first { it.durationMs > 0 && it.playing && it.canSavePosition } }
            main { model.player.pause() }
            click("竖屏全屏")
            detailsGone()
            checkFullscreen()
            click("退出全屏")
            assertTrue(device.wait(Until.hasObject(By.desc("播放详情")), 5_000))
            click("横屏全屏")
            detailsGone()
            withTimeout(5_000) { while (device.displayWidth <= device.displayHeight) delay(50) }
            checkFullscreen()
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.desc("播放详情")), 5_000))
            assertNotNull("Back exits fullscreen before leaving the video", model.state.value.selected)
        } finally {
            withContext(NonCancellable) {
                try {
                    main { model.disconnect() }
                    // PlayerScreen restores the direction captured at composition.
                    // Let that disposal finish before restoring the pre-fixture value.
                    awaitStage("fixture-player-disposed") {
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
                        try {
                            store.remove(profile)
                        } finally {
                            try {
                                source.close()
                            } finally {
                                previousSession?.let {
                                    if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Raw WM display state; no app-adjusted InsetsState, view text, icons or notification payloads. */
private data class SystemStatusBarSnapshot(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val barBottom: Int,
    val visible: Boolean,
    val transient: Boolean,
) {
    val hidden: Boolean get() = !visible && !transient
}

private fun parseSystemStatusBarSnapshot(
    dump: String, displayId: Int, width: Int, height: Int,
): SystemStatusBarSnapshot? {
    val lines = dump.lineSequence().map(String::trim).toList()
    val displayHeader = Regex("""Display: mDisplayId=(\d+)(?:\s.*)?""")
    val displays = lines.mapIndexedNotNull { index, line ->
        displayHeader.matchEntire(line)?.let { index to it.groupValues[1].toIntOrNull() }
    }
    val target = displays.singleOrNull { it.second == displayId } ?: return null
    val end = displays.firstOrNull { it.first > target.first }?.first ?: lines.size
    val display = lines.subList(target.first + 1, end)
    val rawStart = display.indexOf("WindowInsetsStateController").takeIf { it >= 0 } ?: return null
    val rawEnd = (rawStart + 1 until display.size).firstOrNull { display[it] == "Control map:" } ?: return null
    val raw = display.subList(rawStart + 1, rawEnd)
    if ("mDisplayFrame=Rect(0, 0 - $width, $height)" !in raw) return null
    val status = raw.filter { it.startsWith("InsetsSource id=") && " type=statusBars " in it }
        .singleOrNull() ?: return null
    val frame = Regex("""\bframe=\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
        .find(status)?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: return null } ?: return null
    if (frame[0] != 0 || frame[1] != 0 || frame[2] != width || frame[3] !in 2..height) return null
    val visible = Regex("""\bvisible=(true|false)\b""").find(status)?.groupValues?.get(1)
        ?.let { it == "true" } ?: return null
    val policyStart = display.indexOf("InsetsPolicy").takeIf { it > rawEnd } ?: return null
    val policy = display.drop(policyStart + 1)
    if (policy.none { it.startsWith("status: WINDOW_STATE_") }) return null
    val transient = policy.firstOrNull { it.startsWith("mShowingTransientTypes=") }
        ?.substringAfter('=')?.split(' ')?.contains("statusBars") ?: false
    return SystemStatusBarSnapshot(displayId, width, height, frame[3], visible, transient)
}

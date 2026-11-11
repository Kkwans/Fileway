package io.github.kkwans.nasfilebrowser

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.kkwans.fileway.remote.RemoteConnectionState
import io.github.kkwans.fileway.remote.RemotePhase
import io.github.kkwans.fileway.remote.RemoteSnapshot
import io.github.kkwans.fileway.remote.TvDescriptor
import io.github.kkwans.fileway.remote.TvRemoteClient
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.TvControlModel
import io.github.kkwans.nasfilebrowser.ui.ClientTheme
import io.github.kkwans.nasfilebrowser.ui.TvControlScreen
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic UI-only state in the real phone Activity; no TV or media commands. */
@RunWith(AndroidJUnit4::class)
class TvRemoteUiVisualTest {
    @Test fun portraitAndLandscapeRemoteWithSyntheticSnapshot() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit synthetic UI opt-in required", args.getString("filewaySyntheticUi") == "true")
        val emulator = Build.FINGERPRINT.contains("generic", true) ||
            Build.PRODUCT.contains("sdk_gphone", true) || Build.HARDWARE.contains("goldfish", true) ||
            Build.HARDWARE.contains("ranchu", true)
        assumeTrue("This UI-only journey requires an API 35 emulator", Build.VERSION.SDK_INT == 35 && emulator)
        val systemFontTest = args.getString("filewaySystemFontScaleTest") == "true"
        assumeTrue("System font changes require an explicitly task-owned emulator",
            !systemFontTest || args.getString("filewayOwnedEmulator") == "true")

        // Refuse before starting the Activity if this is not a fresh app install.
        // In particular, do not inspect, reuse, replace or delete existing records.
        for (name in listOf("nfb-client.db", "fileway-remote.db")) {
            assertFalse("Fresh isolated app required; existing $name was preserved", context.getDatabasePath(name).exists())
        }

        val device = UiDevice.getInstance(instrumentation)
        val originalFontSetting = if (systemFontTest) device.executeShellCommand("settings get system font_scale").trim() else null
        require(originalFontSetting == null || originalFontSetting == "null" ||
            originalFontSetting.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) { "Cannot safely restore system font scale" }
        val originalEffectiveFontScale = context.resources.configuration.fontScale
        var systemFontChanged = false
        var systemFontRestored: Boolean? = null
        var activityFontScale: Float? = null
        var dialogFontScale: Float? = null
        var dialogOwnerFontScale: Float? = null
        val base = requireNotNull(context.getExternalFilesDir(null))
        val evidence = File(base, "synthetic-tv-remote-ui-${SystemClock.elapsedRealtimeNanos()}")
        check(evidence.mkdir()) { "Cannot create task-owned UI evidence directory" }
        val checks = mutableListOf<String>()
        val screenshots = mutableListOf<String>()
        var stage = "launch"
        var locator = "none"
        var status = "RUNNING"
        var failureDetail: String? = null
        val fixtureName = "UI 样本 - 午夜列车.mp4"
        val fixtureDeviceName = "UI 样本电视"
        val darkTheme = mutableStateOf(false)
        var scenario: ActivityScenario<MainActivity>? = null
        var control: TvControlModel? = null
        var fixtureFlow: MutableStateFlow<RemoteConnectionState>? = null
        var originalConnection: RemoteConnectionState? = null
        val sentinel = Any()
        var sentinelInstalled = false

        fun report() {
            File(evidence, "result.json").writeText(JSONObject()
                .put("status", status)
                .put("stage", stage)
                .put("locator", locator)
                .put("scope", "synthetic UI-only; real MainActivity/Compose; no TV, account, pairing, or media transport")
                .put("api", Build.VERSION.SDK_INT)
                .put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
                .put("display", "${device.displayWidth}x${device.displayHeight}")
                .put("density", context.resources.displayMetrics.density.toDouble())
                .put("systemFontScaleTest", systemFontTest)
                .put("originalFontSetting", originalFontSetting ?: JSONObject.NULL)
                .put("originalEffectiveFontScale", originalEffectiveFontScale.toDouble())
                .put("activityFontScale", activityFontScale?.toDouble() ?: JSONObject.NULL)
                .put("dialogFontScale", dialogFontScale?.toDouble() ?: JSONObject.NULL)
                .put("dialogComposeOwnerFontScale", dialogOwnerFontScale?.toDouble() ?: JSONObject.NULL)
                .put("systemFontRestored", systemFontRestored ?: JSONObject.NULL)
                .put("checks", JSONArray(checks))
                .put("screenshots", JSONArray(screenshots))
                .put("activitySystemBars", "MainActivity light-theme edge-to-edge configuration; dialog window remains production-owned")
                .put("visibleConfirmText", "Review portrait/landscape screenshot: child text is cleared from accessibility semantics; accessible name is 确认")
                .put("failure", failureDetail ?: JSONObject.NULL).toString(2))
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stream", "SYNTHETIC_TV_UI status=$status stage=$stage evidence=${evidence.absolutePath}\n")
            })
        }

        fun screen() = Rect(0, 0, device.displayWidth, device.displayHeight)
        fun find(label: String, description: Boolean, clickable: Boolean): FreshAccessibilityNodeBounds? {
            if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
            val nodes = freshAccessibilityBounds(instrumentation, screen()) ?: return null
            val leaves = nodes.filter { it.visible && it.enabled &&
                (if (description) it.description == label else it.text == label) }
            for (leaf in leaves) {
                if (!clickable) return leaf
                if (leaf.clickable) return leaf
                val owner = nodes.filter { it.visible && it.enabled && it.clickable && it.bounds.contains(leaf.bounds) }
                    .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }
                if (owner != null) return owner
            }
            return null
        }
        fun diagnosis(label: String, description: Boolean): String {
            val nodes = freshAccessibilityBounds(instrumentation, screen()) ?: return "active tree unavailable or stale"
            val leaves = nodes.filter { if (description) it.description == label else it.text == label }
            val related = nodes.filter { node -> leaves.any { node.bounds.contains(it.bounds) } }
            return "matches=${leaves.size}; related=" + related.takeLast(12).joinToString { node ->
                "{bounds=${node.bounds},visible=${node.visible},enabled=${node.enabled},clickable=${node.clickable}}"
            }
        }
        suspend fun await(label: String, description: Boolean = false, clickable: Boolean = false): FreshAccessibilityNodeBounds {
            locator = "label=$label description=$description clickable=$clickable"
            return try { withTimeout(8_000) {
                var result = find(label, description, clickable)
                while (result == null) { delay(75); result = find(label, description, clickable) }
                requireNotNull(result)
            } } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("$stage timed out locating $locator; ${diagnosis(label, description)}", timeout)
            }
        }
        data class SelectedTab(val bounds: Rect, val focusable: Boolean)
        var selectedTabObservation = "no matching visible node"
        fun findSelectedTab(label: String): SelectedTab? {
            if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
            val windowId = root.windowId
            if (root.packageName?.toString() != context.packageName || windowId < 0) {
                root.recycle()
                return null
            }
            fun visit(node: AccessibilityNodeInfo): SelectedTab? {
                try {
                    if (!node.refresh() || node.windowId != windowId || !node.isVisibleToUser) return null
                    if (node.text?.toString() == label) {
                        val observed = mutableListOf<String>()
                        var ancestor = node.parent
                        while (ancestor != null) {
                            val parent = ancestor
                            try {
                                val bounds = Rect().also(parent::getBoundsInScreen)
                                observed += "{bounds=$bounds,selected=${parent.isSelected},focusable=${parent.isFocusable},clickable=${parent.isClickable},enabled=${parent.isEnabled},visible=${parent.isVisibleToUser}}"
                                selectedTabObservation = observed.joinToString()
                                if (parent.isSelected && parent.isEnabled && parent.isVisibleToUser) {
                                    if (bounds.intersect(screen())) return SelectedTab(bounds, parent.isFocusable)
                                }
                                ancestor = parent.parent
                            } finally { parent.recycle() }
                        }
                    }
                    for (index in 0 until node.childCount) {
                        node.getChild(index)?.let { child -> visit(child)?.let { return it } }
                    }
                    return null
                } finally { node.recycle() }
            }
            return visit(root)
        }
        suspend fun selectedTab(label: String): SelectedTab {
            locator = "selected tab=$label"
            selectedTabObservation = "no matching visible node"
            val selected = try { withTimeout(8_000) {
                var result = findSelectedTab(label)
                while (result == null) { delay(75); result = findSelectedTab(label) }
                requireNotNull(result)
            } } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("$stage timed out locating $locator; ancestors=$selectedTabObservation; ${diagnosis(label, false)}", timeout)
            }
            val minimum = 48f * context.resources.displayMetrics.density - 2f
            assertTrue("$stage: selected $label is not focusable", selected.focusable)
            assertTrue("$stage: selected $label is smaller than 48dp or clipped: ${selected.bounds}",
                selected.bounds.width() >= minimum && selected.bounds.height() >= minimum)
            return selected
        }
        suspend fun touchTarget(label: String, description: Boolean = false): FreshAccessibilityNodeBounds {
            val value = await(label, description, clickable = true)
            val minimum = 48f * context.resources.displayMetrics.density - 2f
            assertTrue("$stage: $label target is smaller than 48dp or clipped: ${value.bounds}",
                value.bounds.width() >= minimum && value.bounds.height() >= minimum)
            return value
        }
        fun activeWindowId(): Int? {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
            return try { root.windowId.takeIf { it >= 0 } } finally { root.recycle() }
        }
        suspend fun tap(label: String, description: Boolean = false) {
            val target = touchTarget(label, description)
            val activeBefore = activeWindowId()
            val current = find(label, description, clickable = true)
            val activeAfter = activeWindowId()
            check(activeBefore != null && activeBefore == activeAfter && current?.bounds == target.bounds) {
                "$stage: target or window changed before tap: $label"
            }
            check(device.click(target.bounds.centerX(), target.bounds.centerY())) { "$stage: tap failed for $label" }
        }
        fun capture(name: String) {
            check(device.takeScreenshot(File(evidence, name))) { "$stage: screenshot failed" }
            screenshots += name
            report()
        }
        fun assertStatusBarContrast(name: String, dark: Boolean) {
            val bitmap = requireNotNull(BitmapFactory.decodeFile(File(evidence, name).absolutePath))
            try {
                val density = context.resources.displayMetrics.density
                val width = minOf(bitmap.width, (96f * density).toInt())
                val height = minOf(bitmap.height, (24f * density).toInt())
                val background = bitmap.getPixel(bitmap.width / 2, minOf(height - 1, (3f * density).toInt()))
                fun brightness(color: Int) = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
                assertTrue("$stage: status bar background does not match theme",
                    if (dark) brightness(background) < 100 else brightness(background) > 180)
                var foreground = 0
                for (y in 0 until height) for (x in 0 until width) {
                    val value = brightness(bitmap.getPixel(x, y))
                    if (if (dark) value > 175 else value < 120) foreground++
                }
                assertTrue("$stage: status bar icons are not legible against their actual background", foreground >= 10)
            } finally {bitmap.recycle()}
        }
        fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(target)
        fun assertSyntheticOnly() {
            val model = requireNotNull(control)
            assertNull("Synthetic test must never have a TV transport", field(model.client, "socket"))
            assertNull("Synthetic test must never create a transport client", field(model.client, "client"))
            assertNull("Synthetic test must not start discovery", field(model, "scan"))
            assertEquals(RemotePhase.CONNECTED, model.connection.value.phase)
            assertEquals(fixtureDeviceName, model.connection.value.device?.name)
            assertEquals(fixtureName, model.connection.value.snapshot?.content?.optJSONObject("media")?.optString("name"))
        }
        suspend fun orientation(expected: Int) {
            locator = "orientation=$expected"
            var current = Configuration.ORIENTATION_UNDEFINED
            try { withTimeout(8_000) {
                while (current != expected) {
                    requireNotNull(scenario).onActivity { current = it.resources.configuration.orientation }
                    if (current != expected) delay(75)
                }
            } } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("$stage timed out waiting for $locator; actual=$current", timeout)
            }
        }

        try {
            report()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            if (systemFontTest) {
                stage = "system-font-configuration"
                // The flag above is allowed only on a fresh task-owned emulator.
                // Mark first so a partially successful shell command is restored too.
                systemFontChanged = true
                device.executeShellCommand("settings put system font_scale 1.8")
                assertEquals(1.8f, device.executeShellCommand("settings get system font_scale").trim().toFloat(), 0.01f)
                withTimeout(12_000) {
                    while (true) {
                        var actual = 0f
                        requireNotNull(scenario).onActivity { actual = it.resources.configuration.fontScale }
                        if (kotlin.math.abs(actual - 1.8f) < 0.01f) {
                            activityFontScale = actual
                            break
                        }
                        delay(75)
                    }
                }
            }
            requireNotNull(scenario).onActivity { host ->
                host.requestedOrientation = if (systemFontTest) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                val phone = ViewModelProvider(host)[ClientModel::class.java]
                val model = ViewModelProvider(host)[TvControlModel::class.java]
                control = model
                check(model.connection.value.phase == RemotePhase.OFFLINE && model.connection.value.device == null)
                check(field(model.client, "socket") == null && field(model, "scan") == null)
                @Suppress("UNCHECKED_CAST")
                val views = field(model, "views") as MutableSet<Any>
                check(views.isEmpty()) { "A real TV view already owns this model" }
                // A second view owner prevents LifecycleStartEffect from launching discovery.
                views.add(sentinel)
                sentinelInstalled = true
                @Suppress("UNCHECKED_CAST")
                val flow = TvRemoteClient::class.java.getDeclaredField("mutable").apply { isAccessible = true }
                    .get(model.client) as MutableStateFlow<RemoteConnectionState>
                fixtureFlow = flow
                originalConnection = flow.value
                val descriptor = TvDescriptor("synthetic-ui-id", fixtureDeviceName, "", "", 0, "")
                val playback = JSONObject().put("playing", true).put("positionMs", 12_000L)
                    .put("durationMs", 120_000L).put("bufferedMs", 24_000L).put("seekable", true)
                    .put("rate", 1.0).put("volume", 50).put("selectedSubtitle", 1)
                    .put("subtitles", JSONArray().put(JSONObject().put("id", 1).put("title", "简体中文")
                        .put("codec", "srt").put("language", "zh")))
                    .put("subtitleAppearance", JSONObject().put("scale", 1.0).put("bottomPadding", .08)
                        .put("backgroundOpacity", 0.0).put("area", "PICTURE").put("fontId", "subtitle-default"))
                val content = JSONObject().put("media", JSONObject().put("name", fixtureName))
                    .put("playback", playback).put("queue", JSONObject().put("index", 0).put("count", 2))
                    .put("preferences", JSONObject().put("seekStepSeconds", 10))
                val capabilities = JSONObject().put("subtitleBrowseServerCursor", 1).put("subtitleBrowseLocal", 1)
                val snapshot = RemoteSnapshot("synthetic-ui-only", 1, 2, 1, "VIDEO", capabilities, content)
                flow.value = RemoteConnectionState(RemotePhase.CONNECTED, descriptor, snapshot)
                val open = mutableStateOf(true)
                // Replacing MainActivity's root also disposes its system-bar
                // effect. Apply the same production light-theme settings before
                // the real dialog window is created, and leave that window alone.
                val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                host.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                host.window.isNavigationBarContrastEnforced = false
                WindowCompat.getInsetsController(host.window, host.window.decorView).apply {
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
                host.setContent {
                    ClientTheme(darkTheme = darkTheme.value) {
                        if (open.value) TvControlScreen(phone, { open.value = false }, controlModel = model)
                        else Text("UI 测试已关闭")
                    }
                }
            }
            if (systemFontTest) {
                stage = "landscape-login-system-font"
                orientation(Configuration.ORIENTATION_LANDSCAPE)
                val mediaState = requireNotNull(fixtureFlow).value
                val loginState = mediaState.copy(snapshot = requireNotNull(mediaState.snapshot)
                    .copy(scene = "LOGIN", content = JSONObject()))
                requireNotNull(scenario).onActivity { requireNotNull(fixtureFlow).value = loginState }
                // Dialog has its own Compose owner. Inspect that owner, not the
                // Activity's outer CompositionLocal, to prove the actual density.
                withTimeout(8_000) {
                    while (dialogOwnerFontScale == null) {
                        requireNotNull(scenario).onActivity {
                            fun findView(root: View, predicate: (View) -> Boolean): View? {
                                if (predicate(root)) return root
                                if (root is ViewGroup) for (index in 0 until root.childCount) {
                                    findView(root.getChildAt(index), predicate)?.let { return it }
                                }
                                return null
                            }
                            val dialog = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { root ->
                                findView(root) { it is DialogWindowProvider }
                            }
                            val owner = dialog?.let { findView(it) { view ->
                                view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView"
                            } }
                            if (dialog != null && owner != null) {
                                dialogFontScale = dialog.context.resources.configuration.fontScale
                                val getter = owner.javaClass.getMethod("getDensity").apply { isAccessible = true }
                                dialogOwnerFontScale = (getter.invoke(owner) as Density).fontScale
                            }
                        }
                        if (dialogOwnerFontScale == null) delay(75)
                    }
                }
                assertEquals("Dialog window must use the system font scale", 1.8f, requireNotNull(dialogFontScale), 0.01f)
                assertEquals("Dialog Compose owner must use the system font scale", 1.8f, requireNotNull(dialogOwnerFontScale), 0.01f)
                var login: FreshAccessibilityNodeBounds? = null
                withTimeout(8_000) {
                    while (login == null) {
                        login = freshAccessibilityBounds(instrumentation, screen())?.firstOrNull {
                            it.visible && it.text == "用手机当前账号登录 TV"
                        }
                        if (login == null) delay(75)
                    }
                }
                val dpad = listOf("上", "下", "左", "右", "确认").map { touchTarget(it, description = true) }
                val actions = listOf("返回", "首页", "键盘").map { touchTarget(it) }
                val targets = dpad + actions
                for (i in targets.indices) for (j in i + 1 until targets.size) {
                    assertFalse("System large-font targets overlap: ${targets[i].bounds} and ${targets[j].bounds}",
                        Rect.intersects(targets[i].bounds, targets[j].bounds))
                }
                assertTrue("Login entry must remain to the right of the D-pad",
                    requireNotNull(login).bounds.left >= dpad.maxOf { it.bounds.right })
                capture("landscape-login-system-font-1.8.png")
                for (label in listOf("上", "下", "左", "右", "确认")) tap(label, description = true)
                assertNull("Synthetic test must never have a TV transport", field(requireNotNull(control).client, "socket"))
                assertNull("Synthetic test must never create a transport client", field(requireNotNull(control).client, "client"))
                checks += "system 1.8 font scale reached the Activity, dialog window and dialog Compose owner"
                checks += "all five landscape login D-pad targets are visible, at least 48dp, non-overlapping and receive touch input"
                checks += "login entry and remote actions remain visible without a TV transport"
                status = "PASS_UI_ONLY"
                return@runBlocking
            }
            orientation(Configuration.ORIENTATION_PORTRAIT)
            stage = "portrait-playback"
            await("TV 播放进度", description = true)
            await(fixtureName)
            capture("portrait-playback.png")
            stage = "portrait-playback-device-target"
            touchTarget("设备")
            stage = "portrait-playback-selected-tab"
            selectedTab("播放")
            stage = "portrait-playback-inactive-remote-tab"
            touchTarget("遥控")
            checks += "portrait playback tab is selected and focusable; device and inactive remote tab have 48dp targets"
            assertSyntheticOnly()

            stage = "portrait-subtitle-options"
            tap("字幕")
            await("字幕调整")
            await("TV 当前服务器")
            await("TV 本机字幕")
            capture("portrait-subtitle-options.png")
            tap("字幕调整")
            await("字号")
            capture("portrait-subtitle-appearance.png")
            assertTrue(device.pressBack())
            await("TV 当前服务器")
            assertTrue(device.pressBack())
            await("TV 播放进度", description = true)
            checks += "subtitle source entries and appearance sheet are visible without a TV transport"
            assertSyntheticOnly()

            stage = "portrait-remote"
            tap("遥控")
            selectedTab("遥控")
            await("确认", description = true, clickable = true)
            await(fixtureName)
            await("暂停 TV 播放", description = true, clickable = true)
            await("键盘", clickable = true)
            capture("portrait-remote.png")
            assertStatusBarContrast("portrait-remote.png", dark = false)
            for (label in listOf("上", "下", "左", "右", "确认")) touchTarget(label, description = true)
            touchTarget("暂停 TV 播放", description = true)
            touchTarget("返回")
            touchTarget("首页")
            touchTarget("键盘")
            checks += "portrait D-pad, accessible confirm, fixture name and quick action are visible with 48dp targets"
            assertSyntheticOnly()

            stage = "portrait-tabs-playback-switch"
            tap("播放")
            await("TV 播放进度", description = true)
            selectedTab("播放")
            stage = "portrait-tabs-remote-switch"
            tap("遥控")
            selectedTab("遥控")
            await("确认", description = true, clickable = true)
            checks += "playback and remote tabs switch in both directions"
            assertSyntheticOnly()

            stage = "portrait-device-sheet"
            tap("设备")
            await("当前 TV")
            await(fixtureDeviceName)
            capture("portrait-device-sheet.png")
            touchTarget("关闭当前 TV", description = true)
            tap("关闭当前 TV", description = true)
            await("确认", description = true, clickable = true)
            checks += "device sheet opens and closes without disconnecting"
            assertSyntheticOnly()

            stage = "landscape-remote"
            requireNotNull(scenario).onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            orientation(Configuration.ORIENTATION_LANDSCAPE)
            selectedTab("遥控")
            await("确认", description = true, clickable = true)
            await(fixtureName)
            await("暂停 TV 播放", description = true, clickable = true)
            await("键盘", clickable = true)
            capture("landscape-remote.png")
            assertStatusBarContrast("landscape-remote.png", dark = false)
            for (label in listOf("上", "下", "左", "右", "确认")) touchTarget(label, description = true)
            touchTarget("暂停 TV 播放", description = true)
            touchTarget("返回")
            touchTarget("首页")
            touchTarget("键盘")
            val landscapeTargets=listOf("上", "下", "左", "右", "确认").map{touchTarget(it,description=true)}+
                listOf(touchTarget(fixtureName),touchTarget("暂停 TV 播放",description=true),
                    touchTarget("返回"),touchTarget("首页"),touchTarget("键盘"))
            for (i in landscapeTargets.indices) for (j in i+1 until landscapeTargets.size) {
                assertFalse("Landscape targets overlap: ${landscapeTargets[i].bounds} and ${landscapeTargets[j].bounds}",
                    Rect.intersects(landscapeTargets[i].bounds,landscapeTargets[j].bounds))
            }
            checks += "landscape D-pad, fixture name and quick action are visible with 48dp targets"
            assertSyntheticOnly()

            stage = "landscape-without-media"
            val mediaState=requireNotNull(fixtureFlow).value
            val browseState=mediaState.copy(snapshot=requireNotNull(mediaState.snapshot).copy(scene="BROWSE",content=JSONObject()))
            requireNotNull(scenario).onActivity { requireNotNull(fixtureFlow).value=browseState }
            withTimeout(8_000){while(find(fixtureName,description=false,clickable=false)!=null)delay(75)}
            for (label in listOf("上", "下", "左", "右", "确认")) touchTarget(label, description = true)
            for (label in listOf("返回", "首页", "键盘")) touchTarget(label)
            assertNull("No media must not produce a playback action",find("暂停 TV 播放",description=true,clickable=true))
            capture("landscape-no-media.png")
            checks += "landscape navigation remains visible without inventing a media action"

            stage = "landscape-dark-system-bars"
            requireNotNull(scenario).onActivity {requireNotNull(fixtureFlow).value=mediaState;darkTheme.value=true}
            await(fixtureName)
            delay(300)
            capture("landscape-dark-remote.png")
            assertStatusBarContrast("landscape-dark-remote.png", dark = true)
            checks += "light and dark dialog windows show contrasting status bar icons"
            assertSyntheticOnly()

            stage = "close-remote"
            assertTrue("Back should close the synthetic remote", device.pressBack())
            await("UI 测试已关闭")
            checks += "system Back closes the remote without a transport command"
            status = "PASS_UI_ONLY"
        } catch (failure: Throwable) {
            status = "FAIL_UI_ONLY"
            failureDetail = "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"
            runCatching { if (device.takeScreenshot(File(evidence, "failure.png"))) screenshots += "failure.png" }
            runCatching { device.dumpWindowHierarchy(File(evidence, "failure.xml")) }
            throw failure
        } finally {
            runCatching {
                scenario?.onActivity {
                    fixtureFlow?.let { flow -> originalConnection?.let { flow.value = it } }
                    if (sentinelInstalled) control?.stopView(sentinel)
                }
            }
            runCatching { scenario?.close() }
            var restoreFailure: Throwable? = null
            if (systemFontChanged) {
                try {
                    val command = if (originalFontSetting == "null") "settings delete system font_scale"
                        else "settings put system font_scale $originalFontSetting"
                    device.executeShellCommand(command)
                    check(device.executeShellCommand("settings get system font_scale").trim() == originalFontSetting) {
                        "System font setting did not restore"
                    }
                    withTimeout(8_000) {
                        while (kotlin.math.abs(context.resources.configuration.fontScale - originalEffectiveFontScale) >= 0.01f) delay(75)
                    }
                    systemFontRestored = true
                } catch (failure: Throwable) {
                    systemFontRestored = false
                    status = "FAIL_UI_ONLY"
                    failureDetail = listOfNotNull(failureDetail, "Font restore: ${failure.message.orEmpty()}").joinToString("; ")
                    restoreFailure = failure
                }
            }
            runCatching { report() }
            restoreFailure?.let { throw AssertionError("System font scale restoration failed", it) }
        }
    }
}

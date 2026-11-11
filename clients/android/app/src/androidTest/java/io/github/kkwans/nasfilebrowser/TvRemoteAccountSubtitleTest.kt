package io.github.kkwans.nasfilebrowser

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import io.github.kkwans.fileway.remote.RemotePhase
import io.github.kkwans.fileway.remote.RemoteSnapshot
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.TvControlModel
import io.github.kkwans.nasfilebrowser.ui.ClientTheme
import io.github.kkwans.nasfilebrowser.ui.TvControlScreen
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Native phone picker over a paired LAN connection to a separate, owned TV emulator. */
@RunWith(AndroidJUnit4::class)
class TvRemoteAccountSubtitleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun findAction(node: AccessibilityNodeInfo?, label: String, description: Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        val name = if (description) node.contentDescription else node.text
        if (node.packageName?.toString() == instrumentation.targetContext.packageName &&
            node.isVisibleToUser && name?.toString() == label) {
            return generateSequence(node) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }
        }
        for (index in 0 until node.childCount) findAction(node.getChild(index), label, description)?.let { return it }
        return null
    }

    private fun roots(): List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }
    }

    private fun actionNode(label: String, description: Boolean = false): AccessibilityNodeInfo? =
        roots().firstNotNullOfOrNull { findAction(it, label, description) }

    @Test fun selectsSubtitleFromFolderGrantedOnTv(): Unit = runBlocking {
        val runId = InstrumentationRegistry.getArguments().getString("ownedSafRun")
        assumeTrue("Use an owned paired TV with an explicit system folder grant", runId?.matches(Regex("[a-z0-9]+")) == true)
        require(Build.VERSION.SDK_INT == 35 && Build.HARDWARE.contains("ranchu"))
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val device = UiDevice.getInstance(instrumentation)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var phone: ClientModel
        lateinit var control: TvControlModel
        var stage = "phone starts without a media account"
        suspend fun click(label: String) {
            withTimeout(15_000) {
                while (actionNode(label)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) delay(100)
            }
        }
        try {
            scenario.onActivity {
                val models = ViewModelProvider(it)
                phone = models[ClientModel::class.java]
                control = models[TvControlModel::class.java]
            }
            val startup = withTimeout(20_000) { phone.state.first { !it.startupPending && !it.busy } }
            assertFalse(startup.connected)
            val trusted = control.client.store.dao.devices().first().single { it.trusted }
            scenario.onActivity { it.setContent { ClientTheme { TvControlScreen(phone, {}, control) } } }
            stage = "resume the authorized TV"
            val before = withTimeout(35_000) {
                control.connection.first { it.phase == RemotePhase.CONNECTED && it.device?.id == trusted.id && it.snapshot?.scene == "VIDEO" }
            }.snapshot!!
            assertEquals("tv-fixture", before.content.getJSONObject("session").getString("username"))
            assertEquals(1, before.capabilities.optInt("subtitleBrowseLocal"))
            val playback = before.content.getJSONObject("playback")
            assertFalse(playback.getBoolean("playing"))
            assertEquals(-1, playback.getInt("selectedSubtitle"))
            stage = "open granted TV folder from the native phone picker"
            click("字幕")
            click("本机字幕")
            click("Fileway SAF $runId")
            val file = "Owned TV local caption.srt"
            withTimeout(12_000) { while (actionNode(file) == null) delay(100) }
            device.takeScreenshot(File(directory, "fileway-saf-$runId-phone.png"))
            click(file)
            stage = "TV reports the selected local document"
            val after = withTimeout(30_000) {
                control.connection.first { it.snapshot?.let { snapshot ->
                    snapshot.content.getJSONObject("playback").let { p -> !p.getBoolean("subtitleLoading") && p.getInt("selectedSubtitle") >= 0 }
                } == true }
            }.snapshot!!
            assertEquals(before.sessionEpoch, after.sessionEpoch)
            assertEquals(before.mediaEpoch, after.mediaEpoch)
            assertFalse(after.content.getJSONObject("playback").getBoolean("playing"))
            assertTrue(abs(after.content.getJSONObject("playback").getLong("positionMs") - playback.getLong("positionMs")) < 500L)
            assertFalse(phone.state.value.connected)
        } catch (failure: Throwable) {
            runCatching { device.takeScreenshot(File(directory, "fileway-saf-$runId-phone-failure.png")) }
            throw AssertionError("Owned TV SAF journey failed at $stage", failure)
        } finally {
            scenario.close()
        }
    }

    @Test fun selectsFromTvAccountAndPreservesPlaybackWhenPhoneChangesAccount(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val runId = args.getString("ownedTvAccountRun")
        assumeTrue("A task-owned two-emulator fixture is required", runId?.matches(Regex("[a-z0-9]+")) == true)
        require(Build.VERSION.SDK_INT == 35 && Build.HARDWARE.contains("ranchu"))
        val url = requireNotNull(args.getString("fixture_url"))
        require(url.matches(Regex("http://10\\.0\\.2\\.2:\\d+")))
        val address = requireNotNull(args.getString("tv_address"))
        val first = requireNotNull(args.getString("phone_first_user"))
        val second = requireNotNull(args.getString("phone_second_user"))
        require(first.startsWith("owned-remote-a-") && second.startsWith("owned-remote-b-"))
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val status = File(directory, "fileway-tv-account-subtitle-$runId.json")
        val screenshotReady = File(directory, "fileway-tv-account-subtitle-$runId.ready")
        check(!status.exists() && !screenshotReady.exists())
        val device = UiDevice.getInstance(instrumentation)
        val config = Configurator.getInstance()
        val priorIdle = config.waitForIdleTimeout
        config.waitForIdleTimeout = 0
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var phone: ClientModel
        lateinit var control: TvControlModel
        var stage = "phone startup"
        var ownedPlayback = false
        var ownedLogin = false
        var pickerWindow: Rect? = null
        fun report(result: String) = status.writeText(JSONObject().put("status", result).put("stage", stage).toString())
        suspend fun login(user: String, secret: String) {
            scenario.onActivity { phone.connect(url, user, secret) }
            withTimeout(25_000) { phone.state.first { it.connected && !it.busy && phone.tvAuthorizationContext()?.account?.username == user } }
        }
        suspend fun remote(predicate: (RemoteSnapshot) -> Boolean = { true }): RemoteSnapshot = withTimeout(25_000) {
            control.connection.first { it.phase == RemotePhase.CONNECTED && it.snapshot?.let(predicate) == true }.snapshot!!
        }
        suspend fun command(operation: String, data: JSONObject = JSONObject()) {
            val result = control.client.execute(operation, data)
            assertTrue("TV rejected $operation (${result.status}/${result.code})", result.completed)
        }
        fun file(snapshot: RemoteSnapshot, title: String): JSONObject {
            val rows = snapshot.content.getJSONArray("files")
            return (0 until rows.length()).map(rows::getJSONObject).single { it.getString("name") == title }
        }
        fun scrollForward(node: AccessibilityNodeInfo?): Boolean {
            if (node == null || !node.isVisibleToUser) return false
            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            return (0 until node.childCount).any { scrollForward(node.getChild(it)) }
        }
        suspend fun click(label: String, description: Boolean = false, touch: Boolean = false) {
            var previousBounds: Rect? = null
            var stableSamples = 0
            withTimeout(12_000) {
                while (true) {
                    val target = actionNode(label, description)
                    if (touch) {
                        val bounds = target?.let { Rect().also(it::getBoundsInScreen) }
                        stableSamples = if (bounds != null && !bounds.isEmpty && bounds == previousBounds) stableSamples + 1 else 0
                        previousBounds = bounds
                        if (target != null && bounds != null && stableSamples >= 3) {
                            val window = Rect().also { requireNotNull(target.window).getBoundsInScreen(it) }
                            pickerWindow = window
                            File(directory, "fileway-tv-account-subtitle-$runId-touch.json").writeText(JSONObject()
                                .put("screenBounds", bounds.toShortString()).put("windowBounds", window.toShortString())
                                .put("tapX", bounds.centerX()).put("tapY", bounds.centerY()).toString())
                            device.takeScreenshot(File(directory, "fileway-tv-account-subtitle-$runId-before-touch.png"))
                            assertTrue(device.click(bounds.centerX(), bounds.centerY()))
                            break
                        }
                    } else if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) break
                    delay(100)
                }
            }
        }
        fun RemoteSnapshot.playback() = content.getJSONObject("playback")
        fun RemoteSnapshot.username() = content.getJSONObject("session").getString("username")
        try {
            scenario.onActivity {
                val models = ViewModelProvider(it)
                phone = models[ClientModel::class.java]
                control = models[TvControlModel::class.java]
            }
            val startup = withTimeout(20_000) { phone.state.first { !it.startupPending && !it.busy } }
            check(!startup.connected && phone.tvAuthorizationContext() == null) { "Use the task-owned signed-out phone" }
            val trusted = control.client.store.dao.devices().first().single { it.trusted }
            stage = "phone login A"
            ownedLogin = true
            login(first, requireNotNull(args.getString("phone_first_password")))
            assertFalse(phone.state.value.files.any { it.name == "Owned Captions" })
            scenario.onActivity { it.setContent { ClientTheme { TvControlScreen(phone, {}, control) } } }
            stage = "resume trusted TV over Wi-Fi"
            val initial = remote { it.scene == "BROWSE" && !it.content.optBoolean("busy") }
            assertEquals(trusted.id, control.connection.value.device?.id)
            assertEquals(address, control.connection.value.device?.address)
            assertEquals("tv-fixture", initial.username())
            stage = "open TV-owned media"
            command("app.navigate", JSONObject().put("route", "FILES"))
            val root = remote { it.scene == "BROWSE" && it.content.optString("route") == "FILES" && !it.content.optBoolean("busy") }
            if (root.content.getString("directory") == "/") {
                command("media.open", JSONObject().put("id", file(root, "Cinema").getString("id")))
            }
            val movies = remote { it.scene == "BROWSE" && it.content.optString("directory") == "/Cinema" && !it.content.optBoolean("busy") }
            command("media.open", JSONObject().put("id", file(movies, "Fileway owned 150s +%# fixture.mp4").getString("id")))
            ownedPlayback = true
            remote { it.scene == "VIDEO" && it.playback().optBoolean("firstFrameRendered") }
            command("playback.pause")
            command("playback.seekTo", JSONObject().put("positionMs", 6_000))
            command("subtitle.disable")
            val before = remote { it.scene == "VIDEO" && it.playback().let { p ->
                !p.optBoolean("playing") && !p.optBoolean("seeking") && !p.optBoolean("waitingForBuffer") &&
                    p.optInt("selectedSubtitle", -2) == -1 && abs(p.getLong("positionMs") - 6_000L) < 650L
            } }
            assertEquals(1, before.capabilities.optInt("subtitleBrowseServerCursor"))
            stage = "paused frame before subtitle"
            report("CAPTURE_BEFORE")
            withTimeout(20_000) { while (!screenshotReady.exists()) delay(100) }

            stage = "phone subtitle picker"
            withTimeout(12_000) { while (actionNode("字幕") == null) delay(100) }
            device.takeScreenshot(File(directory, "fileway-tv-account-subtitle-$runId-playback.png"))
            click("字幕")
            stage = "subtitle actions align"
            withTimeout(12_000) {
                while (true) {
                    val windows = roots()
                    val bounds = listOf("字幕调整", "服务器字幕", "本机字幕").mapNotNull { label ->
                        windows.firstNotNullOfOrNull { findAction(it, label, false) }?.let { Rect().also(it::getBoundsInScreen) }
                    }
                    if (bounds.size == 3 && bounds.none { it.isEmpty } &&
                        bounds.maxOf { it.top } - bounds.minOf { it.top } <= 1 &&
                        bounds.maxOf { it.bottom } - bounds.minOf { it.bottom } <= 1) break
                    delay(100)
                }
            }
            device.takeScreenshot(File(directory, "fileway-tv-account-subtitle-$runId-actions.png"))
            click("服务器字幕")
            stage = "leave the video directory in the TV subtitle picker"
            click("进入目录：根目录", description = true, touch = true)
            stage = "choose subtitle from the separate TV directory"
            val rootDescription = "当前目录：根目录，展开完整路径"
            val rootWindow = withTimeout(12_000) {
                var window = actionNode(rootDescription, description = true)?.window
                while (window == null) { delay(100); window = actionNode(rootDescription, description = true)?.window }
                Rect().also(window::getBoundsInScreen)
            }
            assertEquals("Subtitle window keeps its position across directory results", pickerWindow, rootWindow)
            withTimeout(12_000) {
                while (actionNode("Owned Captions") == null) {
                    scrollForward(actionNode(rootDescription, description = true)?.window?.root)
                    delay(300)
                }
            }
            click("Owned Captions")
            click("Owned remote caption.srt")
            val selected = remote { it.mediaEpoch == before.mediaEpoch && it.playback().let { p ->
                !p.optBoolean("subtitleLoading") && p.optInt("selectedSubtitle", -1) >= 0
            } }
            assertEquals(before.sessionEpoch, selected.sessionEpoch)
            assertEquals("tv-fixture", selected.username())
            stage = "phone login B while TV is paused"
            login(second, requireNotNull(args.getString("phone_second_password")))
            val atLogin = control.connection.value.snapshot ?: selected
            val changed = remote { it.connectionId != atLogin.connectionId || it.revision > atLogin.revision }
            assertEquals("tv-fixture", changed.username())
            assertEquals(before.sessionEpoch, changed.sessionEpoch)
            assertEquals(before.mediaEpoch, changed.mediaEpoch)
            assertFalse(changed.playback().getBoolean("playing"))
            assertEquals(selected.playback().getInt("selectedSubtitle"), changed.playback().getInt("selectedSubtitle"))
            assertTrue(abs(before.playback().getLong("positionMs") - changed.playback().getLong("positionMs")) < 650L)
            stage = "paused frame after phone account change"
            report("CAPTURE_AFTER")
            val captured = File(directory, "fileway-tv-account-subtitle-$runId.after")
            withTimeout(20_000) { while (!captured.exists()) delay(100) }
            report("PASS")
        } catch (failure: Throwable) {
            runCatching { device.takeScreenshot(File(directory, "fileway-tv-account-subtitle-$runId-failure.png")) }
            report("FAIL")
            throw AssertionError("Owned remote subtitle journey failed at $stage", failure)
        } finally {
            if (ownedPlayback) runCatching { command("playback.stop") }
            if (ownedLogin) {
                scenario.onActivity { phone.signOut() }
                runCatching { withTimeout(6_000) { phone.state.first { !it.connected && !it.busy } } }
            }
            scenario.close()
            config.waitForIdleTimeout = priorIdle
        }
    }
}

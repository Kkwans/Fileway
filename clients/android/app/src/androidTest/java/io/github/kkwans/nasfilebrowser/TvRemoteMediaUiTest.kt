package io.github.kkwans.nasfilebrowser

import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import io.github.kkwans.fileway.remote.*
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.SearchResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in checks for the changed media UI only, using an existing trusted TV/login. */
@RunWith(AndroidJUnit4::class)
class TvRemoteMediaUiTest {
    @Test fun mediaPanelsImageTransformAndCompactDpad() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configFile = File(context.getExternalFilesDir(null), "fileway-media-ui-acceptance.json")
        assumeTrue("Owned fixture opt-in required", configFile.isFile)
        val config = JSONObject(configFile.readText())
        require(config.getBoolean("useCurrentLogin"))
        val directory = config.getString("directory")
        val videoName = config.getString("videoName")
        val imageName = config.getString("imageName")
        require(directory.startsWith('/') && videoName.startsWith("Fileway owned") && imageName.startsWith("Fileway owned"))
        val evidence = File(context.getExternalFilesDir(null), "fileway-media-ui-evidence").apply { mkdirs() }
        val device = UiDevice.getInstance(instrumentation)
        val checks = mutableListOf<String>()
        fun record(status: String = "RUNNING") = File(evidence, "result.json").writeText(
            JSONObject().put("status", status).put("checks", JSONArray(checks))
                .put("scope", "Xiaomi14, one phone/TV process pair; bounded changed media UI, no audio/Q1/HDR acceptance").toString(2))
        suspend fun activity(): MainActivity = withTimeout(15000) {
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
        device.executeShellCommand("am start --windowingMode 5 -n io.github.kkwans.nasfilebrowser/.MainActivity")
        val first = activity()
        val density = withContext(Dispatchers.Main) { first.resources.displayMetrics.density }
        val right = device.displayWidth - 24
        val left = (right - (380 * density).toInt()).coerceAtLeast(24)
        device.executeShellCommand("am task resize ${first.taskId} $left 24 $right ${device.displayHeight - 24}")
        delay(500)
        val host = activity()
        val phone = withContext(Dispatchers.Main) { ViewModelProvider(host)[ClientModel::class.java] }
        val control = withContext(Dispatchers.Main) { ViewModelProvider(host)[TvControlModel::class.java] }
        val owner = Any()
        val original = withTimeout(30000) { phone.state.first { it.connected && !it.startupPending && !it.busy } }
        val source = withContext(Dispatchers.Main) { phone.tvAuthorizationContext() } ?: error("Current login required")
        var originalSubtitleScale: Double? = null
        suspend fun remote(predicate: (RemoteSnapshot) -> Boolean = { true }): RemoteSnapshot = withTimeout(30000) {
            control.connection.first { it.phase == RemotePhase.CONNECTED && it.snapshot?.let(predicate) == true }.snapshot!!
        }
        fun node(label: String): AccessibilityNodeInfo? {
            // A scroll can finish before the accessibility cache invalidates.
            if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
            fun find(value: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (value == null) return null
                if (value.packageName?.toString() == context.packageName && value.isVisibleToUser &&
                    (value.text?.toString() == label || value.contentDescription?.toString() == label)) return value
                for (index in 0 until value.childCount) find(value.getChild(index))?.let { return it }
                return null
            }
            return instrumentation.uiAutomation.windows.mapNotNull { it.root }.firstNotNullOfOrNull(::find)
        }
        suspend fun visible(label: String): AccessibilityNodeInfo = withTimeout(10000) {
            var found = node(label)
            while (found == null) { delay(100); found = node(label) }
            found
        }
        fun scrollable(value: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (value == null) return null
            if (value.packageName?.toString() == context.packageName && value.isVisibleToUser && value.isScrollable) return value
            for (index in 0 until value.childCount) scrollable(value.getChild(index))?.let { return it }
            return null
        }
        suspend fun scrollTo(label: String) {
            repeat(6) {
                if (node(label) != null) return
                val list = instrumentation.uiAutomation.windows.mapNotNull { it.root }.firstNotNullOfOrNull(::scrollable)
                    ?: error("No phone scroll container for $label")
                val moved = list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(250)
                if (!moved) { visible(label); return }
            }
            visible(label)
        }
        suspend fun click(label: String) {
            scrollTo(label)
            var target: AccessibilityNodeInfo? = visible(label)
            while (target != null && !target.isClickable) target = target.parent
            assertTrue("No clickable native action: $label", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            delay(250)
        }
        suspend fun progress(label: String, value: Float) {
            scrollTo(label)
            val arguments = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }
            assertTrue("No native range action: $label", visible(label).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, arguments))
        }
        suspend fun prepare(file: ResourceRef) {
            withContext(Dispatchers.Main) { control.handoff.begin(phone, file, phone.state.value.previewScope) }
            withTimeout(125000) {
                control.connection.first { state -> state.snapshot?.let { snapshot ->
                    snapshot.content.optJSONObject("media")?.optString("name") == file.name &&
                        snapshot.content.optJSONObject("handoff")?.optString("status") == "READY"
                } == true }
            }
            visible("TV 遥控")
        }
        try {
            withContext(Dispatchers.Main) { control.startView(owner) }
            val trusted = withTimeout(15000) { control.client.store.dao.devices().first().firstOrNull { it.trusted } } ?: error("Existing pairing required")
            val found = withTimeout(20000) { control.discovery.first { it.devices.any { row -> row.id == trusted.id && row.fingerprint == trusted.fingerprint } } }.devices.first { it.id == trusted.id }
            withContext(Dispatchers.Main) { control.connect(trusted.descriptor().at(found.address, found.port)) }
            val initial = remote()
            require(initial.scene == "BROWSE" && initial.content.optJSONObject("session")?.optString("username") == source.api.identity.username)
            originalSubtitleScale = initial.content.getJSONObject("playback").getJSONObject("subtitleAppearance").getDouble("scale")
            withContext(Dispatchers.Main) { phone.open(ResourceRef(directory, SearchResult.encodePath(directory), directory.substringAfterLast('/'), true, "", 0)) }
            val listing = withTimeout(30000) { phone.state.first { it.path == directory && !it.busy && it.files.any { file -> file.name == videoName } } }
            val video = listing.files.first { it.name == videoName }
            val image = listing.files.first { it.name == imageName }
            withContext(Dispatchers.Main) { phone.open(video) }
            withTimeout(30000) { phone.player.state.first { it.firstFrameRendered && it.canSavePosition } }
            withContext(Dispatchers.Main) { phone.pausePlayback() }
            prepare(video)
            visible("TV 播放进度")
            device.takeScreenshot(File(evidence, "video-controls.png"))
            checks += "native handoff opens the scene-specific media tab"; record()
            progress("TV 播放进度", 5000f)
            val videoScope = remote { it.content.getJSONObject("playback").let { value -> !value.optBoolean("seeking") && kotlin.math.abs(value.optLong("positionMs") - 5000) < 1000 } }
            click("字幕")
            visible("同目录外挂")
            click("外观与同步")
            visible("字幕外观与同步")
            progress("字号", 1.2f)
            remote { kotlin.math.abs(it.content.getJSONObject("playback").getJSONObject("subtitleAppearance").getDouble("scale") - 1.2) < .02 }
            assertTrue("TV font catalog must be available", control.mediaList(remote(), "fonts").isNotEmpty())
            device.takeScreenshot(File(evidence, "subtitle-appearance.png"))
            checks += "source-fenced seek and subtitle appearance controls reach actual TV state"; record()
            device.pressBack(); delay(200)
            device.pressBack(); delay(200)
            val oldRate = remote().content.getJSONObject("playback").optDouble("rate", 1.0)
            click("${oldRate}×"); click("1.5×")
            remote { kotlin.math.abs(it.content.getJSONObject("playback").optDouble("rate") - 1.5) < .01 }
            click("音轨")
            visible("音轨")
            device.takeScreenshot(File(evidence, "audio-menu.png"))
            device.pressBack(); delay(200)
            click("遥控")
            val phoneRoot = instrumentation.uiAutomation.windows.mapNotNull { it.root }.first { it.packageName?.toString() == context.packageName }
            val phoneBounds = Rect().also(phoneRoot::getBoundsInScreen)
            val logicalWidth = withContext(Dispatchers.Main) { host.resources.configuration.screenWidthDp }
            val physicalDensity = phoneBounds.width().toFloat() / logicalWidth
            val keyboardAncestors = generateSequence(visible("键盘")) { it.parent }.toList()
            val contentViewport = generateSequence(visible("上")) { it.parent }.first { it in keyboardAncestors }
            val viewport = Rect().also(contentViewport::getBoundsInScreen)
            for (label in listOf("上", "下", "左", "右", "确认")) {
                var target: AccessibilityNodeInfo? = visible(label)
                while (target != null && !target.isClickable) target = target.parent
                val bounds = Rect().also { requireNotNull(target).getBoundsInScreen(it) }
                assertTrue("Compact D-pad $label is clipped", viewport.contains(bounds) && bounds.height() >= 48 * physicalDensity - 3)
            }
            for (label in listOf("首页", "键盘")) assertTrue("Compact footer $label is clipped", viewport.contains(Rect().also(visible(label)::getBoundsInScreen)))
            device.takeScreenshot(File(evidence, "compact-dpad.png"))
            checks += "speed/audio panels and a complete compact D-pad are reachable"; record()
            device.pressBack(); delay(300)
            withContext(Dispatchers.Main) { phone.leavePlayer(); phone.open(image) }
            withTimeout(15000) { phone.state.first { it.image?.mediaKey == image.mediaKey } }
            prepare(image)
            visible("TV 图片缩放")
            progress("TV 图片缩放", 2f)
            remote { kotlin.math.abs(it.content.getJSONObject("image").getDouble("scale") - 2) < .02 }
            click("适合屏幕")
            remote { kotlin.math.abs(it.content.getJSONObject("image").getDouble("scale") - 1) < .02 }
            visible("缩放 100%")
            val stale = runCatching { control.client.execute("playback.pause", expected = videoScope) }.exceptionOrNull()
            assertTrue("An old video gesture must be rejected before transmission", stale is RemoteFailure && stale.code == "SCOPE_CHANGED")
            device.takeScreenshot(File(evidence, "image-controls.png"))
            checks += "image zoom/fit reports actual viewport; old video scope is rejected"; record("PASS")
        } catch (failure: Throwable) {
            runCatching { device.dumpWindowHierarchy(File(evidence, "failure.xml")); device.takeScreenshot(File(evidence, "failure.png")) }
            record("FAIL"); throw failure
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                control.handoff.dismiss(cancelTv = false)
                val media = control.connection.value.snapshot?.content?.optJSONObject("media")?.optString("name")
                if (media?.startsWith("Fileway owned") == true) {
                    originalSubtitleScale?.let { value -> runCatching { control.client.execute("subtitle.setAppearance", JSONObject().put("scale", value)) } }
                    runCatching { control.client.execute("playback.stop") }
                }
                control.disconnect(); control.stopView(owner)
                phone.closeImage(); phone.leavePlayer()
                if (phone.tvAuthorizationContext() === source && phone.state.value.path != original.path) phone.back()
            }
            configFile.delete()
            device.executeShellCommand("am start --windowingMode 1 -n io.github.kkwans.nasfilebrowser/.MainActivity")
        }
    }
}

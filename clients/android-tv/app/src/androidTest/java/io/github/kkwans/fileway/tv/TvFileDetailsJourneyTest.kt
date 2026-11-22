package io.github.kkwans.fileway.tv

import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import io.github.kkwans.nasfilebrowser.data.VideoDecodePolicy
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses a task-owned emulator and loopback fixture; it is not projector acceptance. */
@RunWith(AndroidJUnit4::class)
class TvFileDetailsJourneyTest {
    @Test fun fileDetailsRemainReachableAfterPlaybackReturn() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires a task-owned emulator and loopback fixture", Build.FINGERPRINT.contains("generic") &&
            args.getString("fixture_url") != null && args.getString("fixture_username") != null && args.getString("fixture_password") != null)
        val url = requireNotNull(args.getString("fixture_url"))
        require(url.startsWith("http://127.0.0.1:"))
        val username = requireNotNull(args.getString("fixture_username"))
        val password = requireNotNull(args.getString("fixture_password"))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val config = Configurator.getInstance()
        val previousIdle = config.waitForIdleTimeout
        config.waitForIdleTimeout = 0
        try {
            ActivityScenario.launch(TvActivity::class.java).use { scenario ->
                lateinit var model: TvModel
                scenario.onActivity { model = ViewModelProvider(it)[TvModel::class.java] }
                await("login screen", 30_000) { !model.state.value.restoring }
                if (model.state.value.connected) {
                    scenario.onActivity { model.disconnect() }
                    await("fixture session closed", 15_000) { !model.state.value.connected }
                }
                scenario.onActivity {
                    model.media.setVideoDecodePolicy(VideoDecodePolicy.SOFTWARE)
                    model.login(url, username, password)
                }
                await("fixture login", 40_000) { model.state.value.connected && !model.state.value.busy }
                scenario.onActivity { model.navigate(TvRoute.FILES); model.browse("/Cinema", "/Cinema", false) }
                await("owned folder", 30_000) { model.state.value.path == "/Cinema" && !model.state.value.busy }
                await("file focus", 20_000) { focusedAction(device, "Owned Catalog Film.mp4") }
                device.pressDPadCenter()
                await("decoded video frame", 45_000) { model.media.player.state.value.firstFrameRendered }
                repeat(3) {
                    if (model.media.state.value.file != null) { device.pressBack(); SystemClock.sleep(250) }
                }
                await("return to owned folder", 20_000) { model.media.state.value.file == null }
                await("original file focus", 20_000) { focusedAction(device, "Owned Catalog Film.mp4") }
                activateRemote(device, "详情", 20_000)
                await("details actions", 15_000) { device.findObject(By.text("加入收藏")) != null }
                capture(device, "file-details-after-playback.png")
                device.pressBack()
                await("restore details control", 15_000) { focusedAction(device, "详情") }
                device.pressDPadDown()
                await("return to file", 15_000) { focusedAction(device, "Owned Catalog Film.mp4") }
            }
        } finally { config.waitForIdleTimeout = previousIdle }
    }

    private fun focusedAction(device: UiDevice, text: String): Boolean {
        var node = device.findObject(By.text(text)) ?: return false
        while (!node.isClickable) node = node.parent ?: return false
        return node.isFocused || node.findObject(By.focused(true)) != null
    }

    private fun activateRemote(device: UiDevice, text: String, timeout: Long) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            var target = device.findObject(By.text(text))
            while (target != null && !target.isFocusable) target = target.parent
            if (target?.isFocused == true) { device.pressDPadCenter(); return }
            val current = device.findObject(By.focused(true))
            if (target == null || current == null) device.pressDPadUp()
            else {
                val from = current.visibleBounds
                val to = target.visibleBounds
                val dx = to.centerX() - from.centerX()
                val dy = to.centerY() - from.centerY()
                if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                    if (dx < 0) device.pressDPadLeft() else device.pressDPadRight()
                } else if (dy < 0) device.pressDPadUp() else device.pressDPadDown()
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Remote target not reachable: $text")
    }

    private fun await(label: String, timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out: $label")
    }

    private fun capture(device: UiDevice, name: String) {
        device.waitForIdle(1_500)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "media-catalog-acceptance").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, name)))
    }
}

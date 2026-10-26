package io.github.kkwans.nasfilebrowser

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Viewport regression gate with real JNI/HTTP and held source confirmation.
 * Decoder/frame correctness belongs to NativePlaybackTest, not this layout gate. */
@RunWith(AndroidJUnit4::class)
class PlayerViewportTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun savingAndHeldResumeNeverResizeFullscreenVideo() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val media = instrumentation.context.assets.open("media/fixture.mkv").use { it.readBytes() }
        val source = NativePlaybackTest.Fixture(media)
        val store = ProfileStore(ClientDatabase.get(instrumentation.targetContext), CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "Viewport fixture", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
        fun bounds() = device.wait(Until.findObject(By.desc("视频画面")), 5_000)?.visibleBounds
            ?: error("Video viewport missing")
        fun noRoutineNotices() {
            for (text in listOf("正在确认播放来源", "正在保存续播", "续播已同步", "续播已保存本机，待同步")) {
                assertFalse("Routine status must stay out of playback UI: $text", device.hasObject(By.text(text)))
            }
        }
        try {
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "fixture", "fixture-only", "direct") }
            withTimeout(15_000) { model.state.first { it.connected && !it.busy } }
            main { model.open(model.state.value.files.single()) }
            withTimeout(15_000) { model.state.first { it.selected != null && !it.busy } }
            // Duration can arrive while the decoder is still buffering or
            // recovering. This gate pauses a confirmed playable position.
            withTimeout(15_000) { model.player.state.first { it.durationMs > 0 && it.playing && it.canSavePosition } }
            device.setOrientationLeft()
            assertTrue(device.wait(Until.hasObject(By.desc("退出全屏")), 10_000))
            instrumentation.waitForIdleSync()
            val initial = bounds()
            assertTrue("Fullscreen viewport must occupy the screen height", initial.height() >= device.displayHeight * .95f)
            main { model.pausePlayback() }
            withTimeout(10_000) { model.state.first { it.progressStatus == "续播已同步" } }
            noRoutineNotices()
            assertEquals(initial, bounds())

            source.stallNextRead.set(true)
            main { model.togglePlayback() }
            assertTrue(withContext(Dispatchers.IO) { source.resumeRead.await(5, TimeUnit.SECONDS) })
            assertTrue(model.state.value.busy)
            assertTrue(device.wait(Until.hasObject(By.desc("正在加载视频")), 5_000))
            assertFalse(device.hasObject(By.desc("取消播放请求")))
            noRoutineNotices()
            assertEquals("Loading must overlay, never consume viewport height", initial, bounds())
            device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/player-resume-wait-viewport.png")
            main { model.cancel() }
            withTimeout(5_000) { model.state.first { !it.busy } }
            assertTrue(device.wait(Until.gone(By.desc("正在加载视频")), 5_000))
            assertEquals(initial, bounds())
            assertFalse(model.player.state.value.playing)
        } finally {
            source.releaseRead.countDown()
            main { model.disconnect() }
            device.setOrientationNatural()
            device.unfreezeRotation()
            store.remove(profile)
            source.close()
        }
    }
}

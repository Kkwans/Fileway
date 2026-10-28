package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Installed cards use the actual account history, JNI preview and compositor. */
@RunWith(AndroidJUnit4::class)
class RecentScreenTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun text(value: String) = device.wait(Until.findObject(By.text(value)), 5000)
        ?: throw AssertionError("Missing visible action: $value")
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/recent-$name.png")
    }

    @Test fun authenticatedCardsShowProgressDetailsAndAccountIsolation(): Unit = runBlocking {
        val source = ClientSearchTest.Fixture(previewBody = ownedPreviewPng())
        val db = ClientDatabase.get(instrumentation.targetContext)
        val store = ProfileStore(db, CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "我的媒体库", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        val oldTheme = withTimeout(5000) { model.appearance.state.first { it.loaded } }.theme
        suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
        suspend fun theme(value: AppTheme) {
            main { model.appearance.save(value) }
            withTimeout(5000) { model.appearance.state.first { it.theme == value && !it.saving } }
        }
        val longName = "秋日旅行.导演剪辑版.2026.2160p.特别长的完整文件名称.中文字幕.mkv"
        try {
            theme(AppTheme.LIGHT)
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            val account = store.accounts(profile).single()
            val wire = SearchResult.encodePath("/$longName")
            val entries = listOf(
                PlaybackSnapshot(account.key, wire, "movie-v1", "/$longName", wire, longName, 1234000, 9813824, 30, ProgressSync.SYNCED),
                PlaybackSnapshot(account.key, "/changed.mkv", "old-movie", "/changed.mkv", "/changed.mkv", "已替换的影片.mkv", 3000, 12000, 20, ProgressSync.IDENTITY_CHANGED),
                PlaybackSnapshot(account.key, "/unknown.mkv", "unknown", "/unknown.mkv", "/unknown.mkv", "时长未确认.mkv", 3000, 0, 10, ProgressSync.UNSUPPORTED))
            entries.forEach { PlaybackHistory(db.playback()).save(it) }
            withTimeout(5000) { model.recent.first { it.size == 3 } }
            main { model.tab("recent") }
            text("继续观看"); text("3 项"); text("20:34 / 2:43:33"); text("已同步")
            assertFalse(device.hasObject(By.text(model.state.value.serverLabel)))
            assertFalse(device.hasObject(By.text("切换服务器")))
            text("文件已变化"); text("原记录 0:03 / 0:12"); text("0:03 / 时长待确认")
            assertFalse("Unknown file sizes must not be invented", device.hasObject(By.text("0 KB")) || device.hasObject(By.text("0.0 KB")))
            assertTrue(withContext(Dispatchers.IO) { source.previewSeen.await(5, TimeUnit.SECONDS) })
            withTimeout(10_000) {
                while (true) {
                    val screen = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
                    val image = screen.copy(Bitmap.Config.ARGB_8888, false)
                    val count = try {
                        val pixels = IntArray(image.width * image.height)
                        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                        pixels.count { Color.red(it) in 25..35 && Color.green(it) in 175..185 && Color.blue(it) in 215..225 }
                    } finally { image.recycle(); screen.recycle() }
                    if (count > 400) break
                    delay(100)
                }
            }
            capture("cards-light")
            text(longName).longClick(); text("文件详情"); text("/$longName")
            capture("details"); text("关闭").click()
            theme(AppTheme.DARK); text("文件已变化"); capture("cards-dark")
            text(longName).click()
            assertTrue(withContext(Dispatchers.IO) { source.playbackStarted.await(5, TimeUnit.SECONDS) })
            assertTrue(device.wait(Until.hasObject(By.desc("正在加载视频")), 5000))
            assertFalse(device.hasObject(By.desc("取消播放请求")))
            main { model.cancel() }
            withTimeout(5000) { model.state.first { it.selected == null && !it.busy } }
            assertEquals("recent", model.state.value.tab)
            assertNull(model.state.value.mediaQueue)
            source.releasePlayback.countDown()
            delay(250)
            assertNull("Late history replies must not reopen a canceled recent item", model.state.value.selected)
            main { model.connectDraft(profile.name, source.url, BackendKind.NAS, "two", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy && it.accountName == "two" } }
            withTimeout(5000) { model.recent.first { it.isEmpty() } }
            main { model.tab("recent") }
            text("还没有播放记录")
            assertFalse(device.hasObject(By.text(longName)))
            capture("empty-dark")
            text("浏览文件").click(); text("这个目录还没有文件。")
        } catch (error: Throwable) { capture("failure"); throw error }
        finally {
            source.releasePlayback.countDown()
            main { model.disconnect() }
            theme(oldTheme)
            store.remove(profile); source.close()
        }
    }
}

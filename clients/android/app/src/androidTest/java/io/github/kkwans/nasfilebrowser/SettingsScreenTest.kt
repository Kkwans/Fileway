package io.github.kkwans.nasfilebrowser

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.update.UpdatePolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/** Installed settings and navigation with real login/preview/cache, no static settings model. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun action(label: String, direction: Direction = Direction.DOWN): UiObject2 {
        repeat(6) {
            device.findObject(By.text(label))?.let { return it }
            val content = device.wait(Until.findObject(By.desc("设置内容")), 5000) ?: error("Settings missing")
            if (!content.scroll(direction, .6f)) return@repeat
        }
        error("Settings action missing: $label")
    }
    private fun nav(label: String) = device.wait(Until.findObject(By.desc("${label}导航")), 5000) ?: error("Navigation missing: $label")
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val variant = InstrumentationRegistry.getArguments().getString("nfbVisualVariant").orEmpty()
        require(variant.matches(Regex("[a-z0-9-]{0,32}")))
        val suffix = if (variant.isEmpty()) "" else "-$variant"
        device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/$name$suffix.png")
    }
    @Test fun actualAccountProfileCacheNavigationAndServerSwitch(): Unit = runBlocking {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(Color.CYAN)
            ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
        val source = ClientSearchTest.Fixture(listOf("A.mkv"), bytes)
        val store = ProfileStore(ClientDatabase.get(instrumentation.targetContext), CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "我的媒体库", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        InstrumentationRegistry.getArguments().getString("nfbFontScale")?.toFloat()?.let { expected ->
            assertEquals(expected, instrumentation.targetContext.resources.configuration.fontScale, .01f)
        }
        try {
            withContext(Dispatchers.Main) { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            withTimeout(10_000) { while (model.previewImageLoader.memoryCache?.keys.isNullOrEmpty()) delay(50) }
            assertEquals("one", model.state.value.accountName)
            nav("设置").click()
            assertTrue(device.wait(Until.hasObject(By.text("one")), 5000))
            capture("settings-overview")
            action("应用内 Tailscale").click()
            assertTrue(device.wait(Until.hasObject(By.text("连接 Tailscale")), 5000))
            capture("settings-network")
            (device.wait(Until.findObject(By.text("关闭")), 5000) ?: error("Network close action missing")).click()
            action("服务器档案").click()
            assertTrue(device.wait(Until.hasObject(By.text(source.url)), 5000))
            assertTrue(device.wait(Until.hasObject(By.text("账号：one")), 5000))
            (device.wait(Until.findObject(By.text("关闭")), 5000) ?: error("Dialog close action missing")).click()
            action("清理缩略图缓存").click()
            assertTrue(device.wait(Until.hasObject(By.text("清理缓存？")), 5000))
            (device.wait(Until.findObject(By.text("清理")), 5000) ?: error("Cache confirmation missing")).click()
            assertTrue(device.wait(Until.hasObject(By.text("缓存已清理")), 5000))
            assertEquals(0L, model.previewImageLoader.diskCache?.size)
            assertEquals(0L, model.previewImageLoader.memoryCache?.size)
            assertTrue(model.previewImageLoader.memoryCache?.keys.isNullOrEmpty())
            capture("settings-cache")
            action("关于应用").click()
            assertTrue(device.wait(Until.hasObject(By.text("栖卷 · Fileway")), 5000))
            val repositoryIntent = AtomicReference<Intent>()
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action != Intent.ACTION_VIEW) return null
                    repositoryIntent.set(Intent(intent))
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
            instrumentation.addMonitor(monitor)
            try {
                val link = withTimeout(5000) {
                    var found: FreshAccessibilityNodeBounds? = null
                    while (found == null) {
                        found = freshAccessibilityBounds(instrumentation, Rect(0, 0, device.displayWidth, device.displayHeight))
                            ?.singleOrNull { it.visible && it.enabled && it.clickable && it.description == "打开 GitHub 仓库" }
                        if (found == null) delay(25)
                    }
                    checkNotNull(found)
                }
                assertTrue("Repository link must have a 48dp target", link.bounds.height() + 1 >= 48 * instrumentation.targetContext.resources.displayMetrics.density)
                OwnedTouchInput.tap(instrumentation, link.bounds, tracePrefix = "settings-repository")
                withTimeout(5000) { while (repositoryIntent.get() == null) delay(25) }
                val launched = checkNotNull(repositoryIntent.get())
                assertEquals(UpdatePolicy.REPOSITORY, launched.dataString)
                assertTrue(launched.hasCategory(Intent.CATEGORY_BROWSABLE))
                assertFalse(launched.hasExtra("com.android.browser.headers"))
                assertNull(launched.clipData)
                assertEquals(0, launched.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } finally { instrumentation.removeMonitor(monitor) }
            (device.wait(Until.findObject(By.text("关闭")), 5000) ?: error("Dialog close action missing")).click()
            nav("最近").click()
            assertTrue(device.wait(Until.hasObject(By.text("继续观看")), 5000))
            nav("文件").click()
            assertTrue(device.wait(Until.hasObject(By.desc("文件网格")), 5000))
            nav("设置").click(); device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.desc("文件网格")), 5000))
            assertTrue(model.state.value.connected)
            // Settings restores its scroll position after visiting another tab.
            nav("设置").click(); action("服务器", Direction.UP).click()
            withTimeout(5000) { model.state.first { !it.connected } }
        } catch (error: Throwable) { capture("settings-failure"); throw error }
        finally { withContext(Dispatchers.Main) { model.disconnect() }; store.remove(profile); source.close() }
    }
}

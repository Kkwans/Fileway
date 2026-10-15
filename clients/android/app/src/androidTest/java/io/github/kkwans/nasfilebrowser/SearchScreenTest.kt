package io.github.kkwans.nasfilebrowser

import android.graphics.Rect
import android.view.ViewConfiguration
import android.view.WindowInsets
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** Installed application UI -> bound ViewModel -> actual JNI/Go -> owned HTTP fixture. */
@RunWith(AndroidJUnit4::class)
class SearchScreenTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun objectWithText(text: String): UiObject2 = device.wait(Until.findObject(By.text(text)), 5000)
        ?: throw AssertionError("Visible action missing: $text")
    private suspend fun resultWithName(name: String): Rect = withTimeout(5000) {
        while (true) {
            val matches = freshAccessibilityBounds(instrumentation, Rect(0, 0, device.displayWidth, device.displayHeight))
                ?.filter { it.visible && it.enabled && it.canLongClick && it.className == "android.widget.TextView" &&
                    it.text == name && "搜索结果" in it.ancestorDescriptions }
            matches?.singleOrNull()?.let { return@withTimeout Rect(it.bounds) }
            delay(25)
        }
        @Suppress("UNREACHABLE_CODE") error("Visible result missing: $name")
    }
    private suspend fun clickResult(name: String) {
        val bounds = resultWithName(name)
        assertTrue("Current search result tap must be injected", device.click(bounds.centerX(), bounds.centerY()))
    }
    private suspend fun longPressResult(name: String) {
        val bounds = resultWithName(name)
        // Match UiAutomator 2.3.0's 1.5 * platform long-press timeout. The
        // existing UiDevice swipe path sends real FINGER events every 5ms.
        val steps = ceil(ViewConfiguration.getLongPressTimeout() * 1.5 / 5).toInt().coerceAtLeast(1)
        assertTrue("Current search result long press must be injected",
            device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.centerY(), steps))
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        // Capture the settled dialog/system-bar frame, not its exit animation.
        android.os.SystemClock.sleep(250)
        val variant = InstrumentationRegistry.getArguments().getString("nfbVisualVariant").orEmpty()
        require(variant.matches(Regex("[a-z0-9-]{0,32}")))
        val suffix = if (variant.isEmpty()) "" else "-$variant"
        device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/$name$suffix.png")
        device.dumpWindowHierarchy(instrumentation.targetContext.filesDir.resolve("search-tree.xml"))
    }

    private suspend fun fixture(action: suspend (ClientModel, ClientSearchTest.Fixture) -> Unit) {
        val database = ClientDatabase.get(instrumentation.targetContext)
        val previousSession = database.profiles().activeSession()
        val store = ProfileStore(database, CredentialVault(instrumentation.targetContext))
        val source = ClientSearchTest.Fixture()
        var savedProfile: ServerProfile? = null
        var fixtureModel: ClientModel? = null
        try {
            val profile = store.save(ServerProfile(name = "Search UI fixture", address = source.url)).also { savedProfile = it }
            activity.scenario.onActivity { fixtureModel = ViewModelProvider(it)[ClientModel::class.java] }
            val model = checkNotNull(fixtureModel)
            InstrumentationRegistry.getArguments().getString("nfbFontScale")?.toFloat()?.let { expected ->
                assertEquals(expected, instrumentation.targetContext.resources.configuration.fontScale, 0.01f)
            }
            withContext(Dispatchers.Main) { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            (device.wait(Until.findObject(By.desc("搜索文件")), 5000) ?: error("Header search action missing")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("搜索文件名")), 5000))
            assertFalse("Search paths must not include server/account metadata", device.hasObject(By.textContains(model.state.value.serverLabel)))
            action(model, source)
        } catch (error: Exception) { capture("search-failure"); throw error }
        catch (error: AssertionError) { capture("search-failure"); throw error }
        finally {
            source.releaseSearch.countDown(); source.releaseMetadata.countDown(); source.releasePlayback.countDown()
            withContext(NonCancellable) {
                try { withContext(Dispatchers.Main) { fixtureModel?.disconnect() } } finally {
                    try { savedProfile?.let { store.remove(it) } } finally {
                        try { source.close() } finally {
                            previousSession?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
                        }
                    }
                }
            }
        }
    }
    private fun submit(value: String) {
        val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000)
            ?: throw AssertionError("Native editable search field missing")
        input.text = value
        (device.wait(Until.findObject(By.desc("执行搜索")), 5000) ?: error("Search submit action missing")).click()
    }

    @Test fun globalSearchUsesRootWhileScopeChangesPreserveEntryDirectory(): Unit = runBlocking {
        fixture { model, source ->
            withContext(Dispatchers.Main) { model.back(); model.open(ResourceRef("/library", "/library", "Library", true, "", 0)) }
            withTimeout(5000) { model.state.first { !it.busy && it.path == "/library" } }
            (device.wait(Until.findObject(By.desc("搜索文件")), 5000) ?: error("Search entry missing")).click()
            val bounds = listOf("当前目录", "包含子目录", "全部").map { objectWithText(it).visibleBounds }
            assertTrue("Scope controls need equal widths", bounds.maxOf { it.width() } - bounds.minOf { it.width() } <= 3)
            val input = device.wait(Until.findObject(By.desc("搜索文件名")), 5000) ?: error("Search field missing")
            assertTrue("Search field belongs at the top", input.visibleBounds.top < device.displayHeight * .15f)
            capture("search-scopes-entry")
            objectWithText("当前目录").click(); submit("current-only")
            withTimeout(5000) { model.search.state.first { it.ending == SearchEnding.COMPLETED } }
            assertTrue(source.searchRequests.any { it.startsWith("/api/search/library?") && it.contains("scope=current") })
            objectWithText("全部").click()
            assertEquals("/library", model.search.state.value.basePath)
            assertEquals("/", model.search.state.value.resultBasePath)
            assertEquals("/", model.search.state.value.resultBaseWirePath)
            objectWithText("包含子目录").click()
            assertEquals("/library", model.search.state.value.resultBasePath)
            assertTrue(model.search.state.value.items.isEmpty())
            objectWithText("全部").click(); submit("跨目录+100%")
            withTimeout(5000) { model.search.state.first { it.ending == SearchEnding.COMPLETED } }
            assertTrue(source.searchRequests.any { it.startsWith("/api/search/?") && it.contains("scope=recursive") })
            assertEquals("/library", model.state.value.path)
            resultWithName("跨目录+100%")
            val resultArea = device.wait(Until.findObject(By.desc("搜索结果")), 5000) ?: error("Search results missing")
            assertNotNull("Result parent location is independently visible", resultArea.findObject(By.text("/")))
            capture("search-global-results")
            val cached = model.search.state.value
            val requests = source.searchRequests.size
            clickResult("跨目录+100%")
            val opened = withTimeout(5000) { model.state.first { !it.busy && it.path == "/跨目录+100%" } }
            assertEquals("/%e8%b7%a8%e7%9b%ae%e5%bd%95%2b100%25", opened.wirePath)
            assertTrue("A result directory retains its search source", model.search.state.value.open)
            assertFalse("A result directory displays the browser", opened.searchDisplayed)
            (device.wait(Until.findObject(By.desc("上一级")), 5000) ?: error("Directory return action missing")).click()
            withTimeout(5000) { model.state.first { !it.busy && it.searchDisplayed && it.path == "/library" } }
            assertEquals(cached.query, model.search.state.value.query)
            assertEquals(cached.scope, model.search.state.value.scope)
            assertEquals(cached.items, model.search.state.value.items)
            assertEquals("Returning restores results without another search", requests, source.searchRequests.size)
            resultWithName("跨目录+100%")
        }
    }

    @Test fun actualSearchControlsCancelRetryScopeDetailsAndDirectoryNavigation(): Unit = runBlocking {
        fixture { model, source ->
            submit("slow")
            withTimeout(5000) { model.search.state.first { it.items.isNotEmpty() && it.running } }
            objectWithText("取消").click()
            assertTrue(withContext(Dispatchers.IO) { source.canceled.await(5, TimeUnit.SECONDS) })
            assertEquals(SearchEnding.CANCELED, model.search.state.value.ending)
            objectWithText("当前目录").click()
            assertEquals(SearchScope.CURRENT, model.search.state.value.scope)
            submit("retry")
            withTimeout(5000) { model.search.state.first { it.ending == SearchEnding.FAILED } }
            objectWithText("重新搜索").click()
            withTimeout(5000) { model.search.state.first { it.ending == SearchEnding.COMPLETED } }
            assertEquals("retry", model.search.state.value.query)
            longPressResult("retry")
            assertTrue(device.wait(Until.hasObject(By.text("/retry")), 5000))
            objectWithText("关闭").click()
            val cached = model.search.state.value
            clickResult("retry")
            withTimeout(5000) { model.state.first { !it.busy && it.path == "/retry" } }
            assertTrue(model.search.state.value.open)
            assertFalse(model.state.value.searchDisplayed)
            assertTrue(device.wait(Until.hasObject(By.desc("上一级")), 5000))
            device.findObject(By.desc("上一级")).click()
            withTimeout(5000) { model.state.first { !it.busy && it.searchDisplayed && it.path == "/" } }
            assertEquals(cached.query, model.search.state.value.query)
            assertEquals(cached.items, model.search.state.value.items)
            resultWithName("retry")
        }
    }

    @Test fun returningToFilesCancelsPendingMovieSourceConfirmation(): Unit = runBlocking {
        fixture { model, source ->
            submit("back.mkv")
            withTimeout(5000) { model.search.state.first { it.ending == SearchEnding.COMPLETED } }
            clickResult("back.mkv")
            assertTrue(withContext(Dispatchers.IO) { source.playbackStarted.await(5, TimeUnit.SECONDS) })
            val back = device.wait(Until.findObject(By.desc("返回文件")), 5000) ?: throw AssertionError("Return action missing")
            back.click()
            assertTrue(device.wait(Until.gone(By.desc("返回文件")), 5000))
            source.releasePlayback.countDown()
            withTimeout(5000) { model.state.first { !it.busy } }
            delay(200)
            assertNull("Leaving search must not start the previously selected movie after its identity response arrives", model.state.value.selected)
            assertFalse(model.search.state.value.open)
            assertFalse(model.state.value.searchDisplayed)
        }
    }

    @SdkSuppress(minSdkVersion = 30)
    @Test fun toolbarBackHidesImeBeforeClosingSearch(): Unit = runBlocking {
        fixture { model, _ ->
            fun imeVisible(): Boolean {
                var visible = false
                activity.scenario.onActivity { host ->
                    visible = host.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                }
                return visible
            }
            (device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000) ?: error("Search input missing")).click()
            withTimeout(5000) { while (!imeVisible()) delay(25) }
            (device.wait(Until.findObject(By.desc("返回文件")), 5000) ?: error("Return action missing")).click()
            withTimeout(5000) { while (imeVisible()) delay(25) }
            instrumentation.waitForIdleSync()
            device.waitForIdle()
            assertTrue("First toolbar back preserves the search while dismissing IME", model.state.value.searchDisplayed)
            assertTrue(model.search.state.value.open)
            assertTrue(device.wait(Until.hasObject(By.desc("返回文件")), 5000))
            device.findObject(By.desc("返回文件")).click()
            withTimeout(5000) { model.state.first { !it.searchDisplayed } }
            assertFalse("Second toolbar back closes the search", model.search.state.value.open)
        }
    }

    @Test fun longFilenameAndScopeRemainUsableInPortraitAndLandscape(): Unit = runBlocking {
        fixture { model, _ ->
            val title = "秋日旅行.导演剪辑版.2026.2160p.特别长的完整文件名称.中文字幕.mkv"
            submit(title)
            withTimeout(5000) { model.search.state.first { it.ending == SearchEnding.COMPLETED } }
            resultWithName(title)
            capture("search-results")
            device.setOrientationLeft()
            try {
                val list = device.wait(Until.findObject(By.desc("搜索结果")), 5000) ?: throw AssertionError("Scrollable results missing")
                if (!device.hasObject(By.clazz("android.widget.TextView").text(title))) list.scroll(Direction.DOWN, 0.8f)
                longPressResult(title)
                capture("search-details-top-landscape")
                if (!device.hasObject(By.text("/$title"))) {
                    val content = device.wait(Until.findObject(By.desc("文件详情内容")), 5000)
                        ?: throw AssertionError("Scrollable file details missing")
                    content.scroll(Direction.DOWN, 0.8f)
                }
                assertTrue(device.wait(Until.hasObject(By.text("/$title")), 5000))
                capture("search-details-landscape")
                objectWithText("关闭").click()
                capture("search-landscape")
                assertEquals(title, model.search.state.value.query)
            } finally { device.setOrientationNatural(); device.unfreezeRotation() }
        }
    }
}

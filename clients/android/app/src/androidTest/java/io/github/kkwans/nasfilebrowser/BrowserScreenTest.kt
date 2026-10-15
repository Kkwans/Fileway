package io.github.kkwans.nasfilebrowser

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

/** Installed browser -> actual model/JNI/HTTP directory, not a static mock screen. */
@RunWith(AndroidJUnit4::class)
class BrowserScreenTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun text(value: String) = device.wait(Until.findObject(By.text(value)), 5000)
        ?: throw AssertionError("Missing visible action: $value")
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(250)
        device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/browser-$name.png")
    }
    @Test fun actualListGridDetailsAndDirectoryActions(): Unit = runBlocking {
        val longName = "秋日旅行.导演剪辑版.2026.2160p.特别长的完整文件名称.中文字幕.mkv"
        val database = ClientDatabase.get(instrumentation.targetContext)
        val previousSession = database.profiles().activeSession()
        val presentation = instrumentation.targetContext.getSharedPreferences("file_presentation", android.content.Context.MODE_PRIVATE)
        val previousCompactValue = presentation.getString("compact_grid_size", null)
        val previousCompactSize = CompactGridSize.fromStored(previousCompactValue)
        val store = ProfileStore(database, CredentialVault(instrumentation.targetContext))
        val source = ClientSearchTest.Fixture(listOf("旅行", "文档", "A.mkv", "B.mkv", "C.mkv", longName))
        var savedProfile: ServerProfile? = null
        var fixtureModel: ClientModel? = null
        try {
            val profile = store.save(ServerProfile(name = "我的媒体库", address = source.url)).also { savedProfile = it }
            activity.scenario.onActivity { fixtureModel = ViewModelProvider(it)[ClientModel::class.java] }
            val model = checkNotNull(fixtureModel)
            withContext(Dispatchers.Main) { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy && it.files.size == 6 } }
            if (model.state.value.compactGridSize != CompactGridSize.MEDIUM) {
                withContext(Dispatchers.Main) { model.compactGridSize(CompactGridSize.MEDIUM) }
                withTimeout(5000) { model.state.first { it.compactGridSize == CompactGridSize.MEDIUM } }
            }
            suspend fun chooseLayout(next: String) {
                (device.wait(Until.findObject(By.descStartsWith("切换文件布局")), 5000) ?: error("Layout action missing")).click()
                text(next).click()
                withTimeout(5000) { model.state.first { it.fileLayout.label == next } }
                assertTrue("Selecting a layout closes its menu", device.wait(Until.gone(By.desc("当前选项")), 5000))
            }
            assertTrue(device.wait(Until.hasObject(By.desc("文件网格")), 5000))
            assertFalse("Server identity belongs in settings", device.hasObject(By.text(model.state.value.serverLabel)))
            assertFalse(device.hasObject(By.text("切换服务器")))
            val search = device.wait(Until.findObject(By.desc("搜索文件")), 5000) ?: error("Header search icon missing")
            assertTrue("Search belongs in the first toolbar on the right", search.visibleBounds.top < device.displayHeight * .12f && search.visibleBounds.centerX() > device.displayWidth * .8f)
            val folders = listOf("旅行", "文档").map { text(it).visibleBounds }
            assertTrue("Folders must share cover-grid columns instead of spanning full-width rows", folders[0].left != folders[1].left)
            assertTrue("Folder titles must align in the same grid row", kotlin.math.abs(folders[0].top - folders[1].top) <= 2)
            capture("cover-default")
            chooseLayout("常规列表")
            val list = device.wait(Until.findObject(By.desc("文件列表")), 5000) ?: error("File list missing")
            if (instrumentation.targetContext.resources.configuration.fontScale <= 1.05f)
                assertTrue("Files must occupy at least 60% of the compact portrait screen", list.visibleBounds.height() >= device.displayHeight * .6f)
            capture("list")
            chooseLayout("封面网格")
            val grid = device.wait(Until.findObject(By.desc("文件网格")), 5000) ?: error("File grid missing")
            if (instrumentation.targetContext.resources.configuration.fontScale <= 1.05f) {
                instrumentation.waitForIdleSync()
                val titleHeights = mutableListOf<Int>()
                val offsets = listOf("A.mkv", "B.mkv", "C.mkv", longName).map { name ->
                    var offset: Int? = null
                    // Inspect the final scroll result too, and wait for the
                    // accessibility tree rather than only the app's main queue.
                    for (attempt in 0..6) {
                        device.waitForIdle()
                        val title = device.findObject(By.text(name))?.visibleBounds
                        val size = if (title == null || title.height() == 0) null else device.findObjects(By.text("100.0 MB")).map { it.visibleBounds }
                            .filter { it.height() > 0 && kotlin.math.abs(it.centerX() - title.centerX()) <= 2 && it.top >= title.bottom }.minByOrNull { it.top }
                        if (title != null && size != null) {
                            titleHeights += title.height()
                            val maximumGap = kotlin.math.ceil(8 * instrumentation.targetContext.resources.displayMetrics.density).toInt() + 2
                            assertTrue("Cover names must not reserve an empty second line", size.top - title.bottom <= maximumGap)
                            offset = size.top - title.top
                            break
                        }
                        if (attempt < 6) grid.scroll(Direction.DOWN, .35f)
                    }
                    offset ?: error("Visible metadata missing for $name")
                }
                assertTrue("Cover names, including long names, occupy one line", titleHeights.max() - titleHeights.min() <= 2)
                assertTrue("Cover metadata has the same offset below short and ellipsized names", offsets.max() - offsets.min() <= 2)
            }
            if (!device.hasObject(By.text(longName))) grid.scroll(Direction.DOWN, .8f)
            text(longName).longClick()
            text("文件详情")
            if (!device.hasObject(By.text("/$longName"))) device.findObject(By.desc("文件详情内容")).scroll(Direction.DOWN, .8f)
            text("/$longName")
            capture("details")
            text("关闭").click()
            capture("grid")
            chooseLayout("紧凑网格")
            assertTrue(device.wait(Until.hasObject(By.desc("紧凑文件网格")), 5000))
            text("紧凑 · 中")
            capture("compact-grid")
            for (size in listOf(CompactGridSize.SMALL, CompactGridSize.LARGE, CompactGridSize.MEDIUM)) {
                (device.wait(Until.findObject(By.descStartsWith("切换文件布局")), 5000) ?: error("Layout action missing")).click()
                (device.wait(Until.findObject(By.textContains("${size.columns} 列")), 5000) ?: error("Compact tier missing: ${size.label}")).click()
                withTimeout(5000) { model.state.first { it.compactGridSize == size && it.fileLayout == FileLayout.COMPACT } }
                text("紧凑 · ${size.label}")
                assertTrue("Selecting a compact tier closes its menu", device.wait(Until.gone(By.textContains("${size.columns} 列")), 5000))
                // Fixture has six objects: the first row directly exposes the phone's tier column contract.
                val compact = device.wait(Until.findObject(By.desc("紧凑文件网格")), 5000) ?: error("Compact grid missing")
                compact.scroll(Direction.UP, 1f)
                device.waitForIdle()
                if (instrumentation.targetContext.resources.configuration.screenWidthDp < 600) {
                    val firstRow = listOf("旅行", "文档", "A.mkv", "B.mkv").take(size.columns).map { text(it).visibleBounds }
                    assertEquals("Compact tier must expose ${size.columns} distinct columns", size.columns, firstRow.map { it.centerX() }.distinct().size)
                    assertTrue("Compact tier columns share a row", firstRow.maxOf { it.top } - firstRow.minOf { it.top } <= 2)
                }
                assertFalse("Compact cards must not show file size", device.hasObject(By.text("100.0 MB")))
                assertFalse("Compact cards must not show a time placeholder", device.hasObject(By.text("时间未提供")))
            }
            chooseLayout("无界网格")
            assertTrue(device.wait(Until.hasObject(By.desc("无界文件网格")), 5000))
            capture("unbounded-grid")
            chooseLayout("大图列表")
            assertTrue(device.wait(Until.hasObject(By.desc("大图文件列表")), 5000))
            capture("detail-list")
            chooseLayout("常规列表")
            (device.wait(Until.findObject(By.desc("更多文件操作")), 5000) ?: error("Browser actions missing")).click()
            text("刷新").click()
            withTimeout(5000) { model.state.first { !it.busy && it.files.size == 6 } }
            text("旅行").click()
            withTimeout(5000) { model.state.first { !it.busy && it.path == "/旅行" } }
            text("这个目录还没有文件。")
            device.findObject(By.desc("上一级")).click()
            withTimeout(5000) { model.state.first { !it.busy && it.path == "/" && it.files.size == 6 } }
            text("最近").click(); text("继续观看")
            text("文件").click()
            assertTrue(device.wait(Until.hasObject(By.desc("文件列表")), 5000))
        } catch (error: Throwable) { capture("failure"); throw error }
        finally {
            source.releaseSearch.countDown(); source.releaseMetadata.countDown(); source.releasePlayback.countDown()
            withContext(NonCancellable) {
                val model = fixtureModel
                try {
                    if (model != null && model.state.value.compactGridSize != previousCompactSize) {
                        withContext(Dispatchers.Main) { model.compactGridSize(previousCompactSize) }
                        withTimeout(5000) { model.state.first { it.compactGridSize == previousCompactSize } }
                    }
                } finally {
                    // Restore just this test's changed key, including its originally absent state.
                    try {
                        check(presentation.edit().apply {
                            if (previousCompactValue == null) remove("compact_grid_size") else putString("compact_grid_size", previousCompactValue)
                        }.commit()) { "Unable to restore compact-grid preference" }
                    } finally {
                        try { withContext(Dispatchers.Main) { model?.disconnect() } } finally {
                            try { savedProfile?.let { store.remove(it) } } finally {
                                try { source.close() } finally {
                                    previousSession?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

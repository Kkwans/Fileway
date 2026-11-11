package io.github.kkwans.fileway.tv

import android.graphics.Rect
import android.app.Dialog
import android.os.SystemClock
import android.util.Base64
import android.view.KeyEvent
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.widget.TextView
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.kkwans.nasfilebrowser.app.mediaKey
import io.github.kkwans.nasfilebrowser.data.CredentialVault
import io.github.kkwans.nasfilebrowser.data.ProfileStore
import io.github.kkwans.nasfilebrowser.data.PlaybackSnapshot
import io.github.kkwans.nasfilebrowser.data.ProgressSync
import io.github.kkwans.nasfilebrowser.data.SearchScope
import io.github.kkwans.nasfilebrowser.data.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Actual TvModel, Activity and JNI transport against a loopback-only fixture.
 * Run only on an isolated app installation with an empty TV profile database. */
@RunWith(AndroidJUnit4::class)
class TvNavigationFailureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test fun failureRetryBackAndSourceSwitchRetainTheRightPage() {
        val app = instrumentation.targetContext
        val db = TvDatabase.get(app)
        val store = ProfileStore(db, CredentialVault(app))
        runBlocking {
            withContext(Dispatchers.IO) {
                check(db.profiles().profiles().first().isEmpty()) { "Use an isolated TV app installation with no profiles" }
                check(db.profiles().activeSession() == null) { "Existing TV session must not be replaced" }
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM accounts", emptyArray()).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getLong(0) == 0L) { "Existing accounts must not be modified" }
                }
            }
        }
        val fixtureA = Fixture("A")
        val fixtureB = Fixture("B")
        val profiles = listOf(fixtureA, fixtureB).map {
            ServerProfile(id = "tv-nav-test-${UUID.randomUUID()}", name = "TV navigation fixture", address = it.url)
        }
        var scenario: ActivityScenario<TvActivity>? = null
        lateinit var model: TvModel
        try {
            val activity = ActivityScenario.launch(TvActivity::class.java)
            scenario = activity
            activity.onActivity { model = ViewModelProvider(it)[TvModel::class.java] }
            waitFor("initial restore") { !model.state.value.restoring }
            check(!model.state.value.connected) { "Unexpected existing TV session" }
            activity.onActivity { model.login(fixtureA.url, "nav-fixture", "fixture-only", profiles[0]) }
            waitFor("fixture A login") { model.state.value.connected && !model.state.value.busy && model.context?.profile?.id == profiles[0].id }

            fun action(block: TvModel.() -> Unit) = activity.onActivity { model.block() }
            fun directory(path: String) = waitFor("directory $path") { !model.state.value.busy && model.state.value.path == path && !model.state.value.search }
            fun open(name: String) {
                val item = model.state.value.files.single { it.name == name }
                action { open(item) }
            }
            fun failed() = waitFor("directory failure") { !model.state.value.busy && model.state.value.error != null }
            fun fileFocus(description: String, label: String) {
                waitFor(description, diagnostics = {
                    val window = runCatching {
                        var info = ""
                        activity.onActivity {
                            val decor = it.window.decorView
                            info = "windowFocus=${decor.hasWindowFocus()}, touchMode=${decor.isInTouchMode}, anchor=${model.anchor().key}"
                        }
                        info
                    }.getOrDefault("Activity window unavailable")
                    val state = model.state.value
                    val root = tvWindowRoot()
                    val matches = focusTargets(root, label).joinToString(limit = 6) { it.summary() }
                    "route=${state.route}, path=${state.path}, busy=${state.busy}, $window; " +
                        "target=$label [$matches]; inputFocus=${root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.summary()}"
                }) { focusTargets(tvWindowRoot(), label).any { it.isClickable && it.isFocused } }
            }
            fun remote(operation: String, payload: JSONObject = JSONObject()) {
                activity.onActivity {
                    runBlocking {
                        model.remoteGateway.execute(JSONObject().put("operation", operation)
                            .put("payload", payload).put("sessionEpoch", model.sessionEpoch))
                    }
                }
            }
            fun remoteSettings() = remote("app.navigate", JSONObject().put("route", "SETTINGS"))
            fun touchSettings() {
                waitFor("settings touch target") { focusTargets(tvWindowRoot(), "设置").any { it.isClickable && it.isFocusable } }
                val node = focusTargets(tvWindowRoot(), "设置").first { it.isClickable && it.isFocusable }
                val bounds = Rect().also(node::getBoundsInScreen)
                assertFalse(bounds.isEmpty)
                assertTrue(device.click(bounds.centerX(), bounds.centerY()))
                waitFor("settings opened by touch") { model.state.value.route == TvRoute.SETTINGS && !model.state.value.search }
            }
            fun dpadSettings() {
                val until = SystemClock.elapsedRealtime() + 15_000
                while (SystemClock.elapsedRealtime() < until) {
                    val root = tvWindowRoot()
                    val target = focusTargets(root, "设置").firstOrNull { it.isClickable && it.isFocusable }
                    if (target?.isFocused == true) {
                        assertTrue(device.pressDPadCenter())
                        waitFor("settings opened by D-pad") { model.state.value.route == TvRoute.SETTINGS && !model.state.value.search }
                        return
                    }
                    val current = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (target == null) device.pressDPadDown()
                    else if (current == null) device.pressDPadRight()
                    else {
                        val from = Rect().also(current::getBoundsInScreen)
                        val to = Rect().also(target::getBoundsInScreen)
                        val dx = to.centerX() - from.centerX(); val dy = to.centerY() - from.centerY()
                        when {
                            to.bottom <= from.top -> device.pressDPadUp()
                            to.top >= from.bottom -> device.pressDPadDown()
                            abs(dx) > abs(dy) -> if (dx < 0) device.pressDPadLeft() else device.pressDPadRight()
                            dy < 0 -> device.pressDPadUp()
                            else -> device.pressDPadDown()
                        }
                    }
                    SystemClock.sleep(150)
                }
                error("D-pad could not reach settings")
            }

            action { navigate(TvRoute.FILES) }; directory("/")
            open("Folder A"); directory("/A")
            open("Inner"); directory("/A/Inner")
            val deep = model.state.value.files.single()
            fileFocus("original file focus", deep.name)
            val originalAnchor = model.anchor()

            // Touch, physical remote and phone remote all enter the same
            // returnable settings destination; duplicate entry is idempotent.
            touchSettings()
            assertTrue(device.pressBack()); directory("/A/Inner")
            assertEquals(listOf(deep), model.state.value.files)
            assertEquals(originalAnchor.key, model.anchor().key)
            fileFocus("focus after touch settings", deep.name)
            dpadSettings()
            assertTrue(device.pressBack()); directory("/A/Inner")
            assertEquals(originalAnchor.key, model.anchor().key)
            fileFocus("focus after D-pad settings", deep.name)
            remoteSettings(); remoteSettings(); remote("app.back")
            directory("/A/Inner")
            assertEquals(originalAnchor.key, model.anchor().key)
            fileFocus("focus after phone settings", deep.name)

            // A search started inside settings must keep both return levels.
            remoteSettings()
            action { search("Fixture", SearchScope.GLOBAL) }
            waitFor("search from settings") { model.state.value.route == TvRoute.SETTINGS && model.state.value.searchDone }
            fileFocus("settings search result focus", "SearchDir")
            remoteSettings(); remote("app.back")
            waitFor("settings search restored") { model.state.value.route == TvRoute.SETTINGS && model.state.value.search }
            assertEquals("Fixture", model.state.value.searchQuery)
            assertEquals("/SearchDir", model.anchor().key)
            remote("app.back")
            waitFor("search closes to settings") { model.state.value.route == TvRoute.SETTINGS && !model.state.value.search }
            remote("app.back"); directory("/A/Inner")
            assertEquals(originalAnchor.key, model.anchor().key)

            // An unsuccessful top-level switch leaves settings returnable;
            // a successful switch makes the new root the navigation boundary.
            remoteSettings()
            fixtureA.failNext("/")
            action { navigate(TvRoute.MUSIC) }; failed()
            assertEquals(TvRoute.SETTINGS, model.state.value.route)
            action { assertTrue(backDirectory()) }
            remote("app.back"); directory("/A/Inner")
            remoteSettings()
            action { navigate(TvRoute.MUSIC) }
            waitFor("top-level switch from settings") { model.state.value.route == TvRoute.MUSIC && !model.state.value.busy }
            action { assertFalse(backDirectory()) }
            action { navigate(TvRoute.FILES) }; directory("/")
            open("Folder A"); directory("/A")
            open("Inner"); directory("/A/Inner")
            fileFocus("focus after returning through top navigation", deep.name)

            fixtureA.failNext("/A")
            action { assertTrue(backDirectory()) }; failed()
            assertEquals(TvRoute.FILES, model.state.value.route)
            assertEquals("/A/Inner", model.state.value.path)
            assertEquals(listOf(deep), model.state.value.files)
            assertTrue(model.state.value.error!!.contains("/A"))
            assertEquals(originalAnchor.key, model.anchor().key)
            fileFocus("focus after failed back", deep.name)
            remoteSettings(); remote("app.back")
            waitFor("failed back restored from settings") { model.state.value.error.orEmpty().contains("/A") }
            assertEquals("/A/Inner", model.state.value.path)
            action { retry() }; directory("/A")
            action { assertTrue(backDirectory()) }; directory("/")

            open("Folder A"); directory("/A")
            open("Inner"); directory("/A/Inner")
            fixtureA.failNext("/")
            action { navigate(TvRoute.VIDEO) }; failed()
            assertEquals(TvRoute.FILES, model.state.value.route)
            assertEquals("/A/Inner", model.state.value.path)
            assertEquals(listOf(deep), model.state.value.files)
            assertTrue(model.state.value.error!!.contains(TvRoute.VIDEO.title))
            action { retry() }; directory("/")
            assertEquals(TvRoute.VIDEO, model.state.value.route)
            assertFalse(model.state.value.files.contains(deep))

            action { navigate(TvRoute.FILES) }; directory("/")
            open("Folder A"); directory("/A")
            fixtureA.failNext("/")
            action { navigate(TvRoute.VIDEO) }; failed()
            assertEquals(TvRoute.FILES, model.state.value.route)
            assertEquals("/A", model.state.value.path)
            action { navigate(TvRoute.MUSIC) }
            waitFor("another route can replace a failure") { !model.state.value.busy && model.state.value.route == TvRoute.MUSIC && model.state.value.path == "/" }
            action { navigate(TvRoute.FILES) }; directory("/")
            open("Folder A"); directory("/A")
            val canceled = fixtureA.holdNext("/A/Slow")
            open("Slow")
            assertTrue("slow request reached fixture", canceled.started.await(5, TimeUnit.SECONDS))
            device.pressBack()
            waitFor("back cancels pending directory") { !model.state.value.busy && model.state.value.path == "/A" }
            canceled.release.countDown()
            assertTrue(canceled.finished.await(5, TimeUnit.SECONDS))
            SystemClock.sleep(200)
            assertEquals(TvRoute.FILES, model.state.value.route)
            assertEquals("/A", model.state.value.path)

            val superseded = fixtureA.holdNext("/A/Slow")
            open("Slow")
            assertTrue(superseded.started.await(5, TimeUnit.SECONDS))
            action { navigate(TvRoute.MUSIC) }
            waitFor("new route wins") { !model.state.value.busy && model.state.value.route == TvRoute.MUSIC && model.state.value.path == "/" }
            superseded.release.countDown()
            assertTrue(superseded.finished.await(5, TimeUnit.SECONDS))
            SystemClock.sleep(200)
            assertEquals(TvRoute.MUSIC, model.state.value.route)
            assertEquals("/", model.state.value.path)

            action { navigate(TvRoute.FILES) }; directory("/")
            action { search("Fixture", SearchScope.CURRENT) }
            waitFor("fixture search") { model.state.value.search && model.state.value.searchDone && model.state.value.files.any { it.name == "SearchDir" } }
            fixtureA.failNext("/SearchDir")
            open("SearchDir"); failed()
            assertTrue(model.state.value.search)
            val attempts = fixtureA.count("/SearchDir")
            val searches = fixtureA.searches.get()
            action { refresh() }
            waitFor("explicit refresh repeats search") { model.state.value.searchDone && fixtureA.searches.get() > searches }
            assertEquals(attempts, fixtureA.count("/SearchDir"))

            fixtureA.failNext("/")
            action { assertTrue(backDirectory()) }; failed()
            assertTrue("failed search exit retains the search page", model.state.value.search)
            assertEquals("SearchDir", model.state.value.files.single().name)
            action { retry() }; directory("/")
            action { search("Fixture", SearchScope.CURRENT) }
            waitFor("search after returning to root") { model.state.value.searchDone && model.state.value.files.any { it.name == "SearchDir" } }
            fixtureA.failNext("/SearchDir")
            open("SearchDir"); failed()
            val searchResult = model.state.value.files.single()
            action { retry() }; directory("/SearchDir")
            assertEquals(attempts + 2, fixtureA.count("/SearchDir"))
            open("Child"); directory("/SearchDir/Child")
            action { assertTrue(backDirectory()) }; directory("/SearchDir")
            action { assertTrue(backDirectory()) }
            waitFor("return to original search") { model.state.value.search && model.state.value.route == TvRoute.FILES }
            assertEquals("Fixture", model.state.value.searchQuery)
            assertEquals(SearchScope.CURRENT, model.state.value.searchScope)
            assertEquals(listOf(searchResult), model.state.value.files)
            assertEquals(searchResult.mediaKey, model.anchor().key)
            fileFocus("original search result focus", searchResult.name)
            action { assertTrue(backDirectory()) }; directory("/")

            // The search itself began inside /A; its original parent stack must
            // survive opening the result and walking down another directory.
            open("Folder A"); directory("/A")
            action { search("Fixture", SearchScope.CURRENT) }
            waitFor("search inside /A") { model.state.value.searchDone && model.state.value.files.any { it.path == "/A/SearchDir" } }
            val nestedResult = model.state.value.files.single()
            fileFocus("nested search focus before settings", nestedResult.name)
            val searchCountBeforeSettings = fixtureA.searches.get()
            remoteSettings(); remote("app.back")
            waitFor("search restored from settings") { model.state.value.search && model.state.value.path == "/A" }
            assertEquals("Fixture", model.state.value.searchQuery)
            assertEquals(SearchScope.CURRENT, model.state.value.searchScope)
            assertEquals(listOf(nestedResult), model.state.value.files)
            assertEquals(nestedResult.mediaKey, model.anchor().key)
            assertEquals(searchCountBeforeSettings, fixtureA.searches.get())
            fileFocus("nested search focus after settings", nestedResult.name)
            open("SearchDir"); directory("/A/SearchDir")
            open("Child"); directory("/A/SearchDir/Child")
            action { assertTrue(backDirectory()) }; directory("/A/SearchDir")
            action { assertTrue(backDirectory()) }
            waitFor("return to nested search") { model.state.value.search && model.state.value.path == "/A" }
            assertEquals(listOf(nestedResult), model.state.value.files)
            assertEquals(nestedResult.mediaKey, model.anchor().key)
            action { assertTrue(backDirectory()) }; directory("/A")
            action { assertTrue(backDirectory()) }; directory("/")

            // Opening a result cancels a streaming search. Its partial result is
            // restorable, but the canceled stream must never be marked complete.
            action { navigate(TvRoute.HOME) }
            val partial = fixtureA.holdNextSearch()
            action { search("Fixture", SearchScope.GLOBAL) }
            assertTrue(partial.started.await(5, TimeUnit.SECONDS))
            waitFor("partial home search") { model.state.value.search && model.state.value.busy && !model.state.value.searchDone && model.state.value.files.isNotEmpty() }
            val partialResult = model.state.value.files.single()
            remoteSettings(); remote("app.back")
            waitFor("partial search restored from settings") { model.state.value.search && !model.state.value.busy }
            assertEquals(listOf(partialResult), model.state.value.files)
            assertFalse(model.state.value.searchDone)
            assertTrue(model.state.value.notice.orEmpty().contains("搜索已停止"))
            open("SearchDir"); directory("/SearchDir")
            partial.release.countDown()
            assertTrue(partial.finished.await(5, TimeUnit.SECONDS))
            action { assertTrue(backDirectory()) }
            waitFor("partial search restored") { model.state.value.route == TvRoute.HOME && model.state.value.search }
            assertFalse(model.state.value.busy)
            assertFalse(model.state.value.searchDone)
            assertEquals(listOf(partialResult), model.state.value.files)
            assertEquals(partialResult.mediaKey, model.anchor().key)
            assertTrue(model.state.value.notice.orEmpty().contains("搜索已停止"))
            fileFocus("partial search result focus", partialResult.name)
            action { assertTrue(backDirectory()) }
            assertFalse(model.state.value.search)

            // Beyond the in-memory snapshot budget, navigation remains usable
            // and returning re-runs the search with its original anchor.
            action { navigate(TvRoute.FILES) }; directory("/")
            repeat(9) { depth ->
                action { search("Fixture", SearchScope.CURRENT) }
                waitFor("search depth $depth") { model.state.value.search && model.state.value.searchDone && model.state.value.files.isNotEmpty() }
                open("SearchDir"); directory("/SearchDir".repeat(depth + 1))
            }
            val reSearchCount = fixtureA.searches.get()
            fixtureA.failNextSearch()
            action { assertTrue(backDirectory()) }; failed()
            assertTrue(model.state.value.search)
            assertEquals("/SearchDir".repeat(8), model.state.value.path)
            assertTrue(model.state.value.files.isEmpty())
            remoteSettings(); remote("app.back")
            waitFor("failed search restoration survives settings") { model.state.value.search && model.state.value.error != null }
            action { retry() }
            waitFor("search restoration retries after failure") {
                model.state.value.search && model.state.value.searchDone && model.state.value.error == null &&
                    model.state.value.files.isNotEmpty() && fixtureA.searches.get() >= reSearchCount + 2
            }
            assertEquals("/SearchDir".repeat(9), model.anchor().key)
            fileFocus("focus after search restoration", "SearchDir")
            for (depth in 8 downTo 1) {
                action { assertTrue(backDirectory()) }; directory("/SearchDir".repeat(depth))
                action { assertTrue(backDirectory()) }
                val parentPath = if (depth == 1) "/" else "/SearchDir".repeat(depth - 1)
                waitFor("unwind search depth $depth") { model.state.value.search && model.state.value.path == parentPath }
            }
            action { assertTrue(backDirectory()) }; directory("/")
            action { assertFalse(backDirectory()) }

            // Current-directory search has no server result limit. The large
            // page also falls back to re-search without blocking entry.
            action { search("LargeFixture", SearchScope.CURRENT) }
            waitFor("large fixture search", 30_000) { model.state.value.searchDone && model.state.value.files.size == 4001 }
            open("SearchDir"); directory("/SearchDir")
            val largeSearchCount = fixtureA.searches.get()
            val restoringSearch = fixtureA.holdNextSearch()
            action { assertTrue(backDirectory()) }
            assertTrue("search restoration reached fixture", restoringSearch.started.await(15, TimeUnit.SECONDS))
            waitFor("large search restoration is running") { model.state.value.search && model.state.value.busy }
            remoteSettings()
            restoringSearch.release.countDown()
            assertTrue(restoringSearch.finished.await(5, TimeUnit.SECONDS))
            remote("app.back")
            waitFor("large search restored", 30_000) {
                model.state.value.search && !model.state.value.busy && model.state.value.searchDone &&
                    model.state.value.files.size == 4001 && fixtureA.searches.get() >= largeSearchCount + 2
            }
            assertEquals("/SearchDir", model.anchor().key)
            val beforeLargeSettings = fixtureA.searches.get()
            remoteSettings(); remote("app.back")
            waitFor("large search restored after settings", 30_000) {
                model.state.value.search && !model.state.value.busy && model.state.value.searchDone &&
                    model.state.value.files.size == 4001 && fixtureA.searches.get() > beforeLargeSettings
            }
            assertEquals("/SearchDir", model.anchor().key)

            action { navigate(TvRoute.HOME) }
            waitFor("home") { model.state.value.route == TvRoute.HOME }
            fixtureA.failNext("/A")
            action { open(state.value.rootEntries.single { it.name == "Folder A" }) }; failed()
            assertEquals(TvRoute.HOME, model.state.value.route)
            assertEquals("/", model.state.value.path)
            action { retry() }; directory("/A")
            assertEquals(TvRoute.FILES, model.state.value.route)
            action { assertTrue(backDirectory()) }
            assertEquals(TvRoute.HOME, model.state.value.route)

            action { navigate(TvRoute.FILES) }; directory("/")
            open("Folder A"); directory("/A")
            action { search("Fixture", SearchScope.CURRENT) }
            waitFor("search before account switch") { model.state.value.searchDone && model.state.value.files.any { it.path == "/A/SearchDir" } }
            open("SearchDir"); directory("/A/SearchDir")
            val oldSource = fixtureA.holdNext("/A/SearchDir/Slow")
            open("Slow")
            assertTrue(oldSource.started.await(5, TimeUnit.SECONDS))
            remoteSettings()
            assertEquals(TvRoute.SETTINGS, model.state.value.route)
            action { login(fixtureB.url, "nav-fixture", "fixture-only", profiles[1]) }
            waitFor("fixture B replaces A") { model.state.value.connected && model.context?.profile?.id == profiles[1].id && !model.state.value.restoring }
            oldSource.release.countDown()
            assertTrue(oldSource.finished.await(5, TimeUnit.SECONDS))
            SystemClock.sleep(200)
            assertEquals(TvRoute.HOME, model.state.value.route)
            assertEquals("/", model.state.value.path)
            assertTrue(model.state.value.rootEntries.any { it.name == "Source B" })
            assertFalse(model.state.value.search)
            action { assertFalse(backDirectory()) }
            remoteSettings(); remote("app.back")
            waitFor("settings returns to new account") { model.state.value.route == TvRoute.HOME && !model.state.value.search }
            assertEquals("/", model.state.value.path)
            assertTrue(model.state.value.rootEntries.any { it.name == "Source B" })
        } finally {
            fixtureA.releaseAll(); fixtureB.releaseAll()
            scenario?.close()
            // Only the two random profile IDs created by this test are removed;
            // the preflight refuses to start on an installation with user data.
            runBlocking { profiles.forEach { store.remove(it) } }
            fixtureA.close(); fixtureB.close()
        }
    }

    @Test fun recentAccessAndFileDetailsWorkWithRemoteAndTouch() {
        val app = instrumentation.targetContext
        val db = TvDatabase.get(app)
        val store = ProfileStore(db, CredentialVault(app))
        runBlocking {
            withContext(Dispatchers.IO) {
                check(db.profiles().profiles().first().isEmpty()) { "Use an isolated TV app installation with no profiles" }
                check(db.profiles().activeSession() == null) { "Existing TV session must not be replaced" }
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM accounts", emptyArray()).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getLong(0) == 0L) { "Existing accounts must not be modified" }
                }
            }
        }
        val fixture = Fixture("A")
        fun recent(path: String, name: String, directory: Boolean, accessedAt: Long) = JSONObject()
            .put("id", name).put("path", path).put("wirePath", path).put("name", name)
            .put("isDir", directory).put("accessedAt", accessedAt)
        val now = System.currentTimeMillis()
        val posterName = "收藏海报-" + "a".repeat(125) + "🌿.jpg"
        fixture.setRecent(JSONArray().put(recent("/movie.MKV", "movie.MKV", false, now))
            .put(recent("/A", "Folder A", true, now - 1_000)).put(recent("/poster.jpg", posterName, false, now - 2_000)))
        val profile = ServerProfile(id = "tv-recent-test-${UUID.randomUUID()}", name = "TV recent fixture", address = fixture.url)
        var scenario: ActivityScenario<TvActivity>? = null
        var model: TvModel? = null
        var originalHold: Int? = null
        try {
            val activity = ActivityScenario.launch(TvActivity::class.java)
            scenario = activity
            activity.onActivity { model = ViewModelProvider(it)[TvModel::class.java] }
            val tv = requireNotNull(model)
            waitFor("initial restore") { !tv.state.value.restoring }
            check(!tv.state.value.connected)
            activity.onActivity { tv.login(fixture.url, "nav-fixture", "fixture-only", profile) }
            waitFor("fixture login") { tv.state.value.connected && !tv.state.value.busy }
            waitFor("recent access loaded") { tv.recentAccess.state.value.items.size == 3 && !tv.recentAccess.state.value.loading }
            assertEquals(listOf("Folder A", posterName), tvRecentAccessResources(tv.recentAccess.state.value.items).map { it.name })
            waitFor("recent access shelf") { focusTargets(tvWindowRoot(), "最近访问").isNotEmpty() }
            assertTrue("videos are not shown in recent access", focusTargets(tvWindowRoot(), "movie.MKV").isEmpty())
            assertTrue("home has no detached details button", focusTargets(tvWindowRoot(), "详情").isEmpty())
            assertTrue("home has no detached browse-options button", focusTargets(tvWindowRoot(), "浏览选项").isEmpty())
            fun openRecentAccess() {
                waitFor("recent access entry") { focusTargets(tvWindowRoot(), "最近访问查看全部").any { it.isClickable } }
                val target = focusTargets(tvWindowRoot(), "最近访问查看全部").filter { it.isClickable }
                    .minBy { Rect().also(it::getBoundsInScreen).top }
                val bounds = Rect().also(target::getBoundsInScreen)
                assertTrue(device.click(bounds.centerX(), bounds.centerY()))
                waitFor("all recent access") { tv.state.value.route == TvRoute.RECENT_ACCESS }
            }
            openRecentAccess()
            assertTrue("records use the file's hold action instead of a details button", focusTargets(tvWindowRoot(), "详情").isEmpty())
            val remoteFiles = tv.remoteGateway.snapshot().getJSONObject("content").getJSONArray("files")
            assertEquals(2, remoteFiles.length())
            assertFalse((0 until remoteFiles.length()).any { remoteFiles.getJSONObject(it).getString("name") == "movie.MKV" })
            val preview = remoteFiles.getJSONObject(1)
            assertEquals(posterName, preview.getString("name"))
            activity.onActivity {
                val next = runBlocking {
                    tv.remoteGateway.execute(JSONObject().put("operation", "app.list").put("sessionEpoch", tv.sessionEpoch)
                        .put("payload", JSONObject().put("offset", 1).put("count", 1)))
                }.getJSONArray("files").getJSONObject(0)
                assertEquals(posterName, next.getString("name"))
                assertEquals(preview.getString("id"), next.getString("id"))
            }
            assertTrue(device.pressBack())
            waitFor("records return home") { tv.state.value.route == TvRoute.HOME }
            openRecentAccess()
            fun folderFocus() = waitFor("folder focus") {
                focusTargets(tvWindowRoot(), "Folder A").any { it.isClickable && it.isFocused }
            }
            folderFocus()
            originalHold = tv.state.value.detailsHoldMillis
            activity.onActivity { tv.setDetailsHold(750) }
            val movedDown = SystemClock.uptimeMillis()
            assertTrue(instrumentation.uiAutomation.injectInputEvent(
                KeyEvent(movedDown, movedDown, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0), true))
            assertTrue(device.pressDPadRight())
            waitFor("second file focused while OK is held") {
                focusTargets(tvWindowRoot(), posterName).any { it.isClickable && it.isFocused }
            }
            assertTrue(instrumentation.uiAutomation.injectInputEvent(
                KeyEvent(movedDown, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0), true))
            SystemClock.sleep(200)
            assertEquals("release after focus moves must not open another file", TvRoute.RECENT_ACCESS, tv.state.value.route)
            assertEquals("/", tv.state.value.path)
            assertNull("release after focus moves must not start media", tv.media.state.value.file)
            assertTrue(device.pressDPadLeft()); folderFocus()
            val canceledDown = SystemClock.uptimeMillis()
            assertTrue(instrumentation.uiAutomation.injectInputEvent(
                KeyEvent(canceledDown, canceledDown, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0,
                    0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD), true))
            assertTrue(instrumentation.uiAutomation.injectInputEvent(
                KeyEvent(canceledDown, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0,
                    0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_CANCELED, InputDevice.SOURCE_DPAD), true))
            SystemClock.sleep(200)
            assertEquals("canceled OK must not open the folder", TvRoute.RECENT_ACCESS, tv.state.value.route)
            assertEquals("/", tv.state.value.path)
            assertTrue("canceled OK must not open details", focusTargets(tvWindowRoot(), "详情").isEmpty())
            assertTrue(device.pressDPadCenter())
            waitFor("short OK opens folder") { tv.state.value.path == "/A" && !tv.state.value.busy }
            assertTrue(device.pressBack())
            waitFor("return to recent access") { tv.state.value.route == TvRoute.RECENT_ACCESS && !tv.state.value.busy }
            folderFocus()
            val down = SystemClock.uptimeMillis()
            assertTrue(instrumentation.uiAutomation.injectInputEvent(
                KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0), true))
            SystemClock.sleep(900)
            waitFor("long OK opens details") { focusTargets(tvWindowRoot(), "详情").isNotEmpty() }
            assertEquals("long OK must not open the folder", TvRoute.RECENT_ACCESS, tv.state.value.route)
            assertEquals("/", tv.state.value.path)
            waitFor("details window receives input") {
                instrumentation.uiAutomation.windows.any { window ->
                    window.isFocused && window.root?.packageName?.toString() == app.packageName &&
                        focusTargets(window.root, "详情").isNotEmpty()
                }
            }
            fun focusOpenAction() {
                repeat(5) {
                    if (focusTargets(tvWindowRoot(), "打开").any { it.isClickable && it.isFocused }) return
                    device.pressDPadDown()
                    repeat(8) {
                        if (focusTargets(tvWindowRoot(), "打开").any { it.isClickable && it.isFocused }) return
                        SystemClock.sleep(80)
                    }
                }
            }
            focusOpenAction()
            waitFor("details open action focused") { focusTargets(tvWindowRoot(), "打开").any { it.isClickable && it.isFocused } }
            assertTrue(instrumentation.uiAutomation.injectInputEvent(
                KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0), true))
            SystemClock.sleep(200)
            assertTrue("old OK release must leave details open", focusTargets(tvWindowRoot(), "详情").isNotEmpty())
            assertNull("old OK release must not start media", tv.media.state.value.file)
            assertEquals("old OK release must not open the folder", "/", tv.state.value.path)
            val backInjected = device.pressBack()
            waitFor("details closed after remote Back", diagnostics = { "pressBack=$backInjected" }) {
                focusTargets(tvWindowRoot(), "详情").isEmpty()
            }
            folderFocus()
            val backgroundDown = SystemClock.uptimeMillis()
            assertTrue(instrumentation.uiAutomation.injectInputEvent(
                KeyEvent(backgroundDown, backgroundDown, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0), true))
            var overlay: Dialog? = null
            try {
                activity.onActivity { host ->
                    overlay = Dialog(host).apply { setContentView(TextView(host).apply { text = "fixture overlay" }); show() }
                }
                waitFor("other window focused") {
                    instrumentation.uiAutomation.windows.any { window ->
                        window.isFocused && focusTargets(window.root, "fixture overlay").isNotEmpty()
                    }
                }
                SystemClock.sleep(900)
                assertTrue("window loss must not open details in the background",
                    instrumentation.uiAutomation.windows.none { focusTargets(it.root, "详情").isNotEmpty() })
                assertTrue(instrumentation.uiAutomation.injectInputEvent(
                    KeyEvent(backgroundDown, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0), true))
            } finally {
                activity.onActivity { overlay?.dismiss() }
            }
            folderFocus()
            val folder = focusTargets(tvWindowRoot(), "Folder A").first { it.isClickable }
            val bounds = Rect().also(folder::getBoundsInScreen)
            assertTrue(device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.centerY(), 160))
            waitFor("touch hold opens details") { focusTargets(tvWindowRoot(), "详情").isNotEmpty() }
            val touchBackInjected = device.pressBack()
            waitFor("details closed after touch", diagnostics = { "pressBack=$touchBackInjected" }) {
                focusTargets(tvWindowRoot(), "详情").isEmpty()
            }
            folderFocus()
            activity.onActivity {
                runBlocking {
                    tv.remoteGateway.execute(JSONObject().put("operation", "app.key")
                        .put("payload", JSONObject().put("key", "details")).put("sessionEpoch", tv.sessionEpoch))
                }
            }
            waitFor("phone detail key opens details") { focusTargets(tvWindowRoot(), "详情").isNotEmpty() }
        } catch (failure: Throwable) {
            runCatching {
                val output = requireNotNull(app.getExternalFilesDir(null))
                device.takeScreenshot(File(output, "recent-details-failure.png"))
                device.dumpWindowHierarchy(File(output, "recent-details-failure.xml"))
            }
            throw failure
        } finally {
            if (originalHold != null) scenario?.onActivity { model?.setDetailsHold(requireNotNull(originalHold)) }
            scenario?.close()
            runBlocking { store.remove(profile) }
            fixture.close()
        }
    }

    @Test fun loadingAnEarlierHomeShelfKeepsTheFocusedResource() {
        val app = instrumentation.targetContext
        val db = TvDatabase.get(app)
        val store = ProfileStore(db, CredentialVault(app))
        runBlocking {
            withContext(Dispatchers.IO) {
                check(db.profiles().profiles().first().isEmpty()) { "Use an isolated TV app installation with no profiles" }
                check(db.profiles().activeSession() == null) { "Existing TV session must not be replaced" }
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM accounts", emptyArray()).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getLong(0) == 0L) { "Existing accounts must not be modified" }
                }
            }
        }
        val fixture = Fixture("A")
        val profile = ServerProfile(id = "tv-home-test-${UUID.randomUUID()}", name = "TV home fixture", address = fixture.url)
        var scenario: ActivityScenario<TvActivity>? = null
        var accountKey: String? = null
        try {
            val activity = ActivityScenario.launch(TvActivity::class.java)
            scenario = activity
            lateinit var model: TvModel
            activity.onActivity { model = ViewModelProvider(it)[TvModel::class.java] }
            waitFor("initial restore") { !model.state.value.restoring }
            check(!model.state.value.connected)
            activity.onActivity { model.login(fixture.url, "nav-fixture", "fixture-only", profile) }
            waitFor("fixture login") { model.state.value.connected && !model.state.value.busy }
            waitFor("initial root focus", diagnostics = {
                "focus=${tvWindowRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.summary()}"
            }) { focusTargets(tvWindowRoot(), "Folder A").any { it.isClickable && it.isFocused } }
            assertTrue(device.pressDPadRight())
            waitFor("user moves to second root") { focusTargets(tvWindowRoot(), "Source A").any { it.isClickable && it.isFocused } }
            assertTrue(device.pressDPadLeft())
            waitFor("user returns to first root") { focusTargets(tvWindowRoot(), "Folder A").any { it.isClickable && it.isFocused } }
            val account = requireNotNull(model.context).account
            accountKey = account.key
            val snapshot = PlaybackSnapshot(account.key, "/owned-home-test.mp4", "home-test-identity",
                "/owned-home-test.mp4", "/owned-home-test.mp4", "owned-home-test.mp4",
                1_000, 10_000, System.currentTimeMillis(), ProgressSync.PENDING)
            runBlocking { db.playback().save(snapshot) }
            waitFor("new continue-watching shelf") {
                model.state.value.recentPlayback.any { it.resourceKey == snapshot.resourceKey }
            }
            instrumentation.waitForIdleSync()
            val stableUntil = SystemClock.elapsedRealtime() + 1_000
            while (SystemClock.elapsedRealtime() < stableUntil) {
                assertTrue("Focus disappeared after the earlier shelf arrived: " +
                    tvWindowRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.summary(),
                    focusTargets(tvWindowRoot(), "Folder A").any { it.isClickable && it.isFocused })
                SystemClock.sleep(50)
            }
            waitFor("focused root after earlier shelf arrives", diagnostics = {
                "focus=${tvWindowRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.summary()}"
            }) { focusTargets(tvWindowRoot(), "Folder A").any { it.isClickable && it.isFocused } }
        } finally {
            scenario?.close()
            runBlocking {
                accountKey?.let { db.playback().clear(it) }
                store.remove(profile)
            }
            fixture.close()
        }
    }

    private fun waitFor(
        description: String,
        timeout: Long = 15_000,
        diagnostics: (() -> String)? = null,
        predicate: () -> Boolean,
    ) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        error("Timed out: $description" + (diagnostics?.let { "; ${it()}" } ?: ""))
    }

    private fun tvWindowRoot(): AccessibilityNodeInfo? {
        val appPackage = instrumentation.targetContext.packageName
        return instrumentation.uiAutomation.windows.filter {
            it.root?.packageName?.toString() == appPackage
        }.maxByOrNull { it.layer }?.root
    }

    private fun focusTargets(root: AccessibilityNodeInfo?, label: String): Set<AccessibilityNodeInfo> {
        val matches = mutableSetOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) {
                matches += node
                generateSequence(node.parent) { it.parent }.take(8)
                    .filter { it.isFocusable && it.isClickable }
                    .forEach { matches += it }
            }
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(root)
        return matches
    }

    private fun AccessibilityNodeInfo.summary(): String {
        val bounds = Rect().also(::getBoundsInScreen)
        return "${text ?: contentDescription ?: className} clickable=$isClickable focusable=$isFocusable focused=$isFocused bounds=$bounds"
    }

    private class Gate {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
    }

    private class Fixture(private val source: String) : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val failures = ConcurrentHashMap<String, AtomicInteger>()
        private val counts = ConcurrentHashMap<String, AtomicInteger>()
        private val gates = ConcurrentHashMap<String, Gate>()
        private val searchGate = AtomicReference<Gate?>()
        private val searchFailures = AtomicInteger()
        private val recentResponse = AtomicReference("[]")
        private val allGates = ConcurrentHashMap.newKeySet<Gate>()
        val searches = AtomicInteger()
        val url = "http://127.0.0.1:${server.localPort}"
        private val payload = JSONObject().put("user", JSONObject().put("id", if (source == "A") 91 else 92).put("username", "nav-fixture"))
        private val token = "header." + Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
        private val acceptor = Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                Thread({ try { serve(socket) } catch (_: Exception) { } finally { sockets.remove(socket); socket.close() } }, "tv-nav-fixture-request").apply { isDaemon = true; start() }
            }
        }, "tv-nav-fixture").apply { isDaemon = true; start() }

        fun failNext(path: String) { failures.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet() }
        fun count(path: String) = counts[path]?.get() ?: 0
        fun holdNext(path: String): Gate = Gate().also { check(gates.putIfAbsent(path, it) == null); allGates.add(it) }
        fun holdNextSearch(): Gate = Gate().also { check(searchGate.compareAndSet(null, it)); allGates.add(it) }
        fun failNextSearch() { searchFailures.incrementAndGet() }
        fun setRecent(rows: JSONArray) { recentResponse.set(rows.toString()) }
        fun releaseAll() = allGates.forEach { it.release.countDown() }

        private fun serve(socket: Socket) {
            socket.soTimeout = 10_000
            val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val request = (input.readLine() ?: return).split(' ')
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: return
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            val body = CharArray(headers["content-length"]?.toIntOrNull() ?: 0)
            var read = 0
            while (read < body.size) { val n = input.read(body, read, body.size - read); if (n < 0) return; read += n }
            val endpoint = request[1].substringBefore('?')
            if (endpoint == "/api/login") { reply(socket, 200, token, "text/plain"); return }
            check(headers["x-auth"] == token)
            if (endpoint.startsWith("/api/resources/")) {
                val path = endpoint.removePrefix("/api/resources")
                counts.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                val gate = gates.remove(path)
                try {
                    gate?.started?.countDown()
                    gate?.release?.await(10, TimeUnit.SECONDS)
                    val failure = failures[path]
                    if (failure != null && failure.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) { reply(socket, 503, ""); return }
                    reply(socket, 200, directory(path).toString()); return
                } finally { gate?.finished?.countDown() }
            }
            if (endpoint.startsWith("/api/search/")) {
                searches.incrementAndGet()
                if (searchFailures.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) { reply(socket, 503, ""); return }
                val query = URI(request[1]).rawQuery.orEmpty().split('&').associate { field ->
                    field.substringBefore('=') to URLDecoder.decode(field.substringAfter('='), "UTF-8")
                }["query"]
                val item = JSONObject().put("path", "SearchDir").put("name", "SearchDir").put("dir", true).put("size", 0)
                val result = buildString {
                    append(JSONObject().put("type", "result").put("item", item)).append('\n')
                    if (query == "LargeFixture") repeat(4000) { index ->
                        val name = "item-$index.txt"
                        val file = JSONObject().put("path", name).put("name", name).put("dir", false).put("size", 0)
                        append(JSONObject().put("type", "result").put("item", file)).append('\n')
                    }
                }
                val count = if (query == "LargeFixture") 4001 else 1
                val summary = JSONObject().put("type", "summary").put("reason", "completed").put("count", count).toString() + "\n"
                val gate = searchGate.getAndSet(null)
                if (gate == null) { reply(socket, 200, result + summary, "application/x-ndjson"); return }
                try {
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.write(result.toByteArray(Charsets.UTF_8)); output.flush()
                    gate.started.countDown()
                    gate.release.await(10, TimeUnit.SECONDS)
                    output.write(summary.toByteArray(Charsets.UTF_8)); output.flush()
                } finally { gate.finished.countDown() }
                return
            }
            if (request[0] == "GET" && endpoint == "/api/recent") { reply(socket, 200, recentResponse.get()); return }
            if (request[0] == "GET" && endpoint in setOf("/api/favorites", "/api/favorites/groups", "/api/media/playback/recent")) {
                reply(socket, 200, "[]"); return
            }
            reply(socket, 503, "")
        }

        private fun directory(path: String): JSONObject {
            fun entry(path: String, name: String, directory: Boolean) = JSONObject().put("path", path).put("wirePath", path).put("name", name).put("isDir", directory)
            val items = when (path) {
                "/" -> listOf(entry("/A", "Folder A", true), entry("/source", "Source $source", true))
                "/A" -> listOf(entry("/A/Inner", "Inner", true), entry("/A/Slow", "Slow", true))
                "/A/Inner" -> listOf(entry("/A/Inner/deep.txt", "deep.txt", false))
                "/A/Slow" -> listOf(entry("/A/Slow/late.txt", "late.txt", false))
                "/SearchDir" -> listOf(entry("/SearchDir/Child", "Child", true), entry("/SearchDir/found.txt", "found.txt", false))
                "/SearchDir/Child" -> listOf(entry("/SearchDir/Child/child.txt", "child.txt", false))
                "/A/SearchDir" -> listOf(entry("/A/SearchDir/Child", "Child", true), entry("/A/SearchDir/Slow", "Slow", true))
                "/A/SearchDir/Child" -> listOf(entry("/A/SearchDir/Child/child.txt", "child.txt", false))
                else -> emptyList()
            }
            return JSONObject().put("path", path).put("wirePath", path).put("name", path.substringAfterLast('/').ifEmpty { "/" }).put("items", JSONArray(items))
        }

        private fun reply(socket: Socket, status: Int, body: String, contentType: String = "application/json") {
            val bytes = body.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status ${if (status == 200) "OK" else "Unavailable"}\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                write(bytes); flush()
            }
        }

        override fun close() { releaseAll(); server.close(); sockets.forEach { runCatching { it.close() } }; acceptor.join(1000) }
    }
}

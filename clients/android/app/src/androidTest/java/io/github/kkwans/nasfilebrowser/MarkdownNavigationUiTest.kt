package io.github.kkwans.nasfilebrowser

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import io.github.kkwans.nasfilebrowser.app.DocumentPreviewController
import io.github.kkwans.nasfilebrowser.ui.ClientTheme
import io.github.kkwans.nasfilebrowser.ui.DocumentPreviewScreen
import io.github.kkwans.nasfilebrowser.ui.LibraryTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Real production screen, original document controller/fixture, fresh AX and
 * one checked pointer action per step. No new parser or screenshot surrogate. */
@RunWith(AndroidJUnit4::class)
internal class MarkdownNavigationUiTest : LibraryUiHarness() {
    private fun snapshot() = freshAccessibilityBounds(instrumentation, Rect(0, 0, device.displayWidth, device.displayHeight))
    private suspend fun <T> atStage(stage: String, block: suspend () -> T): T = try {
        withTimeout(5000) { block() }
    } catch (failure: TimeoutCancellationException) {
        throw AssertionError("Markdown navigation stage=$stage timed out after 5000 ms", failure)
    }
    private suspend fun await(stage: String, condition: () -> Boolean) = atStage(stage) {
        while (!condition()) delay(25)
    }
    private fun visible(label: String) = snapshot()?.any { it.visible && it.text == label } == true
    private fun visibleDescription(label: String) = snapshot()?.any { it.visible && it.description == label } == true

    private data class InputWindow(val id: Int, val display: Int?)
    private data class BoundTap(val fresh: FreshAccessibilityNodeBounds, val owner: UiObject2, val window: InputWindow)
    private fun activeWindow(): InputWindow? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        return try {
            if (!root.refresh() || root.windowId < 0 || root.packageName?.toString() != instrumentation.targetContext.packageName) return null
            val window = root.window
            try {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    if (window == null) return null
                    InputWindow(root.windowId, window.displayId)
                } else InputWindow(root.windowId, null)
            } finally { window?.recycle() }
        } finally { root.recycle() }
    }
    private fun boundTap(label: String): BoundTap? {
        val window = activeWindow() ?: return null
        val nodes = snapshot() ?: return null
        val leaf = nodes.filter { it.visible && (it.text == label || it.description == label) }.singleOrNull() ?: return null
        val candidates = nodes.filter { it.visible && it.enabled && it.clickable && it.bounds.contains(leaf.bounds) }
        val minimum = candidates.minOfOrNull { it.bounds.width().toLong() * it.bounds.height() }
        val target = candidates.filter { it.bounds.width().toLong() * it.bounds.height() == minimum }.singleOrNull() ?: return null
        val objects = device.findObjects(By.pkg(instrumentation.targetContext.packageName).clickable(true))
        var retained: UiObject2? = null
        return try {
            val owner = objects.filter { it.applicationPackage == instrumentation.targetContext.packageName &&
                (window.display == null || it.displayId == window.display) && it.isEnabled && it.isClickable && it.visibleBounds == target.bounds &&
                it.text == target.text && it.contentDescription == target.description && it.className == target.className }.singleOrNull()
                ?: return null
            if (activeWindow() != window) return null
            retained = owner
            BoundTap(target, owner, window)
        } catch (_: StaleObjectException) {
            null // Only a pre-input readiness sample may be discarded.
        } finally { objects.filter { it !== retained }.forEach { it.recycle() } }
    }

    private suspend fun tap(stage: String, label: String, minimum48dp: Boolean = true) {
        val configuration = Configurator.getInstance()
        val oldIdle = configuration.waitForIdleTimeout
        var target: BoundTap? = null
        try {
            // Pinned UiObject2 property reads otherwise add an implicit 10s idle
            // wait. Keep the existing 5s readiness budget and restore exactly.
            configuration.setWaitForIdleTimeout(0L)
            val ready = atStage("$stage-owner-ready") {
                var result: BoundTap? = null
                while (result == null) {
                    result = boundTap(label)
                    if (result == null) delay(25)
                }
                requireNotNull(result)
            }
            target = ready
            if (minimum48dp) {
                val px = instrumentation.targetContext.resources.displayMetrics.density * 48 - 1
                assertTrue("A real 48dp owner is required", ready.fresh.bounds.width() >= px && ready.fresh.bounds.height() >= px)
            }
            assertEquals(ready.window, activeWindow())
            assertEquals(ready.fresh.bounds, ready.owner.visibleBounds)
            assertTrue(ready.owner.isEnabled && ready.owner.isClickable)
            // One AndroidX real pointer click. No semantic action, callback,
            // fallback or retry after the input has begun.
            ready.owner.click()
        } finally {
            try { target?.owner?.recycle() } finally { configuration.setWaitForIdleTimeout(oldIdle) }
        }
    }

    @Test fun outlineAndInternalLinkScrollTheRealDocumentBackClosesOnlyTheOutlineAndRotationKeepsReading(): Unit = runBlocking {
        val source = "# Start\n\n[Jump owned](#Target)\n\n" +
            (1..60).joinToString("\n\n") { "Owned filler $it." } +
            "\n\n# Target\n\nOwned destination paragraph.\n\n" +
            (61..90).joinToString("\n\n") { "Owned filler $it." } + "\n\n## Last\n\nOwned ending.\n"
        val fixture = DocumentEditFixture(source.toByteArray(), name = "navigation.md")
        val owner = fixture.session("markdown-navigation-ui")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = DocumentPreviewController(instrumentation.targetContext, scope, { it === owner }, reader = fixture.reader)
        val closed = AtomicInteger()
        var oldOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        var captured = false
        try {
            activity.scenario.onActivity { host ->
                oldOrientation = host.requestedOrientation
                captured = true
                host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                host.setContent { ClientTheme(darkTheme = false) { LibraryTheme {
                    DocumentPreviewScreen(controller, { closed.incrementAndGet() }, { error("No download action in navigation fixture") }, false)
                } } }
            }
            await("portrait-ready") {
                var portrait = false
                activity.scenario.onActivity { portrait = it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                    it.window.decorView.width < it.window.decorView.height }
                portrait
            }
            main { controller.bind(owner); controller.open(fixture.file, owner.api.id) }
            atStage("document-read") { controller.state.first { !it.loading && it.text != null } }
            await("initial-heading-visible") { visible("Start") }
            assertFalse(visible("Owned destination paragraph."))
            tap("open-outline-initial", "目录")
            await("outline-initial-visible") { visibleDescription("关闭目录") }
            tap("choose-target", "目录项 2，Target，1级标题")
            await("target-visible-after-outline") { !visibleDescription("关闭目录") && visible("Owned destination paragraph.") }
            assertEquals(source, controller.state.value.text!!.text)

            tap("open-outline-for-back", "目录")
            await("outline-for-back-visible") { visibleDescription("关闭目录") }
            assertTrue(device.pressBack())
            await("outline-back-dismissed") { !visibleDescription("关闭目录") && visible("Owned destination paragraph.") }
            assertEquals(0, closed.get())
            assertNotNull(controller.state.value.file)

            tap("open-outline-for-start", "目录")
            await("outline-for-start-visible") { visibleDescription("关闭目录") }
            tap("choose-start", "目录项 1，Start，1级标题")
            await("start-link-visible") { !visibleDescription("关闭目录") && visible("Jump owned") }
            tap("internal-fragment", "Jump owned", minimum48dp = false)
            await("target-visible-after-fragment") { visible("Owned destination paragraph.") }
            assertEquals(instrumentation.targetContext.packageName, device.currentPackageName)

            activity.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
            await("landscape-target-visible") {
                var landscape = false
                activity.scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    it.window.decorView.width > it.window.decorView.height }
                landscape && visible("Owned destination paragraph.")
            }
            assertTrue(fixture.writes.isEmpty())
            assertEquals(source, controller.state.value.text!!.text)
            assertTrue(device.pressBack())
            await("document-closed") { controller.state.value.file == null }
            assertEquals(1, closed.get())
        } finally { withContext(NonCancellable) {
            try { main { controller.close() } } finally {
                scope.cancel()
                if (captured) activity.scenario.onActivity { it.requestedOrientation = oldOrientation }
            }
        } }
    }
}

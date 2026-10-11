package io.github.kkwans.nasfilebrowser

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Appearance/layout only. Copy remains covered by the existing independently
 * failing functional case; this test never invokes it or replaces its result.
 * Palette/32-language token contracts remain in the existing six JVM tests.
 */
@RunWith(AndroidJUnit4::class)
internal class MarkdownCodeAppearanceUiTest : LibraryUiHarness() {
    private data class Sample(val language: String, val code: String, val marker: String)
    private val samples = listOf(
        Sample("kotlin", "val ownedName = \"Owned long source stays readable across a narrow native code panel\"\nprintln(ownedName)", "val ownedName"),
        Sample("json", "{\"name\": \"owned\",\n \"count\": 3,\n \"enabled\": true}", "\"count\": 3"),
        Sample("sql", "SELECT name, COUNT(*) AS total\nFROM owned_items WHERE enabled = TRUE;", "FROM owned_items"),
        Sample("yaml", "name: owned\nenabled: true\nitems: [alpha, beta]", "name: owned"),
    )
    private data class NativeDetails(val bounds: Rect, val state: String?)
    private data class Panel(val sample: Sample, val label: FreshAccessibilityNodeBounds,
        val body: FreshAccessibilityNodeBounds, val wrap: FreshAccessibilityNodeBounds,
        val copy: FreshAccessibilityNodeBounds, val wrapState: String?)
    private val screen get() = Rect(0, 0, device.displayWidth, device.displayHeight)
    private val density get() = instrumentation.targetContext.resources.displayMetrics.density
    private fun snapshot() = freshAccessibilityBounds(instrumentation, screen)

    /** Supplements the shared values-only sampler with raw bounds/stateDescription.
     * Every node is refreshed/recycled; no live or cached node survives a query.
     */
    private fun details(target: FreshAccessibilityNodeBounds): NativeDetails? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val packageName = instrumentation.targetContext.packageName
        if (root.packageName?.toString() != packageName) { root.recycle(); return null }
        val window = root.windowId
        var valid = true
        val found = mutableListOf<NativeDetails>()
        fun visit(node: AccessibilityNodeInfo) {
            try {
                if (!node.refresh() || node.windowId != window) { valid = false; return }
                if (node.isVisibleToUser && node.isClickable == target.clickable &&
                    node.text?.toString() == target.text && node.contentDescription?.toString() == target.description) {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (bounds.contains(target.bounds)) found += NativeDetails(bounds,
                        AccessibilityNodeInfoCompat.wrap(node).stateDescription?.toString())
                }
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index)
                    if (child == null) valid = false else visit(child)
                }
            } finally { node.recycle() }
        }
        visit(root)
        val active = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        return try {
            if (valid && active.refresh() && active.windowId == window && active.packageName?.toString() == packageName)
                found.singleOrNull() else null
        } finally { active.recycle() }
    }

    private fun panel(sample: Sample, nodes: List<FreshAccessibilityNodeBounds>): Panel? {
        val label = nodes.singleOrNull { it.visible && it.text == sample.language } ?: return null
        val body = nodes.filter { it.visible && it.text?.contains(sample.marker) == true }
            .minByOrNull { it.bounds.width().toLong() * it.bounds.height() } ?: return null
        fun aligned(description: String) = nodes.singleOrNull { it.visible && it.enabled && it.clickable &&
            it.description == description && kotlin.math.abs(it.bounds.centerY() - label.bounds.centerY()) <= density * 2 }
        val wrap = aligned("横向滚动代码") ?: aligned("自动换行代码") ?: return null
        val copy = aligned("复制代码") ?: return null
        val labelDetails = details(label) ?: return null
        val bodyDetails = details(body) ?: return null
        val wrapDetails = details(wrap) ?: return null
        val copyDetails = details(copy) ?: return null
        // Labels/controls must be fully visible. Horizontal source overflow is
        // intentional only while the real code control is in scrolling mode.
        if (listOf(label to labelDetails, wrap to wrapDetails, copy to copyDetails)
                .any { (node, native) -> node.bounds != native.bounds }) return null
        if (body.bounds.top != bodyDetails.bounds.top || body.bounds.bottom != bodyDetails.bounds.bottom) return null
        if (wrap.description == "横向滚动代码" && body.bounds != bodyDetails.bounds) return null
        return Panel(sample, label, body, wrap, copy, wrapDetails.state)
    }
    private suspend fun panels(wanted: List<Sample>): List<Panel> = withTimeout(5000) {
        var result: List<Panel>? = null
        while (result == null) {
            val nodes = snapshot()
            if (nodes != null) {
                val found = wanted.map { panel(it, nodes) }
                if (found.all { it != null }) result = found.filterNotNull()
            }
            if (result == null) delay(25)
        }
        requireNotNull(result)
    }
    private fun checkLayout(panels: List<Panel>) {
        for (panel in panels) {
            for (control in listOf(panel.wrap, panel.copy)) {
                assertTrue("${panel.sample.language} action requires a complete 48dp owner",
                    control.bounds.width() >= 48 * density - 1 && control.bounds.height() >= 48 * density - 1)
            }
            assertTrue("Language label cannot overlap wrap", panel.label.bounds.right <= panel.wrap.bounds.left)
            assertTrue("Wrap and copy owners cannot overlap", panel.wrap.bounds.right <= panel.copy.bounds.left)
            assertFalse(Rect.intersects(panel.wrap.bounds, panel.copy.bounds))
            assertTrue("Code text must be below its native icon toolbar",
                panel.body.bounds.top >= maxOf(panel.wrap.bounds.bottom, panel.copy.bounds.bottom))
        }
        assertTrue("Code cards cannot overlap", panels.sortedBy { it.label.bounds.top }.zipWithNext().all { (a, b) ->
            a.body.bounds.bottom <= minOf(b.wrap.bounds.top, b.copy.bounds.top)
        })
        val nodes = requireNotNull(snapshot())
        assertFalse("Compact toolbar must show icons, not duplicate text actions", nodes.any {
            it.visible && it.text in setOf("复制代码", "已复制代码", "自动换行", "横向滚动", "自动换行代码", "横向滚动代码")
        })
    }
    private fun tapWrap(panel: Panel) {
        val packageName = instrumentation.targetContext.packageName
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: error("Missing owned Markdown window")
        val windowId: Int
        val displayId: Int
        try {
            check(root.refresh() && root.packageName?.toString() == packageName)
            windowId = root.windowId
            val window = root.window
            displayId = try { if (android.os.Build.VERSION.SDK_INT >= 30) window?.displayId ?: 0 else 0 }
                finally { window?.recycle() }
        } finally { root.recycle() }
        check(requireNotNull(snapshot()).singleOrNull { it.visible && it.enabled && it.clickable &&
            it.description == panel.wrap.description && it.bounds == panel.wrap.bounds } != null)
        val configurator = Configurator.getInstance()
        val previousIdle = configurator.waitForIdleTimeout
        configurator.waitForIdleTimeout = 0
        try {
            val objects = device.findObjects(By.pkg(packageName).clazz(requireNotNull(panel.wrap.className))
                .clickable(true).enabled(true).desc(requireNotNull(panel.wrap.description)))
            try {
                val owner = objects.filter { it.displayId == displayId && it.visibleBounds == panel.wrap.bounds }.single()
                val active = instrumentation.uiAutomation.rootInActiveWindow ?: error("Missing owned Markdown window")
                try { check(active.refresh() && active.windowId == windowId && active.packageName?.toString() == packageName) }
                finally { active.recycle() }
                owner.click() // One real pointer click; never a fallback or retry.
            } finally { objects.forEach { it.recycle() } }
        } finally { configurator.waitForIdleTimeout = previousIdle }
    }
    private suspend fun wrapped(expected: Boolean): Panel = withTimeout(5000) {
        var found: Panel? = null
        while (found == null) {
            val current = panels(samples.take(1)).single()
            if (current.wrap.description == if (expected) "横向滚动代码" else "自动换行代码") {
                if (current.wrapState == if (expected) "当前自动换行" else "当前横向滚动") found = current
            }
            if (found == null) delay(25)
        }
        requireNotNull(found)
    }

    private fun capture(theme: String, page: String, panels: List<Panel>, observerNull: Boolean, scrolled: Boolean) {
        check(observerNull)
        check(device.currentPackageName == instrumentation.targetContext.packageName)
        check(snapshot() != null)
        var focused = false
        activity.scenario.onActivity { focused = it.hasWindowFocus() && it.window.decorView.isShown }
        check(focused)
        checkLayout(panels)
        val directory = File(instrumentation.targetContext.cacheDir, "markdown-code-appearance").apply { check(isDirectory || mkdirs()) }
        val name = "$theme-$page-${SystemClock.uptimeMillis()}"
        val png = File(directory, "$name.png")
        // Same raw, lossless UiAutomation screenshot path as the owned CSV gate.
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(device.displayWidth, bitmap.width); assertEquals(device.displayHeight, bitmap.height)
            png.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val evidence = JSONObject().put("theme", theme).put("page", page).put("observerNull", observerNull)
            .put("screenshot", png.absolutePath).put("rawDisplayWidth", device.displayWidth).put("rawDisplayHeight", device.displayHeight)
            .put("scrolled", scrolled).put("copyInvoked", false).put("nativeColorComparison", false).put("textToolbarAbsent", true)
            .put("panels", JSONArray(panels.map { panel -> JSONObject().put("language", panel.sample.language)
                .put("bodyVisible", true).put("labelBounds", panel.label.bounds.flattenToString())
                .put("bodyBounds", panel.body.bounds.flattenToString()).put("copyBounds", panel.copy.bounds.flattenToString())
                .put("copyEnabledClickable", panel.copy.enabled && panel.copy.clickable)
                .put("wrapBounds", panel.wrap.bounds.flattenToString()).put("wrapAction", panel.wrap.description)
                .put("wrapState", panel.wrapState) }))
        val json = File(directory, "$name.json").apply { writeText(evidence.toString(2)) }
        instrumentation.sendStatus(2, Bundle().apply {
            putString("stream", "MARKDOWN_CODE_APPEARANCE theme=$theme page=$page observer=null screenshot=${png.absolutePath} evidence=${json.absolutePath}\n")
        })
    }

    /** Failure evidence must not depend on the layout predicate that failed.
     * Only the actual owned fixture may be captured, never an old user page. */
    private fun failureCapture(theme: String, stage: String) {
        check(device.currentPackageName == instrumentation.targetContext.packageName)
        val nodes = requireNotNull(snapshot()).filter { it.visible }
        check(samples.any { sample -> nodes.any { it.text == sample.language } &&
            nodes.any { it.text?.contains(sample.marker) == true } })
        var ownedHost = false
        activity.scenario.onActivity { host ->
            ownedHost = host.hasWindowFocus() && host.window.decorView.isAttachedToWindow && host.window.decorView.isShown
        }
        check(ownedHost)
        val directory = File(instrumentation.targetContext.cacheDir, "markdown-code-appearance").apply { check(isDirectory || mkdirs()) }
        val png = File(directory, "$theme-failed-$stage-${SystemClock.uptimeMillis()}.png")
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            check(bitmap.width == device.displayWidth && bitmap.height == device.displayHeight)
            png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        instrumentation.sendStatus(2, Bundle().apply {
            putString("stream", "MARKDOWN_CODE_APPEARANCE_FAILURE result=FAIL theme=$theme stage=$stage screenshot=${png.absolutePath} appearancePass=false\n")
        })
    }

    private suspend fun appearance(dark: Boolean) {
        val theme = if (dark) "dark" else "light"
        val source = "# Owned code\n\n" + samples.joinToString("\n\n") { "~~~${it.language}\n${it.code}\n~~~" }
        val document = (markdownPreview(decodeDocumentText(source.toByteArray())) as MarkdownPreview.Reading).document
        val inset = mutableStateOf(0.dp)
        val observerNull = AtomicBoolean()
        var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        var orientationCaptured = false
        var stage = "fixture"
        try {
            activity.scenario.onActivity { host ->
                originalOrientation = host.requestedOrientation
                orientationCaptured = true
                host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                host.setContent { ClientTheme(darkTheme = dark) { LibraryTheme {
                    val noObserver = LocalMarkdownCodeDiagnostics.current == null
                    check(noObserver) // Production default; no provider or diagnostic pointer modifier.
                    SideEffect { observerNull.set(noObserver) }
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                            MarkdownBody(document, { error("Owned appearance fixture contains no links") },
                                Modifier.fillMaxSize().padding(horizontal = inset.value))
                        }
                    }
                } } }
            }
            stage = "portrait"
            withTimeout(5000) {
                var portrait = false
                while (!portrait) {
                    activity.scenario.onActivity { portrait = it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                        it.window.decorView.width < it.window.decorView.height }
                    if (!portrait) delay(25)
                }
            }
            stage = "kotlin-json-layout"
            val first = panels(samples.take(2)); checkLayout(first)
            assertEquals("当前自动换行", first.first().wrapState)
            // Copy is only measured for owner/hit geometry. Its existing failure
            // is kept independent, with no extra clipboard access or copy tap.
            stage = "wrap-off"
            tapWrap(first.first()); wrapped(false)
            stage = "width-recompose"
            main { inset.value = 1.dp } // Real width/recomposition change, same code/document.
            wrapped(false)
            stage = "wrap-on"
            tapWrap(panels(samples.take(1)).single()); wrapped(true)
            stage = "kotlin-json-capture"
            main { inset.value = 0.dp }
            capture(theme, "kotlin-json", panels(samples.take(2)), observerNull.get(), scrolled = false)

            stage = "sql-yaml-scroll"
            val current = snapshot()
            val allVisible = current != null && samples.drop(2).all { panel(it, current) != null }
            if (!allVisible) {
                val viewport = requireNotNull(snapshot()).filter { it.visible && it.scrollable }
                    .maxByOrNull { it.bounds.width().toLong() * it.bounds.height() }?.bounds ?: error("Owned Markdown vertical list unavailable")
                assertTrue("One real list swipe must be delivered", device.swipe(viewport.centerX(),
                    viewport.top + viewport.height() * 4 / 5, viewport.centerX(), viewport.top + viewport.height() / 5, 20))
            }
            stage = "sql-yaml-capture"
            capture(theme, "sql-yaml", panels(samples.drop(2)), observerNull.get(), scrolled = !allVisible)
            stage = "source-preserved"
            assertEquals(source, document.state.content)
        } catch (failure: Throwable) {
            runCatching { failureCapture(theme, stage) }.onFailure { captureFailure ->
                runCatching {
                    instrumentation.sendStatus(2, Bundle().apply {
                        putString("stream", "MARKDOWN_CODE_APPEARANCE_FAILURE result=FAIL theme=$theme stage=$stage screenshot=unavailable captureExceptionType=${captureFailure.javaClass.simpleName} appearancePass=false\n")
                    })
                }
            }
            throw failure
        } finally { if (orientationCaptured) activity.scenario.onActivity { it.requestedOrientation = originalOrientation } }
    }

    @Test fun lightCodeCardsRenderOwnedKotlinJsonSqlYamlWithCompactControls(): Unit = runBlocking { appearance(false) }
    @Test fun darkCodeCardsRenderOwnedKotlinJsonSqlYamlWithCompactControls(): Unit = runBlocking { appearance(true) }
}

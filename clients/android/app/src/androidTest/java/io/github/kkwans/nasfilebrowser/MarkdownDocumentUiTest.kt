package io.github.kkwans.nasfilebrowser

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class MarkdownDocumentUiTest : LibraryUiHarness() {
    private data class NativeWindowOwner(
        val activity: Int, val decor: Int, val token: Int, val windowId: Int, val display: Int,
        val attached: Boolean, val shown: Boolean, val focused: Boolean, val resumed: Boolean,
        val callback: String?, val callbackIdentity: Int, val sampledAt: Long,
    ) {
        val ready get() = attached && shown && focused && resumed && windowId >= 0
        fun sameWindow(other: NativeWindowOwner) = activity == other.activity && decor == other.decor &&
            token == other.token && windowId == other.windowId && display == other.display
    }
    private data class OwnedButton(val bounds: Rect, val owner: NativeWindowOwner)
    private var lastWindowOwner: NativeWindowOwner? = null

    /** View.getAccessibilityWindowId is hidden. Create a local public node on main instead. */
    private fun nativeWindowOwner(): NativeWindowOwner? {
        var sampled: NativeWindowOwner? = null
        activity.scenario.onActivity { host ->
            val decor = host.window.decorView
            val node = decor.createAccessibilityNodeInfo()
            val windowId = try { node?.windowId ?: -1 } finally { node?.recycle() }
            sampled = NativeWindowOwner(System.identityHashCode(host), System.identityHashCode(decor),
                decor.windowToken?.let(System::identityHashCode) ?: 0, windowId, decor.display?.displayId ?: -1,
                decor.isAttachedToWindow,
                decor.isShown && decor.windowVisibility == android.view.View.VISIBLE && decor.width > 0 && decor.height > 0,
                host.hasWindowFocus(), !host.isFinishing && !host.isDestroyed &&
                    host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED),
                host.window.callback?.javaClass?.name, host.window.callback?.let(System::identityHashCode) ?: 0,
                android.os.SystemClock.uptimeMillis())
        }
        lastWindowOwner = sampled
        return sampled
    }
    private var codeDiagnosticCount = 0
    private fun codeDiagnostic(value: MarkdownCodeDiagnostic) {
        if (codeDiagnosticCount++ >= 64) return
        instrumentation.sendStatus(2, android.os.Bundle().apply {
            putString("stream", "MARKDOWN_CODE phase=${value.phase} instance=${value.instance} copied=${value.copied} failed=${value.failed} " +
                "exceptionType=${value.exceptionType ?: "none"} geometry=${value.geometry ?: "none"}\n")
        })
    }
    /** One fresh unique title, then its closest actual clickable ancestor.
     * No cached node survives the query and no rejected tap is retried. */
    private fun buttonBounds(label: String): OwnedButton? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val packageName = instrumentation.targetContext.packageName
        if (!root.refresh() || root.packageName?.toString() != packageName) { root.recycle(); return null }
        val window = root.windowId
        val native = try { nativeWindowOwner() } catch (failure: Throwable) { root.recycle(); throw failure }
        if (native == null || !native.ready || native.windowId != window) { root.recycle(); return null }
        val matches = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo): Boolean {
            try {
                if (!node.refresh() || node.windowId != window) return false
                if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label))
                    matches.add(AccessibilityNodeInfo.obtain(node))
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: return false
                    if (!collect(child)) return false
                }
                return true
            } finally { node.recycle() }
        }
        try {
            if (!collect(root) || matches.size != 1) return null
            var current: AccessibilityNodeInfo? = matches.removeAt(0)
            var bounds: Rect? = null
            try {
                var depth = 0
                while (current != null && depth++ < 32) {
                    val node = current ?: break
                    if (!node.refresh() || !node.isVisibleToUser || !node.isEnabled || node.windowId != window) return null
                    if (node.isClickable) {
                        bounds = Rect().also(node::getBoundsInScreen).takeIf {
                            it.intersect(Rect(0, 0, device.displayWidth, device.displayHeight)) && !it.isEmpty
                        }
                        break
                    }
                    val parent = node.parent
                    node.recycle(); current = parent
                }
            } finally { current?.recycle() }
            val active = instrumentation.uiAutomation.rootInActiveWindow ?: return null
            return try {
                if (bounds == null || !active.refresh() || active.windowId != window || active.packageName?.toString() != packageName) null
                else {
                    val currentOwner = nativeWindowOwner()
                    if (currentOwner != null && currentOwner.ready && native.sameWindow(currentOwner)) OwnedButton(bounds, currentOwner) else null
                }
            }
            finally { active.recycle() }
        } finally { matches.forEach { it.recycle() } }
    }
    private suspend fun click(label: String) {
        val driver = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("nfbInputDriver") ?: "automation"
        require(driver in setOf("automation", "shell"))
        lastWindowOwner = null
        val target = try { withTimeout(5000) {
            var target: OwnedButton? = null
            while (target == null) { target = buttonBounds(label); if (target == null) delay(25) }
            requireNotNull(target)
        } } catch (failure: Throwable) {
            OwnedUiTraceRule.trace("markdown-owner-not-ready label=$label last=$lastWindowOwner exception=${failure.javaClass.simpleName}")
            throw failure
        }
        val bounds = target.bounds
        val density = instrumentation.targetContext.resources.displayMetrics.density
        assertTrue("$label requires a 48dp actual clickable owner: $bounds",
            bounds.width() >= 48 * density - 1 && bounds.height() >= 48 * density - 1)
        OwnedUiTraceRule.trace("markdown-control=$label driver=$driver bounds=$bounds")
        val started = android.os.SystemClock.uptimeMillis()
        OwnedUiTraceRule.trace("markdown-tap-start label=$label driver=$driver t=$started native=${target.owner}")
        try {
            if (driver == "shell") {
                val output = device.executeShellCommand("input touchscreen tap ${bounds.centerX()} ${bounds.centerY()}")
                OwnedUiTraceRule.trace("markdown-tap-return label=$label driver=shell start=$started end=${android.os.SystemClock.uptimeMillis()} " +
                    "window=${target.owner.windowId} outputLength=${output.length} output=${output.take(256).replace('\n', ' ')} dispatch=unverified")
            } else {
                OwnedTouchInput.tap(instrumentation, bounds, tracePrefix = "markdown-$label")
                OwnedUiTraceRule.trace("markdown-tap-return label=$label driver=automation start=$started end=${android.os.SystemClock.uptimeMillis()} " +
                    "window=${target.owner.windowId} injectionChecked=true dispatch=unverified")
            }
        } catch (failure: Throwable) {
            OwnedUiTraceRule.trace("markdown-tap-error label=$label driver=$driver start=$started end=${android.os.SystemClock.uptimeMillis()} exception=${failure.javaClass.simpleName}")
            throw failure
        }
    }
    private fun ownedCapture(stage: String) {
        val packageName = instrumentation.targetContext.packageName
        check(device.currentPackageName == packageName)
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: error("Owned active window unavailable")
        try { check(root.packageName?.toString() == packageName) } finally { root.recycle() }
        var focused = false
        activity.scenario.onActivity { focused = it.hasWindowFocus() && it.window.decorView.isShown }
        check(focused)
        val name = "markdown-first-owned-$stage-${android.os.SystemClock.uptimeMillis()}.png"
        check(device.takeScreenshot(java.io.File(instrumentation.targetContext.cacheDir, name)))
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "MARKDOWN_FIRST_OWNED_CAPTURE=$name\n") })
    }
    private fun failureEvidence(stage: String) {
        runCatching { ownedCapture("$stage-failed") }
            .onFailure { captureUnavailable(stage, it.javaClass.simpleName) }
        if (stage == "reading") {
            // Compare only owned expected text: never print an unknown clipboard.
            val clipboard = runCatching { copiedText() }
            val copiedCode = clipboard.getOrNull()?.trimEnd('\n')?.let { it == "println(\"needle\")" }
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString("stream", "MARKDOWN_COPY_OWNED_CODE_MATCHES=$copiedCode exceptionType=${clipboard.exceptionOrNull()?.javaClass?.simpleName ?: "none"}\n")
            })
        }
    }
    private fun successEvidence(stage: String) {
        runCatching { ownedCapture("$stage-passed") }
            .onFailure { captureUnavailable(stage, it.javaClass.simpleName) }
    }
    private fun captureUnavailable(stage: String, type: String) {
        instrumentation.sendStatus(2, android.os.Bundle().apply {
            putString("stream", "MARKDOWN_FIRST_CAPTURE_UNAVAILABLE stage=$stage type=$type visual=UNVERIFIED\n")
        })
    }
    private fun copiedText(): String {
        var value = ""
        instrumentation.runOnMainSync {
            value = instrumentation.targetContext.getSystemService(ClipboardManager::class.java)
                .primaryClip!!.getItemAt(0).text.toString()
        }
        return value
    }

    @Test fun readingSourceSearchCodeCopyEditAndDownloadRetainTheOriginalDocument(): Unit = runBlocking {
        val source = "# Owned heading\n\nA **strong** needle.\n\n```kotlin\nprintln(\"needle\")\n```\n"
        val fixture = DocumentEditFixture(source.toByteArray(), name = "README.mdown")
        val owner = fixture.session("markdown-ui")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = DocumentPreviewController(instrumentation.targetContext, scope, { it === owner }, reader = fixture.reader)
        val diagnostics = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("nfbMarkdownCodeDiagnostics") == "true"
        var edited: DocumentText? = null
        var downloads = 0
        activity.scenario.onActivity { host ->
            model = ViewModelProvider(host)[ClientModel::class.java]
            host.setContent { ClientTheme(darkTheme = false) { LibraryTheme {
                CompositionLocalProvider(LocalMarkdownCodeDiagnostics provides if (diagnostics) ::codeDiagnostic else null) {
                    DocumentPreviewScreen(controller, {}, { assertEquals(fixture.sourceWire, it.wirePath); downloads++ }, true,
                        onEdit = { file, text -> assertEquals(fixture.sourceWire, file.wirePath); edited = text })
                }
            } } }
        }
        try {
            main { controller.bind(owner); controller.open(fixture.file, owner.api.id) }
            val opened = withTimeout(5000) { controller.state.first { !it.loading && it.text != null } }
            text("Owned heading"); text("UTF-8 · 阅读 · 只读")
            assertFalse(device.hasObject(By.clazz("android.widget.EditText")))
            click("复制代码"); action("已复制代码")
            assertEquals("println(\"needle\")", copiedText().trimEnd('\n'))
            click("横向滚动代码"); action("自动换行代码")
            click("自动换行代码"); action("横向滚动代码")
            click("源码"); text("UTF-8 · 只读")
            val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000) ?: error("Missing source search")
            input.text = "needle"
            withTimeout(5000) { controller.state.first { !it.searching && it.search.matches.size == 2 } }
            text("1 / 2 处匹配")
            click("复制全文"); assertEquals(source, copiedText())
            click("阅读"); text("Owned heading")
            click("编辑文本"); click("下载文档")
            instrumentation.runOnMainSync { assertSame(opened.text, edited); assertEquals(1, downloads) }
            assertEquals(source, controller.state.value.text!!.text)
            assertTrue(fixture.writes.isEmpty())
            successEvidence("reading")
        } catch (error: Throwable) { failureEvidence("reading"); throw error
        } finally { main { controller.close() }; scope.cancel() }
    }

    @Test fun oversizedMarkdownFallsBackToFullSearchableSourceWithoutEnablingWritesOrDownloads(): Unit = runBlocking {
        val source = " ".repeat(MARKDOWN_READING_LIMIT.toInt()) + "\ntail-owned"
        val fixture = DocumentEditFixture(source.toByteArray(), modify = false, name = "large.mkd")
        val owner = fixture.session("markdown-large")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = DocumentPreviewController(instrumentation.targetContext, scope, { it === owner }, reader = fixture.reader)
        activity.scenario.onActivity { host ->
            model = ViewModelProvider(host)[ClientModel::class.java]
            host.setContent { ClientTheme(darkTheme = false) { LibraryTheme {
                DocumentPreviewScreen(controller, {}, { error("Download must remain disabled") }, canDownload = false)
            } } }
        }
        try {
            main { controller.bind(owner); controller.open(fixture.file, owner.api.id) }
            withTimeout(5000) { controller.state.first { !it.loading && it.text != null } }
            text("Markdown 超过 2 MiB 阅读渲染上限，已显示完整源码；原文件未改变。")
            text("UTF-8 · 只读")
            assertFalse(device.hasObject(By.desc("编辑文本")))
            assertFalse(action("下载文档").isEnabled)
            val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000) ?: error("Missing fallback search")
            input.text = "tail-owned"
            withTimeout(5000) { controller.state.first { !it.searching && it.search.matches.size == 1 } }
            text("1 / 1 处匹配")
            assertEquals(source, controller.state.value.text!!.text)
            assertEquals(source.indexOf("tail-owned"), controller.state.value.search.matches.single().start)
            assertTrue(fixture.writes.isEmpty())
            successEvidence("oversized")
        } catch (error: Throwable) { failureEvidence("oversized"); throw error
        } finally { main { controller.close() }; scope.cancel() }
    }

    @Test fun externalIntentsHaveOnlyAnApprovedDestinationAndNoCredentialsOrUriGrants() {
        val intent = markdownExternalIntent("HTTPS://example.invalid/doc?q=one#section")!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https", intent.data!!.scheme)
        assertEquals("example.invalid", intent.data!!.host)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories)
        assertNull(intent.extras); assertNull(intent.clipData); assertEquals(0, intent.flags)
        for (destination in listOf("javascript:alert(1)", "content://fixture/file", "intent://owned/", "https://user:secret@example.invalid/",
            "http://2130706433/owned", "http://0177.0.0.1/owned", "http://[0:0:0:0:0:0:0:1]/owned"))
            assertNull(markdownExternalIntent(destination))
    }

    @Test fun taskRowsExposeBothCompletionSpellingsAsReadonlyWithoutChangingTheSource() {
        val source = "- [x] Owned completed lower\n- [X] Owned completed upper\n- [ ] Owned waiting\n"
        val document = (markdownPreview(decodeDocumentText(source.toByteArray())) as MarkdownPreview.Reading).document
        activity.scenario.onActivity { host -> host.setContent { ClientTheme(darkTheme = true) { LibraryTheme {
            MarkdownBody(document, { error("Task rows must not open a link") })
        } } } }
        try {
            val rows = mutableListOf<Rect>()
            for ((label, checked) in listOf("Owned completed lower" to true, "Owned completed upper" to true, "Owned waiting" to false)) {
                val row = text(label)
                try {
                    assertTrue(label, row.isCheckable)
                    assertEquals(label, checked, row.isChecked)
                    assertFalse("Reading tasks must remain readonly", row.isClickable)
                    rows.add(Rect(row.visibleBounds))
                } finally { row.recycle() }
            }
            val nodes = freshAccessibilityBounds(instrumentation, Rect(0, 0, device.displayWidth, device.displayHeight))
                ?: error("Owned task accessibility tree unavailable")
            assertFalse("Task syntax must not be rendered a second time", nodes.any {
                it.visible && it.text?.trim() in setOf("-", "[x]", "[X]", "[ ]")
            })
            assertTrue("Task labels must not overlap", rows.zipWithNext().all { (before, after) -> before.bottom <= after.top })
            assertEquals(source, document.state.content)
            successEvidence("tasks")
        } catch (error: Throwable) { failureEvidence("tasks"); throw error }
    }

    @Test fun nativeLinksRequireAnActualTapAndUnsafeLinksRemainPlainText(): Unit = runBlocking {
        val document = (markdownPreview(decodeDocumentText(("[Owned external](https://example.invalid/owned)\n\n" +
            "[Blocked](javascript:alert%281%29)").toByteArray())) as MarkdownPreview.Reading).document
        val opened = mutableListOf<String>()
        activity.scenario.onActivity { host -> host.setContent { ClientTheme(darkTheme = false) { LibraryTheme {
            MarkdownBody(document, { opened += it })
        } } } }
        text("Owned external")
        main { assertTrue(opened.isEmpty()) }
        val safeBounds = text("Owned external").visibleBounds
        assertTrue(device.click(safeBounds.left + 5, safeBounds.centerY()))
        withTimeout(5000) {
            while (true) {
                var delivered = false
                main { delivered = opened == listOf("https://example.invalid/owned") }
                if (delivered) break
                delay(25)
            }
        }
        val blockedBounds = text("Blocked").visibleBounds
        assertTrue(device.click(blockedBounds.left + 5, blockedBounds.centerY()))
        main { assertEquals(listOf("https://example.invalid/owned"), opened) }
    }
}

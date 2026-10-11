package io.github.kkwans.nasfilebrowser

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.graphics.Rect
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
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

/** One owned production-screen journey. Pointer actions use the existing
 * AndroidX input helper; no callback/semantic action or input retries. */
@RunWith(AndroidJUnit4::class)
internal class MarkdownExtendedSyntaxUiTest : LibraryUiHarness() {
    private fun nodes() = freshAccessibilityBounds(instrumentation, Rect(0, 0, device.displayWidth, device.displayHeight)).orEmpty()
    private fun visibleText(fragment: String) = nodes().any { it.visible && it.text?.contains(fragment) == true }
    private fun visibleDescription(label: String) = nodes().any { it.visible && it.description == label }
    private suspend fun await(stage: String, condition: () -> Boolean) {
        try { withTimeout(5000) { while (!condition()) delay(25) } }
        catch (failure: TimeoutCancellationException) { throw AssertionError("Markdown extensions stage=$stage timed out after 5000 ms", failure) }
    }
    private fun ownedCapture(stage: String) {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: error("Owned Markdown window missing")
        try { assertEquals(instrumentation.targetContext.packageName, root.packageName?.toString()) }
        finally { root.recycle() }
        var focused = false
        activity.scenario.onActivity { focused = it.hasWindowFocus() && it.window.decorView.isShown }
        assertTrue("Capture requires the actual focused owned activity", focused)
        capture("markdown-extensions-owned-$stage")
    }

    @Test fun closedMetadataInlineTocAndRepeatedFootnotesNavigateAndReturnWithoutChangingSourceBytes(): Unit = runBlocking {
        val dollar = '$'
        val targetTitle = "Target ${dollar}E=mc^2${dollar}"
        val source = ("---\ntitle: Owned metadata\ncustom: !!not-an-object {owned: true}\n---\n\n# Start\n\n[TOC]\n\n" +
            "Owned inline ${dollar}x_1+x_2${dollar}.\n\n[^one]\n\n" +
            (1..45).joinToString("\n\n") { "Owned extension filler $it." } +
            "\n\n# $targetTitle\n\nOwned target paragraph.\n\n" +
            "| Formula | Result |\n| --- | --- |\n| ${dollar}\\frac{1}{2}${dollar} | Owned table |\n\n[^one]\n\n" +
            (46..65).joinToString("\n\n") { "Owned extension filler $it." } +
            "\n\n[^one]: Owned footnote first ${dollar}\\sqrt{4}=2${dollar}.\n\tOwned footnote continuation.\n\n    - Owned list item\n\n    ```kotlin\n    println(1)\n    ```\n").replace("\n", "\r\n")
        val original = byteArrayOf(-17, -69, -65) + source.toByteArray(Charsets.UTF_8)
        val fixture = DocumentEditFixture(original, name = "owned-extensions.md")
        val owner = fixture.session("markdown-extended-syntax-ui")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = DocumentPreviewController(instrumentation.targetContext, scope, { it === owner }, reader = fixture.reader)
        val closed = AtomicInteger()
        val clipboard = instrumentation.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var previousClip: ClipData? = null
        try {
            activity.scenario.onActivity { host ->
                previousClip = clipboard.primaryClip
                host.setContent { ClientTheme(darkTheme = false) { LibraryTheme {
                    DocumentPreviewScreen(controller, { closed.incrementAndGet() }, { error("Owned readonly fixture cannot download") }, false)
                } } }
            }
            main { controller.bind(owner); controller.open(fixture.file, owner.api.id) }
            withTimeout(5000) { controller.state.first { !it.loading && it.text != null } }
            await("closed-metadata-reading") { visibleDescription("YAML 元数据") && visibleDescription("正文目录：$targetTitle") }
            await("native-math-ready") { !visibleText("公式正在排版") }
            assertFalse(visibleText("公式无法排版")); assertFalse(visibleText("数学排版组件不可用"))
            assertFalse("Front matter starts compact and folded", visibleText("title: Owned metadata"))
            ownedCapture("reading")
            clickOwnedDescription(instrumentation, device, "YAML 元数据")
            await("metadata-original-visible") { visibleText("title: Owned metadata") && visibleText("!!not-an-object") }
            clickOwnedDescription(instrumentation, device, "YAML 元数据")
            await("metadata-folded") { !visibleText("title: Owned metadata") }
            clickOwnedDescription(instrumentation, device, "正文目录：$targetTitle")
            await("inline-toc-target") { visibleText("Owned target paragraph.") && visibleDescription("脚注 1，第 2 处引用") }
            clickOwnedDescription(instrumentation, device, "脚注 1，第 2 处引用")
            await("repeat-reference-same-note") { visibleText("Owned footnote first") && visibleDescription("返回脚注 1 的引用") }
            assertFalse(visibleText("公式无法排版")); assertFalse(visibleText("公式图形不可用"))
            ownedCapture("footnote")
            assertTrue(device.pressBack())
            await("back-returns-without-closing-document") { visibleDescription("脚注 1，第 2 处引用") }
            assertEquals(0, closed.get()); assertNotNull(controller.state.value.file)
            clickOwnedText(instrumentation, device, "源码")
            await("original-source-mode") { visibleText("复制全文") }
            clickOwnedText(instrumentation, device, "复制全文")
            await("copy-original-complete") { visibleText("已复制全文") }
            main { assertEquals(source, clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
            assertEquals(source, controller.state.value.text!!.text)
            assertArrayEquals(original, io.github.kkwans.nasfilebrowser.data.encodeEditedDocument(
                io.github.kkwans.nasfilebrowser.data.documentEditorText(controller.state.value.text!!), controller.state.value.text!!))
            assertTrue(fixture.writes.isEmpty())
            clickOwnedText(instrumentation, device, "阅读")
            await("reading-reopens") { visibleDescription("YAML 元数据") && visibleDescription("脚注 1，第 1 处引用") }
            clickOwnedDescription(instrumentation, device, "脚注 1，第 1 处引用")
            await("first-reference-same-note-after-reopen") { visibleText("Owned footnote first") &&
                visibleText("Owned footnote continuation.") && visibleDescription("返回脚注 1 的引用") }
            clickOwnedDescription(instrumentation, device, "返回脚注 1 的引用")
            await("return-first-reference") { visibleDescription("脚注 1，第 1 处引用") }
            assertTrue(device.pressBack())
            await("document-close") { controller.state.value.file == null }
            assertEquals(1, closed.get())
        } finally { withContext(NonCancellable) {
            try { main { controller.close(); previousClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip() } }
            finally { scope.cancel(); owner.api.close() }
        } }
    }
}

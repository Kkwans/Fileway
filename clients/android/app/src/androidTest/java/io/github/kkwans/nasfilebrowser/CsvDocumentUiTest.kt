package io.github.kkwans.nasfilebrowser

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Owned documents only; every navigation/edit entry is exercised through the real UI. */
@RunWith(AndroidJUnit4::class)
internal class CsvDocumentUiTest : LibraryUiHarness() {
    private val screen get() = Rect(0, 0, device.displayWidth, device.displayHeight)
    private val density get() = instrumentation.targetContext.resources.displayMetrics.density
    private fun snapshot() = freshAccessibilityBounds(instrumentation, screen)

    private fun await(label: String, predicate: (List<FreshAccessibilityNodeBounds>) -> Boolean): List<FreshAccessibilityNodeBounds> {
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            val nodes = snapshot()
            if (nodes != null && predicate(nodes)) return nodes
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        error("CSV UI did not reach $label within 5000ms")
    }
    private fun visibleText(label: String) = await(label) { nodes -> nodes.any { it.visible && it.text == label } }
        .first { it.visible && it.text == label }

    private fun target(nodes: List<FreshAccessibilityNodeBounds>, label: String): FreshAccessibilityNodeBounds? {
        val leaf = nodes.firstOrNull { it.visible && it.enabled && it.description == label }
            ?: nodes.firstOrNull { it.visible && it.enabled && it.text == label } ?: return null
        return nodes.filter { it.visible && it.enabled && it.clickable && it.bounds.contains(leaf.bounds) }
            .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }
    }
    private fun tapBounds(bounds: Rect) {
        val driver = InstrumentationRegistry.getArguments().getString("nfbInputDriver", "automation")
        require(driver == "automation" || driver == "shell")
        if (driver == "shell") device.executeShellCommand("input touchscreen tap ${bounds.centerX()} ${bounds.centerY()}")
        else OwnedTouchInput.tap(instrumentation, bounds, tracePrefix = "csv")
    }
    private fun click(label: String) {
        val nodes = await("enabled action $label") { target(it, label) != null }
        val bounds = target(nodes, label)!!.bounds
        assertTrue("$label must retain a 48dp touch target: $bounds", bounds.width() >= 48 * density - 1 && bounds.height() >= 48 * density - 1)
        tapBounds(bounds)
    }
    private fun checked(label: String, expected: Boolean) {
        await("$label checked=$expected") { nodes ->
            nodes.any { it.visible && (it.description == label || it.text == label) && it.checkable && it.checked == expected }
        }
    }
    private fun delimiterState(choice: String) {
        await("delimiter selected=$choice") { nodes ->
            nodes.any { it.visible && it.description == "选择表格分隔符" } &&
                withTextNode("分隔符：$choice") { node ->
                    node.contentDescription?.toString() == "选择表格分隔符" &&
                        AccessibilityNodeInfoCompat.wrap(node).stateDescription?.toString() == "当前选择：$choice"
                }
        }
    }
    private fun chooseDelimiter(choice: String) {
        click("选择表格分隔符")
        click("分隔符：$choice")
        delimiterState(choice)
    }
    private fun swipe(x1: Int, y1: Int, x2: Int, y2: Int) {
        assertTrue("Real CSV scroll must be injected", device.swipe(x1, y1, x2, y2, 24))
    }
    private fun scrollUntil(label: String, step: () -> Unit) {
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            val nodes = snapshot()
            if (nodes != null && nodes.any { it.visible && it.text == label } &&
                characterVisible(label, 0) && characterVisible(label, label.lastIndex)) return
            step()
        } while (SystemClock.uptimeMillis() < deadline)
        error("Real scroll did not expose $label within 5000ms")
    }

    /** Public API supported by pinned Compose 1.12.1: no layout/model callback. */
    private fun withTextNode(text: String, action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        fun visit(node: AccessibilityNodeInfo): Boolean {
            try {
                if (!node.refresh() || node.packageName?.toString() != instrumentation.targetContext.packageName) return false
                if (node.isVisibleToUser && node.text?.toString().orEmpty() == text && action(node)) return true
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: return false
                    if (visit(child)) return true
                }
                return false
            } finally { node.recycle() }
        }
        return visit(root)
    }
    private fun setInput(value: String) {
        val input = await("editable field") { nodes -> nodes.any { it.visible && it.enabled && it.className == "android.widget.EditText" } }
            .single { it.visible && it.enabled && it.className == "android.widget.EditText" }
        tapBounds(input.bounds)
        assertTrue("Actual native text field must accept text", withTextNode(input.text.orEmpty()) { node ->
            node.className == "android.widget.EditText" && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })
        })
    }
    private fun characterVisible(text: String, index: Int): Boolean {
        val clipped = snapshot()?.firstOrNull { it.visible && it.text == text }?.bounds ?: return false
        return withTextNode(text) { node ->
            val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
            if (!node.availableExtraData.contains(key)) return@withTextNode false
            val arguments = Bundle().apply {
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, index)
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, 1)
            }
            if (!node.refreshWithExtraData(key, arguments)) return@withTextNode false
            @Suppress("DEPRECATION")
            val glyph = node.extras.getParcelableArray(key)?.firstOrNull() as? RectF
            glyph != null && !glyph.isEmpty && RectF(clipped).contains(glyph.centerX(), glyph.centerY())
        }
    }
    private fun copiedText(): String {
        var value = ""
        instrumentation.runOnMainSync {
            value = instrumentation.targetContext.getSystemService(ClipboardManager::class.java)
                .primaryClip!!.getItemAt(0).text.toString()
        }
        return value
    }
    private fun captureOwned(stage: String) {
        if (InstrumentationRegistry.getArguments().getString("nfbTraceUi") != "true") return
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Missing CSV success screenshot" }
        try {
            val file = File(instrumentation.targetContext.cacheDir, "csv-owned-$stage-${SystemClock.uptimeMillis()}.png")
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            OwnedUiTraceRule.trace("csv-owned-screenshot=${file.absolutePath}")
        } finally { bitmap.recycle() }
    }

    @Test fun tableScrollingLongCellsSourceAndConditionalEditingKeepTheOriginalBytes(): Unit = runBlocking {
        val longCell = (listOf("单元格起点") + (1..34).map { "完整中文行$it" } + "单元格终点TAIL").joinToString("\r\n")
        fun record(cells: List<String>) = cells.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
        val source = (listOf(record((1..12).map { "CSV列%02d".format(it) })) +
            (1..36).map { row -> record((1..12).map { col -> when {
                row == 1 && col == 1 -> longCell
                row == 36 && col == 1 -> "CSV末行"
                else -> "行${row}列${col}"
            } }) }).joinToString("\r\n", postfix = "\r\n")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val original = bom + source.toByteArray()
        val fixture = DocumentEditFixture(original, name = "owned.csv")
        val owner = fixture.session("csv-ui")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val preview = DocumentPreviewController(instrumentation.targetContext, scope, { it === owner }, reader = fixture.reader)
        val editor = DocumentEditController(instrumentation.targetContext, scope, { it === owner }, reader = fixture.reader)
        var downloads = 0
        activity.scenario.onActivity { host -> host.setContent { ClientTheme(darkTheme = false) { LibraryTheme {
            val editing by editor.state.collectAsStateWithLifecycle()
            if (editing.file != null) DocumentEditorScreen(editor, {})
            else DocumentPreviewScreen(preview, {}, { assertEquals(fixture.sourceWire, it.wirePath); downloads++ }, true,
                onEdit = { file, text -> editor.open(file, text, owner.api.id) })
        } } } }
        try {
            main { preview.bind(owner); editor.bind(owner); preview.open(fixture.file, owner.api.id) }
            val opened = withTimeout(5000) { preview.state.first { !it.loading && it.text != null } }
            visibleText("CSV列01"); captureOwned("initial")
            chooseDelimiter("逗号")
            val header = visibleText("CSV列01").bounds
            scrollUntil("CSV列12") { swipe(screen.width() * 4 / 5, header.centerY(), screen.width() / 5, header.centerY()) }
            await("first column horizontally offscreen") { it.none { node -> node.visible && node.text == "CSV列01" } }
            captureOwned("horizontal-end")
            scrollUntil("#") { swipe(screen.width() / 5, header.centerY(), screen.width() * 4 / 5, header.centerY()) }
            val first = visibleText(longCell)
            assertEquals("The cell's full original text must survive native rendering", longCell, first.text)
            assertFalse("Long cell tail must begin outside its viewport", characterVisible(longCell, longCell.indexOf("单元格终点TAIL")))
            val deadline = SystemClock.uptimeMillis() + 5000
            while (!characterVisible(longCell, longCell.indexOf("单元格终点TAIL")) && SystemClock.uptimeMillis() < deadline) {
                val cell = visibleText(longCell).bounds
                swipe(cell.centerX(), cell.bottom - 16, cell.centerX(), cell.top + 16)
            }
            assertTrue("Actual cell scroll must expose the final character geometry", characterVisible(longCell, longCell.lastIndex))
            assertEquals(longCell, visibleText(longCell).text); captureOwned("complete-cell-tail")
            val gutterX = visibleText("1").bounds.centerX()
            scrollUntil("CSV末行") { swipe(gutterX, screen.bottom - (64 * density).toInt(), gutterX, header.bottom + (24 * density).toInt()) }
            assertEquals("The header stays above the vertically scrolled body", header.top, visibleText("CSV列01").bounds.top)
            captureOwned("vertical-end")
            click("源码"); setInput("单元格终点TAIL")
            withTimeout(5000) { preview.state.first { !it.searching && it.search.matches.size == 1 } }
            visibleText("1 / 1 处匹配"); click("复制全文"); assertEquals(source, copiedText())
            assertArrayEquals(original, fixture.files.getValue(fixture.sourceWire)); assertTrue(fixture.writes.isEmpty())
            click("表格"); delimiterState("逗号")
            click("下载文档"); main { assertEquals(1, downloads) }
            click("编辑文本"); visibleText("文本内容")
            assertEquals(documentEditorText(opened.text!!), editor.state.value.draft)
            val edited = source.replace("CSV末行", "CSV新末行")
            setInput(edited.replace("\r\n", "\n"))
            withTimeout(5000) { editor.state.first { it.dirty } }
            click("保存")
            withTimeout(5000) { editor.state.first { !it.saving && it.acknowledged } }
            visibleText("已保存并核对服务器内容"); assertNull(editor.state.value.error)
            assertEquals(1, fixture.writes.size)
            val write = fixture.writes.single()
            assertEquals("PUT", write.getString("method"))
            val endpoint = Uri.parse(write.getString("endpoint"))
            assertEquals("/api/resources${fixture.sourceWire}", write.getString("endpoint").substringBefore('?'))
            assertEquals("true", endpoint.getQueryParameter("conditional"))
            assertEquals(original.size.toString(), endpoint.getQueryParameter("expectedSize"))
            assertArrayEquals(bom + edited.toByteArray(), fixture.files.getValue(fixture.sourceWire))
        } finally { withContext(NonCancellable) { main { preview.close(); editor.close(discard = true) }; scope.cancel() } }
    }

    @Test fun ambiguousDelimiterHeaderAndSourceRecoveryDoNotEnableReadonlyWritesOrDownloads(): Unit = runBlocking {
        val source = "a,b;c\r\n1,2;3\r\n"
        val original = source.toByteArray()
        val fixture = DocumentEditFixture(original, modify = false, name = "ambiguous.csv")
        val owner = fixture.session("csv-readonly")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val preview = DocumentPreviewController(instrumentation.targetContext, scope, { it === owner }, reader = fixture.reader)
        activity.scenario.onActivity { host -> host.setContent { ClientTheme(darkTheme = false) { LibraryTheme {
            DocumentPreviewScreen(preview, {}, { error("Readonly download must remain disabled") }, canDownload = false)
        } } } }
        fun permissions() {
            await("readonly controls") { nodes ->
                nodes.none { it.visible && it.description == "编辑文本" } &&
                    nodes.any { it.visible && it.description == "下载文档" && !it.enabled }
            }
        }
        try {
            main { preview.bind(owner); preview.open(fixture.file, owner.api.id) }
            withTimeout(5000) { preview.state.first { !it.loading && it.text != null } }
            visibleText("查看完整源码"); permissions()
            click("查看完整源码"); setInput("1,2")
            withTimeout(5000) { preview.state.first { !it.searching && it.search.matches.size == 1 } }
            visibleText("1 / 1 处匹配"); click("复制全文"); assertEquals(source, copiedText()); permissions()
            click("表格"); chooseDelimiter("分号")
            visibleText("a,b"); visibleText("1,2"); captureOwned("readonly-semicolon")
            click("首行作为表头"); checked("首行作为表头", false)
            visibleText("列 1"); visibleText("列 2"); visibleText("a,b"); visibleText("1,2")
            captureOwned("readonly-no-header")
            click("选择表格分隔符"); visibleText("UTF-8 · 只读 · 分号分隔 · 2 行 · 2 列")
            assertTrue("Back must close the delimiter menu", device.pressBack())
            await("delimiter menu closed") { nodes ->
                nodes.any { it.visible && it.description == "选择表格分隔符" } &&
                    nodes.none { it.visible && it.description?.startsWith("分隔符：") == true }
            }
            click("源码"); permissions(); click("表格")
            delimiterState("分号"); checked("首行作为表头", false); visibleText("列 1"); permissions()
            assertEquals(source, preview.state.value.text!!.text)
            assertArrayEquals(original, fixture.files.getValue(fixture.sourceWire)); assertTrue(fixture.writes.isEmpty())
        } finally { withContext(NonCancellable) { main { preview.close() }; scope.cancel() } }
    }
}

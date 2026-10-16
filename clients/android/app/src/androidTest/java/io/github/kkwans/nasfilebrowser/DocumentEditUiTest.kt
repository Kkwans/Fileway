package io.github.kkwans.nasfilebrowser

import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.DocumentEditController
import io.github.kkwans.nasfilebrowser.data.decodeDocumentText
import io.github.kkwans.nasfilebrowser.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class DocumentEditUiTest : LibraryUiHarness() {
    private suspend fun awaitIme(visible: Boolean) = withTimeout(5000) {
        while (true) {
            var matches = false
            activity.scenario.onActivity { host ->
                matches = ViewCompat.getRootWindowInsets(host.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == visible
            }
            if (matches) break
            delay(50)
        }
    }
    private fun click(label: String) {
        val target = enabledTextAction(label)
        try { assertTrue(target.isEnabled); target.click() }
        finally { target.recycle() }
    }
    @Test fun discardConflictReloadAndExclusiveCreateRetainNativeInputs(): Unit = runBlocking {
        val fixture = DocumentEditFixture(); val owner = fixture.session("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); var closed = 0; var created = 0
        val controller = DocumentEditController(instrumentation.targetContext, scope, { it === owner }, onCreated = { _, _ -> created++ }, reader = fixture.reader)
        activity.scenario.onActivity { host ->
            model = ViewModelProvider(host)[io.github.kkwans.nasfilebrowser.app.ClientModel::class.java]
            host.setContent { ClientTheme(darkTheme = false) { LibraryTheme { DocumentEditorScreen(controller) { closed++ } } } }
        }
        try {
            main { controller.bind(owner); controller.open(fixture.file, decodeDocumentText(fixture.files.getValue(fixture.sourceWire)), owner.api.id) }
            val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000) ?: error("Missing document editor")
            input.text = "保留草稿\n"
            withTimeout(5000) { controller.state.first { it.dirty && it.draft == "保留草稿\n" } }
            // The keyboard owns the first Back. Dismissing it must preserve the draft.
            input.click()
            awaitIme(true)
            device.pressBack()
            awaitIme(false)
            assertEquals(0, closed)
            assertEquals("保留草稿\n", controller.state.value.draft)
            action("关闭文本编辑").click(); text("放弃未保存的更改？"); click("继续编辑")
            assertEquals(0, closed); assertEquals("保留草稿\n", controller.state.value.draft)
            fixture.writeStatus = 409
            click("保存")
            withTimeout(5000) { controller.state.first { !it.saving && it.conflict } }
            assertEquals("保留草稿\n", controller.state.value.draft)
            action("编辑操作").click(); click("重新读取服务器版本"); text("丢弃草稿并重新读取？"); click("取消")
            assertEquals("保留草稿\n", controller.state.value.draft)
            action("编辑操作").click(); click("重新读取服务器版本"); click("丢弃草稿并读取")
            withTimeout(5000) { controller.state.first { !it.loading && !it.dirty && !it.conflict } }
            assertEquals("old\n", controller.state.value.draft)
            action("关闭文本编辑").click()
            instrumentation.runOnMainSync { assertEquals(1, closed) }
            activity.scenario.onActivity { host -> host.setContent { ClientTheme(darkTheme = false) { LibraryTheme {
                CreateFileDialog(controller, onVerifyDirectory = {})
            } } } }
            fixture.writeStatus = 200
            main { controller.startCreate(fixture.parent, owner.api.id) }
            text("新建文件")
            val fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), 5000)
            assertEquals(2, fields.size)
            fields[0].text = "notes.txt"; fields[1].text = "创建内容\n"
            click("创建文件")
            withTimeout(5000) { controller.state.first { !it.saving && it.creation != null && it.error != null } }
            assertEquals("notes.txt", controller.state.value.creation!!.name)
            assertEquals("创建内容\n", controller.state.value.creation!!.content)
            device.wait(Until.findObjects(By.clazz("android.widget.EditText")), 5000)[0].text = "new.txt"
            click("创建文件")
            withTimeout(5000) { controller.state.first { !it.saving && it.creation == null } }
            assertEquals(1, created)
            assertArrayEquals("创建内容\n".toByteArray(), fixture.files.getValue("/%FF/new.txt"))
        } finally { main { controller.close(discard = true); controller.closeCreation(verifyUnknown = true) }; scope.cancel() }
    }
}

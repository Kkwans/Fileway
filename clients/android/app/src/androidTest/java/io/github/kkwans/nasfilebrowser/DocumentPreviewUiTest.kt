package io.github.kkwans.nasfilebrowser

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class DocumentPreviewUiTest : LibraryUiHarness() {
    @Test fun textSearchCopyAndDownloadUseNativeReaderControls(): Unit = runBlocking {
        val bytes = "Owned needle\n中文 needle".toByteArray()
        val profile = ServerProfile(name = "Owned document UI", address = "https://fixture.invalid")
        val file = ResourceRef("/notes.txt", "/notes.txt", "notes.txt", false, "text", bytes.size.toLong())
        val token = "owned." + android.util.Base64.encodeToString("{\"user\":{\"id\":1,\"username\":\"fixture\",\"perm\":{\"download\":true}}}".toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP) + ".fixture"
        val api = NasSession.restore(profile, token, 1) { command -> when (command.getString("op")) {
            "open" -> "owned-document-ui"
            "token" -> token
            "request" -> JSONObject().put("status", 200).put("body", JSONObject().put("path", file.path).put("wirePath", file.wirePath)
                .put("name", file.name).put("type", "text").put("isDir", false).put("size", file.size).toString())
            "lease" -> "http://127.0.0.1:12345/stream/owned"
            "revoke" -> Unit
            else -> error("Unexpected native operation")
        } }
        val owner = SessionContext(profile, AccountRecord("owned", profile.id, 0, 1, "fixture", "fixture-only", 0), api, 1, "owned-document-ui")
        val reader = object : DocumentPreviewReader {
            override suspend fun text(lease: PreviewLease, expected: Long) = bytes
            override suspend fun pdf(context: Context, lease: PreviewLease, expected: Long): DocumentWorkingFile = error("Not a PDF fixture")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = DocumentPreviewController(instrumentation.targetContext, scope, { it === owner }, reader = reader)
        var downloads = 0; var closed = 0
        activity.scenario.onActivity { host ->
            model = ViewModelProvider(host)[ClientModel::class.java]
            host.setContent { ClientTheme(darkTheme = false) { LibraryTheme {
                DocumentPreviewScreen(controller, onClose = { closed++ }, onDownload = { assertEquals(file.wirePath, it.wirePath); downloads++ }, canDownload = true)
            } } }
        }
        try {
            main { controller.bind(owner); controller.open(file, api.id) }
            withTimeout(5000) { controller.state.first { it.text != null && !it.loading } }
            text("notes.txt"); text("UTF-8 · 只读")
            assertFalse(device.hasObject(By.clazz("android.widget.EditText")))
            action("查找文档文字").click()
            val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000) ?: error("Missing text search")
            input.text = "needle"
            withTimeout(5000) { controller.state.first { !it.searching && it.search.matches.size == 2 } }
            text("1 / 2 处匹配"); action("下一个匹配").click(); text("2 / 2 处匹配")
            text("复制全文").click(); text("已复制全文")
            instrumentation.runOnMainSync {
                val clipboard = instrumentation.targetContext.getSystemService(ClipboardManager::class.java)
                assertEquals(bytes.toString(Charsets.UTF_8), clipboard.primaryClip!!.getItemAt(0).text.toString())
            }
            action("关闭文档查找").click()
            awaitKeyboardHidden()
            assertFalse(device.hasObject(By.clazz("android.widget.EditText")))
            assertEquals("needle", controller.state.value.query)
            action("文档操作").click(); action("下载文档").click(); action("关闭文档").click()
            instrumentation.runOnMainSync { assertEquals(1, downloads); assertEquals(1, closed) }
            assertNull(controller.state.value.file)
        } finally { main { controller.close() }; scope.cancel() }
    }
}

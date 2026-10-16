package io.github.kkwans.nasfilebrowser

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.ClientTheme
import io.github.kkwans.nasfilebrowser.ui.LibraryTheme
import io.github.kkwans.nasfilebrowser.ui.OperationHistoryContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class OperationHistoryUiTest : LibraryUiHarness() {
    private class Authority {
        val token = "owned." + android.util.Base64.encodeToString("{\"user\":{\"id\":1,\"username\":\"fixture\"}}".toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP) + ".fixture"
        var deletes = 0
        var rejectDelete = true
        var rows = JSONArray().put(JSONObject().put("id", "owned-history").put("action", "file.rename").put("target", "/测试数据/文件 #?%.txt")
            .put("detail", "/测试数据/旧文件.txt").put("status", "success").put("createdAt", 1_800_000_000_000))
        suspend fun context(): SessionContext {
            val profile = ServerProfile(name = "Owned history UI", address = "https://fixture.invalid")
            val api = NasSession.restore(profile, token, 1) { command ->
                when (command.getString("op")) {
                    "open" -> "owned-history-ui"
                    "token" -> token
                    "request" -> {
                        val endpoint = command.getString("endpoint")
                        check(endpoint == "/api/history" || endpoint.startsWith("/api/history?"))
                        if (command.getString("method") == "DELETE") {
                            deletes++
                            if (rejectDelete) JSONObject().put("status", 403).put("body", "forbidden")
                            else { rows = JSONArray(); JSONObject().put("status", 200).put("body", "{\"deleted\":1}") }
                        } else JSONObject().put("status", 200).put("body", JSONObject().put("items", rows).put("total", rows.length()).toString())
                    }
                    else -> error("Unexpected native operation")
                }
            }
            return SessionContext(profile, AccountRecord("owned", profile.id, 0, 1, "fixture", "fixture-only", 0), api, 1, "owned-history-ui")
        }
    }
    @Test fun confirmationCancelAndForbiddenClearKeepVisibleHistory(): Unit = runBlocking {
        val authority = Authority(); val context = authority.context()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = OperationHistoryController(scope) { it === context }
        activity.scenario.onActivity { host ->
            model = ViewModelProvider(host)[ClientModel::class.java]
            host.setContent { ClientTheme(darkTheme = false) { LibraryTheme {
                Surface(Modifier.safeDrawingPadding()) { OperationHistoryContent(controller, connected = true, onConnect = {}) }
            } } }
        }
        try {
            main { controller.bind(context); controller.setVisible(true) }
            withTimeout(5000) { controller.state.first { it.loaded && !it.loading } }
            text("文件 #?%.txt")
            action("完整记录：文件 #?%.txt").click()
            text("/测试数据/文件 #?%.txt")
            text("/测试数据/旧文件.txt")
            action("关闭操作记录").click()
            action("操作历史管理").click(); text("清空记录").click(); text("清空当前账号的操作历史？")
            assertEquals(0, authority.deletes)
            text("取消").click()
            assertEquals(0, authority.deletes)
            action("操作历史管理").click(); text("清空记录").click(); text("确认清空").click()
            withTimeout(5000) { controller.state.first { !it.clearing && it.error != null } }
            text("清空未获确认：当前账号没有访问权限")
            text("文件 #?%.txt")
            assertEquals(1, authority.deletes)
            text("重新读取").click()
            withTimeout(5000) { controller.state.first { !it.loading && it.error == null } }
            text("筛选").click(); text("筛选操作历史"); text("状态：全部").click(); text("失败").click(); text("应用").click()
            withTimeout(5000) { controller.state.first { !it.loading && it.filter.status == OperationHistoryStatus.FAILED } }
            text("重置").click()
            withTimeout(5000) { controller.state.first { !it.loading && !it.filter.active } }
            authority.rejectDelete = false
            action("操作历史管理").click(); text("清空记录").click(); text("确认清空").click()
            withTimeout(5000) { controller.state.first { !it.clearing && it.items.isEmpty() } }
            text("还没有操作记录")
            assertEquals(2, authority.deletes)
        } finally { scope.cancel() }
    }
    @Test fun disconnectedHistoryProvidesNativeConnectionAction() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = OperationHistoryController(scope) { false }
        var connections = 0
        try {
            activity.scenario.onActivity { host ->
                model = ViewModelProvider(host)[ClientModel::class.java]
                host.setContent { ClientTheme(darkTheme = false) { LibraryTheme {
                    Surface(Modifier.safeDrawingPadding()) { OperationHistoryContent(controller, connected = false, onConnect = { connections++ }) }
                } } }
            }
            text("连接后查看操作历史"); text("连接服务器").click()
            instrumentation.runOnMainSync { assertEquals(1, connections) }
            assertTrue(controller.state.value.items.isEmpty())
        } finally { scope.cancel() }
    }
}

package io.github.kkwans.nasfilebrowser

import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.ArchiveScreen
import io.github.kkwans.nasfilebrowser.ui.ClientTheme
import io.github.kkwans.nasfilebrowser.ui.LibraryTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class ArchiveUiTest : LibraryUiHarness() {
    @Test fun navigateSelectExtractAndOpenOwnedResultDirectory(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val profile = ServerProfile(name = "Owned archive UI", address = "https://archive.invalid")
        val token = "owned." + android.util.Base64.encodeToString("{\"user\":{\"id\":7,\"username\":\"fixture\",\"perm\":{\"download\":true,\"create\":true}}}".toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP) + ".fixture"
        val entryWire = "%D6%D0%CE%C4/file.txt"
        val listing = JSONObject().put("archivePath", "/owned.zip").put("archiveWirePath", "/owned.zip").put("pathVerified", true)
            .put("format", "zip").put("sourceSize", 123).put("sourceModified", 1_700_000_000_000L)
            .put("entries", JSONArray().put(JSONObject().put("path", "中文/file.txt").put("wirePath", entryWire).put("name", "file.txt")
                .put("isDir", false).put("size", 4).put("modified", 1)))
            .put("listedBytes", 4).put("blockedCount", 0).put("truncated", false).put("maxEntries", 10000).put("maxFileBytes", 8L shl 30).put("maxExtractBytes", 20L shl 30)
        fun task(status: String) = JSONObject().put("id", "owned-archive-ui").put("userId", 7).put("type", "archive.extract").put("status", status)
        var posts = 0; var opened: DirectoryCrumb? = null
        val api = NasSession.restore(profile, token, 7) { command -> when (command.getString("op")) {
            "open" -> "owned-archive-ui"
            "token" -> token
            "request" -> {
                val endpoint = command.getString("endpoint")
                var status = 200
                val body = when {
                    endpoint.startsWith("/api/archives/entries?") -> listing
                    endpoint.startsWith("/api/resources/owned.zip") -> JSONObject().put("path", "/owned.zip").put("wirePath", "/owned.zip").put("isDir", false).put("size", 123).put("modified", "2023-11-14T22:13:20Z")
                    endpoint.startsWith("/api/resources/") -> JSONObject().put("path", "/").put("wirePath", "/").put("isDir", true)
                    endpoint == "/api/archives/extractions" -> {
                        assertEquals(entryWire, command.getJSONObject("body").getJSONArray("selectedWirePaths").getString(0))
                        posts++; status = 202; task("queued")
                    }
                    endpoint.startsWith("/api/tasks/") -> task("completed")
                    endpoint.startsWith("/api/archives/extractions/") -> JSONObject().put("archivePath", "/owned.zip").put("archiveWirePath", "/owned.zip")
                        .put("destination", "/").put("destinationWirePath", "/").put("pathsVerified", true)
                        .put("selected", JSONArray().put("中文/file.txt")).put("selectedWirePaths", JSONArray().put(entryWire))
                        .put("extractedFiles", 1).put("extractedDirs", 0).put("extractedBytes", 4).put("skippedCount", 0).put("completedAt", 1_700_000_000_500L)
                    else -> error("Unexpected owned-fixture endpoint")
                }
                JSONObject().put("status", status).put("body", body.toString())
            }
            else -> error("Unexpected owned-fixture operation")
        } }
        val context = SessionContext(profile, AccountRecord("owned", profile.id, 0, 7, "fixture", "fixture-only", 0), api, 1, "owned-archive-ui")
        val controller = ArchiveController(scope, isCurrent = { it === context })
        activity.scenario.onActivity { host ->
            model = ViewModelProvider(host)[ClientModel::class.java]
            host.setContent { ClientTheme(darkTheme = false) { LibraryTheme {
                ArchiveScreen(controller, onBack = {}, onOpenTasks = {}, onOpenDirectory = { opened = it })
            } } }
        }
        try {
            main { controller.bind(context); controller.setVisible(true); controller.open(ResourceRef("/owned.zip", "/owned.zip", "owned.zip", false, "blob", 123), api.id) }
            withTimeout(5000) { controller.state.first { it.listing != null && !it.loading } }
            // Native safe areas are part of the route, not an assumption from a
            // passing extraction request. The primary action must remain above navigation.
            var top = 0; var bottom = 0; var height = 0
            activity.scenario.onActivity { host ->
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(host.window.decorView)
                    ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                top = insets?.top ?: 0; bottom = insets?.bottom ?: 0; height = host.window.decorView.height
            }
            assertTrue(action("返回文件").visibleBounds.top >= top)
            assertTrue(text("解压全部").visibleBounds.bottom <= height - bottom)
            action("多选包内文件").click()
            action("全选当前结果").click()
            assertTrue(controller.state.value.selectedEntries.isNotEmpty())
            action("反选当前结果").click()
            assertTrue(controller.state.value.selected.isEmpty())
            action("刷新压缩包")
            action("更多压缩包操作")
            action("退出多选").click()
            text("中文").click()
            text("包内条目详情")
            action("进入文件夹").click()
            text("file.txt").click()
            text("包内条目详情")
            action("关闭包内条目详情").click()
            var row = text("file.txt")
            while (!row.isLongClickable) row = row.parent ?: error("Archive row has no long-click owner")
            row.longClick()
            action("退出多选")
            text("解压所选项目").click()
            text("确认解压")
            text("开始解压").click()
            withTimeout(5000) { controller.state.first { it.report != null } }
            text("解压任务").click()
            text("解压任务 · 已完成")
            action("所在目录").click()
            main { assertEquals("/", opened?.wirePath) }
            assertEquals(1, posts)
        } finally { scope.cancel() }
    }
}

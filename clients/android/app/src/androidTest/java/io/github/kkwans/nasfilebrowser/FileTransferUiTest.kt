package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.collectionDescription
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class FileTransferUiTest : LibraryUiHarness() {
    private suspend fun clickText(label: String) = clickOwnedText(instrumentation, device, label)
    private suspend fun regularList() {
        main { model.fileLayout(FileLayout.LIST) }
        withTimeout(5000) { model.state.first { it.fileLayout == FileLayout.LIST } }
        assertTrue(device.wait(Until.hasObject(By.desc(FileLayout.LIST.collectionDescription())), 5000))
    }
    private suspend fun target(name: String) {
        clickOwnedDescription(instrumentation, device, "目标目录：$name")
        withTimeout(5000) { model.fileOperations.state.first { it.transfer?.let { draft -> !draft.loading && draft.directory.path == "/$name" } == true } }
    }
    private suspend fun review() {
        try { clickText("检查此目录") }
        catch (failure: Exception) {
            val state = model.fileOperations.state.value
            val draft = state.transfer
            val windows = ownedWindowFacts(instrumentation)
            android.util.Log.i("FilewayTransferUi", "Owned review windows: $windows")
            capture("transfer-review-not-ready")
            throw AssertionError("Owned review unavailable: changing=${state.changing}, present=${draft != null}, " +
                "visible=${draft?.visible}, loading=${draft?.loading}, reviewed=${draft?.reviewed}, " +
                "unknownSubmission=${draft?.unknownSubmission}, errorPresent=${draft?.error != null}; windows=$windows", failure)
        }
        withTimeout(5000) { model.fileOperations.state.first { !it.changing && it.transfer?.reviewed == true } }
    }

    @Test fun globalCopyQueuesBothSourcesKeepsConflictAndUsesPersistentTaskPage(): Unit = runBlocking {
        val data = LibraryFixtureData().apply { renameAllowed = true; liveDirectoryListing = true }
        val first = "/A中文 +%.png"; val second = "/B图片.png"
        data.file(first); data.file(second); data.file("/目标", true); data.file("/目标/A中文 +%.png")
        fixture(data) { source ->
            regularList(); clickText("多选"); text("已选 0 项"); clickText(first.substringAfterLast('/')); clickText(second.substringAfterLast('/')); text("已选 2 项")
            action("批量复制").click(); target("目标"); review()
            action("A中文 +%.png：保留两份")
            capture("transfer-copy-conflict")
            clickText("复制 2 项")
            val task = withTimeout(5000) { model.fileOperations.state.first { !it.changing && it.lastTask != null } }.lastTask!!
            assertEquals("queued", task.status); assertEquals("file.copy", task.type)
            assertTrue(data.files.containsKey(first)); assertTrue(data.files.containsKey(second))
            assertFalse(data.files.containsKey("/目标/B图片.png"))
            text("已选 0 项")
            val input = data.transferAttempts.single().getJSONArray("items")
            assertEquals("/files" + SearchResult.encodePath(first), input.getJSONObject(0).getString("from"))
            assertTrue(input.getJSONObject(0).getBoolean("rename")); assertFalse(input.getJSONObject(0).getBoolean("overwrite"))
            clickText("查看任务"); text("任务详情")
            assertEquals("file", model.tasks.state.value.filter.category)
            withTimeout(5000) { model.tasks.state.first { !it.detailLoading && it.selected?.id == task.id } }
            data.finishTransfers(source.favoriteRecords)
            withTimeout(7000) { model.fileOperations.state.first { it.lastTask?.status == "completed" } }
            assertTrue(data.files.containsKey("/目标/A中文 +%(1).png"))
            assertTrue(data.files.containsKey("/目标/A中文 +%.png"))
            assertTrue(data.files.containsKey("/目标/B图片.png"))
            assertTrue(data.files.containsKey(first)); assertTrue(data.files.containsKey(second))
            clickText("关闭"); action("文件导航").click(); clickText("打开目标目录")
            withTimeout(5000) { model.state.first { !it.busy && it.path == "/目标" } }
            text("B图片.png"); capture("transfer-copy-result")
        }
    }

    @Test fun destinationRaceRequiresNewReviewAndReplacementConsentThenMoveUpdatesSharedPaths(): Unit = runBlocking {
        val data = LibraryFixtureData().apply { renameAllowed = true; liveDirectoryListing = true }
        val original = "/移动样本.png"; val destination = "/新位置/移动样本.png"
        data.file(original); data.file("/新位置", true)
        data.tags.put(JSONObject().put("id", "move-tag").put("name", "已标记").put("color", "#1767E8").put("paths", JSONArray().put(original)))
        fixture(data) { source ->
            source.favoriteRecords.put(JSONObject().put("id", "move-favorite").put("name", "保留收藏名").put("path", original).put("order", 0))
            regularList(); fileDetails("移动样本.png"); action("更多文件操作").click(); action("移动文件").click()
            target("新位置"); review()
            data.file(destination)
            clickText("移动 1 项")
            withTimeout(5000) { model.fileOperations.state.first { !it.changing && it.transfer?.let { draft -> !draft.reviewed && draft.error != null } == true } }
            assertTrue(data.tasks.length() == 0); assertTrue(data.files.containsKey(original))
            review(); action("移动样本.png：替换").click()
            main { model.fileOperations.submitTransfer() }
            withTimeout(5000) { model.fileOperations.state.first { !it.changing && it.transfer?.error?.contains("确认替换") == true } }
            assertEquals(1, data.transferAttempts.size)
            clickText("确认替换同名目标内容"); capture("transfer-move-replace-confirmation")
            clickText("移动 1 项")
            withTimeout(5000) { model.fileOperations.state.first { !it.changing && it.lastTask?.type == "file.move" } }
            assertTrue(data.files.containsKey(original))
            data.finishTransfers(source.favoriteRecords)
            withTimeout(7000) { model.state.first { it.files.none { file -> file.path == original } } }
            withTimeout(5000) { model.tags.state.first { !it.loading && it.items.singleOrNull()?.paths == listOf(destination) } }
            withTimeout(5000) { model.favorites.state.first { !it.loading && it.items.singleOrNull()?.path == destination } }
            assertEquals("move-favorite", model.favorites.state.value.items.single().id)
            assertEquals("保留收藏名", model.favorites.state.value.items.single().name)
            assertEquals(2, data.transferAttempts.size)
            assertFalse(data.files.containsKey(original)); assertTrue(data.files.containsKey(destination))
            capture("transfer-move-completed")
        }
    }

    @Test fun unknownSubmissionIsNotRepeatedAndLateOldAccountGestureCannotStartNewTransfer(): Unit = runBlocking {
        val data = LibraryFixtureData().apply { renameAllowed = true; liveDirectoryListing = true; rejectNextTransferStatus = 503 }
        data.file("/待复制.png"); data.file("/目标", true)
        fixture(data) { source ->
            regularList(); fileDetails("待复制.png"); action("更多文件操作").click(); action("复制文件").click()
            target("目标"); review(); clickText("复制 1 项")
            withTimeout(5000) { model.fileOperations.state.first { !it.changing && it.transfer?.unknownSubmission == true } }
            main { model.fileOperations.submitTransfer() }; delay(200)
            assertEquals(1, data.transferAttempts.size)
            capture("transfer-unknown-submission")
            clickText("查看文件任务")
            assertTrue(model.fileOperations.state.value.transfer?.visible == false)
            assertEquals("file", model.tasks.state.value.filter.category)
            action("文件导航").click(); clickText("继续确认")
            assertTrue(model.fileOperations.state.value.transfer?.unknownSubmission == true)
            val files = model.state.value.files.filter { !it.directory }; val oldScope = model.state.value.previewScope
            main { model.connectDraft("Owned library UI fixture", source.url, BackendKind.NAS, "two", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy && it.accountName == "two" } }
            main { model.startFileTransfer(files, FileTransferAction.COPY, oldScope) }
            assertNull(model.fileOperations.state.value.transfer)
            assertNull(model.fileOperations.state.value.lastTask)
            assertEquals(1, data.transferAttempts.size)
        }
    }
}

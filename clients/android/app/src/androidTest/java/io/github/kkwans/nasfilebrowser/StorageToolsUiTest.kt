package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class StorageToolsUiTest : LibraryUiHarness() {
    @Test fun reportUsesServerHistoryAndScanningStartsOnlyAfterExplicitConfirmation(): Unit = runBlocking {
        val data = LibraryFixtureData(); data.file("/scan/样本.png")
        data.tasks.put(data.task("storage-owned", "analysis.storage", "completed", "已有存储报告"))
        data.reports["storage-owned"] = JSONObject().put("scannedFiles", 3).put("scannedDirectories", 1).put("scannedBytes", 3072)
            .put("largestFiles", JSONArray().put(JSONObject().put("path", "/scan/样本.png").put("size", 1234).put("modified", System.currentTimeMillis())))
            .put("largestDirectories", JSONArray()).put("skippedCount", 0)
        fixture(data) {
            action("资料库导航").click(); action("工具资料页").click()
            text("查看报告").click(); text("样本.png"); text("/scan")
            text("详情").click(); text("/scan/样本.png"); text("关闭").click()
            assertEquals(3, model.storageTools.state.value.report!!.getInt("scannedFiles"))
            assertFalse(data.mutations.any { it.second.startsWith("/api/analysis/") })
            capture("storage-report-phone")
            text("返回分析记录").click(); text("新建扫描").click()
            text("提交扫描").click()
            withTimeout(5000) { model.storageTools.state.first { !it.changing && it.task?.id?.startsWith("analysis-") == true } }
            assertTrue(data.mutations.contains("POST" to "/api/analysis/storage"))
            assertNull(model.storageTools.state.value.report)
            assertEquals("queued", model.storageTools.state.value.task?.status)
            capture("storage-scan-phone")
        }
    }
}

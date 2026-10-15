package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import io.github.kkwans.nasfilebrowser.app.LibrarySection
import io.github.kkwans.nasfilebrowser.app.RecentSection
import io.github.kkwans.nasfilebrowser.app.TaskCenterSection
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class TaskCenterNavigationTest : LibraryUiHarness() {
    private fun singleMainNavigation() {
        listOf("文件", "最近", "资料库", "任务").forEach { label ->
            action("${label}导航")
            assertEquals("Only one global item for $label", 1, device.findObjects(By.desc("${label}导航")).size)
        }
        assertFalse(device.hasObject(By.desc("下载导航")))
        assertFalse(device.hasObject(By.desc("最近播放导航")))
        assertFalse(device.hasObject(By.desc("设置导航")))
        assertFalse(device.hasObject(By.desc("任务中心导航")))
    }

    @Test fun taskGroupsKeepNotificationRoutesAndRemainAvailableOffline(): Unit = runBlocking {
        fixture(LibraryFixtureData()) {
            action("资料库导航").click()
            assertFalse(device.hasObject(By.desc("任务资料页")))
            action("任务导航").click()
            text("本机下载"); singleMainNavigation()
            action("上传分组").click()
            text("本机上传")
            assertEquals("uploads", model.state.value.tab)
            action("文件导航").click(); action("任务导航").click()
            text("本机上传")
            assertEquals(TaskCenterSection.UPLOADS, model.state.value.taskCenterSection)
            main { model.openDownloads() }
            text("本机下载"); assertEquals("downloads", model.state.value.tab)
            main { model.librarySection(LibrarySection.TASKS) }
            action("后台任务分组")
            assertEquals("tasks", model.state.value.tab)
            assertEquals(TaskCenterSection.BACKGROUND, model.state.value.taskCenterSection)
            main { model.disconnect(); model.openUploads() }
            text("本机上传"); singleMainNavigation()
            action("下载分组").click(); text("本机下载")
            action("后台任务分组").click(); text("连接服务器后查看后台任务")
            action("操作历史分组").click(); text("连接后查看操作历史")
            text("连接服务器").click(); text("连接你的文件库")
            assertFalse(model.state.value.connected)
            assertEquals("files", model.state.value.tab)
        }
    }

    @Test fun recentPlaybackAndAccessUseOneMainNavigation(): Unit = runBlocking {
        fixture(LibraryFixtureData()) {
            action("最近导航").click()
            action("最近播放分组"); action("最近访问分组")
            text("继续观看"); singleMainNavigation()
            action("最近访问分组").click()
            assertEquals(RecentSection.ACCESS, model.state.value.recentSection)
            action("文件导航").click(); action("最近导航").click()
            assertEquals(RecentSection.ACCESS, model.state.value.recentSection)
            action("最近播放分组").click(); text("继续观看")
            assertEquals(RecentSection.PLAYBACK, model.state.value.recentSection)
            action("打开导航菜单").click()
            text("本机设置").click()
            withTimeout(5000) { model.state.first { it.tab == "settings" } }
            action("返回来源页面")
            listOf("文件", "最近", "资料库", "任务", "设置").forEach { label ->
                assertFalse("Settings is auxiliary, with no bottom navigation", device.hasObject(By.desc("${label}导航")))
            }
            action("返回来源页面").click()
            text("继续观看"); singleMainNavigation()
            assertEquals("recent", model.state.value.tab)
            assertEquals(RecentSection.PLAYBACK, model.state.value.recentSection)
        }
    }
}

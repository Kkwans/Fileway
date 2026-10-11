package io.github.kkwans.nasfilebrowser

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModelProvider
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.ClientDatabase
import io.github.kkwans.nasfilebrowser.ui.AppUpdateContent
import io.github.kkwans.nasfilebrowser.ui.LibraryTheme
import io.github.kkwans.nasfilebrowser.ui.ClientTheme
import io.github.kkwans.nasfilebrowser.update.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
internal class AppUpdateUiTest : LibraryUiHarness() {
    private suspend fun focused() = withTimeout(5000) {
        while (true) {
            var focused = false
            activity.scenario.onActivity { focused = it.hasWindowFocus() }
            if (focused) break
            delay(50)
        }
    }
    @Test fun connectionEntryWorksWithoutServerAndSurvivesActivityRecreation(): Unit = runBlocking {
        val database = ClientDatabase.get(instrumentation.targetContext); val previous = database.profiles().activeSession()
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        try {
            main { model.cancel(); model.disconnect() }
            focused()
            text("检查更新").click(); text("应用更新"); text("当前版本 ${BuildConfig.VERSION_NAME}")
            withTimeout(10_000) { model.updates.state.first { it.phase != UpdatePhase.RESTORING } }
            text("获取最新 Android 版本"); capture("app-update-idle")
            activity.scenario.recreate()
            focused()
            text("应用更新"); text("获取最新 Android 版本")
            action("返回").click(); text("连接服务器")
        } finally { withContext(NonCancellable) {
            previous?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
        } }
    }

    @Test fun updatePageShowsNotesActualPercentageCancelAndRetryActions() {
        fun touchTarget(label: String) {
            var button = text(label)
            while (!button.isClickable) button = button.parent ?: error("Missing clickable parent for $label")
            assertTrue("$label must have a 48dp touch target", button.visibleBounds.height() + 1 >= 48 * instrumentation.targetContext.resources.displayMetrics.density)
        }
        val asset = UpdateAsset(1, "owned.apk", "https://owned.invalid/owned.apk", 8 * 1024 * 1024)
        val update = AppUpdate(AppRelease("owned", "0.7.0", "测试数据：上传、下载和媒体体验改进。", true, listOf(asset)), asset)
        val stableAsset = asset.copy(id = 2)
        val stable = AppUpdate(update.release.copy(version = "0.6.9", preview = false, notes = "测试数据：正式版本说明。", assets = listOf(stableAsset)), stableAsset)
        val choices = listOf(update, stable)
        val state = mutableStateOf(UpdateState(UpdatePhase.AVAILABLE, update, choices = choices))
        activity.scenario.onActivity { it.setContent {
            ClientTheme(darkTheme = false) { LibraryTheme { AppUpdateContent(state.value, back = {}, check = {}, download = {
                state.value = UpdateState(UpdatePhase.DOWNLOADING, update, 2 * 1024 * 1024, asset.size, "正在下载，离开页面后仍会继续")
            }, cancel = { state.value = UpdateState(UpdatePhase.FAILED, update, error = "测试数据：网络中断，请重试") }, install = {},
                select = { id -> state.value = UpdateState(UpdatePhase.AVAILABLE, choices.single { it.asset.id == id }, choices = choices) }) } }
        } }
        text("发现新版本 0.7.0"); text("更新说明"); capture("app-update-available-owned")
        touchTarget("重新检查更新")
        text("0.6.9").click(); text("发现新版本 0.6.9"); text("测试数据：正式版本说明。")
        text("0.7.0").click(); text("发现新版本 0.7.0")
        text("下载更新 · 8.0 MB").click(); text("25%"); text("2.0 MB / 8.0 MB"); capture("app-update-progress-owned")
        text("取消下载").click(); text("更新未完成"); text("重新下载"); text("重新检查更新")
        touchTarget("重新下载"); touchTarget("重新检查更新")
        instrumentation.runOnMainSync { state.value = UpdateState(UpdatePhase.READY, update) }
        text("安装更新"); touchTarget("删除安装包")
        val revised = update.copy(release = update.release.copy(version = "0.7.5", previewRevision = 2))
        instrumentation.runOnMainSync { state.value = UpdateState(UpdatePhase.AVAILABLE, revised, choices = listOf(revised)) }
        text("发现新版本 0.7.5-preview-r2")
    }

    @Test fun settingsEntryReturnsToSettingsInsteadOfLosingItsLocation(): Unit = runBlocking {
        fixture(LibraryFixtureData()) {
            main { model.tab("settings") }
            // The application group can be below the phone's first viewport.
            val list = action("设置内容")
            repeat(5) {
                if (device.hasObject(androidx.test.uiautomator.By.text("检查更新"))) return@repeat
                list.scroll(androidx.test.uiautomator.Direction.DOWN, .7f)
            }
            text("检查更新").click(); text("应用更新")
            action("返回").click()
            withTimeout(5000) { model.state.first { it.tab == "settings" } }
            action("设置内容")
        }
    }
}

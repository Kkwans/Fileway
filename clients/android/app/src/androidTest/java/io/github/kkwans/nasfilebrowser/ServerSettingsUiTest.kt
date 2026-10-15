package io.github.kkwans.nasfilebrowser

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.github.kkwans.nasfilebrowser.app.ServerSettingsController
import io.github.kkwans.nasfilebrowser.ui.LibraryTheme
import io.github.kkwans.nasfilebrowser.ui.ServerSettingsScreen
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerSettingsUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun text(value: String) = device.wait(Until.findObject(By.text(value)), 5000) ?: error("Missing global settings action: $value")
    @Test fun groupedNativeFormRequiresConfirmationAndKeepsExecReadonly(): Unit = runBlocking {
        val authority = ServerSettingsAuthority(); val context = authority.context("ui")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); val controller = ServerSettingsController(scope) { it === context }
        try {
            withContext(Dispatchers.Main) { controller.bind(context); controller.refresh() }
            withTimeout(5000) { controller.state.first { it.authorized && !it.loading } }
            activity.scenario.onActivity { it.setContent { MaterialTheme { LibraryTheme { ServerSettingsScreen(controller, {}, {}) } } } }
            text("命令执行：未启用")
            val list = device.wait(Until.findObject(By.desc("全局设置内容")), 5000) ?: error("Global settings list missing")
            repeat(5) { if (!device.hasObject(By.text("编辑注册与账户"))) list.scroll(Direction.DOWN, .5f) }
            text("编辑注册与账户").click(); text("允许注册").click(); text("保留草稿并返回").click()
            text("保存全局设置").click(); text("保存全局设置？")
            assertEquals(0, authority.writes)
            text("取消").click(); assertEquals(0, authority.writes)
            text("保存全局设置").click(); text("确认保存").click()
            withTimeout(5000) { controller.state.first { it.notice == "全局设置已保存并核对" && !it.saving } }
            assertTrue(authority.settings.getBoolean("signup")); assertFalse(authority.lastBody!!.has("enableExec"))
        } finally { scope.cancel() }
    }
}

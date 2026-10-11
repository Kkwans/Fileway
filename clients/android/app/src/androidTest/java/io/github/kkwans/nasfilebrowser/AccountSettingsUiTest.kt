package io.github.kkwans.nasfilebrowser

import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.github.kkwans.nasfilebrowser.app.AccountSettingsController
import io.github.kkwans.nasfilebrowser.ui.AccountSettingsScreen
import io.github.kkwans.nasfilebrowser.ui.LibraryTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AccountSettingsUiTest {
    private val uiTrace = OwnedUiTraceRule()
    private val activity = ActivityScenarioRule(MainActivity::class.java)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(uiTrace).around(activity)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val inputDriver = InstrumentationRegistry.getArguments().getString("nfbInputDriver", "automation")
    private var openingInputDelivered = false
    private fun freshNodes() = freshAccessibilityBounds(instrumentation, Rect(0, 0, device.displayWidth, device.displayHeight))
    private data class EntryCandidates(val titleCount: Int, val containingOwnerCount: Int,
        val nearestOwnerCount: Int, val owner: FreshAccessibilityNodeBounds?)
    private fun entryCandidates(nodes: List<FreshAccessibilityNodeBounds>): EntryCandidates {
        val titles = nodes.filter { it.visible && (it.text == "修改密码" || it.description == "修改密码") }
        val title = titles.singleOrNull() ?: return EntryCandidates(titles.size, 0, 0, null)
        // SettingsAction's title belongs to a child Text node. Select its
        // nearest unique clickable row by containment, without requiring the
        // row to repeat its children's text or accepting another window's tree.
        val owners = nodes.filter { it.visible && it.enabled && it.clickable && it.bounds.contains(title.bounds) }
        val minimumArea = owners.minOfOrNull { it.bounds.width().toLong() * it.bounds.height() }
        val nearest = owners.filter { it.bounds.width().toLong() * it.bounds.height() == minimumArea }
        return EntryCandidates(titles.size, owners.size, nearest.size, nearest.singleOrNull())
    }
    private fun text(label: String): UiObject2 = device.wait(Until.findObject(By.text(label)), 5000) ?: error("Missing account control: $label")
    private fun fieldTarget(label: String): UiObject2 = device.wait(Until.findObject(By.desc(label)), 5000) ?: error("Missing password field: $label")
    private suspend fun enterPassword(label: String, value: String) {
        // Password semantics may intentionally expose only a View container.
        // Use actual focus/key input instead of requiring ACTION_SET_TEXT.
        require(value in setOf("owned-current-password", "owned-new-password"))
        var target: UiObject2? = null
        val density = instrumentation.targetContext.resources.displayMetrics.density
        repeat(4) {
            if (target != null) return@repeat
            val candidate = sheet().findObject(By.desc(label))
            if (candidate != null && candidate.visibleBounds.height() / density >= 48f) target = candidate
            else sheet().scroll(Direction.DOWN, .5f)
        }
        val control = target ?: error("Password input target is not reachable: $label")
        assertTrue(control.isEnabled)
        control.click()
        withTimeout(5000) { while (!keyboardVisible()) delay(50) }
        device.waitForIdle()
        // Values are fixed owned ASCII fixtures, never real login credentials.
        device.executeShellCommand("input text $value")
    }
    private fun keyboardVisible(): Boolean = instrumentation.uiAutomation.windows.any { window ->
        if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) false else {
            val bounds = Rect(); window.getBoundsInScreen(bounds)
            bounds.height() > 0 && bounds.top < device.displayHeight
        }
    }
    private fun sheet() = device.wait(Until.findObject(By.desc("密码修改内容")), 5000) ?: error("Password sheet missing")
    private fun sheetButton(label: String): UiObject2 {
        repeat(4) {
            val panel = sheet()
            val target = panel.findObject(By.text(label))
            if (target != null) {
                var button = target
                while (!button.isClickable) button = button.parent ?: error("Password action not clickable: $label")
                assertTrue("Password action disabled: $label", button.isEnabled)
                return button
            }
            panel.scroll(Direction.DOWN, .6f)
        }
        error("Password action not visible: $label")
    }
    private fun diagnose(stage: String, controller: AccountSettingsController, writes: Int, capture: Boolean) {
        runCatching {
            val state = controller.state.value
            val nodes = runCatching { freshNodes() }.getOrNull()
            val candidates = nodes?.let(::entryCandidates)
            val imeVisible = runCatching { keyboardVisible() }.getOrNull()
            val output = File(instrumentation.targetContext.cacheDir, "account-settings-ui")
            check(output.isDirectory || output.mkdirs())
            // Only booleans, counts and fixed stage/driver names: never password text, lengths,
            // account snapshots or accessibility hierarchy dumps.
            File(output, "password-stage.txt").writeText(listOf(
                "stage=$stage", "profileReady=${state.profile != null}", "capabilitiesReady=${state.capabilities != null}",
                "canChangePassword=${state.canChangePassword}", "currentRequired=${state.capabilities?.currentPasswordRequired == true}",
                "currentMatches=${state.password.current == "owned-current-password"}",
                "replacementMatches=${state.password.replacement == "owned-new-password"}",
                "confirmationMatches=${state.password.confirmation == "owned-new-password"}",
                "confirmationAgrees=${state.password.replacement == state.password.confirmation}",
                "saving=${state.saving}", "passwordUnknown=${state.passwordUnknown}", "writeObserved=${writes > 0}",
                "errorPresent=${state.error != null}", "imeVisible=$imeVisible",
                "openInputDelivered=$openingInputDelivered", "activeRootObserved=${nodes != null}",
                "inputDriver=$inputDriver", "entryTitleCount=${candidates?.titleCount}",
                "entryContainingOwnerCount=${candidates?.containingOwnerCount}",
                "entryNearestOwnerCount=${candidates?.nearestOwnerCount}", "entryOwnerReady=${candidates?.owner != null}",
                "sheetPresent=${nodes?.any { it.visible && it.description == "密码修改内容" }}",
                "confirmPresent=${nodes?.any { it.visible && it.text?.contains("确认修改密码") == true }}",
                "reconnectPresent=${nodes?.any { it.visible && it.text?.contains("重新连接核对") == true }}"
            ).joinToString("\n"))
            if (capture) device.takeScreenshot(File(output, "password-sheet.png"))
        }
    }

    @Test fun passwordSheetRetainsUnknownResultAndUsesAccessibleTouchTargets(): Unit = runBlocking {
        require(inputDriver in setOf("automation", "shell"))
        val authority = AccountSettingsAuthority(); authority.commitThenFail = true
        val context = authority.context("ui")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = AccountSettingsController(scope, { it === context })
        var reconnects = 0
        var stage = "load-profile"
        var mounted = false
        try {
            withContext(Dispatchers.Main) { controller.bind(context); controller.refresh() }
            withTimeout(5000) { controller.state.first { !it.loading && it.profile != null } }
            activity.scenario.onActivity { it.setContent { MaterialTheme { LibraryTheme {
                AccountSettingsScreen(controller, {}, { reconnects++ })
            } } } }
            mounted = true; stage = "open-password-sheet"
            text("账户安全")
            listOf("交互与日期", "播放偏好", "默认播放策略", "兼容播放", "特殊前缀", "网页编辑器", "保存账户设置").forEach { label ->
                assertFalse("Android must not expose an unused Web preference: $label", device.hasObject(By.text(label)))
            }
            val originalPreferences = controller.state.value.profile!!.preferences
            val content = device.wait(Until.findObject(By.desc("账户设置内容")), 5000) ?: error("Account content missing")
            repeat(8) {
                if (device.hasObject(By.text("修改密码"))) return@repeat
                content.scroll(Direction.DOWN, .7f)
            }
            val density = instrumentation.targetContext.resources.displayMetrics.density
            stage = "open-password-target"
            val openingAction = withTimeout(5000) {
                var found: FreshAccessibilityNodeBounds? = null
                while (found == null) {
                    found = freshNodes()?.let(::entryCandidates)?.owner
                    if (found == null) delay(25)
                }
                checkNotNull(found)
            }
            assertTrue("Password entry must have a 48dp target", openingAction.bounds.height() / density >= 48f)
            stage = "open-password-input"
            val beforeTouches = uiTrace.deliveredTouches()
            OwnedUiTraceRule.trace("account-open-password-driver=$inputDriver")
            if (inputDriver == "shell") {
                // Explicit driver choice, never a retry/fallback. The same
                // Activity DOWN/UP and real sheet assertions remain below.
                device.executeShellCommand("input touchscreen tap ${openingAction.bounds.centerX()} ${openingAction.bounds.centerY()}")
            } else OwnedTouchInput.tap(instrumentation, openingAction.bounds, tracePrefix = "account-open-password")
            openingInputDelivered = uiTrace.deliveredTouches() >= beforeTouches + 2
            assertTrue("Password entry DOWN/UP must reach the owned Activity; ${uiTrace.snapshot()}", openingInputDelivered)
            stage = "open-password-sheet"
            withTimeout(5000) {
                while (freshNodes()?.any { it.visible && it.description == "密码修改内容" } != true) delay(25)
            }
            listOf("当前密码", "新密码", "确认新密码").forEach { label ->
                assertTrue("Password target below 48dp: $label", fieldTarget(label).visibleBounds.height() / density >= 48f)
            }
            stage = "set-current-password"
            enterPassword("当前密码", "owned-current-password")
            withTimeout(5000) { controller.state.first { it.password.current == "owned-current-password" } }
            stage = "set-new-password"
            enterPassword("新密码", "owned-new-password")
            withTimeout(5000) { controller.state.first { it.password.replacement == "owned-new-password" } }
            stage = "set-password-confirmation"
            enterPassword("确认新密码", "owned-new-password")
            withTimeout(5000) { controller.state.first { it.password.current == "owned-current-password" &&
                it.password.replacement == "owned-new-password" && it.password.confirmation == "owned-new-password" } }
            stage = "dismiss-visible-ime"
            device.waitForIdle()
            if (keyboardVisible()) {
                device.pressBack()
                withTimeout(5000) { while (keyboardVisible()) delay(50) }
            }
            device.waitForIdle()
            sheet()
            assertTrue(controller.state.value.password.current == "owned-current-password" &&
                controller.state.value.password.replacement == "owned-new-password" && controller.state.value.password.confirmation == "owned-new-password")
            stage = "submit-password"
            val confirm = sheetButton("确认修改密码")
            assertTrue("Password submit target below 48dp", confirm.visibleBounds.height() / density >= 48f)
            confirm.click()
            stage = "await-unknown-password-result"
            withTimeout(5000) { controller.state.first { it.passwordUnknown && !it.saving } }
            assertEquals(1, authority.writes)
            stage = "reconnect-after-unknown-result"
            val reconnect = sheetButton("重新连接核对")
            assertTrue(reconnect.visibleBounds.height() / density >= 48f)
            reconnect.click()
            instrumentation.waitForIdleSync()
            assertEquals(1, reconnects)
            assertEquals("owned-new-password", controller.state.value.password.replacement)
            assertEquals(1, authority.writes)
            assertEquals("Password actions must preserve the stored Web preferences", originalPreferences, controller.state.value.profile!!.preferences)
        } catch (error: Throwable) {
            diagnose(stage, controller, authority.writes, mounted)
            throw error
        } finally { scope.cancel() }
    }
}

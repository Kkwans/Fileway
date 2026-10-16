package io.github.kkwans.nasfilebrowser

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.rules.RuleChain

internal open class LibraryUiHarness {
    protected val activity = ActivityScenarioRule(MainActivity::class.java)
    private val uiTrace = OwnedUiTraceRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(uiTrace).around(activity)
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val device get() = UiDevice.getInstance(instrumentation)
    protected lateinit var model: ClientModel
    protected suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    protected suspend fun awaitKeyboardHidden() = withTimeout(5000) {
        while (true) {
            var hidden = false
            activity.scenario.onActivity { host ->
                hidden = ViewCompat.getRootWindowInsets(host.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false
            }
            if (hidden) break
            delay(50)
        }
    }
    protected fun text(label: String): UiObject2 { device.waitForIdle(); return device.wait(Until.findObject(By.text(label)), 5000) ?: missing(label) }
    protected fun action(label: String): UiObject2 { device.waitForIdle(); return device.wait(Until.findObject(By.desc(label)), 5000) ?: missing(label) }
    /** Opt-in readiness query; a caller performs exactly one click after this
     * returns. Never retry a gesture whose write outcome could be unknown.
     * onPending is a test-only observation seam for controlled state changes.
     */
    protected fun enabledTextAction(label: String, onPending: (() -> Unit)? = null): UiObject2 {
        val timeout = 5_000L
        val deadline = SystemClock.uptimeMillis() + timeout
        val configuration = Configurator.getInstance()
        val previousIdle = configuration.getWaitForIdleTimeout()
        var last = "label-not-found"
        val ready = try {
            // Pinned 2.3.0 findObject and every UiObject2 attribute/parent read
            // implicitly waitForIdle (default 10s). Disable only that nested
            // wait inside this serial query, restoring the exact original value.
            configuration.setWaitForIdleTimeout(0L)
            device.wait(Condition<UiDevice, UiObject2?> { currentDevice ->
                if (SystemClock.uptimeMillis() >= deadline) return@Condition null
                var node: UiObject2? = null
                var returned = false
                try {
                    node = currentDevice.findObject(By.text(label))
                    var depth = 0
                    while (depth++ < 32 && SystemClock.uptimeMillis() < deadline) {
                        val current = node ?: break
                        val clickable = current.isClickable
                        val enabled = current.isEnabled
                        // Never pass a disabled button (even if the platform
                        // omits its clickable flag) to reach an enabled wrapper.
                        if (!enabled) { last = "enabled=false,clickable=$clickable"; break }
                        if (clickable) {
                            val bounds = current.visibleBounds
                            last = "enabled=true,clickable=true,size=${bounds.width()}x${bounds.height()}"
                            if (bounds.width() > 0 && bounds.height() > 0 && SystemClock.uptimeMillis() < deadline) {
                                returned = true
                                return@Condition current
                            }
                            break
                        }
                        node = current.parent
                        current.recycle()
                        last = "clickable-owner-not-found"
                    }
                } catch (_: StaleObjectException) {
                    last = "stale-query-node"
                } finally {
                    if (!returned) node?.recycle()
                }
                onPending?.invoke()
                null
            }, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0))
        } finally { configuration.setWaitForIdleTimeout(previousIdle) }
        // A polling budget, not a hard preemption of platform Binder calls.
        return ready ?: missing("$label (enabled clickable owner within ${timeout}ms; $last)")
    }
    protected fun fileDetails(name: String) {
        var target = text(name)
        while (!target.isLongClickable) target = target.parent ?: missing("长按文件卡片 $name")
        target.longClick()
        text("文件详情")
    }
    private fun missing(label: String): Nothing {
        // Pure setContent tests intentionally need no bound ClientModel. A
        // diagnostic failure must never replace the original missing control.
        fun observe(block: () -> String) = runCatching(block).getOrElse { "unavailable(${it.javaClass.simpleName})" }
        val modelState = observe {
            if (!this::model.isInitialized) "model=uninitialized" else {
                val state = model.state.value
                "model=bound,tab=${state.tab},busy=${state.busy},startupPending=${state.startupPending}," +
                    "connected=${state.connected},tagsLoaded=${model.tags.state.value.loaded}"
            }
        }
        // No hierarchy, screenshot, Intent or View text: those can contain
        // credentials/input/private filenames. Explicit owned captures remain opt-in.
        // Even scenario.state waits for main idle in core 1.7.0. Read only a
        // timestamped main-callback cache; unavailable foreground is not a fact.
        val diagnostics = "$modelState; foregroundPackage=unverified; scenario=unverified; ${uiTrace.snapshot()}"
        error("Missing visible action $label; $diagnostics")
    }
    protected suspend fun fixture(data: LibraryFixtureData, block: suspend (ClientSearchTest.Fixture) -> Unit) {
        val source = ClientSearchTest.Fixture(data.files.keys.filter { it.substringBeforeLast('/').isEmpty() }.map { it.substringAfterLast('/') }, library = data)
        val database = ClientDatabase.get(instrumentation.targetContext)
        val previous = database.profiles().activeSession()
        val store = ProfileStore(database, CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "Owned library UI fixture", address = source.url))
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        try {
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            withTimeout(5000) {
                while (true) { var focused = false; activity.scenario.onActivity { focused = it.hasWindowFocus() }; if (focused) break; delay(50) }
            }
            block(source)
        } finally { withContext(NonCancellable) {
            main { model.disconnect() }; store.remove(profile); source.close()
            previous?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
        } }
    }
    protected fun capture(name: String) {
        device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/$name.png")
    }
}

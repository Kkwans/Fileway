package io.github.kkwans.nasfilebrowser

import android.app.Instrumentation
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

internal suspend fun clickOwnedDescription(instrumentation: Instrumentation, device: UiDevice,
    label: String, stableFrames: Int = 2) = clickOwned(instrumentation, device, label, true, stableFrames)

internal suspend fun clickOwnedText(instrumentation: Instrumentation, device: UiDevice,
    label: String, stableFrames: Int = 2) = clickOwned(instrumentation, device, label, false, stableFrames)

private data class OwnedClickWindow(val id: Int, val display: Int, val bounds: Rect, val screen: Rect)
private data class OwnedClickOwner(val windowId: Int, val displayId: Int, val className: String, val bounds: Rect)

/** Existing read values only; no label, hierarchy or extra accessibility queries. */
private class OwnedClickFailureTrace(private val deadline: Long) {
    private val started = SystemClock.uptimeMillis()
    private var phaseStarted = started
    private var phase = "start"
    private val timings = ArrayDeque<String>()
    var frames = 0
    var owner: OwnedClickOwner? = null
    var leaves: Int? = null
    var matches: Int? = null
    var rejected = "unknown"
    fun enter(next: String) {
        val now = SystemClock.uptimeMillis()
        if (timings.size == 8) timings.removeFirst()
        timings.addLast("$phase:${now - phaseStarted}ms")
        phase = next; phaseStarted = now
    }
    fun failure(error: Throwable): String {
        val now = SystemClock.uptimeMillis()
        val shape = owner?.let {
            val safeClass = it.className.takeIf { value -> value.matches(Regex("[A-Za-z0-9_.$]{1,160}")) } ?: "unknown"
            "class=$safeClass,window=${it.windowId},display=${it.displayId},bounds=${it.bounds}"
        } ?: "unknown"
        return "owned-click-failure exception=${error.javaClass.simpleName} phase=$phase phaseMs=${now - phaseStarted} " +
            "elapsedMs=${now - started} remainingMs=${deadline - now} frames=$frames timings=$timings " +
            "owner={$shape} uiLeaves=${leaves ?: "unknown"} uiMatches=${matches ?: "unknown"} rejected=$rejected"
    }
}

/** AndroidX locates the exact speaking node and its unique real action owner.
 * Keep that UiObject2 through the existing stable frames; do not traverse a second AX tree.
 * Once the one physical click starts, an exception is never retried.
 */
private suspend fun clickOwned(instrumentation: Instrumentation, device: UiDevice, label: String,
    description: Boolean, stableFrames: Int): Unit = withContext(Dispatchers.Default) {
    require(stableFrames > 0)
    val configuration = Configurator.getInstance()
    val previousIdle = configuration.getWaitForIdleTimeout()
    val deadline = SystemClock.uptimeMillis() + 5_000
    val trace = OwnedClickFailureTrace(deadline)
    val packageName = instrumentation.targetContext.packageName
    var target: UiObject2? = null
    var locatedWindow: OwnedClickWindow? = null
    configuration.setWaitForIdleTimeout(0L)
    try {
        withTimeout((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1)) {
            var previous: OwnedClickOwner? = null
            var frames = 0
            while (true) {
                trace.enter("frame-wait")
                nextOwnedFrame()
                trace.enter("active-window")
                val window = activeOwnedWindow(instrumentation)
                if (window == null) {
                    trace.rejected = "active-window-unavailable"
                    target?.recycle(); target = null; locatedWindow = null; frames = 0; previous = null; trace.frames = 0
                    continue
                }
                if (target != null && (locatedWindow?.id != window.id || locatedWindow?.display != window.display)) {
                    trace.rejected = "active-window-changed"
                    target?.recycle(); target = null; locatedWindow = null; frames = 0; previous = null; trace.frames = 0
                }
                if (target == null) {
                    trace.enter("ui-locate")
                    target = locateOwnedAction(device, packageName, window, label, description, trace)
                    locatedWindow = if (target != null) window else null
                }
                val candidate = target
                trace.enter("owner-state")
                val owner = candidate?.let { readOwnedState(it, packageName, window, trace) }
                frames = if (owner != null && owner == previous) frames + 1 else if (owner != null) 1 else 0
                previous = owner; trace.frames = frames
                if (owner == null) { target?.recycle(); target = null; continue }
                if (frames < stableFrames) continue
                trace.enter("final-window-owner")
                val currentWindow = activeOwnedWindow(instrumentation)
                val currentOwner = if (currentWindow == null) null else
                    readOwnedState(requireNotNull(candidate), packageName, currentWindow, trace)
                if (currentOwner != owner) { trace.rejected = "final-window-owner-changed"; frames = 0; continue }
                trace.enter("deadline-check")
                currentCoroutineContext().ensureActive()
                check(SystemClock.uptimeMillis() < deadline) { "Owned action readiness exceeded 5000ms" }
                trace.enter("click")
                requireNotNull(candidate).click(Point(owner.bounds.centerX(), owner.bounds.centerY()))
                trace.enter("click-returned")
                return@withTimeout
            }
        }
    } catch (error: Throwable) {
        runCatching {
            if (InstrumentationRegistry.getArguments().getString("nfbTraceUi") == "true")
                Log.i("FilewayOwnedUi", trace.failure(error))
        }
        throw error
    } finally {
        try { target?.recycle() } finally { configuration.setWaitForIdleTimeout(previousIdle) }
    }
}

private suspend fun nextOwnedFrame(): Unit = withContext(Dispatchers.Main.immediate) {
    suspendCancellableCoroutine { continuation ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
        continuation.invokeOnCancellation {
            if (Looper.myLooper() == Looper.getMainLooper()) choreographer.removeFrameCallback(callback)
            else Handler(Looper.getMainLooper()).post { choreographer.removeFrameCallback(callback) }
        }
        choreographer.postFrameCallback(callback)
    }
}

/** Root/window ownership only: never recursively refresh labels or descendants. */
private fun activeOwnedWindow(instrumentation: Instrumentation): OwnedClickWindow? {
    val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
    try {
        if (!root.refresh() || root.packageName?.toString() != instrumentation.targetContext.packageName || root.windowId < 0) return null
        val window = root.window ?: return null
        try {
            if (!window.isActive || window.id != root.windowId) return null
            val displayId = if (Build.VERSION.SDK_INT >= 30) window.displayId else Display.DEFAULT_DISPLAY
            val display = instrumentation.targetContext.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return null
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            val bounds = Rect().also(window::getBoundsInScreen)
            return OwnedClickWindow(window.id, displayId, bounds, Rect(0, 0, size.x, size.y))
        } finally { window.recycle() }
    } finally { root.recycle() }
}

private fun readOwnedState(node: UiObject2, packageName: String, window: OwnedClickWindow,
    trace: OwnedClickFailureTrace): OwnedClickOwner? = try {
    when {
        node.applicationPackage != packageName -> { trace.rejected = "package"; null }
        node.displayId != window.display -> { trace.rejected = "display"; null }
        !node.isEnabled -> { trace.rejected = "disabled"; null }
        !node.isClickable -> { trace.rejected = "not-clickable"; null }
        else -> {
            val className = node.className
            val bounds = node.visibleBounds
            val shape = OwnedClickOwner(window.id, window.display, className.orEmpty(), bounds)
            trace.owner = shape
            when {
                className.isNullOrEmpty() -> { trace.rejected = "missing-class"; null }
                bounds.isEmpty -> { trace.rejected = "empty-visible-bounds"; null }
                !window.screen.contains(bounds) -> { trace.rejected = "outside-screen"; null }
                !window.bounds.contains(bounds) -> { trace.rejected = "outside-active-window"; null }
                else -> shape
            }
        }
    }
} catch (_: StaleObjectException) { trace.rejected = "stale-owner"; null }

private fun locateOwnedAction(device: UiDevice, packageName: String, window: OwnedClickWindow,
    label: String, description: Boolean, trace: OwnedClickFailureTrace): UiObject2? {
    val matches = mutableListOf<UiObject2>()
    try {
        val selector = (if (description) By.desc(label) else By.text(label)).pkg(packageName)
        trace.leaves = null; trace.matches = null; trace.owner = null; trace.rejected = "unknown"
        val leaves = device.findObjects(selector)
        trace.leaves = leaves.size; trace.matches = null
        for (leaf in leaves) {
            var current: UiObject2? = leaf
            try {
                var depth = 0
                while (current != null && depth++ < 32) {
                    val node = current
                    if (node.applicationPackage != packageName || node.displayId != window.display || !node.isEnabled) break
                    if (node.isClickable) {
                        if (readOwnedState(node, packageName, window, trace) != null && matches.none { it == node }) {
                            matches.add(node); current = null
                        }
                        break
                    }
                    current = node.parent
                    node.recycle()
                }
            } catch (_: StaleObjectException) { trace.rejected = "stale-locator" }
            finally { current?.recycle() }
        }
        trace.matches = matches.size
        if (matches.size != 1) {
            if (trace.rejected == "unknown") trace.rejected = "match-count"
            return null
        }
        return matches.removeAt(0)
    } catch (_: StaleObjectException) { trace.rejected = "stale-locator"; return null }
    finally { matches.forEach { it.recycle() } }
}

/** Failure-only, own-process root geometry. No view text, Intent, credentials,
 * screenshots or foreign-window contents are read or printed. */
internal fun ownedWindowFacts(instrumentation: Instrumentation): String {
    var facts = "unavailable"
    val read = {
        val packageName = instrumentation.targetContext.packageName
        facts = WindowInspector.getGlobalWindowViews().filter { it.context.packageName == packageName }.joinToString(";") { view ->
            val location = IntArray(2); view.getLocationOnScreen(location)
            "root=${view.javaClass.simpleName}@${System.identityHashCode(view)},display=${view.display?.displayId}," +
                "attached=${view.isAttachedToWindow},shown=${view.isShown},focus=${view.hasWindowFocus()}," +
                "bounds=${Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)},alpha=${view.alpha}"
        }
    }
    return try {
        if (Looper.myLooper() == Looper.getMainLooper()) read() else instrumentation.runOnMainSync { read() }
        facts
    } catch (error: Exception) { "unavailable(${error.javaClass.simpleName})" }
}

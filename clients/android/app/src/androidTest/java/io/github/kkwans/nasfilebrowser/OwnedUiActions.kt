package io.github.kkwans.nasfilebrowser

import android.app.Instrumentation
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
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

private data class OwnedClickOwner(val packageName: String, val windowId: Int, val displayId: Int,
    val className: String, val bounds: Rect)

/** Records values already read by the click path; never performs an accessibility query. */
private class OwnedClickFailureTrace(private val deadline: Long) {
    private val started = SystemClock.uptimeMillis()
    private var phaseStarted = started
    private var phase = "start"
    private val timings = ArrayDeque<String>()
    private val rejections = linkedMapOf<String, Int>()
    var frames = 0
    var owner: OwnedClickOwner? = null
    var finalOwner: OwnedClickOwner? = null
    var finalChanged = "unknown"
    var leaves: Int? = null
    var matches: Int? = null
    var uiClass: String? = null
    var uiDisplay: Int? = null
    var uiBounds: Rect? = null
    var uiEnabled: Boolean? = null
    var uiClickable: Boolean? = null
    fun enter(next: String) {
        val now = SystemClock.uptimeMillis()
        if (timings.size == 12) timings.removeFirst()
        timings.addLast("$phase:${now - phaseStarted}ms")
        phase = next; phaseStarted = now
    }
    fun reject(reason: String) { rejections[reason] = (rejections[reason] ?: 0) + 1 }
    fun newUiNode() {
        uiClass = null; uiDisplay = null; uiBounds = null; uiEnabled = null; uiClickable = null
    }
    private fun safeClass(value: String?) = value?.takeIf { it.matches(Regex("[A-Za-z0-9_.$]{1,160}")) } ?: "unknown"
    private fun shape(value: OwnedClickOwner?) = value?.let {
        "class=${safeClass(it.className)},window=${it.windowId},display=${it.displayId},bounds=${it.bounds}"
    } ?: "unknown"
    fun failure(error: Throwable): String {
        val now = SystemClock.uptimeMillis()
        return "owned-click-failure exception=${error.javaClass.simpleName} phase=$phase phaseMs=${now - phaseStarted} " +
            "elapsedMs=${now - started} remainingMs=${deadline - now} frames=$frames timings=$timings " +
            "owner={${shape(owner)}} finalFreshChanged=$finalChanged finalOwner={${shape(finalOwner)}} " +
            "uiLeaves=${leaves ?: "unknown"} uiMatches=${matches ?: "unknown"} " +
            "ui={class=${safeClass(uiClass)},display=${uiDisplay ?: "unknown"},bounds=${uiBounds ?: "unknown"}," +
            "enabled=${uiEnabled ?: "unknown"},clickable=${uiClickable ?: "unknown"}} rejects=$rejections"
    }
}

/** Fresh AX establishes the owner/window. AndroidX then injects one real pointer
 * gesture at that exact owner's center; a failed gesture is never retried. */
private suspend fun clickOwned(instrumentation: Instrumentation, device: UiDevice, label: String,
    description: Boolean, stableFrames: Int): Unit = withContext(Dispatchers.Default) {
    require(stableFrames > 0)
    val configuration = Configurator.getInstance()
    val previousIdle = configuration.getWaitForIdleTimeout()
    val deadline = SystemClock.uptimeMillis() + 5_000
    val failureTrace = OwnedClickFailureTrace(deadline)
    // Every UiObject2 attribute read implicitly waits for idle. Keep that
    // nested wait within the original readiness budget, restoring it exactly.
    configuration.setWaitForIdleTimeout(0L)
    try {
        withTimeout((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1)) {
            var previous: OwnedClickOwner? = null
            var frames = 0
            while (true) {
                failureTrace.enter("frame-wait")
                nextOwnedFrame()
                failureTrace.enter("fresh-owner")
                val owner = freshOwnedOwner(instrumentation, label, description)
                failureTrace.owner = owner
                frames = if (owner != null && owner == previous) frames + 1 else if (owner != null) 1 else 0
                previous = owner
                failureTrace.frames = frames
                if (owner == null) failureTrace.reject("fresh-owner-unavailable")
                if (owner == null || frames < stableFrames) continue
                failureTrace.enter("ui-match")
                val target = matchingUiObject(device, owner, label, description, failureTrace)
                if (target == null) { frames = 0; continue }
                try {
                    failureTrace.enter("final-fresh")
                    failureTrace.finalChanged = "unknown"; failureTrace.finalOwner = null
                    val fresh = freshOwnedOwner(instrumentation, label, description)
                    failureTrace.finalOwner = fresh
                    failureTrace.finalChanged = (fresh != owner).toString()
                    if (fresh != owner) { failureTrace.reject("final-fresh-changed"); frames = 0; continue }
                    failureTrace.enter("deadline-check")
                    currentCoroutineContext().ensureActive()
                    check(SystemClock.uptimeMillis() < deadline) { "Owned action readiness exceeded 5000ms" }
                    // click(Point) uses GestureController, not ACTION_CLICK.
                    // Do not catch/retry after entering this physical action.
                    failureTrace.enter("click")
                    target.click(Point(owner.bounds.centerX(), owner.bounds.centerY()))
                    failureTrace.enter("click-returned")
                    return@withTimeout
                } finally { target.recycle() }
            }
        }
    } catch (error: Throwable) {
        runCatching { OwnedUiTraceRule.trace(failureTrace.failure(error)) }
        throw error
    } finally { configuration.setWaitForIdleTimeout(previousIdle) }
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

private fun freshOwnedOwner(instrumentation: Instrumentation, label: String, description: Boolean): OwnedClickOwner? {
    val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
    val packageName = instrumentation.targetContext.packageName
    val owners = mutableListOf<AccessibilityNodeInfo>()
    var complete = true
    try {
        if (!root.refresh() || root.packageName?.toString() != packageName || root.windowId < 0) return null
        val windowId = root.windowId
        val window = root.window ?: return null
        val displayId: Int
        val windowBounds = Rect()
        try {
            if (!window.isActive || window.id != windowId) return null
            displayId = if (Build.VERSION.SDK_INT >= 30) window.displayId else Display.DEFAULT_DISPLAY
            window.getBoundsInScreen(windowBounds)
        } finally { window.recycle() }
        val display = instrumentation.targetContext.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return null
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        val screen = Rect(0, 0, size.x, size.y)
        val density = instrumentation.targetContext.createDisplayContext(display).resources.displayMetrics.density
        val minimum = 48 * density - 1
        fun nearestOwner(leaf: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            @Suppress("DEPRECATION")
            var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(leaf)
            try {
                var depth = 0
                while (current != null && depth++ < 32) {
                    val node = current
                    if (!node.refresh() || node.windowId != windowId || node.packageName?.toString() != packageName ||
                        !node.isVisibleToUser || !node.isEnabled) return null
                    if (node.isClickable) {
                        val bounds = Rect().also(node::getBoundsInScreen)
                        if (bounds.width() < minimum || bounds.height() < minimum || !screen.contains(bounds) ||
                            !windowBounds.contains(bounds) || node.className.isNullOrEmpty()) return null
                        current = null
                        return node
                    }
                    current = node.parent
                    node.recycle()
                }
                return null
            } finally { current?.recycle() }
        }
        fun visit(node: AccessibilityNodeInfo) {
            if (!node.refresh() || node.windowId != windowId || node.packageName?.toString() != packageName) { complete = false; return }
            val value = if (description) node.contentDescription else node.text
            if (node.isVisibleToUser && value?.toString() == label) {
                nearestOwner(node)?.let { owner ->
                    // Parent and speaking child can both export the label.
                    // AccessibilityNodeInfo equality deduplicates that same
                    // real node; overlapping distinct owners remain ambiguous.
                    if (owners.any { it == owner }) owner.recycle() else owners.add(owner)
                }
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index)
                if (child == null) { complete = false; return }
                try { visit(child) } finally { child.recycle() }
            }
        }
        visit(root)
        if (!complete || owners.size != 1) return null
        val owner = owners.single()
        if (!owner.refresh() || !owner.isVisibleToUser || !owner.isEnabled || !owner.isClickable) return null
        val bounds = Rect().also(owner::getBoundsInScreen)
        if (bounds.width() < minimum || bounds.height() < minimum || !screen.contains(bounds) || !windowBounds.contains(bounds)) return null
        val active = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        try {
            if (!active.refresh() || active.windowId != windowId || active.packageName?.toString() != packageName) return null
            val activeWindow = active.window ?: return null
            try {
                val activeDisplay = if (Build.VERSION.SDK_INT >= 30) activeWindow.displayId else Display.DEFAULT_DISPLAY
                if (!activeWindow.isActive || activeWindow.id != windowId || activeDisplay != displayId) return null
            } finally { activeWindow.recycle() }
        } finally { active.recycle() }
        return OwnedClickOwner(packageName, windowId, displayId, owner.className.toString(), bounds)
    } finally { owners.forEach { it.recycle() }; root.recycle() }
}

/** UiObject2 has no public windowId accessor in 2.3.0. Relate its unique actual
 * owner to the fresh active AX window by package/display/class/full bounds,
 * then recheck that active window before the one physical click. */
private fun matchingUiObject(device: UiDevice, owner: OwnedClickOwner, label: String, description: Boolean,
    failureTrace: OwnedClickFailureTrace): UiObject2? {
    val matches = mutableListOf<UiObject2>()
    failureTrace.leaves = null; failureTrace.matches = null; failureTrace.newUiNode()
    try {
        val selector = (if (description) By.desc(label) else By.text(label)).pkg(owner.packageName)
        val leaves = device.findObjects(selector)
        failureTrace.leaves = leaves.size
        for (leaf in leaves) {
            var current: UiObject2? = leaf
            try {
                var depth = 0
                while (current != null && depth++ < 32) {
                    val node = current
                    failureTrace.newUiNode()
                    // Preserve the original short-circuit property reads. Diagnostics
                    // only retain a value after the original path has requested it.
                    if (node.applicationPackage != owner.packageName) { failureTrace.reject("package"); break }
                    val display = node.displayId
                    failureTrace.uiDisplay = display
                    if (display != owner.displayId) { failureTrace.reject("display"); break }
                    val enabled = node.isEnabled
                    failureTrace.uiEnabled = enabled
                    if (!enabled) { failureTrace.reject("disabled"); break }
                    val clickable = node.isClickable
                    failureTrace.uiClickable = clickable
                    if (clickable) {
                        val className = node.className
                        failureTrace.uiClass = className
                        if (className == owner.className) {
                            val bounds = node.visibleBounds
                            failureTrace.uiBounds = bounds
                            if (bounds == owner.bounds) {
                                if (matches.none { it == node }) { matches.add(node); current = null }
                                else failureTrace.reject("duplicate-owner")
                            } else failureTrace.reject("bounds")
                        } else failureTrace.reject("class")
                        break
                    }
                    current = node.parent
                    node.recycle()
                }
                if (depth > 32) failureTrace.reject("ancestor-depth")
            } catch (_: StaleObjectException) {
                failureTrace.reject("stale-query-node") // No input has been attempted.
            } finally { current?.recycle() }
        }
        failureTrace.matches = matches.size
        if (matches.size != 1) { failureTrace.reject("match-count"); return null }
        return matches.removeAt(0)
    } catch (_: StaleObjectException) {
        failureTrace.matches = matches.size
        failureTrace.reject("stale-query")
        return null
    } finally { matches.forEach { it.recycle() } }
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

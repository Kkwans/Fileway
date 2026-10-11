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

/** Fresh AX establishes the owner/window. AndroidX then injects one real pointer
 * gesture at that exact owner's center; a failed gesture is never retried. */
private suspend fun clickOwned(instrumentation: Instrumentation, device: UiDevice, label: String,
    description: Boolean, stableFrames: Int): Unit = withContext(Dispatchers.Default) {
    require(stableFrames > 0)
    val configuration = Configurator.getInstance()
    val previousIdle = configuration.getWaitForIdleTimeout()
    val deadline = SystemClock.uptimeMillis() + 5_000
    // Every UiObject2 attribute read implicitly waits for idle. Keep that
    // nested wait within the original readiness budget, restoring it exactly.
    configuration.setWaitForIdleTimeout(0L)
    try {
        withTimeout((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1)) {
            var previous: OwnedClickOwner? = null
            var frames = 0
            while (true) {
                nextOwnedFrame()
                val owner = freshOwnedOwner(instrumentation, label, description)
                frames = if (owner != null && owner == previous) frames + 1 else if (owner != null) 1 else 0
                previous = owner
                if (owner == null || frames < stableFrames) continue
                val target = matchingUiObject(device, owner, label, description)
                if (target == null) { frames = 0; continue }
                try {
                    if (freshOwnedOwner(instrumentation, label, description) != owner) { frames = 0; continue }
                    currentCoroutineContext().ensureActive()
                    check(SystemClock.uptimeMillis() < deadline) { "Owned action readiness exceeded 5000ms" }
                    // click(Point) uses GestureController, not ACTION_CLICK.
                    // Do not catch/retry after entering this physical action.
                    target.click(Point(owner.bounds.centerX(), owner.bounds.centerY()))
                    return@withTimeout
                } finally { target.recycle() }
            }
        }
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
private fun matchingUiObject(device: UiDevice, owner: OwnedClickOwner, label: String, description: Boolean): UiObject2? {
    val matches = mutableListOf<UiObject2>()
    try {
        val selector = (if (description) By.desc(label) else By.text(label)).pkg(owner.packageName)
        for (leaf in device.findObjects(selector)) {
            var current: UiObject2? = leaf
            try {
                var depth = 0
                while (current != null && depth++ < 32) {
                    val node = current
                    if (node.applicationPackage != owner.packageName || node.displayId != owner.displayId || !node.isEnabled) break
                    if (node.isClickable) {
                        if (node.className == owner.className && node.visibleBounds == owner.bounds && matches.none { it == node }) {
                            matches.add(node); current = null
                        }
                        break
                    }
                    current = node.parent
                    node.recycle()
                }
            } catch (_: StaleObjectException) { /* Not a gesture retry: this query produced no usable owner. */ }
            finally { current?.recycle() }
        }
        if (matches.size != 1) return null
        return matches.removeAt(0)
    } catch (_: StaleObjectException) { return null }
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

package io.github.kkwans.nasfilebrowser.ui

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import androidx.compose.runtime.*
import io.github.kkwans.nasfilebrowser.BuildConfig
import io.github.kkwans.nasfilebrowser.player.HdrDisplayInfo
import java.util.function.Consumer

private fun hdrDisplayInfo(display: Display?): HdrDisplayInfo {
    display ?: return HdrDisplayInfo()
    val types = display.hdrCapabilities?.supportedHdrTypes ?: return HdrDisplayInfo()
    val ratio = if (Build.VERSION.SDK_INT >= 34 && display.isHdrSdrRatioAvailable)
        display.hdrSdrRatio.takeIf { it.isFinite() && it >= 1f } else null
    return HdrDisplayInfo(true, Display.HdrCapabilities.HDR_TYPE_HDR10 in types,
        Display.HdrCapabilities.HDR_TYPE_HLG in types, Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types, ratio)
}

/** Capability and current headroom are separate from the renderer's actual output format. */
@Composable internal fun rememberPlayerHdrDisplay(view: View): HdrDisplayInfo {
    var value by remember(view) { mutableStateOf(hdrDisplayInfo(view.display)) }
    DisposableEffect(view) {
        val manager = view.context.getSystemService(DisplayManager::class.java)
        var active = true
        var ratioDisplay: Display? = null
        var update: () -> Unit = {}
        val ratioListener = Consumer<Display> { if (active) update() }
        fun stopRatio() {
            if (Build.VERSION.SDK_INT >= 34) ratioDisplay?.unregisterHdrSdrRatioChangedListener(ratioListener)
            ratioDisplay = null
        }
        update = {
            if (active) {
                val display = view.display.takeIf { view.isAttachedToWindow }
                if (Build.VERSION.SDK_INT >= 34) {
                    if (display?.displayId != ratioDisplay?.displayId || display?.isHdrSdrRatioAvailable != true) stopRatio()
                    if (ratioDisplay == null && display?.isHdrSdrRatioAvailable == true) {
                        ratioDisplay = display
                        display.registerHdrSdrRatioChangedListener(view.context.mainExecutor, ratioListener)
                    }
                }
                val next = hdrDisplayInfo(display)
                if (value != next) {
                    value = next
                    if (BuildConfig.DEBUG) android.util.Log.i("FilewayHdrDisplay",
                        "pq=${next.pq} hlg=${next.hlg} dolby=${next.dolby} ratio=${next.ratio}")
                }
            }
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = update()
            override fun onDisplayRemoved(displayId: Int) = update()
            override fun onDisplayChanged(displayId: Int) = update()
        }
        val attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = update()
            override fun onViewDetachedFromWindow(v: View) = stopRatio()
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        view.addOnAttachStateChangeListener(attach)
        update()
        onDispose { active = false; stopRatio(); manager.unregisterDisplayListener(listener); view.removeOnAttachStateChangeListener(attach) }
    }
    return value
}

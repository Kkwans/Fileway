package io.github.kkwans.fileway.tv

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import io.github.kkwans.fileway.tv.medialibrary.TvMediaScrollTestActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvMediaBusyDetailTest {
    @Test
    fun busyUserStateControlsStayMountedAndDisabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, TvMediaScrollTestActivity::class.java).putExtra("screen", "detail-busy")
        ActivityScenario.launch<TvMediaScrollTestActivity>(intent).use {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            await("favorite control", 10_000) { device.findObject(By.text("收藏")) != null }
            await("watched control", 10_000) { device.findObject(By.text("标记已看")) != null }
            val favorite = clickableNode(device, "收藏")
            val watched = clickableNode(device, "标记已看")
            assertNotNull(favorite)
            assertNotNull(watched)
            assertFalse("收藏应保持挂载但禁用", favorite!!.isEnabled)
            assertFalse("标记已看应保持挂载但禁用", watched!!.isEnabled)
        }
    }

    private fun clickableNode(device: UiDevice, text: String): UiObject2? {
        var node = device.findObject(By.text(text))
        while (node != null && !node.isClickable) node = node.parent
        return node
    }

    private fun await(label: String, timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out: $label")
    }
}

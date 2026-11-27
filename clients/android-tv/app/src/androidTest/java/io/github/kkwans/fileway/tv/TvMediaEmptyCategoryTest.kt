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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvMediaEmptyCategoryTest {
    @Test
    fun emptyCategoryKeepsOtherCategoriesReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, TvMediaScrollTestActivity::class.java).putExtra("screen", "home")
        ActivityScenario.launch<TvMediaScrollTestActivity>(intent).use {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            await("category navigation", 10_000) {
                device.findObject(By.text("电影")) != null && device.findObject(By.text("剧集")) != null
            }
            activate(device, "剧集")
            await("empty category message", 10_000) {
                device.findObject(By.text("这个分类还没有可见内容。")) != null
            }
            assertTrue("电影分类必须仍然可见", device.findObject(By.text("电影")) != null)
            activate(device, "电影")
            await("movie catalog restored", 10_000) { device.findObject(By.text("测试影片 0")) != null }
        }
    }

    private fun activate(device: UiDevice, text: String) {
        var target: UiObject2? = null
        await(text, 10_000) {
            var node = device.findObject(By.text(text))
            while (node != null && !node.isClickable) node = node.parent
            target = node
            node != null && node.isEnabled
        }
        requireNotNull(target).click()
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

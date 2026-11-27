package io.github.kkwans.fileway.tv.medialibrary

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvMediaScrollJourneyTest {
    @Test
    fun searchResultsKeepPaginationReachable() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val config = Configurator.getInstance()
        val previousIdle = config.waitForIdleTimeout
        config.waitForIdleTimeout = 0
        try {
            device.executeShellCommand("am start -n io.github.kkwans.fileway.tv/io.github.kkwans.fileway.tv.medialibrary.TvMediaScrollTestActivity --es screen search")
            await("search results", 30_000) { device.findObject(By.text("测试影片 0")) != null }
            val results = device.findObject(By.res("media-search-results"))
            assertNotNull("搜索结果必须位于纵向滚动容器内", results)
            assertTrue("搜索结果容器必须支持纵向滚动", results!!.isScrollable)
            val target = scrollTo(device, results, "加载更多")
            assertTrue("分页按钮必须可见", target.visibleBounds.width() > 0 && target.visibleBounds.height() > 0)
        } finally {
            config.waitForIdleTimeout = previousIdle
        }
    }

    @Test
    fun personAndCollectionWorksKeepPaginationReachable() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val config = Configurator.getInstance()
        val previousIdle = config.waitForIdleTimeout
        config.waitForIdleTimeout = 0
        try {
            device.executeShellCommand("am start -n io.github.kkwans.fileway.tv/io.github.kkwans.fileway.tv.medialibrary.TvMediaScrollTestActivity --es screen works")
            await("works page", 30_000) { device.findObject(By.text("测试作品页")) != null }
            val results = device.findObject(By.res("media-works-results"))
            assertNotNull("人物与合集作品必须位于纵向滚动容器内", results)
            assertTrue("作品容器必须支持纵向滚动", results!!.isScrollable)
            val target = scrollTo(device, results, "加载更多作品")
            assertTrue(target.visibleBounds.width() > 0 && target.visibleBounds.height() > 0)
        } finally {
            config.waitForIdleTimeout = previousIdle
        }
    }

    private fun scrollTo(device: UiDevice, container: UiObject2, label: String): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + 30_000
        val bounds = container.visibleBounds
        val startX = bounds.centerX()
        val startY = bounds.top + bounds.height() * 3 / 4
        val endY = bounds.top + bounds.height() / 4
        while (SystemClock.uptimeMillis() < deadline) {
            device.findObject(By.text(label))?.let { return it }
            device.swipe(startX, startY, startX, endY, 24)
            SystemClock.sleep(180)
        }
        throw AssertionError("分页操作必须可达: $label")
    }

    private fun await(label: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out: $label")
    }
}

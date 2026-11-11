package io.github.kkwans.nasfilebrowser.ui

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.MainActivity
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvPhoneConfirmKeyTest {
    @Test fun shortAndLongPressAreDistinctAndOlderTvKeepsNormalConfirm() {
        assumeTrue("Use a task-owned emulator", Build.FINGERPRINT.contains("generic", true) ||
            Build.HARDWARE.contains("ranchu", true) || Build.HARDWARE.contains("goldfish", true))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        check(!app.getDatabasePath("nfb-client.db").exists() && !app.getDatabasePath("fileway-remote.db").exists()) {
            "Use a fresh isolated phone installation"
        }
        val device = UiDevice.getInstance(instrumentation)
        val short = AtomicInteger()
        val long = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            fun show(detailsKey: Boolean) = activity.onActivity {
                it.setContent {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            TvPhoneConfirmKey(compact = false, detailsKey = detailsKey,
                                onConfirm = { short.incrementAndGet() }, onDetails = { long.incrementAndGet() })
                        }
                    }
                }
            }
            show(true)
            val confirm = device.wait(Until.findObject(By.desc("确认")), 10_000)
            assertNotNull(confirm)
            val bounds = requireNotNull(confirm).visibleBounds
            assertTrue(device.click(bounds.centerX(), bounds.centerY()))
            instrumentation.waitForIdleSync()
            assertEquals(1, short.get())
            assertEquals(0, long.get())
            assertTrue(device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.centerY(), 160))
            instrumentation.waitForIdleSync()
            assertEquals(1, short.get())
            assertEquals(1, long.get())
            show(false)
            val legacy = device.wait(Until.findObject(By.desc("确认")), 10_000)
            assertNotNull(legacy)
            val legacyBounds = requireNotNull(legacy).visibleBounds
            assertTrue(device.click(legacyBounds.centerX(), legacyBounds.centerY()))
            instrumentation.waitForIdleSync()
            assertEquals(2, short.get())
            assertEquals(1, long.get())
        }
    }
}

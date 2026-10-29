package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only the explicit opt-in TV entry case, using the already installed signed
 * phone test component. No account change, new test package or audio check. */
@RunWith(AndroidJUnit4::class)
class TvTouchEntryProbeTest {
    @Test fun pairingEntryRespondsToActualPointer() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val optIn=File(context.getExternalFilesDir(null),"fileway-tv-touch-entry.optin")
        assumeTrue("Explicit TV UI opt-in required",optIn.isFile)
        val device=UiDevice.getInstance(instrumentation)
        try {
            assertEquals("TV must remain foreground", "io.github.kkwans.fileway.tv",device.currentPackageName)
            val settings=device.wait(Until.findObject(By.desc("设置")),5000)?:error("TV settings entry unavailable")
            settings.click()
            val remote=device.wait(Until.findObject(By.text("手机遥控")),5000)?:error("First settings click did not enter settings")
            remote.click()
            val connect=device.wait(Until.findObject(By.textStartsWith("连接手机")),5000)?:error("TV phone connection entry unavailable")
            connect.click()
            assertTrue("Pairing entry must display QR",device.wait(Until.hasObject(By.desc("手机配对二维码")),10000))
            val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(context.getExternalFilesDir(null),"tv-touch-entry.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
            device.findObject(By.text("关闭")).click()
        } finally { optIn.delete() }
    }
}

package io.github.kkwans.nasfilebrowser

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.download.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BatchDownloadTest {
    private val activity = ActivityScenarioRule(MainActivity::class.java)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(OwnedUiTraceRule()).around(activity)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database = ClientDatabase.get(context)
    private val dao = database.downloads()
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun fixture(block: suspend (ClientModel, ServerProfile, ByteArray) -> Unit) {
        // This case verifies batch selection/bytes. Give the owned download
        // fixture its notification prerequisite so a system permission dialog
        // cannot cover the selection row after the queue is already complete.
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val id = UUID.randomUUID().toString()
        val bytes = instrumentation.context.assets.open("media/fixture.mkv").use { it.readBytes() }
        val source = NativePlaybackTest.Fixture(bytes, download = true, videos = listOf("owned-$id-one.mkv", "owned-$id-two.mkv"))
        val previous = database.profiles().activeSession()
        val store = ProfileStore(database, CredentialVault(context))
        val profile = store.save(ServerProfile(name = "Owned batch download", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        try {
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "fixture", "fixture-only", "direct") }
            withTimeout(15000) { model.state.first { it.connected && !it.busy && it.files.size == 2 } }
            withTimeout(5000) {
                while (true) {
                    var focused = false
                    activity.scenario.onActivity { focused = it.hasWindowFocus() }
                    if (focused) break
                    delay(50)
                }
            }
            block(model, profile, bytes)
        } finally { withContext(NonCancellable) {
            main { model.disconnect() }
            for (record in dao.observe().first().filter { it.profileId == profile.id }) {
                DownloadScheduler.pause(context, record.id)
                DownloadRuntime.get(context).awaitStopped(record.id)
                dao.get(record.id)?.let { if (it.localUri.isNotEmpty()) DownloadTarget(context).delete(it) }
                dao.removeRecord(record.id)
            }
            source.close(); store.remove(profile)
            previous?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
        } }
    }

    @Test fun globalSelectionQueuesBothFilesAndDownloadsOriginalBytes(): Unit = runBlocking {
        fixture { model, profile, bytes ->
            val device = UiDevice.getInstance(instrumentation)
            fun text(value: String): androidx.test.uiautomator.UiObject2 {
                device.waitForIdle()
                return device.wait(Until.findObject(By.text(value)), 5000) ?: error("Missing $value")
            }
            fun click(value: String) {
                var button = text(value)
                while (!button.isClickable) button = button.parent ?: error("Missing clickable owner for $value")
                assertTrue("$value must be enabled", button.isEnabled)
                OwnedUiTraceRule.trace("batch-button label=$value bounds=${button.visibleBounds}")
                button.click()
            }
            (device.wait(Until.findObject(By.desc("更多文件操作")), 5000) ?: error("Missing browser actions")).click()
            click("多选"); click("全选"); text("已选 2 项")
            click("反选"); text("已选 0 项")
            click("全选")
            val download = device.wait(Until.findObject(By.desc("批量下载")), 5000) ?: error("Missing batch download")
            download.click()
            val rows = withTimeout(30000) { dao.observe().first { values -> values.count { it.profileId == profile.id && it.complete } == 2 } }
                .filter { it.profileId == profile.id }
            assertEquals(2, rows.size)
            assertEquals(2, rows.map { it.jobId }.distinct().size)
            rows.forEach { record ->
                assertArrayEquals(bytes, context.contentResolver.openInputStream(Uri.parse(record.localUri))!!.use { it.readBytes() })
            }
            withTimeout(5000) { model.downloads.state.first { !it.busy } }
            assertNull(model.downloads.state.value.error)
            assertTrue("Accepted selection returns to browsing", device.wait(Until.gone(By.desc("批量下载")), 5000))
            device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/batch-download.png")
            (device.wait(Until.findObject(By.desc("更多文件操作")), 5000) ?: error("Missing browser actions")).click()
            assertTrue(device.wait(Until.hasObject(By.text("多选")), 5000))
            assertFalse(device.hasObject(By.desc("批量下载")))
            device.pressBack()
        }
    }

    @Test fun sourceChangeStopsRemainingQueueWithoutErasingCreatedTask(): Unit = runBlocking {
        fixture { model, profile, _ ->
            val files = model.state.value.files.toList()
            var callbacks = 0
            main { model.downloadFiles(files) { callbacks++; model.disconnect() } }
            withTimeout(15000) { model.downloads.state.first { !it.busy && it.error != null } }
            assertEquals(1, callbacks)
            assertEquals(1, dao.observe().first().count { it.profileId == profile.id })
            assertTrue(model.downloads.state.value.error!!.contains("1 / 2"))
        }
    }
}

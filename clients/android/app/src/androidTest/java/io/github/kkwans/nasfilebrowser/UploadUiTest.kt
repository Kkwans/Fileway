package io.github.kkwans.nasfilebrowser

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.download.*
import io.github.kkwans.nasfilebrowser.upload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Native page/transfer acceptance with an original owned MediaStore file.
 * The system document-picker itself is accepted separately on the device. */
@RunWith(AndroidJUnit4::class)
internal class UploadUiTest : LibraryUiHarness() {
    @Test fun nativeConfirmationPauseResumeAndNotificationKeepOriginalBytesAndTarget(): Unit = runBlocking {
        val context = instrumentation.targetContext
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val database = ClientDatabase.get(context); val dao = database.uploads(); val store = ProfileStore(database, CredentialVault(context))
        val previous = database.profiles().activeSession(); val source = OwnedUploadFixture()
        val profile = store.save(ServerProfile(name = "Owned upload acceptance", address = source.url))
        val id = UUID.randomUUID().toString(); val name = "fileway-owned-upload-$id.bin"
        val bytes = ByteArray(5 * 1024 * 1024 + 7) { (it * 31).toByte() }
        val target = DownloadTarget(context)
        val original = DownloadRecord(id, 0, "owned", profile.id, 0, "/$name", "/$name", name, "blob", bytes.size.toLong(), "owned", "owned", "Owned source", "",
            createdAt = 1, updatedAt = 1)
        val uri = target.allocate(original); val local = original.copy(localUri = uri.toString(), status = "completed", downloaded = bytes.size.toLong())
        context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }; target.complete(local)
        lateinit var launch: android.content.Intent
        activity.scenario.onActivity { model = ViewModelProvider(it)[io.github.kkwans.nasfilebrowser.app.ClientModel::class.java]; launch = android.content.Intent(it.intent) }
        suspend fun prepare() {
            main { assertTrue(model.beginUploadSelection()); model.uploads.prepare(files = listOf(uri)) }
            withTimeout(15_000) { model.uploads.state.first { !it.busy && it.drafts.isNotEmpty() } }
            text("上传到服务器"); text("开始上传")
        }
        try {
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "fixture", "fixture-only", "direct") }
            withTimeout(15_000) { model.state.first { it.connected && !it.busy } }
            action("更多文件操作").click()
            text("上传文件")
            device.pressBack()
            prepare()
            assertTrue(dao.observe().first().none { it.profileId == profile.id })
            text("取消").click()
            assertTrue(dao.observe().first().none { it.profileId == profile.id })
            prepare(); capture("upload-confirmation")
            source.holdSecondChunk = true
            text("开始上传").click()
            assertTrue(withContext(Dispatchers.IO) { source.held.await(10, TimeUnit.SECONDS) })
            val row = dao.observe().first().single { it.profileId == profile.id }
            assertTrue(row.remoteCreated); assertEquals(2L * 1024 * 1024, row.uploaded)
            main { model.openUploads() }
            text("本机上传"); action("暂停上传：$name").click()
            withTimeout(10_000) { dao.observe().first { list -> list.any { it.id == row.id && it.status == "paused" } } }
            UploadRuntime.get(context).awaitStopped(row.id)
            source.release.countDown()
            assertTrue(withContext(Dispatchers.IO) { source.lateAccepted.await(5, TimeUnit.SECONDS) })
            assertEquals(4 * 1024 * 1024, source.files.getValue("/$name").size)
            assertEquals("paused", dao.get(row.id)!!.status)
            assertEquals("Late old generation cannot overwrite the paused ledger", 2L * 1024 * 1024, dao.get(row.id)!!.uploaded)
            action("继续上传：$name").click()
            val complete = withTimeout(20_000) { dao.observe().first { list -> list.any { it.id == row.id && it.complete } } }.single { it.id == row.id }
            assertEquals(bytes.size.toLong(), complete.uploaded)
            assertArrayEquals(bytes, source.files.getValue("/$name"))
            assertArrayEquals("Local source must remain unchanged", bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            text("上传完成"); capture("upload-completed")
            main { model.tab("files") }
            UploadNotice.notification(context, complete).contentIntent.send()
            withTimeout(5000) { model.state.first { it.tab == "uploads" && it.selected == null && it.image == null } }
            text("本机上传")
            activity.scenario.onActivity { assertSame(model, ViewModelProvider(it)[io.github.kkwans.nasfilebrowser.app.ClientModel::class.java]); assertFalse(it.intent.hasExtra("open_uploads")) }
        } finally { withContext(NonCancellable) {
            source.release.countDown(); activity.scenario.onActivity { it.intent = launch }
            main { model.disconnect() }
            for (row in dao.observe().first().filter { it.profileId == profile.id }) {
                UploadScheduler.pause(context, row.id); UploadRuntime.get(context).awaitStopped(row.id); dao.removeRecord(row.id)
            }
            target.delete(local); store.remove(profile); source.close()
            previous?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
        } }
    }
}

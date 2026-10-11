package io.github.kkwans.nasfilebrowser

import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.download.DownloadTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** Count actual HTTP requests to the other, currently connected NAS. Reuse the
 * existing server fixture; this loopback forwarder does not implement an API. */
private class DownloadedDocumentRequestCounter(upstream: String) : AutoCloseable {
    private val destination = URI(upstream)
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val requests = java.util.Collections.synchronizedList(arrayListOf<String>())
    @Volatile var recording = false
    val url = "http://127.0.0.1:${server.localPort}"
    init { Thread({
        while (!server.isClosed) {
            val client = try { server.accept() } catch (_: Exception) { break }
            sockets.add(client)
            Thread({ try {
                client.soTimeout = 10_000
                Socket(destination.host, destination.port).use { remote ->
                    sockets.add(remote); remote.soTimeout = 10_000
                    try {
                        val input = client.getInputStream(); val header = ByteArrayOutputStream()
                        var ending = 0
                        while (ending != 0x0d0a0d0a) {
                            val byte = input.read(); check(byte >= 0 && header.size() < 65536)
                            header.write(byte); ending = ending.shl(8) or byte
                        }
                        val lines = header.toString("ISO-8859-1").split("\r\n")
                        if (recording) requests.add(lines.first())
                        val size = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                            ?.substringAfter(':')?.trim()?.toInt() ?: 0
                        val output = remote.getOutputStream(); output.write(header.toByteArray())
                        val buffer = ByteArray(8192); var remaining = size
                        while (remaining > 0) {
                            val count = input.read(buffer, 0, minOf(remaining, buffer.size)); check(count > 0)
                            output.write(buffer, 0, count); remaining -= count
                        }
                        output.flush(); remote.getInputStream().copyTo(client.getOutputStream())
                    } finally { sockets.remove(remote) }
                }
            } catch (_: Exception) { /* Owned request canceled during fixture teardown. */ }
            finally { sockets.remove(client); client.close() } }, "owned-document-http-count").apply { isDaemon = true; start() }
        }
    }, "owned-document-http-listener").apply { isDaemon = true; start() } }
    override fun close() { server.close(); sockets.toList().forEach { runCatching { it.close() } } }
}

@RunWith(AndroidJUnit4::class)
internal class DownloadedDocumentUiTest : LibraryUiHarness() {
    private suspend fun clickDisabledDownloadAndProveNoEffects(counter: DownloadedDocumentRequestCounter) {
        val icon = device.wait(Until.findObject(By.desc("下载文档")), 5000) ?: error("Missing document toolbar")
        val ancestors = generateSequence(icon) { it.parent }.take(10).toList()
        val semantics = ancestors.joinToString(" > ") { "${it.className}(enabled=${it.isEnabled},clickable=${it.isClickable})" }
        // Role.Button identifies the actual owner independently of the state
        // this test is about to assert. A disabled owner need not be clickable.
        val button = ancestors.firstOrNull { it.applicationPackage == instrumentation.targetContext.packageName &&
            it.className == "android.widget.Button" } ?: error("Missing download Button owner: $semantics")
        Log.i("DownloadedDocumentUiTest", "Download AX owner: $semantics")
        OwnedUiTraceRule.trace("document download AX owner: $semantics")
        assertFalse("Already downloaded content must have a disabled button; $semantics", button.isEnabled)
        assertFalse("Disabled download must expose no click action; $semantics", button.isClickable)
        val bounds = button.visibleBounds
        val minimum = (48 * instrumentation.targetContext.resources.displayMetrics.density).roundToInt() - 1
        assertTrue("Download owner must expose its 48dp touch area: $bounds; $semantics", bounds.width() >= minimum && bounds.height() >= minimum)
        val dao = ClientDatabase.get(instrumentation.targetContext).downloads()
        val ids = dao.observe().first().map { it.id }.toSet()
        val error = model.downloads.state.value.error; val notice = model.downloads.state.value.notice
        assertNull("The owned download probe must start without an earlier error", error)
        counter.requests.clear(); counter.recording = true
        try {
            // Inject a real touch even though the semantic action is disabled.
            val center = button.visibleCenter
            assertTrue(device.click(center.x, center.y)); device.waitForIdle(); main { }
            assertEquals("Touching disabled download must not create a job", ids, dao.observe().first().map { it.id }.toSet())
            assertFalse(model.downloads.state.value.busy)
            assertEquals("No rejected download callback should run either", error, model.downloads.state.value.error)
            assertEquals(notice, model.downloads.state.value.notice)
            assertTrue("Local document touch sent NAS requests: ${counter.requests}", counter.requests.isEmpty())
        } finally { counter.recording = false }
    }
    @Test fun completedTextAndPdfOpenInsideAppAndBackReturnsToDownloads(): Unit = runBlocking {
        val context = instrumentation.targetContext
        val textBytes = "owned offline needle\n中文 needle".toByteArray()
        val pdfBytes = ownedDocumentPdf()
        val textRow = ownedDownloadedDocument(context, "reader.txt", textBytes)
        val pdfRow = ownedDownloadedDocument(context, "reader.pdf", pdfBytes)
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        val previousTab = model.state.value.tab
        val database = ClientDatabase.get(context)
        val previousSession = database.profiles().activeSession()
        val store = ProfileStore(database, CredentialVault(context))
        val source = ClientSearchTest.Fixture(library = LibraryFixtureData())
        val counter = DownloadedDocumentRequestCounter(source.url)
        val profile = store.save(ServerProfile(name = "Owned downloaded document other NAS", address = counter.url))
        try {
            main { model.selectProfile(profile); model.connectDraft(profile.name, counter.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            assertTrue(model.state.value.permissions.download)
            main { model.openDownloads() }
            text("本机下载"); text("已完成").click()
            withTimeout(5000) { model.downloads.state.first { state -> state.items.any { it.id == textRow.id } && state.items.any { it.id == pdfRow.id } } }
            action("打开下载：${textRow.name}").click()
            withTimeout(10_000) { model.documents.state.first { !it.loading && it.text != null } }
            text("UTF-8 · 只读")
            assertNull(device.findObject(By.desc("编辑文本")))
            clickDisabledDownloadAndProveNoEffects(counter)
            val search = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000) ?: error("Missing local text search")
            search.text = "needle"
            withTimeout(5000) { model.documents.state.first { !it.searching && it.search.matches.size == 2 } }
            text("1 / 2 处匹配")
            action("关闭文档").click(); text("本机下载")
            assertEquals("downloads", model.state.value.tab)
            action("打开下载：${pdfRow.name}").click()
            val pdf = withTimeout(10_000) { model.documents.state.first { !it.loading && !it.rendering && it.pageImage != null } }
            assertEquals(2, pdf.pageCount)
            pdf.pageImage!!.borrow()!!.use { image ->
                val pixel = image.bitmap.getPixel(image.bitmap.width / 2, image.bitmap.height / 2)
                assertTrue(Color.blue(pixel) > 200 && Color.red(pixel) < 80)
            }
            assertNull(device.findObject(By.desc("编辑文本")))
            clickDisabledDownloadAndProveNoEffects(counter)
            action("关闭文档").click(); text("本机下载")
            assertEquals("downloads", model.state.value.tab)
            assertArrayEquals(textBytes, context.contentResolver.openInputStream(Uri.parse(textRow.localUri))!!.use { it.readBytes() })
            assertArrayEquals(pdfBytes, context.contentResolver.openInputStream(Uri.parse(pdfRow.localUri))!!.use { it.readBytes() })
            assertEquals(textRow, ClientDatabase.get(context).downloads().get(textRow.id))
            assertEquals(pdfRow, ClientDatabase.get(context).downloads().get(pdfRow.id))
        } catch (failure: Throwable) {
            runCatching {
                capture("downloaded-document-ui-failure")
                device.dumpWindowHierarchy(java.io.File(context.getExternalFilesDir(null), "downloaded-document-ui-failure.xml"))
            }
            throw failure
        } finally { withContext(NonCancellable) {
            main { model.documents.close(); model.disconnect(); model.tab(previousTab) }
            store.remove(profile); counter.close(); source.close()
            previousSession?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
            for (row in listOf(textRow, pdfRow)) {
                DownloadTarget(context).delete(row); ClientDatabase.get(context).downloads().removeRecord(row.id)
            }
        } }
    }
}

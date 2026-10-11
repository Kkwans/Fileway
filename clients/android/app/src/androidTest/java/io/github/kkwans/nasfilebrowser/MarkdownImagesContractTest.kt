package io.github.kkwans.nasfilebrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** Owned native HTTP/Coil pixels plus controlled lease-race contracts. These
 * fixtures do not stand in for real-NAS or physical-device visual acceptance. */
@RunWith(AndroidJUnit4::class)
internal class MarkdownImagesContractTest : LibraryUiHarness() {
    private val color = Color.rgb(30, 180, 220)
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }
    private suspend fun loaded(controller: DocumentPreviewController) = withTimeout(5000) {
        controller.state.first { !it.loading && it.text != null }
    }
    private fun ownedCapture(stage: String) {
        val packageName = instrumentation.targetContext.packageName
        assertEquals("Capture must stay in the owned app", packageName, device.currentPackageName)
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: error("Owned active window unavailable")
        try { assertEquals("Do not capture notifications or another app", packageName, root.packageName?.toString()) }
        finally { root.recycle() }
        var focused = false
        activity.scenario.onActivity { focused = it.hasWindowFocus() && it.window.decorView.isShown }
        assertTrue("Owned MainActivity must hold focus", focused)
        val name = "markdown-images-owned-$stage-${android.os.SystemClock.uptimeMillis()}.png"
        val output = java.io.File(instrumentation.targetContext.cacheDir, name)
        assertTrue("Owned original-screen capture failed", device.takeScreenshot(output))
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "MARKDOWN_IMAGES_OWNED_CAPTURE=$name\n") })
    }
    private suspend fun showImage(alt: String) = withTimeout(5000) {
        while (true) {
            val image = device.findObject(By.descStartsWith("图片：$alt · "))
            if (image != null) {
                val visible = try { image.visibleBounds.let { it.width() > 0 && it.height() > 0 } } finally { image.recycle() }
                if (visible) return@withTimeout
            }
            val scroller = device.findObject(By.scrollable(true)) ?: error("Owned Markdown reading scroller missing")
            try { scroller.scroll(Direction.DOWN, .6f) } finally { scroller.recycle() }
            delay(25)
        }
    }
    // Exact existing ImageFallbackTest owned 16x16 two-frame looping GIF.
    private fun gif() = android.util.Base64.decode("R0lGODlhEAAQAIEAAOYoPAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAHgAAACwAAAAAEAAQAAAIHQABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFgQEBACH5BAAtAAAALAAAAAAQABAAgR5a5gAAAAAAAAAAAAgdAAEIHEiwoMGDCBMqXMiwocOHECNKnEixosWBAQEAOw==", android.util.Base64.DEFAULT)
    private suspend fun imagePixels(alt: String, expected: Int = color, phase: String = "预览已加载") = withTimeout(5000) {
        val label = "图片：$alt · $phase"
        while (true) {
            val image = device.findObject(By.desc(label))
            if (image != null) {
                val bounds = try { image.visibleBounds } finally { image.recycle() }
                val shot = instrumentation.uiAutomation.takeScreenshot() ?: error("Owned screenshot unavailable")
                val pixels = shot.copy(Bitmap.Config.ARGB_8888, false)
                val matches = try {
                    var hit = 0; var count = 0
                    val radius = (minOf(bounds.width(), bounds.height()) / 4).coerceAtLeast(2)
                    for (y in bounds.centerY() - radius until bounds.centerY() + radius step 4)
                        for (x in bounds.centerX() - radius until bounds.centerX() + radius step 4) {
                            if (x !in 0 until pixels.width || y !in 0 until pixels.height) continue
                            val value = pixels.getPixel(x, y); count++
                            if (abs(Color.red(value) - Color.red(expected)) < 10 && abs(Color.green(value) - Color.green(expected)) < 10 &&
                                abs(Color.blue(value) - Color.blue(expected)) < 10) hit++
                        }
                    count > 0 && hit > count * .7
                } finally { pixels.recycle(); shot.recycle() }
                if (matches) return@withTimeout
            }
            delay(25)
        }
    }

    private suspend fun withOwnedSource(source: ClientSearchTest.Fixture, block: suspend () -> Unit) {
        val database = ClientDatabase.get(instrumentation.targetContext)
        val previousSession = database.profiles().activeSession()
        val store = ProfileStore(database, CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "Owned Markdown images", address = source.url))
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        try {
            model.cache.awaitReady()
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            block()
        } finally { withContext(NonCancellable) {
            try { main { model.documents.close(); model.disconnect() }; store.remove(profile) }
            finally { try { source.close() } finally {
                previousSession?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
            } }
        } }
    }

    @Test fun nativeHttpMetadataPreviewAndCoilRenderRelativeAndRootReferenceImages(): Unit = runBlocking {
        val body = ("# Owned Markdown 图片\n\n**粗体正文**与中文段落。\n\n> Owned 引用\n\n- [X] Owned 已完成\n\n" +
            "| 项目 | 内容 |\n| :--- | ---: |\n| Owned | 完整表格文字 |\n\n```kotlin\nprintln(\"owned\")\n```\n\n" +
            "![Owned relative](relative.png)\n\n![Owned reference][root]\n\n[root]: /root.png\n").toByteArray()
        val preview = png()
        val library = LibraryFixtureData().apply {
            file("/docs/README.md")
            files.getValue("/docs/README.md").put("type", "text").put("size", body.size)
        }
        val source = ClientSearchTest.Fixture(listOf("docs"), previewBody = preview, modified = "owned-version",
            imageBodies = mapOf("docs/README.md" to body, "docs/relative.png" to preview, "root.png" to preview), library = library)
        withOwnedSource(source) {
            main { model.open(ResourceRef("/docs/README.md", "/docs/README.md", "README.md", false, "text", body.size.toLong(), "owned-version")) }
            loaded(model.documents)
            text("Owned Markdown 图片")
            ownedCapture("png-top")
            showImage("Owned relative")
            imagePixels("Owned relative")
            ownedCapture("png-relative")
            showImage("Owned reference")
            imagePixels("Owned reference")
            ownedCapture("png-reference")
            assertEquals(setOf("/api/preview/thumb/docs/relative.png", "/api/preview/thumb/root.png"), source.previewPaths.toSet())
            assertTrue(source.imageMetadataRequests.get() >= 2)
            assertEquals("Images must use contained previews rather than raw image reads", setOf("docs/README.md"), source.rawImages.toSet())
            assertEquals(body.toString(Charsets.UTF_8), model.documents.state.value.text!!.text)
        }
    }

    @Test fun genuineGifUsesBoundedOriginalBytesAndCoilActuallyRendersBothFrames(): Unit = runBlocking {
        val body = "# Owned GIF 阅读\n\n真实双帧 GIF 原图字节。\n\n![Owned GIF](image.gif)\n".toByteArray()
        val image = gif()
        assertEquals("GIF89a", image.copyOfRange(0, 6).toString(Charsets.US_ASCII))
        val library = LibraryFixtureData().apply {
            file("/docs/README.md"); files.getValue("/docs/README.md").put("type", "text").put("size", body.size)
        }
        val source = ClientSearchTest.Fixture(listOf("docs"), modified = "owned-gif-version",
            imageBodies = mapOf("docs/README.md" to body, "docs/image.gif" to image), library = library,
            previewStatusCodes = mapOf("docs/image.gif" to 501))
        withOwnedSource(source) {
            main { model.open(ResourceRef("/docs/README.md", "/docs/README.md", "README.md", false, "text", body.size.toLong())) }
            loaded(model.documents)
            imagePixels("Owned GIF", Color.rgb(230, 40, 60), "原图已加载")
            imagePixels("Owned GIF", Color.rgb(30, 90, 230), "原图已加载")
            ownedCapture("gif-image")
            assertTrue(source.previewPaths.isEmpty())
            assertEquals(setOf("docs/README.md", "docs/image.gif"), source.rawImages.toSet())
            assertEquals(1, source.imageMetadataRequests.get())
            assertEquals(body.toString(Charsets.UTF_8), model.documents.state.value.text!!.text)
        }
    }

    private class Authority(val label: String, val imageName: String = "image.png", val imageSize: Long = 32) {
        val bytes = "# Owned $label\n".toByteArray()
        val file = ResourceRef("/docs/README.md", "/docs/README.md", "README.md", false, "text", bytes.size.toLong(), label)
        var download = true
        var holdPreview: CompletableDeferred<Unit>? = null
        val previewEntered = CompletableDeferred<Unit>()
        val imageUrl = "http://127.0.0.1:12345/stream/image-$label"
        val revoked = java.util.Collections.synchronizedList(mutableListOf<String>())
        var imageMetadata = 0; var previews = 0; var rawImageLeases = 0
        suspend fun session(): SessionContext {
            val profile = ServerProfile(name = "Owned Markdown lease", address = "https://fixture.invalid")
            fun token() = "owned." + android.util.Base64.encodeToString(
                "{\"user\":{\"id\":1,\"username\":\"fixture\",\"perm\":{\"download\":$download}}}".toByteArray(),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP) + ".fixture"
            val api = NasSession.restore(profile, token(), 1) { command -> when (command.getString("op")) {
                "open" -> "owned-markdown-$label"
                "token" -> token()
                "request" -> {
                    assertEquals("GET", command.getString("method"))
                    val wire = command.getString("endpoint").removePrefix("/api/resources").substringBefore('?')
                    val image = wire != file.wirePath
                    if (image) { imageMetadata++; assertEquals("/docs/$imageName", wire) }
                    JSONObject().put("status", 200).put("body", JSONObject().put("path", wire).put("wirePath", wire)
                        .put("name", if (image) imageName else file.name).put("isDir", false).put("type", if (image) "image" else "text")
                        .put("size", if (image) imageSize else bytes.size.toLong()).put("modified", label).toString())
                }
                "lease" -> if (command.getString("wirePath") == file.wirePath) "http://127.0.0.1:12345/stream/document-$label"
                    else { rawImageLeases++; imageUrl }
                "preview" -> {
                    assertEquals("/docs/image.png", command.getString("wirePath")); assertEquals("contain", command.getString("scope"))
                    previews++; previewEntered.complete(Unit)
                    holdPreview?.let { withContext(NonCancellable) { it.await() } }
                    imageUrl
                }
                "revoke" -> { revoked.add(command.getString("url")); Unit }
                "close_session" -> Unit
                else -> error("Unexpected owned Markdown operation")
            } }
            return SessionContext(profile, AccountRecord(label, profile.id, 0, 1, "fixture", "fixture-only", 0), api, 1, label)
        }
        val reader = object : DocumentPreviewReader {
            override suspend fun text(lease: PreviewLease, expected: Long) = bytes.copyOf()
            override suspend fun pdf(context: Context, lease: PreviewLease, expected: Long): DocumentWorkingFile = error("Owned text only")
        }
    }

    @Test fun oversizedGifIsRejectedBeforeAnyOriginalLeaseOrBodyRead(): Unit = runBlocking {
        val authority = Authority("gif-budget", "image.gif", DOCUMENT_TEXT_LIMIT + 1); val owner = authority.session()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = DocumentPreviewController(instrumentation.targetContext, scope, { it === owner }, reader = authority.reader)
        try {
            main { controller.bind(owner); controller.open(authority.file, owner.api.id) }; val state = loaded(controller)
            val binding = withContext(Dispatchers.Main) { controller.imageBinding(state.file!!, state.text!!, state.scope)!! }
            val failure = runCatching { withContext(Dispatchers.Main) { binding.load("/docs/image.gif") } }.exceptionOrNull()
            assertTrue(failure is DocumentImageLimitException)
            assertEquals(1, authority.imageMetadata); assertEquals(0, authority.previews); assertEquals(0, authority.rawImageLeases)
            assertEquals(authority.bytes.toString(Charsets.UTF_8), controller.state.value.text!!.text)
        } finally { withContext(NonCancellable) { main { controller.close() }; owner.api.close(); scope.cancel() } }
    }

    @Test fun nativeGifLeaseBeforeOrDuringBodyReadIsRevokedOnceWhenTheDocumentCloses(): Unit = runBlocking {
        for (beforeBody in listOf(true, false)) {
            val body = "# Owned GIF lifetime\n".toByteArray()
            val library = LibraryFixtureData().apply {
                file("/docs/README.md"); files.getValue("/docs/README.md").put("type", "text").put("size", body.size)
            }
            val source = ClientSearchTest.Fixture(imageBodies = mapOf("docs/image.gif" to gif()), library = library)
            source.heldImage = "docs/image.gif"
            val token = "owned." + android.util.Base64.encodeToString(
                "{\"user\":{\"id\":1,\"username\":\"fixture\",\"perm\":{\"download\":true}}}".toByteArray(),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP) + ".fixture"
            val entered = CompletableDeferred<String>(); val release = CompletableDeferred<Unit>()
            val revoked = java.util.Collections.synchronizedList(mutableListOf<String>())
            val profile = ServerProfile(name = "Owned GIF lifetime", address = source.url)
            val api = NasSession.restore(profile, token, 1) { command ->
                if (command.getString("op") == "revoke") revoked.add(command.getString("url"))
                val result = NativeTransport.call(command)
                if (command.getString("op") == "lease" && command.getString("wirePath") == "/docs/image.gif") {
                    entered.complete(result as String)
                    if (beforeBody) withContext(NonCancellable) { release.await() }
                }
                result
            }
            val owner = SessionContext(profile, AccountRecord("gif-lifetime", profile.id, 0, 1, "fixture", "fixture-only", 0), api, 1)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val reader = object : DocumentPreviewReader {
                override suspend fun text(lease: PreviewLease, expected: Long) = body.copyOf()
                override suspend fun pdf(context: Context, lease: PreviewLease, expected: Long): DocumentWorkingFile = error("Owned text only")
            }
            val controller = DocumentPreviewController(instrumentation.targetContext, scope, { it === owner }, reader = reader)
            try {
                main { controller.bind(owner); controller.open(ResourceRef("/docs/README.md", "/docs/README.md", "README.md", false, "text", body.size.toLong()), api.id) }
                val state = loaded(controller)
                val binding = withContext(Dispatchers.Main) { controller.imageBinding(state.file!!, state.text!!, state.scope)!! }
                val pending = scope.async { binding.load("/docs/image.gif") }
                val rawLease = withTimeout(5000) { entered.await() }
                if (!beforeBody) assertTrue(withContext(Dispatchers.IO) { source.imageStarted.await(5, java.util.concurrent.TimeUnit.SECONDS) })
                pending.cancel(); main { controller.close() }; release.complete(Unit)
                source.releaseImage.countDown()
                withTimeout(5000) { pending.join() }
                assertFalse(binding.current()); assertNull(controller.state.value.text)
                withTimeout(5000) { while (synchronized(revoked) { revoked.count { it == rawLease } } == 0) delay(10) }
                assertEquals(1, synchronized(revoked) { revoked.count { it == rawLease } })
                assertEquals(if (beforeBody) emptySet<String>() else setOf("docs/image.gif"), source.rawImages.toSet())
            } finally { withContext(NonCancellable) {
                release.complete(Unit)
                source.releaseImage.countDown()
                try { main { controller.close() }; api.close() } finally { scope.cancel(); source.close() }
            } }
        }
    }

    @Test fun revokedDownloadPermissionStopsImagesBeforeMetadataOrPreviewLease(): Unit = runBlocking {
        val authority = Authority("permission"); val owner = authority.session()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = DocumentPreviewController(instrumentation.targetContext, scope, { it === owner }, reader = authority.reader)
        try {
            main { controller.bind(owner); controller.open(authority.file, owner.api.id) }; val state = loaded(controller)
            val binding = withContext(Dispatchers.Main) { controller.imageBinding(state.file!!, state.text!!, state.scope)!! }
            authority.download = false
            val failure = runCatching { withContext(Dispatchers.Main) { binding.load("/docs/image.png") } }.exceptionOrNull()
            assertNotNull(failure); assertEquals(0, authority.imageMetadata); assertEquals(0, authority.previews)
            assertEquals(authority.bytes.toString(Charsets.UTF_8), controller.state.value.text!!.text)
        } finally { withContext(NonCancellable) { main { controller.close() }; owner.api.close(); scope.cancel() } }
    }

    @Test fun lateLeaseAfterCloseCancellationOrNewOwnerIsRevokedOnceWithoutReplacingTheDocument(): Unit = runBlocking {
        for (switchOwner in listOf(false, true)) {
            val old = Authority("old-$switchOwner"); val first = old.session()
            val fresh = Authority("fresh-$switchOwner"); val second = fresh.session(); var current = first
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = DocumentPreviewController(instrumentation.targetContext, scope, { it === current }, reader = old.reader)
            val gate = CompletableDeferred<Unit>(); old.holdPreview = gate
            try {
                main { controller.bind(first); controller.open(old.file, first.api.id) }; val state = loaded(controller)
                val binding = withContext(Dispatchers.Main) { controller.imageBinding(state.file!!, state.text!!, state.scope)!! }
                val pending = scope.async { binding.load("/docs/image.png") }
                withTimeout(5000) { old.previewEntered.await() }
                if (switchOwner) main { current = second; controller.bind(second) }
                else { pending.cancel(); main { controller.close() } }
                assertFalse(binding.current())
                gate.complete(Unit)
                withTimeout(5000) { pending.join() }
                assertNotNull(runCatching { pending.await() }.exceptionOrNull())
                assertEquals(1, synchronized(old.revoked) { old.revoked.count { it == old.imageUrl } })
                assertNull(controller.state.value.text)
                assertEquals(if (switchOwner) second.owner else first.owner, controller.state.value.scope)
                assertEquals(1, old.imageMetadata); assertEquals(1, old.previews)
            } finally { withContext(NonCancellable) {
                gate.complete(Unit)
                try { main { controller.close() }; first.api.close(); second.api.close() } finally { scope.cancel() }
            } }
        }
    }
}

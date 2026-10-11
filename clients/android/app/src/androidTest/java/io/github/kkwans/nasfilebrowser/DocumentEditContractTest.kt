package io.github.kkwans.nasfilebrowser

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

internal class DocumentEditFixture(bytes: ByteArray = "old\r\n".toByteArray(), modify: Boolean = true, create: Boolean = true, download: Boolean = true,
    private val name: String = "notes.txt") {
    val parent = DirectoryCrumb("原目录", "/�", "/%FF")
    val sourceWire = "/%FF/${SearchResult.encodePath(name)}"
    val files = linkedMapOf(sourceWire to bytes)
    var version = "2026-10-09T10:00:00Z"
    var supported = true
    var writeStatus = 200
    var loseWriteResponse = false
    var holdWrite: CompletableDeferred<Unit>? = null
    val writeEntered = CompletableDeferred<Unit>()
    val writeFinished = CompletableDeferred<Unit>()
    val writes = mutableListOf<JSONObject>()
    val metadataReads = mutableListOf<String>()
    var contentReads = 0
    private val leases = mutableMapOf<String, String>()
    private val token = "owned." + Base64.encodeToString("{\"user\":{\"id\":1,\"username\":\"fixture\",\"perm\":{\"modify\":$modify,\"create\":$create,\"download\":$download}}}".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP) + ".fixture"
    val file get() = ResourceRef("/�/$name", sourceWire, name, false, "text", files.getValue(sourceWire).size.toLong(), version)
    val reader = object : DocumentPreviewReader {
        override suspend fun text(lease: PreviewLease, expected: Long): ByteArray {
            contentReads++
            return files.getValue(leases.getValue(lease.url)).copyOf()
        }
        override suspend fun pdf(context: Context, lease: PreviewLease, expected: Long): DocumentWorkingFile = error("Not a PDF fixture")
    }
    suspend fun session(owner: String): SessionContext {
        val profile = ServerProfile(name = "Owned document edit", address = "https://fixture.invalid")
        val api = NasSession.restore(profile, token, 1) { command -> when (command.getString("op")) {
            "open" -> "owned-edit-$owner"
            "token" -> token
            "lease" -> "http://127.0.0.1:12345/stream/$owner".also { leases[it] = command.getString("wirePath") }
            "revoke" -> Unit
            "request" -> {
                val endpoint = command.getString("endpoint"); val method = command.getString("method")
                if (method == "GET" && endpoint == "/api/client-capabilities") JSONObject().put("status", 200).put("body", JSONObject().put("conditionalTextSave", supported).toString())
                else if (method == "GET") {
                    val wire = endpoint.substringBefore('?').removePrefix("/api/resources")
                    metadataReads.add(wire)
                    val directory = wire == parent.wirePath
                    val content = files[wire]
                    if (!directory && content == null) JSONObject().put("status", 404).put("body", "missing")
                    else {
                        val name = if (directory) "�" else Uri.decode(wire.substringAfterLast('/'))
                        JSONObject().put("status", 200).put("body", JSONObject().put("path", if (directory) parent.path else parent.path + "/" + name)
                            .put("wirePath", wire).put("name", name).put("isDir", directory).put("type", if (directory) "" else "text")
                            .put("size", content?.size ?: 0).put("modified", version).toString())
                    }
                } else {
                    assertTrue(command.has("bodyBase64")); assertFalse(command.has("body"))
                    val wire = endpoint.substringBefore('?').removePrefix("/api/resources")
                    val body = Base64.decode(command.getString("bodyBase64"), Base64.DEFAULT)
                    writes.add(JSONObject(command.toString()))
                    val query = Uri.parse(endpoint)
                    var status = writeStatus
                    if (status == 200 && method == "POST" && files.containsKey(wire)) status = 409
                    if (status == 200 && method == "PUT" && (query.getQueryParameter("expectedModified") != version ||
                        query.getQueryParameter("expectedSize") != files.getValue(wire).size.toString())) status = 409
                    if (method == "PUT") assertEquals("true", query.getQueryParameter("conditional"))
                    else { assertEquals("POST", method); assertEquals("false", query.getQueryParameter("override")) }
                    if (status == 200) { files[wire] = body; version = "2026-10-09T10:01:00Z" }
                    val gate = holdWrite
                    writeEntered.complete(Unit)
                    gate?.let { try { withContext(NonCancellable) { it.await() } } finally { writeFinished.complete(Unit) } }
                    if (loseWriteResponse && status == 200) throw IOException("Owned lost write response")
                    JSONObject().put("status", status).put("body", if (status == 200) "200 OK" else "rejected")
                }
            }
            else -> error("Unexpected native operation")
        } }
        return SessionContext(profile, AccountRecord(owner, profile.id, 0, 1, "fixture", "fixture-only", 0), api, 1, owner)
    }
}

@RunWith(AndroidJUnit4::class)
class DocumentEditContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun settled(controller: DocumentEditController) = withTimeout(5000) { controller.state.first { !it.saving && !it.loading } }

    @Test fun markdownReadingKeepsOriginalBomConditionalSaveAndOpaqueWirePath(): Unit = runBlocking {
        for ((encoding, bom) in listOf("UTF-8" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            "UTF-16LE" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()), "UTF-16BE" to byteArrayOf(0xFE.toByte(), 0xFF.toByte()))) {
            val initial = bom + "# 原文\r\n\r\n**保留格式**\r\n".toByteArray(charset(encoding))
            val fixture = DocumentEditFixture(initial, name = "README.md")
            val owner = fixture.session("markdown-$encoding")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val preview = DocumentPreviewController(context, scope, { it === owner }, reader = fixture.reader)
            val editor = DocumentEditController(context, scope, { it === owner }, reader = fixture.reader)
            try {
                main { preview.bind(owner); editor.bind(owner); preview.open(fixture.file, owner.api.id) }
                val opened = withTimeout(5000) { preview.state.first { !it.loading && it.text != null } }
                assertTrue(isMarkdownDocument(opened.file!!))
                assertTrue(markdownPreview(opened.text!!) is MarkdownPreview.Reading)
                assertArrayEquals(initial, encodeEditedDocument(documentEditorText(opened.text!!), opened.text!!))
                main { editor.open(opened.file!!, opened.text!!, owner.api.id); editor.edit("# 新文\n\n**仍是源码**\n"); editor.save() }
                settled(editor)
                assertNull(editor.state.value.error)
                assertEquals(1, fixture.writes.size)
                val write = fixture.writes.single()
                assertEquals("PUT", write.getString("method"))
                assertEquals("/api/resources/%FF/README.md", write.getString("endpoint").substringBefore('?'))
                assertEquals("true", Uri.parse(write.getString("endpoint")).getQueryParameter("conditional"))
                assertArrayEquals(bom + "# 新文\r\n\r\n**仍是源码**\r\n".toByteArray(charset(encoding)), fixture.files.getValue(fixture.sourceWire))
            } finally { main { preview.close(); editor.close(discard = true) }; scope.cancel() }
        }
    }
    private fun beginCallbackWrite(controller: DocumentEditController, fixture: DocumentEditFixture, owner: SessionContext, creating: Boolean) {
        controller.bind(owner)
        if (creating) {
            controller.startCreate(fixture.parent, owner.api.id); controller.createName("callback.txt"); controller.createContent("callback draft\n"); controller.create()
        } else {
            controller.open(fixture.file, decodeDocumentText(fixture.files.getValue(fixture.sourceWire)), owner.api.id)
            controller.edit("callback draft\n"); controller.save()
        }
    }

    @Test fun savePublishesCompletionAfterReadbackAndSynchronousCallbackOnly(): Unit = runBlocking {
        val fixture = DocumentEditFixture(); val owner = fixture.session("save-order")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); val checked = CompletableDeferred<Result<Unit>>()
        lateinit var controller: DocumentEditController
        controller = DocumentEditController(context, scope, { it === owner }, onSaved = { bound, actual ->
            checked.complete(runCatching {
                assertSame(owner, bound); assertEquals(1, fixture.writes.size); assertEquals(1, fixture.contentReads)
                assertEquals(fixture.version, actual.modified)
                assertArrayEquals("callback draft\r\n".toByteArray(), fixture.files.getValue(actual.wirePath))
                assertTrue("Keep save locked until local refresh returns", controller.state.value.saving)
                assertTrue(controller.state.value.dirty); assertNull(controller.state.value.notice)
                assertEquals("callback draft\n", controller.state.value.draft)
                controller.edit("reentrant edit"); controller.save(); controller.close(discard = true)
                assertTrue(controller.state.value.saving); assertEquals("callback draft\n", controller.state.value.draft)
                assertEquals(1, fixture.writes.size)
            })
        }, reader = fixture.reader)
        try {
            main { beginCallbackWrite(controller, fixture, owner, false) }
            withTimeout(5000) { checked.await() }.getOrThrow(); settled(controller)
            assertTrue(controller.state.value.acknowledged); assertFalse(controller.state.value.dirty)
            assertFalse(controller.state.value.unknownWrite); assertNull(controller.state.value.error)
            assertEquals(fixture.version, controller.state.value.file!!.modified)
        } finally { main { controller.close(discard = true) }; scope.cancel() }
    }

    @Test fun createKeepsItsInputLockedUntilSynchronousCallbackReturnsWithoutDownloadPermission(): Unit = runBlocking {
        val fixture = DocumentEditFixture(download = false); val owner = fixture.session("create-order")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); val checked = CompletableDeferred<Result<Unit>>()
        lateinit var controller: DocumentEditController
        controller = DocumentEditController(context, scope, { it === owner }, onCreated = { bound, actual ->
            checked.complete(runCatching {
                assertSame(owner, bound); assertEquals(1, fixture.writes.size); assertEquals(0, fixture.contentReads)
                assertTrue(fixture.metadataReads.contains(actual.wirePath))
                assertArrayEquals("callback draft\n".toByteArray(), fixture.files.getValue(actual.wirePath))
                assertTrue(controller.state.value.saving); assertNull(controller.state.value.notice)
                assertEquals("callback.txt", controller.state.value.creation!!.name)
                controller.create(); controller.createName("reentrant.txt"); controller.closeCreation()
                assertTrue(controller.state.value.saving); assertEquals("callback.txt", controller.state.value.creation!!.name)
                assertEquals(1, fixture.writes.size)
            })
        }, reader = fixture.reader)
        try {
            main { beginCallbackWrite(controller, fixture, owner, true) }
            withTimeout(5000) { checked.await() }.getOrThrow(); settled(controller)
            assertNull(controller.state.value.creation); assertNull(controller.state.value.error)
            assertEquals(1, fixture.writes.size)
        } finally { scope.cancel() }
    }

    @Test fun saveCallbackFailureRetainsAcknowledgementAndDraftUntilReadonlyVerificationRecovers(): Unit = runBlocking {
        val fixture = DocumentEditFixture(); val owner = fixture.session("save-callback-failure")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); var failCallback = true; var callbacks = 0
        var checked = CompletableDeferred<Result<Unit>>()
        lateinit var controller: DocumentEditController
        controller = DocumentEditController(context, scope, { it === owner }, onSaved = { _, _ ->
            callbacks++
            checked.complete(runCatching {
                assertTrue(controller.state.value.saving || controller.state.value.loading)
                assertNull(controller.state.value.notice); assertTrue(controller.state.value.dirty)
            })
            if (failCallback) throw ServiceException(409, "Owned saved-view refresh failure after a successful PUT")
        }, reader = fixture.reader)
        try {
            main { beginCallbackWrite(controller, fixture, owner, false) }
            withTimeout(5000) { checked.await() }.getOrThrow(); settled(controller)
            assertTrue(controller.state.value.acknowledged); assertTrue(controller.state.value.unknownWrite)
            assertFalse("A callback's status is not a rejected remote save", controller.state.value.conflict)
            assertEquals("callback draft\n", controller.state.value.draft); assertTrue(controller.state.value.dirty)
            assertTrue(controller.state.value.error.orEmpty().contains("本地")); assertNull(controller.state.value.notice)
            main { controller.save(); controller.save() }; assertEquals(1, fixture.writes.size)
            checked = CompletableDeferred()
            main { controller.verifySave() }; withTimeout(5000) { checked.await() }.getOrThrow(); settled(controller)
            assertTrue(controller.state.value.unknownWrite); assertTrue(controller.state.value.acknowledged); assertEquals(1, fixture.writes.size)
            failCallback = false; checked = CompletableDeferred()
            main { controller.verifySave() }; withTimeout(5000) { checked.await() }.getOrThrow(); settled(controller)
            assertFalse(controller.state.value.unknownWrite); assertFalse(controller.state.value.dirty)
            assertNull(controller.state.value.error); assertEquals(3, callbacks); assertEquals(1, fixture.writes.size)
            assertArrayEquals("callback draft\r\n".toByteArray(), fixture.files.getValue(fixture.sourceWire))
        } finally { main { controller.close(discard = true) }; scope.cancel() }
    }

    @Test fun createCallbackFailureKeepsConfirmedTargetAndInputWithoutRepeatingPost(): Unit = runBlocking {
        val fixture = DocumentEditFixture(); val owner = fixture.session("create-callback-failure")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); var failCallback = true; var callbacks = 0
        var checked = CompletableDeferred<Result<Unit>>()
        lateinit var controller: DocumentEditController
        controller = DocumentEditController(context, scope, { it === owner }, onCreated = { _, _ ->
            callbacks++
            checked.complete(runCatching {
                assertTrue(controller.state.value.saving || controller.state.value.loading)
                assertNull(controller.state.value.notice); assertEquals("callback draft\n", controller.state.value.creation!!.content)
            })
            if (failCallback) throw ServiceException(403, "Owned created-view refresh failure after a successful POST")
        }, reader = fixture.reader)
        try {
            main { beginCallbackWrite(controller, fixture, owner, true) }
            withTimeout(5000) { checked.await() }.getOrThrow(); settled(controller)
            val target = fixture.parent.wirePath!! + "/callback.txt"
            assertTrue(controller.state.value.acknowledged); assertTrue(controller.state.value.unknownWrite)
            assertEquals(target, controller.state.value.creation!!.target!!.wirePath)
            assertTrue(controller.state.value.error.orEmpty().contains("本地")); assertNull(controller.state.value.notice)
            main { controller.create(); controller.createName("must-not-change.txt") }; assertEquals(1, fixture.writes.size)
            assertEquals("callback.txt", controller.state.value.creation!!.name)
            checked = CompletableDeferred()
            main { controller.verifyCreation() }; withTimeout(5000) { checked.await() }.getOrThrow(); settled(controller)
            assertTrue(controller.state.value.unknownWrite); assertTrue(controller.state.value.acknowledged); assertEquals(1, fixture.writes.size)
            failCallback = false; checked = CompletableDeferred()
            main { controller.verifyCreation() }; withTimeout(5000) { checked.await() }.getOrThrow(); settled(controller)
            assertNull(controller.state.value.creation); assertNull(controller.state.value.error)
            assertEquals(3, callbacks); assertEquals(1, fixture.writes.size)
            assertArrayEquals("callback draft\n".toByteArray(), fixture.files.getValue(target))
        } finally { scope.cancel() }
    }

    @Test fun lostAckVerificationCallbackFailureKeepsUnknownAndPromotesProvenAcknowledgement(): Unit = runBlocking {
        for (creating in listOf(false, true)) {
            val fixture = DocumentEditFixture(); fixture.loseWriteResponse = true
            val owner = fixture.session("verify-failure-$creating"); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val checked = CompletableDeferred<Result<Unit>>()
            lateinit var controller: DocumentEditController
            val callback: (SessionContext, ResourceRef) -> Unit = { _, _ ->
                checked.complete(runCatching { assertTrue(controller.state.value.loading); assertTrue(controller.state.value.unknownWrite); assertNull(controller.state.value.notice) })
                throw IllegalStateException("Owned verification-view refresh failure")
            }
            controller = DocumentEditController(context, scope, { it === owner }, onSaved = callback, onCreated = callback, reader = fixture.reader)
            try {
                main { beginCallbackWrite(controller, fixture, owner, creating) }; settled(controller)
                assertTrue(controller.state.value.unknownWrite); assertFalse(controller.state.value.acknowledged)
                main { if (creating) controller.verifyCreation() else controller.verifySave() }
                withTimeout(5000) { checked.await() }.getOrThrow(); settled(controller)
                assertTrue(controller.state.value.unknownWrite); assertTrue(controller.state.value.acknowledged)
                assertTrue(controller.state.value.error.orEmpty().contains("本地")); assertNull(controller.state.value.notice)
                main { if (creating) controller.create() else controller.save() }; assertEquals(1, fixture.writes.size)
            } finally { scope.cancel() }
        }
    }

    @Test fun saveCreateAndVerificationCallbacksCannotRestoreAnUnboundOrDifferentOwner(): Unit = runBlocking {
        for (creating in listOf(false, true)) for (verifying in listOf(false, true)) for (switchOwner in listOf(false, true)) {
            val fixture = DocumentEditFixture(); fixture.loseWriteResponse = verifying
            val owner = fixture.session("old-$creating-$verifying-$switchOwner")
            val fresh = DocumentEditFixture("fresh\n".toByteArray()); val next = fresh.session("next")
            var active = owner
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); val checked = CompletableDeferred<Result<Unit>>()
            lateinit var controller: DocumentEditController
            val callback: (SessionContext, ResourceRef) -> Unit = { bound, _ ->
                checked.complete(runCatching {
                    assertSame(owner, bound); assertTrue(controller.state.value.saving || controller.state.value.loading)
                    if (switchOwner) {
                        active = next; controller.bind(next)
                        controller.open(fresh.file, decodeDocumentText(fresh.files.getValue(fresh.sourceWire)), next.api.id)
                    } else controller.bind(null)
                })
            }
            controller = DocumentEditController(context, scope, { it === active }, onSaved = callback, onCreated = callback, reader = fixture.reader)
            try {
                main { beginCallbackWrite(controller, fixture, owner, creating) }
                if (verifying) { settled(controller); main { if (creating) controller.verifyCreation() else controller.verifySave() } }
                withTimeout(5000) { checked.await() }.getOrThrow(); main { }
                assertEquals(if (switchOwner) next.owner else "", controller.state.value.scope)
                assertEquals(if (switchOwner) "fresh\n" else "", controller.state.value.draft)
                assertNull(controller.state.value.notice); assertNull(controller.state.value.error); assertNull(controller.state.value.creation)
                assertFalse(controller.state.value.loading); assertFalse(controller.state.value.saving); assertFalse(controller.state.value.unknownWrite)
                assertEquals(1, fixture.writes.size); assertTrue(fresh.writes.isEmpty())
            } finally { scope.cancel() }
        }
    }

    @Test fun rawConditionalSavePreservesUtf16BomCrLfAndUnsupportedServersNeverReceiveWrites(): Unit = runBlocking {
        val initial = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "old\r\n".toByteArray(Charsets.UTF_16LE)
        val fixture = DocumentEditFixture(initial); val owner = fixture.session("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); var saved = 0
        val controller = DocumentEditController(context, scope, { it === owner }, onSaved = { _, _ -> saved++ }, reader = fixture.reader)
        try {
            val originalFile = fixture.file
            main { controller.bind(owner); controller.open(originalFile, decodeDocumentText(initial), owner.api.id); controller.edit("新\n文本\n") }
            fixture.supported = false
            main { controller.save() }; settled(controller)
            assertTrue(fixture.writes.isEmpty()); assertTrue(controller.state.value.dirty)
            assertTrue(controller.state.value.error!!.contains("升级服务器"))
            fixture.supported = true
            main { controller.save(); controller.save() }; settled(controller)
            assertEquals(1, fixture.writes.size); assertEquals(1, saved)
            val expected = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "新\r\n文本\r\n".toByteArray(Charsets.UTF_16LE)
            assertArrayEquals(expected, fixture.files.getValue(fixture.sourceWire))
            assertTrue(fixture.writes.single().getString("endpoint").startsWith("/api/resources${fixture.sourceWire}?conditional=true&expectedSize=${initial.size}&expectedModified="))
            assertFalse(controller.state.value.dirty)
            assertEquals(fixture.version, controller.state.value.file!!.modified)
            val denied = DocumentEditFixture(modify = false); val deniedOwner = denied.session("denied")
            val deniedController = DocumentEditController(context, scope, { it === deniedOwner }, reader = denied.reader)
            main { deniedController.bind(deniedOwner); deniedController.open(denied.file, decodeDocumentText(denied.files.getValue(denied.sourceWire)), deniedOwner.api.id); deniedController.edit("not allowed"); deniedController.save() }
            settled(deniedController)
            assertTrue(denied.writes.isEmpty())
            assertTrue(deniedController.state.value.error!!.contains("修改权限"))
            main { deniedController.close(discard = true) }
        } finally { main { controller.close(discard = true) }; scope.cancel() }
    }
    @Test fun conflictRetainsDraftAndUnknownSaveOnlyReadsDuringVerification(): Unit = runBlocking {
        val fixture = DocumentEditFixture(); val owner = fixture.session("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = DocumentEditController(context, scope, { it === owner }, reader = fixture.reader)
        try {
            main { controller.bind(owner); controller.open(fixture.file, decodeDocumentText(fixture.files.getValue(fixture.sourceWire)), owner.api.id); controller.edit("draft\n") }
            fixture.writeStatus = 409
            main { controller.save() }; settled(controller)
            assertTrue(controller.state.value.conflict); assertEquals("draft\n", controller.state.value.draft)
            main { controller.save() }; assertEquals(1, fixture.writes.size)
            main { controller.reload() }; settled(controller)
            assertFalse(controller.state.value.conflict); assertFalse(controller.state.value.dirty)
            fixture.writeStatus = 200; fixture.loseWriteResponse = true
            main { controller.edit("lost\n"); controller.save() }; settled(controller)
            assertTrue(controller.state.value.unknownWrite)
            main { controller.save(); controller.save() }; assertEquals(2, fixture.writes.size)
            main { controller.verifySave() }; settled(controller)
            assertFalse(controller.state.value.unknownWrite); assertFalse(controller.state.value.dirty)
            assertEquals(2, fixture.writes.size)
        } finally { main { controller.close(discard = true) }; scope.cancel() }
    }
    @Test fun exclusiveCreateRetainsRejectedInputAndAllowsCreateWithoutDownloadPermission(): Unit = runBlocking {
        val fixture = DocumentEditFixture(download = false); val owner = fixture.session("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); var created: ResourceRef? = null
        val controller = DocumentEditController(context, scope, { it === owner }, onCreated = { _, file -> created = file }, reader = fixture.reader)
        try {
            main { controller.bind(owner); controller.startCreate(fixture.parent, owner.api.id); controller.createName("notes.txt"); controller.createContent("保留输入\n"); controller.create() }; settled(controller)
            assertEquals("保留输入\n", controller.state.value.creation!!.content)
            assertTrue(controller.state.value.error!!.contains("未覆盖"))
            assertArrayEquals("old\r\n".toByteArray(), fixture.files.getValue(fixture.sourceWire))
            val name = "%2F +# 中文.txt"
            main { controller.createName(name); controller.createContent(""); controller.create() }; settled(controller)
            assertNull(controller.state.value.creation)
            val expectedWire = "/%FF/" + SearchResult.encodePath(name)
            assertEquals(expectedWire, created!!.wirePath)
            assertArrayEquals(byteArrayOf(), fixture.files.getValue(expectedWire))
            assertTrue(fixture.writes.last().getString("endpoint").endsWith("?override=false"))
        } finally { scope.cancel() }
    }
    @Test fun unknownCreateNeverResubmitsAndOnlyMatchingContentAcknowledgesRecovery(): Unit = runBlocking {
        val fixture = DocumentEditFixture(); val owner = fixture.session("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); var created = 0
        val controller = DocumentEditController(context, scope, { it === owner }, onCreated = { _, _ -> created++ }, reader = fixture.reader)
        fixture.loseWriteResponse = true
        try {
            main { controller.bind(owner); controller.startCreate(fixture.parent, owner.api.id); controller.createName("new.txt"); controller.createContent("\"新\"\n"); controller.create() }; settled(controller)
            assertTrue(controller.state.value.unknownWrite); assertEquals(0, created)
            main { controller.create(); controller.create() }; assertEquals(1, fixture.writes.size)
            main { controller.verifyCreation() }; settled(controller)
            assertNull(controller.state.value.creation); assertEquals(1, created); assertEquals(1, fixture.writes.size)
        } finally { scope.cancel() }
    }
    @Test fun latePreviousAccountWriteCannotChangeNewDraftOrCallSavedCallback(): Unit = runBlocking {
        val old = DocumentEditFixture(); val fresh = DocumentEditFixture("fresh\n".toByteArray())
        val first = old.session("old"); val second = fresh.session("new"); var active = first; var saved = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = DocumentEditController(context, scope, { it === active }, onSaved = { _, _ -> saved++ }, reader = old.reader)
        old.holdWrite = CompletableDeferred()
        try {
            main { controller.bind(first); controller.open(old.file, decodeDocumentText(old.files.getValue(old.sourceWire)), first.api.id); controller.edit("changed\n"); controller.save() }
            withTimeout(5000) { old.writeEntered.await() }
            main { active = second; controller.bind(second); controller.open(fresh.file, decodeDocumentText(fresh.files.getValue(fresh.sourceWire)), second.api.id) }
            old.holdWrite!!.complete(Unit)
            withTimeout(5000) { old.writeFinished.await() }; withContext(Dispatchers.Main) { yield() }
            assertEquals("new", controller.state.value.scope); assertEquals("fresh\n", controller.state.value.draft)
            assertEquals(0, saved); assertTrue(fresh.writes.isEmpty())
        } finally { old.holdWrite?.complete(Unit); main { controller.close(discard = true) }; scope.cancel() }
    }
}

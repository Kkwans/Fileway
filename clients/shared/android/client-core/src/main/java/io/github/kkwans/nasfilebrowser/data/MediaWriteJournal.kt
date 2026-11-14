package io.github.kkwans.nasfilebrowser.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

enum class MediaWriteState { UNKNOWN, ACKNOWLEDGED, ACKNOWLEDGED_INVALID_RESPONSE, REJECTED }

/** Only server IDs and revisions needed for a fresh read; never contains source paths. */
data class MediaWriteReceipt(
    val libraryId: String, val sourceBindingId: String? = null, val scanId: String? = null,
    val memberId: Long? = null, val revision: Long? = null, val libraryRevision: Long? = null, val scanGeneration: Long? = null,
    val itemId: String? = null, val metadataRevision: Long? = null,
)

data class MediaWriteRecord(
    val originalScope: MediaSessionScope, val lastScope: MediaSessionScope, val operation: MediaOperation,
    val state: MediaWriteState, val receipt: MediaWriteReceipt?, val failure: MediaFailure?,
    val inFlight: Boolean, val storageFailed: Boolean,
)

class MediaJournalException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Caller-owned and account-filtered. UNKNOWN is never automatically replayed or evicted. */
class MediaWriteJournal private constructor(private val file: AtomicFile, private val capacity: Int, private val ownerPath: String) {
    private data class Entry(
        val originalScope: MediaSessionScope, var lastScope: MediaSessionScope, val request: MediaMutationRequest,
        var state: MediaWriteState = MediaWriteState.UNKNOWN, var receipt: MediaWriteReceipt? = null,
        var failure: MediaFailure? = null, var inFlight: Boolean = false, var storageFailed: Boolean = false,
    )
    private val entries = LinkedHashMap<MediaOperation, Entry>()
    private val io = Mutex()
    private var closed = false

    companion object {
        private const val MAX_FILE_BYTES = 12 * 1024 * 1024
        private const val SAVE_TIMEOUT_MS = 2_000L
        // One caller-owned journal per app file prevents independent snapshots
        // from overwriting one another. This registry owns no coroutine or I/O.
        private val activeFiles = mutableSetOf<String>()
        /** Use an application context. The file and source wire paths remain app-private. */
        suspend fun open(context: Context, capacity: Int = 128): MediaWriteJournal {
            require(capacity in 1..1024)
            return withContext(Dispatchers.IO) {
                val root = context.filesDir ?: throw MediaJournalException("媒体库操作记录目录不可用")
                val base = File(root, "media-library-writes.json")
                val path = base.canonicalPath
                synchronized(activeFiles) { check(activeFiles.add(path)) { "媒体库操作记录已打开" } }
                try { MediaWriteJournal(AtomicFile(base), capacity, path).also { it.load() } }
                catch (error: Exception) { synchronized(activeFiles) { activeFiles.remove(path) }; throw error }
            }
        }
    }

    /** The owner closes the journal only after its writes have completed. */
    suspend fun close() = io.withLock {
        synchronized(this) {
            check(!closed && entries.values.none { it.inFlight }) { "媒体库操作仍在进行" }
            closed = true
        }
        synchronized(activeFiles) { activeFiles.remove(ownerPath) }
    }

    /** Present records only for the currently active account. */
    @Synchronized fun records(account: MediaAccountScope): List<MediaWriteRecord> {
        check(!closed) { "媒体库操作记录已关闭" }
        return entries.values.filter { it.request.operation.account == account }
            .map { MediaWriteRecord(it.originalScope, it.lastScope, it.request.operation, it.state, it.receipt, it.failure, it.inFlight, it.storageFailed) }
    }

    /** Acknowledged/rejected entries can be removed after the owner has resolved them. */
    suspend fun forget(operation: MediaOperation): Boolean = io.withLock {
        val removed = synchronized(this) {
            check(!closed) { "媒体库操作记录已关闭" }
            val entry = entries[operation] ?: return@withLock false
            if (entry.inFlight || entry.state == MediaWriteState.UNKNOWN) return@withLock false
            entries.remove(operation)
        } ?: return@withLock false
        try { writeSnapshot(); true }
        catch (error: Exception) {
            synchronized(this) { entries[operation] = removed }
            if (error is kotlinx.coroutines.CancellationException) throw error
            false
        }
    }

    /** Persists the exact request before any network operation starts. */
    internal suspend fun begin(scope: MediaSessionScope, request: MediaMutationRequest): Boolean = io.withLock {
        var inserted = false
        val entry = synchronized(this) {
            check(!closed) { "媒体库操作记录已关闭" }
            val previous = entries[request.operation]
            if (previous == null) {
                check(entries.size < capacity) { "待处理的媒体库操作过多，请先处理已有记录" }
                inserted = true
                Entry(scope, scope, request, inFlight = true).also { entries[request.operation] = it }
            } else {
                check(!previous.inFlight) { "相同操作正在进行" }
                check(previous.request.sameRequest(request)) { "不能使用相同操作标识提交不同参数" }
                previous.lastScope = scope
                previous.inFlight = true
                previous
            }
        }
        try {
            writeSnapshot()
            synchronized(this) { entry.storageFailed = false }
            true
        } catch (error: Exception) {
            synchronized(this) {
                if (inserted) entries.remove(request.operation) else { entry.inFlight = false; entry.storageFailed = true }
            }
            if (error is kotlinx.coroutines.CancellationException) throw error
            false
        }
    }

    @Synchronized internal fun replay(operation: MediaOperation): MediaMutationRequest {
        check(!closed) { "媒体库操作记录已关闭" }
        return entries[operation]?.request ?: error("找不到待确认操作")
    }

    @Synchronized internal fun firstUnresolved(
        account: MediaAccountScope, kinds: Set<MediaMutationKind>, libraryId: String? = null, itemId: String? = null,
    ): MediaOperation? =
        entries.values.firstOrNull {
            !it.inFlight && it.request.operation.account == account && it.request.kind in kinds &&
                (libraryId == null || it.request.libraryId == libraryId) && (itemId == null || it.request.itemId == itemId) &&
                (it.state == MediaWriteState.UNKNOWN || it.state == MediaWriteState.ACKNOWLEDGED_INVALID_RESPONSE)
        }?.request?.operation

    /** Called synchronously as soon as the 2xx response is observed. */
    @Synchronized internal fun accepted(operation: MediaOperation, receipt: MediaWriteReceipt?, failure: MediaFailure?) {
        val entry = checkNotNull(entries[operation])
        entry.receipt = receipt
        entry.failure = failure
        entry.state = if (receipt != null) MediaWriteState.ACKNOWLEDGED else MediaWriteState.ACKNOWLEDGED_INVALID_RESPONSE
    }

    @Synchronized internal fun failed(operation: MediaOperation, failure: MediaFailure) {
        val entry = checkNotNull(entries[operation])
        // A later replay can lose permission; that cannot erase an earlier 2xx.
        if (entry.state == MediaWriteState.ACKNOWLEDGED || entry.state == MediaWriteState.ACKNOWLEDGED_INVALID_RESPONSE) return
        entry.failure = failure
        entry.state = if (failure.mutationMayHaveCommitted) MediaWriteState.UNKNOWN else MediaWriteState.REJECTED
    }

    @Synchronized internal fun finish(operation: MediaOperation) { entries[operation]?.inFlight = false }

    /** Local persistence only; never protects or retries a network request. */
    internal suspend fun saveOutcome(operation: MediaOperation): Boolean {
        val saved = withContext(NonCancellable) {
            try {
                withTimeoutOrNull(SAVE_TIMEOUT_MS) {
                    io.withLock { writeSnapshot() }
                    true
                } ?: false
            } catch (_: Exception) { false }
        }
        synchronized(this) { entries[operation]?.storageFailed = !saved }
        return saved
    }

    private suspend fun writeSnapshot() {
        val encoded = synchronized(this) { serialize() }.toString().toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_FILE_BYTES) { "媒体库操作记录过大" }
        runInterruptible(Dispatchers.IO) {
            val stream = file.startWrite()
            try { stream.write(encoded); file.finishWrite(stream) }
            catch (error: Throwable) { file.failWrite(stream); throw error }
        }
    }

    private fun serialize(): JSONObject = JSONObject().put("schema", 1).put("entries", JSONArray().apply {
        entries.values.forEach { entry ->
            val account = entry.request.operation.account
            put(JSONObject().put("account", accountJson(account)).put("original", sessionJson(entry.originalScope))
                .put("last", sessionJson(entry.lastScope)).put("operationId", entry.request.operation.id)
                .put("request", entry.request.toJson()).put("state", entry.state.name).apply {
                    entry.receipt?.let { put("receipt", receiptJson(it)) }
                    entry.failure?.let { put("failure", failureJson(it)) }
                })
        }
    })

    private fun load() {
        val bytes = try {
            file.openRead().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    require(out.size() + n <= MAX_FILE_BYTES)
                    out.write(chunk, 0, n)
                }
                out.toByteArray()
            }
        } catch (error: FileNotFoundException) {
            val base = file.baseFile
            if (!base.exists() && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists()) return
            throw MediaJournalException("无法读取媒体库操作记录", error)
        } catch (error: Exception) { throw MediaJournalException("无法读取媒体库操作记录", error) }
        try {
            val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val tokener = org.json.JSONTokener(decoded)
            val root = tokener.nextValue() as? JSONObject ?: error("invalid journal")
            require(tokener.nextClean() == '\u0000')
            require(root.keys().asSequence().toSet() == setOf("schema", "entries") && jsonLong(root, "schema", 1) == 1L)
            val rows = root.getJSONArray("entries")
            require(rows.length() <= 1024)
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val expected = mutableSetOf("account", "original", "last", "operationId", "request", "state")
                if (row.has("receipt")) expected.add("receipt")
                if (row.has("failure")) expected.add("failure")
                require(row.keys().asSequence().toSet() == expected)
                val account = readAccount(row.getJSONObject("account"))
                val operation = MediaOperation(account, jsonString(row, "operationId"))
                val request = MediaMutationRequest.fromJson(operation, row.getJSONObject("request"))
                val state = MediaWriteState.valueOf(jsonString(row, "state"))
                val receipt = if (row.has("receipt")) readReceipt(row.getJSONObject("receipt")) else null
                val failure = if (row.has("failure")) readFailure(row.getJSONObject("failure")) else null
                require((state == MediaWriteState.ACKNOWLEDGED) == (receipt != null))
                require(state != MediaWriteState.ACKNOWLEDGED_INVALID_RESPONSE || failure?.kind == MediaFailureKind.INVALID_RESPONSE)
                require(entries.put(operation, Entry(readSession(row.getJSONObject("original"), account),
                    readSession(row.getJSONObject("last"), account), request, state, receipt, failure)) == null)
            }
        } catch (error: Exception) { throw MediaJournalException("媒体库操作记录损坏或版本不受支持，未清除原文件", error) }
    }
}

private fun jsonString(row: JSONObject, key: String): String = row.get(key) as? String ?: error("invalid journal")
private fun jsonLong(row: JSONObject, key: String, min: Long = 0): Long {
    val value = (row.get(key) as? Number)?.toString()?.toLongOrNull()
    require(value != null && value >= min)
    return value
}
private fun accountJson(value: MediaAccountScope) = JSONObject().put("profileId", value.profileId).put("sourceRevision", value.sourceRevision)
    .put("accountKey", value.accountKey).put("userId", value.userId)
private fun readAccount(row: JSONObject): MediaAccountScope {
    require(row.keys().asSequence().toSet() == setOf("profileId", "sourceRevision", "accountKey", "userId"))
    val profile = jsonString(row, "profileId"); val key = jsonString(row, "accountKey")
    requireMediaId(profile); require(key.isNotEmpty() && key.toByteArray(Charsets.UTF_8).size <= 512)
    return MediaAccountScope(profile, jsonLong(row, "sourceRevision"), key, jsonLong(row, "userId"))
}
private fun sessionJson(scope: MediaSessionScope) = JSONObject().put("owner", scope.owner).put("generation", scope.generation)
private fun readSession(row: JSONObject, account: MediaAccountScope): MediaSessionScope {
    require(row.keys().asSequence().toSet() == setOf("owner", "generation"))
    val owner = jsonString(row, "owner"); requireMediaId(owner)
    val generation = jsonLong(row, "generation"); require(generation <= Int.MAX_VALUE)
    return MediaSessionScope(account, owner, generation.toInt())
}
private fun receiptJson(value: MediaWriteReceipt) = JSONObject().put("libraryId", value.libraryId).apply {
    value.sourceBindingId?.let { put("sourceBindingId", it) }; value.scanId?.let { put("scanId", it) }
    value.memberId?.let { put("memberId", it) }; value.revision?.let { put("revision", it) }
    value.libraryRevision?.let { put("libraryRevision", it) }; value.scanGeneration?.let { put("scanGeneration", it) }
    value.itemId?.let { put("itemId", it) }; value.metadataRevision?.let { put("metadataRevision", it) }
}
private fun readReceipt(row: JSONObject): MediaWriteReceipt {
    val allowed = setOf("libraryId", "sourceBindingId", "scanId", "memberId", "revision", "libraryRevision", "scanGeneration", "itemId", "metadataRevision")
    require(row.keys().asSequence().all { it in allowed })
    val library = jsonString(row, "libraryId").also(::requireMediaRouteId)
    fun id(key: String) = if (row.has(key)) jsonString(row, key).also(::requireMediaRouteId) else null
    fun number(key: String) = if (row.has(key)) jsonLong(row, key, 1) else null
    return MediaWriteReceipt(library, id("sourceBindingId"), id("scanId"), number("memberId"), number("revision"), number("libraryRevision"), number("scanGeneration"),
        id("itemId"), if (row.has("metadataRevision")) jsonLong(row, "metadataRevision") else null)
}
private fun failureJson(value: MediaFailure) = JSONObject().put("kind", value.kind.name).put("mutationMayHaveCommitted", value.mutationMayHaveCommitted).apply {
    value.status?.let { put("status", it) }
}
private fun readFailure(row: JSONObject): MediaFailure {
    require(row.keys().asSequence().all { it in setOf("kind", "status", "mutationMayHaveCommitted") })
    val status = if (row.has("status")) jsonLong(row, "status", 100).also { require(it <= 599) }.toInt() else null
    val unknown = row.get("mutationMayHaveCommitted") as? Boolean ?: error("invalid journal")
    return MediaFailure(MediaFailureKind.valueOf(jsonString(row, "kind")), status, unknown)
}

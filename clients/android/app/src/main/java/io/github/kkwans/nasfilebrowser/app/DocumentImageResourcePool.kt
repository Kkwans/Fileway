package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.PreviewLease
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal const val DOCUMENT_IMAGE_BYTES_LIMIT = 24L * 1024 * 1024

/** No persistent cache: an entry lives only while this document has references.
 * The original-byte reservation includes pending reads, including retiring
 * reads which have not stopped yet. Decoded Coil frames have their own cache. */
internal class DocumentImageResourcePool(
    private val scope: CoroutineScope,
    private val cleanup: CoroutineScope,
    private val byteLimit: Long = DOCUMENT_IMAGE_BYTES_LIMIT,
    parallelism: Int = 2,
) {
    private data class Key(val epoch: Long, val document: String, val file: ResourceRef)
    private class Entry(val key: Key, val bytes: Long) {
        val ready = CompletableDeferred<Unit>()
        lateinit var producer: Job
        var references = 0
        var retired = false
        var finished = false
        var accounted = true
        var value: DocumentImageLease? = null
    }
    private val epoch = AtomicLong()
    private val mutex = Mutex()
    private val permits = Semaphore(parallelism)
    private val entries = mutableMapOf<Key, Entry>()
    private var reservedBytes = 0L

    fun generation(): Long = epoch.get()

    suspend fun acquire(generation: Long, document: String, file: ResourceRef, originalBytes: Long,
        load: suspend () -> DocumentImageLease): DocumentImageLease {
        require(originalBytes >= 0)
        val key = Key(generation, document, file)
        val entry = mutex.withLock {
            checkCurrent(generation)
            val shared = entries[key] ?: run {
                if (originalBytes > byteLimit - reservedBytes)
                    throw DocumentImageLimitException("图片原图合计超过 24 MiB，暂未加载；原文件未改变。")
                Entry(key, originalBytes).also { created ->
                    reservedBytes += originalBytes
                    entries[key] = created
                    created.producer = scope.launch(start = CoroutineStart.LAZY) { produce(created, load) }
                    // A cancelled parent can prevent a LAZY producer from ever
                    // entering its finally block. Retire its reservation too.
                    created.producer.invokeOnCompletion { cause ->
                        cleanup.launch { finish(created, cause) }
                    }
                }
            }
            check(shared.bytes == originalBytes)
            shared.references++
            shared
        }
        entry.producer.start()
        var reference: PreviewLease? = null
        try {
            entry.ready.await()
            val image = mutex.withLock {
                checkCurrent(generation)
                if (entry.retired) throw CancellationException("文档图片已释放")
                val shared = checkNotNull(entry.value)
                val ticket = PreviewLease(shared.lease.url, shared.lease.scope) { release(entry) }
                reference = ticket
                DocumentImageLease(shared.file, ticket, shared.data)
            }
            currentCoroutineContext().ensureActive()
            return image
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                val ticket = reference
                if (ticket == null) release(entry) else ticket.release()
            }
            throw error
        }
    }

    /** Invalidates synchronously; cleanup never makes the retired epoch usable
     * again. A new account/document still shares this controller's byte budget. */
    fun retire() {
        val retiredThrough = epoch.getAndIncrement()
        cleanup.launch {
            val leases = mutex.withLock {
                entries.values.filter { it.key.epoch <= retiredThrough }.mapNotNull { entry ->
                    retireLocked(entry)
                }
            }
            leases.forEach { runCatching { it.release() } }
        }
    }

    private fun checkCurrent(generation: Long) {
        if (generation != epoch.get()) throw CancellationException("文档来源已切换")
    }

    private suspend fun produce(entry: Entry, load: suspend () -> DocumentImageLease) {
        var unowned: DocumentImageLease? = null
        try {
            permits.withPermit {
                currentCoroutineContext().ensureActive()
                checkCurrent(entry.key.epoch)
                unowned = load()
                currentCoroutineContext().ensureActive()
                mutex.withLock {
                    checkCurrent(entry.key.epoch)
                    if (entry.retired || entry.references == 0) throw CancellationException("文档图片已释放")
                    entry.value = unowned
                    unowned = null
                    entry.ready.complete(Unit)
                }
            }
        } catch (error: Throwable) {
            entry.ready.completeExceptionally(error)
        } finally {
            withContext(NonCancellable) {
                // A native lease may arrive after cancellation. Never publish it.
                val lease = unowned?.lease
                unowned = null
                try { lease?.release() } finally { finish(entry, null) }
            }
        }
    }

    private suspend fun finish(entry: Entry, cause: Throwable?) {
        val lease = mutex.withLock {
            if (entry.finished) return@withLock null
            entry.finished = true
            if (entry.value == null) {
                entry.ready.completeExceptionally(cause ?: CancellationException("文档图片已释放"))
                entry.retired = true
                if (entries[entry.key] === entry) entries.remove(entry.key)
            }
            if (entry.retired || entry.references == 0) retireLocked(entry) else null
        }
        lease?.release()
    }

    private suspend fun release(entry: Entry) {
        val (lease, producer) = mutex.withLock {
            check(entry.references > 0)
            entry.references--
            if (entry.references == 0) retireLocked(entry) to entry.producer else null to null
        }
        // The final cancelled waiter also owns completion of its cancelled
        // producer. A native late response is reclaimed before this returns.
        try { lease?.release() } finally { producer?.join() }
    }

    /** Called only under mutex. Each underlying lease leaves the entry once;
     * each returned reference has PreviewLease's existing idempotent release. */
    private fun retireLocked(entry: Entry): PreviewLease? {
        entry.retired = true
        if (entries[entry.key] === entry) entries.remove(entry.key)
        entry.ready.completeExceptionally(CancellationException("文档图片已释放"))
        entry.producer.cancel()
        val lease = entry.value?.lease
        entry.value = null
        if (entry.finished && entry.references == 0 && entry.accounted) {
            reservedBytes -= entry.bytes
            entry.accounted = false
        }
        return lease
    }
}

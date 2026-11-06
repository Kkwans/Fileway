package io.github.kkwans.nasfilebrowser.player

import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** One account/source/open generation owns this store. Completed private files
 * survive decoder recreation, never media/account changes. Reservations include
 * in-flight downloads; LRU eviction cannot remove a pinned reader's file. */
class SourceSubtitleAssets(private val maxBytes: Long = 128L * 1024 * 1024) : Closeable {
    init { require(maxBytes > 0) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var closed = false
    private var charged = 0L
    private class Entry(val key: String, val limit: Long) {
        var readers = 0
        var charge = limit
        var file: File? = null
        lateinit var result: Deferred<File>
    }
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    class Lease internal constructor(val file: File, private val release: () -> Unit) : Closeable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }

    /** prepare transfers ownership of a complete temporary file. Its network
     * lease belongs to the producer, independently of any one waiting reader. */
    suspend fun acquire(key: String, limit: Long, prepare: suspend () -> File): Lease {
        require(key.isNotBlank() && limit in 1..maxBytes)
        val entry = synchronized(lock) {
            check(!closed) { "字幕播放会话已关闭" }
            val existing = entries[key]
            val value = existing ?: run {
                while (charged > maxBytes - limit) {
                    val victim = entries.values.firstOrNull { it.readers == 0 }
                        ?: error("字幕资源正在使用，暂时没有足够缓存空间")
                    remove(victim)
                }
                Entry(key, limit).also { created ->
                    entries[key] = created; charged += limit
                    created.result = scope.async(start = CoroutineStart.LAZY) {
                        var owned: File? = null
                        var transferred = false
                        try {
                            val file = prepare().also { owned = it }
                            ensureActive()
                            val size = file.length()
                            check(file.isFile && size in 1..limit) { "字幕资源未完整读取或超过上限" }
                            synchronized(lock) {
                                check(!closed && entries[key] === created) { "字幕播放会话已关闭" }
                                created.file = file
                                charged -= created.charge - size; created.charge = size
                                transferred = true
                            }
                            file
                        } finally { if (!transferred) owned?.delete() }
                    }
                }
            }
            check(value.limit == limit) { "字幕资源读取预算已变化" }
            value.readers++
            value
        }
        try {
            val file = entry.result.await()
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                check(!closed && entries[key] === entry && file.isFile && file.length() == entry.charge) { "字幕资源已失效，请重新选择" }
            }
            return Lease(file) { release(entry) }
        } catch (failure: Throwable) { release(entry); throw failure }
    }

    private fun release(entry: Entry) = synchronized(lock) {
        check(entry.readers > 0)
        entry.readers--
        if (entry.readers == 0 && (closed || !entry.result.isCompleted || entry.result.isCancelled ||
                entry.file?.let { !it.isFile || it.length() != entry.charge } == true)) remove(entry)
    }

    /** Called under lock. Active readers keep ownership even after close. */
    private fun remove(entry: Entry) {
        check(entry.readers == 0)
        if (entries[entry.key] !== entry) return
        entries.remove(entry.key); charged -= entry.charge
        entry.result.cancel(); entry.file?.delete(); entry.file = null
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            entries.values.filter { it.readers == 0 }.toList().forEach(::remove)
            scope.cancel()
        }
    }
}

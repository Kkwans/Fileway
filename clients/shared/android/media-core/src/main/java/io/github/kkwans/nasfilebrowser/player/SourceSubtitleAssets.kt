package io.github.kkwans.nasfilebrowser.player

import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One account/source/open generation owns this store. Completed private files
 * survive decoder recreation, never media/account changes. Reservations include
 * in-flight downloads; LRU eviction cannot remove a pinned reader's file. */
class SourceSubtitleAssets(private val maxBytes: Long = 128L * 1024 * 1024) : Closeable {
    init { require(maxBytes > 0) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var closed = false
    private var charged = 0L
    data class Progress(val file: File? = null, val bytes: Long = 0, val complete: Boolean = false,
        val failure: Throwable? = null)
    private class Entry(val key: String, val limit: Long) {
        var readers = 0
        var charge = limit
        var file: File? = null
        lateinit var result: Deferred<File>
        val progress = MutableStateFlow(Progress())
    }
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    class Lease internal constructor(val file: File, val progress: StateFlow<Progress>, private val release: () -> Unit) : Closeable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }

    /** start transfers the private file to this pool. advance publishes only
     * bytes already written, while successful prepare return commits the file. */
    class Writer internal constructor(private val begin: (File) -> Unit, private val update: (Long) -> Unit) {
        fun start(file: File) = begin(file)
        fun advance(bytes: Long) = update(bytes)
    }

    /** prepare transfers ownership of a complete temporary file. Its network
     * lease belongs to the producer, independently of any one waiting reader. */
    suspend fun acquire(key: String, limit: Long, prepare: suspend () -> File): Lease {
        return acquireGrowing(key, limit) { prepare() }.let { lease ->
            try {
                val terminal = lease.progress.first { it.complete || it.failure != null }
                terminal.failure?.let { throw it }
                lease
            } catch (failure: Throwable) { lease.close(); throw failure }
        }
    }

    /** Readers may consume the bounded growing file, but must observe failure
     * and cannot treat a prefix as a committed asset. Any new renderer can join
     * from byte zero without coupling the producer to an old selection. */
    suspend fun acquireGrowing(key: String, limit: Long, prepare: suspend (Writer) -> File): Lease {
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
                            val writer = Writer(begin = { file ->
                                synchronized(lock) {
                                    check(!closed && entries[key] === created && created.file == null && file.isFile)
                                    created.file = file; transferred = true
                                    created.progress.value = Progress(file)
                                }
                            }, update = { bytes ->
                                synchronized(lock) {
                                    val file = checkNotNull(created.file)
                                    check(!closed && entries[key] === created && bytes in created.progress.value.bytes..limit && file.length() >= bytes)
                                    created.progress.value = Progress(file, bytes)
                                }
                            })
                            val file = prepare(writer).also { owned = it }
                            ensureActive()
                            val size = file.length()
                            check(file.isFile && size in 1..limit) { "字幕资源未完整读取或超过上限" }
                            synchronized(lock) {
                                check(!closed && entries[key] === created) { "字幕播放会话已关闭" }
                                check(created.file == null || created.file == file) { "字幕资源文件已变化" }
                                created.file = file
                                charged -= created.charge - size; created.charge = size
                                transferred = true
                                created.progress.value = Progress(file, size, complete = true)
                            }
                            file
                        } finally { if (!transferred || owned != created.file) owned?.delete() }
                    }
                    created.result.invokeOnCompletion { failure ->
                        if (failure != null) created.progress.update { if (it.complete) it else it.copy(failure = failure) }
                    }
                }
            }
            check(value.limit == limit) { "字幕资源读取预算已变化" }
            value.readers++
            value
        }
        try {
            entry.result.start()
            val ready = entry.progress.first { it.file != null || it.failure != null }
            ready.failure?.let { throw it }
            val file = checkNotNull(ready.file)
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                check(!closed && entries[key] === entry && file.isFile && (!ready.complete || file.length() == entry.charge)) { "字幕资源已失效，请重新选择" }
            }
            return Lease(file, entry.progress.asStateFlow()) { release(entry) }
        } catch (failure: Throwable) { release(entry); throw failure }
    }

    private fun release(entry: Entry) = synchronized(lock) {
        check(entry.readers > 0)
        entry.readers--
        if (entry.readers == 0 && (closed || !entry.progress.value.complete || entry.progress.value.failure != null ||
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

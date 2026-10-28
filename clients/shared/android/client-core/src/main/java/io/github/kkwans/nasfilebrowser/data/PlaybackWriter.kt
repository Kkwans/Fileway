package io.github.kkwans.nasfilebrowser.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Local commits are ordered independently of slow, coalesced remote writes. */
class PlaybackWriter(private val history: PlaybackHistory, private val api: NasSession,
    private val result: (PlaybackSnapshot?, Throwable?) -> Unit = { _, _ -> }) {
    private data class Update(val snapshot: PlaybackSnapshot, val stored: CompletableDeferred<Unit>)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val local = Channel<Update>(Channel.UNLIMITED)
    private val remote = Channel<PlaybackSnapshot>(Channel.CONFLATED)
    private val localWorker = scope.launch {
        for (update in local) {
            try {
                history.save(update.snapshot)
                result(update.snapshot, null)
                remote.trySend(update.snapshot)
                update.stored.complete(Unit)
            } catch (error: Exception) { update.stored.completeExceptionally(error); result(null, error) }
        }
    }
    private val remoteWorker = scope.launch {
        for (snapshot in remote) {
            try { result(history.synchronize(api, snapshot), null) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { result(snapshot.copy(sync = ProgressSync.PENDING), error) }
        }
    }
    fun submit(snapshot: PlaybackSnapshot): Deferred<Unit> {
        val stored = CompletableDeferred<Unit>()
        if (local.trySend(Update(snapshot, stored)).isFailure) stored.completeExceptionally(IllegalStateException("播放保存已关闭"))
        return stored
    }
    suspend fun close() {
        local.close()
        localWorker.join()
        remote.close()
        // Local data is already durable. A weak link cannot hold switching
        // indefinitely; unfinished remote writes remain visibly pending.
        if (withTimeoutOrNull(2000) { remoteWorker.join(); true } != true) remoteWorker.cancelAndJoin()
        scope.cancel()
    }
}

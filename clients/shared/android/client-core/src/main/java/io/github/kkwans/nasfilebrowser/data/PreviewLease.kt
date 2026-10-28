package io.github.kkwans.nasfilebrowser.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Token-free localhost capability belonging to one immutable session. */
class PreviewLease constructor(val url: String, val scope: String, private val revoke: suspend () -> Unit) {
    private val released = AtomicBoolean()
    suspend fun release() {
        if (released.compareAndSet(false, true)) withContext(NonCancellable) { revoke() }
    }
}

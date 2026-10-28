package io.github.kkwans.nasfilebrowser.core

import kotlinx.coroutines.CancellableContinuation

/** Only resource-creating responses have a compensating operation. An ordinary
 * request, including an acknowledged write, must never be replayed or undone. */
data class NativeResourceCleanup(
    val operation: String,
    val argument: String,
    val value: String,
    val session: String? = null,
)

/** Transfers ownership at the actual cancellable continuation boundary. The
 * callback also covers cancellation after resume but before caller dispatch.
 * enqueueCleanup must only enqueue work: it can run on the caller's UI thread. */
fun CancellableContinuation<Any?>.resumeNativeResult(
    operation: String,
    session: String,
    result: Any?,
    enqueueCleanup: (NativeResourceCleanup) -> Unit,
) {
    val resource = result as? String
    val cleanup = if (resource == null) null else when (operation) {
        "open" -> NativeResourceCleanup("close_session", "session", resource)
        "lease", "asset", "preview", "upload_lease", "hls_lease" -> NativeResourceCleanup("revoke", "url", resource)
        "search_start" -> NativeResourceCleanup("search_cancel", "search", resource, session)
        "command_start" -> NativeResourceCleanup("command_cancel", "commandHandle", resource, session)
        else -> null
    }
    // This is the single cleanup source for an undelivered response. Once the
    // caller receives it, only that caller owns and releases the resource.
    resume(result) { _, _, _ -> cleanup?.let(enqueueCleanup) }
}

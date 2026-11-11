package io.github.kkwans.fileway.tv

import io.github.kkwans.nasfilebrowser.data.ScopedSubtitleEntry
import io.github.kkwans.nasfilebrowser.data.ScopedSubtitlePage
import io.github.kkwans.nasfilebrowser.data.ScopedSubtitleResources
import io.github.kkwans.nasfilebrowser.data.ServiceException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Holds only the server's latest page. Positions are private to the TV. */
internal class TvScopedSubtitleStream(
    private val resources: ScopedSubtitleResources,
    private val wirePath: String,
    private val cursorMode: Boolean,
) {
    internal class Changed : Exception()
    internal class ScanIncomplete : Exception()
    internal data class Page(val entries: List<ScopedSubtitleEntry>, val next: Int?)

    private val mutex = Mutex()
    private var page: ScopedSubtitlePage? = null
    private var input: String? = null
    private var start = 0
    private var emptyContinuation = false

    suspend fun verify(entry: ScopedSubtitleEntry) = resources.verify(entry)
    suspend fun close() = resources.close(wirePath)

    suspend fun read(position: Int): Page = mutex.withLock {
        var scannedEmpty = 0
        var fresh = false
        while (true) {
            var current = page
            if (current == null) {
                if (position != 0) throw Changed()
                current = fetch(null)
                page = current
                input = null
                start = position
                fresh = true
            }
            val end = start + current.entries.size
            if (position < start || position > end + if (emptyContinuation) 1 else 0) throw Changed()

            // Replaying the last input makes the server recheck the current account,
            // directory and file versions before cached rows are sent again.
            if (position < end || current.entries.isEmpty() && position == start && emptyContinuation) {
                if (!fresh && fetch(input) != current) throw Changed()
                if (current.entries.isNotEmpty()) {
                    return@withLock Page(current.entries.drop(position - start), end.takeIf { current.nextCursor != null })
                }
                return@withLock Page(emptyList(), start + 1)
            }

            val next = current.nextCursor ?: return@withLock Page(emptyList(), null)
            if (current.entries.isEmpty()) {
                if (emptyContinuation) {
                    if (position != start + 1) throw Changed()
                } else if (++scannedEmpty >= MAX_EMPTY_WINDOWS) {
                    if (!cursorMode) throw ScanIncomplete()
                    emptyContinuation = true
                    return@withLock Page(emptyList(), start + 1)
                }
            }
            current = fetch(next)
            page = current
            input = next
            start = position
            emptyContinuation = false
            fresh = true
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private suspend fun fetch(cursor: String?): ScopedSubtitlePage {
        var retries = 0
        while (true) {
            try {
                return resources.page(wirePath, cursor)
            } catch (failure: ServiceException) {
                if (failure.status != 429 || retries >= 2) throw failure
                delay(150L shl retries++)
            }
        }
    }

    private companion object { const val MAX_EMPTY_WINDOWS = 8 }
}

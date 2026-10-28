package io.github.kkwans.nasfilebrowser.player

/** Convert presentation-local timestamps without changing the original media identity. */
data class SourceTimeline(val durationMs: Long, val offsetMs: Long = 0, val segmentDurationMs: Long = durationMs) {
    init { require(durationMs >= 0 && offsetMs in 0..durationMs && segmentDurationMs >= 0) }
    private val end get() = offsetMs + minOf(segmentDurationMs, durationMs - offsetMs)
    fun toSource(localMs: Long): Long = offsetMs + localMs.coerceIn(0, end - offsetMs)
    fun toLocal(sourceMs: Long): Long? {
        if (sourceMs < offsetMs || sourceMs > end || sourceMs == end && end != durationMs) return null
        return sourceMs - offsetMs
    }
}

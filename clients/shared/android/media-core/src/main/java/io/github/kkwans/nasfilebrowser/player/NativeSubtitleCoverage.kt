package io.github.kkwans.nasfilebrowser.player

/** ASS events are retained for one media generation. Buffered video proves only
 * the prefix read continuously from the beginning, not a hole crossed by seek.
 * This is input coverage, independent of track selection and actual glyphs. */
internal class NativeSubtitleCoverage(startMs: Long) {
    private var continuous = startMs <= 0
    private var throughMs = if (continuous) 0L else -1L
    private var failed = false
    @Synchronized fun buffered(through: Long) {
        if (continuous && !failed) throughMs = maxOf(throughMs, through)
    }
    @Synchronized fun seek(targetMs: Long) {
        if (targetMs > throughMs) continuous = false
        // Once there is a hole, a later read from zero does not instantly fill
        // it. The existing complete prefix remains usable until media close.
    }
    @Synchronized fun invalidate() { failed = true; continuous = false; throughMs = -1 }
    @Synchronized fun covers(positionMs: Long) = !failed && positionMs in 0..throughMs
}

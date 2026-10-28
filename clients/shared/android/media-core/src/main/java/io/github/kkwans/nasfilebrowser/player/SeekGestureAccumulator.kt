package io.github.kkwans.nasfilebrowser.player

/** A burst advances the requested target, even when the native clock still reports the old position. */
class SeekGestureAccumulator {
    private var lastTime = Long.MIN_VALUE
    private var target = 0L

    fun next(position: Long, duration: Long, delta: Long, now: Long): Long {
        val continuing = lastTime != Long.MIN_VALUE && now >= lastTime && now - lastTime <= 1_000
        val base = if (continuing) target else position
        target = (base + delta).coerceIn(0, duration.coerceAtLeast(0))
        lastTime = now
        return target
    }
    fun reset() { lastTime = Long.MIN_VALUE }
}

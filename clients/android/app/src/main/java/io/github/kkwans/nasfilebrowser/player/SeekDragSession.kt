package io.github.kkwans.nasfilebrowser.player

import kotlin.math.roundToLong

/** A picture drag previews against one clock snapshot and commits at most once.
 * Short drags allow fine adjustment; longer drags accelerate across the movie.
 * Uses the power-curve approach of VLC Android, scaled to this media duration.
 */
internal class SeekDragSession(val generation: Long, positionMs: Long, val durationMs: Long) {
    init { require(durationMs > 0) }
    val startMs = positionMs.coerceIn(0, durationMs)
    var targetMs: Long = startMs
        private set
    private var finished = false

    fun preview(displacementFraction: Float): Long {
        if (finished || !displacementFraction.isFinite()) return targetMs
        val fraction = displacementFraction.coerceIn(-1f, 1f).toDouble()
        val distance = kotlin.math.abs(fraction)
        val curve = .02 * distance + .98 * distance * distance * distance
        val span = maxOf(minOf(durationMs, 120_000L), durationMs / 2).toDouble()
        val delta = (span * curve * kotlin.math.sign(fraction)).roundToLong()
        targetMs = if (delta >= 0) startMs + minOf(delta, durationMs - startMs)
            else startMs - minOf(-delta, startMs)
        return targetMs
    }

    fun finish(currentGeneration: Long, released: Boolean, seekable: Boolean): Long? {
        if (finished) return null
        finished = true
        return targetMs.takeIf { released && seekable && generation == currentGeneration && it != startMs }
    }
}

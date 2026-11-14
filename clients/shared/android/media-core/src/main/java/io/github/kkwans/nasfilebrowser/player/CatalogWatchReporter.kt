package io.github.kkwans.nasfilebrowser.player

import android.os.SystemClock
import io.github.kkwans.nasfilebrowser.data.CatalogPlaybackLease

/** Samples the confirmed position of one original-source player generation. */
class CatalogWatchReporter(
    private val lease: CatalogPlaybackLease,
    private val generation: Long,
) {
    private var lastSavedAt = SystemClock.elapsedRealtime()
    private var completionSubmitted = false

    fun capture(state: PlayerState, immediate: Boolean = false) {
        if (completionSubmitted || state.mediaGeneration != generation || !state.canSavePosition ||
            (!state.firstFrameRendered && state.audioRenderedBuffers == 0)) return
        val now = SystemClock.elapsedRealtime()
        if (!immediate && (!state.playing || now - lastSavedAt < 10_000)) return
        lease.recordProgress(state.positionMs, state.durationMs, state.naturalEnd)
        completionSubmitted = state.naturalEnd && state.durationMs > 0 && state.durationMs - state.positionMs <= 2_000
        lastSavedAt = now
    }
}

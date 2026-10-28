package io.github.kkwans.nasfilebrowser.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/** Observe the actual startup/rebuffer gate without changing Media3's loading decisions.
 * All product media URLs are HTTP broker leases, so streaming defaults apply. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackLoadControl private constructor(private val delegate: DefaultLoadControl) : LoadControl by delegate {
    constructor(bufferBytes: Int? = null) : this(if (bufferBytes == null) DefaultLoadControl() else
        DefaultLoadControl.Builder().setTargetBufferBytes(bufferBytes).setPrioritizeTimeOverSizeThresholds(false).build())
    // Kotlin delegation does not forward Java interface default methods. Forward
    // the pinned Media3 PlayerId-aware API explicitly; deprecated fallbacks throw.
    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)
    override fun onTracksSelected(parameters: LoadControl.Parameters, groups: TrackGroupArray, selections: Array<ExoTrackSelection?>) = delegate.onTracksSelected(parameters, groups, selections)
    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId) = delegate.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId) = delegate.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinueLoading(parameters: LoadControl.Parameters) = delegate.shouldContinueLoading(parameters)
    override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, periodId: MediaPeriodId, bufferedDurationUs: Long) = delegate.shouldContinuePreloading(playerId, timeline, periodId, bufferedDurationUs)
    @Volatile var percent: Int = 0
        private set
    fun resetProgress() { percent = 0 }
    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val ready = delegate.shouldStartPlayback(parameters)
        var requiredUs = 1000L * if (parameters.rebuffering) DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS else DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) requiredUs = minOf(requiredUs, parameters.targetLiveOffsetUs / 2)
        val bufferedPlayoutUs = parameters.bufferedDurationUs / parameters.playbackSpeed.toDouble()
        percent = if (ready || requiredUs <= 0) 100 else (100 * bufferedPlayoutUs / requiredUs).toInt().coerceIn(0, 99)
        return ready
    }
}

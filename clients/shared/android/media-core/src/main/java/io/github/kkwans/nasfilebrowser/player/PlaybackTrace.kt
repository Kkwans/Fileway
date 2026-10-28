package io.github.kkwans.nasfilebrowser.player

enum class PlaybackTraceAction {
    ENGINE_READY, PLAYER_READY, OPEN_REQUEST, MEDIA_SET, ATTACH, DETACH,
    PLAY_REQUEST, PAUSE_REQUEST, STOP_REQUEST, STOP_RETURNED, RELEASE,
    OPENING, BUFFERING, PLAYING, PAUSED, FIRST_CLOCK, VIDEO_OUTPUT, ENDED, ERROR,
    SEEK_REQUEST, SEEK_ACCEPTED, SEEK_REACHED, RATE_REQUEST, RATE_REPORTED,
    AUDIO_REQUEST, AUDIO_ACCEPTED, AUDIO_SELECTED,
    SUBTITLE_REQUEST, SUBTITLE_ACCEPTED, SUBTITLE_SELECTED, EXTERNAL_SUBTITLE_REQUEST,
}

/** Only enum/numeric data is accepted: paths, URLs, credentials and titles cannot enter the trace. */
data class PlaybackTraceEntry(
    val sequence: Long, val openAttempt: Long, val elapsedMs: Long,
    val action: PlaybackTraceAction, val value: Double? = null,
)

/** Bounded command/event observations, not proof of frame/audio output or native callback identity. */
class PlaybackTrace(private val clock: () -> Long, private val enabled: Boolean = true, private val capacity: Int = 128) {
    private val origin = clock()
    private val entries = ArrayDeque<PlaybackTraceEntry>()
    private var sequence = 0L
    private var openAttempt = 0L
    private var elapsed = 0L

    init { require(capacity in 1..512) }

    @Synchronized fun beginOpen(positionMs: Long) {
        if (!enabled) return
        openAttempt++
        record(PlaybackTraceAction.OPEN_REQUEST, positionMs.toDouble())
    }

    @Synchronized fun record(action: PlaybackTraceAction, value: Double? = null) {
        if (!enabled) return
        elapsed = maxOf(elapsed, clock() - origin)
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(PlaybackTraceEntry(++sequence, openAttempt, elapsed, action, value?.takeIf { it.isFinite() }))
    }

    @Synchronized fun snapshot(): List<PlaybackTraceEntry> = entries.toList()
}

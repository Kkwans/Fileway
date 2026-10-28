package io.github.kkwans.nasfilebrowser.player

/** User intent survives asynchronous engine events and view recreation. */
class PlaybackSession {
    var generation = 0L
        private set
    var active = false
        private set
    var wantsPlay = false
        private set
    var preferredRate = 1f
        private set

    fun open(autoplay: Boolean): Long { generation++; active = true; wantsPlay = autoplay; return generation }
    fun accepts(epoch: Long): Boolean = active && generation == epoch
    fun play() { if (active) wantsPlay = true }
    fun pause() { wantsPlay = false }
    fun stop() { generation++; active = false; wantsPlay = false }
    fun selectRate(value: Float): Boolean {
        if (!value.isFinite() || value !in .1f..5f) return false
        preferredRate = value
        return true
    }
}

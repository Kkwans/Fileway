package io.github.kkwans.fileway.tv

/** Identity of one physical press, including presses from different remotes. */
internal data class TvConfirmPress(val keyCode: Int, val downTime: Long, val deviceId: Int)

/**
 * A consumed file-key down may release after focus or the window changes.
 * Keep that release out of the next TV Material button; a later independent
 * press is tracked separately and remains usable.
 */
internal class TvConfirmKeyGuard {
    private val pending = LinkedHashMap<TvConfirmPress, Boolean>()

    fun begin(press: TvConfirmPress) {
        if (press !in pending && pending.size >= 64) pending.remove(pending.keys.first())
        pending[press] = false
    }

    fun suppress(press: TvConfirmPress) {
        if (press in pending) pending[press] = true
    }

    fun finish(press: TvConfirmPress) { pending.remove(press) }

    fun consume(press: TvConfirmPress, release: Boolean, repeat: Boolean): Boolean {
        if (pending[press] != true || (!release && !repeat)) return false
        if (release) pending.remove(press)
        return true
    }

    fun clear() = pending.clear()
}

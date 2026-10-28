package io.github.kkwans.nasfilebrowser.player

/** Lower priority entries leave first; within a priority keep the playhead neighbourhood. */
fun <T> subtitleCacheVictim(
    entries: Sequence<T>,
    positionUs: Long,
    current: (T) -> Boolean,
    selected: (T) -> Boolean,
    timeUs: (T) -> Long,
): T? = entries.minWithOrNull(
    compareBy<T> { if (current(it)) 1 else 0 }
        .thenBy { if (current(it) && selected(it)) 1 else 0 }
        .thenByDescending { kotlin.math.abs(timeUs(it).toDouble() - positionUs.toDouble()) },
)

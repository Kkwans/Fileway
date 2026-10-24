package io.github.kkwans.nasfilebrowser.data

/** Effective throughput; incomplete historical timing must never inflate this value. */
internal fun transferAverageBytesPerSecond(bytes: Long, activeMillis: Long, completeHistory: Boolean): Long? =
    if (!completeHistory || bytes < 0 || activeMillis <= 0) null
    else (bytes.toDouble() * 1000.0 / activeMillis).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()

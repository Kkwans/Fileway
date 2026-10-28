package io.github.kkwans.nasfilebrowser.player

/** Independent from visual theme; subtitle clock precision is never reduced. */
data class MediaResourcePolicy(val bufferBytes: Int? = null, val subtitleCacheBytes: Long = 32L * 1024 * 1024, val uiTickMs: Long = 200, val assBitmapCacheMB: Int = 64, val assEventBytes: Long = 64L * 1024 * 1024) {
    init { require(bufferBytes == null || bufferBytes > 0); require(subtitleCacheBytes > 0 && uiTickMs in 100..1000 && assBitmapCacheMB > 0 && assEventBytes > 0) }
    companion object {
        val Phone = MediaResourcePolicy()
        val LowMemoryTv = MediaResourcePolicy(24 * 1024 * 1024, 16L * 1024 * 1024, 500, 16, 16L * 1024 * 1024)
    }
}

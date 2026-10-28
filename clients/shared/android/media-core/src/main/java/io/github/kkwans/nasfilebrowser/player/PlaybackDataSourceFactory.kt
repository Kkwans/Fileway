package io.github.kkwans.nasfilebrowser.player

import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/** Optional source policy without a dependency on a client's download module. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
interface PlaybackDataSourceFactory : DataSource.Factory {
    fun wrapExtractor(extractor: androidx.media3.extractor.Extractor): androidx.media3.extractor.Extractor = extractor
    fun errorPolicy(): LoadErrorHandlingPolicy
}

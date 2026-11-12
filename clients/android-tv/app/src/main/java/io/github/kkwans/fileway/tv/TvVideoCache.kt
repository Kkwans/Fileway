package io.github.kkwans.fileway.tv

import android.content.Context
import androidx.media3.datasource.DataSource
import io.github.kkwans.nasfilebrowser.player.PlaybackStreamCache
import io.github.kkwans.nasfilebrowser.player.PlaybackCacheLimits
import kotlinx.coroutines.CoroutineScope

internal typealias TvCachedRange=io.github.kkwans.nasfilebrowser.player.CachedPlaybackRange
internal typealias TvCacheState=io.github.kkwans.nasfilebrowser.player.PlaybackCacheState

internal val TvCacheState.feedback: String?
    get() = error ?: "正在清理视频缓存…".takeIf { releasing || clearing }

/** Retains the TV settings/directory; shared media owns the bounded writer,
 * source generations and reader-aware cleanup. */
internal class TvVideoCache(context:Context,scope:CoroutineScope) {
    private val delegate=PlaybackStreamCache(context,scope,directoryName="video-streams",preferencesName="tv-video-cache",
        limits=PlaybackCacheLimits(minimumBytes=MIN_MIB * MIB, maximumBytes=MAX_MIB * MIB))
    val state=delegate.state
    suspend fun prepare(owner:String,identity:String,resource:String,url:String,fileBytes:Long,job:String?,offset:Long,duration:Long,
        ready:()->Boolean,playbackPosition:()->Long):DataSource.Factory =
        delegate.prepare(owner,identity,resource,url,fileBytes,job,offset,duration,ready,playbackPosition)
    fun seek(positionMs:Long)=delegate.seek(positionMs)
    fun inspect()=delegate.inspect()
    suspend fun setLimitConfirmed(mib:Long) = delegate.setLimitConfirmed(mib.also { require(it in MIN_MIB..MAX_MIB) } * MIB)
    suspend fun clear() = delegate.clear()
    suspend fun resumePrefetch() = delegate.resumePrefetch()
    fun background()=delegate.background()
    fun foreground()=delegate.foreground()
    suspend fun stop()=delegate.stop()
    fun release()=delegate.release()

    companion object {
        const val MIB = 1_048_576L
        const val MIN_MIB = 500L
        const val MAX_MIB = 20_480L
    }
}

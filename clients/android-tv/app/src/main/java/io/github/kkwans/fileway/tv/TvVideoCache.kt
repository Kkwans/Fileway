package io.github.kkwans.fileway.tv

import android.content.Context
import androidx.media3.datasource.DataSource
import io.github.kkwans.nasfilebrowser.player.PlaybackStreamCache
import kotlinx.coroutines.CoroutineScope

internal typealias TvCachedRange=io.github.kkwans.nasfilebrowser.player.CachedPlaybackRange
internal typealias TvCacheState=io.github.kkwans.nasfilebrowser.player.PlaybackCacheState

/** Retains the TV settings/directory; shared media owns the bounded writer,
 * source generations and reader-aware cleanup. */
internal class TvVideoCache(context:Context,scope:CoroutineScope) {
    private val delegate=PlaybackStreamCache(context,scope,directoryName="video-streams",preferencesName="tv-video-cache")
    val state=delegate.state
    suspend fun prepare(owner:String,identity:String,resource:String,url:String,fileBytes:Long,job:String?,offset:Long,duration:Long,
        ready:()->Boolean,playbackPosition:()->Long):DataSource.Factory =
        delegate.prepare(owner,identity,resource,url,fileBytes,job,offset,duration,ready,playbackPosition)
    fun seek(positionMs:Long)=delegate.seek(positionMs)
    fun inspect()=delegate.inspect()
    fun setLimit(bytes:Long)=delegate.setLimit(bytes)
    fun background()=delegate.background()
    fun foreground()=delegate.foreground()
    suspend fun stop()=delegate.stop()
    fun release()=delegate.release()
}

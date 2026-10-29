package io.github.kkwans.fileway.tv

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.ParsingLoadable
import androidx.media3.extractor.*
import io.github.kkwans.nasfilebrowser.player.PlaybackDataSourceFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class TvCachedRange(val startMs:Long,val endMs:Long)
internal data class TvCacheState(val limitBytes:Long=2L*1024*1024*1024,val usedBytes:Long=0,val bytesPerSecond:Long=0,
    val ranges:List<TvCachedRange> = emptyList(),val error:String?=null)

/** Disk budget and RAM load control are independent. One cache, one prefetcher.
 * Native keys bind server/account/source identity; compatibility keys additionally
 * bind the immutable transcode job. Manifests retain their lease-specific URL. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class TvVideoCache(context:Context,private val scope:CoroutineScope) {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("tv-video-cache",0)
    private val mutable=MutableStateFlow(TvCacheState(limitBytes=prefs.getLong("limit",2L*1024*1024*1024)))
    val state=mutable.asStateFlow()
    private val root=File(app.cacheDir,"video-streams")
    private val initLock=Mutex()
    private val workerLock=Mutex()
    @Volatile private var cache:SimpleCache?=null
    private var database:StandaloneDatabaseProvider?=null
    private val evictor=TvLruEvictor(mutable.value.limitBytes)
    private var worker:Job?=null
    @Volatile private var current:Reading?=null
    @Volatile private var cancelDownload:(()->Unit)?=null
    private var revision=0L
    private var foreground=true
    private val networkBytes=AtomicLong()
    private val meter=object:TransferListener {
        override fun onTransferInitializing(source:DataSource,spec:DataSpec,isNetwork:Boolean){}
        override fun onTransferStart(source:DataSource,spec:DataSpec,isNetwork:Boolean){}
        override fun onBytesTransferred(source:DataSource,spec:DataSpec,isNetwork:Boolean,bytes:Int){if(isNetwork)networkBytes.addAndGet(bytes.toLong())}
        override fun onTransferEnd(source:DataSource,spec:DataSpec,isNetwork:Boolean){}
    }
    private data class Segment(val key:String,val position:Long,val length:Long,val start:Long,val end:Long)
    private class Reading(val serial:Long,val url:String,val key:String,val hls:Boolean,val fileBytes:Long,val offset:Long,
        val duration:Long,val ready:()->Boolean,val playbackPosition:()->Long) {
        val cursor=AtomicLong(0);val seek=AtomicReference<SeekMap?>();@Volatile var segments=emptyList<Segment>()
        lateinit var factory:CacheDataSource.Factory
        lateinit var network:DataSource.Factory
    }
    private suspend fun storage():SimpleCache=initLock.withLock {
        cache?:withContext(Dispatchers.IO) {
            root.mkdirs()
            val provider=StandaloneDatabaseProvider(app)
            try {SimpleCache(root,evictor,provider).also{it.checkInitialization();evictor.resize(it,mutable.value.limitBytes);cache=it;database=provider}}
            catch(error:Exception){provider.close();throw error}
        }
    }
    suspend fun prepare(owner:String,identity:String,resource:String,url:String,fileBytes:Long,job:String?,offset:Long,duration:Long,ready:()->Boolean,playbackPosition:()->Long):DataSource.Factory {
        stop()
        val base="$owner\u0000$identity\u0000$resource\u0000${job?:"native"}"
        val key=MessageDigest.getInstance("SHA-256").digest(base.toByteArray()).joinToString(""){"%02x".format(it)}
        val network=DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(15_000).setTransferListener(meter)
        val disk=try {storage()} catch(error:Exception) {
            mutable.update{it.copy(error="视频缓存不可用：${error.message}",bytesPerSecond=0,ranges=emptyList())};return network
        }
        val reading=Reading(++revision,url,key,job!=null,fileBytes,offset,duration,ready,playbackPosition)
        fun cacheFactory(flags:Int)=CacheDataSource.Factory().setCache(disk).setUpstreamDataSourceFactory(network)
            .setCacheKeyFactory{spec->cacheKey(reading,spec.uri)}
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(disk).setFragmentSize(2L*1024*1024))
            .setFlags(flags)
        // A prefetch writer can hold a hole across a large read-ahead window.
        // Foreground seek/playback must read upstream rather than wait for that
        // writer. Background readers still coordinate their writes with cache.
        val factory=cacheFactory(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        evictor.protect(disk)
        reading.factory=cacheFactory(CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        reading.network=network;current=reading
        mutable.update{it.copy(bytesPerSecond=0,ranges=emptyList(),error=null)}
        start(reading)
        return object:PlaybackDataSourceFactory {
            override fun errorPolicy()=DefaultLoadErrorHandlingPolicy()
            override fun createDataSource():DataSource {
                return object:DataSource {
                    private var delegate:DataSource?=null
                    private var position=0L
                    private val listeners=mutableListOf<TransferListener>()
                    override fun addTransferListener(listener:TransferListener){listeners+=listener;delegate?.addTransferListener(listener)}
                    override fun open(spec:DataSpec):Long {
                        position=spec.position
                        // A streamable HLS manifest changes while FFmpeg writes.
                        // Always fetch it through the authenticated broker lease.
                        val source=if(reading.hls&&spec.uri.lastPathSegment?.endsWith(".m3u8")==true)network.createDataSource() else factory.createDataSource()
                        delegate=source;listeners.forEach(source::addTransferListener)
                        return source.open(spec)
                    }
                    override fun read(buffer:ByteArray,offset:Int,length:Int):Int=requireNotNull(delegate).read(buffer,offset,length).also{count->
                        if(count>0&&!reading.hls){position+=count;reading.cursor.set(position)}
                    }
                    override fun getUri()=delegate?.uri
                    override fun getResponseHeaders()=delegate?.responseHeaders?:emptyMap()
                    override fun close(){delegate?.close();delegate=null}
                }
            }
            override fun wrapExtractor(extractor:Extractor):Extractor=object:Extractor by extractor {
                override fun init(output:ExtractorOutput) {extractor.init(object:ExtractorOutput by output {
                    override fun seekMap(map:SeekMap){reading.seek.set(map);output.seekMap(map)}
                })}
            }
        }
    }
    private fun cacheKey(reading:Reading,uri:Uri):String=when {
        !reading.hls->reading.key
        uri.lastPathSegment?.endsWith(".m3u8")==true->reading.key+":manifest:"+uri.toString()
        else->reading.key+":"+uri.lastPathSegment.orEmpty()
    }
    private fun start(reading:Reading) {
        if(!foreground)return
        worker=scope.launch(Dispatchers.IO) {
            val disk=storage()
            var oldBytes=networkBytes.get();var oldTime=SystemClock.elapsedRealtime()
            val statistics=launch {
                while(isActive && current===reading) {
                    delay(1000)
                    val now=SystemClock.elapsedRealtime();val bytes=networkBytes.get()
                    try {
                        val intervals=if(reading.hls)hlsRanges(disk,reading)else nativeRanges(disk,reading)
                        mutable.update{it.copy(usedBytes=disk.cacheSpace,bytesPerSecond=((bytes-oldBytes)*1000/(now-oldTime).coerceAtLeast(1)).coerceAtLeast(0),ranges=intervals)}
                    } catch(error:Exception){if(error is CancellationException)throw error;mutable.update{it.copy(error="缓存状态暂不可用")}}
                    oldBytes=bytes;oldTime=now
                }
            }
            try {
                while(isActive && current===reading && !reading.ready())delay(250)
                if(reading.hls) {
                    // The server uses bounded VOD windows. Download the current
                    // one ahead; controller preparation handles the next window.
                    var finalPlaylist:HlsMediaPlaylist?=null
                    while(isActive && current===reading) {
                        val loaded=finalPlaylist?:ParsingLoadable.load(reading.network.createDataSource(),HlsPlaylistParser(),Uri.parse(reading.url),C.DATA_TYPE_MANIFEST)
                        check(loaded is HlsMediaPlaylist){"Expected a bounded Fileway media playlist"}
                        val playlist=loaded
                        if(playlist.hasEndTag)finalPlaylist=playlist
                        val available=StatFs(root.absolutePath).availableBytes+disk.cacheSpace-128L*1024*1024
                        if(available<32L*1024*1024){mutable.update{it.copy(error="存储空间不足，预缓冲已暂停")};delay(3000);continue}
                        val effective=minOf(mutable.value.limitBytes,available)
                        evictor.resize(disk,effective)
                        var ahead=0L
                        val protected=linkedSetOf<String>()
                        reading.segments=playlist.segments.map{segment->
                            val uri=Uri.parse(URI(reading.url).resolve(segment.url).toASCIIString())
                            Segment(cacheKey(reading,uri),segment.byteRangeOffset,segment.byteRangeLength,
                                reading.offset+segment.relativeStartTimeUs/1000,reading.offset+(segment.relativeStartTimeUs+segment.durationUs)/1000)
                        }
                        for((index,segment) in playlist.segments.withIndex()) {
                            ensureActive()
                            val uri=Uri.parse(URI(reading.url).resolve(segment.url).toASCIIString())
                            val spec=DataSpec.Builder().setUri(uri).setPosition(segment.byteRangeOffset)
                            if(segment.byteRangeLength>0)spec.setLength(segment.byteRangeLength)
                            val metadata=reading.segments[index]
                            if(metadata.end<=reading.offset+reading.playbackPosition())continue
                            if(ahead>=effective*3/4)break
                            protected+=metadata.key
                            evictor.protect(disk,keys=protected.toSet())
                            val length=if(metadata.length>0)metadata.length else ContentMetadata.getContentLength(disk.getContentMetadata(metadata.key))
                            if(length>0&&disk.isCached(metadata.key,metadata.position,length)){ahead+=length;continue}
                            val writer=CacheWriter(reading.factory.createDataSource(),spec.build(),ByteArray(64*1024),null)
                            cancelDownload=writer::cancel
                            try{runInterruptible{writer.cache()}}finally{cancelDownload=null}
                            ahead+=ContentMetadata.getContentLength(disk.getContentMetadata(metadata.key)).coerceAtLeast(0)
                        }
                        delay(1000)
                    }
                } else {
                    var failures=0
                    while(isActive && current===reading) {
                        val remaining=StatFs(root.absolutePath).availableBytes+disk.cacheSpace-128L*1024*1024
                        if(remaining<32L*1024*1024){mutable.update{it.copy(error="存储空间不足，预缓冲已暂停")};delay(3000);continue}
                        val effective=minOf(mutable.value.limitBytes,remaining)
                        evictor.resize(disk,effective)
                        val start=reading.cursor.get().coerceAtLeast(0)
                        val length=minOf(effective*3/4,(reading.fileBytes-start).coerceAtLeast(0))
                        if(length>0) {
                            evictor.protect(disk,reading.key,start,start+length)
                            val writer=CacheWriter(reading.factory.createDataSource(),DataSpec.Builder().setUri(reading.url).setPosition(start).setLength(length).build(),ByteArray(64*1024),null)
                            cancelDownload=writer::cancel
                            try {runInterruptible{writer.cache()};failures=0;mutable.update{it.copy(error=null)}}
                            catch(error:Exception){if(error is CancellationException)throw error;failures++;mutable.update{it.copy(error="预缓冲暂不可用")};delay((failures.coerceAtMost(5)*1000).toLong())}
                            finally{cancelDownload=null}
                        }
                        delay(1000)
                    }
                }
            } catch(error:Exception) {if(error is CancellationException)throw error;if(current===reading)mutable.update{it.copy(error="预缓冲暂不可用")}}
            finally{statistics.cancel();if(current===reading)mutable.update{it.copy(bytesPerSecond=0)}}
        }
    }
    private fun hlsRanges(disk:SimpleCache,reading:Reading)=reading.segments.mapNotNull{segment->
        val length=if(segment.length!=C.LENGTH_UNSET.toLong())segment.length else ContentMetadata.getContentLength(disk.getContentMetadata(segment.key))
        if(length>0 && disk.isCached(segment.key,segment.position,length))TvCachedRange(segment.start,segment.end)else null
    }
    // Use the extractor's real byte/time seek map. Never pretend a VBR file's
    // byte percentage is a time percentage; unknown maps show decoder buffer only.
    private fun nativeRanges(disk:SimpleCache,reading:Reading):List<TvCachedRange> {
        val map=reading.seek.get()?.takeIf{it.isSeekable&&it.durationUs>0}?:return emptyList()
        if(reading.fileBytes>0&&disk.isCached(reading.key,0,reading.fileBytes))return listOf(TvCachedRange(reading.offset,reading.offset+reading.duration))
        val spans=disk.getCachedSpans(reading.key)
        val bytes=mutableListOf<Pair<Long,Long>>()
        for(span in spans) {
            val previous=bytes.lastOrNull()
            if(previous!=null&&previous.second>=span.position)bytes[bytes.lastIndex]=previous.first to maxOf(previous.second,span.position+span.length)
            else bytes+=span.position to span.position+span.length
        }
        fun timeAt(byte:Long):Long {
            var low=0L;var high=map.durationUs
            repeat(32){if(high-low>1000){val mid=(low+high)/2;if(map.getSeekPoints(mid).first.position<byte)low=mid else high=mid}}
            return map.getSeekPoints(high).first.timeUs/1000
        }
        return bytes.mapNotNull{(start,end)->val from=timeAt(start);val to=timeAt(end);if(to>from)TvCachedRange(reading.offset+from,reading.offset+to)else null}
    }
    fun seek(positionMs:Long) {
        val reading=current?:return
        if(!reading.hls)reading.seek.get()?.takeIf{it.isSeekable}?.let{reading.cursor.set(it.getSeekPoints(positionMs.coerceAtLeast(0)*1000).first.position)}
        scope.launch {workerLock.withLock {
            if(current!==reading)return@withLock
            cancelDownload?.invoke();worker?.cancelAndJoin();cancelDownload=null;start(reading)
        }}
    }
    fun inspect(){scope.launch(Dispatchers.IO){try{val disk=storage();mutable.update{it.copy(usedBytes=disk.cacheSpace)}}catch(error:Exception){if(error is CancellationException)throw error;mutable.update{it.copy(error="视频缓存暂不可用")}}}}
    fun setLimit(bytes:Long) {
        require(bytes in 64L*1024*1024..64L*1024*1024*1024)
        prefs.edit().putLong("limit",bytes).apply();mutable.update{it.copy(limitBytes=bytes)}
        scope.launch(Dispatchers.IO){try{cache?.let{evictor.resize(it,bytes)}}catch(error:Exception){if(error is CancellationException)throw error;mutable.update{it.copy(error="缓存容量已保存，旧缓存清理暂未完成")}}}
    }
    fun background(){foreground=false;cancelDownload?.invoke();worker?.cancel();mutable.update{it.copy(bytesPerSecond=0)}}
    fun foreground(){foreground=true;scope.launch{workerLock.withLock{current?.let{if(worker?.isActive!=true){worker?.join();start(it)}}}}}
    suspend fun stop()=workerLock.withLock {cancelDownload?.invoke();worker?.cancelAndJoin();cancelDownload=null;worker=null;current=null;cache?.let{evictor.protect(it)};mutable.update{it.copy(bytesPerSecond=0,ranges=emptyList())}}
    fun release(){background();CoroutineScope(Dispatchers.IO).launch{worker?.join();cache?.release();database?.close()}}
}

/** LRU is updated under SimpleCache's monitor. Capacity changes use that same
 * monitor, avoiding both index races and cache/evictor lock inversion. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class TvLruEvictor(private var limit:Long):CacheEvictor {
    private val spans=TreeSet<CacheSpan>(compareBy<CacheSpan>{it.lastTouchTimestamp}.thenBy{it.key}.thenBy{it.position})
    private var bytes=0L
    private var activeKey:String?=null
    private var activeStart=0L
    private var activeEnd=0L
    private var activeKeys:Set<String> = emptySet()
    override fun requiresCacheSpanTouches()=true
    override fun onCacheInitialized(){}
    override fun onStartFile(cache:Cache,key:String,position:Long,length:Long){if(length!=C.LENGTH_UNSET.toLong())evict(cache,length)}
    override fun onSpanAdded(cache:Cache,span:CacheSpan){if(spans.add(span))bytes+=span.length;evict(cache,0)}
    override fun onSpanRemoved(cache:Cache,span:CacheSpan){if(spans.remove(span))bytes-=span.length}
    override fun onSpanTouched(cache:Cache,old:CacheSpan,new:CacheSpan){onSpanRemoved(cache,old);onSpanAdded(cache,new)}
    private fun evict(cache:Cache,incoming:Long) {
        while(bytes+incoming>limit&&spans.isNotEmpty()) {
            // Playback touches old bytes after prefetch, making simple LRU evict
            // unread upcoming data first. Prefer played/out-of-window spans;
            // use LRU within eligible data, and retain a hard capacity fallback.
            val candidate=spans.firstOrNull{span->span.key !in activeKeys && !(span.key==activeKey&&span.position<activeEnd&&span.position+span.length>activeStart)}?:spans.first()
            cache.removeSpan(candidate)
        }
    }
    fun protect(cache:Cache,key:String?=null,start:Long=0,end:Long=0,keys:Set<String> = emptySet())=synchronized(cache){activeKey=key;activeStart=start;activeEnd=end;activeKeys=keys}
    fun resize(cache:Cache,value:Long)=synchronized(cache){limit=value;evict(cache,0)}
}

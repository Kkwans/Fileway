package io.github.kkwans.nasfilebrowser.player

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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

data class PlaybackCacheLimits(
    val defaultBytes:Long=2L*1024*1024*1024,
    val minimumBytes:Long=64L*1024*1024,
    val maximumBytes:Long=64L*1024*1024*1024,
    val chunkBytes:Long=8L*1024*1024,
    val fragmentBytes:Long=2L*1024*1024,
    val reserveFreeBytes:Long=128L*1024*1024,
    val readGuardBytes:Long=64L*1024,
    val retention: PlaybackCacheRetention? = null,
) {
    init {
        require(minimumBytes>0&&defaultBytes in minimumBytes..maximumBytes)
        require(chunkBytes>0&&fragmentBytes in 1..minimumBytes&&readGuardBytes>0&&reserveFreeBytes>=0)
    }
}

data class PlaybackCacheRetention(val rewindMinutes: Int, val exitMinutes: Int) {
    init { require(rewindMinutes in 0..30 && exitMinutes in 0..1440) }
}

data class PlaybackCacheState(val limitBytes:Long,val usedBytes:Long=0,val bytesPerSecond:Long=0,
    val ranges:List<CachedPlaybackRange> = emptyList(),val error:String?=null,val releasing:Boolean=false,
    val clearing:Boolean=false,val prefetchPaused:Boolean=false,val retention: PlaybackCacheRetention? = null)

/** One bounded writer, independent foreground reads, and per-open-generation
 * ownership. Nothing here owns a player, account, subtitle or UI component. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackStreamCache(context:Context,private val scope:CoroutineScope,
    directoryName:String="playback-streams",preferencesName:String="playback-cache",
    private val limits:PlaybackCacheLimits=PlaybackCacheLimits(),
) {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences(preferencesName,0)
    private val retentionPrefs = app.getSharedPreferences("$preferencesName-retired", 0)
    private var expiryJob: Job? = null
    private val root=File(app.cacheDir,directoryName)
    private val mutable=MutableStateFlow(PlaybackCacheState(prefs.getLong("limit",limits.defaultBytes).coerceIn(limits.minimumBytes,limits.maximumBytes),
        retention = limits.retention?.let {
            PlaybackCacheRetention(prefs.getInt("rewindMinutes", it.rewindMinutes).coerceIn(0,30),
                prefs.getInt("exitMinutes", it.exitMinutes).coerceIn(0,1440))
        }))
    val state=mutable.asStateFlow()
    // The settings floor limits user input, while low disk space only limits the effective cache.
    private val minimumOperationalBytes = minOf(limits.minimumBytes, 64L * 1024 * 1024)
    private val evictor=PlaybackCacheEvictor(mutable.value.limitBytes)
    private val initLock=Mutex();private val workerLock=Mutex();private val cleanupLock=Mutex()
    private val cleanupScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val sequence=AtomicLong()
    private val lifecycle=Any()
    private var stopFence=0L
    private var releaseFence: Long? = null
    private var lastOpened=0L
    private val readings=ConcurrentHashMap<Long,Reading>()
    @Volatile private var disk:SimpleCache?=null
    private var database:StandaloneDatabaseProvider?=null
    @Volatile private var current:Reading?=null
    @Volatile private var displayed:Reading?=null
    @Volatile private var foreground=true
    @Volatile private var closing=false
    private var worker:Job?=null
    @Volatile private var pausedMedia: Media? = null
    private var telemetry: Job? = null
    private var transferSample: TransferSample? = null
    @Volatile private var cancelWriter:(()->Unit)?=null
    @Volatile private var cancelNetwork:(()->Unit)?=null
    private val downloading=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(1,30,TimeUnit.SECONDS)).build().also{
            it.dispatcher.maxRequests=1;it.dispatcher.maxRequestsPerHost=1
        }
    private data class Media(val owner: String, val identity: String, val resource: String)
    private data class TransferSample(val reading: Reading, val bytes: Long, val timeMs: Long)
    private class Reading(val serial:Long,val media:Media,val url:String,val prefix:String,val hls:Boolean,@Volatile var size:Long,
        val offset:Long,val duration:Long,val ready:()->Boolean,val position:()->Long) {
        val nativeKey=prefix+"file"
        val seek=AtomicReference<SeekMap?>()
        val opened=AtomicBoolean();val cleaning=AtomicBoolean()
        val disposed=CompletableDeferred<Unit>()
        val networkBytes=AtomicLong()
        val pendingSeek=AtomicReference<Pair<Long,Long>?>()
        var activatedAtMs = 0L
        var retiredAtMs = 0L
        @Volatile var retired=false
        var readers=0
        var writers=0
        @Volatile var parts:List<Part> = emptyList()
        lateinit var cache:SimpleCache
        lateinit var foreground:DataSource.Factory
        lateinit var network:DataSource.Factory
        lateinit var writing:CacheDataSource.Factory
        lateinit var meter:TransferListener
    }
    private class Part(val uri:Uri,val key:String,val position:Long,@Volatile var length:Long,val start:Long,val end:Long,val permanent:Boolean=false)

    init {require(directoryName.matches(Regex("[A-Za-z0-9_-]{1,100}")))}

    private suspend fun storage():SimpleCache=initLock.withLock {
        check(!closing){"Playback cache is closing"}
        disk?:withContext(Dispatchers.IO){
            check(root.isDirectory||root.mkdirs()){"Cannot create playback cache"}
            val provider=StandaloneDatabaseProvider(app)
            val created=SimpleCache(root,evictor,provider)
            try {
                created.checkInitialization()
                // Only this component's root is opened; no settings/fonts or
                // other cache directories are removed after an interrupted run.
                if (limits.retention == null) created.keys.toList().forEach(created::removeResource)
                else synchronized(lifecycle) { sweepRetainedLocked(created) }
                disk=created;database=provider;created
            }catch(failure:Exception){runCatching{created.release()};provider.close();throw failure}
        }
    }

    suspend fun prepare(owner:String,identity:String,resource:String,url:String,fileBytes:Long,job:String?,offset:Long,duration:Long,
        ready:()->Boolean,playbackPosition:()->Long):DataSource.Factory {
        check(!closing&&scope.isActive){"Playback cache is closing"}
        val cache=try{storage()}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){
            if(closing||!scope.isActive)throw CancellationException("Playback cache is closing")
            mutable.update{it.copy(error="视频缓存暂不可用，正在直接播放",bytesPerSecond=0,ranges=emptyList())}
            return DefaultHttpDataSource.Factory()
        }
        val number=sequence.incrementAndGet()
        val identitySeed="$owner\u0000$identity\u0000$resource\u0000${job?:"native"}"
        val hash=MessageDigest.getInstance("SHA-256").digest(identitySeed.toByteArray()).joinToString(""){"%02x".format(it)}
        val prefix = if (limits.retention == null) "$hash:${UUID.randomUUID()}:" else "$hash:"
        val reading=Reading(number,Media(owner,identity,resource),url,prefix,job!=null,fileBytes,offset,duration,ready,playbackPosition)
        reading.cache=cache
        val meter=object:TransferListener {
            override fun onTransferInitializing(source:DataSource,spec:DataSpec,isNetwork:Boolean){}
            override fun onTransferStart(source:DataSource,spec:DataSpec,isNetwork:Boolean){}
            override fun onBytesTransferred(source:DataSource,spec:DataSpec,isNetwork:Boolean,bytes:Int){if(isNetwork)reading.networkBytes.addAndGet(bytes.toLong())}
            override fun onTransferEnd(source:DataSource,spec:DataSpec,isNetwork:Boolean){}
        }
        reading.meter=meter
        reading.foreground=DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(15_000).setTransferListener(meter)
        fun base(upstream:DataSource.Factory)=CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory{key(reading,it.uri)}
        val playback=base(reading.foreground).setCacheWriteDataSinkFactory(null)
            .setCacheReadDataSourceFactory { pinnedFileSource(cache) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // Unused candidate factories hold no cache files or lifecycle slot.
        // Preparing a failing HDR candidate must leave the old source running.
        return object:PlaybackDataSourceFactory {
            override fun errorPolicy()=DefaultLoadErrorHandlingPolicy()
            override fun wrapExtractor(extractor:Extractor):Extractor=object:Extractor by extractor {
                override fun init(output:ExtractorOutput){extractor.init(object:ExtractorOutput by output{
                    override fun seekMap(map:SeekMap){reading.seek.set(map);output.seekMap(map)}
                })}
            }
            override fun createDataSource():DataSource=object:DataSource {
                private val token=Any();private var source:DataSource?=null
                private var at=0L;private var cacheKey="";private var registered=false
                private val listeners=mutableListOf<TransferListener>()
                override fun addTransferListener(listener:TransferListener){listeners+=listener;source?.addTransferListener(listener)}
                override fun open(spec:DataSpec):Long {
                    synchronized(lifecycle){
                        if(reading.retired||closing||reading.serial<=stopFence||reading.serial<lastOpened)throw IOException("Playback generation retired")
                        if (limits.retention != null && !reading.opened.get()) {
                            // Expired data must not be revived by reopening before the timer runs.
                            if (readings.values.none { it.prefix == reading.prefix }) sweepRetainedLocked(cache)
                            retentionPrefs.edit().putLong(reading.prefix, 0).apply()
                        }
                        synchronized(reading){reading.readers++;registered=true}
                        readings[reading.serial]=reading
                    }
                    at=spec.position;cacheKey=key(reading,spec.uri)
                    return try{
                        if(!activate(reading)){retire(reading);throw IOException("Playback generation retired")}
                        val delegate=if(reading.hls&&spec.uri.lastPathSegment?.endsWith(".m3u8")==true)reading.foreground.createDataSource() else playback.createDataSource()
                        source=delegate;listeners.forEach(delegate::addTransferListener)
                        evictor.pin(cache,token,cacheKey,at,limits.readGuardBytes)
                        try { delegate.open(spec) } finally { evictor.unpin(cache,token) }
                    }catch(failure:Exception){close();throw failure}
                }
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                    evictor.pin(cache,token,cacheKey,at,maxOf(length.toLong(),limits.readGuardBytes))
                    try { return requireNotNull(source).read(buffer,offset,length).also{if(it>0)at+=it} }
                    finally { evictor.unpin(cache,token) }
                }
                override fun getUri()=source?.uri
                override fun getResponseHeaders()=source?.responseHeaders?:emptyMap()
                override fun close(){
                    try{source?.close()}finally{
                        source=null
                        synchronized(reading){if(registered){registered=false;reading.readers--}}
                        scheduleCleanup(reading)
                    }
                }
            }
        }
    }

    private fun pinnedFileSource(cache: Cache): DataSource {
        val file = FileDataSource()
        val token = Any()
        return object : DataSource by file {
            override fun open(spec: DataSpec): Long {
                // The outer call guards span selection until the file itself is pinned.
                evictor.pinFile(cache, token, checkNotNull(spec.uri.path))
                return file.open(spec)
            }

            override fun close() {
                try { file.close() } finally { evictor.unpinFile(cache, token) }
            }
        }
    }

    private fun key(reading:Reading,uri:Uri):String=if(!reading.hls)reading.nativeKey else reading.prefix+
        MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray()).joinToString(""){"%02x".format(it)}

    private fun activate(reading:Reading):Boolean {
        synchronized(lifecycle){
            if(closing||reading.retired||reading.serial<=stopFence||reading.serial<lastOpened)return false
            if(!reading.opened.compareAndSet(false,true))return true
            reading.activatedAtMs = SystemClock.elapsedRealtime()
            lastOpened=reading.serial;displayed=reading
            releaseFence = null
            if (pausedMedia != reading.media) pausedMedia = null
            mutable.update { it.copy(releasing = false, bytesPerSecond = 0, ranges = emptyList(),
                error = null, prefetchPaused = pausedMedia != null) }
        }
        if(!reading.hls&&reading.size>0)runCatching{
            reading.cache.applyContentMetadataMutations(reading.nativeKey,ContentMetadataMutations().also{ContentMetadataMutations.setContentLength(it,reading.size)})
        }
        scope.launch(Dispatchers.IO){workerLock.withLock{
            if(closing||reading.retired||synchronized(lifecycle){reading.serial!=lastOpened||reading.serial<=stopFence}){retire(reading);return@withLock}
            stopWorker();current?.let(::retire)
            current=reading;evictor.activate(reading.cache,reading.prefix)
            startTelemetry(reading.cache)
            if(foreground)startWorker(reading)
        }}
        return true
    }
    private suspend fun stopWorker(){
        cancelNetwork?.invoke();cancelWriter?.invoke()
        worker?.cancelAndJoin();worker=null;cancelWriter=null;cancelNetwork=null
    }

    private fun startTelemetry(cache: SimpleCache) {
        if (telemetry?.isActive == true) return
        telemetry = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(500)
                workerLock.withLock {
                    val reading = displayed ?: run {
                        telemetry = null
                        return@launch
                    }
                    val sample = TransferSample(reading, reading.networkBytes.get(), SystemClock.elapsedRealtime())
                    val previous = transferSample?.takeIf { it.reading === reading }
                    transferSample = sample
                    val bytes = sample.bytes - (previous?.bytes ?: 0L)
                    val elapsed = sample.timeMs - (previous?.timeMs ?: reading.activatedAtMs)
                    refreshTelemetry(cache, reading, if (foreground) bytes * 1000 / elapsed.coerceAtLeast(1) else 0L)
                }
            }
        }
    }

    private fun refreshTelemetry(cache: SimpleCache, reading: Reading?, speed: Long? = null) {
        synchronized(lifecycle) {
            synchronized(cache) {
                val used = cache.cacheSpace
                val clearing = evictor.isClearing(cache)
                val ranges = try {
                    reading?.takeIf { displayed === it }?.let { if (it.hls) hlsRanges(it) else nativeRanges(it) }.orEmpty()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    reading?.let { setError(it, "缓存状态暂不可用") }
                    emptyList()
                }
                mutable.update {
                    val updated = it.copy(usedBytes = used, clearing = clearing)
                    if (displayed === reading) updated.copy(ranges = ranges, bytesPerSecond = speed?.coerceAtLeast(0) ?: it.bytesPerSecond) else updated
                }
            }
        }
    }

    private fun startWorker(reading:Reading){
        if (displayed !== reading || pausedMedia == reading.media || !foreground) return
        val network=CancellableHttpSources(downloading,reading.meter)
        reading.network=network
        reading.writing=CacheDataSource.Factory().setCache(reading.cache).setUpstreamDataSourceFactory(network)
            .setCacheKeyFactory{key(reading,it.uri)}
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(reading.cache).setFragmentSize(limits.fragmentBytes))
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
        cancelNetwork=network::cancel
        synchronized(reading){reading.writers++}
        worker=scope.launch(Dispatchers.IO){
            val buffer=ByteArray(64*1024)
            while(isActive&&foreground&&displayed===reading&&!reading.ready())delay(100)
            var playlist:HlsMediaPlaylist?=null;var nextPlaylistRefresh=0L;var failures=0
            while(isActive&&displayed===reading&&!reading.retired&&foreground){
                val pending=reading.pendingSeek.get()
                val position=reading.position().coerceAtLeast(0)
                if(pending!=null){
                    if(abs(position-pending.first)>1000&&SystemClock.elapsedRealtime()-pending.second<3000){delay(50);continue}
                    reading.pendingSeek.compareAndSet(pending,null)
                }
                try{
                    val available=StatFs(root.absolutePath).availableBytes+reading.cache.cacheSpace-limits.reserveFreeBytes
                    if(available<minimumOperationalBytes){setError(reading,"存储空间不足，预缓冲已暂停");delay(1000);continue}
                    val capacity=synchronized(reading.cache) {
                        minOf(mutable.value.limitBytes,available).also { evictor.resize(reading.cache,it) }
                    }
                    val wrote=if(reading.hls){
                        if(playlist==null||(playlist?.hasEndTag!=true&&SystemClock.elapsedRealtime()>=nextPlaylistRefresh)){
                            val loaded=ParsingLoadable.load(reading.network.createDataSource(),HlsPlaylistParser(),Uri.parse(reading.url),C.DATA_TYPE_MANIFEST)
                            check(loaded is HlsMediaPlaylist){"Expected bounded HLS media playlist"};playlist=loaded
                            reading.parts=parts(reading,loaded)
                            nextPlaylistRefresh=SystemClock.elapsedRealtime()+1000
                        }
                        val bounds=reading.parts.filterNot{it.permanent}.groupBy{it.key}.mapValues{(_,items)->CachedPlaybackRange(items.minOf{it.start},items.maxOf{it.end})}
                        val rewindMs = (mutable.value.retention?.rewindMinutes ?: 0) * 60_000L
                        val sourcePositionMs = add(reading.offset, position)
                        val evictBeforeMs = (sourcePositionMs - rewindMs).coerceAtLeast(0)
                        evictor.hlsWindow(reading.cache,evictBeforeMs,sourcePositionMs,bounds,reading.parts.filter{it.permanent}.map{it.key}.toSet())
                        prefetchHls(reading,position,buffer)
                    }else{
                        if(reading.size<=0){
                            reading.size=resourceLength(reading,Uri.parse(reading.url))
                            if(reading.size<=0){setError(reading,"片源长度未知，当前使用播放缓冲");delay(1000);continue}
                            reading.cache.applyContentMetadataMutations(reading.nativeKey,ContentMetadataMutations().also{ContentMetadataMutations.setContentLength(it,reading.size)})
                        }
                        val map=reading.seek.get()?.takeIf{it.isSeekable&&!it.isEstimated}
                        fun byteAt(timeMs: Long): Long = map?.getSeekPoints(timeMs.coerceAtMost(Long.MAX_VALUE / 1000) * 1000)
                            ?.first?.position?.coerceIn(0, reading.size) ?: 0L
                        val anchor=byteAt(position)
                        val rewindMs = (mutable.value.retention?.rewindMinutes ?: 0) * 60_000L
                        val evictBefore = if (rewindMs == 0L) anchor else {
                            val cutoffMs = position - rewindMs
                            if (cutoffMs > 0) byteAt(cutoffMs).coerceAtMost(anchor) else 0L
                        }
                        val end=minOf(reading.size,add(anchor,capacity))
                        evictor.nativeWindow(reading.cache,reading.nativeKey,evictBefore,anchor,end)
                        if(end>anchor)prefetch(reading,Uri.parse(reading.url),reading.nativeKey,anchor,end,buffer)else false
                    }
                    failures=0;setError(reading,null)
                    if(!wrote)delay(if(reading.hls)500 else 150)
                }catch(failure:Exception){
                    ensureActive()
                    if(generateSequence<Throwable>(failure){it.cause}.any{it is PlaybackCacheFull}){delay(150);continue}
                    failures++;setError(reading,"预缓冲暂不可用，正在重试")
                    delay(failures.coerceAtMost(5)*500L)
                }
            }
        }.also{job->job.invokeOnCompletion{synchronized(reading){reading.writers--};scheduleCleanup(reading)}}
    }

    /** CacheWriter only receives a reserved missing chunk, never the whole file. */
    private suspend fun prefetch(reading:Reading,uri:Uri,key:String,start:Long,end:Long,buffer:ByteArray):Boolean {
        var position=start
        while(position<end){
            currentCoroutineContext().ensureActive()
            val cached=reading.cache.getCachedLength(key,position,end-position)
            if(cached>0){position+=cached;continue}
            val count=evictor.beginWrite(reading.cache,key,position,minOf(-cached,limits.chunkBytes,end-position))
            if(count==0L)return false
            try{
                val spec=DataSpec.Builder().setUri(uri).setPosition(position).setLength(count).setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
                val writer=CacheWriter(reading.writing.createDataSource(),spec,buffer,null)
                cancelWriter=writer::cancel
                try{runInterruptible{writer.cache()}}finally{cancelWriter=null}
            }finally{evictor.endWrite(reading.cache)}
            return true
        }
        return false
    }
    private suspend fun prefetchHls(reading:Reading,position:Long,buffer:ByteArray):Boolean {
        for(part in reading.parts){
            if(!part.permanent&&part.end<=reading.offset+position)continue
            if(part.length<0){
                // Full unread windows do not generate repeated length probes.
                if(evictor.beginWrite(reading.cache,part.key,part.position,limits.fragmentBytes)==0L)return false
                evictor.endWrite(reading.cache)
                part.length=resourceLength(reading,part.uri)-part.position
            }
            if(part.length<=0)continue
            if(prefetch(reading,part.uri,part.key,part.position,add(part.position,part.length),buffer))return true
        }
        return false
    }
    private fun parts(reading:Reading,playlist:HlsMediaPlaylist):List<Part> {
        fun uri(path:String):Uri {
            val base=URI(reading.url);val resolved=base.resolve(path)
            check(resolved.scheme==base.scheme&&resolved.host==base.host&&resolved.port==base.port){"Cross-origin HLS resource"}
            return Uri.parse(resolved.toASCIIString())
        }
        val output=mutableListOf<Part>();val initializations=mutableSetOf<String>()
        for(segment in playlist.segments){
            segment.initializationSegment?.let{init->
                val value=uri(init.url);val key=key(reading,value)
                if(initializations.add(key)){
                    val item=Part(value,key,init.byteRangeOffset,init.byteRangeLength,0,0,true)
                    reading.parts.firstOrNull{it.key==key&&it.permanent&&it.position==item.position}?.length?.takeIf{it>=0}?.let{item.length=it}
                    output+=item
                }
            }
            val value=uri(segment.url);val key=key(reading,value)
            val item=Part(value,key,segment.byteRangeOffset,segment.byteRangeLength,reading.offset+segment.relativeStartTimeUs/1000,reading.offset+(segment.relativeStartTimeUs+segment.durationUs)/1000)
            reading.parts.firstOrNull{it.key==key&&!it.permanent&&it.position==item.position}?.length?.takeIf{it>=0}?.let{item.length=it}
            output+=item
        }
        return output
    }
    private fun resourceLength(reading:Reading,uri:Uri):Long {
        val source=reading.network.createDataSource()
        try{
            source.open(DataSpec.Builder().setUri(uri).setLength(1).build())
            fun header(name:String)=source.responseHeaders.entries.firstOrNull{it.key.equals(name,true)}?.value?.firstOrNull()
            header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.takeIf{it>0}?.let{return it}
            if((source as?HttpDataSource)?.responseCode==200)header("Content-Length")?.toLongOrNull()?.takeIf{it>0}?.let{return it}
        }finally{source.close()}
        val fallback=reading.network.createDataSource()
        return try{fallback.open(DataSpec.Builder().setUri(uri).build())}finally{fallback.close()}
    }

    private fun hlsRanges(reading:Reading)=merge(reading.parts.filter{!it.permanent&&it.length>0&&reading.cache.isCached(it.key,it.position,it.length)}.map{CachedPlaybackRange(it.start,it.end)})
    private fun nativeRanges(reading:Reading):List<CachedPlaybackRange> {
        val duration=reading.duration.takeIf{it>0}?:reading.seek.get()?.durationUs?.takeIf{it>0}?.div(1000)?:0
        if(duration>0&&reading.size>0&&reading.cache.isCached(reading.nativeKey,0,reading.size))return listOf(CachedPlaybackRange(reading.offset,reading.offset+duration))
        val map=reading.seek.get()?.takeIf{it.isSeekable&&!it.isEstimated&&it.durationUs>0}?:return emptyList()
        val intervals=mutableListOf<Pair<Long,Long>>()
        for(span in reading.cache.getCachedSpans(reading.nativeKey)){
            val previous=intervals.lastOrNull()
            if(previous!=null&&previous.second>=span.position)intervals[intervals.lastIndex]=previous.first to maxOf(previous.second,add(span.position,span.length))
            else intervals+=span.position to add(span.position,span.length)
        }
        fun timeAt(byte:Long,roundUp:Boolean):Long{
            var low=0L;var high=map.durationUs
            while(low<high){val middle=low+(high-low)/2;if(map.getSeekPoints(middle).first.position<byte)low=middle+1 else high=middle}
            val point=map.getSeekPoints(low).first
            // Start at a contained seek point; end before a partially cached
            // cluster. Never claim the next cluster is already buffered.
            if(roundUp&&point.position<byte)return map.durationUs/1000
            return (if(roundUp||point.position==byte)point else map.getSeekPoints((low-1).coerceAtLeast(0)).first).timeUs/1000
        }
        return merge(intervals.mapNotNull{(from,to)->val a=timeAt(from,true);val b=timeAt(to,false);if(b>a)CachedPlaybackRange(reading.offset+a,reading.offset+b)else null})
    }
    private fun merge(values:List<CachedPlaybackRange>):List<CachedPlaybackRange>{
        val result=mutableListOf<CachedPlaybackRange>()
        for(value in values.sortedBy{it.startMs}){
            val previous=result.lastOrNull()
            if(previous!=null&&previous.endMs>=value.startMs)result[result.lastIndex]=previous.copy(endMs=maxOf(previous.endMs,value.endMs))else result+=value
        }
        return result
    }
    private fun setError(reading: Reading, message: String?) = synchronized(lifecycle) {
        mutable.update { if (displayed === reading) it.copy(error = message) else it }
    }

    fun seek(positionMs:Long){
        val reading=displayed?:return
        reading.pendingSeek.set(positionMs.coerceAtLeast(0) to SystemClock.elapsedRealtime())
        scope.launch(Dispatchers.IO){workerLock.withLock{if(current===reading&&displayed===reading&&!reading.retired){stopWorker();if(foreground)startWorker(reading)}}}
    }
    fun setLimit(bytes: Long) {
        require(bytes in limits.minimumBytes..limits.maximumBytes)
        scope.launch {
            try { setLimitConfirmed(bytes) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = "无法保存视频缓存上限") } }
        }
    }

    suspend fun setLimitConfirmed(bytes: Long) = withContext(Dispatchers.IO) {
        require(bytes in limits.minimumBytes..limits.maximumBytes)
        workerLock.withLock {
            check(!closing) { "Playback cache is closing" }
            val cache = disk
            val capacity = cache?.let {
                val available = StatFs(root.absolutePath).availableBytes + it.cacheSpace - limits.reserveFreeBytes
                minOf(bytes, available.coerceAtLeast(minimumOperationalBytes))
            }
            check(prefs.edit().putLong("limit", bytes).commit()) { "无法保存视频缓存上限" }
            if (cache == null) mutable.update { it.copy(limitBytes = bytes) }
            else {
                synchronized(cache) {
                    evictor.resize(cache, checkNotNull(capacity))
                    mutable.update { it.copy(limitBytes = bytes, usedBytes = cache.cacheSpace) }
                }
                refreshTelemetry(cache, displayed)
            }
        }
    }

    suspend fun setRetentionConfirmed(rewindMinutes: Int? = null, exitMinutes: Int? = null) = withContext(Dispatchers.IO) {
        workerLock.withLock {
            check(!closing)
            val current = checkNotNull(mutable.value.retention)
            val next = PlaybackCacheRetention(rewindMinutes ?: current.rewindMinutes, exitMinutes ?: current.exitMinutes)
            check(prefs.edit().putInt("rewindMinutes", next.rewindMinutes).putInt("exitMinutes", next.exitMinutes).commit()) {
                "无法保存缓存保留时间"
            }
            mutable.update { it.copy(retention = next) }
            synchronized(lifecycle) { disk?.let(::sweepRetainedLocked) }
            disk?.let { refreshTelemetry(it, displayed) }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        workerLock.withLock {
            val cache = storage()
            withContext(NonCancellable) {
                synchronized(lifecycle) {
                    pausedMedia = displayed?.media
                    mutable.update { it.copy(prefetchPaused = pausedMedia != null) }
                }
                stopWorker()
                evictor.clearUnpinned(cache)
                val reading = displayed
                transferSample = reading?.let { TransferSample(it, it.networkBytes.get(), SystemClock.elapsedRealtime()) }
                refreshTelemetry(cache, reading, 0)
            }
        }
    }

    suspend fun resumePrefetch() = withContext(Dispatchers.IO) {
        workerLock.withLock {
            check(!closing) { "Playback cache is closing" }
            synchronized(lifecycle) {
                pausedMedia = null
                mutable.update { it.copy(prefetchPaused = false) }
            }
            val reading = current
            if (reading != null && reading === displayed && foreground && !reading.retired && worker?.isActive != true) {
                stopWorker()
                startWorker(reading)
            }
        }
    }
    fun background() {
        foreground = false
        mutable.update { it.copy(bytesPerSecond = 0) }
        scope.launch(Dispatchers.IO) { workerLock.withLock { if (!foreground) stopWorker() } }
    }
    fun foreground(){foreground=true;scope.launch(Dispatchers.IO){workerLock.withLock{val reading=current;if(!closing&&foreground&&reading!=null&&reading===displayed&&!reading.retired&&worker?.isActive!=true){stopWorker();startWorker(reading)}}}}

    private fun retire(reading:Reading){synchronized(reading){
        if (!reading.retired) {
            reading.retired = true
            reading.retiredAtMs = System.currentTimeMillis()
        }
    };scheduleCleanup(reading)}

    /** Runs under lifecycle so open, retirement and expiry share one key owner. */
    private fun sweepRetainedLocked(cache: SimpleCache) {
        val retention = mutable.value.retention ?: return
        val now = System.currentTimeMillis()
        val grace = retention.exitMinutes * 60_000L
        val live = readings.values.mapTo(mutableSetOf()) { it.prefix }
        val present = cache.keys.mapTo(mutableSetOf()) { it.substringBefore(':') + ":" }
        val records = retentionPrefs.all
        val expired = present.filterTo(mutableSetOf()) { prefix ->
            prefix !in live && (records[prefix] as? Long)?.let { it > 0 && now - it < grace } != true
        }
        evictor.expirePrefixes(cache, expired)
        val edits = retentionPrefs.edit()
        records.keys.filter { it !in live && (it !in present || it in expired) }.forEach(edits::remove)
        edits.apply()
        expiryJob?.cancel()
        val next = present.asSequence().filter { it !in live && it !in expired }
            .mapNotNull { records[it] as? Long }.minOrNull()
        expiryJob = next?.takeUnless { closing }?.let { retiredAt ->
            cleanupScope.launch {
                delay((retiredAt + grace - now).coerceAtLeast(1L))
                synchronized(lifecycle) {
                    expiryJob = null
                    if (!closing) sweepRetainedLocked(cache)
                }
                if (!closing) refreshTelemetry(cache, displayed)
            }
        }
    }

    private fun scheduleCleanup(reading:Reading){
        val eligible=synchronized(reading){reading.retired&&reading.readers==0&&reading.writers==0}
        if(!eligible||!reading.cleaning.compareAndSet(false,true))return
        cleanupScope.launch {
            cleanupLock.withLock{
                try{
                    if (limits.retention == null) reading.cache.keys.filter{it.startsWith(reading.prefix)}.forEach(reading.cache::removeResource)
                    else synchronized(lifecycle) {
                        readings.remove(reading.serial, reading)
                        if (readings.values.none { it.prefix == reading.prefix }) {
                            retentionPrefs.edit().putLong(reading.prefix, reading.retiredAtMs).apply()
                        }
                        sweepRetainedLocked(reading.cache)
                    }
                }
                catch(_:Exception){mutable.update{it.copy(error="视频缓存清理未完成，可在设置中重试")}}
                finally{
                    synchronized(lifecycle) {
                        readings.remove(reading.serial, reading)
                        val fence = releaseFence
                        if (fence != null && readings.keys.none { it < fence }) releaseFence = null
                        synchronized(reading.cache) {
                            mutable.update { it.copy(usedBytes = reading.cache.cacheSpace, releasing = releaseFence != null,
                                clearing = evictor.isClearing(reading.cache)) }
                        }
                    }
                    reading.disposed.complete(Unit)
                }
            }
        }
    }
    suspend fun stop(){
        val (fence, retired) = workerLock.withLock {
            val batch = synchronized(lifecycle) {
                val fence = sequence.incrementAndGet()
                stopFence = fence
                displayed = null
                pausedMedia = null
                mutable.update { it.copy(bytesPerSecond = 0, ranges = emptyList(), prefetchPaused = false) }
                fence to readings.values.toList().also { it.forEach(::retire) }
            }
            stopWorker();current=null
            transferSample = null
            if (closing) { telemetry?.cancel(); telemetry = null }
            batch
        }
        // Never destroy a cache file while a player still owns its read handle.
        if (withTimeoutOrNull(1500) { retired.forEach { it.disposed.await() }; true } != true) {
            synchronized(lifecycle) {
                // A completion or a new playback may have won the timeout race.
                if (stopFence == fence && displayed == null && readings.keys.any { it < fence }) {
                    releaseFence = fence
                    mutable.update { it.copy(releasing = true) }
                }
            }
        }
        workerLock.withLock {
            disk?.let { cache ->
                synchronized(cache) { mutable.update { it.copy(usedBytes = cache.cacheSpace, clearing = evictor.isClearing(cache)) } }
            }
        }
    }
    fun inspect(){scope.launch(Dispatchers.IO){
        try {
            workerLock.withLock {
                val cache = storage()
                cleanupLock.withLock {
                    if (limits.retention != null) synchronized(lifecycle) { sweepRetainedLocked(cache) }
                    else {
                        val live = readings.values.map { it.prefix }
                        cache.keys.filter { key -> live.none { key.startsWith(it) } }.forEach(cache::removeResource)
                    }
                }
                refreshTelemetry(cache, displayed)
            }
        }
        catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){mutable.update{it.copy(error="视频缓存暂不可用")}}
    }}
    fun release(){
        synchronized(lifecycle) {
            if (closing) return
            closing = true
            expiryJob?.cancel()
        }
        background()
        cleanupScope.launch {
            stop()
            readings.values.toList().forEach{it.disposed.await()}
            workerLock.withLock {
                cleanupLock.withLock { initLock.withLock { disk?.release();disk=null;database?.close();database=null } }
            }
            downloading.connectionPool.evictAll();downloading.dispatcher.executorService.shutdown()
            cleanupScope.cancel()
        }
    }
    private fun add(start:Long,length:Long)=if(length>Long.MAX_VALUE-start)Long.MAX_VALUE else start+length
}

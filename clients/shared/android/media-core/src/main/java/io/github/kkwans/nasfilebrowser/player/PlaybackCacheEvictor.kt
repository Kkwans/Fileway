package io.github.kkwans.nasfilebrowser.player

import androidx.media3.common.C
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.io.IOException
import java.util.TreeSet

data class CachedPlaybackRange(val startMs:Long,val endMs:Long)

/** A full unread window is a pause, not a reason to discard it and download it again. */
class PlaybackCacheFull:IOException("Playback cache window is full")

/** One background writer reserves space before network IO. Foreground sources
 * are cache-read-only. Every mutation and Media3 callback uses Cache's monitor,
 * so span accounting, reservations and reader pins have one lock order. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackCacheEvictor(private var limit:Long):CacheEvictor {
    private data class NativeWindow(val key:String,val played:Long,val start:Long,val end:Long)
    private data class Pin(val key:String,val start:Long,val end:Long)
    private data class Write(val key:String,val start:Long,val end:Long,var remaining:Long)
    private val spans=TreeSet<CacheSpan>(compareBy<CacheSpan>{it.key}.thenBy{it.position})
    private val readers=mutableMapOf<Any,Pin>()
    private val openFiles = mutableMapOf<Any, String>()
    private val pendingClear = mutableSetOf<CacheSpan>()
    private var bytes=0L
    private var prefix:String?=null
    private var native:NativeWindow?=null
    private var segments:Map<String,CachedPlaybackRange> = emptyMap()
    private var permanent:Set<String> = emptySet()
    private var playedMs=0L
    private var write:Write?=null

    init {require(limit>0)}
    override fun requiresCacheSpanTouches()=false
    override fun onCacheInitialized(){}
    override fun onSpanTouched(cache:Cache,old:CacheSpan,new:CacheSpan){onSpanRemoved(cache,old);onSpanAdded(cache,new)}
    override fun onSpanRemoved(cache:Cache,span:CacheSpan){if(spans.remove(span))bytes-=span.length;pendingClear.remove(span)}
    override fun onSpanAdded(cache:Cache,span:CacheSpan){
        if(spans.add(span)) {
            bytes+=span.length
            write?.takeIf{it.key==span.key&&span.position>=it.start&&span.position<it.end}?.let{it.remaining=(it.remaining-span.length).coerceAtLeast(0)}
        }
        evict(cache,0,null)
    }
    override fun onStartFile(cache:Cache,key:String,position:Long,length:Long){
        val active=write?:throw PlaybackCacheFull()
        // CacheWriter may retry a failed bounded open with an unbounded request.
        // Never let that fallback bypass the reserved chunk or the disk budget.
        if(length==C.LENGTH_UNSET.toLong()||length<=0||key!=active.key||position<active.start||
            position>active.end||length>active.end-position||length>active.remaining||bytes+active.remaining>limit)
            throw PlaybackCacheFull()
    }

    fun activate(cache:Cache,keyPrefix:String)=synchronized(cache){
        check(write==null){"Previous cache writer has not drained"}
        require(keyPrefix.isNotEmpty())
        prefix=keyPrefix;native=null;segments=emptyMap();permanent=emptySet();playedMs=0
        evict(cache,0,null)
    }
    fun nativeWindow(cache:Cache,key:String,playedByte:Long,start:Long,end:Long)=synchronized(cache){
        require(start>=0&&end>=start&&playedByte>=0)
        native=NativeWindow(key,playedByte,start,end);segments=emptyMap();permanent=emptySet()
    }
    fun hlsWindow(cache:Cache,positionMs:Long,parts:Map<String,CachedPlaybackRange>,permanentKeys:Set<String>)=synchronized(cache){
        native=null;playedMs=positionMs.coerceAtLeast(0);segments=parts.toMap();permanent=permanentKeys.toSet()
    }
    fun pin(cache:Cache,token:Any,key:String,position:Long,length:Long)=synchronized(cache){
        require(position>=0&&length>0)
        readers[token]=Pin(key,position,add(position,length))
    }
    fun unpin(cache: Cache, token: Any) = synchronized(cache) {
        if (readers.remove(token) != null) trimAfterUnpin(cache)
    }

    fun pinFile(cache: Cache, token: Any, path: String) = synchronized(cache) {
        openFiles[token] = path
    }

    fun unpinFile(cache: Cache, token: Any) = synchronized(cache) {
        if (openFiles.remove(token) != null) trimAfterUnpin(cache)
    }

    private fun trimAfterUnpin(cache: Cache) {
        finishClear(cache)
        evict(cache,0,null)
    }

    fun clearUnpinned(cache: Cache) = synchronized(cache) {
        check(write == null)
        pendingClear.addAll(spans)
        finishClear(cache)
    }

    fun isClearing(cache: Cache) = synchronized(cache) { pendingClear.isNotEmpty() }

    private fun finishClear(cache: Cache) {
        pendingClear.filterNot(::pinned).forEach(cache::removeSpan)
    }

    /** Returns a possibly shorter final chunk. Zero performs no network request. */
    fun beginWrite(cache:Cache,key:String,position:Long,requested:Long):Long=synchronized(cache){
        check(write==null){"Only one cache writer is supported"}
        require(position>=0&&requested>0)
        if(prefix?.let{key.startsWith(it)}!=true)return@synchronized 0L
        val window=native
        if(window?.key==key&&position>=window.end)return@synchronized 0L
        val count=minOf(requested,limit,Long.MAX_VALUE-position)
        evict(cache,count,key)
        val available=minOf(count,(limit-bytes).coerceAtLeast(0))
        if(available>0)write=Write(key,position,position+available,available)
        available
    }
    fun endWrite(cache:Cache)=synchronized(cache){write=null;evict(cache,0,null)}
    fun resize(cache:Cache,value:Long)=synchronized(cache){
        require(value>0);limit=value
        native?.let{native=it.copy(end=minOf(it.end,add(it.start,value)))}
        evict(cache,0,null)
    }

    private fun pinned(span: CacheSpan) = span.file?.absolutePath?.let(openFiles::containsValue) == true ||
        readers.values.any { it.key == span.key && it.start < add(span.position, span.length) && it.end > span.position }
    private fun belongs(span:CacheSpan)=prefix?.let{span.key.startsWith(it)}==true
    private fun played(span:CacheSpan):Boolean {
        if(!belongs(span)||span.key in permanent)return false
        native?.let{return span.key==it.key&&add(span.position,span.length)<=it.played}
        return segments[span.key]?.let{it.endMs<=playedMs}==true
    }
    private fun abandoned(span:CacheSpan,incomingKey:String?):Boolean {
        if(!belongs(span)||span.key in permanent)return false
        native?.let{return span.key==it.key&&(span.position>=it.end||add(span.position,span.length)<=it.start)}
        val incoming=incomingKey?.let{segments[it]}?:return false
        return segments[span.key]?.let{it.startMs>incoming.startMs}==true
    }
    private fun evict(cache:Cache,incoming:Long,incomingKey:String?){
        while(bytes+(write?.remaining?:0)+incoming>limit) {
            val eligible=spans.filterNot(::pinned)
            val candidate=eligible.firstOrNull(::played)
                ?:eligible.firstOrNull{!belongs(it)}
                ?:eligible.filter{abandoned(it,incomingKey)}.maxByOrNull{segments[it.key]?.startMs?:it.position}
                // Explicit shrinking (or reduced free disk space) may make the
                // existing unread window larger than the new budget. Trim its
                // far end, while preserving active readers and HLS init data.
                ?:eligible.takeIf{incoming==0L&&bytes>limit}?.filter{belongs(it)&&it.key !in permanent}
                    ?.maxByOrNull{segments[it.key]?.startMs?:it.position}
                ?:break
            cache.removeSpan(candidate)
        }
    }
    private fun add(start:Long,length:Long)=if(length>Long.MAX_VALUE-start)Long.MAX_VALUE else start+length
}

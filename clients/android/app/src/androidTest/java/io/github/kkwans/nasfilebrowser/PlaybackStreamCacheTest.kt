package io.github.kkwans.nasfilebrowser

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.extractor.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Real HTTP/Range, SQLite/cache spans and cancellation, using small owned
 * budgets rather than writing gigabytes on the owner's phone. No media decoder. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackStreamCacheTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val budget=128L*1024
    private val length=2*1024*1024
    private val opened=ConcurrentHashMap.newKeySet<DataSource>()

    private suspend fun fixture(body:suspend(PlaybackStreamCache,RangeServer,AtomicLong)->Unit) {
        val name="owned-stream-cache-${System.nanoTime()}"
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val root=File(context.cacheDir,name)
        val cache=PlaybackStreamCache(context,owner,name,name,PlaybackCacheLimits(budget,16L*1024,4L*1024*1024,32L*1024,16L*1024,0,4096))
        val server=RangeServer(length);val position=AtomicLong()
        try{body(cache,server,position)}finally{
            opened.forEach{runCatching{it.close()}};opened.clear()
            try{
                cache.release();owner.cancel()
                withTimeout(10000){while(SimpleCache.isCacheFolderLocked(root))delay(20)}
                val database=StandaloneDatabaseProvider(context)
                try{SimpleCache.delete(root,database)}finally{database.close()}
            }finally{context.deleteSharedPreferences(name);server.close()}
        }
    }
    private suspend fun source(cache:PlaybackStreamCache,url:String,position:AtomicLong,account:String="owned-a"):DataSource.Factory {
        val factory=cache.prepare(account,"owned-source","/owned.bin",url,length.toLong(),null,0,length/1024L,{true},{position.get()})
        (factory as PlaybackDataSourceFactory).wrapExtractor(object:Extractor {
            override fun sniff(input:ExtractorInput)=true
            override fun init(output:ExtractorOutput){output.seekMap(object:SeekMap {
                override fun isSeekable()=true
                override fun getDurationUs()=length/1024L*1000
                override fun getSeekPoints(timeUs:Long)=SeekMap.SeekPoints(SeekPoint(timeUs,(timeUs/1000*1024).coerceIn(0,length.toLong())))
            })}
            override fun read(input:ExtractorInput,seekPosition:PositionHolder)=Extractor.RESULT_END_OF_INPUT
            override fun seek(position:Long,timeUs:Long){}
            override fun release(){}
        }).init(object:ExtractorOutput {
            override fun track(id:Int,type:Int):TrackOutput=error("No decoder tracks in cache fixture")
            override fun endTracks(){}
            override fun seekMap(map:SeekMap){}
        })
        return factory
    }
    private fun prime(factory:DataSource.Factory,url:String):DataSource {
        val data=factory.createDataSource()
        opened+=data
        try{data.open(DataSpec.Builder().setUri(url).build());assertEquals(1,data.read(ByteArray(1),0,1));return data}
        catch(failure:Throwable){data.close();opened-=data;throw failure}
    }
    private suspend fun full(cache:PlaybackStreamCache,predicate:(PlaybackCacheState)->Boolean={true})=
        withTimeout(10000){cache.state.first{it.usedBytes==budget&&predicate(it)}}

    @Test fun fullBudgetWaitsThenPlayedBytesRefillAndSeekReorders()=runBlocking {fixture {cache,server,position->
        val factory=source(cache,server.url,position);prime(factory,server.url).close()
        full(cache){(it.ranges.lastOrNull()?.endMs?:0)>=120}
        val requests=server.requests.get();delay(450)
        assertEquals("Unread full window was evicted/redownloaded",requests,server.requests.get())
        position.set(64)
        full(cache){(it.ranges.firstOrNull()?.startMs?:0)>=60&&(it.ranges.lastOrNull()?.endMs?:0)>=188}
        assertTrue(server.requests.get()>requests)
        position.set(0);cache.seek(0)
        full(cache){it.ranges.firstOrNull()?.startMs==0L&&(it.ranges.lastOrNull()?.endMs?:0)>=120}
        val cachedRequests=server.requests.get()
        prime(factory,server.url).close()
        assertEquals("Foreground ignored a cached span",cachedRequests,server.requests.get())
        cache.setLimit(budget/2)
        withTimeout(5000){cache.state.first{it.limitBytes==budget/2&&it.usedBytes==budget/2}}
        cache.stop();assertEquals(0L,cache.state.value.usedBytes)
        assertTrue(runCatching{prime(factory,server.url)}.exceptionOrNull() is IOException)
    }}

    @Test fun pendingCandidateAndOldReaderCannotDeleteNewGeneration()=runBlocking {fixture {cache,server,position->
        val first=source(cache,server.url,position);prime(first,server.url).close();full(cache)
        // Merely preparing a candidate leaves the active source/cache intact.
        source(cache,server.url,position,"unused-candidate")
        val calls=server.requests.get();prime(first,server.url).close();assertEquals(calls,server.requests.get())
        val oldReader=prime(first,server.url)
        val next=source(cache,server.url,position,"owned-b")
        val newReader=prime(next,server.url)
        oldReader.close()
        full(cache){(it.ranges.lastOrNull()?.endMs?:0)>=120}
        val before=server.requests.get();prime(next,server.url).close();assertEquals(before,server.requests.get())
        cache.stop()
        assertTrue("Live generation was deleted before reader close",cache.state.value.usedBytes>0)
        newReader.close()
        withTimeout(5000){cache.state.first{it.usedBytes==0L}}
    }}

    @Test fun blockedPrefetchDoesNotBlockForegroundOrCancellation()=runBlocking {fixture {cache,server,position->
        server.blockBounded.set(true)
        val factory=source(cache,server.url,position);prime(factory,server.url).close()
        assertTrue(server.blocked.await(5,TimeUnit.SECONDS))
        val start=android.os.SystemClock.elapsedRealtime()
        val foreground=factory.createDataSource()
        opened+=foreground
        try{
            foreground.open(DataSpec.Builder().setUri(server.url).setPosition(1024).build())
            val data=ByteArray(1);assertEquals(1,foreground.read(data,0,1));assertEquals((1024%251).toByte(),data[0])
        }finally{foreground.close()}
        assertTrue("Foreground waited for the prefetch hole",android.os.SystemClock.elapsedRealtime()-start<2000)
        position.set(100);cache.seek(100)
        val stopping=android.os.SystemClock.elapsedRealtime();cache.stop()
        assertTrue("Cancel waited for the HTTP read timeout",android.os.SystemClock.elapsedRealtime()-stopping<2000)
        assertEquals(0L,cache.state.value.usedBytes)
    }}

    @Test fun hlsCoverageUsesSourceTimelineAndKeepsUnreadSegments()=runBlocking {fixture {cache,server,position->
        val manifest=server.url.substringBeforeLast('/')+"/index.m3u8"
        val factory=cache.prepare("owned-a","hls-source","/owned.mkv",manifest,0,"owned-job",5000,3000,{true},{position.get()})
        prime(factory,manifest).close()
        full(cache){it.ranges==listOf(CachedPlaybackRange(5000,7000))}
        val before=server.requests.get();delay(600);assertEquals(before,server.requests.get())
        position.set(1000)
        full(cache){it.ranges==listOf(CachedPlaybackRange(6000,8000))}
        cache.setLimit(budget/2)
        withTimeout(5000){cache.state.first{it.usedBytes==budget/2&&it.ranges==listOf(CachedPlaybackRange(6000,7000))}}
        cache.stop();assertEquals(0L,cache.state.value.usedBytes)
    }}

    private class RangeServer(private val length:Int):AutoCloseable {
        private val listener=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val url="http://127.0.0.1:${listener.localPort}/owned.bin"
        val requests=AtomicInteger();val blockBounded=AtomicBoolean();val blocked=CountDownLatch(1)
        private val running=AtomicBoolean(true)
        private val sockets=ConcurrentHashMap.newKeySet<Socket>()
        private val workers=Executors.newFixedThreadPool(4){Thread(it,"Owned-cache-http").apply{isDaemon=true}}
        private val acceptor=Thread({while(running.get())try{val socket=listener.accept();sockets+=socket;workers.execute{serve(socket)}}catch(_:IOException){}} ,"Owned-cache-accept").apply{isDaemon=true;start()}
        private fun serve(socket:Socket){
            try{socket.use{
                socket.soTimeout=5000
                val input=socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                val request=input.readLine()?:return
                val path=request.split(' ').getOrNull(1).orEmpty()
                var range:String?=null
                while(true){val line=input.readLine()?:return;if(line.isEmpty())break;if(line.startsWith("Range:",true))range=line.substringAfter(':').trim()}
                requests.incrementAndGet()
                val playlist="#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:1.0,\nsegment-0.ts\n#EXTINF:1.0,\nsegment-1.ts\n#EXTINF:1.0,\nsegment-2.ts\n#EXT-X-ENDLIST\n".toByteArray()
                val size=if(path.endsWith(".m3u8"))playlist.size else if(path.endsWith(".ts"))64*1024 else length
                val value=range?.removePrefix("bytes=")?.split('-')
                val start=value?.getOrNull(0)?.toIntOrNull()?:0
                val end=(value?.getOrNull(1)?.toIntOrNull()?:size-1).coerceAtMost(size-1)
                val output=socket.getOutputStream()
                if(start>=size){output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray());output.flush();return}
                val status=if(range==null)"200 OK"else"206 Partial Content"
                val contentRange=if(range==null)""else"Content-Range: bytes $start-$end/$size\r\n"
                output.write("HTTP/1.1 $status\r\nContent-Length: ${end-start+1}\r\n${contentRange}Content-Type: application/octet-stream\r\nConnection: close\r\n\r\n".toByteArray());output.flush()
                // Foreground CacheDataSource can bound its otherwise open-ended
                // request using known content length. Stall only prefetch-size
                // chunks, so this fixture models a stuck background request.
                if(blockBounded.get()&&value?.getOrNull(1)?.isNotEmpty()==true&&end-start+1<=32*1024){
                    socket.soTimeout=100
                    blocked.countDown()
                    while(running.get())try{if(input.read()<0)return}catch(_:SocketTimeoutException){}
                    return
                }
                val buffer=ByteArray(8192);var at=start
                while(at<=end){val count=minOf(buffer.size,end-at+1);for(i in 0 until count)buffer[i]=if(path.endsWith(".m3u8"))playlist[at+i]else((at+i)%251).toByte();output.write(buffer,0,count);at+=count}
                output.flush()
            }}catch(_:IOException){}finally{sockets-=socket}
        }
        override fun close(){running.set(false);listener.close();sockets.forEach{runCatching{it.close()}};workers.shutdownNow();acceptor.join(1000)}
    }
}

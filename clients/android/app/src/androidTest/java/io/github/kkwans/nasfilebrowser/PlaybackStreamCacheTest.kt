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
    private val length=2L*1024*1024
    private val opened=ConcurrentHashMap.newKeySet<DataSource>()

    private suspend fun fixture(fileLength: Long = length, retention: PlaybackCacheRetention? = null,
        body:suspend(PlaybackStreamCache,RangeServer,AtomicLong)->Unit) {
        val name="owned-stream-cache-${System.nanoTime()}"
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val root=File(context.cacheDir,name)
        val cache=PlaybackStreamCache(context,owner,name,name,PlaybackCacheLimits(budget,16L*1024,4L*1024*1024,32L*1024,16L*1024,0,4096,retention))
        val server=RangeServer(fileLength);val position=AtomicLong()
        try{body(cache,server,position)}finally{
            opened.forEach{runCatching{it.close()}};opened.clear()
            try{
                cache.release();owner.cancel()
                withTimeout(10000){while(SimpleCache.isCacheFolderLocked(root))delay(20)}
                val database=StandaloneDatabaseProvider(context)
                try{SimpleCache.delete(root,database)}finally{database.close()}
            }finally{context.deleteSharedPreferences(name);context.deleteSharedPreferences("$name-retired");server.close()}
        }
    }
    private suspend fun source(cache: PlaybackStreamCache, url: String, position: AtomicLong, account: String = "owned-a",
        resource: String = "/owned.bin", fileLength: Long = length, estimated: Boolean = false): DataSource.Factory {
        val factory=cache.prepare(account,"owned-source",resource,url,fileLength,null,0,fileLength/1024L,{true},{position.get()})
        (factory as PlaybackDataSourceFactory).wrapExtractor(object:Extractor {
            override fun sniff(input:ExtractorInput)=true
            override fun init(output:ExtractorOutput){output.seekMap(object:SeekMap {
                override fun isSeekable()=true
                override fun isEstimated() = estimated
                override fun getDurationUs()=fileLength/1024L*1000
                override fun getSeekPoints(timeUs:Long)=SeekMap.SeekPoints(SeekPoint(timeUs/1000*1000,(timeUs/1000*1024).coerceIn(0,fileLength)))
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
    private fun prime(factory:DataSource.Factory,url:String,position: Long = 0):DataSource {
        val data=factory.createDataSource()
        opened+=data
        try{data.open(DataSpec.Builder().setUri(url).setPosition(position).build());assertEquals(1,data.read(ByteArray(1),0,1));return data}
        catch(failure:Throwable){data.close();opened-=data;throw failure}
    }
    private suspend fun full(cache:PlaybackStreamCache,predicate:(PlaybackCacheState)->Boolean={true})=
        withTimeout(10000){cache.state.first{it.usedBytes==budget&&predicate(it)}}

    @Test fun exitedCacheCanBeReopenedAndExpiresWhenRetentionIsDisabled()=runBlocking {
        fixture(retention=PlaybackCacheRetention(2,30)) { cache,server,position ->
            prime(source(cache,server.url,position),server.url).close()
            full(cache)
            cache.stop()
            assertEquals(budget,cache.state.value.usedBytes)
            val requests = server.requests.get()
            prime(source(cache,server.url,position),server.url).close()
            assertEquals(requests,server.requests.get())
            cache.stop()
            cache.setRetentionConfirmed(exitMinutes=0)
            assertEquals(PlaybackCacheRetention(2,0),cache.state.value.retention)
            assertEquals(0L,cache.state.value.usedBytes)
        }
    }

    @Test fun clearingRetainsActiveReaderAndResumesOnlyOnRequest()=runBlocking { fixture { cache,server,position ->
        val factory = source(cache,server.url,position)
        prime(factory,server.url).close()
        full(cache)
        val reader = prime(factory,server.url)
        val cachedRequests = server.requests.get()
        cache.clear()
        assertTrue(cache.state.value.prefetchPaused)
        assertTrue(cache.state.value.clearing)
        assertEquals(16L * 1024, cache.state.value.usedBytes)
        val cached = ByteArray(1)
        assertEquals(1, reader.read(cached, 0, 1))
        assertEquals(1.toByte(), cached[0])
        assertEquals(cachedRequests, server.requests.get())
        reader.close()
        withTimeout(8000) { cache.state.first { !it.clearing && it.usedBytes == 0L } }
        assertTrue(cache.state.value.prefetchPaused)
        val foreground = prime(factory, server.url, position = budget * 2)
        try {
            val buffer = ByteArray(8192)
            assertTrue(foreground.read(buffer, 0, buffer.size) > 0)
            val streamed = withTimeout(5000) { cache.state.first { it.bytesPerSecond > 0 } }
            assertTrue(streamed.prefetchPaused)
            assertEquals(0L, streamed.usedBytes)
            assertTrue(streamed.ranges.isEmpty())
        } finally { foreground.close() }
        cache.resumePrefetch()
        full(cache) { !it.prefetchPaused }
    }}

    @Test fun clearingStaysPausedAcrossSameFileReopenAndResumesForAnotherFile() = runBlocking { fixture { cache, server, position ->
        prime(source(cache,server.url,position),server.url).close()
        full(cache)
        cache.clear()
        val sameFile = source(cache,server.url,position)
        prime(sameFile,server.url).close()
        assertTrue(cache.state.value.prefetchPaused)
        assertNull(withTimeoutOrNull(750) { cache.state.first { !it.prefetchPaused || it.usedBytes > 0 } })
        val nextFile = source(cache,server.url,position,resource = "/next.bin")
        prime(nextFile,server.url).close()
        full(cache) { !it.prefetchPaused }
    }}

    @Test fun prefetchAndTelemetryUseLongSourceOffsetsBeyondFourGiB() = runBlocking {
        val start = 5L * 1024 * 1024 * 1024 + 32 * 1024
        val fileLength = start + length
        fixture(fileLength) { cache, server, position ->
            position.set(start / 1024)
            val factory = source(cache,server.url,position,fileLength = fileLength)
            prime(factory,server.url,start).close()
            full(cache) { it.ranges == listOf(CachedPlaybackRange(position.get(), position.get() + budget / 1024)) }
            assertTrue(server.boundedStarts.contains(start))
            val before = server.requests.get()
            val reader = prime(factory,server.url,start)
            try {
                val byte = ByteArray(1)
                assertEquals(1, reader.read(byte, 0, 1))
                assertEquals(((start + 1) % 251).toByte(), byte[0])
                assertEquals(before, server.requests.get())
            } finally { reader.close() }
        }
    }

    @Test fun estimatedSeekMapDoesNotClaimPartialTimeCoverage() = runBlocking { fixture { cache,server,position ->
        val factory = source(cache,server.url,position,estimated = true)
        prime(factory,server.url).close()
        assertTrue(full(cache).ranges.isEmpty())
    }}

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
        assertTrue("A held reader must keep release feedback visible",cache.state.value.releasing)
        assertNull("Waiting for a reader is not a cache error",cache.state.value.error)
        newReader.close()
        val released=withTimeout(5000){cache.state.first{it.usedBytes==0L}}
        assertFalse("Release feedback must disappear after the old reader closes",released.releasing)
        assertNull(released.error)
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

    private class RangeServer(private val length:Long):AutoCloseable {
        private val listener=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val url="http://127.0.0.1:${listener.localPort}/owned.bin"
        val requests=AtomicInteger();val blockBounded=AtomicBoolean();val blocked=CountDownLatch(1)
        val boundedStarts=ConcurrentHashMap.newKeySet<Long>()
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
                val size=if(path.endsWith(".m3u8"))playlist.size.toLong() else if(path.endsWith(".ts"))64L*1024 else length
                val value=range?.removePrefix("bytes=")?.split('-')
                val start=value?.getOrNull(0)?.toLongOrNull()?:0L
                val end=(value?.getOrNull(1)?.toLongOrNull()?:size-1).coerceAtMost(size-1)
                val output=socket.getOutputStream()
                if(start>=size){output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray());output.flush();return}
                val status=if(range==null)"200 OK"else"206 Partial Content"
                val contentRange=if(range==null)""else"Content-Range: bytes $start-$end/$size\r\n"
                output.write("HTTP/1.1 $status\r\nContent-Length: ${end-start+1}\r\n${contentRange}Content-Type: application/octet-stream\r\nConnection: close\r\n\r\n".toByteArray());output.flush()
                // Foreground CacheDataSource can bound its otherwise open-ended
                // request using known content length. Stall only prefetch-size
                // chunks, so this fixture models a stuck background request.
                val bounded = value?.getOrNull(1)?.isNotEmpty() == true && end-start+1<=32*1024
                if (bounded) boundedStarts += start
                if(blockBounded.get() && bounded){
                    socket.soTimeout=100
                    blocked.countDown()
                    while(running.get())try{if(input.read()<0)return}catch(_:SocketTimeoutException){}
                    return
                }
                val buffer=ByteArray(8192);var at=start
                while(at<=end){val count=minOf(buffer.size.toLong(),end-at+1).toInt();for(i in 0 until count)buffer[i]=if(path.endsWith(".m3u8"))playlist[(at+i).toInt()]else((at+i)%251).toByte();output.write(buffer,0,count);at+=count}
                output.flush()
            }}catch(_:IOException){}finally{sockets-=socket}
        }
        override fun close(){running.set(false);listener.close();sockets.forEach{runCatching{it.close()}};workers.shutdownNow();acceptor.join(1000)}
    }
}

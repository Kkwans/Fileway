package io.github.kkwans.fileway.tv

import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.player.PlaybackCacheEvictor
import io.github.kkwans.nasfilebrowser.data.LanServiceDiscovery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class TvCacheAndDiscoveryTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun ownedCache(body:(SimpleCache,PlaybackCacheEvictor,(Long)->Unit)->Unit) {
        val root=File(context.cacheDir,"owned-window-eviction-${System.nanoTime()}")
        val database=StandaloneDatabaseProvider(context);val evictor=PlaybackCacheEvictor(128*1024)
        val cache=SimpleCache(root,evictor,database)
        val data=ByteArray(512*1024){(it%251).toByte()}
        val factory=CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory{ByteArrayDataSource(data)}
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache).setFragmentSize(64*1024))
        fun fill(position:Long){
            val count=evictor.beginWrite(cache,"owned:file",position,64*1024)
            assertEquals(64*1024L,count)
            try{CacheWriter(factory.createDataSource(),DataSpec.Builder().setUri("test://owned/window").setKey("owned:file").setPosition(position).setLength(count).setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build(),ByteArray(8192),null).cache()}
            finally{evictor.endWrite(cache)}
        }
        try{evictor.activate(cache,"owned:");body(cache,evictor,::fill)}
        finally{cache.release();database.close();root.deleteRecursively()}
    }
    @Test fun fullUnreadWindowWaitsThenPlayedBytesYield()=ownedCache {cache,evictor,fill->
        evictor.nativeWindow(cache,"owned:file",0,0,256*1024)
        fill(0);fill(64*1024)
        assertEquals(128*1024L,cache.cacheSpace)
        assertEquals(0L,evictor.beginWrite(cache,"owned:file",128*1024,64*1024))
        assertTrue(cache.isCached("owned:file",0,128*1024))
        evictor.nativeWindow(cache,"owned:file",64*1024,64*1024,192*1024)
        fill(128*1024)
        assertFalse(cache.isCached("owned:file",0,64*1024))
        assertTrue(cache.isCached("owned:file",64*1024,128*1024))
    }
    @Test fun activeReaderSurvivesPlayedEviction()=ownedCache {cache,evictor,fill->
        evictor.nativeWindow(cache,"owned:file",0,0,256*1024)
        fill(0);fill(64*1024)
        val reader=Any();evictor.pin(cache,reader,"owned:file",0,1024)
        evictor.nativeWindow(cache,"owned:file",64*1024,64*1024,192*1024)
        assertEquals(0L,evictor.beginWrite(cache,"owned:file",128*1024,64*1024))
        evictor.unpin(cache,reader);fill(128*1024)
        assertTrue(cache.isCached("owned:file",64*1024,128*1024))
        assertEquals(128*1024L,cache.cacheSpace)
    }

    @Test fun rewindWindowProtectsRecentlyPlayedBytesUntilCutoffMoves()=ownedCache {cache,evictor,fill->
        val chunk = 64 * 1024L
        evictor.nativeWindow(cache,"owned:file",0,0,4*chunk)
        fill(0);fill(chunk)
        evictor.nativeWindow(cache,"owned:file",0,2*chunk,4*chunk)
        assertEquals(0L,evictor.beginWrite(cache,"owned:file",2*chunk,chunk))
        assertTrue(cache.isCached("owned:file",0,2*chunk))
        evictor.nativeWindow(cache,"owned:file",chunk,2*chunk,4*chunk)
        fill(2*chunk)
        assertFalse(cache.isCached("owned:file",0,chunk))
        assertTrue(cache.isCached("owned:file",chunk,2*chunk))
    }

    @Test fun exitedVideoYieldsUnderPressureAndExpiryPreservesPinnedFile()=ownedCache {cache,evictor,fill->
        val chunk = 64 * 1024L
        evictor.nativeWindow(cache,"owned:file",0,0,4*chunk)
        fill(0);fill(chunk)
        evictor.activate(cache,"new:")
        assertTrue(cache.isCached("owned:file",0,2*chunk))
        val pin = Any()
        evictor.pin(cache,pin,"owned:file",chunk,chunk)
        assertEquals(chunk,evictor.beginWrite(cache,"new:file",0,chunk))
        evictor.endWrite(cache)
        assertFalse(cache.isCached("owned:file",0,chunk))
        evictor.expirePrefixes(cache,setOf("owned:"))
        assertTrue(cache.isCached("owned:file",chunk,chunk))
        evictor.unpin(cache,pin)
        assertFalse(cache.isCached("owned:file",chunk,chunk))
    }

    @Suppress("DEPRECATION")
    @Test fun api28NsdDiscoversAndResolvesFilewayService()=runBlocking {
        val manager=context.getSystemService(NsdManager::class.java)
        val registered=CountDownLatch(1)
        var actualName=""
        val listener=object:NsdManager.RegistrationListener {
            override fun onServiceRegistered(info:NsdServiceInfo){actualName=info.serviceName;registered.countDown()}
            override fun onRegistrationFailed(info:NsdServiceInfo,code:Int){registered.countDown()}
            override fun onServiceUnregistered(info:NsdServiceInfo){}
            override fun onUnregistrationFailed(info:NsdServiceInfo,code:Int){}
        }
        val service=NsdServiceInfo().apply {
            serviceName="Fileway-owned-NSD-${System.nanoTime()}";serviceType=LanServiceDiscovery.TYPE;port=49151
            setAttribute("version","1");setAttribute("scheme","http");setAttribute("path","/owned")
        }
        manager.registerService(service,NsdManager.PROTOCOL_DNS_SD,listener)
        try {
            assertTrue(registered.await(10,TimeUnit.SECONDS));assertTrue(actualName.isNotEmpty())
            val result=withTimeout(20_000){LanServiceDiscovery(context).scan().first{value->value.servers.any{it.name==actualName}}}
            val found=result.servers.single{it.name==actualName}
            assertEquals(49151,Uri.parse(found.address).port)
            assertEquals("/owned",Uri.parse(found.address).path)
        } finally {manager.unregisterService(listener)}
    }
}

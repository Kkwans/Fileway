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
    @Test fun fullDiskBudgetEvictsOldSpansAndContinuesPrefetch() {
        val root=File(context.cacheDir,"owned-cache-eviction-${System.nanoTime()}")
        val database=StandaloneDatabaseProvider(context)
        val evictor=TvLruEvictor(128*1024)
        val cache=SimpleCache(root,evictor,database)
        val data=ByteArray(64*1024){(it%251).toByte()}
        val factory=CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory{ByteArrayDataSource(data)}
        fun fill(key:String) {CacheWriter(factory.createDataSource(),DataSpec.Builder().setUri("test://owned/$key").setKey(key).setLength(data.size.toLong()).build(),ByteArray(8192),null).cache()}
        try {
            fill("first");fill("second")
            assertEquals(128*1024L,cache.cacheSpace)
            // Touch the first resource; the second must now be least recent.
            android.os.SystemClock.sleep(10)
            val span=cache.startReadWrite("first",0,data.size.toLong())
            assertTrue(span.isCached)
            fill("third")
            assertFalse(cache.isCached("second",0,data.size.toLong()))
            assertTrue(cache.isCached("first",0,data.size.toLong()))
            assertTrue(cache.isCached("third",0,data.size.toLong()))
            fill("fourth")
            assertEquals(128*1024L,cache.cacheSpace)
            assertTrue("full capacity never disables subsequent prebuffer",cache.isCached("fourth",0,data.size.toLong()))
            evictor.resize(cache,64*1024)
            assertTrue(cache.cacheSpace<=64*1024)
            fill("fifth")
            assertTrue(cache.cacheSpace<=64*1024)
            assertTrue(cache.isCached("fifth",0,data.size.toLong()))
        } finally {cache.release();database.close();root.deleteRecursively()}
    }

    @Test fun activeReadAheadSurvivesRecentTouchesToPlayedBytes() {
        val root=File(context.cacheDir,"owned-window-eviction-${System.nanoTime()}")
        val database=StandaloneDatabaseProvider(context);val evictor=TvLruEvictor(128*1024)
        val cache=SimpleCache(root,evictor,database)
        val data=ByteArray(256*1024){(it%251).toByte()}
        val factory=CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory{ByteArrayDataSource(data)}
        fun fill(position:Long){CacheWriter(factory.createDataSource(),DataSpec.Builder().setUri("test://owned/window").setKey("window").setPosition(position).setLength(64*1024).build(),ByteArray(8192),null).cache()}
        try {
            fill(0);fill(64*1024)
            android.os.SystemClock.sleep(10)
            assertTrue(cache.startReadWrite("window",0,64*1024).isCached)
            evictor.protect(cache,"window",64*1024,192*1024)
            fill(128*1024)
            assertFalse("played data yields to unread upcoming data",cache.isCached("window",0,64*1024))
            assertTrue(cache.isCached("window",64*1024,128*1024))
            evictor.protect(cache,"window",128*1024,256*1024)
            fill(192*1024)
            assertTrue(cache.isCached("window",128*1024,128*1024))
            assertTrue(cache.cacheSpace<=128*1024)
        } finally {cache.release();database.close();root.deleteRecursively()}
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

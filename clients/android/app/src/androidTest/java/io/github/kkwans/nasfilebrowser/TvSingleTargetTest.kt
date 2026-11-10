package io.github.kkwans.nasfilebrowser

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.fileway.remote.*
import io.github.kkwans.nasfilebrowser.app.TvControlModel
import java.lang.reflect.InvocationTargetException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Synthetic identity/state fixtures; no TV, credentials, or network writes. */
@RunWith(AndroidJUnit4::class)
class TvSingleTargetTest {
    private fun device(byte:Int):TvDescriptor {
        val digest=ByteArray(32){byte.toByte()}
        val id=digest.take(16).joinToString(""){"%02x".format(it)}
        return TvDescriptor(id,"Test TV","tv-$id.fileway.local","192.0.2.1",443,
            Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
    }

    private fun invitation(device:TvDescriptor)=TvInvitation(device,"invitation-test-1",
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)),60)

    private fun isBlocked(error:Throwable?)=error is RemoteFailure&&error.code=="DISCONNECT_REQUIRED"

    @Suppress("UNCHECKED_CAST")
    private fun setFixtureState(client:TvRemoteClient,state:RemoteConnectionState) {
        val field=TvRemoteClient::class.java.getDeclaredField("mutable").apply{isAccessible=true}
        (field.get(client) as MutableStateFlow<RemoteConnectionState>).value=state
    }

    private suspend fun rememberFixture(client:TvRemoteClient,generation:Long,device:TvDescriptor,trusted:Boolean):Unit =
        suspendCoroutine { continuation ->
            val method=TvRemoteClient::class.java.getDeclaredMethod("rememberDevice",java.lang.Long.TYPE,
                TvDescriptor::class.java,java.lang.Boolean.TYPE,Continuation::class.java).apply{isAccessible=true}
            try {
                val result=method.invoke(client,generation,device,trusted,continuation)
                if(result!==COROUTINE_SUSPENDED)continuation.resume(Unit)
            } catch(error:InvocationTargetException) {continuation.resumeWithException(error.targetException)}
        }

    @Test fun allRetainedStatesGateEveryTransportEntryAndAllowRetry()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val client=TvRemoteClient(context,scope)
        val first=device(1);val other=device(2)
        val savedBefore=client.store.dao.devices().first()
        try {
            for(phase in listOf(RemotePhase.OFFLINE,RemotePhase.PAIRING,RemotePhase.CONNECTING,
                RemotePhase.CONNECTED,RemotePhase.FAILED,RemotePhase.BUSY)) {
                val snapshot=if(phase==RemotePhase.CONNECTED)RemoteSnapshot("fixture",1,2,3,"player",JSONObject(),JSONObject())else null
                val before=RemoteConnectionState(phase,first,snapshot,error="fixture")
                setFixtureState(client,before)
                val operations=listOf<suspend ()->Unit>(
                    {client.connect(other)}, {client.requestPairing(other)}, {client.pair(invitation(other))},
                )
                for(operation in operations) {
                    assertTrue("$phase must reject another target",isBlocked(runCatching{operation()}.exceptionOrNull()))
                    assertEquals("Rejected selection must retain connection and snapshot",before,client.state.value)
                }
                val retry=runCatching{client.requestPairing(first)}.exceptionOrNull()
                assertNotNull("The fixture deliberately fails before any IO",retry)
                assertFalse("The same target must be allowed",isBlocked(retry))
                assertEquals(RemotePhase.FAILED,client.state.value.phase)
                assertEquals(first.id,client.state.value.device?.id)
                client.disconnect()
                val next=runCatching{client.requestPairing(other)}.exceptionOrNull()
                assertNotNull(next)
                assertFalse("An explicit disconnect releases the target",isBlocked(next))
                assertEquals(other.id,client.state.value.device?.id)
                client.disconnect()
            }
            assertEquals("Control disconnect must not alter saved pairings",savedBefore,client.store.dao.devices().first())
        } finally { client.disconnect();scope.cancel() }
    }

    @Test fun simultaneousDifferentTargetsHaveOneWinner()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val client=TvRemoteClient(context,scope)
        val targets=listOf(device(3),device(4))
        try {
            repeat(40) {
                client.disconnect()
                val ready=CountDownLatch(2);val start=CountDownLatch(1)
                val attempts=targets.map{target->async(Dispatchers.Default){
                    ready.countDown()
                    check(start.await(5,TimeUnit.SECONDS))
                    runCatching{client.requestPairing(target)}.exceptionOrNull()
                }}
                assertTrue(ready.await(5,TimeUnit.SECONDS))
                start.countDown()
                val errors=withTimeout(10000){attempts.awaitAll()}
                assertEquals("Exactly one target must be rejected",1,errors.count(::isBlocked))
                assertTrue("Winning fixture must fail locally",errors.any{it!=null&&!isBlocked(it)})
                assertEquals(RemotePhase.FAILED,client.state.value.phase)
                assertEquals(targets[errors.indexOfFirst{!isBlocked(it)}].id,client.state.value.device?.id)
            }
        } finally { client.disconnect();scope.cancel() }
    }

    @Test fun staleFailureCannotReplaceNewTargetAfterDisconnect()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val client=TvRemoteClient(context,scope)
        try {
            runCatching{client.connect(device(5))}
            assertEquals(RemotePhase.FAILED,client.state.value.phase)
            val generation=TvRemoteClient::class.java.getDeclaredField("generation").apply{isAccessible=true}.getLong(client)
            client.disconnect()
            val other=device(6)
            runCatching{client.connect(other)}
            val current=client.state.value
            assertEquals(RemotePhase.FAILED,current.phase)
            assertEquals(other.id,current.device?.id)
            val stale=TvRemoteClient::class.java.getDeclaredMethod("fail",java.lang.Long.TYPE,String::class.java,
                java.lang.Boolean.TYPE,java.lang.Boolean.TYPE).apply{isAccessible=true}
            stale.invoke(client,generation,"stale callback",true,true)
            assertEquals("A callback from an obsolete generation cannot retire the new target",current,client.state.value)
        } finally {client.disconnect();scope.cancel()}
    }

    private class SqlBarrier(val matches:(String)->Boolean) {
        val reached=CompletableDeferred<Unit>()
        val release=CountDownLatch(1)
        fun pause() {
            reached.complete(Unit)
            release.await(10,TimeUnit.SECONDS)
        }
    }

    private suspend fun withPairingDatabase(
        action:suspend (TvRemoteClient,TvRemoteClient,AtomicReference<SqlBarrier?>)->Unit,
    ) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val barrier=AtomicReference<SqlBarrier?>()
        val database=Room.inMemoryDatabaseBuilder(context,RemoteDatabase::class.java)
            .setQueryCallback(RoomDatabase.QueryCallback{sql,_->
                val target=barrier.get()
                if(target!=null&&target.matches(sql)&&barrier.compareAndSet(target,null))target.pause()
            },Executor{it.run()}).build()
        val constructor=RemoteStore::class.java.getDeclaredConstructor(Context::class.java,RemoteDatabase::class.java)
        val first=TvRemoteClient(context,scope,constructor.newInstance(context,database))
        val other=TvRemoteClient(context,scope,constructor.newInstance(context,database))
        try {action(first,other,barrier)}
        finally {first.disconnect();other.disconnect();scope.cancel();database.close()}
    }

    private fun pairingInsert(sql:String)=sql.startsWith("INSERT",ignoreCase=true)&&sql.contains("paired_tvs")

    @Test fun disconnectDuringSqlRollsBackAndOtherClientKeepsNewTrust()=runBlocking {
        withPairingDatabase { client,other,barrier ->
            val staleDevice=device(9)
            val digest=ByteArray(32){if(it<16)9 else 10}
            val acknowledged=staleDevice.copy(fingerprint=Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
            val write=SqlBarrier(::pairingInsert)
            barrier.set(write)
            val stale=async {runCatching{rememberFixture(client,0,staleDevice,true)}.exceptionOrNull()}
            try {
                withTimeout(5000){write.reached.await()}
                client.disconnect()
                val confirmed=async(start=CoroutineStart.UNDISPATCHED){rememberFixture(other,0,acknowledged,true)}
                write.release.countDown()
                assertTrue(stale.await() is CancellationException)
                confirmed.await()
            } finally {write.release.countDown();stale.join()}
            val trusted=other.store.dao.device(acknowledged.id)!!
            assertTrue(trusted.trusted)
            assertEquals(acknowledged.fingerprint,trusted.fingerprint)
            rememberFixture(other,0,acknowledged.copy(name="Candidate"),false)
            assertEquals(trusted,other.store.dao.device(acknowledged.id))
            val conflicting=runCatching{rememberFixture(client,1,staleDevice,true)}.exceptionOrNull()
            assertEquals("IDENTITY_CHANGED",(conflicting as? RemoteFailure)?.code)
            assertEquals(trusted,other.store.dao.device(acknowledged.id))
        }
    }

    @Test fun callerCancellationRollsBackSqlBeforeAdmission()=runBlocking {
        withPairingDatabase { client,_,barrier ->
            val fixture=device(10)
            val write=SqlBarrier(::pairingInsert)
            barrier.set(write)
            val cancelled=launch {rememberFixture(client,0,fixture,false)}
            try {
                withTimeout(5000){write.reached.await()}
                cancelled.cancel()
            } finally {write.release.countDown();cancelled.join()}
            assertNull(client.store.dao.device(fixture.id))
        }
    }

    @Test fun admittedTrustCommitsEvenIfDisconnectPrecedesPhysicalCommit()=runBlocking {
        withPairingDatabase { client,_,barrier ->
            val fixture=device(11)
            val transactionDepth=ThreadLocal<Int>()
            val insertThread=AtomicReference<Thread?>()
            val commit=SqlBarrier{sql->
                val depth=transactionDepth.get()?:0
                when(sql){
                    "BEGIN EXCLUSIVE TRANSACTION","BEGIN IMMEDIATE TRANSACTION"->transactionDepth.set(depth+1)
                    "END TRANSACTION"->if(depth>1)transactionDepth.set(depth-1)else transactionDepth.remove()
                }
                if(pairingInsert(sql))insertThread.set(Thread.currentThread())
                // The DAO's nested transaction reports success before the final
                // selection check. Only the outer Room transaction can commit.
                sql=="TRANSACTION SUCCESSFUL"&&depth==1&&insertThread.get()===Thread.currentThread()
            }
            barrier.set(commit)
            val accepted=async {rememberFixture(client,0,fixture,true)}
            try {
                withTimeout(5000){commit.reached.await()}
                client.disconnect()
            } finally {commit.release.countDown();accepted.await()}
            assertTrue(client.store.dao.device(fixture.id)!!.trusted)
        }
    }

    private fun holdMain(action:()->Unit) {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        Handler(Looper.getMainLooper()).post{entered.countDown();release.await(5,TimeUnit.SECONDS)}
        try {assertTrue("Main thread did not reach test barrier",entered.await(5,TimeUnit.SECONDS));action()}
        finally {release.countDown()}
    }

    @Test fun modelReservesBeforeLaunchAndDisconnectFencesQueuedWork()=runBlocking {
        val application=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val store=ViewModelStore()
        val model=withContext(Dispatchers.Main){ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory.getInstance(application))[TvControlModel::class.java]}
        val first=device(7);val other=device(8)
        try {
            holdMain {
                model.requestPairing(first)
                model.requestPairing(other)
                assertEquals(RemotePhase.OFFLINE,model.connection.value.phase)
                assertEquals("请先断开当前 TV，再连接其他 TV",model.notice.value)
            }
            val failed=withTimeout(10000){model.connection.first{it.phase==RemotePhase.FAILED}}
            assertEquals(first.id,failed.device?.id)
            model.disconnect()
            holdMain {
                model.requestPairing(first)
                model.disconnect()
                model.requestPairing(other)
            }
            val next=withTimeout(10000){model.connection.first{it.phase==RemotePhase.FAILED&&it.device?.id==other.id}}
            withContext(Dispatchers.Main) { /* drain previously queued selection work */ }
            assertEquals(next,model.connection.value)
        } finally {withContext(Dispatchers.Main){model.disconnect();store.clear()}}
    }
}

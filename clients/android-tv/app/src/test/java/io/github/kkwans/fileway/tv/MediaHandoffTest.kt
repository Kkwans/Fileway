package io.github.kkwans.fileway.tv

import io.github.kkwans.fileway.remote.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MediaHandoffTest {
    private val id="0123456789abcdef0123456789abcdef"
    private val fence=HandoffFence("connection-0123456789",3,9)
    private fun item(n:Int)=HandoffItem("/电影/$n.mkv","/%E7%94%B5%E5%BD%B1/$n.mkv","$n.mkv","video",n.toLong(),"2026-11-05T00:00:00Z")
    private fun chunk(offset:Int,vararg rows:HandoffItem)=JSONObject().put("handoffId",id).put("offset",offset).put("items",JSONArray(rows.map{it.json()}))
    private fun fails(block:()->Unit) { try { block();fail("Expected rejected transaction") }catch(_:IllegalArgumentException){}catch(_:IllegalStateException){} }

    @Test fun largeQueueIsChunkedAndCommittedWithoutTruncatingAtOneHundred() {
        val rows=(0 until 600).map(::item)
        val queue=HandoffQueue.snapshot(rows,417)
        val receiver=HandoffQueueReceiver(id,fence,queue.manifest,rows[417])
        val pages=queue.chunks(id)
        assertTrue(pages.size>1)
        for(page in pages) { assertTrue(page.toString().toByteArray().size<=HANDOFF_CHUNK_BYTES);receiver.append(fence,page) }
        assertEquals(rows.map{it.resource()},receiver.commit(fence))
    }
    @Test fun partialAndGappedQueuesCannotCommit() {
        val rows=listOf(item(0),item(1));val queue=HandoffQueue.snapshot(rows,0)
        val receiver=HandoffQueueReceiver(id,fence,queue.manifest,rows[0])
        fails{receiver.append(fence,chunk(1,rows[1]))}
        receiver.append(fence,chunk(0,rows[0]));fails{receiver.commit(fence)}
        receiver.append(fence,chunk(1,rows[1]));assertEquals(2,receiver.commit(fence).size)
    }
    @Test fun matchingRetryDoesNotDuplicateButChangedRetryIsRejected() {
        val rows=listOf(item(0),item(1));val q=HandoffQueue.snapshot(rows,0)
        val receiver=HandoffQueueReceiver(id,fence,q.manifest,rows[0])
        assertEquals(1,receiver.append(fence,chunk(0,rows[0])))
        assertEquals(1,receiver.append(fence,chunk(0,rows[0])))
        fails{receiver.append(fence,chunk(0,rows[1]))}
        assertEquals(1,receiver.received)
    }
    @Test fun accountMediaOrConnectionChangesRejectOldChunks() {
        val row=item(0);val q=HandoffQueue.snapshot(listOf(row),0)
        val receiver=HandoffQueueReceiver(id,fence,q.manifest,row)
        for(other in listOf(fence.copy(sessionEpoch=4),fence.copy(mediaEpoch=10),fence.copy(connectionId="new-connection-0123456789")))fails{receiver.append(other,chunk(0,row))}
        assertEquals(0,receiver.received)
    }
    @Test fun badDigestOrSelectedItemCannotReplaceQueue() {
        val rows=listOf(item(0),item(1));val q=HandoffQueue.snapshot(rows,0)
        val receiver=HandoffQueueReceiver(id,fence,q.manifest.copy(sha256="0".repeat(64)),rows[0])
        receiver.append(fence,chunk(0,*rows.toTypedArray()));fails{receiver.commit(fence)}
        val wrong=HandoffQueueReceiver(id,fence,q.manifest,rows[1])
        wrong.append(fence,chunk(0,*rows.toTypedArray()));fails{wrong.commit(fence)}
    }
    @Test fun expiredAndCancelledTransfersCannotRevive() {
        var time=0L;val row=item(0);val q=HandoffQueue.snapshot(listOf(row),0)
        val receiver=HandoffQueueReceiver(id,fence,q.manifest,row){time}
        time=120_001;fails{receiver.append(fence,chunk(0,row))}
        val cancelled=HandoffQueueReceiver(id,fence,q.manifest,row);cancelled.cancel();fails{cancelled.append(fence,chunk(0,row))}
    }
    @Test fun wireIdentityPreservesNonUtf8AndRejectsPathInjection() {
        val row=HandoffItem("/legacy �.mkv","/legacy%20%FF.mkv","legacy �.mkv","video",1,"")
        assertEquals(row,HandoffItem.from(row.json()))
        for(wire in listOf("/../film.mkv","/foo%2Fbar.mkv","/film.mkv?token=secret","/film.mkv#x","//host/film.mkv","/x%00.mkv"))fails{row.copy(wirePath=wire)}
        fails{HandoffQueue.snapshot(listOf(row,row.copy(wirePath="/legacy%20%ff.mkv")),0)}
    }
    @Test fun queueAndChunkBudgetsRejectOversizedInputWithoutPartialAppend() {
        fails{HandoffQueue.snapshot((0..HANDOFF_QUEUE_LIMIT).map(::item),0)}
        val rows=(0 until 1200).map{item(it).copy(path="/"+"x".repeat(2000)+"/$it.mkv")}
        fails{HandoffQueue.snapshot(rows,0)}
        val q=HandoffQueue.snapshot(listOf(item(0)),0)
        val receiver=HandoffQueueReceiver(id,fence,q.manifest,item(0))
        fails{receiver.append(fence,chunk(0,item(0)).put("extra","x".repeat(HANDOFF_CHUNK_BYTES)))}
        assertEquals(0,receiver.received)
    }
    @Test fun snapshotCancellationCannotBeUndoneByAnInFlightWorker() {
        val rows=(0 until 100).map(::item);val q=HandoffQueue.snapshot(rows,0)
        val receiver=HandoffQueueReceiver(id,fence,q.manifest,rows[0]);val start=CountDownLatch(1)
        val workers=Executors.newFixedThreadPool(2)
        try {
            val append=workers.submit { start.await();try{receiver.append(fence,chunk(0,*rows.toTypedArray()))}catch(_:IllegalStateException){} }
            val cancel=workers.submit { start.await();receiver.cancel() }
            start.countDown();append.get(5,TimeUnit.SECONDS);cancel.get(5,TimeUnit.SECONDS)
            assertEquals(0,receiver.received);fails{receiver.commit(fence)}
        }finally{workers.shutdownNow()}
    }
}

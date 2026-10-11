package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.PreviewLease
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class DocumentImageResourcePoolTest {
    private val mib = 1024L * 1024
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun next() { pending.removeFirst().run() }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }
    private class Fixture {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val cleanup = CoroutineScope(SupervisorJob() + dispatcher)
        val pool = DocumentImageResourcePool(scope, cleanup)
        var loads = 0
        val revoked = mutableListOf<String>()
        fun file(name: String, size: Long, modified: String = "version-1") =
            ResourceRef("/docs/$name", "/docs/$name", name, false, "image", size, modified)
        fun loaded(file: ResourceRef): DocumentImageLease {
            loads++
            val id = "${file.wirePath}/${file.modified}/$loads"
            return DocumentImageLease(file, PreviewLease("http://127.0.0.1:1234/stream/$loads", "owned") { revoked.add(id) },
                ByteArray(file.size.toInt()))
        }
        fun request(file: ResourceRef, document: String = "owner/revision", generation: Long = pool.generation(),
            load: suspend () -> DocumentImageLease = { loaded(file) }) = scope.async {
            pool.acquire(generation, document, file, file.size, load)
        }
        fun close() {
            pool.retire(); scope.cancel(); dispatcher.drain()
            cleanup.cancel(); dispatcher.drain()
        }
    }

    @Test fun oneHundredReferencesToNineMiBShareOneAllocationAndOneUnderlyingLease(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val file = fixture.file("same.gif", 9 * mib)
            val pending = List(100) { fixture.request(file) }
            fixture.dispatcher.drain()
            val images = pending.map { it.await() }
            assertEquals(1, fixture.loads)
            assertEquals(9 * mib, images.first().data!!.size.toLong())
            images.forEach { assertSame(images.first().data, it.data) }
            assertNotSame(images[0].lease, images[1].lease)
            images.dropLast(1).forEach { it.lease.release(); it.lease.release() }
            assertTrue(fixture.revoked.isEmpty())
            images.last().lease.release(); images.last().lease.release()
            assertEquals(1, fixture.revoked.size)
        } finally { fixture.close() }
    }

    @Test fun originalByteBudgetIsReservedBeforeLoadAndSharedHitsDoNotReserveAgain(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val files = listOf(fixture.file("a.gif", 9 * mib), fixture.file("b.gif", 9 * mib), fixture.file("c.gif", 6 * mib))
            val pending = files.map { fixture.request(it) }
            fixture.dispatcher.drain()
            val images = pending.map { it.await() }
            val denied = fixture.request(fixture.file("over.gif", 1))
            val shared = fixture.request(files[0])
            fixture.dispatcher.drain()
            assertTrue(runCatching { denied.await() }.exceptionOrNull() is DocumentImageLimitException)
            assertSame(images[0].data, shared.await().data)
            assertEquals(3, fixture.loads)
            shared.await().lease.release()
            images[0].lease.release()
            val replacement = fixture.request(fixture.file("replacement.gif", 9 * mib))
            fixture.dispatcher.drain()
            replacement.await().lease.release()
            images.drop(1).forEach { it.lease.release() }
            assertEquals(4, fixture.loads)
            assertEquals(4, fixture.revoked.size)
        } finally { fixture.close() }
    }

    @Test fun cancellingOneWaiterKeepsTheOtherWaitersSharedReadAlive(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val file = fixture.file("wait.gif", 128)
            val gate = CompletableDeferred<Unit>()
            val first = fixture.request(file) { gate.await(); fixture.loaded(file) }
            val second = fixture.request(file) { error("Must join the existing producer") }
            fixture.dispatcher.drain()
            first.cancel(); fixture.dispatcher.drain()
            assertTrue(first.isCancelled)
            assertFalse(second.isCompleted)
            assertEquals(0, fixture.loads)
            gate.complete(Unit); fixture.dispatcher.drain()
            assertEquals(1, fixture.loads)
            assertTrue(fixture.revoked.isEmpty())
            second.await().lease.release()
            assertEquals(1, fixture.revoked.size)
        } finally { fixture.close() }
    }

    @Test fun atMostTwoDistinctReadsRunAndACancelledQueuedReadNeverStarts(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val gate = CompletableDeferred<Unit>()
            var active = 0; var maximum = 0; var started = 0
            val pending = List(4) { index ->
                val file = fixture.file("$index.gif", 128)
                fixture.request(file) {
                    active++; started++; maximum = maxOf(maximum, active)
                    try { gate.await(); fixture.loaded(file) } finally { active-- }
                }
            }
            fixture.dispatcher.drain()
            assertEquals(2, started)
            pending.last().cancel(); fixture.dispatcher.drain()
            gate.complete(Unit); fixture.dispatcher.drain()
            pending.dropLast(1).forEach { it.await().lease.release() }
            assertEquals(3, started)
            assertEquals(2, maximum)
            assertEquals(3, fixture.revoked.size)
        } finally { fixture.close() }
    }

    @Test fun lastWaiterCancellationKeepsTheReservationUntilTheLateNativeResourceIsRevoked(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val file = fixture.file("late.gif", 9 * mib)
            val gate = CompletableDeferred<Unit>()
            val pending = fixture.request(file) { withContext(NonCancellable) { gate.await() }; fixture.loaded(file) }
            fixture.dispatcher.drain()
            pending.cancel(); fixture.dispatcher.drain()
            assertFalse("Final cancellation must await late resource cleanup", pending.isCompleted)
            val live = fixture.request(fixture.file("live.gif", 8 * mib))
            fixture.dispatcher.drain()
            val denied = fixture.request(fixture.file("replacement.gif", 8 * mib))
            fixture.dispatcher.drain()
            assertTrue(runCatching { denied.await() }.exceptionOrNull() is DocumentImageLimitException)
            assertEquals(1, fixture.loads)
            gate.complete(Unit); fixture.dispatcher.drain()
            assertTrue(pending.isCompleted)
            assertEquals(1, fixture.revoked.size)
            val replacement = fixture.request(fixture.file("replacement.gif", 8 * mib))
            fixture.dispatcher.drain()
            replacement.await().lease.release()
            live.await().lease.release()
            assertEquals(3, fixture.revoked.size)
        } finally { fixture.close() }
    }

    @Test fun accountDocumentAndResourceVersionNeverShareAndRetirementRejectsOldGeneration(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val file = fixture.file("version.gif", 128)
            val generation = fixture.pool.generation()
            val pending = listOf(fixture.request(file), fixture.request(file.copy(modified = "version-2")),
                fixture.request(file, document = "other-account/revision"), fixture.request(file, document = "owner/next-revision"))
            fixture.dispatcher.drain()
            val images = pending.map { it.await() }
            assertEquals(4, fixture.loads)
            images.drop(1).forEach { assertNotSame(images[0].data, it.data) }
            fixture.pool.retire(); fixture.dispatcher.drain()
            assertEquals(4, fixture.revoked.size)
            val stale = fixture.request(file, generation = generation)
            fixture.dispatcher.drain()
            assertNotNull(runCatching { stale.await() }.exceptionOrNull())
            images.forEach { it.lease.release(); it.lease.release() }
            assertEquals(4, fixture.revoked.size)
            val fresh = fixture.request(file)
            fixture.dispatcher.drain()
            assertNotSame(images[0].data, fresh.await().data)
            fresh.await().lease.release()
            assertEquals(5, fixture.revoked.size)
        } finally { fixture.close() }
    }

    @Test fun cancellationAfterTheSharedReadResumesButBeforeDeliveryReclaimsTheOnlyLease(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val pending = fixture.request(fixture.file("undelivered.gif", 128))
            fixture.dispatcher.next() // Registers the reference and starts the producer.
            fixture.dispatcher.next() // Completes the read; the consumer remains queued.
            assertEquals(1, fixture.loads)
            assertFalse(pending.isCompleted)
            pending.cancel(); fixture.dispatcher.drain()
            assertTrue(pending.isCancelled)
            assertEquals(1, fixture.revoked.size)
            val replacement = fixture.request(fixture.file("undelivered.gif", 128))
            fixture.dispatcher.drain()
            replacement.await().lease.release()
            assertEquals(2, fixture.loads)
            assertEquals(2, fixture.revoked.size)
        } finally { fixture.close() }
    }
}

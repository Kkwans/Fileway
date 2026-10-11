package io.github.kkwans.nasfilebrowser.core

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class NativeResourceHandoffTest {
    /** No threads, sleeps, Android runtime or coroutine-test dependency. The
     * real continuation can be held after resume and before its dispatch. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    private class Handoff {
        val dispatcher = QueuedDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        lateinit var continuation: CancellableContinuation<Any?>
        val cleanup = mutableListOf<NativeResourceCleanup>()
        private val pendingCleanup = ArrayDeque<() -> Unit>()
        var delivered = false
        var received: Any? = null
        var requestCancellations = 0
        val job: Job = scope.launch {
            received = suspendCancellableCoroutine { value ->
                continuation = value
                value.invokeOnCancellation { requestCancellations++ }
            }
            delivered = true
            awaitCancellation()
        }
        init { dispatcher.drain() }
        fun resume(operation: String, result: Any?) {
            continuation.resumeNativeResult(operation, "owned-session", result) { action ->
                pendingCleanup.addLast { cleanup.add(action) }
            }
        }
        fun drainCleanup() { while (pendingCleanup.isNotEmpty()) pendingCleanup.removeFirst().invoke() }
        fun close() { scope.cancel(); dispatcher.drain(); drainCleanup() }
    }

    private val resources = listOf(
        "open" to NativeResourceCleanup("close_session", "session", "owned-resource"),
        "lease" to NativeResourceCleanup("revoke", "url", "owned-resource"),
        "asset" to NativeResourceCleanup("revoke", "url", "owned-resource"),
        "preview" to NativeResourceCleanup("revoke", "url", "owned-resource"),
        "upload_lease" to NativeResourceCleanup("revoke", "url", "owned-resource"),
        "search_start" to NativeResourceCleanup("search_cancel", "search", "owned-resource", "owned-session"),
        "command_start" to NativeResourceCleanup("command_cancel", "commandHandle", "owned-resource", "owned-session"),
    )

    @Test fun cancellationBeforeResumeReclaimsEveryResourceExactlyOnceOffTheCallback() {
        for ((operation, expected) in resources) {
            val handoff = Handoff()
            try {
                handoff.job.cancel()
                handoff.resume(operation, "owned-resource")
                handoff.job.cancel()
                handoff.continuation.cancel()
                assertTrue("Cleanup must only be queued by the callback", handoff.cleanup.isEmpty())
                handoff.dispatcher.drain()
                handoff.drainCleanup()
                assertFalse(operation, handoff.delivered)
                assertEquals(operation, listOf(expected), handoff.cleanup)
                assertEquals(1, handoff.requestCancellations)
                handoff.job.cancel(); handoff.drainCleanup()
                assertEquals(operation, 1, handoff.cleanup.size)
            } finally { handoff.close() }
        }
    }

    @Test fun cancellationAfterResumeBeforeDispatchReclaimsEveryResourceExactlyOnce() {
        for ((operation, expected) in resources) {
            val handoff = Handoff()
            try {
                handoff.resume(operation, "owned-resource")
                assertFalse("The resumed caller is still queued", handoff.delivered)
                handoff.job.cancel(); handoff.job.cancel()
                handoff.dispatcher.drain()
                assertTrue("Native cleanup must not run on caller dispatch", handoff.cleanup.isEmpty())
                handoff.drainCleanup()
                assertFalse(operation, handoff.delivered)
                assertEquals(operation, listOf(expected), handoff.cleanup)
                assertEquals(1, handoff.requestCancellations)
                handoff.continuation.cancel(); handoff.drainCleanup()
                assertEquals(operation, 1, handoff.cleanup.size)
            } finally { handoff.close() }
        }
    }

    @Test fun successfulDeliveryKeepsCallerOwnershipWhenItsJobIsLaterCancelled() {
        for ((operation, _) in resources) {
            val handoff = Handoff()
            try {
                handoff.resume(operation, "owned-resource")
                handoff.dispatcher.drain()
                assertTrue(operation, handoff.delivered)
                assertEquals("owned-resource", handoff.received)
                handoff.job.cancel(); handoff.job.cancel()
                handoff.dispatcher.drain(); handoff.drainCleanup()
                assertTrue("A delivered resource belongs to its caller", handoff.cleanup.isEmpty())
                assertEquals(0, handoff.requestCancellations)
            } finally { handoff.close() }
        }
    }

    @Test fun ordinaryResponsesAndAcknowledgedWritesNeverAcquireCompensatingCleanup() {
        for (operation in listOf("request", "token", "upload_stats", "close_session", "revoke", "owned-unknown-write")) {
            val handoff = Handoff()
            try {
                handoff.resume(operation, "owned-ack")
                handoff.job.cancel(); handoff.dispatcher.drain(); handoff.drainCleanup()
                assertFalse(handoff.delivered)
                assertTrue(operation, handoff.cleanup.isEmpty())
            } finally { handoff.close() }
        }
    }

    @Test fun nonStringResponsesAreNotMistakenForResourceHandles() {
        for ((operation, _) in resources) for (result in listOf(null, 42, mapOf("owned" to true))) {
            val handoff = Handoff()
            try {
                handoff.job.cancel()
                handoff.resume(operation, result)
                handoff.dispatcher.drain(); handoff.drainCleanup()
                assertTrue(operation, handoff.cleanup.isEmpty())
            } finally { handoff.close() }
        }
    }

    @Test fun successfulNonResourceResponsesKeepTheirExactIdentityAndNullability() {
        for (result in listOf(null, "owned-ack", mapOf("owned" to true))) {
            val handoff = Handoff()
            try {
                handoff.resume("request", result)
                handoff.dispatcher.drain()
                assertTrue(handoff.delivered)
                assertSame(result, handoff.received)
                handoff.job.cancel(); handoff.dispatcher.drain(); handoff.drainCleanup()
                assertTrue(handoff.cleanup.isEmpty())
            } finally { handoff.close() }
        }
    }
}

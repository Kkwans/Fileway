package io.github.kkwans.nasfilebrowser.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resumeWithException

class TransportException(message: String) : Exception(message)

object NativeTransport {
    init { System.loadLibrary("nfbbridge") }
    private external fun nativeCall(command: ByteArray): ByteArray?
    private val requests = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancellations = Executors.newSingleThreadExecutor()

    suspend fun call(command: JSONObject): Any? = suspendCancellableCoroutine { continuation ->
        val request = JSONObject(command.toString())
        val id = UUID.randomUUID().toString()
        request.put("requestId", id)
        continuation.invokeOnCancellation {
            cancellations.execute {
                nativeCall(JSONObject().put("op", "cancel").put("requestId", id).toString().toByteArray(Charsets.UTF_8))
            }
        }
        requests.launch {
            if (!continuation.isActive) return@launch
            try {
                val bytes = nativeCall(request.toString().toByteArray(Charsets.UTF_8))
                    ?: throw TransportException("无法读取传输响应")
                val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
                if (!envelope.optBoolean("ok")) throw TransportException(envelope.optString("error", "连接失败"))
                val result = envelope.opt("result").takeUnless { it == JSONObject.NULL }
                continuation.resumeNativeResult(request.optString("op"), request.optString("session"), result) { cleanup ->
                    cancellations.execute {
                        val command = JSONObject().put("op", cleanup.operation).put(cleanup.argument, cleanup.value)
                        cleanup.session?.let { command.put("session", it) }
                        nativeCall(command.toString().toByteArray(Charsets.UTF_8))
                    }
                }
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            }
        }
    }
}

package io.github.kkwans.fileway.remote

import android.content.Context
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class RemotePhase { OFFLINE, PAIRING, CONNECTING, CONNECTED, BUSY, FAILED }
data class RemoteConnectionState(
    val phase: RemotePhase = RemotePhase.OFFLINE, val device: TvDescriptor? = null,
    val snapshot: RemoteSnapshot? = null, val error: String? = null,
)
data class RemoteResult(val id: String, val status: String, val code: String = "", val payload: JSONObject? = null) {
    val completed get() = status == "COMPLETED"
    val uncertain get() = status == "UNKNOWN"
}
class RemoteFailure(val code: String, message: String) : IOException(message)

/** One phone controls one TV at a time. This owns no NAS account/player state. */
class TvRemoteClient(context: Context, private val scope: CoroutineScope, val store: RemoteStore = RemoteStore(context)) {
    private val context = context.applicationContext
    private val identity = ControllerIdentity()
    private val mutable = MutableStateFlow(RemoteConnectionState())
    val state = mutable.asStateFlow()
    private var client: OkHttpClient? = null
    private var socket: WebSocket? = null
    @Volatile private var generation = 0L
    private val pending = ConcurrentHashMap<String, CompletableDeferred<RemoteResult>>()

    /** Discovery is not trust. This unauthenticated request only displays the
     * TV's pairing QR; it never stores its advertised pin or shares a NAS token. */
    suspend fun requestPairing(device: TvDescriptor) {
        val transport = pinnedClient(context, device)
        try { post(transport,device,"/v1/pairing/request",JSONObject()) }
        finally { transport.dispatcher.cancelAll();transport.connectionPool.evictAll();transport.dispatcher.executorService.shutdown() }
    }

    suspend fun pair(invitation: TvInvitation) {
        disconnect()
        val expected = generation
        val device = invitation.device
        mutable.value = RemoteConnectionState(RemotePhase.PAIRING, device)
        val transport = pinnedClient(context, device).also { client = it }
        val controller = identity.publicController()
        val id = controller.getString("id")
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val secret = Base64.getUrlDecoder().decode(invitation.secret)
        val proof = try {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
            Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(proofMessage("pair", device.id, id, nonce)))
        } finally { secret.fill(0) }
        // Preserve the scanned identity before sending the request so a lost
        // pair response can be resolved by a key challenge rather than re-pairing.
        store.dao.saveDevice(PairedTv.from(device, false))
        try {
            post(transport, device, "/v1/pair", JSONObject().put("invitationId", invitation.id)
                .put("nonce", nonce).put("proof", proof).put("controller", controller))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (generation != expected) throw CancellationException("TV selection changed")
            // A saved controller can authenticate even when its pairing ACK was lost.
            post(transport, device, "/v1/challenge", JSONObject().put("controllerId", id))
        }
        if (generation != expected) return
        store.dao.saveDevice(PairedTv.from(device, true))
        connect(device)
    }

    suspend fun connect(descriptor: TvDescriptor) {
        disconnect()
        val expected = generation
        val device = TvDescriptor.from(descriptor.json())
        mutable.value = RemoteConnectionState(RemotePhase.CONNECTING, device)
        val transport = pinnedClient(context, device).also { client = it }
        try {
            val controller = identity.publicController()
            val id = controller.getString("id")
            val challenge = post(transport, device, "/v1/challenge", JSONObject().put("controllerId", id))
            val nonce = challenge.getString("nonce")
            val proof = identity.sign(device.id, id, nonce)
            if (generation != expected) return
            val request = Request.Builder().url("https://${device.serverName}:${device.port}/v1/control")
                .header("Sec-WebSocket-Protocol", REMOTE_SUBPROTOCOL)
                .header("X-Fileway-Controller", id).header("X-Fileway-Challenge", nonce).header("X-Fileway-Proof", proof).build()
            socket = transport.newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (generation != expected) return
                    if (text.toByteArray(Charsets.UTF_8).size > REMOTE_MESSAGE_LIMIT) { webSocket.cancel(); fail(expected, "电视控制消息过大"); return }
                    try {
                        val value = JSONObject(text)
                        when (value.getString("type")) {
                            "hello" -> require(value.getInt("protocol") == REMOTE_PROTOCOL && value.getString("deviceId") == device.id)
                            "snapshot" -> {
                                val snapshot = RemoteSnapshot.from(value)
                                val previous = mutable.value.snapshot
                                if (previous == null || snapshot.connectionId != previous.connectionId || snapshot.revision >= previous.revision)
                                    mutable.value = RemoteConnectionState(RemotePhase.CONNECTED, device, snapshot)
                            }
                            "command.result" -> {
                                val result = RemoteResult(value.getString("commandId"), value.getString("status"), value.optString("code"), value.optJSONObject("payload"))
                                require(result.status in setOf("ACCEPTED", "COMPLETED", "REJECTED", "UNKNOWN"))
                                if (result.status != "ACCEPTED") pending.remove(result.id)?.complete(result)
                            }
                        }
                    } catch (_: Exception) { webSocket.cancel(); fail(expected, "电视控制协议无法识别，请重新连接") }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val message = when (response?.code) { 409 -> "另一部手机正在控制这台电视"; 401 -> "电视配对已失效，请重新配对"; else -> "电视连接中断，可重新连接" }
                    fail(expected, message, response?.code == 409)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail(expected, "电视已断开连接") }
                override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason)}
            })
            store.dao.saveDevice(PairedTv.from(device, true))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            fail(expected, error.message ?: "无法连接电视")
            throw error
        }
    }

    suspend fun execute(operation: String, payload: JSONObject = JSONObject()): RemoteResult {
        val snapshot = mutable.value.snapshot ?: throw RemoteFailure("NOT_CONNECTED", "尚未连接电视")
        val id = UUID.randomUUID().toString()
        val message = JSONObject().put("type", "command").put("protocol", REMOTE_PROTOCOL).put("commandId", id)
            .put("connectionId", snapshot.connectionId).put("sessionEpoch", snapshot.sessionEpoch).put("mediaEpoch", snapshot.mediaEpoch)
            .put("operation", operation).put("payload", payload).toString()
        require(message.toByteArray(Charsets.UTF_8).size <= REMOTE_MESSAGE_LIMIT) { "操作内容过长，请分批发送" }
        if (pending.size >= 32) throw RemoteFailure("DEVICE_BUSY", "操作尚在处理中")
        val result = CompletableDeferred<RemoteResult>()
        pending[id] = result
        if (socket?.send(message) != true) { pending.remove(id); throw RemoteFailure("NOT_CONNECTED", "电视连接已断开") }
        return try { withTimeoutOrNull(35_000) { result.await() } ?: RemoteResult(id, "UNKNOWN", "RESULT_PENDING_TIMEOUT") }
        finally { pending.remove(id) }
    }

    fun disconnect() {
        generation++
        socket?.cancel(); socket = null
        client?.let { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdown() }; client = null
        for ((id, result) in pending) result.complete(RemoteResult(id, "UNKNOWN", "CONNECTION_CLOSED"))
        pending.clear()
        mutable.value = RemoteConnectionState()
        // Deliberately no stop/logout command: TV playback/session is independent.
    }

    private fun fail(expected: Long, message: String, busy: Boolean = false) {
        if (generation != expected) return
        mutable.update { it.copy(phase = if (busy) RemotePhase.BUSY else RemotePhase.FAILED, error = message, snapshot = null) }
        for ((id, result) in pending) result.complete(RemoteResult(id, "UNKNOWN", "CONNECTION_CLOSED"))
        pending.clear()
    }

    private suspend fun post(transport: OkHttpClient, device: TvDescriptor, path: String, data: JSONObject): JSONObject {
        val request = Request.Builder().url("https://${device.serverName}:${device.port}$path")
            .post(data.toString().toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = transport.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(RemoteFailure("UNREACHABLE", "无法连接电视，请确认局域网与配对状态")) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val body = it.body ?: throw IOException("Empty response")
                            if (body.contentLength() > 8192) throw IOException("Response too large")
                            val source = body.source()
                            source.request(8193)
                            if (source.buffer.size > 8192) throw IOException("Response too large")
                            val value = JSONObject(body.string())
                            if (!it.isSuccessful) throw RemoteFailure(value.optString("code", "PAIR_FAILED"), when(it.code) { 401 -> "电视配对已失效或邀请已过期"; 409 -> "电视正在处理其他连接"; else -> "电视尚未确认配对，请重新连接" })
                            if (continuation.isActive) continuation.resume(value)
                        } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                    }
                }
            })
        }
    }
}

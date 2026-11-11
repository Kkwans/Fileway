package io.github.kkwans.fileway.remote

import android.content.Context
import android.util.Log
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val retryable: Boolean = false,
    val pairingCode:String? = null,
    val pairingAwaitingConfirmation:Boolean=false,
)
data class RemoteResult(val id: String, val status: String, val code: String = "", val payload: JSONObject? = null) {
    val completed get() = status == "COMPLETED"
    val uncertain get() = status == "UNKNOWN"
}

/** Saved pairing records are independent of the one active connection. */
fun RemoteConnectionState.blocksSelection(target:String):Boolean = device?.id?.let {
    it!=target
}==true
class RemoteFailure(val code: String, message: String) : IOException(message)

/** One phone controls one TV at a time. This owns no NAS account/player state. */
class TvRemoteClient(context: Context, private val scope: CoroutineScope, val store: RemoteStore = RemoteStore(context)) {
    private val context = context.applicationContext
    private val identity = ControllerIdentity()
    private val mutable = MutableStateFlow(RemoteConnectionState())
    val state = mutable.asStateFlow()
    private var client: OkHttpClient? = null
    private var socket: WebSocket? = null
    private val lifecycle = Any()
    @Volatile private var generation = 0L
    private val pending = ConcurrentHashMap<String, CompletableDeferred<RemoteResult>>()
    private var pairingConfirmation:Pair<Long,CompletableDeferred<Unit>>?=null
    // Do not let an old request's bounded cancellation cancel a new request
    // that reused the same signed approval attempt.
    private val pairingRequests = Mutex()
    private data class ApprovalAttempt(
        val device: String, val fingerprint: String, val controller: String, val nonce: String,
        val signature: String, val version: Int, val invitationId: String?,
    )
    private var approvalAttempt:ApprovalAttempt?=null

    fun confirmPairing() {
        val confirmation=synchronized(lifecycle){pairingConfirmation?.takeIf{it.first==generation&&mutable.value.pairingAwaitingConfirmation}?.second}
        confirmation?.complete(Unit)
    }

    /** Discovery is not trust. The TV must approve this exact installation key. */
    suspend fun requestPairing(device: TvDescriptor) = requestPairing(device, null, false)

    suspend fun requestTvConsent(device: TvDescriptor, invitation: TvInvitation? = null) =
        requestPairing(device, invitation, true)

    private suspend fun requestPairing(device: TvDescriptor, invitation: TvInvitation?, requireConsent: Boolean) {
        val expected = requireNotNull(reserve(device, RemotePhase.PAIRING))
        try {
            pairingRequests.withLock { requestPairing(device, expected, invitation, requireConsent) }
        } catch(failure:Exception) {
            failPairing(expected,failure)
            throw failure
        }
    }

    private suspend fun requestPairing(device: TvDescriptor, expected: Long, invitation: TvInvitation?, requireConsent: Boolean) {
        val transport=activate(expected,device)
        var auth:JSONObject?=null
        var paired=false
        try {
            val descriptor=TvDescriptor.from(post(transport,device,"/v1/identity",JSONObject(),useGet=true))
            check(descriptor.id==device.id&&descriptor.fingerprint==device.fingerprint){"TV 身份已变化，请重新选择"}
            val version = if (descriptor.pairingVersion >= 3) 3 else 0
            if (requireConsent && version != 3) throw RemoteFailure("PAIRING_UNSUPPORTED", "请先将 TV 更新到支持首次授权连接的版本")
            require(invitation == null || (invitation.device.id == descriptor.id && invitation.device.fingerprint == descriptor.fingerprint)) {
                "二维码与当前 TV 不一致"
            }
            if(descriptor.pairingVersion<2){
                post(transport,device,"/v1/pairing/request",JSONObject())
                throw RemoteFailure("PAIRING_UNSUPPORTED","此 TV 版本请使用扫码配对，升级后可直接授权连接")
            }
            val controller=identity.publicController()
            val id=controller.getString("id")
            val cached = synchronized(lifecycle) {
                approvalAttempt?.takeIf {
                    it.device == device.id && it.fingerprint == device.fingerprint && it.controller == id &&
                        it.version == version && it.invitationId == invitation?.id
                }
            }
            val nonce=cached?.nonce?:ByteArray(32).also{SecureRandom().nextBytes(it)}.let{Base64.getUrlEncoder().withoutPadding().encodeToString(it)}
            val signature=cached?.signature?:identity.sign(device.id,id,nonce,"pair-request")
            val attempt = cached ?: ApprovalAttempt(device.id, device.fingerprint, id, nonce, signature, version, invitation?.id)
            if(!withGeneration(expected){approvalAttempt=attempt})throw CancellationException("TV selection changed")
            val code = if (version == 3) null else approvalCode(device.id, id, nonce)
            val request=JSONObject().put("controller",controller).put("nonce",nonce).put("signature",signature)
            if (version == 3) request.put("version", version)
            if (invitation != null) request.put("invitationId", invitation.id)
                .put("invitationProof", invitationProof(invitation, id, nonce))
            rememberDevice(expected,device,false)
            var response=try { post(transport,device,"/v1/pairing/start",request) }
                catch(failure:RemoteFailure){
                    if(failure.code!="UNREACHABLE")throw failure
                    // This one retry is the same idempotent signed request.
                    post(transport,device,"/v1/pairing/start",request)
                }
            val ticket=JSONObject().put("id",response.getString("id")).put("controllerId",id).put("nonce",nonce).put("signature",signature)
            auth=ticket
            val confirmation = if (code != null) CompletableDeferred<Unit>() else null
            if (!withGeneration(expected) { pairingConfirmation = confirmation?.let { expected to it } })
                throw CancellationException("TV selection changed")
            withTimeout(125_000){
                while(true){
                    ensureActive()
                    if(generation!=expected)throw CancellationException("TV selection changed")
                    check(response.getString("deviceId") == device.id && response.getString("controllerId") == id &&
                        response.optString("verificationCode") == code.orEmpty()) { "配对请求身份不匹配" }
                    check(response.getString("id")==ticket.getString("id")){"配对申请已变化"}
                    if(response.getString("status")!="PENDING")synchronized(lifecycle){if(approvalAttempt===attempt)approvalAttempt=null}
                    when(response.getString("status")){
                        "PAIRED"->{
                            paired=true
                            if (confirmation != null) {
                                withGeneration(expected) { mutable.value = mutable.value.copy(pairingCode = code, pairingAwaitingConfirmation = true) }
                                confirmation.await()
                            }
                            break
                        }
                        "PENDING"->withGeneration(expected){mutable.value=mutable.value.copy(pairingCode=code)}
                        "DENIED"->throw RemoteFailure("PAIR_DENIED","TV 已拒绝连接，可重新申请")
                        "CANCELLED"->throw RemoteFailure("PAIR_CANCELLED","配对申请已取消")
                        "EXPIRED"->throw RemoteFailure("PAIR_EXPIRED","配对申请已过期，请重新连接")
                        else->throw RemoteFailure("PAIR_FAILED","TV 未能保存配对，请重试")
                    }
                    delay(1000)
                    response=post(transport,device,"/v1/pairing/status",ticket)
                }
            }
            if(generation!=expected)return
            rememberConfirmedPairing(expected, device)
            connect(device,expected)
        } catch(failure:Exception){
            failPairing(expected,failure)
            throw failure
        } finally {
            if(!paired&&auth!=null)withContext(NonCancellable){
                // A stopped selection cannot leave an actionable orphan prompt.
                // Cancellation never revokes a pairing already acknowledged by TV.
                val cleanup=pinnedClient(context,device)
                try { withTimeout(3000){
                    val result=post(cleanup,device,"/v1/pairing/cancel",auth)
                    if(result.getString("status")!="PENDING")synchronized(lifecycle){
                        if(approvalAttempt?.device==device.id&&approvalAttempt?.nonce==auth.getString("nonce"))approvalAttempt=null
                    }
                } }
                catch(_:Exception) { /* TV expiry remains the bounded fallback. */ }
                finally {closeTransport(cleanup)}
            }
        }
    }

    suspend fun pair(invitation: TvInvitation) {
        val device = invitation.device
        val expected = requireNotNull(reserve(device, RemotePhase.PAIRING))
        try {
            val transport = activate(expected, device)
            val controller = identity.publicController()
            val id = controller.getString("id")
            val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            val proof = invitationProof(invitation, id, nonce)
            // Preserve the scanned identity before sending the request so a lost
            // pair response can be resolved by a key challenge rather than re-pairing.
            rememberDevice(expected,device,false)
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
            rememberConfirmedPairing(expected, device)
            connect(device, expected)
        } catch (error: Exception) {
            if (error is CancellationException) fail(expected, "连接已取消，可重新连接")
            else fail(expected, error.message ?: "配对未完成，请重试")
            throw error
        }
    }

    private fun invitationProof(invitation: TvInvitation, controllerId: String, nonce: String): String {
        val secret = Base64.getUrlDecoder().decode(invitation.secret)
        return try {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal(proofMessage("pair", invitation.device.id, controllerId, nonce)),
            )
        } finally { secret.fill(0) }
    }

    suspend fun connect(descriptor: TvDescriptor) = connect(descriptor, null)

    private suspend fun connect(descriptor: TvDescriptor, predecessor: Long?) {
        val expected = reserve(descriptor, RemotePhase.CONNECTING, predecessor) ?: return
        try {
            val device = TvDescriptor.from(descriptor.json())
            val transport = activate(expected, device)
            val controller = identity.publicController()
            val id = controller.getString("id")
            val challenge = post(transport, device, "/v1/challenge", JSONObject().put("controllerId", id))
            val nonce = challenge.getString("nonce")
            val proof = identity.sign(device.id, id, nonce)
            if (generation != expected) return
            val request = Request.Builder().url("https://${device.serverName}:${device.port}/v1/control")
                .header("Sec-WebSocket-Protocol", REMOTE_SUBPROTOCOL)
                .header("X-Fileway-Controller", id).header("X-Fileway-Challenge", nonce).header("X-Fileway-Proof", proof).build()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    scope.launch {
                        try {
                            rememberDevice(expected, device, true)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // Pairing trust has its own commit. An address
                            // refresh must not tear down an authenticated socket.
                            Log.w("FilewayTvRemote", "Could not refresh the saved TV connection")
                        }
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (generation != expected) return
                    if (text.toByteArray(Charsets.UTF_8).size > REMOTE_MESSAGE_LIMIT) { fail(expected, "电视控制消息过大"); return }
                    try {
                        val value = JSONObject(text)
                        when (value.getString("type")) {
                            "hello" -> require(value.getInt("protocol") == REMOTE_PROTOCOL && value.getString("deviceId") == device.id)
                            "snapshot" -> {
                                val snapshot = RemoteSnapshot.from(value)
                                withGeneration(expected) {
                                    val previous = mutable.value.snapshot
                                    if (previous == null || snapshot.connectionId != previous.connectionId || snapshot.revision >= previous.revision)
                                        mutable.value = RemoteConnectionState(RemotePhase.CONNECTED, device, snapshot)
                                }
                            }
                            "command.result" -> {
                                val result = RemoteResult(value.getString("commandId"), value.getString("status"), value.optString("code"), value.optJSONObject("payload"))
                                require(result.status in setOf("ACCEPTED", "COMPLETED", "REJECTED", "UNKNOWN"))
                                if (result.status != "ACCEPTED") {
                                    var completion: CompletableDeferred<RemoteResult>? = null
                                    withGeneration(expected) { completion = pending.remove(result.id) }
                                    completion?.complete(result)
                                }
                            }
                        }
                    } catch (_: Exception) { fail(expected, "电视控制协议无法识别，请重新连接") }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val identityFailure = identityFailure(t)
                    val message = when {
                        identityFailure -> "电视身份验证失败，请重新配对"
                        response?.code == 409 -> "另一部手机正在控制这台电视"
                        response?.code in setOf(401, 403) -> "电视配对已失效，请重新配对"
                        else -> "电视连接中断，可重新连接"
                    }
                    val retryable = !identityFailure && (response == null || response.code == 429 || response.code >= 500)
                    fail(expected, message, response?.code == 409, retryable)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    val retryable = code !in setOf(1002, 1003, 1008) && reason != "DEVICE_BUSY"
                    val message = when (reason) {
                        "DEVICE_BUSY" -> "另一部手机正在控制这台电视"
                        "UNAUTHORIZED", "AUTHORIZATION_REVOKED" -> "电视配对已失效，请重新配对"
                        "RATE_LIMITED" -> "操作过于频繁，请稍后重新连接"
                        "INVALID_COMMAND", "PROTOCOL_UNSUPPORTED" -> "遥控协议不兼容，请更新客户端"
                        else -> if (code in setOf(1002, 1003)) "遥控协议不兼容，请更新客户端" else "电视已断开连接"
                    }
                    fail(expected, message, reason == "DEVICE_BUSY", retryable)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // An empty peer close frame is reported as 1005. OkHttp
                    // rejects sending that reserved code back to the peer.
                    val reply = if (code in 1000..1003 || code in 1007..1014 || code in 3000..4999) code else 1000
                    webSocket.close(reply, null)
                }
            }
            if (!withGeneration(expected) { socket = transport.newWebSocket(request, listener) })
                throw CancellationException("TV selection changed")
        } catch (error: Exception) {
            if (error is CancellationException) {
                fail(expected, "连接已取消，可重新连接")
                throw error
            }
            val message = error.message ?: "无法连接电视"
            val retryable = error is RemoteFailure && error.code == "UNREACHABLE"
            if (predecessor != null) {
                val pairedMessage = "TV 已配对，但当前连接未完成：$message"
                fail(expected, pairedMessage, retryable = retryable)
                throw RemoteFailure((error as? RemoteFailure)?.code ?: "CONNECT_FAILED", pairedMessage).apply { initCause(error) }
            }
            fail(expected, message, retryable = retryable)
            throw error
        }
    }

    suspend fun execute(operation: String, payload: JSONObject = JSONObject(), expected: RemoteSnapshot? = null): RemoteResult {
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<RemoteResult>()
        synchronized(lifecycle) {
            val snapshot = mutable.value.snapshot ?: throw RemoteFailure("NOT_CONNECTED", "尚未连接电视")
            if (expected != null && (snapshot.connectionId != expected.connectionId ||
                    snapshot.sessionEpoch != expected.sessionEpoch || snapshot.mediaEpoch != expected.mediaEpoch))
                throw RemoteFailure("SCOPE_CHANGED", "TV 播放或登录来源已变化，请重新打开面板")
            val message = JSONObject().put("type", "command").put("protocol", REMOTE_PROTOCOL).put("commandId", id)
                .put("connectionId", snapshot.connectionId).put("sessionEpoch", snapshot.sessionEpoch).put("mediaEpoch", snapshot.mediaEpoch)
                .put("operation", operation).put("payload", payload).toString()
            require(message.toByteArray(Charsets.UTF_8).size <= REMOTE_MESSAGE_LIMIT) { "操作内容过长，请分批发送" }
            if (pending.size >= 32) throw RemoteFailure("DEVICE_BUSY", "操作尚在处理中")
            pending[id] = result
            if (socket?.send(message) != true) { pending.remove(id); throw RemoteFailure("NOT_CONNECTED", "电视连接已断开") }
        }
        return try { withTimeoutOrNull(35_000) { result.await() } ?: RemoteResult(id, "UNKNOWN", "RESULT_PENDING_TIMEOUT") }
        finally { synchronized(lifecycle) { pending.remove(id) } }
    }

    fun disconnect() {
        retire { RemoteConnectionState() }
        // Deliberately no stop/logout command: TV playback/session is independent.
    }

    private fun fail(expected: Long, message: String, busy: Boolean = false, retryable: Boolean = false) {
        retire(expected) { it.copy(phase = if (busy) RemotePhase.BUSY else RemotePhase.FAILED, error = message, snapshot = null, retryable = retryable,pairingCode=null,pairingAwaitingConfirmation=false) }
    }

    private fun failPairing(expected:Long, failure:Exception) {
        val message=when(failure) {
            is TimeoutCancellationException -> "等待 TV 授权超时，可重新连接"
            is CancellationException -> "连接已取消，可重新连接"
            else -> failure.message?:"配对未完成，请重试"
        }
        fail(expected,message)
    }

    private suspend fun rememberDevice(expected: Long, device: TvDescriptor, trusted: Boolean) {
        val caller = currentCoroutineContext()
        val checkSelection: () -> Unit = {
            if (!withGeneration(expected) { caller.ensureActive() })
                throw CancellationException("TV selection changed")
        }
        checkSelection()
        // Let Room finish the transaction. The original caller and generation
        // are checked again after SQL, before the transaction admits the write.
        withContext(NonCancellable) {
            store.rememberDevice(PairedTv.from(device, trusted), checkSelection)
        }
    }

    private suspend fun rememberConfirmedPairing(expected: Long, device: TvDescriptor) {
        try {
            rememberDevice(expected, device, true)
        } catch (error: Exception) {
            if (error is CancellationException || error is RemoteFailure) throw error
            throw RemoteFailure("PAIRING_NOT_SAVED", "TV 已确认配对，手机未能保存配对记录，请重新连接")
                .apply { initCause(error) }
        }
    }

    private suspend fun reserve(device: TvDescriptor, phase: RemotePhase, predecessor: Long? = null): Long? =
        retire(predecessor, targetDevice = device.id, caller = currentCoroutineContext()[Job]) {
            RemoteConnectionState(phase, device)
        }

    private fun withGeneration(expected: Long, action: () -> Unit): Boolean = synchronized(lifecycle) {
        if (generation != expected) false else { action(); true }
    }

    private fun activate(expected: Long, device: TvDescriptor): OkHttpClient {
        val transport = pinnedClient(context, device)
        if (!withGeneration(expected) {
            client = transport
        }) {
            closeTransport(transport)
            throw CancellationException("TV selection changed")
        }
        return transport
    }

    private data class Retired(val generation: Long, val socket: WebSocket?, val client: OkHttpClient?,
        val pending: List<Pair<String, CompletableDeferred<RemoteResult>>>,val confirmation:CompletableDeferred<Unit>?)

    private fun retire(expected: Long? = null, targetDevice:String?=null, caller: Job? = null,
        next: (RemoteConnectionState) -> RemoteConnectionState): Long? {
        val retired = synchronized(lifecycle) {
            caller?.ensureActive()
            if (expected != null && generation != expected) return null
            if(targetDevice!=null&&mutable.value.blocksSelection(targetDevice))
                throw RemoteFailure("DISCONNECT_REQUIRED","请先断开当前 TV，再连接其他 TV")
            val retired = Retired(++generation, socket, client, pending.entries.map { it.key to it.value },pairingConfirmation?.second)
            socket = null; client = null; pending.clear();pairingConfirmation=null
            mutable.value = next(mutable.value)
            retired
        }
        // Invalidate callbacks and detach pending results before canceling IO.
        // Cleanup and continuations run outside the lifecycle monitor.
        try { retired.socket?.cancel() } catch (_: Exception) { }
        retired.confirmation?.cancel()
        retired.client?.let(::closeTransport)
        retired.pending.forEach { (id, result) -> result.complete(RemoteResult(id, "UNKNOWN", "CONNECTION_CLOSED")) }
        return retired.generation
    }

    private fun closeTransport(transport: OkHttpClient) {
        try { transport.dispatcher.cancelAll() } catch (_: Exception) { }
        try { transport.connectionPool.evictAll() } catch (_: Exception) { }
        try { transport.dispatcher.executorService.shutdown() } catch (_: Exception) { }
    }

    private fun identityFailure(error: Throwable) = generateSequence(error) { it.cause }.take(8)
        .any { it is SSLException || it is java.security.cert.CertificateException }

    private suspend fun post(transport: OkHttpClient, device: TvDescriptor, path: String, data: JSONObject, useGet:Boolean=false): JSONObject {
        val request = Request.Builder().url("https://${device.serverName}:${device.port}$path")
            .apply{if(useGet)get()else post(data.toString().toRequestBody("application/json".toMediaType()))}.build()
        return suspendCancellableCoroutine { continuation ->
            val call = transport.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(if (identityFailure(e))
                        RemoteFailure("IDENTITY_CHANGED", "电视身份验证失败，请重新配对") else
                        RemoteFailure("UNREACHABLE", "无法连接电视，请确认局域网与配对状态"))
                }
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

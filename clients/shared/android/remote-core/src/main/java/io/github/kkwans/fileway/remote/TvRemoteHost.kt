package io.github.kkwans.fileway.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class TvHostState(val device: TvDescriptor? = null, val invitation: TvInvitation? = null,
    val controllerName: String? = null, val error: String? = null)

/** Foreground-only control host. Stopping it never stops a media player or
 * signs out the TV. All JNI handles are tied to this host's generation. */
class TvRemoteHost(context: Context, private val scope: CoroutineScope,
    private val execute: suspend (JSONObject) -> JSONObject,
    private val snapshot: () -> JSONObject,
) {
    private val app = context.applicationContext
    val store = RemoteStore(app)
    private val mutable = MutableStateFlow(TvHostState())
    val state = mutable.asStateFlow()
    private val lifecycle = Mutex()
    private val commands = Mutex()
    private val publishing=Mutex()
    private val invitations=Mutex()
    private var running: Job? = null
    private var invitationJob: Job? = null
    private var handle: String? = null
    private var revision = 0L
    private var foreground = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var restart: Job? = null

    fun start() {
        foreground = true
        if (networkCallback == null) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = networkChanged()
                override fun onLost(network: Network) = networkChanged()
            }
            runCatching { app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback); networkCallback = callback }
        }
        if (running?.isActive == true) return
        launchHost()
    }
    private fun networkChanged() {
        restart?.cancel()
        restart = scope.launch { delay(500); if (foreground) { running?.cancelAndJoin(); launchHost() } }
    }
    private fun launchHost() {
        running = scope.launch {
            lifecycle.withLock {
                var localHandle: String? = null
                var registration: NsdManager.RegistrationListener? = null
                try {
                    val network = lanNetwork(app) ?: error("请连接局域网后使用手机遥控")
                    val address = lanAddress(app, network) ?: error("当前网络没有可用的局域网地址")
                    val controllers = store.dao.currentControllers()
                    val config = JSONObject().put("identity", store.tvIdentity()).put("name", Build.MODEL.take(40).ifBlank { "栖卷 TV" })
                        .put("listenAddress", address).put("controllers", JSONArray(controllers.map { it.json() }))
                    localHandle = NativeTransport.call(JSONObject().put("op", "remote_start").put("remoteConfig", config)) as String
                    handle = localHandle
                    val device = TvDescriptor.from(call("remote_descriptor") as JSONObject)
                    mutable.value = TvHostState(device)
                    registration = advertise(device)
                    // Pairing is opened by the TV entry or a discovered phone's
                    // request, so an unpaired installation remains usable by remote.
                    publish()
                    coroutineScope {
                        launch { while (isActive) { delay(1000); publish() } }
                        while (isActive) {
                            val event = call("remote_wait") as JSONObject
                            when (event.optString("type")) {
                                "pairing.requested" -> showPairing()
                                "pair.requested" -> {
                                    val controller = TrustedController.from(event.getJSONObject("controller"))
                                    var saved = false
                                    try { store.dao.saveController(controller); saved = true
                                        call("remote_pair_complete", JSONObject().put("remotePairRequestId", event.getString("pairRequestId")).put("remoteSaved", true))
                                        invitationJob?.cancel(); mutable.update { it.copy(invitation = null) }
                                    } catch (error: Exception) {
                                        if (saved) withContext(NonCancellable){
                                            runCatching { call("remote_revoke",JSONObject().put("remoteControllerId",controller.id)) }
                                            store.dao.removeController(controller.id)
                                        }
                                        if (error is CancellationException) throw error
                                        runCatching { call("remote_pair_complete", JSONObject().put("remotePairRequestId", event.getString("pairRequestId")).put("remoteSaved", false)) }
                                    }
                                }
                                "connection.opened" -> {
                                    val controller = store.dao.controller(event.getString("controllerId"))
                                    mutable.update { it.copy(controllerName = controller?.name) }
                                }
                                "connection.closed" -> mutable.update { it.copy(controllerName = null) }
                                "command" -> launch { commands.withLock { dispatch(event) } }
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutable.update { it.copy(error = error.message ?: "手机遥控暂不可用") }
                } finally {
                    invitationJob?.cancel()
                    registration?.let { listener -> runCatching { app.getSystemService(NsdManager::class.java).unregisterService(listener) } }
                    if (handle == localHandle) handle = null
                    withContext(NonCancellable) { localHandle?.let { runCatching { NativeTransport.call(JSONObject().put("op", "remote_stop").put("remoteHandle", it)) } } }
                    mutable.update { it.copy(device = null, invitation = null, controllerName = null) }
                }
            }
        }
    }
    private suspend fun dispatch(event: JSONObject) {
        val command = event.getJSONObject("command")
        val operation = command.getString("operation")
        val controller = event.getString("controllerId")
        val connection = event.getString("connectionId")
        val authorized = call("remote_connection", JSONObject().put("remoteConnectionId", connection).put("remoteControllerId", controller)) as Boolean
        var status = "COMPLETED"
        var code = ""
        var payload: JSONObject? = null
        try {
            check(authorized && store.dao.controller(controller) != null) { "控制连接已变化" }
            when (operation) {
                "device.enablePairing" -> showPairing()
                "device.revokeController" -> {
                    val id = command.getJSONObject("payload").getString("id")
                    store.dao.removeController(id)
                    call("remote_revoke", JSONObject().put("remoteControllerId", id))
                }
                else -> payload = execute(JSONObject(command.toString()).put("controllerId",controller))
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            status = "REJECTED"; code = "ACTION_FAILED"
            payload = JSONObject().put("message", error.message ?: "操作未完成")
        }
        publish()
        val result = JSONObject().put("type", "command.result").put("commandId", command.getString("commandId"))
            .put("status", status).put("code", code).put("revision", revision)
        payload?.let { result.put("payload", it) }
        call("remote_reply", JSONObject().put("remoteConnectionId", connection).put("remoteControllerId", controller).put("remoteResult", result))
    }
    suspend fun publish() =publishing.withLock {
        if (handle == null) return@withLock
        call("remote_publish", JSONObject().put("remoteState", snapshot().put("revision", ++revision)))
    }
    suspend fun authorized(command:JSONObject):Boolean{
        val controller=command.getString("controllerId")
        if(handle==null||store.dao.controller(controller)==null)return false
        return call("remote_connection",JSONObject().put("remoteConnectionId",command.getString("connectionId")).put("remoteControllerId",controller)) as Boolean
    }
    fun requestPairing() { scope.launch { runCatching { showPairing() }.onFailure { e -> mutable.update { it.copy(error = e.message) } } } }
    private suspend fun showPairing()=invitations.withLock {
        val invitation = TvInvitation.from(call("remote_invite") as JSONObject)
        mutable.update { it.copy(invitation = invitation, error = null) }
        invitationJob?.cancel()
        invitationJob = scope.launch { delay(invitation.expiresIn * 1000L); mutable.update { it.copy(invitation = null) } }
    }
    fun dismissPairing() { mutable.update { it.copy(invitation = null) } }
    fun revokeController(id:String){scope.launch{
        var removed=false
        try{store.dao.removeController(id);removed=true;if(handle!=null)call("remote_revoke",JSONObject().put("remoteControllerId",id))}
        catch(error:Exception){if(error is CancellationException)throw error;mutable.update{it.copy(error=if(removed)"配对记录已更新，控制连接需要重新连接"else"配对记录未能保存，请重试")}}
    }}
    fun stop() {
        foreground = false; restart?.cancel(); running?.cancel(); running = null
        networkCallback?.let { runCatching { app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }; networkCallback = null
    }
    private suspend fun call(op: String, fields: JSONObject = JSONObject()): Any? = NativeTransport.call(fields.put("op", op).put("remoteHandle", handle ?: error("电视控制服务未启动")))

    private fun advertise(device: TvDescriptor): NsdManager.RegistrationListener {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { mutable.update { it.copy(error = "电视自动发现暂不可用（$code），仍可扫码连接") } }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        val info = NsdServiceInfo().apply {
            serviceName = "Fileway-TV-${device.id.take(8)}"; serviceType = REMOTE_SERVICE_TYPE; port = device.port
            setAttribute("version", "1"); setAttribute("id", device.id); setAttribute("fp", device.fingerprint); setAttribute("name", device.name)
        }
        app.getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        return listener
    }
}

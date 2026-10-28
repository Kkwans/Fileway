package io.github.kkwans.nasfilebrowser.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

// Ads identify candidates, never authenticate them. Saved credentials remain
// bound to the chosen profile's exact origin and are never sent to a new ad.
data class LanServer(val name: String, val address: String)
data class LanDiscoveryState(val searching: Boolean = true, val servers: List<LanServer> = emptyList(), val error: String? = null)

class LanServiceDiscovery(context: Context) {
    private val app = context.applicationContext
    companion object {
        const val TYPE = "_fileway._tcp."
        fun endpoint(host: String, port: Int, attributes: Map<String,String>): String? = runCatching {
            require(attributes["version"] == "1" && port in 1..65535)
            val scheme = attributes["scheme"] ?: "http"
            require(scheme in setOf("http","https"))
            val target = attributes["host"] ?: host
            require(target.isNotBlank() && target.length<=200 && target.none { it.isWhitespace() || it in "/\\@?#" })
            val base = attributes["path"] ?: "/"
            require(base.startsWith('/') && base.length<=160 && base.none { it.isWhitespace() || it in "?#\\" })
            val authority = if (':' in target && !target.startsWith('[')) "[$target]" else target
            val uri = URI("$scheme://$authority:$port${base.trimEnd('/')}")
            require(uri.host != null && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null)
            require(uri.path.split('/').none { it==".." || it=="." })
            uri.toASCIIString()
        }.getOrNull()
    }

    @Suppress("DEPRECATION") // Resolver API supported by Android 9.
    fun scan() = callbackFlow {
        val manager = app.getSystemService(NsdManager::class.java)
        val multicast = app.getSystemService(WifiManager::class.java)?.createMulticastLock("fileway-discovery")?.apply { setReferenceCounted(false) }
        val found = Channel<NsdServiceInfo>(32)
        val lost = ConcurrentHashMap.newKeySet<String>()
        val guard=Any(); val servers=linkedMapOf<String,LanServer>()
        var registered=false
        fun publish() { trySend(LanDiscoveryState(servers=synchronized(guard){servers.values.distinctBy{it.address}.take(32)})) }
        val listener=object:NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type:String) { publish() }
            override fun onDiscoveryStopped(type:String) {}
            override fun onStartDiscoveryFailed(type:String,code:Int) { trySend(LanDiscoveryState(false,error="无法搜索局域网服务（$code）")); close() }
            override fun onStopDiscoveryFailed(type:String,code:Int) {}
            override fun onServiceFound(info:NsdServiceInfo) {
                if(info.serviceType.trim('.')==TYPE.trim('.')) { lost.remove(info.serviceName); found.trySend(info) }
            }
            override fun onServiceLost(info:NsdServiceInfo) {
                lost.add(info.serviceName);synchronized(guard){servers.remove(info.serviceName)};publish()
            }
        }
        val resolver=launch {
            for(info in found) {
                // API28 allows only one active resolution; keep the work serial.
                val resolution=withTimeoutOrNull(4500) {
                    suspendCancellableCoroutine<Result<NsdServiceInfo>> { continuation ->
                        val callback=object:NsdManager.ResolveListener {
                            override fun onResolveFailed(value:NsdServiceInfo,code:Int) { if(continuation.isActive)continuation.resume(Result.failure(IllegalStateException("NSD resolution failed"))) }
                            override fun onServiceResolved(value:NsdServiceInfo) { if(continuation.isActive)continuation.resume(Result.success(value)) }
                        }
                        try { manager.resolveService(info,callback) } catch (_:Exception) { if(continuation.isActive)continuation.resume(Result.failure(IllegalStateException("NSD resolution failed"))) }
                    }
                }
                if(resolution==null) {trySend(LanDiscoveryState(false,synchronized(guard){servers.values.toList()},"服务解析超时"));close();return@launch}
                val resolved=resolution.getOrNull()
                if(resolved!=null && !lost.contains(info.serviceName)) {
                    val attributes=resolved.attributes.mapValues{(_,value)->value.toString(Charsets.UTF_8)}
                    endpoint(resolved.host?.hostAddress.orEmpty(),resolved.port,attributes)?.let{url->
                        synchronized(guard){if(servers.size<32 || servers.containsKey(info.serviceName))servers[info.serviceName]=LanServer(info.serviceName,url)}
                        publish()
                    }
                }
            }
        }
        try {
            multicast?.acquire()
            manager.discoverServices(TYPE,NsdManager.PROTOCOL_DNS_SD,listener);registered=true
        } catch (_:Exception) { trySend(LanDiscoveryState(false,error="局域网搜索暂不可用")); close() }
        val timeout=launch { delay(12_000);trySend(LanDiscoveryState(false,synchronized(guard){servers.values.distinctBy{it.address}.take(32)}));close() }
        awaitClose {
            timeout.cancel();resolver.cancel();found.close()
            if(registered)runCatching{manager.stopServiceDiscovery(listener)}
            if(multicast?.isHeld==true)runCatching{multicast.release()}
        }
    }
}

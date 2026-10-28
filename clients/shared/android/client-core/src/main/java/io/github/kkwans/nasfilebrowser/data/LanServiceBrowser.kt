package io.github.kkwans.nasfilebrowser.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Semaphore

data class DiscoveredLanService(val name: String, val address: String, val port: Int, val attributes: Map<String, String>)
data class LanServiceScan(val searching: Boolean = true, val services: List<DiscoveredLanService> = emptyList(), val error: String? = null)

/** Shared bounded DNS-SD resolver. Its results are candidates, never identities
 * or authorization to send a previously saved credential to a new origin. */
class LanServiceBrowser(context: Context) {
    private val app = context.applicationContext
    companion object { private val resolverPermit=Semaphore(1) }

    @Suppress("DEPRECATION") // Keep the API28 resolution path supported.
    fun scan(type: String) = callbackFlow {
        require(type.matches(Regex("_[a-z0-9-]+\\._tcp\\.")))
        val manager = app.getSystemService(NsdManager::class.java)
        val multicast = app.getSystemService(WifiManager::class.java)?.createMulticastLock("fileway-discovery")?.apply { setReferenceCounted(false) }
        val found = Channel<NsdServiceInfo>(32)
        val lost = ConcurrentHashMap.newKeySet<String>()
        val guard = Any()
        val services = linkedMapOf<String, DiscoveredLanService>()
        var registered = false
        fun publish() { trySend(LanServiceScan(services = synchronized(guard) { services.values.toList() })) }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(value: String) { publish() }
            override fun onDiscoveryStopped(value: String) = Unit
            override fun onStartDiscoveryFailed(value: String, code: Int) { trySend(LanServiceScan(false, error = "局域网搜索暂不可用（$code）")); close() }
            override fun onStopDiscoveryFailed(value: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.trim('.') == type.trim('.')) { lost.remove(info.serviceName); found.trySend(info) }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                lost.add(info.serviceName); synchronized(guard) { services.remove(info.serviceName) }; publish()
            }
        }
        val resolver = launch {
            for (info in found) {
                val resolution = withTimeoutOrNull(4500) {
                    resolverPermit.acquire()
                    suspendCancellableCoroutine<Result<NsdServiceInfo>> { continuation ->
                        val released=AtomicBoolean(false)
                        fun finish(result:Result<NsdServiceInfo>){
                            if(released.compareAndSet(false,true)){
                                resolverPermit.release()
                                if(continuation.isActive)continuation.resume(result)
                            }
                        }
                        if(!continuation.isActive){resolverPermit.release();return@suspendCancellableCoroutine}
                        val callback = object : NsdManager.ResolveListener {
                            override fun onResolveFailed(value: NsdServiceInfo, code: Int) { finish(Result.failure(IllegalStateException("NSD resolution failed"))) }
                            override fun onServiceResolved(value: NsdServiceInfo) { finish(Result.success(value)) }
                        }
                        try { manager.resolveService(info, callback) }
                        catch (_: Exception) { finish(Result.failure(IllegalStateException("NSD resolution failed"))) }
                    }
                }
                // Legacy NSD has one active resolver and no cancel method.
                // Do not launch an unbounded set of overlapping resolutions.
                if (resolution == null) { trySend(LanServiceScan(false, synchronized(guard) { services.values.toList() }, "局域网服务解析超时")); close(); return@launch }
                val resolved = resolution.getOrNull()
                if (resolved != null && !lost.contains(info.serviceName)) {
                    val value = DiscoveredLanService(info.serviceName, resolved.host?.hostAddress.orEmpty(), resolved.port,
                        resolved.attributes.mapValues { (_, value) -> value.toString(Charsets.UTF_8) })
                    synchronized(guard) { if (services.size < 32 || services.containsKey(value.name)) services[value.name] = value }
                    publish()
                }
            }
        }
        try { multicast?.acquire(); manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener); registered = true }
        catch (_: Exception) { trySend(LanServiceScan(false, error = "局域网搜索暂不可用")); close() }
        val timeout = launch { delay(12_000); trySend(LanServiceScan(false, synchronized(guard) { services.values.toList() })); close() }
        awaitClose {
            timeout.cancel(); resolver.cancel(); found.close()
            if (registered) runCatching { manager.stopServiceDiscovery(listener) }
            if (multicast?.isHeld == true) runCatching { multicast.release() }
        }
    }
}

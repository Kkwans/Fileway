package io.github.kkwans.nasfilebrowser.data

import android.content.Context
import java.net.URI
import kotlinx.coroutines.flow.map

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

    fun scan() = LanServiceBrowser(app).scan(TYPE).map { scan ->
        LanDiscoveryState(scan.searching, scan.services.mapNotNull { service ->
            endpoint(service.address, service.port, service.attributes)?.let { LanServer(service.name, it) }
        }.distinctBy { it.address }.take(32), scan.error)
    }
}

package io.github.kkwans.fileway.remote

import android.content.Context
import io.github.kkwans.nasfilebrowser.data.LanServiceBrowser
import kotlinx.coroutines.flow.map
import org.json.JSONObject

data class TvDiscoveryState(val searching: Boolean = true, val devices: List<TvDescriptor> = emptyList(), val error: String? = null)

class TvServiceDiscovery(context: Context) {
    private val browser = LanServiceBrowser(context)
    fun scan() = browser.scan(REMOTE_SERVICE_TYPE).map { scan ->
        val devices = scan.services.mapNotNull { service -> runCatching {
            val id = requireNotNull(service.attributes["id"])
            require(service.attributes["version"] == REMOTE_PROTOCOL.toString())
            TvDescriptor.from(JSONObject().put("protocol", REMOTE_PROTOCOL).put("deviceId", id)
                .put("name", service.attributes["name"]?:service.name).put("serverName", "tv-$id.fileway.local")
                .put("address", service.address).put("port", service.port).put("spkiSha256", service.attributes["fp"]))
        }.getOrNull() }.distinctBy { it.id }
        TvDiscoveryState(scan.searching, devices, scan.error)
    }
}

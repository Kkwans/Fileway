package io.github.kkwans.fileway.remote

import android.net.Uri
import java.net.InetAddress
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

const val REMOTE_PROTOCOL = 1
const val REMOTE_SERVICE_TYPE = "_fileway-tv._tcp."
const val REMOTE_SUBPROTOCOL = "fileway.remote.v1"
const val REMOTE_MESSAGE_LIMIT = 64 * 1024

private val opaque = Regex("[A-Za-z0-9_-]{16,96}")
private val deviceId = Regex("[a-f0-9]{32}")

data class TvDescriptor(
    val id: String, val name: String, val serverName: String,
    val address: String, val port: Int, val fingerprint: String,
) {
    fun json() = JSONObject().put("protocol", REMOTE_PROTOCOL).put("deviceId", id).put("name", name)
        .put("serverName", serverName).put("address", address).put("port", port).put("spkiSha256", fingerprint)

    fun at(address: String, port: Int) = from(json().put("address", address).put("port", port))

    companion object {
        fun from(value: JSONObject): TvDescriptor {
            require(value.getInt("protocol") == REMOTE_PROTOCOL) { "电视遥控协议需要更新" }
            val id = value.getString("deviceId")
            val name = value.getString("name")
            val serverName = value.getString("serverName")
            val address = value.getString("address")
            val port = value.getInt("port")
            val fingerprint = value.getString("spkiSha256")
            require(deviceId.matches(id) && serverName == "tv-$id.fileway.local") { "电视设备身份无效" }
            require(name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() }) { "电视名称无效" }
            require(port in 1..65535 && localAddress(address) != null) { "电视没有公布可访问的局域网地址" }
            val digest = Base64.getUrlDecoder().decode(fingerprint)
            require(digest.size == 32 && digest.take(16).joinToString("") { "%02x".format(it) } == id) { "电视身份指纹无效" }
            return TvDescriptor(id, name, serverName, address, port, fingerprint)
        }
    }
}

/** Numeric LAN addresses only. Never resolve an untrusted advertised hostname. */
fun localAddress(value: String): InetAddress? = runCatching {
    require(value.length in 3..64 && value.all { it in "0123456789abcdefABCDEF:." })
    val ip = InetAddress.getByName(value)
    val uniqueLocalV6 = ip.address.size == 16 && (ip.address[0].toInt() and 0xfe) == 0xfc
    require(!ip.isLoopbackAddress && !ip.isAnyLocalAddress && !ip.isMulticastAddress &&
        (ip.isSiteLocalAddress || ip.isLinkLocalAddress || uniqueLocalV6))
    ip
}.getOrNull()

data class TvInvitation(val device: TvDescriptor, val id: String, val secret: String, val expiresIn: Int) {
    fun json() = JSONObject().put("device", device.json()).put("id", id).put("secret", secret).put("expiresIn", expiresIn)
    fun uri(): String {
        val data = Base64.getUrlEncoder().withoutPadding().encodeToString(json().toString().toByteArray(Charsets.UTF_8))
        return Uri.Builder().scheme("fileway").authority("tv").appendPath("pair")
            .appendQueryParameter("v", REMOTE_PROTOCOL.toString()).appendQueryParameter("data", data).build().toString()
    }
    companion object {
        fun from(value: JSONObject): TvInvitation {
            val id = value.getString("id"); val secret = value.getString("secret"); val ttl = value.getInt("expiresIn")
            require(opaque.matches(id) && Base64.getUrlDecoder().decode(secret).size == 32 && ttl in 1..120) { "配对邀请无效或已过期" }
            return TvInvitation(TvDescriptor.from(value.getJSONObject("device")), id, secret, ttl)
        }
        fun parse(text: String): TvInvitation {
            require(text.toByteArray(Charsets.UTF_8).size <= 4096) { "配对码过长" }
            val uri = Uri.parse(text)
            require(uri.scheme == "fileway" && uri.host == "tv" && uri.path == "/pair" && uri.fragment == null && uri.userInfo == null)
            require(uri.queryParameterNames == setOf("v", "data") && uri.getQueryParameters("v").size == 1 && uri.getQueryParameters("data").size == 1)
            require(uri.getQueryParameter("v") == REMOTE_PROTOCOL.toString()) { "配对协议需要更新" }
            val bytes = Base64.getUrlDecoder().decode(requireNotNull(uri.getQueryParameter("data")))
            require(bytes.size <= 2048) { "配对内容过长" }
            return from(JSONObject(bytes.toString(Charsets.UTF_8)))
        }
    }
}

data class RemoteSnapshot(
    val connectionId: String, val sessionEpoch: Long, val mediaEpoch: Long, val revision: Long,
    val scene: String, val capabilities: JSONObject, val content: JSONObject,
) {
    companion object {
        fun from(value: JSONObject): RemoteSnapshot {
            require(value.getInt("protocol") == REMOTE_PROTOCOL && value.getString("type") == "snapshot")
            val connection = value.getString("connectionId")
            val session = value.getLong("sessionEpoch"); val media = value.getLong("mediaEpoch"); val revision = value.getLong("revision")
            require(opaque.matches(connection) && session >= 0 && media >= 0 && revision >= 0)
            val scene = value.getString("scene")
            require(scene in setOf("LOGIN", "BROWSE", "VIDEO", "AUDIO", "IMAGE"))
            return RemoteSnapshot(connection, session, media, revision, scene, value.getJSONObject("capabilities"), value.getJSONObject("content"))
        }
    }
}

/** Exact JSON-array domain agreed with clients/shared/core/remote.ProofMessage. */
fun proofMessage(purpose: String, device: String, controller: String, nonce: String): ByteArray {
    require(purpose in setOf("pair", "connect") && deviceId.matches(device) && opaque.matches(controller) && opaque.matches(nonce))
    return JSONArray(listOf("fileway.remote.v1", purpose, device, controller, nonce)).toString().toByteArray(Charsets.UTF_8)
}

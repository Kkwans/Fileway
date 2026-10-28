package io.github.kkwans.nasfilebrowser.data

import org.json.JSONObject

/** Authenticated server policy, never inferred from HTML or a failed request. */
data class ServerCapabilities(val authMethod: String, val enableExec: Boolean, val minimumPasswordLength: Int,
    val resourceWireOperations: Boolean = false) {
    val currentPasswordRequired get() = authMethod == "json"
    val passwordChangesAvailable get() = authMethod != "noauth"
    companion object {
        fun from(value: JSONObject): ServerCapabilities {
            val auth = value.get("authMethod") as? String ?: error("服务器认证方式无效")
            require(auth in setOf("json", "proxy", "hook", "noauth")) { "服务器认证方式暂不支持" }
            val execute = value.get("enableExec") as? Boolean ?: error("服务器命令能力无效")
            val length = (value.get("minimumPasswordLength") as? Number)?.toString()?.toIntOrNull()
            require(length != null && length > 0) { "服务器密码要求无效" }
            val wire = if (!value.has("resourceWireOperations")) false else
                value.get("resourceWireOperations") as? Boolean ?: error("服务器原始路径操作能力无效")
            return ServerCapabilities(auth, execute, length, wire)
        }
    }
}

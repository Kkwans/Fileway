package io.github.kkwans.nasfilebrowser.data

import android.util.Base64
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AccountIdentity(val id: Long, val username: String, val hostname: String, val permissions: ServerPermissions = ServerPermissions())
class ServiceException(val status: Int, message: String) : Exception(message)

enum class SessionHealthCode { RENEWAL_FAILED, EXPIRED, TOKEN_STORAGE_FAILED, ACCOUNT_CHANGED }
data class SessionHealth(val code: SessionHealthCode, val message: String)

/** One immutable server/account context. Never reuse a handle for another login. */
class NasSession private constructor(val profile: ServerProfile, val id: String, identity: AccountIdentity,
    private val native: suspend (JSONObject) -> Any?) {
    private val userId = identity.id
    @Volatile private var currentIdentity = identity
    val identity: AccountIdentity get() = currentIdentity
    private val tokenWrites = Mutex()
    private val mutableSessionHealth = MutableStateFlow<SessionHealth?>(null)
    val sessionHealth: StateFlow<SessionHealth?> = mutableSessionHealth.asStateFlow()
    @Volatile var businessRevision: Long = 0
        private set
    private var transportHealth: SessionHealth? = null
    private var storageFailed = false
    private fun publishHealth() {
        mutableSessionHealth.value = if (storageFailed) SessionHealth(SessionHealthCode.TOKEN_STORAGE_FAILED,
            "操作结果已保留，登录状态暂未保存，请检查本机存储") else transportHealth
    }
    private fun transportHealth(code: String) {
        transportHealth = when (code) {
            "expired" -> SessionHealth(SessionHealthCode.EXPIRED, "登录已过期，请重新连接")
            "renewal_failed" -> SessionHealth(SessionHealthCode.RENEWAL_FAILED, "登录续期暂不可用，已保留当前结果")
            else -> null
        }
        publishHealth()
    }
    /** Reads local core state only. No network requests and no session keepalive. */
    suspend fun synchronizeSession() {
        val state = native(JSONObject().put("op", "session_state").put("session", id)) as JSONObject
        tokenWrites.withLock {
            transportHealth(state.optString("health"))
            businessRevision = state.optLong("businessRevision")
            acceptToken(state.getString("token"))
        }
    }
    /** Renew a valid saved session on actual use, matching Web startup. */
    suspend fun refreshAuthentication() {
        try {
            val state = native(JSONObject().put("op", "renew_session").put("session", id)) as JSONObject
            tokenWrites.withLock { transportHealth(state.optString("health")); acceptToken(state.getString("token")) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (error.message == "session expired; sign in again") {
                transportHealth("expired"); throw ServiceException(401, "登录已过期，请重新连接")
            }
            transportHealth("renewal_failed")
            // A valid business GET can still succeed while renewal is temporarily unavailable.
        }
    }
    internal suspend fun recoverAuthentication(username: String, password: String) {
        val result = native(JSONObject().put("op", "recover_session").put("session", id)
            .put("expectedUser", userId).put("username", username).put("password", password)) as JSONObject
        if (result.getInt("status") != 200) throw ServiceException(result.getInt("status"), "保存的登录无法恢复，请重新输入账号密码")
        tokenWrites.withLock { transportHealth(""); acceptToken(result.getString("body")) }
    }
    /** One collector belongs to the bound lifecycle/account context and is canceled with it. */
    fun observeSessionState(): Flow<SessionHealth?> = flow {
        while (currentCoroutineContext().isActive) {
            try { synchronizeSession() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { transportHealth("renewal_failed") }
            emit(sessionHealth.value)
            delay(1000)
        }
    }.distinctUntilChanged()
    private var tokenStore: (suspend (String) -> Unit)? = null
    private var persistedToken: String? = null
    suspend fun persistTokens(store: suspend (String) -> Unit) {
        tokenWrites.withLock {
            check(tokenStore == null) { "Login storage is already bound" }
            tokenStore = store
        }
        token()
    }
    private suspend fun acceptToken(token: String): String {
        val principal = parseIdentity(token)
        if (principal.id != userId) {
            transportHealth = SessionHealth(SessionHealthCode.ACCOUNT_CHANGED, "登录账号已变化，请重新连接")
            publishHealth()
            error("服务器账号已变化，请重新登录")
        }
        currentIdentity = principal
        tokenStore?.let { save ->
            if (persistedToken != token) {
                try {
                    save(token)
                    persistedToken = token
                    storageFailed = false
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { storageFailed = true }
                publishHealth()
            }
        }
        return token
    }
    suspend fun token(): String = tokenWrites.withLock {
        acceptToken(native(JSONObject().put("op", "token").put("session", id)) as String)
    }
    private suspend fun synchronizeAcknowledgement(result: JSONObject) {
        if (result.has("sessionHealth")) transportHealth(result.optString("sessionHealth"))
        try { token() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            if (transportHealth?.code != SessionHealthCode.ACCOUNT_CHANGED) transportHealth("renewal_failed")
        }
    }
    suspend fun permissions(): ServerPermissions = parseIdentity(token()).permissions
    fun command(path: String, wirePath: String, command: String): Flow<ShellOutput> =
        shellCommandFlow(path, wirePath, command, identity = { token(); Unit }) { request -> native(request.put("session", id)) }
    suspend fun accountProfile(): AccountProfile = AccountProfile.from(request("GET", "/api/users/${identity.id}")).also {
        check(it.id == identity.id) { "服务器返回了其他账号的资料" }
    }
    suspend fun clientCapabilities(): ServerCapabilities = ServerCapabilities.from(request("GET", "/api/client-capabilities"))
    suspend fun updateOwnAccount(patch: JSONObject, currentPassword: String? = null): AccountWriteAcknowledgement {
        val fields = patch.keys().asSequence().toList()
        require(fields.isNotEmpty() && fields.all { it in ACCOUNT_PREFERENCE_FIELDS || it == "password" })
        require("password" !in fields || fields.size == 1) { "密码须单独保存" }
        val body = JSONObject().put("what", "user").put("which", JSONArray(fields))
            .put("data", JSONObject(patch.toString()).put("id", identity.id))
        currentPassword?.let { body.put("current_password", it) }
        val result = native(JSONObject().put("op", "request").put("session", id).put("method", "PUT")
            .put("endpoint", "/api/users/${identity.id}").put("body", body)) as JSONObject
        val status = result.getInt("status")
        if (status != 200) throw ServiceException(status, "账户更改未获服务器确认")
        // A later local token-storage error cannot erase a known HTTP acknowledgement.
        return try { synchronizeAcknowledgement(result); AccountWriteAcknowledgement(tokenStorageFailed = storageFailed || transportHealth != null) }
        catch (error: Exception) { if (error is CancellationException) throw error; AccountWriteAcknowledgement(tokenStorageFailed = true) }
    }
    private suspend fun response(method: String, endpoint: String, body: JSONObject? = null, accepted: Set<Int> = setOf(200), statusTextBody: Boolean = false, rawBody: ByteArray? = null): String {
        val command = JSONObject().put("op", "request").put("session", id).put("method", method).put("endpoint", endpoint)
        body?.let { command.put("body", it) }
        rawBody?.let { command.put("bodyBase64", Base64.encodeToString(it, Base64.NO_WRAP)) }
        val result = native(command) as JSONObject
        when (result.getInt("status")) {
            in accepted -> {
                synchronizeAcknowledgement(result)
                val text = result.getString("body")
                val expected = when (result.getInt("status")) { 200 -> "200 OK"; 201 -> "201 Created"; 202 -> "202 Accepted"; 204 -> "204 No Content"; else -> null }
                return if (statusTextBody && expected != null && text.trim() == expected) "" else text
            }
            401 -> { transportHealth("expired"); throw ServiceException(401, "登录已过期，请重新登录") }
            403 -> throw ServiceException(403, "当前账号没有访问权限")
            404 -> throw ServiceException(404, "文件、目录或服务功能不存在")
            400 -> throw ServiceException(400, "输入内容无效，请检查名称、路径或操作选项")
            409 -> throw ServiceException(409, "数据状态已变化或目标已存在，请刷新后重试")
            else -> throw ServiceException(result.getInt("status"), "服务器暂时无法完成请求，请重试")
        }
    }
    suspend fun request(method: String, endpoint: String, body: JSONObject? = null) = JSONObject(response(method, endpoint, body))
    suspend fun action(method: String, endpoint: String, body: JSONObject? = null): JSONObject {
        val text = response(method, endpoint, body, setOf(200, 201, 202, 204), statusTextBody = true)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
    suspend fun rawResource(method: String, wire: String, bytes: ByteArray, expectedModified: String? = null, expectedSize: Long? = null): JSONObject {
        require(method in setOf("PUT", "POST") && bytes.size <= 10 * 1024 * 1024)
        require(wire.startsWith('/') && wire != "/" && !wire.endsWith('/'))
        resourceWireBytes(wire)
        val query = if (method == "POST") "override=false" else {
            require(!expectedModified.isNullOrEmpty() && expectedSize != null && expectedSize >= 0) { "缺少原文件版本，请重新读取" }
            "conditional=true&expectedSize=$expectedSize&expectedModified=" + java.net.URLEncoder.encode(expectedModified, "UTF-8")
        }
        val text = response(method, "/api/resources$wire?$query", accepted = setOf(200, 201, 202, 204), statusTextBody = true, rawBody = bytes)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
    suspend fun spriteMetadata(path: String): JSONObject {
        require(path.startsWith('/'))
        val query = java.net.URLEncoder.encode(path, "UTF-8")
        return JSONObject(response("GET", "/api/media/sprite?path=$query", accepted = setOf(200, 202)))
    }
    suspend fun assetLease(endpoint: String): PreviewLease {
        require(endpoint.startsWith("/api/") && !endpoint.startsWith("//"))
        token()
        val url = native(JSONObject().put("op", "asset").put("session", id).put("endpoint", endpoint)) as String
        return PreviewLease(url, id) { native(JSONObject().put("op", "revoke").put("url", url)); Unit }
    }
    suspend fun spriteImage(path: String): PreviewLease {
        require(path.startsWith('/'))
        token()
        // The API's returned URL is never trusted as another origin. The existing
        // authenticated broker supplies this same-service asset, including tsnet.
        val endpoint = "/api/media/sprite.jpg?path=" + java.net.URLEncoder.encode(path, "UTF-8")
        val url = native(JSONObject().put("op", "asset").put("session", id).put("endpoint", endpoint)) as String
        return PreviewLease(url, id) { native(JSONObject().put("op", "revoke").put("url", url)); Unit }
    }
    suspend fun array(endpoint: String) = JSONArray(response("GET", endpoint))
    suspend fun resourceBatch(paths: List<String>, wirePaths: List<String>? = null): JSONArray {
        require(paths.isNotEmpty() && paths.size <= 500 && (wirePaths == null || wirePaths.size == paths.size))
        val body = JSONObject()
        if (wirePaths == null) body.put("paths", JSONArray(paths)) else {
            val targets = paths.indices.map { index -> recentAccessRecordTarget(io.github.kkwans.nasfilebrowser.app.ResourceRef(paths[index], wirePaths[index], "tag", false, "", 0)) }
            body.put("wirePaths", JSONArray(targets.map { it.wirePath }))
            if (targets.all { it.legacyCompatible }) body.put("paths", JSONArray(targets.map { it.path }))
        }
        // Both JNI envelopes and this readonly endpoint are bounded at 1 MiB.
        // Split before sending, preserving order without retrying any request.
        if (body.toString().toByteArray(Charsets.UTF_8).size > 768 * 1024) {
            require(paths.size > 1) { "资源路径过长，无法读取元数据" }
            val middle = paths.size / 2
            val left = resourceBatch(paths.take(middle), wirePaths?.take(middle))
            val right = resourceBatch(paths.drop(middle), wirePaths?.drop(middle))
            return JSONArray().apply { for (rows in listOf(left, right)) for (index in 0 until rows.length()) put(rows.get(index)) }
        }
        return JSONArray(response("POST", "/api/resources/batch", body))
    }
    fun search(path: String, wirePath: String, query: String, scope: SearchScope): Flow<SearchUpdate> =
        searchFlow(path, wirePath, query, scope, identity = { token(); Unit }) { command -> native(command.put("session", id)) }
    suspend fun lease(path: String, wirePath: String, cacheKey: String = ""): String {
        token()
        return native(JSONObject().put("op", "lease").put("cacheKey", cacheKey).put("session", id).put("path", path).put("wirePath", wirePath)) as String
    }
    suspend fun preview(path: String, wirePath: String, contain: Boolean = false): PreviewLease {
        token()
        val url = native(JSONObject().put("op", "preview").put("scope", if (contain) "contain" else "").put("session", id).put("path", path).put("wirePath", wirePath)) as String
        return PreviewLease(url, id) { native(JSONObject().put("op", "revoke").put("url", url)); Unit }
    }
    suspend fun image(path: String, wirePath: String, quality: ImageQuality): PreviewLease {
        token()
        val url = if (quality == ImageQuality.ORIGINAL || quality == ImageQuality.HIGH) lease(path, wirePath)
        else {
            val wire = wirePath.ifEmpty { path.split('/').joinToString("/") { android.net.Uri.encode(it) } }
            val endpoint = if (quality == ImageQuality.MEDIUM) "/api/preview/big$wire" else "/api/preview/thumb$wire?fit=contain"
            native(JSONObject().put("op", "asset").put("session", id).put("endpoint", endpoint)) as String
        }
        return PreviewLease(url, id) { native(JSONObject().put("op", "revoke").put("url", url)); Unit }
    }
    suspend fun close() { native(JSONObject().put("op", "close_session").put("session", id)) }

    suspend fun upload(wirePath: String, size: Long, transferId: String, protocol: String, overwrite: Boolean, resume: Boolean,
        metadata: JSONObject = JSONObject()): PreviewLease {
        token()
        val options = JSONObject().put("protocol", protocol).put("size", size).put("transferId", transferId)
            .put("overwrite", overwrite).put("resume", resume).put("metadata", metadata)
        val url = native(JSONObject().put("op", "upload_lease").put("session", id).put("wirePath", wirePath).put("upload", options)) as String
        return PreviewLease(url, id) { native(JSONObject().put("op", "revoke").put("url", url)); Unit }
    }
    suspend fun uploadStatistics(lease: PreviewLease): JSONObject {
        check(lease.scope == id) { "上传来源已切换" }
        token()
        return native(JSONObject().put("op", "upload_stats").put("session", id).put("url", lease.url)) as JSONObject
    }

    companion object {
        internal fun issuedAt(token: String): Long = runCatching {
            val payload = token.split('.')[1]
            require(payload.length <= 262_144)
            JSONObject(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP).toString(Charsets.UTF_8)).optLong("iat")
        }.getOrDefault(0)
        fun parseIdentity(token: String): AccountIdentity {
            try {
                val parts = token.trim().split('.')
                require(parts.size == 3 && parts[1].length <= 262_144)
                val payload = JSONObject(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP).toString(Charsets.UTF_8))
                val user = payload.getJSONObject("user")
                val rawId = user.get("id")
                require(rawId is Number)
                val id = rawId.toString().toLongOrNull() ?: error("invalid id")
                val name = user.get("username") as? String ?: error("invalid username")
                require(id >= 0 && name.isNotBlank())
                val perm = user.optJSONObject("perm")
                return AccountIdentity(id, name, payload.optJSONObject("instance")?.optString("hostname").orEmpty(),
                    ServerPermissions(perm != null, perm?.optBoolean("admin") == true, perm?.optBoolean("create") == true,
                        perm?.optBoolean("delete") == true, perm?.optBoolean("modify") == true, perm?.optBoolean("download") == true,
                        perm?.optBoolean("rename") == true, perm?.optBoolean("execute") == true))
            } catch (_: Exception) { error("服务器返回了不支持的账号格式") }
        }
        suspend fun login(profile: ServerProfile, username: String, password: String,
            native: suspend (JSONObject) -> Any? = NativeTransport::call): NasSession {
            val id = native(JSONObject().put("op", "open").put("baseUrl", profile.address).put("network", profile.network.name.lowercase())) as String
            try {
                val result = native(JSONObject().put("op", "login").put("session", id).put("username", username).put("password", password)) as JSONObject
                when (result.getInt("status")) {
                    200 -> return NasSession(profile, id, parseIdentity(result.getString("body")), native)
                    401, 403 -> throw ServiceException(result.getInt("status"), "账号或密码不正确")
                    else -> error("登录失败，请检查服务器地址和账号")
                }
            } catch (error: Exception) {
                runCatching { withContext(NonCancellable) { native(JSONObject().put("op", "close_session").put("session", id)) } }.onFailure { error.addSuppressed(it) }
                throw error
            }
        }
        suspend fun restore(profile: ServerProfile, token: String, expectedUser: Long,
            native: suspend (JSONObject) -> Any? = NativeTransport::call): NasSession {
            val identity = parseIdentity(token)
            check(identity.id == expectedUser) { "保存的账号不匹配，请重新登录" }
            val id = native(JSONObject().put("op", "open").put("baseUrl", profile.address).put("network", profile.network.name.lowercase()).put("token", token)) as String
            return NasSession(profile, id, identity, native)
        }
    }
}

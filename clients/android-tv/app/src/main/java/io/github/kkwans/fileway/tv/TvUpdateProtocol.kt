package io.github.kkwans.fileway.tv

import io.github.kkwans.nasfilebrowser.data.NasSession
import io.github.kkwans.nasfilebrowser.data.ServiceException
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

internal const val TV_UPDATE_PACKAGE = "io.github.kkwans.fileway.tv"
internal const val TV_UPDATE_SIGNER = "ad35201bcbbcba94754447da9f6e7f369c2a4264bfd5e179194a3cd0b0a0c4a9"
internal const val TV_UPDATE_DOMAIN = "Fileway-TV-Update/v1\u0000"
internal const val TV_UPDATE_MAX_BYTES = 128L * 1024 * 1024
private const val MANIFEST_LIMIT = 64 * 1024
private const val SIGNATURE_LIMIT = 1024
private val sha256 = Regex("[0-9a-f]{64}")
private val revision = Regex("[0-9a-f]{40}")
private val updateId = Regex("[A-Za-z0-9_-]{32}")
private const val RELEASE_VERSION = "[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}"
private val releaseVersion = Regex(RELEASE_VERSION)
private val versionName = Regex("$RELEASE_VERSION(-[A-Za-z0-9][A-Za-z0-9.-]{0,31})?")
private val supportedAbis = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
private val manifestFields = setOf("schema", "product", "applicationId", "tag", "channel", "versionName",
    "versionCode", "minSdk", "abis", "apkName", "size", "sha256", "signerSha256", "sourceRevision", "notes")

internal enum class TvUpdateChannel(val wire: String, val title: String) {
    STABLE("stable", "正式版"), PREVIEW("preview", "预览版")
}

internal data class TvUpdateManifest(
    val channel: TvUpdateChannel,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val abis: Set<String>,
    val size: Long,
    val sha256: String,
    val signerSha256: String,
    val notes: String,
    val manifestSha256: String,
) {
    val etag: String get() = "\"$sha256\""
}

internal data class TvUpdateOffer(val id: String, val manifest: TvUpdateManifest)
internal data class TvUpdateDevice(val versionCode: Long, val api: Int, val abis: Set<String>)
internal data class TvUpdateDirectory(val offers: List<TvUpdateOffer>, val rejected: Boolean)
internal data class TvUpdateRemoteStatus(val id: String, val ready: Boolean, val received: Long, val total: Long?)
internal class TvUpdateError(val userMessage: String, val recheck: Boolean = false) : Exception(userMessage)

internal fun tvUpdateDirectoryIncomplete(response: JSONObject): Boolean =
    if (response.has("incomplete")) response.get("incomplete") as? Boolean
        ?: throw IllegalArgumentException("Invalid update directory status") else false

internal fun ByteArray.tvUpdateSha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

private fun JSONObject.strictString(key: String): String = get(key) as? String ?: error("Invalid string")
private fun JSONObject.strictLong(key: String): Long = when (val value = get(key)) {
    is Int -> value.toLong()
    is Long -> value
    else -> error("Invalid integer")
}

private fun decodeBounded(value: String, limit: Int): ByteArray {
    require(value.length <= ((limit + 2) / 3) * 4)
    return Base64.getDecoder().decode(value).also { require(it.size in 1..limit) }
}

private fun parseManifest(raw: ByteArray): JSONObject {
    val json = JSONObject()
    val reader = JSONTokener(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString())
    require(reader.nextClean() == '{')
    repeat(manifestFields.size) { index ->
        require(reader.nextClean() == '"')
        val field = reader.nextString('"')
        require(field in manifestFields && !json.has(field) && reader.nextClean() == ':')
        json.put(field, reader.nextValue())
        require(reader.nextClean() == if (index == manifestFields.size - 1) '}' else ',')
    }
    require(reader.nextClean() == '\u0000')
    return json
}

internal fun verifyTvUpdateManifest(
    raw: ByteArray,
    signature: ByteArray,
    publicKey: PublicKey,
    signerPin: String,
): TvUpdateManifest {
    require(raw.size in 1..MANIFEST_LIMIT && signature.size in 1..SIGNATURE_LIMIT)
    require(publicKey.algorithm == "RSA")
    require(Signature.getInstance("SHA256withRSA").run {
        initVerify(publicKey)
        update(TV_UPDATE_DOMAIN.toByteArray(Charsets.US_ASCII))
        update(raw)
        verify(signature)
    })
    val json = parseManifest(raw)
    require(json.strictLong("schema") == 1L && json.strictString("product") == "android-tv")
    require(json.strictString("applicationId") == TV_UPDATE_PACKAGE)
    val tag = json.strictString("tag")
    val tagVersion = tag.removePrefix("android-tv-")
    require(tag.startsWith("android-tv-") && releaseVersion.matches(tagVersion))
    require(json.strictString("apkName") == "Fileway-TV-$tagVersion.apk")
    val channel = TvUpdateChannel.entries.single { it.wire == json.strictString("channel") }
    val version = json.strictString("versionName").also {
        require(versionName.matches(it) && it.substringBefore('-') == tagVersion)
    }
    val code = json.strictLong("versionCode").also { require(it > 0) }
    val minSdk = json.strictLong("minSdk").also { require(it in 28..1000) }.toInt()
    val rawAbis = json.getJSONArray("abis")
    require(rawAbis.length() in 1..supportedAbis.size)
    val abis = (0 until rawAbis.length()).map { rawAbis.get(it) as String }.toSet()
    require(abis.size == rawAbis.length() && supportedAbis.containsAll(abis))
    val size = json.strictLong("size").also { require(it in 1..TV_UPDATE_MAX_BYTES) }
    val digest = json.strictString("sha256").also { require(sha256.matches(it)) }
    require(json.strictString("signerSha256") == signerPin)
    require(revision.matches(json.strictString("sourceRevision")))
    val notes = json.strictString("notes").also { require(it.toByteArray(Charsets.UTF_8).size <= 32 * 1024) }
    return TvUpdateManifest(channel, version, code, minSdk, abis, size, digest, signerPin, notes, raw.tvUpdateSha256())
}

internal fun tvUpdateOffers(
    response: JSONObject,
    device: TvUpdateDevice,
    publicKey: PublicKey,
    signerPin: String,
): TvUpdateDirectory {
    require(response.toString().toByteArray(Charsets.UTF_8).size <= 192 * 1024)
    val incomplete = tvUpdateDirectoryIncomplete(response)
    val rows = response.getJSONArray("offers")
    require(rows.length() <= TvUpdateChannel.entries.size)
    val verified = (0 until rows.length()).mapNotNull { index ->
        runCatching {
            val row = rows.getJSONObject(index)
            val id = row.strictString("offerId").also { require(updateId.matches(it)) }
            val manifest = verifyTvUpdateManifest(
                decodeBounded(row.strictString("manifestBase64"), MANIFEST_LIMIT),
                decodeBounded(row.strictString("signatureBase64"), SIGNATURE_LIMIT),
                publicKey, signerPin,
            )
            require(manifest.versionCode > device.versionCode && manifest.minSdk <= device.api)
            require(manifest.abis.any(device.abis::contains))
            TvUpdateOffer(id, manifest)
        }.getOrNull()
    }
    val distinct = verified.filter { offer ->
        verified.count { it.id == offer.id || it.manifest.channel == offer.manifest.channel } == 1
    }
    return TvUpdateDirectory(distinct.sortedBy { it.manifest.channel }, incomplete || distinct.size != rows.length())
}

internal fun tvUpdateRemoteStatus(json: JSONObject, manifest: TvUpdateManifest, expectedId: String? = null): TvUpdateRemoteStatus {
    val id = json.strictString("downloadId")
    require(updateId.matches(id) && (expectedId == null || id == expectedId))
    val state = json.strictString("state")
    if (state == "failed") throw when (json.optString("code")) {
        "UPDATE_EXPIRED" -> TvUpdateError("更新已失效，请重新检查", recheck = true)
        "UPSTREAM_RATE_LIMITED" -> TvUpdateError("发布服务请求过多，请稍后重试")
        "UPDATE_ARTIFACT_INVALID" -> TvUpdateError("服务器校验安装包失败，请重新检查", recheck = true)
        "UPDATE_CACHE_UNAVAILABLE" -> TvUpdateError("服务器更新缓存暂不可用，请稍后重试")
        "UPDATE_BUSY" -> TvUpdateError("服务器正在处理其他更新，请稍后重试")
        else -> TvUpdateError("服务器准备更新失败，请重试")
    }
    require(state == "preparing" || state == "ready")
    val total = if (json.isNull("totalBytes")) null else json.strictLong("totalBytes")
    val received = json.strictLong("receivedBytes")
    require((total == null || total == manifest.size) && received in 0..manifest.size)
    require(state != "ready" || total == manifest.size && received == manifest.size)
    return TvUpdateRemoteStatus(id, state == "ready", received, total)
}

internal class TvUpdateServer(private val session: NasSession) {
    suspend fun supportsUpdates(): Boolean = try {
        val version = session.request("GET", "/api/client-capabilities").opt("tvUpdateProxyVersion")
        version == 1 || version == 1L
    } catch (failure: ServiceException) {
        if (failure.status == 404) false else throw failure
    }

    suspend fun directory(device: TvUpdateDevice): JSONObject {
        val abis = device.abis.filter { it in supportedAbis }.take(supportedAbis.size)
        if (abis.isEmpty()) return JSONObject().put("offers", org.json.JSONArray())
        val query = "versionCode=${device.versionCode}&api=${device.api}" + abis.joinToString("") { "&abi=$it" }
        return session.request("GET", "$ROOT?$query")
    }

    suspend fun prepare(offer: TvUpdateOffer): TvUpdateRemoteStatus = tvUpdateRemoteStatus(
        session.action("POST", "$ROOT/downloads", JSONObject().put("offerId", offer.id)), offer.manifest,
    )

    suspend fun status(id: String, manifest: TvUpdateManifest): TvUpdateRemoteStatus =
        tvUpdateRemoteStatus(session.request("GET", "$ROOT/downloads/$id"), manifest, id)

    suspend fun content(id: String) = session.assetLease("$ROOT/downloads/$id/content")

    private companion object { const val ROOT = "/api/app-updates/tv" }
}

/** Returns the only safe write offset for this response. */
internal fun tvUpdateWriteOffset(
    status: Int,
    etag: String?,
    length: Long?,
    range: String?,
    offset: Long,
    manifest: TvUpdateManifest,
): Long {
    require(etag == manifest.etag)
    return when (status) {
        200 -> {
            require(length == manifest.size && range == null)
            0L
        }
        206 -> {
            require(offset in 1 until manifest.size)
            require(length == manifest.size - offset && range == "bytes $offset-${manifest.size - 1}/${manifest.size}")
            offset
        }
        else -> error("Unexpected response")
    }
}

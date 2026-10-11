package io.github.kkwans.nasfilebrowser.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal class ReleaseClient {
    private val client = OkHttpClient.Builder().cache(null).followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    suspend fun releases(): List<AppRelease> = withContext(Dispatchers.IO) {
        val call = client.newCall(Request.Builder().url(UpdatePolicy.RELEASES)
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2026-03-10")
            .header("User-Agent", "Fileway-Android-Update").build())
        val response = suspendCancellableCoroutine<Response> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("无法连接更新服务，请检查网络后重试", e)) }
                override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
            })
        }
        response.use {
            check(it.code !in listOf(403, 429)) { "更新服务暂时限制请求，请稍后重试" }
            check(it.isSuccessful) { "更新服务暂不可用（${it.code}），请稍后重试" }
            val bytes = it.body?.byteStream()?.use { stream ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer); if (count < 0) break
                    check(output.size() + count <= 2 * 1024 * 1024) { "更新信息过大，请稍后重试" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("更新信息为空")
            parseReleases(bytes.toString(Charsets.UTF_8))
        }
    }
}

internal fun parseReleases(json: String): List<AppRelease> {
    val list = JSONArray(json)
    return (0 until list.length()).mapNotNull { index ->
        val item = list.optJSONObject(index) ?: return@mapNotNull null
        if (item.optBoolean("draft", true)) return@mapNotNull null
        val tag = item.optString("tag_name"); val version = UpdatePolicy.tagVersion(tag) ?: return@mapNotNull null
        val assets = item.optJSONArray("assets") ?: return@mapNotNull null
        AppRelease(tag, version, item.optString("body").take(64 * 1024), item.optBoolean("prerelease", true),
            (0 until assets.length()).mapNotNull { n -> assets.optJSONObject(n)?.takeIf { it.optString("state") == "uploaded" }?.let {
                UpdateAsset(it.optLong("id"), it.optString("name"), it.optString("browser_download_url"), it.optLong("size"))
            } }, previewRevision = UpdatePolicy.tagRevision(tag)).takeIf { UpdatePolicy.releaseVersion(it) != null }
    }
}

internal fun AppUpdate.serialize(): String = JSONObject().put("tag_name", release.tag).put("draft", false)
    .put("prerelease", release.preview).put("body", release.notes).put("assets", JSONArray().put(JSONObject()
        .put("id", asset.id).put("name", asset.name).put("browser_download_url", asset.url).put("size", asset.size).put("state", "uploaded"))).toString()

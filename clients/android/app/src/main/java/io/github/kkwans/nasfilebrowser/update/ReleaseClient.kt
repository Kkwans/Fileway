package io.github.kkwans.nasfilebrowser.update

import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal data class ReleaseCatalog(val releases: List<AppRelease>, val complete: Boolean, val warning: String? = null)

internal class ReleaseClient {
    private val client = OkHttpClient.Builder().cache(null).followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private data class Page(val releases: List<AppRelease>, val hasNext: Boolean, val bytes: Int)

    suspend fun catalog(): ReleaseCatalog = withContext(Dispatchers.IO) {
        val releases = mutableListOf<AppRelease>()
        try {
            withTimeout(60_000) {
                var bytes = 0
                for (pageNumber in 1..5) {
                    val result = page(pageNumber, minOf(2 * 1024 * 1024, 8 * 1024 * 1024 - bytes))
                    releases.addAll(result.releases); bytes += result.bytes
                    if (!result.hasNext) return@withTimeout ReleaseCatalog(releases.distinctBy { it.tag }, true)
                }
                ReleaseCatalog(releases.distinctBy { it.tag }, false, "历史版本较多，发布说明尚未全部读取")
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            if (releases.isEmpty()) throw IOException("读取更新信息超时，请稍后重试", timeout)
            ReleaseCatalog(releases.distinctBy { it.tag }, false, "部分历史说明读取超时，可稍后重试")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (releases.isEmpty()) throw error
            ReleaseCatalog(releases.distinctBy { it.tag }, false, "部分历史说明暂未读取，可稍后重试")
        }
    }

    /** The continuation owns the whole response, including body reads, so cancellation closes it. */
    private suspend fun page(number: Int, limit: Int): Page {
        check(limit > 0) { "更新信息超过读取预算" }
        val call = client.newCall(Request.Builder().url(UpdatePolicy.RELEASES + "&page=$number")
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2026-03-10")
            .header("User-Agent", "Fileway-Android-Update").build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("无法连接更新服务，请检查网络后重试", e))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            check(it.code !in listOf(403, 429)) { "更新服务暂时限制请求，请稍后重试" }
                            check(it.isSuccessful) { "更新服务暂不可用（${it.code}），请稍后重试" }
                            val bytes = it.body?.byteStream()?.use { stream ->
                                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                                while (continuation.isActive) {
                                    val count = stream.read(buffer); if (count < 0) break
                                    check(output.size() + count <= limit) { "更新信息超过读取预算" }
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            } ?: error("更新信息为空")
                            Page(parseReleases(bytes.toString(Charsets.UTF_8)), it.header("Link").orEmpty().contains("rel=\"next\""), bytes.size)
                        }
                        if (continuation.isActive) continuation.resume(value) { _, _, _ -> }
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }
}

internal fun parseReleases(json: String): List<AppRelease> {
    val list = JSONArray(json)
    return (0 until list.length()).mapNotNull { index ->
        val item = list.optJSONObject(index) ?: return@mapNotNull null
        if (item.optBoolean("draft", true)) return@mapNotNull null
        val tag = item.optString("tag_name"); val version = UpdatePolicy.tagVersion(tag) ?: return@mapNotNull null
        val assets = item.optJSONArray("assets") ?: JSONArray()
        val notes = if (item.isNull("body")) "" else item.optString("body")
        AppRelease(tag, version, notes.take(64 * 1024), item.optBoolean("prerelease", true),
            (0 until assets.length()).mapNotNull { n -> assets.optJSONObject(n)?.takeIf { it.optString("state") == "uploaded" }?.let {
                UpdateAsset(it.optLong("id"), it.optString("name"), it.optString("browser_download_url"), it.optLong("size"))
            } }, previewRevision = UpdatePolicy.tagRevision(tag), notesTruncated = notes.length > 64 * 1024 || item.optBoolean("fileway_notes_truncated", false)).takeIf { UpdatePolicy.releaseVersion(it) != null }
    }
}

internal fun AppUpdate.serialize(): String = JSONObject().put("tag_name", release.tag).put("draft", false)
    .put("prerelease", release.preview).put("body", release.notes).put("fileway_notes_truncated", release.notesTruncated).put("assets", JSONArray().put(JSONObject()
        .put("id", asset.id).put("name", asset.name).put("browser_download_url", asset.url).put("size", asset.size).put("state", "uploaded"))).toString()

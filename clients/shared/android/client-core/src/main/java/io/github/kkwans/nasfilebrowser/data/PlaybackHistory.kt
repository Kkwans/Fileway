package io.github.kkwans.nasfilebrowser.data

import org.json.JSONObject
import java.net.URLEncoder

data class RemotePlayback(val identity: String, val positionMs: Long, val durationMs: Long, val updatedAt: Long, val exists: Boolean)

/** Source positions are milliseconds locally and seconds on the existing NAS API. */
class PlaybackHistory(private val dao: PlaybackDao) {
    fun recent(account: AccountRecord) = dao.recent(account.key)
    suspend fun recentRemote(api: NasSession, account: AccountRecord): List<PlaybackSnapshot> {
        val rows = api.array("/api/media/playback/recent?limit=100")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val file = io.github.kkwans.nasfilebrowser.app.ResourceRef(row.getString("path"), row.getString("wirePath"),
                row.getString("name"), false, row.optString("type", "video"), 0)
            val target = recentAccessRecordTarget(file)
            val value = parse(row)
            PlaybackSnapshot(account.key, target.wirePath, value.identity, target.path, target.wirePath, file.name,
                value.positionMs, value.durationMs, value.updatedAt, ProgressSync.SYNCED)
        }.distinctBy { it.resourceKey }.sortedByDescending { it.updatedAt }
    }
    suspend fun local(account: AccountRecord, key: String, identity: String) = dao.snapshot(account.key, key, identity)
    suspend fun save(snapshot: PlaybackSnapshot) {
        require(snapshot.identity.isNotBlank() && snapshot.positionMs >= 0 && snapshot.durationMs >= 0)
        dao.save(snapshot.copy(positionMs = if (snapshot.durationMs > 0) snapshot.positionMs.coerceAtMost(snapshot.durationMs) else snapshot.positionMs))
    }
    suspend fun remote(api: NasSession, path: String, wirePath: String): RemotePlayback {
        val encoded = if (wirePath.isNotEmpty()) wirePath.replace("+", "%2B") else URLEncoder.encode(path, "UTF-8")
        return parse(api.request("GET", "/api/media/playback?path=$encoded"))
    }
    suspend fun synchronize(api: NasSession, snapshot: PlaybackSnapshot): PlaybackSnapshot {
        require(snapshot.accountKey == "${api.profile.id}/${api.profile.sourceRevision}/${api.identity.id}") { "播放来源账号不匹配" }
        if (!wireMatchesPath(snapshot.path, snapshot.wirePath)) return mark(snapshot, ProgressSync.UNSUPPORTED)
        val current = remote(api, snapshot.path, snapshot.wirePath)
        if (current.identity != snapshot.identity) return mark(snapshot, ProgressSync.IDENTITY_CHANGED)
        val result = parse(api.request("PUT", "/api/media/playback", JSONObject()
            .put("path", snapshot.path).put("position", snapshot.positionMs / 1000.0).put("duration", snapshot.durationMs / 1000.0)))
        // The existing API has no expected-identity mutation argument. Check
        // its response too; never relabel an old snapshot as the replacement.
        if (result.identity != snapshot.identity) {
            // Guard this replacement identity against the old progress written
            // during the API's stat-to-save race, without deleting other clients' state.
            dao.guard(snapshot.copy(identity = result.identity, positionMs = 0, durationMs = result.durationMs, updatedAt = System.currentTimeMillis(), sync = ProgressSync.IDENTITY_CHANGED))
            return mark(snapshot, ProgressSync.IDENTITY_CHANGED)
        }
        return mark(snapshot, ProgressSync.SYNCED)
    }
    suspend fun deleteLocal(account: AccountRecord, snapshots: List<PlaybackSnapshot>, clear: Boolean) {
        if (clear) dao.clear(account.key)
        else snapshots.forEach { require(it.accountKey == account.key); dao.remove(account.key, it.resourceKey, it.identity, it.updatedAt) }
    }
    private suspend fun mark(snapshot: PlaybackSnapshot, sync: ProgressSync): PlaybackSnapshot {
        dao.markSync(snapshot.accountKey, snapshot.resourceKey, snapshot.identity, snapshot.updatedAt, snapshot.positionMs, snapshot.durationMs, sync)
        return snapshot.copy(sync = sync)
    }
    companion object {
        fun resume(local: PlaybackSnapshot?, remote: RemotePlayback): Long {
            if (local?.identity == remote.identity && local.sync == ProgressSync.IDENTITY_CHANGED) return 0
            if (local?.identity == remote.identity && local.sync == ProgressSync.PENDING && local.updatedAt > remote.updatedAt) return local.positionMs
            return if (remote.exists) remote.positionMs else 0
        }
        fun wireMatchesPath(path: String, wire: String): Boolean {
            if (wire.isEmpty()) return true
            val bytes = wire.toByteArray(Charsets.UTF_8)
            val decoded = java.io.ByteArrayOutputStream()
            var index = 0
            while (index < bytes.size) {
                if (bytes[index] == '%'.code.toByte()) {
                    if (index + 2 >= bytes.size) return false
                    val value = String(bytes, index + 1, 2, Charsets.US_ASCII).toIntOrNull(16) ?: return false
                    decoded.write(value); index += 3
                } else decoded.write(bytes[index++].toInt())
            }
            return decoded.toByteArray().contentEquals(path.toByteArray(Charsets.UTF_8))
        }
        fun parse(value: JSONObject): RemotePlayback {
            val identity = value.getString("identity")
            val position = value.optDouble("position", 0.0); val duration = value.optDouble("duration", 0.0)
            require(identity.isNotBlank() && position.isFinite() && duration.isFinite() && position >= 0 && duration >= 0)
            require(position <= Long.MAX_VALUE / 1000.0 && duration <= Long.MAX_VALUE / 1000.0)
            return RemotePlayback(identity, (position * 1000).toLong(), (duration * 1000).toLong(), value.optLong("updatedAt"), value.optBoolean("exists"))
        }
    }
}

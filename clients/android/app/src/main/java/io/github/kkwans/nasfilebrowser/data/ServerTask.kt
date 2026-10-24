package io.github.kkwans.nasfilebrowser.data

import org.json.JSONArray
import org.json.JSONObject

data class ServerPermissions(val known: Boolean = false, val admin: Boolean = false, val create: Boolean = false,
    val delete: Boolean = false, val modify: Boolean = false, val download: Boolean = false, val rename: Boolean = false, val execute: Boolean = false)

val SERVER_TASK_TYPES = linkedMapOf("file.copy" to "复制文件", "file.move" to "移动文件", "file.delete.permanent" to "永久删除文件",
    "trash.delete.permanent" to "删除回收站项目", "trash.clear" to "清空回收站", "trash.size" to "统计回收站大小",
    "analysis.duplicates" to "查找重复文件", "analysis.duplicates.cleanup" to "清理重复文件", "analysis.storage" to "存储分析",
    "archive.extract" to "解压文件", "media.hls" to "媒体兼容处理", "media.transcode" to "媒体转码")

data class ServerTask(val id: String, val userId: Long, val owner: String, val type: String, val title: String, val status: String,
    val createdAt: Long, val startedAt: Long, val finishedAt: Long, val archivedAt: Long, val undoUntil: Long,
    val totalItems: Long, val processedItems: Long, val totalBytes: Long, val processedBytes: Long, val error: String,
    val sourcePath: String, val outputPath: String, val mediaPhase: String = "", val duration: Double = 0.0,
    val processedSeconds: Double = 0.0, val speed: Double = 0.0, val fps: Double = 0.0) {
    val active get() = status in setOf("queued", "running")
    val fileCategory get() = type in setOf("file.copy", "file.move")
    val pendingDeletion get() = type in setOf("file.delete.permanent", "trash.delete.permanent", "trash.clear") && undoUntil > 0 && status == "queued"
    val canRetry get() = archivedAt == 0L && type !in setOf("file.delete.permanent", "trash.delete.permanent") &&
        (status in setOf("failed", "interrupted") || status == "canceled" && type in setOf("file.copy", "file.move", "analysis.duplicates.cleanup", "media.hls", "media.transcode"))
    val canArchive get() = archivedAt == 0L && status in setOf("completed", "failed", "canceled", "interrupted")
    val statusLabel get() = when (status) { "queued" -> if (pendingDeletion) "等待删除，可撤销" else "排队中"; "running" -> "进行中"; "completed" -> "已完成"; "failed" -> "失败"; "canceled" -> "已取消"; "interrupted" -> "已中断"; else -> "未知状态" }
    val typeLabel get() = SERVER_TASK_TYPES[type] ?: type
    val progress: Float? get() {
        if (status == "completed") return 1f
        val raw = when {
            duration.isFinite() && duration > 0 && processedSeconds.isFinite() -> processedSeconds / duration
            totalBytes > 0 -> processedBytes.toDouble() / totalBytes
            totalItems > 0 -> processedItems.toDouble() / totalItems
            else -> return null
        }
        return raw.coerceIn(0.0, if (active) .99 else 1.0).toFloat()
    }
    companion object {
        fun from(row: JSONObject): ServerTask {
            val media = row.optJSONObject("media")
            return ServerTask(row.getString("id"), row.getLong("userId"), row.optString("ownerName"), row.getString("type"),
                row.optString("title"), row.getString("status"), row.optLong("createdAt"), row.optLong("startedAt"), row.optLong("finishedAt"),
                row.optLong("archivedAt"), row.optLong("undoUntil"), row.optLong("totalItems"), row.optLong("processedItems"), row.optLong("totalBytes"), row.optLong("processedBytes"),
                row.optString("error"), row.optString("sourcePath"), row.optString("outputPath"), media?.optString("phase").orEmpty(),
                media?.optDouble("durationSeconds", 0.0) ?: 0.0, media?.optDouble("processedSeconds", 0.0) ?: 0.0,
                media?.optDouble("speed", 0.0) ?: 0.0, media?.optDouble("fps", 0.0) ?: 0.0)
        }
    }
}
enum class TaskView(val label: String, val statuses: List<String>) {
    ALL("全部", emptyList()), ACTIVE("进行中", listOf("queued", "running")), ATTENTION("待处理", listOf("failed", "interrupted")),
    COMPLETED("已完成", listOf("completed")), CANCELED("已取消", listOf("canceled")), ARCHIVED("已删除", emptyList())
}
data class TaskFilter(val category: String = "background", val view: TaskView = TaskView.ALL, val text: String = "", val owner: String = "", val type: String = "",
    val from: Long = 0, val to: Long = 0) {
    fun query(cursor: String = "", limit: Int = 30): String {
        val values = linkedMapOf("category" to category, "archived" to (view == TaskView.ARCHIVED).toString(), "limit" to limit.toString())
        if (view.statuses.isNotEmpty()) values["status"] = view.statuses.joinToString(",")
        if (text.isNotBlank()) values["text"] = text
        if (owner.isNotEmpty()) values["user"] = owner
        if (type.isNotEmpty()) values["type"] = type
        if (cursor.isNotEmpty()) values["cursor"] = cursor
        if (from > 0) values["from"] = from.toString()
        if (to > 0) values["to"] = to.toString()
        return "/api/tasks?" + values.entries.joinToString("&") { it.key + "=" + java.net.URLEncoder.encode(it.value, "UTF-8") }
    }
    fun json(): JSONObject = JSONObject().put("category", category).put("archived", view == TaskView.ARCHIVED).put("statuses", JSONArray(view.statuses))
        .put("text", text).put("user", owner).put("type", type).put("from", from).put("to", to)
}

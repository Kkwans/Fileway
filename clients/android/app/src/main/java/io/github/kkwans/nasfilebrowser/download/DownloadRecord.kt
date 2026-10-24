package io.github.kkwans.nasfilebrowser.download

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Download ownership survives profile removal; deleting a profile never deletes local files. */
@Entity(tableName = "downloads", indices = [Index(value = ["jobId"], unique = true), Index(value = ["accountKey", "createdAt"])])
data class DownloadRecord(@PrimaryKey val id: String, val jobId: Int, val accountKey: String, val profileId: String,
    val sourceRevision: Long, val path: String, val wirePath: String, val name: String, val type: String, val expectedSize: Long,
    val modified: String, val identity: String, val sourceLabel: String, val treeUri: String, val localUri: String = "",
    val status: String = "queued", val downloaded: Long = 0, val createdAt: Long, val updatedAt: Long,
    val generation: Long = 0, val error: String = "", val positionMs: Long = 0, val durationMs: Long = 0,
    @ColumnInfo(defaultValue = "''") val relativeDirectory: String = "",
    @ColumnInfo(defaultValue = "0") val activeMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val timingComplete: Boolean = true) {
    val complete get() = status == "completed"
    val active get() = status in setOf("queued", "running")
    val zipExport get() = type == ZIP_EXPORT_TYPE
}

@Dao interface DownloadDao {
    @Query("UPDATE downloads SET activeMillis = activeMillis + :millis WHERE id = :id")
    suspend fun addRuntime(id: String, millis: Long)
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC") fun observe(): Flow<List<DownloadRecord>>
    @Query("SELECT * FROM downloads WHERE id = :id") suspend fun get(id: String): DownloadRecord?
    @Query("SELECT COALESCE(MAX(jobId), 7300000) FROM downloads") suspend fun lastJobId(): Int
    @Insert suspend fun insert(record: DownloadRecord)
    @Query("UPDATE downloads SET status = 'running', generation = generation + 1, error = '', expectedSize = CASE WHEN type = 'fileway.zip-export' THEN -1 ELSE expectedSize END, downloaded = CASE WHEN type = 'fileway.zip-export' THEN 0 ELSE downloaded END, updatedAt = :now WHERE id = :id AND status IN ('queued', 'interrupted', 'running')")
    suspend fun claim(id: String, now: Long): Int
    @Query("UPDATE downloads SET localUri = :uri, updatedAt = :now WHERE id = :id AND generation = :generation AND status = 'running'")
    suspend fun allocated(id: String, generation: Long, uri: String, now: Long): Int
    @Query("UPDATE downloads SET downloaded = :bytes, updatedAt = :now WHERE id = :id AND generation = :generation AND status = 'running'")
    suspend fun progress(id: String, generation: Long, bytes: Long, now: Long): Int
    @Query("UPDATE downloads SET status = :status, error = :error, updatedAt = :now WHERE id = :id AND generation = :generation AND status = 'running'")
    suspend fun finish(id: String, generation: Long, status: String, error: String, now: Long): Int
    @Query("UPDATE downloads SET expectedSize = :bytes, downloaded = :bytes, updatedAt = :now WHERE id = :id AND generation = :generation AND status = 'running' AND type = 'fileway.zip-export'")
    suspend fun finalExport(id: String, generation: Long, bytes: Long, now: Long): Int
    @Query("UPDATE downloads SET status = :status, generation = generation + 1, error = '', updatedAt = :now WHERE id = :id AND status != 'completed'")
    suspend fun command(id: String, status: String, now: Long): Int
    @Query("UPDATE downloads SET positionMs = :position, durationMs = :duration WHERE id = :id") suspend fun playback(id: String, position: Long, duration: Long)
    @Query("DELETE FROM downloads WHERE id = :id AND status NOT IN ('queued', 'running')") suspend fun removeRecord(id: String): Int
}

package io.github.kkwans.nasfilebrowser.upload

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** A transfer is tied to its original account/target, independent of browsing. */
@Entity(tableName = "uploads", indices = [Index(value = ["jobId"], unique = true), Index(value = ["accountKey", "createdAt"])])
data class UploadRecord(@PrimaryKey val id: String, val jobId: Int, val accountKey: String, val profileId: String,
    val sourceRevision: Long, val sourceUri: String, val name: String, val mime: String, val expectedSize: Long, val sourceModified: Long,
    val targetPath: String, val targetWire: String, val parentWire: String, val sourceLabel: String,
    val overwrite: Boolean = false, val replacedIdentity: String = "", val protocol: String = "tus", val remoteCreated: Boolean = false,
    val status: String = "queued", val uploaded: Long = 0, val createdAt: Long, val updatedAt: Long,
    val generation: Long = 0, val error: String = "", val batchId: String = "", val batchName: String = "", val batchItems: Int = 0,
    val batchBytes: Long = 0, val folderUpload: Boolean = false,
    @ColumnInfo(defaultValue = "0") val activeMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val timingComplete: Boolean = true) {
    val complete get() = status == "completed"
    val active get() = status in setOf("queued", "running")
    val canResume get() = !complete && status !in setOf("canceled", "canceling", "cancel_failed", "restarted")
    val canReselectSource get() = canResume && !active
    val canRestart get() = status in setOf("paused", "failed", "interrupted", "expired", "canceled")
}

@Dao interface UploadDao {
    @Query("UPDATE uploads SET activeMillis = activeMillis + :millis WHERE id = :id")
    suspend fun addRuntime(id: String, millis: Long)
    @Query("SELECT * FROM uploads ORDER BY createdAt DESC") fun observe(): Flow<List<UploadRecord>>
    @Query("SELECT * FROM uploads WHERE id = :id") suspend fun get(id: String): UploadRecord?
    @Query("SELECT targetWire FROM uploads WHERE accountKey = :accountKey AND sourceRevision = :sourceRevision AND status NOT IN ('completed', 'canceled', 'restarted')")
    suspend fun pendingTargets(accountKey: String, sourceRevision: Long): List<String>
    @Query("SELECT COALESCE(MAX(jobId), 7900000) FROM uploads") suspend fun lastJobId(): Int
    @Insert suspend fun insert(record: UploadRecord)
    @Query("UPDATE uploads SET status = 'running', generation = generation + 1, error = '', updatedAt = :now WHERE id = :id AND status IN ('queued', 'interrupted', 'running')")
    suspend fun claim(id: String, now: Long): Int
    @Query("UPDATE uploads SET remoteCreated = 1, updatedAt = :now WHERE id = :id AND generation = :generation AND status = 'running'")
    suspend fun created(id: String, generation: Long, now: Long): Int
    @Query("UPDATE uploads SET uploaded = :bytes, updatedAt = :now WHERE id = :id AND generation = :generation AND status = 'running'")
    suspend fun progress(id: String, generation: Long, bytes: Long, now: Long): Int
    @Query("UPDATE uploads SET status = :status, error = :error, updatedAt = :now WHERE id = :id AND generation = :generation AND status = 'running'")
    suspend fun finish(id: String, generation: Long, status: String, error: String, now: Long): Int
    @Query("UPDATE uploads SET status = :status, generation = generation + 1, error = '', updatedAt = :now WHERE id = :id AND status NOT IN ('completed', 'canceled', 'canceling', 'cancel_failed', 'restarted')")
    suspend fun command(id: String, status: String, now: Long): Int
    @Query("UPDATE uploads SET status = 'paused', generation = generation + 1, error = '', updatedAt = :now WHERE id = :id AND status IN ('queued', 'running', 'interrupted')")
    suspend fun pause(id: String, now: Long): Int
    @Query("UPDATE uploads SET status = 'canceling', generation = generation + 1, error = '', updatedAt = :now WHERE id = :id AND status NOT IN ('completed', 'canceled', 'canceling', 'restarted')")
    suspend fun beginCancel(id: String, now: Long): Int
    @Query("UPDATE uploads SET status = 'canceling', generation = generation + 1, error = '', updatedAt = :now WHERE id = :id AND generation = :generation AND status IN ('paused', 'failed', 'interrupted', 'expired')")
    suspend fun beginCancelAtGeneration(id: String, generation: Long, now: Long): Int
    @Query("UPDATE uploads SET status = :status, uploaded = :bytes, error = :error, updatedAt = :now WHERE id = :id AND generation = :generation AND status = 'canceling'")
    suspend fun canceled(id: String, generation: Long, status: String, bytes: Long, error: String, now: Long): Int
    @Query("UPDATE uploads SET sourceUri = :uri, generation = generation + 1, error = '', status = CASE WHEN status = 'expired' THEN 'expired' ELSE 'paused' END, updatedAt = :now WHERE id = :id AND generation = :generation AND status IN ('paused', 'failed', 'interrupted', 'expired')")
    suspend fun reauthorize(id: String, generation: Long, uri: String, now: Long): Int
    @Query("UPDATE uploads SET status = 'restarted', generation = generation + 1, error = '', updatedAt = :now WHERE id = :id AND generation = :generation AND status = 'canceled'")
    suspend fun restarted(id: String, generation: Long, now: Long): Int
    @Query("DELETE FROM uploads WHERE id = :id AND status NOT IN ('queued', 'running')") suspend fun removeRecord(id: String): Int
}

package io.github.kkwans.nasfilebrowser.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import io.github.kkwans.nasfilebrowser.download.DownloadRecord
import io.github.kkwans.nasfilebrowser.download.DownloadDao
import io.github.kkwans.nasfilebrowser.download.DownloadMigration
import io.github.kkwans.nasfilebrowser.download.DownloadFolderMigration
import io.github.kkwans.nasfilebrowser.upload.UploadRecord
import io.github.kkwans.nasfilebrowser.upload.UploadDao
import io.github.kkwans.nasfilebrowser.upload.UploadMigration

enum class BackendKind { NAS, WINDOWS }
enum class ConnectionMode { DIRECT, TAILNET }
enum class FileLayout(val label: String) { COVER("封面网格"), DETAIL("大图列表"), LIST("常规列表"), COMPACT("紧凑网格"), UNBOUNDED("无界网格") }

@Entity(tableName = "server_profiles")
data class ServerProfile(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: String,
    val backend: BackendKind = BackendKind.NAS,
    val network: ConnectionMode = ConnectionMode.DIRECT,
    val sourceRevision: Long = 0,
    val updatedAt: Long = 0,
)

@Entity(tableName = "accounts", foreignKeys = [ForeignKey(
    entity = ServerProfile::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index(value = ["profileId"]), Index(value = ["profileId", "sourceRevision", "userId"], unique = true)])
data class AccountRecord(
    @PrimaryKey val key: String,
    val profileId: String,
    val sourceRevision: Long,
    val userId: Long,
    val username: String,
    val credentialRef: String,
    val updatedAt: Long,
)

@Entity(tableName = "directory_state", foreignKeys = [ForeignKey(
    entity = AccountRecord::class, parentColumns = ["key"], childColumns = ["accountKey"], onDelete = ForeignKey.CASCADE,
)])
data class DirectoryState(@PrimaryKey val accountKey: String, val path: String, val wirePath: String,
    @ColumnInfo(defaultValue = "'COVER'") val fileLayout: FileLayout = FileLayout.COVER)

@Entity(tableName = "active_session", foreignKeys = [ForeignKey(
    entity = AccountRecord::class, parentColumns = ["key"], childColumns = ["accountKey"], onDelete = ForeignKey.CASCADE,
)], indices = [Index(value = ["accountKey"])])
data class ActiveSession(@PrimaryKey val id: Int = 1, val accountKey: String, val owner: String)

enum class ProgressSync { PENDING, SYNCED, IDENTITY_CHANGED, UNSUPPORTED }

@Entity(tableName = "playback_snapshots", primaryKeys = ["accountKey", "resourceKey", "identity"], foreignKeys = [ForeignKey(
    entity = AccountRecord::class, parentColumns = ["key"], childColumns = ["accountKey"], onDelete = ForeignKey.CASCADE,
)], indices = [Index(value = ["accountKey", "updatedAt"])])
data class PlaybackSnapshot(
    val accountKey: String, val resourceKey: String, val identity: String,
    val path: String, val wirePath: String, val name: String,
    val positionMs: Long, val durationMs: Long, val updatedAt: Long, val sync: ProgressSync,
)

@Dao interface PlaybackDao {
    @Query("SELECT * FROM playback_snapshots WHERE accountKey = :account AND resourceKey = :resource AND identity = :identity")
    suspend fun snapshot(account: String, resource: String, identity: String): PlaybackSnapshot?
    @Query("SELECT * FROM playback_snapshots WHERE accountKey = :account ORDER BY updatedAt DESC LIMIT 100")
    fun recent(account: String): Flow<List<PlaybackSnapshot>>
    @Upsert suspend fun save(snapshot: PlaybackSnapshot)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun guard(snapshot: PlaybackSnapshot): Long
    @Query("UPDATE playback_snapshots SET sync = :sync WHERE accountKey = :account AND resourceKey = :resource AND identity = :identity AND updatedAt = :updated AND positionMs = :position AND durationMs = :duration")
    suspend fun markSync(account: String, resource: String, identity: String, updated: Long, position: Long, duration: Long, sync: ProgressSync): Int
    @Query("DELETE FROM playback_snapshots WHERE accountKey = :account") suspend fun clear(account: String)
}

@Dao interface ProfileDao {
    @Query("SELECT * FROM server_profiles ORDER BY updatedAt DESC, name") fun profiles(): Flow<List<ServerProfile>>
    @Query("SELECT * FROM server_profiles WHERE id = :id") suspend fun profile(id: String): ServerProfile?
    @Upsert suspend fun saveProfile(profile: ServerProfile)
    @Query("SELECT * FROM accounts WHERE profileId = :profile AND sourceRevision = :revision ORDER BY updatedAt DESC")
    suspend fun accounts(profile: String, revision: Long): List<AccountRecord>
    @Query("SELECT * FROM accounts WHERE `key` = :key") suspend fun account(key: String): AccountRecord?
    @Upsert suspend fun saveAccount(account: AccountRecord)
    @Query("SELECT * FROM directory_state WHERE accountKey = :key") suspend fun directory(key: String): DirectoryState?
    @Upsert suspend fun saveDirectory(directory: DirectoryState)
    @Query("SELECT * FROM active_session WHERE id = 1") suspend fun activeSession(): ActiveSession?
    @Upsert suspend fun saveActiveSession(session: ActiveSession)
    @Query("DELETE FROM active_session WHERE id = 1 AND accountKey = :key") suspend fun clearActiveSession(key: String)
    @Query("DELETE FROM active_session WHERE id = 1 AND accountKey = :key AND owner = :owner") suspend fun releaseActiveSession(key: String, owner: String)
    @Query("SELECT credentialRef FROM accounts WHERE profileId = :id") suspend fun credentialRefs(id: String): List<String>
    @Query("DELETE FROM server_profiles WHERE id = :id") suspend fun deleteProfile(id: String)
}

@Database(entities = [ServerProfile::class, AccountRecord::class, DirectoryState::class, PlaybackSnapshot::class, AppPreference::class, ActiveSession::class, DownloadRecord::class, UploadRecord::class], version = 9, exportSchema = true)
abstract class ClientDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun playback(): PlaybackDao
    abstract fun preferences(): PreferenceDao
    abstract fun downloads(): DownloadDao
    abstract fun uploads(): UploadDao
    companion object {
        @Volatile private var instance: ClientDatabase? = null
        /** One upgrade chain for the app and isolated historical-database checks. */
        internal fun migrations(backups: java.io.File): Array<androidx.room.migration.Migration> = arrayOf(
            HistoryMigration(backups), AppearanceMigration(backups), FileLayoutMigration(backups),
            ActiveSessionMigration(backups), DownloadMigration(), DownloadFolderMigration(), UploadMigration(), TransferTimingMigration(backups),
        )
        fun get(context: Context): ClientDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ClientDatabase::class.java, "nfb-client.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*migrations(java.io.File(context.noBackupFilesDir, "state-backups")))
                // Never silently delete state when a future migration is missing.
                .build().also { instance = it }
        }
    }
}

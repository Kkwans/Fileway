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

@Database(entities = [ServerProfile::class, AccountRecord::class, DirectoryState::class, PlaybackSnapshot::class, AppPreference::class, ActiveSession::class, DownloadRecord::class, UploadRecord::class], version = 9, exportSchema = true)
abstract class ClientDatabase : ClientAccountDatabase() {
    override abstract fun profiles(): ProfileDao
    override abstract fun playback(): PlaybackDao
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

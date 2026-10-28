package io.github.kkwans.fileway.tv

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import io.github.kkwans.nasfilebrowser.data.*

@Database(entities = [ServerProfile::class, AccountRecord::class, DirectoryState::class, ActiveSession::class, PlaybackSnapshot::class], version = 1, exportSchema = true)
abstract class TvDatabase : ClientAccountDatabase() {
    companion object {
        @Volatile private var instance: TvDatabase? = null
        fun get(context: Context): TvDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TvDatabase::class.java, "fileway-tv.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build().also { instance = it }
        }
    }
}

package io.github.kkwans.nasfilebrowser.data

import androidx.room.RoomDatabase

/** Shared account/history contract; applications own database names and migrations. */
abstract class ClientAccountDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun playback(): PlaybackDao
}

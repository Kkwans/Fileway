package io.github.kkwans.nasfilebrowser.data

import android.os.SystemClock
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.*

/** Preserve all original records; old tasks explicitly have an incomplete timing history. */
class TransferTimingMigration(private val backups: java.io.File) : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        backupLocalState(db, 8, 9, backups, listOf("server_profiles", "accounts", "directory_state", "playback_snapshots",
            "app_preferences", "active_session", "downloads", "uploads"))
        for (table in listOf("downloads", "uploads")) {
            db.execSQL("ALTER TABLE $table ADD COLUMN activeMillis INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $table ADD COLUMN timingComplete INTEGER NOT NULL DEFAULT 0")
        }
    }
}

/** Monotonic attempt time: no queue wait, pause interval, wall-clock correction or offline gap. */
internal suspend fun <T> withTransferTiming(persist: suspend (Long) -> Unit, block: suspend () -> T): T = coroutineScope {
    var checkpoint = SystemClock.elapsedRealtime()
    suspend fun save() {
        val now = SystemClock.elapsedRealtime()
        val delta = (now - checkpoint).coerceAtLeast(0)
        if (delta > 0) {
            withContext(NonCancellable) { persist(delta); checkpoint = now }
        }
    }
    val ticker = launch(start = CoroutineStart.UNDISPATCHED) {
        try { while (isActive) { delay(2000); runCatching { save() } } }
        finally { withContext(NonCancellable) { runCatching { save() } } }
    }
    try { block() }
    finally { withContext(NonCancellable) { ticker.cancelAndJoin() } }
}

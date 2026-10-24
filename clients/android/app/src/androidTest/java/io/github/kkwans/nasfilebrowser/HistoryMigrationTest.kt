package io.github.kkwans.nasfilebrowser

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HistoryMigrationTest {
    @Test fun upgradePreservesV1RowsAndCreatesPrivateMetadataBackup() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "migration-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(folder, "state.db")
        val schema = JSONObject(instrumentation.context.assets.open("io.github.kkwans.nasfilebrowser.data.ClientDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices -> for (j in 0 until indices.length()) database.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) database.execSQL(setup.getString(i))
            database.execSQL("INSERT INTO server_profiles VALUES ('profile', 'Fixture', 'http://fixture.example.test', 'NAS', 'DIRECT', 0, 1)")
            database.execSQL("INSERT INTO accounts VALUES ('account', 'profile', 0, 7, 'viewer', 'opaque-ref', 1)")
            database.execSQL("INSERT INTO directory_state VALUES ('account', '/films', '/films')")
            database.version = 1
        }
        val backups = File(folder, "backups")
        val upgraded = Room.databaseBuilder(context, ClientDatabase::class.java, file.absolutePath).addMigrations(*ClientDatabase.migrations(backups)).build()
        try {
            assertEquals("Fixture", upgraded.profiles().profile("profile")?.name)
            assertEquals("/films", upgraded.profiles().directory("account")?.wirePath)
            val files = backups.listFiles()!!
            assertEquals(5, files.size)
            val backup = files.single { it.name.startsWith("schema1-before2-") }
            val v2 = JSONObject(files.single { it.name.startsWith("schema2-before3-") }.readText())
            assertEquals(2, v2.getInt("schemaVersion"))
            assertEquals(0, v2.getJSONArray("playback_snapshots").length())
            val data = JSONObject(backup.readText())
            assertEquals(1, data.getInt("schemaVersion"))
            assertEquals("opaque-ref", data.getJSONArray("accounts").getJSONObject(0).getString("credentialRef"))
            val snapshot = PlaybackSnapshot("account", "/video", "identity", "/video", "/video", "Video", 0, 0, 1, ProgressSync.PENDING)
            upgraded.playback().save(snapshot)
            assertEquals(snapshot, upgraded.playback().snapshot("account", "/video", "identity"))
        } finally { upgraded.close(); folder.deleteRecursively() }
    }
}

package io.github.kkwans.fileway.tv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A process-local grant for the system prompt from one submitted install. */
internal class TvUpdateInstallPromptFence<T : Any> {
    private var grant: Pair<T, String>? = null

    fun record(source: T, nonce: String) { grant = source to nonce }

    fun allows(source: T?, nonce: String): Boolean =
        grant?.let { it.first === source && it.second == nonce } == true

    fun clear() { grant = null }
}

internal class TvUpdateInstaller(private val context: Context) {
    private val installer = context.packageManager.packageInstaller
    private val preferences = context.getSharedPreferences("tv-update-install", Context.MODE_PRIVATE)

    data class Attempt(val owner: String, val sessionId: Int, val nonce: String, val versionCode: Long, val status: Int)

    fun attempt(): Attempt? {
        val owner = preferences.getString("owner", null) ?: return null
        val nonce = preferences.getString("nonce", null) ?: return null
        return Attempt(owner, preferences.getInt("session", -1), nonce,
            preferences.getLong("version", -1), preferences.getInt("status", WAITING))
    }

    fun forget(attempt: Attempt) {
        if (attempt() != attempt) return
        runCatching { installer.abandonSession(attempt.sessionId) }
        preferences.edit().clear().commit()
    }

    suspend fun commit(
        owner: String,
        apk: File,
        manifest: TvUpdateManifest,
        canCommit: () -> Boolean,
        onCommitted: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (apk.parentFile!!.usableSpace < manifest.size) {
            throw TvUpdateError("电视存储空间不足，无法为系统安装预留空间")
        }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(manifest.size)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        val nonce = UUID.randomUUID().toString()
        var submitted = false
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, manifest.size).use { target ->
                    apk.inputStream().use { source ->
                        val bytes = ByteArray(64 * 1024)
                        var written = 0L
                        while (written < manifest.size) {
                            currentCoroutineContext().ensureActive()
                            val read = source.read(bytes, 0, minOf(bytes.size.toLong(), manifest.size - written).toInt())
                            check(read > 0)
                            target.write(bytes, 0, read)
                            written += read
                        }
                        check(source.read() == -1)
                    }
                    session.fsync(target)
                }
                withContext(Dispatchers.Main.immediate) {
                    if (!canCommit()) throw TvUpdateError("请返回栖卷并重新发起安装")
                    attempt()?.let(::forget)
                    val saved = preferences.edit().putString("owner", owner).putInt("session", id)
                        .putString("nonce", nonce).putLong("version", manifest.versionCode)
                        .putInt("status", WAITING).commit()
                    if (!saved) throw TvUpdateError("无法保存系统安装记录")
                    val callback = Intent(context, TvUpdateInstallReceiver::class.java)
                        .setAction(RESULT_ACTION).putExtra(NONCE, nonce)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val sender = PendingIntent.getBroadcast(context, id, callback, flags).intentSender
                    session.commit(sender)
                    submitted = true
                    onCommitted(nonce)
                }
            }
        } finally {
            if (!submitted) {
                runCatching { installer.abandonSession(id) }
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                    attempt()?.takeIf { it.sessionId == id && it.nonce == nonce }?.let(::forget)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    fun acceptResult(intent: Intent, allowsPrompt: (Attempt) -> Boolean): Pair<Attempt, Intent?>? {
        if (intent.action != RESULT_ACTION) return null
        val attempt = attempt() ?: return null
        if (intent.getStringExtra(NONCE) != attempt.nonce ||
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != attempt.sessionId) return null
        if (attempt.status != WAITING && attempt.status != PackageInstaller.STATUS_PENDING_USER_ACTION) return null
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, WAITING)
        if (status == WAITING) return null
        val expectsPrompt = status == PackageInstaller.STATUS_PENDING_USER_ACTION && allowsPrompt(attempt)
        val confirmation = if (expectsPrompt) {
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        } else null
        val actualStatus = if (expectsPrompt && confirmation == null) {
            NO_CONFIRMATION
        } else status
        preferences.edit().putInt("status", actualStatus).commit()
        return attempt.copy(status = actualStatus) to confirmation
    }

    companion object {
        const val WAITING = -100
        const val NO_CONFIRMATION = -101
        private const val RESULT_ACTION = "io.github.kkwans.fileway.tv.UPDATE_INSTALL_RESULT"
        private const val NONCE = "update-install-nonce"
    }
}

class TvUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TvUpdateRuntime.get(context).installerResult(intent)
    }
}

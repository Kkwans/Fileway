package io.github.kkwans.nasfilebrowser.ui

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel

internal val LocalPlayerPictureInPicture = staticCompositionLocalOf<PlayerPictureInPicture?> { null }

/** Activity-owned system PiP. The existing native viewport and decoder stay attached. */
internal class PlayerPictureInPicture(private val activity: ComponentActivity, private val model: ClientModel) {
    var active by mutableStateOf(false)
        private set
    var preparing by mutableStateOf(false)
        private set
    private var videoBounds: Rect? = null
    private val action = activity.packageName + ".PLAYER_PIP"
    val supported get() = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = model.player.state.value
            if (intent?.action != action || !active || model.state.value.selected == null ||
                intent.getLongExtra("generation", -1) != state.mediaGeneration) return
            when (intent.getIntExtra("command", -1)) {
                0 -> model.togglePlayback()
                -10, 10 -> if (state.seekable && state.durationMs > 0) model.player.seek(
                    (state.positionMs + intent.getIntExtra("command", 0) * 1000L).coerceIn(0, state.durationMs))
            }
        }
    }
    init { ContextCompat.registerReceiver(activity, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED) }
    fun videoBounds(rect: Rect) { if (rect != videoBounds && !rect.isEmpty) { videoBounds = rect; update() } }
    fun modeChanged(value: Boolean) { active = value; preparing = false }
    fun transition(value: Boolean) { preparing = value }
    fun resumed() { if (!activity.isInPictureInPictureMode) { active = false; preparing = false } }
    fun dispose() { activity.unregisterReceiver(receiver) }
    private fun remote(command: Int, icon: Int, label: String): RemoteAction {
        val intent = Intent(action).setPackage(activity.packageName).putExtra("command", command)
            .putExtra("generation", model.player.state.value.mediaGeneration)
        val pending = PendingIntent.getBroadcast(activity, command + 100, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return RemoteAction(Icon.createWithResource(activity, icon), label, label, pending)
    }
    private fun params(): PictureInPictureParams {
        val state = model.player.state.value
        val valid = model.state.value.selected != null && state.firstFrameRendered && state.error == null
        val ratio = if (state.width > 0 && state.height > 0) (state.width.toFloat() / state.height).coerceIn(1f / 2.39f, 2.39f) else 16f / 9f
        val actions = buildList {
            if (state.seekable) add(remote(-10, R.drawable.ic_skip_back, "后退10秒"))
            add(remote(0, if (state.playing) R.drawable.art_pause else R.drawable.art_play, if (state.playing) "暂停" else "播放"))
            if (state.seekable) add(remote(10, R.drawable.ic_skip_forward, "前进10秒"))
        }
        return PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 10000).toInt(), 10000))
            .setActions(actions).apply {
                videoBounds?.let(::setSourceRectHint)
                if (Build.VERSION.SDK_INT >= 31) {
                    setSeamlessResizeEnabled(true)
                    setAutoEnterEnabled(valid && state.playing && model.playbackPreferences.autoPictureInPicture.value)
                }
            }.build()
    }
    fun update() {
        if (!supported || activity.isFinishing || activity.isDestroyed) return
        activity.setPictureInPictureParams(params())
    }
    fun enter(): Boolean {
        val state = model.player.state.value
        if (!supported || model.state.value.selected == null || !state.firstFrameRendered || state.error != null) return false
        if (activity.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return false
        preparing = true
        return try { activity.enterPictureInPictureMode(params()).also { if (!it) preparing = false } }
        catch (_: IllegalStateException) { preparing = false; false }
        catch (_: SecurityException) { preparing = false; false }
    }
}

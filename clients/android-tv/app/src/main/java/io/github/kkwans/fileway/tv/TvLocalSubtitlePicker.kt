package io.github.kkwans.fileway.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal enum class TvLocalPickerMode { FILE, TREE, LEGACY_READ }

internal sealed interface TvLocalPickerOutcome {
    data class Message(val text: String) : TvLocalPickerOutcome
    data class Folder(val persistent: Boolean) : TvLocalPickerOutcome
    data object LegacyGranted : TvLocalPickerOutcome
    class File(val node: TvLocalSubtitleStorage.Node, val fence: TvLocalSubtitleFence) : TvLocalPickerOutcome
}

internal val LocalTvSubtitlePicker = staticCompositionLocalOf<TvLocalSubtitlePicker?> { null }

/** The foreground Activity owns permission UI and never associates a restored result with a new request. */
internal class TvLocalSubtitlePicker(private val activity: ComponentActivity, private val media: TvMediaController) {
    private class Request(
        val mode: TvLocalPickerMode,
        val fence: TvLocalSubtitleFence,
        val started: Long,
        val active: () -> Boolean,
        val callback: (TvLocalPickerOutcome) -> Unit,
    )

    private var request: Request? = null
    private var playbackToResume: Request? = null
    private var systemReturned = false
    private val documents = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = request?.takeIf { it.mode != TvLocalPickerMode.LEGACY_READ } ?: return@registerForActivityResult
        returned()
        activity.lifecycleScope.launch {
            val outcome = try {
                withTimeout(12_000) { acceptDocument(pending, result.resultCode, result.data) }
            } catch (_: TimeoutCancellationException) { TvLocalPickerOutcome.Message("读取系统选择结果超时，请重试") }
            catch (error: CancellationException) { throw error }
            catch (error: TvLocalSubtitleFailure) { TvLocalPickerOutcome.Message(error.message.orEmpty()) }
            catch (_: Exception) { TvLocalPickerOutcome.Message("无法使用系统选择结果，请重试") }
            finish(pending, outcome)
        }
    }
    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = request?.takeIf { it.mode == TvLocalPickerMode.LEGACY_READ } ?: return@registerForActivityResult
        returned()
        finish(pending, if (granted && media.localSubtitles.hasLegacyRead) TvLocalPickerOutcome.LegacyGranted
            else TvLocalPickerOutcome.Message("未授予共享存储读取权限。可重试授权，或选择系统文件和文件夹"))
    }

    fun open(mode: TvLocalPickerMode, active: () -> Boolean, callback: (TvLocalPickerOutcome) -> Unit) {
        val fence = media.localSubtitleFence()
        if (fence == null || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            callback(TvLocalPickerOutcome.Message("当前视频尚未准备好，请稍后重试"))
            return
        }
        if (request != null) {
            callback(TvLocalPickerOutcome.Message("系统文件选择仍在处理，请先返回电视播放器"))
            return
        }
        val pending = Request(mode, fence, SystemClock.elapsedRealtime(), active, callback)
        request = pending
        systemReturned = false
        try {
            when (mode) {
                TvLocalPickerMode.TREE -> documents.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                })
                TvLocalPickerMode.FILE -> try {
                    documents.launch(documentIntent(Intent.ACTION_OPEN_DOCUMENT))
                } catch (_: ActivityNotFoundException) {
                    documents.launch(documentIntent(Intent.ACTION_GET_CONTENT))
                }
                TvLocalPickerMode.LEGACY_READ -> if (tvLegacySubtitleStorage) permission.launch(TV_LOCAL_READ)
                    else finish(pending, TvLocalPickerOutcome.Message("当前系统请使用文件夹或单文件授权"))
            }
        } catch (_: ActivityNotFoundException) {
            val message = if (mode == TvLocalPickerMode.TREE) "电视没有可用的系统文件夹选择器，可尝试选择单个文件"
                else "电视没有可用的系统字幕文件选择器，可尝试授权文件夹或浏览现有目录"
            finish(pending, TvLocalPickerOutcome.Message(message))
        } catch (_: SecurityException) {
            finish(pending, TvLocalPickerOutcome.Message("电视系统未允许打开文件授权界面"))
        }
    }

    private fun documentIntent(action: String) = Intent(action).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }

    private suspend fun acceptDocument(pending: Request, resultCode: Int, data: Intent?): TvLocalPickerOutcome {
        if (!valid(pending)) return stale()
        if (resultCode != Activity.RESULT_OK) return TvLocalPickerOutcome.Message("已取消系统文件选择")
        val result = data ?: return TvLocalPickerOutcome.Message("系统未返回可读取的文件或文件夹")
        val uri = result.data?.takeIf { it.scheme == "content" }
            ?: return TvLocalPickerOutcome.Message("系统未返回可读取的文件或文件夹")
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val flags = result.flags
        if (flags and read == 0 && activity.checkUriPermission(uri, Process.myPid(), Process.myUid(), read) != PackageManager.PERMISSION_GRANTED)
            return TvLocalPickerOutcome.Message("系统未授予读取权限，请重新选择")
        return if (pending.mode == TvLocalPickerMode.TREE) {
            val name = media.localSubtitles.inspectTree(uri)
            if (!valid(pending)) stale() else TvLocalPickerOutcome.Folder(
                media.localSubtitles.registerTree(pending.fence.account, uri, name, flags),
            )
        } else TvLocalPickerOutcome.File(media.localSubtitles.single(uri), pending.fence)
    }

    private fun valid(pending: Request) = request === pending && pending.active() &&
        SystemClock.elapsedRealtime() - pending.started <= 300_000 && media.acceptsLocalSubtitle(pending.fence)

    private fun stale() = TvLocalPickerOutcome.Message("播放内容已变化，请重新选择字幕")

    private fun finish(pending: Request, outcome: TvLocalPickerOutcome) {
        if (request !== pending) return
        val value = if (valid(pending)) outcome else stale()
        request = null
        if (pending.active()) pending.callback(value)
    }

    fun beforeStop() {
        val pending = request ?: return
        if (!systemReturned && media.acceptsLocalMedia(pending.fence) && media.player.wantsPlay && !media.player.state.value.ended)
            playbackToResume = pending
    }

    private fun returned() {
        systemReturned = true
        afterResume()
    }

    fun afterResume() {
        if (!systemReturned || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val stopped = playbackToResume ?: return
        playbackToResume = null
        val player = media.player
        if (SystemClock.elapsedRealtime() - stopped.started <= 300_000 && media.acceptsLocalMedia(stopped.fence) &&
            !player.wantsPlay && !player.playWhenReady && !player.state.value.ended && player.state.value.error == null) player.toggle()
    }
}

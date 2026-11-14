package io.github.kkwans.fileway.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.media.MediaScannerConnection
import android.provider.MediaStore
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.github.kkwans.nasfilebrowser.app.SessionContext
import io.github.kkwans.nasfilebrowser.data.PreviewLease
import io.github.kkwans.nasfilebrowser.data.ServiceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.io.IOException
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

internal enum class TvUpdatePhase {
    DISCONNECTED, IDLE, CHECKING, UNSUPPORTED, AVAILABLE, EMPTY, PREPARING, DOWNLOADING,
    VERIFYING, READY, EXPORTING, INSTALLING, WAITING_INSTALL, INSTALLED, ERROR,
}

internal data class TvUpdateState(
    val phase: TvUpdatePhase = TvUpdatePhase.DISCONNECTED,
    val offers: List<TvUpdateOffer> = emptyList(),
    val selected: TvUpdateOffer? = null,
    val received: Long = 0,
    val total: Long? = null,
    val message: String? = null,
    val savedInDownloads: Boolean = false,
) {
    val transferring: Boolean get() = phase in setOf(TvUpdatePhase.PREPARING, TvUpdatePhase.DOWNLOADING, TvUpdatePhase.VERIFYING)
    val busy: Boolean get() = transferring || phase in setOf(TvUpdatePhase.CHECKING, TvUpdatePhase.EXPORTING, TvUpdatePhase.INSTALLING)
}

internal class TvUpdateRuntime(private val context: Context, signerPin: String = TV_UPDATE_SIGNER) {
    private val pin = signerPin
    private val payload = TvUpdatePayload(context, pin)
    private val installer = TvUpdateInstaller(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val storage = Mutex()
    private val mutable = MutableStateFlow(TvUpdateState())
    val state = mutable.asStateFlow()
    private val pending = MutableStateFlow<Intent?>(null)
    val installerPrompt = pending.asStateFlow()
    private var operationJob: Job? = null
    private val installPromptFence = TvUpdateInstallPromptFence<Source>()
    private var confirmation: Pair<TvUpdateInstaller.Attempt, Intent>? = null
    private var exportRequest: Pair<Source, TvUpdateOffer>? = null

    private class Source(val session: SessionContext) {
        val device = session.api.identity.deviceSessionId
        val owner = listOf(session.profile.id, session.profile.sourceRevision, session.profile.address,
            session.account.key, session.account.userId, device).joinToString("\u0000")
            .toByteArray(Charsets.UTF_8).tvUpdateSha256()
        val server = TvUpdateServer(session.api)
    }

    private class Transfer(val source: Source, val offer: TvUpdateOffer) {
        val token: String = UUID.randomUUID().toString()
        var job: Job? = null
        var lease: PreviewLease? = null
    }

    @Volatile private var source: Source? = null
    @Volatile private var transfer: Transfer? = null

    fun bind(session: SessionContext) {
        if (source?.let { it.session === session && it.device == session.api.identity.deviceSessionId } == true) return
        if (source != null) unbind(erase = true)
        val owner = Source(session)
        source = owner
        installer.attempt()?.takeUnless { it.owner == owner.owner }?.let(installer::forget)
        mutable.value = TvUpdateState(TvUpdatePhase.IDLE)
        scope.launch {
            val saved = storage.withLock { withContext(Dispatchers.IO) { payload.hasSavedPayload(owner.owner) } }
            if (current(owner) && state.value.phase == TvUpdatePhase.IDLE && saved) {
                mutable.update { it.copy(message = "已保留上次下载，重新检查后可继续") }
            }
        }
        reconcileInstall()
    }

    fun unbind(erase: Boolean, session: SessionContext? = null) {
        val previous = source ?: return
        if (session != null && previous.session !== session) return
        source = null
        exportRequest = null
        operationJob?.cancel()
        operationJob = null
        cancelTransfer()
        clearInstallPrompt()
        mutable.value = TvUpdateState()
        if (erase) {
            installer.attempt()?.takeIf { it.owner == previous.owner }?.let(installer::forget)
            clearPayload(previous)
        }
    }

    private fun clearPayload(owner: Source) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            storage.withLock {
                withContext(Dispatchers.IO) { runCatching { payload.clear(owner.owner) } }
            }
        }
    }

    private fun current(owner: Source): Boolean =
        source === owner && owner.device == owner.session.api.identity.deviceSessionId

    fun check() {
        val owner = source ?: return
        if (state.value.busy || state.value.phase == TvUpdatePhase.WAITING_INSTALL) return
        mutable.value = TvUpdateState(TvUpdatePhase.CHECKING)
        operationJob = scope.launch {
            try {
                val result = withTimeout(CHECK_TIMEOUT) { directory(owner) }
                if (!current(owner)) return@launch
                mutable.value = when {
                    result == null -> TvUpdateState(TvUpdatePhase.UNSUPPORTED,
                        message = "此服务器暂不支持电视在线更新")
                    result.offers.isNotEmpty() -> TvUpdateState(TvUpdatePhase.AVAILABLE, offers = result.offers,
                        message = "部分更新清单未通过校验".takeIf { result.rejected })
                    result.rejected -> TvUpdateState(TvUpdatePhase.ERROR,
                        message = "更新清单未通过校验，请稍后重新检查")
                    else -> TvUpdateState(TvUpdatePhase.EMPTY, message = "暂无适用于此电视的更新")
                }
                reconcileInstall()
            } catch (failure: Exception) {
                report(owner, failure)
            }
        }
    }

    private suspend fun directory(owner: Source): TvUpdateDirectory? {
        if (!owner.server.supportsUpdates()) return null
        val device = withContext(Dispatchers.IO) { payload.device() }
        val json = owner.server.directory(device)
        if (json.getJSONArray("offers").length() == 0) return TvUpdateDirectory(emptyList(), tvUpdateDirectoryIncomplete(json))
        return withContext(Dispatchers.IO) { tvUpdateOffers(json, device, payload.installedSigner().publicKey, pin) }
    }

    fun download(offer: TvUpdateOffer) {
        val owner = source?.takeIf(::current) ?: return
        val before = state.value
        if (before.busy || before.phase == TvUpdatePhase.WAITING_INSTALL || offer !in before.offers) return
        val active = Transfer(owner, offer)
        transfer = active
        mutable.value = before.copy(phase = TvUpdatePhase.PREPARING, selected = offer,
            received = 0, total = null, message = null, savedInDownloads = false)
        try {
            TvUpdateTransfer.start(context, active.token, offer.manifest.size)
            operationJob = scope.launch {
                delay(START_TIMEOUT)
                if (transfer === active && active.job == null) {
                    cancelTransfer()
                    report(owner, TvUpdateError("系统未能启动下载，请保持应用可见后重试"))
                }
            }
        } catch (_: RuntimeException) {
            transfer = null
            report(owner, TvUpdateError("系统暂时无法启动下载，请保持应用可见后重试"))
        }
    }

    /** Called from the platform's user-initiated job or foreground service on Main. */
    fun startTransfer(token: String, finished: () -> Unit): Boolean {
        val active = transfer?.takeIf { it.token == token && it.job == null && current(it.source) } ?: return false
        active.job = scope.launch(Dispatchers.Main) {
            try {
                withTimeout(TRANSFER_TIMEOUT) { receive(active) }
            } catch (failure: Exception) {
                if (transfer === active) report(active.source, failure)
            } finally {
                if (transfer === active) transfer = null
                finished()
            }
        }
        return true
    }

    private suspend fun receive(active: Transfer) {
        val owner = active.source
        val offer = active.offer
        val manifest = offer.manifest
        val remote = withTimeout(PREPARE_TIMEOUT) {
            var result = owner.server.prepare(offer)
            while (!result.ready) {
                ensureCurrent(active)
                mutable.update { it.copy(received = result.received, total = result.total) }
                delay(1000)
                result = owner.server.status(result.id, manifest)
            }
            result
        }
        ensureCurrent(active)
        mutable.update { it.copy(phase = TvUpdatePhase.DOWNLOADING, received = 0, total = null) }
        storage.withLock {
            ensureCurrent(active)
            val lease = owner.server.content(remote.id).also { active.lease = it }
            try {
                payload.receive(owner.owner, offer, lease) { received ->
                    mutable.update { old ->
                        if (transfer === active && current(owner) && old.phase == TvUpdatePhase.DOWNLOADING) {
                            old.copy(received = received, total = manifest.size)
                        } else old
                    }
                }
            } finally {
                active.lease = null
                runCatching { lease.release() }
            }
            ensureCurrent(active)
            mutable.update { it.copy(phase = TvUpdatePhase.VERIFYING) }
            val apk = payload.verify(owner.owner, offer)
            val saved = if (Build.VERSION.SDK_INT >= 29 ||
                context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    withContext(Dispatchers.IO) {
                        check(current(owner))
                        savePublicDownload(apk, downloadName(offer))
                    }
                    true
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    false
                }
            } else false
            ensureCurrent(active)
            mutable.update { it.copy(phase = TvUpdatePhase.READY, received = 0, total = null, savedInDownloads = saved,
                message = if (saved) "安装包已保存到 Download/Fileway。"
                else "安装包已通过校验，可保存到 Download/Fileway 或另存。") }
        }
    }

    private suspend fun ensureCurrent(active: Transfer) {
        currentCoroutineContext().ensureActive()
        if (transfer !== active || !current(active.source)) throw CancellationException("Update source changed")
    }

    fun cancelDownload() {
        val active = transfer ?: return
        cancelTransfer()
        clearPayload(active.source)
        mutable.update { it.copy(phase = TvUpdatePhase.AVAILABLE, selected = null, received = 0,
            total = null, message = "已取消下载") }
    }

    fun platformStopped(token: String) {
        val active = transfer?.takeIf { it.token == token } ?: return
        transfer = null
        active.job?.cancel()
        revoke(active)
        report(active.source, TvUpdateError("下载已暂停，已接收的部分可续传"))
    }

    private fun cancelTransfer() {
        val active = transfer ?: return
        transfer = null
        active.job?.cancel()
        revoke(active)
        TvUpdateTransfer.stop(context)
    }

    private fun revoke(active: Transfer) {
        active.lease?.let { lease -> scope.launch { runCatching { lease.release() } } }
    }

    fun install(activity: Activity) {
        val owner = source?.takeIf(::current) ?: return
        val snapshot = state.value
        val offer = snapshot.selected ?: return
        if (snapshot.busy || snapshot.phase !in setOf(TvUpdatePhase.READY, TvUpdatePhase.WAITING_INSTALL)) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                mutable.update { it.copy(message = "请在系统设置中允许栖卷安装应用，返回后再次选择安装") }
            } catch (_: ActivityNotFoundException) {
                mutable.update { it.copy(message = if (it.savedInDownloads) "投影仪未提供授权页面。请在文件管理器打开 Download/Fileway 中的 APK。"
                    else "投影仪未提供授权页面。请保存 APK，再用系统文件管理器安装。") }
            } catch (_: SecurityException) {
                mutable.update { it.copy(message = "系统限制栖卷发起安装。可导出 APK，交给系统文件管理器。") }
            }
            return
        }
        clearInstallPrompt()
        mutable.update { it.copy(phase = TvUpdatePhase.INSTALLING, message = null) }
        operationJob = scope.launch {
            try {
                val available = withTimeout(CHECK_TIMEOUT) { directory(owner) }?.offers?.any {
                    it.manifest == offer.manifest
                } == true
                if (!current(owner)) return@launch
                if (!available) throw TvUpdateError("更新已失效，请重新检查", recheck = true)
                storage.withLock {
                    val apk = payload.verify(owner.owner, offer)
                    installer.commit(owner.owner, apk, offer.manifest,
                        canCommit = {
                            current(owner) && (activity as? LifecycleOwner)?.lifecycle?.currentState
                                ?.isAtLeast(Lifecycle.State.RESUMED) == true
                        },
                        onCommitted = { nonce -> installPromptFence.record(owner, nonce) },
                    )
                }
                if (current(owner) && state.value.phase == TvUpdatePhase.INSTALLING) {
                    mutable.update { it.copy(phase = TvUpdatePhase.WAITING_INSTALL, message = "等待系统安装确认") }
                }
            } catch (failure: Exception) {
                report(owner, failure, keepReady = true)
            }
        }
    }

    /** The picker grants access to the user's destination; the saved APK stays private. */
    fun exportDestination(): String? {
        val owner = source?.takeIf(::current) ?: return null
        val snapshot = state.value
        val offer = snapshot.selected ?: return null
        if (snapshot.phase != TvUpdatePhase.READY || exportRequest != null) return null
        exportRequest = owner to offer
        return downloadName(offer)
    }

    fun exportPickerUnavailable() {
        exportRequest = null
        mutable.update { it.copy(message = "此设备没有文件保存选择器。请从 Fileway 发布页下载同版本，用 U 盘或 NAS 文件管理器安装。") }
    }

    fun saveToDownloads() {
        if (exportDestination() != null) exportDownloaded(null, toDownloads = true)
    }

    fun exportDownloaded(destination: Uri?, toDownloads: Boolean = false) {
        val (owner, offer) = exportRequest ?: return
        exportRequest = null
        if ((destination == null && !toDownloads) || !current(owner) || state.value.phase != TvUpdatePhase.READY ||
            state.value.selected != offer) return
        mutable.update { it.copy(phase = TvUpdatePhase.EXPORTING, message = null) }
        operationJob = scope.launch {
            try {
                storage.withLock {
                    val apk = payload.verify(owner.owner, offer)
                    withContext(Dispatchers.IO) {
                        check(current(owner))
                        if (toDownloads) savePublicDownload(apk, downloadName(offer))
                        else writeExport(apk, requireNotNull(destination))
                    }
                }
                if (current(owner)) mutable.update {
                    it.copy(phase = TvUpdatePhase.READY, savedInDownloads = toDownloads || it.savedInDownloads,
                        message = if (toDownloads) "APK 已保存到 Download/Fileway。请在文件管理器中打开安装。"
                    else "APK 已保存到所选位置。请在文件管理器中打开安装。")
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (!current(owner)) return@launch
                if (failure is TvUpdateError && failure.recheck) report(owner, failure, keepReady = false)
                else mutable.update {
                    it.copy(phase = TvUpdatePhase.READY, message = "未能导出 APK，原下载仍在，请选择其他位置重试。")
                }
            }
        }
    }

    private fun downloadName(offer: TvUpdateOffer) = "Fileway-TV-${offer.manifest.versionName}.apk"

    private suspend fun savePublicDownload(apk: File, name: String) {
        if (Build.VERSION.SDK_INT < 29) {
            saveLegacyDownload(apk, name)
            return
        }
        val resolver = context.contentResolver
        val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val entry = resolver.insert(downloads, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Fileway/")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw IOException("无法创建下载文件")
        try {
            writeExport(apk, entry)
            currentCoroutineContext().ensureActive()
            if (resolver.update(entry, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) != 1)
                throw IOException("无法完成下载文件")
        } catch (failure: Exception) {
            runCatching { resolver.delete(entry, null, null) }
            throw failure
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun saveLegacyDownload(apk: File, name: String) {
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Fileway")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("下载目录不可用")
        val staging = File.createTempFile(".fileway-", ".part", directory)
        try {
            staging.outputStream().use { copyApk(apk, it) }
            currentCoroutineContext().ensureActive()
            val target = File(directory, name)
            Files.move(staging.toPath(), target.toPath())
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("application/vnd.android.package-archive"), null)
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    private suspend fun writeExport(apk: File, destination: Uri) {
        val output = context.contentResolver.openOutputStream(destination, "w") ?: throw IOException("无法写入目标文件")
        output.use { copyApk(apk, it) }
    }

    private suspend fun copyApk(apk: File, output: OutputStream) {
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
    }

    fun publicDownloadDenied() {
        mutable.update { it.copy(message = "未获准写入下载目录。可选择另存位置，原下载已保留。") }
    }

    fun installerResult(intent: Intent) {
        val owner = source?.takeIf(::current)
        val (attempt, prompt) = installer.acceptResult(intent) {
            installPromptFence.allows(owner, it.nonce)
        } ?: return
        if (owner?.owner != attempt.owner) return
        confirmation = prompt?.let { attempt to it }
        reconcileInstall()
        if (prompt == null) clearInstallPrompt()
    }

    fun openInstaller(activity: Activity) {
        val (attempt, prompt) = confirmation ?: return
        val owner = source?.takeIf(::current) ?: return
        if (!installPromptFence.allows(owner, attempt.nonce) || installer.attempt() != attempt) return
        clearInstallPrompt()
        try {
            activity.startActivity(prompt)
        } catch (_: ActivityNotFoundException) {
            installer.forget(attempt)
            mutable.update { it.copy(phase = TvUpdatePhase.READY, message = "此设备没有可用的系统安装器，已保留下载") }
        } catch (_: SecurityException) {
            installer.forget(attempt)
            mutable.update { it.copy(phase = TvUpdatePhase.READY, message = "系统策略限制安装，已保留下载") }
        }
    }

    fun reconcileInstall() {
        val owner = source?.takeIf(::current) ?: return
        val attempt = installer.attempt()?.takeIf { it.owner == owner.owner } ?: return
        val code = runCatching { payload.device().versionCode }.getOrNull()
        val ownsPrompt = installPromptFence.allows(owner, attempt.nonce)
        if (state.value.phase == TvUpdatePhase.INSTALLING && !ownsPrompt) {
            if (code == null || code < attempt.versionCode) return
            operationJob?.cancel()
        }
        val status = attempt.status
        when {
            code != null && code >= attempt.versionCode -> {
                installer.forget(attempt)
                clearInstallPrompt()
                clearPayload(owner)
                mutable.value = TvUpdateState(TvUpdatePhase.INSTALLED,
                    message = if (code == attempt.versionCode) "更新已安装" else "当前已安装更高版本")
            }
            status == PackageInstaller.STATUS_PENDING_USER_ACTION || status == PackageInstaller.STATUS_SUCCESS ||
                status == TvUpdateInstaller.WAITING -> {
                pending.value = confirmation?.takeIf { ownsPrompt && it.first == attempt }?.second
                mutable.update { it.copy(phase = TvUpdatePhase.WAITING_INSTALL,
                    message = if (pending.value != null || status == TvUpdateInstaller.WAITING && ownsPrompt) "等待系统安装确认"
                    else "安装结果待确认，可重新发起安装") }
            }
            else -> {
                installer.forget(attempt)
                val message = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "已取消系统安装，已保留下载"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "系统策略限制安装，已保留下载"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "电视存储空间不足，已保留下载"
                    TvUpdateInstaller.NO_CONFIRMATION -> "此设备没有可用的系统安装器，已保留下载"
                    else -> "系统未能安装更新，已保留下载"
                }
                mutable.update { it.copy(phase = if (it.selected != null) TvUpdatePhase.READY else TvUpdatePhase.ERROR, message = message) }
            }
        }
    }

    fun resetPendingInstall() {
        val owner = source?.takeIf(::current) ?: return
        clearInstallPrompt()
        installer.attempt()?.takeIf { it.owner == owner.owner }?.let(installer::forget)
        mutable.update { it.copy(phase = if (it.selected == null) TvUpdatePhase.IDLE else TvUpdatePhase.READY,
            message = "请重新发起安装") }
    }

    private fun clearInstallPrompt() {
        installPromptFence.clear()
        confirmation = null
        pending.value = null
    }

    private fun report(owner: Source, failure: Exception, keepReady: Boolean = false) {
        if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
        if (!current(owner)) return
        val error = when (failure) {
            is TvUpdateError -> failure
            is TimeoutCancellationException -> TvUpdateError("更新请求已超时，请重试")
            is ServiceException -> when (failure.status) {
                401, 403 -> TvUpdateError("当前登录已失效或没有更新权限，请重新连接", recheck = true)
                404 -> TvUpdateError("更新已失效，请重新检查", recheck = true)
                429 -> TvUpdateError("发布服务请求过多，请稍后重试")
                503 -> TvUpdateError("更新服务暂不可用，请稍后重试")
                else -> TvUpdateError("服务器暂时无法处理更新请求，请重试")
            }
            is org.json.JSONException, is IllegalArgumentException, is IllegalStateException ->
                TvUpdateError("更新响应未通过校验，请重新检查", recheck = true)
            else -> TvUpdateError(if (keepReady) "系统暂时无法准备安装，已保留下载" else "更新连接暂不可用，请重试")
        }
        mutable.update {
            it.copy(phase = if (keepReady && !error.recheck) TvUpdatePhase.READY else TvUpdatePhase.ERROR,
                offers = if (error.recheck) emptyList() else it.offers,
                selected = if (error.recheck) null else it.selected,
                received = 0, total = null, message = error.userMessage)
        }
    }

    companion object {
        private const val START_TIMEOUT = 25_000L
        private const val CHECK_TIMEOUT = 45_000L
        private const val PREPARE_TIMEOUT = 5 * 60_000L
        private const val TRANSFER_TIMEOUT = 15 * 60_000L
        @Volatile private var instance: TvUpdateRuntime? = null

        fun get(context: Context): TvUpdateRuntime = instance ?: synchronized(this) {
            instance ?: TvUpdateRuntime(context.applicationContext).also { instance = it }
        }

        /** Device tests use the independently signed installed test APK as their root. */
        fun useTestSigner(context: Context, signerSha256: String) {
            check(BuildConfig.DEBUG && instance == null)
            synchronized(this) {
                check(instance == null)
                instance = TvUpdateRuntime(context.applicationContext, signerSha256)
            }
        }
    }
}

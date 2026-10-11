package io.github.kkwans.nasfilebrowser.update

import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import io.github.kkwans.nasfilebrowser.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.zip.ZipFile

internal enum class UpdatePhase { IDLE, RESTORING, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY, FAILED }
internal data class UpdateState(val phase: UpdatePhase = UpdatePhase.RESTORING, val update: AppUpdate? = null,
    val bytes: Long = 0, val total: Long = 0, val status: String = "", val error: String? = null, val choices: List<AppUpdate> = emptyList()) {
    val busy get() = phase in setOf(UpdatePhase.RESTORING, UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
}

internal data class InstalledApp(val packageName: String, val versionCode: Long, val certificates: Set<String>)
internal class UpdatePackages(private val context: Context) {
    @Suppress("DEPRECATION") private fun installedInfo() = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    fun installed(): InstalledApp = installedInfo().let { InstalledApp(it.packageName, it.longVersionCode,
        it.signingInfo?.apkContentsSigners?.map { certificate -> certificate.toCharsString() }?.toSet().orEmpty()) }

    @Suppress("DEPRECATION") fun verify(file: File, update: AppUpdate, current: InstalledApp = installed()) {
        check(file.isFile && file.length() == update.asset.size) { "安装包不完整，请重新下载" }
        val apk = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("安装包无法读取，请重新下载")
        check(apk.packageName == current.packageName) { "安装包不属于栖卷，已拒绝安装" }
        check(apk.longVersionCode > current.versionCode) { "安装包版本未高于当前版本，已拒绝降级或重复安装" }
        val expectedVersion = UpdatePolicy.releaseVersion(update.release)
        check(expectedVersion != null && AppVersion.parse(apk.versionName.orEmpty()) == expectedVersion) { "安装包版本与更新说明不一致，请重新检查更新" }
        val certificates = apk.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        check(certificates.isNotEmpty() && current.certificates.isNotEmpty() && certificates == current.certificates) {
            "安装包签名与已安装的栖卷不一致，已拒绝安装"
        }
        check(apk.applicationInfo?.minSdkVersion?.let { it <= Build.VERSION.SDK_INT } == true) { "此版本不支持当前 Android 系统" }
        val abis = ZipFile(file).use { zip -> zip.entries().asSequence().map { it.name }
            .filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.toSet() }
        check(abis.isEmpty() || Build.SUPPORTED_ABIS.any { it in abis }) { "安装包不支持此设备的处理器，请重新检查更新" }
        // PackageInstaller performs the final cryptographic/ABI checks; no app data is removed.
    }
    fun intent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("栖卷更新", uri) }
    }
}

/** Only this module's app-specific directory and remembered DownloadManager IDs. */
internal class UpdateDownloads(private val context: Context) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    data class Progress(val status: Int, val bytes: Long, val reason: Int)
    fun file(update: AppUpdate): File {
        val directory = context.getExternalFilesDir("updates") ?: error("无法读取更新目录，请检查手机存储")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建更新目录，请检查手机存储" }
        return File(directory, "fileway-update-${update.asset.id}.apk")
    }
    fun enqueue(update: AppUpdate): Long {
        require(UpdatePolicy.trusted(update.asset, update.release.tag)) { "更新下载地址无效，请重新检查更新" }
        return manager.enqueue(DownloadManager.Request(Uri.parse(update.asset.url))
            .setTitle("栖卷 ${update.release.displayVersion}").setDescription("应用更新 · 可返回栖卷查看进度")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(file(update))).setAllowedOverMetered(true))
    }
    fun progress(id: Long): Progress? = manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
        if (!cursor.moveToFirst()) null else Progress(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
    }
    // Recover the narrow crash window between enqueue and saving its returned ID.
    fun find(update: AppUpdate): Long? = manager.query(DownloadManager.Query())?.use { cursor ->
        val expected = Uri.fromFile(file(update)).toString()
        val local = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
        val id = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
        var found: Long? = null
        while (cursor.moveToNext()) if (cursor.getString(local) == expected) found = cursor.getLong(id)
        found
    }
    fun remove(id: Long, update: AppUpdate?) {
        if (id >= 0) manager.remove(id)
        update?.let { val owned = file(it); check(!owned.exists() || owned.delete()) { "更新缓存未能移除，请重试" } }
    }
}

internal class AppUpdates(private val context: Context, private val scope: CoroutineScope,
    private val installedVersion: String = BuildConfig.VERSION_NAME) {
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private val preferences = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    private val downloads = UpdateDownloads(context)
    private val packages = UpdatePackages(context)
    private var task: Job? = null
    private var downloadId = -1L
    private var owned: AppUpdate? = null
    private var choices = emptyList<AppUpdate>()
    init { task = scope.launch {
        try {
            withContext(Dispatchers.IO) {
                val saved = preferences.getString("release", null)
                if (saved != null) {
                    val release = runCatching { parseReleases("[$saved]").singleOrNull() }.getOrNull()
                    owned = release?.let { UpdatePolicy.compatible(it, Build.SUPPORTED_ABIS.toList())?.let { asset -> AppUpdate(it, asset) } }
                    downloadId = preferences.getLong("downloadId", -1)
                    if (owned == null) { check(preferences.edit().clear().commit()); downloadId = -1 }
                    else if (UpdatePolicy.latest(listOf(owned!!.release), installedVersion, Build.SUPPORTED_ABIS.toList()) == null) clearDownload()
                    else if (downloadId < 0) { downloadId = downloads.find(owned!!) ?: -1; save() }
                }
            }
            val update = owned
            if (update == null) mutable.value = UpdateState(UpdatePhase.IDLE)
            else if (downloadId >= 0) monitor(update)
            else mutable.value = UpdateState(UpdatePhase.FAILED, update, error = "上次下载未能开始，请重试下载")
        } catch (error: Exception) { if (error is CancellationException) throw error; failure(error) }
    } }

    fun check() {
        if (task?.isActive == true || mutable.value.phase == UpdatePhase.READY) return
        task = scope.launch {
            mutable.value = UpdateState(UpdatePhase.CHECKING)
            try {
                withContext(Dispatchers.IO) { if (owned != null) clearDownload() }
                val releases = ReleaseClient().releases()
                check(releases.any { UpdatePolicy.compatible(it, Build.SUPPORTED_ABIS.toList()) != null }) { "暂未找到适合此设备的 Android 安装包，请稍后重试" }
                choices = UpdatePolicy.updates(releases, installedVersion, Build.SUPPORTED_ABIS.toList())
                val update = choices.firstOrNull()
                mutable.value = UpdateState(if (update == null) UpdatePhase.CURRENT else UpdatePhase.AVAILABLE, update, choices = choices)
            } catch (error: Exception) { if (error is CancellationException) throw error; failure(error) }
        }
    }

    fun select(assetId: Long) {
        if (task?.isActive == true || mutable.value.phase != UpdatePhase.AVAILABLE) return
        val selected = choices.singleOrNull { it.asset.id == assetId } ?: return
        mutable.value = UpdateState(UpdatePhase.AVAILABLE, selected, choices = choices)
    }

    fun download() {
        val update = mutable.value.update ?: return
        if (task?.isActive == true) return
        task = scope.launch {
            mutable.value = UpdateState(UpdatePhase.DOWNLOADING, update, total = update.asset.size, status = "正在准备下载")
            try {
                withContext(Dispatchers.IO) {
                    clearDownload(); owned = update; save()
                    downloadId = downloads.enqueue(update)
                    try { save() } catch (error: Exception) { downloads.remove(downloadId, update); throw error }
                }
                monitor(update)
            } catch (error: Exception) { if (error is CancellationException) throw error; failure(error, update) }
        }
    }

    fun cancel() {
        if (mutable.value.phase !in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.FAILED, UpdatePhase.READY)) return
        val old = task; old?.cancel()
        task = scope.launch {
            old?.join()
            val update = mutable.value.update
            mutable.value = mutable.value.copy(phase = UpdatePhase.RESTORING, error = null, status = "正在取消下载")
            try {
                withContext(Dispatchers.IO) { clearDownload() }
                mutable.value = UpdateState(if (update == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE, update, status = "已取消下载", choices = choices)
            } catch (error: Exception) { if (error is CancellationException) throw error; failure(error, update) }
        }
    }

    fun install(launch: (Intent) -> Unit) {
        if (task?.isActive == true || mutable.value.phase != UpdatePhase.READY) return
        val update = mutable.value.update ?: return
        task = scope.launch {
            mutable.value = mutable.value.copy(phase = UpdatePhase.VERIFYING, error = null)
            try {
                val intent = withContext(Dispatchers.IO) { val file = downloads.file(update); packages.verify(file, update); packages.intent(file) }
                mutable.value = mutable.value.copy(phase = UpdatePhase.READY)
                launch(intent)
            } catch (error: Exception) { if (error is CancellationException) throw error; failure(error, update) }
        }
    }
    fun installPermissionDenied() { mutable.value = mutable.value.copy(error = "尚未允许栖卷安装更新，安装包已保留，可稍后重试") }

    private suspend fun monitor(update: AppUpdate) {
        while (currentCoroutineContext().isActive) {
            val progress = withContext(Dispatchers.IO) { downloads.progress(downloadId) } ?: error("更新下载记录已被移除，请重新下载")
            when (progress.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    mutable.value = UpdateState(UpdatePhase.VERIFYING, update, update.asset.size, update.asset.size)
                    withContext(Dispatchers.IO) { packages.verify(downloads.file(update), update) }
                    mutable.value = mutable.value.copy(phase = UpdatePhase.READY)
                    return
                }
                DownloadManager.STATUS_FAILED -> error(when (progress.reason) {
                    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "手机存储空间不足，请腾出空间后重试"
                    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "更新存储不可用，请检查手机存储后重试"
                    404, 410 -> "该安装包已撤回，请重新检查更新"
                    else -> "安装包下载失败（${progress.reason}），请重试"
                })
                else -> mutable.value = UpdateState(UpdatePhase.DOWNLOADING, update, progress.bytes.coerceIn(0, update.asset.size), update.asset.size,
                    when {
                        progress.status == DownloadManager.STATUS_PENDING -> "等待下载"
                        progress.reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "等待网络，恢复后继续下载"
                        progress.reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "等待 Wi-Fi"
                        progress.status == DownloadManager.STATUS_PAUSED -> "连接中，系统将自动重试"
                        else -> "正在下载，离开页面后仍会继续"
                    })
            }
            delay(750)
        }
    }
    private fun save() { check(preferences.edit().putString("release", owned?.serialize()).putLong("downloadId", downloadId).commit()) { "无法保存更新下载记录，请重试" } }
    private fun clearDownload() {
        downloads.remove(downloadId, owned)
        check(preferences.edit().clear().commit()) { "无法清理更新记录，请重试" }
        downloadId = -1; owned = null
    }
    private fun failure(error: Exception, update: AppUpdate? = mutable.value.update) {
        mutable.value = mutable.value.copy(phase = UpdatePhase.FAILED, update = update, error = error.message ?: "更新失败，请检查网络和存储后重试")
    }
}

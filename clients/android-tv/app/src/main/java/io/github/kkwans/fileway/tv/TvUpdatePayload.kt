package io.github.kkwans.fileway.tv

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.system.ErrnoException
import android.system.OsConstants
import io.github.kkwans.nasfilebrowser.data.PreviewLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

internal class TvUpdatePayload(private val context: Context, private val signerPin: String) {
    private val manager = context.packageManager
    private val directory = File(context.filesDir, "tv-update")
    private val preferences = context.getSharedPreferences("tv-update-payload", Context.MODE_PRIVATE)
    private val apk = File(directory, "update.apk")

    fun device(): TvUpdateDevice = TvUpdateDevice(installed().longVersionCode, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toSet())

    fun installedSigner(): X509Certificate {
        val signer = currentSigners(installed()).singleOrNull()
            ?: throw TvUpdateError("当前应用签名不支持在线更新")
        if (signer.tvUpdateSha256() != signerPin) throw TvUpdateError("当前应用签名不支持在线更新")
        return CertificateFactory.getInstance("X.509").generateCertificate(signer.inputStream()) as X509Certificate
    }

    private fun hasPayload(owner: String, offer: TvUpdateOffer): Boolean = matches(owner, offer.manifest) &&
        apk.length() == offer.manifest.size && preferences.getLong("offset", 0) == offer.manifest.size

    fun hasSavedPayload(owner: String): Boolean = preferences.getString("owner", null) == owner && apk.length() > 0

    fun clear(owner: String? = null) {
        if (owner != null && preferences.getString("owner", null) != owner) return
        removeFile()
        preferences.edit().clear().commit()
    }

    private fun matches(owner: String, manifest: TvUpdateManifest): Boolean =
        preferences.getString("owner", null) == owner &&
            preferences.getString("sha256", null) == manifest.sha256 &&
            preferences.getLong("size", -1) == manifest.size

    private fun removeFile() {
        if (apk.exists() && !apk.delete()) throw TvUpdateError("无法清理电视上的更新暂存文件")
    }

    private fun open(owner: String, manifest: TvUpdateManifest): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw TvUpdateError("无法创建更新暂存目录")
        if (!matches(owner, manifest) || apk.length() > manifest.size) removeFile()
        val offset = minOf(apk.length(), preferences.getLong("offset", 0).coerceIn(0, manifest.size))
        if (apk.length() > offset) RandomAccessFile(apk, "rw").use { it.setLength(offset) }
        if (directory.usableSpace < (manifest.size - apk.length()) + manifest.size + FREE_RESERVE) {
            throw TvUpdateError("电视存储空间不足，请释放空间后重试")
        }
        if (!preferences.edit().putString("owner", owner).putString("sha256", manifest.sha256)
                .putLong("size", manifest.size).putLong("offset", offset).commit()) {
            throw TvUpdateError("无法保存更新下载记录")
        }
        return apk
    }

    suspend fun receive(
        owner: String,
        offer: TvUpdateOffer,
        lease: PreviewLease,
        progress: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val manifest = offer.manifest
        val file = open(owner, manifest)
        if (file.length() == manifest.size) return@withContext
        val url = URL(lease.url)
        require(url.protocol == "http" && url.host == "127.0.0.1" && url.userInfo == null && url.query == null)
        try {
            if (!transfer(url, file, manifest, progress)) {
                removeFile()
                if (!transfer(url, file, manifest, progress)) throw TvUpdateError("下载范围失效，请重新检查", recheck = true)
            }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            if (error.cause.let { it is ErrnoException && it.errno == OsConstants.ENOSPC }) {
                throw TvUpdateError("电视存储空间不足，请释放空间后重试")
            }
            throw TvUpdateError("下载连接已中断，已接收的部分可续传")
        }
    }

    private suspend fun transfer(
        url: URL,
        file: File,
        manifest: TvUpdateManifest,
        progress: (Long) -> Unit,
    ): Boolean {
        val offset = file.length()
        val connection = (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) {
                setRequestProperty("Range", "bytes=$offset-")
                setRequestProperty("If-Range", manifest.etag)
            }
        }
        try {
            val status = connection.responseCode
            when (status) {
                401, 403 -> throw TvUpdateError("当前登录已失效或没有更新权限，请重新连接", recheck = true)
                404, 410 -> throw TvUpdateError("更新已失效，请重新检查", recheck = true)
                416 -> return false
                409 -> throw TvUpdateError("服务器尚未准备好，请重试")
                502, 503, 504 -> throw TvUpdateError("服务器暂时无法提供下载，请重试")
            }
            val start = try {
                tvUpdateWriteOffset(status, connection.getHeaderField("ETag"),
                    connection.getHeaderField("Content-Length")?.toLongOrNull(),
                    connection.getHeaderField("Content-Range"), offset, manifest)
            } catch (_: IllegalArgumentException) {
                throw TvUpdateError("服务器下载内容与签名清单不一致，请重新检查", recheck = true)
            } catch (_: IllegalStateException) {
                throw TvUpdateError("服务器返回了无效的下载响应，请重试")
            }
            currentCoroutineContext().ensureActive()
            if (file.parentFile!!.usableSpace < (manifest.size - start) + manifest.size + FREE_RESERVE) {
                throw TvUpdateError("电视存储空间不足，请释放空间后重试")
            }
            connection.inputStream.use { input ->
                RandomAccessFile(file, "rw").use { output ->
                    output.setLength(start)
                    output.seek(start)
                    var received = start
                    var confirmed = start
                    var lastReport = 0L
                    progress(received)
                    val buffer = ByteArray(64 * 1024)
                    if (start != offset) checkpoint(output, start)
                    try {
                        while (received < manifest.size) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), manifest.size - received).toInt())
                            currentCoroutineContext().ensureActive()
                            if (count <= 0) throw IOException("Incomplete update")
                            output.write(buffer, 0, count)
                            received += count
                            if (received - confirmed >= CHECKPOINT_BYTES) {
                                checkpoint(output, received)
                                confirmed = received
                            }
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastReport >= 250 || received == manifest.size) {
                                progress(received)
                                lastReport = now
                            }
                        }
                        if (input.read() != -1) throw TvUpdateError("服务器下载长度与签名清单不一致", recheck = true)
                    } finally {
                        if (received != confirmed) checkpoint(output, received)
                    }
                }
            }
            return true
        } finally {
            connection.disconnect()
        }
    }

    private fun checkpoint(file: RandomAccessFile, offset: Long) {
        file.fd.sync()
        if (!preferences.edit().putLong("offset", offset).commit()) throw TvUpdateError("无法保存更新下载进度")
    }

    suspend fun verify(owner: String, offer: TvUpdateOffer): File = withContext(Dispatchers.IO) {
        val manifest = offer.manifest
        val verified = try {
            val installed = installed()
            val actualSigners = currentSigners(installed).map { it.tvUpdateSha256() }
            require(context.packageName == TV_UPDATE_PACKAGE && actualSigners == listOf(signerPin))
            require(manifest.signerSha256 == signerPin && manifest.versionCode > installed.longVersionCode)
            require(hasPayload(owner, offer))
            require(digest(apk, manifest.size) == manifest.sha256)
            // Android 9/10 only collect certificates for archives when the
            // legacy flag is present; still compare SigningInfo's current
            // signers against the installed app and the pinned certificate.
            @Suppress("DEPRECATION")
            val archiveFlags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
            val archive = manager.getPackageArchiveInfo(apk.absolutePath, archiveFlags)
                ?: error("Unreadable APK")
            require(archive.packageName == context.packageName && archive.longVersionCode == manifest.versionCode)
            require(archive.versionName == manifest.versionName && archive.applicationInfo?.minSdkVersion == manifest.minSdk)
            require(manifest.minSdk <= Build.VERSION.SDK_INT)
            require(currentSigners(archive).map { it.tvUpdateSha256() } == actualSigners)
            val abis = ZipFile(apk).use { zip ->
                zip.entries().asSequence().mapNotNull { nativeEntry.matchEntire(it.name)?.groupValues?.get(1) }.toSet()
            }
            require(abis == manifest.abis && abis.any(Build.SUPPORTED_ABIS::contains))
            true
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (!verified) {
            clear(owner)
            throw TvUpdateError("安装包完整性或签名校验失败，请重新检查", recheck = true)
        }
        apk
    }

    private fun installed(): PackageInfo = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)

    private fun currentSigners(info: PackageInfo): List<ByteArray> =
        info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()

    private suspend fun digest(file: File, expected: Long): String {
        val hash = MessageDigest.getInstance("SHA-256")
        var received = 0L
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(bytes)
                if (count < 0) break
                received += count
                require(received <= expected)
                hash.update(bytes, 0, count)
            }
        }
        require(received == expected)
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val FREE_RESERVE = 16L * 1024 * 1024
        const val CHECKPOINT_BYTES = 4L * 1024 * 1024
        val nativeEntry = Regex("lib/([a-zA-Z0-9_-]+)/[^/]+\\.so")
    }
}

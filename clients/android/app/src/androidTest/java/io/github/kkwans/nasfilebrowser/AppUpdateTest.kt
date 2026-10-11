package io.github.kkwans.nasfilebrowser

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.update.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppUpdateTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun ownedUpdate(version: String, revision: Int? = null, preview: Boolean = true, size: Long = 1024): AppUpdate {
        val suffix = revision?.let { "-r$it" }.orEmpty()
        val tag = "android-${if (preview) "preview-" else ""}$version$suffix-1234567"
        val name = "fileway-android-$version${if (preview) "-preview" else ""}$suffix.apk"
        val asset = UpdateAsset(42, name, "https://github.com/Kkwans/Fileway/releases/download/$tag/$name", size)
        return AppUpdate(AppRelease(tag, version, "Owned update", preview, listOf(asset), revision), asset)
    }
    @Test fun publishedMetadataFiltersDraftsNonAndroidAndIncompleteAssetsAndRoundTrips() {
        val json = """[
          {"tag_name":"android-preview-0.7.0-1234567","draft":false,"prerelease":true,"body":"Owned notes","assets":[
            {"id":12,"name":"fileway-android-0.7.0-preview.apk","state":"uploaded","size":1024,"browser_download_url":"https://github.com/Kkwans/fileway/releases/download/android-preview-0.7.0-1234567/fileway-android-0.7.0-preview.apk"},
            {"id":13,"name":"partial.apk","state":"open","size":1024}]},
          {"tag_name":"android-preview-9.0.0-1234567","draft":true,"assets":[]},
          {"tag_name":"Fileway-2026.10.9-v2","draft":false,"assets":[]}
        ]"""
        val releases = parseReleases(json)
        assertEquals(1, releases.size); assertEquals(1, releases.single().assets.size)
        val update = UpdatePolicy.latest(releases, "0.6.1-preview", Build.SUPPORTED_ABIS.toList())!!
        assertEquals(update.release, parseReleases("[${update.serialize()}]").single())
        assertEquals("Owned notes", update.release.notes)
        assertThrows(org.json.JSONException::class.java) { parseReleases("not json") }
    }

    @Test fun revisionMetadataRoundTripsTheFullTagWithoutChangingTheCoreVersionField() {
        val update = ownedUpdate("0.7.5", revision = 2)
        val saved = update.serialize()
        val restored = parseReleases("[$saved]").single()
        assertEquals(update.release, restored)
        assertEquals("0.7.5", restored.version)
        assertEquals(2, restored.previewRevision)
        assertEquals(AppVersion.parse("0.7.5-preview-r2"), UpdatePolicy.releaseVersion(restored))
        assertEquals(update.asset, UpdatePolicy.compatible(restored, Build.SUPPORTED_ABIS.toList()))
        assertFalse(JSONObject(saved).has("version"))
        assertFalse(JSONObject(saved).has("previewRevision"))
        assertTrue(parseReleases("[${JSONObject(saved).put("prerelease", false)}]").isEmpty())
        val wrongTag = JSONObject(saved).put("tag_name", "android-preview-0.7.5-1234567")
        assertNull(UpdatePolicy.compatible(parseReleases("[$wrongTag]").single(), Build.SUPPORTED_ABIS.toList()))
    }

    @Test fun actualSignedPackageRejectsWrongIdentitySignatureVersionAndTruncationBeforeInstaller() {
        val root = File(context.getExternalFilesDir("updates"), "owned-package-${UUID.randomUUID()}"); check(root.mkdirs())
        val file = File(root, "owned.apk")
        try {
            File(context.applicationInfo.sourceDir).copyTo(file)
            val version = checkNotNull(AppVersion.parse(BuildConfig.VERSION_NAME))
            val update = ownedUpdate(version.coreVersion, revision = version.previewRevision?.takeIf { it > 1 },
                preview = version.previewRevision != null, size = file.length())
            val packages = UpdatePackages(context); val installed = packages.installed(); val older = installed.copy(versionCode = installed.versionCode - 1)
            packages.verify(file, update, older)
            assertThrows(IllegalStateException::class.java) { packages.verify(file, update) }
            assertThrows(IllegalStateException::class.java) { packages.verify(file, update, older.copy(packageName = "owned.wrong.package")) }
            assertThrows(IllegalStateException::class.java) { packages.verify(file, update, older.copy(certificates = setOf("wrong owned certificate"))) }
            assertThrows(IllegalStateException::class.java) { packages.verify(file, update.copy(release = update.release.copy(version = "99.0.0")), older) }
            val differentRevision = ownedUpdate(version.coreVersion, revision = if (version.previewRevision == 2) 3 else 2, size = file.length())
            val mismatch = assertThrows(IllegalStateException::class.java) { packages.verify(file, differentRevision, older) }
            assertEquals("安装包版本与更新说明不一致，请重新检查更新", mismatch.message)
            val differentChannel = ownedUpdate(version.coreVersion, preview = version.previewRevision == null, size = file.length())
            assertThrows(IllegalStateException::class.java) { packages.verify(file, differentChannel, older) }
            assertThrows(IllegalStateException::class.java) { packages.verify(file, update.copy(asset = update.asset.copy(size = file.length() - 1)), older) }
            val intent = packages.intent(file)
            assertEquals(Intent.ACTION_VIEW, intent.action); assertEquals("application/vnd.android.package-archive", intent.type)
            assertEquals("content", intent.data!!.scheme); assertEquals("${context.packageName}.updates", intent.data!!.authority)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
        } finally { file.delete(); root.delete() }
    }

    /** Restores remembered metadata only; no network request or download is started. */
    @Test fun oldAndRevisedDownloadsRestoreTheirIdentityAndClearOnlyWhenAlreadyInstalled(): Unit = runBlocking {
        for (revision in listOf<Int?>(null, 2)) {
            val name = "owned-revision-restore-${UUID.randomUUID()}"
            val ownedContext = object : ContextWrapper(context) {
                override fun getSharedPreferences(ignored: String, mode: Int) = context.getSharedPreferences(name, mode)
                override fun getExternalFilesDir(type: String?): File? = context.getExternalFilesDir("updates/$name")
            }
            val preferences = ownedContext.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
            val update = ownedUpdate("0.7.5", revision = revision)
            val sentinel = File(checkNotNull(ownedContext.getExternalFilesDir("updates")), "preserve.txt").apply { writeText("preserve") }
            val scopes = mutableListOf<CoroutineScope>()
            try {
                assertTrue(preferences.edit().putString("release", update.serialize()).putLong("downloadId", -1).commit())
                val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scopes += it }
                val first = AppUpdates(ownedContext, firstScope, installedVersion = "0.7.4-preview")
                val restored = withTimeout(10_000) { first.state.first { !it.busy } }
                assertEquals(UpdatePhase.FAILED, restored.phase)
                assertEquals(update.release, restored.update?.release)
                assertEquals(update.asset, restored.update?.asset)
                assertEquals(update.release, parseReleases("[${preferences.getString("release", null)}]").single())
                firstScope.coroutineContext[Job]!!.cancelAndJoin()
                val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scopes += it }
                val installedVersion = "0.7.5-preview" + revision?.let { "-r$it" }.orEmpty()
                val second = AppUpdates(ownedContext, secondScope, installedVersion = installedVersion)
                val current = withTimeout(10_000) { second.state.first { !it.busy } }
                assertEquals(UpdatePhase.IDLE, current.phase)
                assertNull(current.update)
                assertFalse(preferences.contains("release"))
                assertEquals("preserve", sentinel.readText())
            } finally { withContext(NonCancellable) {
                scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
                UpdateDownloads(ownedContext).remove(preferences.getLong("downloadId", -1), update)
                context.deleteSharedPreferences(name)
                sentinel.delete(); ownedContext.getExternalFilesDir("updates")?.delete()
            } }
        }
    }

    /** Real public GitHub + Android DownloadManager; no server login or private media. */
    @ExternalNetworkAcceptance
    @Test fun publicReleaseDownloadContinuesAcrossControllerRecreationAndCancellationRemovesOnlyOwnedUpdate(): Unit = runBlocking {
        val name = "owned-updates-${UUID.randomUUID()}"
        val ownedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int) = context.getSharedPreferences(name, mode)
            override fun getExternalFilesDir(type: String?): File? = context.getExternalFilesDir("updates/$name")
        }
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var nextScope: CoroutineScope? = null; var second: AppUpdates? = null
        val sentinel = File(ownedContext.getExternalFilesDir("updates"), "keep-owned-note.txt"); sentinel.writeText("preserve")
        try {
            // Simulate an older UI version only. The real PackageManager version/signature
            // checks remain intact and still reject installing the current public APK.
            val first = AppUpdates(ownedContext, firstScope, installedVersion = "0.0.0-preview")
            withTimeout(10_000) { first.state.first { !it.busy } }
            withContext(Dispatchers.Main) { first.check() }
            val available = withTimeout(40_000) { first.state.first { it.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.FAILED) } }
            assertEquals(available.error, UpdatePhase.AVAILABLE, available.phase)
            withContext(Dispatchers.Main) { first.download() }
            val transferred = withTimeout(40_000) { first.state.first { it.bytes > 0 || it.phase == UpdatePhase.FAILED } }
            assertTrue("实际下载没有收到字节：${transferred.error}", transferred.bytes > 0)
            println("REAL_UPDATE_RECEIVED_BYTES=${transferred.bytes}")
            val saved = ownedContext.getSharedPreferences("app-updates", Context.MODE_PRIVATE).getLong("downloadId", -1)
            assertTrue(saved >= 0)
            firstScope.coroutineContext[Job]!!.cancelAndJoin()
            nextScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val resumed = AppUpdates(ownedContext, nextScope, installedVersion = "0.0.0-preview"); second = resumed
            val restored = withTimeout(10_000) { resumed.state.first { it.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.FAILED, UpdatePhase.READY) } }
            assertEquals(available.update!!.asset.id, restored.update!!.asset.id)
            assertEquals(saved, ownedContext.getSharedPreferences("app-updates", Context.MODE_PRIVATE).getLong("downloadId", -1))
            assertNotNull(UpdateDownloads(ownedContext).progress(saved))
            withContext(Dispatchers.Main) { resumed.cancel() }
            withTimeout(10_000) { resumed.state.first { it.phase == UpdatePhase.AVAILABLE && !it.busy } }
            assertNull(UpdateDownloads(ownedContext).progress(saved))
            assertFalse(UpdateDownloads(ownedContext).file(available.update).exists())
            assertEquals("preserve", sentinel.readText())
        } finally { withContext(NonCancellable) {
            firstScope.coroutineContext[Job]!!.cancelAndJoin()
            second?.let { withContext(Dispatchers.Main) { it.cancel() }; withTimeoutOrNull(5000) { it.state.first { state -> !state.busy && state.phase != UpdatePhase.DOWNLOADING } } }
            nextScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            val preferences = ownedContext.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
            val release = preferences.getString("release", null)?.let { parseReleases("[$it]").single() }
            val update = release?.let { UpdatePolicy.compatible(it, Build.SUPPORTED_ABIS.toList())?.let { asset -> AppUpdate(it, asset) } }
            UpdateDownloads(ownedContext).remove(preferences.getLong("downloadId", -1), update)
            context.deleteSharedPreferences(name); sentinel.delete(); ownedContext.getExternalFilesDir("updates")?.delete()
        } }
    }
}

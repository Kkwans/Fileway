package io.github.kkwans.nasfilebrowser.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private fun release(version: String, preview: Boolean = true, abi: String = "", revision: Int? = null): AppRelease {
        val suffix = revision?.let { "-r$it" }.orEmpty()
        val tag = "android-preview-$version$suffix-1234567"; val name = "fileway-android-$version-preview$suffix$abi.apk"
        return AppRelease(tag, version, "Owned test notes", preview,
            listOf(UpdateAsset(100, name, "https://github.com/Kkwans/fileway/releases/download/$tag/$name", 1024)), revision)
    }
    @Test fun numericVersionsDoNotSortLexicallyOrOfferDowngrades() {
        val versions = listOf(release("0.9.0"), release("0.10.0"), release("0.7.2"))
        assertEquals("0.10.0", UpdatePolicy.latest(versions, "0.7.0-preview", listOf("arm64-v8a"))!!.release.version)
        assertNull(UpdatePolicy.latest(versions, "0.10.0-preview", listOf("arm64-v8a")))
        assertNull(UpdatePolicy.latest(versions, "1.0.0-preview", listOf("x86_64")))
        assertEquals(AppVersion(0, 7, 0), AppVersion.parse("0.7"))
        assertNull(AppVersion.parse("0.7.evil")); assertNull(AppVersion.parse("9999999999999.1.0"))
    }
    @Test fun officialReleaseCanReplaceSameVersionPreviewButNotTheReverse() {
        val preview = release("0.7.0"); val stable = preview.copy(preview = false)
        assertFalse(UpdatePolicy.latest(listOf(preview, stable), "0.6.1-preview", listOf("arm64-v8a"))!!.release.preview)
        assertNotNull(UpdatePolicy.latest(listOf(stable), "0.7.0-preview", listOf("arm64-v8a")))
        assertNull(UpdatePolicy.latest(listOf(preview), "0.7.0", listOf("arm64-v8a")))
    }
    @Test fun eachChannelKeepsItsLatestAndOffersBothWithoutDowngradeChoices() {
        val stable = release("0.7.2", preview = false)
        val preview = release("0.8.0")
        val all = listOf(release("0.7.0", preview = false), release("0.7.1"), stable, preview)
        assertEquals(listOf(preview, stable), UpdatePolicy.updates(all, "0.6.1-preview", listOf("arm64-v8a")).map { it.release })
        assertEquals(listOf(preview), UpdatePolicy.updates(all, "0.7.2", listOf("arm64-v8a")).map { it.release })
        assertTrue(UpdatePolicy.updates(all, "0.9.0-preview", listOf("arm64-v8a")).isEmpty())
    }
    @Test fun abiSelectionPrefersCompatibleNativeBuildAndFallsBackToUniversal() {
        val universal = release("0.7.0"); val arm = release("0.7.0", abi = "-arm64-v8a"); val x64 = release("0.7.0", abi = "-x86_64")
        val all = universal.copy(assets = universal.assets + arm.assets + x64.assets)
        assertEquals(arm.assets.single(), UpdatePolicy.compatible(all, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(x64.assets.single(), UpdatePolicy.compatible(all, listOf("x86_64")))
        assertEquals(universal.assets.single(), UpdatePolicy.compatible(universal, listOf("arm64-v8a")))
        assertNull(UpdatePolicy.compatible(arm, listOf("x86_64")))
        assertNull(UpdatePolicy.compatible(universal.copy(tag = "server-0.7.0"), listOf("arm64-v8a")))
    }
    @Test fun downloadsMustBeBoundToTheExactOfficialReleaseAndAsset() {
        val item = release("0.7.0"); val asset = item.assets.single()
        assertTrue(UpdatePolicy.trusted(asset, item.tag))
        for (url in listOf(asset.url.replace("https:", "http:"), asset.url.replace("github.com", "github.com.evil.invalid"),
            asset.url.replace("Kkwans/fileway/", "another/fileway/"), asset.url + "?other=1", asset.url + "#fragment",
            asset.url.replace("github.com", "user@github.com"), asset.url.replace("0.7.0-1234567", "0.6.1-1234567"))) {
            assertFalse(url, UpdatePolicy.trusted(asset.copy(url = url), item.tag))
        }
        assertFalse(UpdatePolicy.trusted(asset.copy(size = 0), item.tag))
        assertFalse(UpdatePolicy.trusted(asset.copy(size = UpdatePolicy.MAX_APK_BYTES + 1), item.tag))
        assertFalse(UpdatePolicy.trusted(asset.copy(id = 0), item.tag))
        assertNull(UpdatePolicy.compatible(item.copy(assets = listOf(asset.copy(name = "server.exe"))), listOf("arm64-v8a")))
    }
    @Test fun published075CanonicalRepositoryCaseMatchesBothDeviceAbis() {
        val tag = "android-preview-0.7.5-65fdfb4"
        val name = "fileway-android-0.7.5-preview.apk"
        val asset = UpdateAsset(628678327, name, "https://github.com/Kkwans/Fileway/releases/download/$tag/$name", 65471469)
        val published = AppRelease(tag, "0.7.5", "", true, listOf(asset))
        assertTrue(UpdatePolicy.trusted(asset, tag))
        for (abis in listOf(listOf("arm64-v8a", "armeabi-v7a"), listOf("x86_64"))) {
            assertEquals(asset, UpdatePolicy.compatible(published, abis))
            assertEquals(asset, UpdatePolicy.latest(listOf(published), "0.7.4-preview", abis)?.asset)
            assertNull(UpdatePolicy.latest(listOf(published), "0.7.5-preview", abis))
        }
        assertTrue(UpdatePolicy.trusted(asset.copy(url = asset.url.replace("Kkwans/Fileway", "kkwans/fileway")), tag))
        assertTrue(UpdatePolicy.trusted(asset.copy(url = asset.url.replace("Kkwans/Fileway", "KKWANS/FILEWAY")), tag))
    }
    @Test fun repositoryCaseToleranceDoesNotRelaxTagAssetOrPathIdentity() {
        val item = release("0.7.0"); val asset = item.assets.single()
        for (url in listOf(
            asset.url.replace("Kkwans/fileway", "Kkwans/fileway-other"),
            asset.url.replace("Kkwans/fileway", "other/fileway"),
            asset.url.replace("Kkwans/fileway", "Kkwans/%66ileway"),
            asset.url.replace("Kkwans/fileway", "Kkwans/Fileway/.."),
            asset.url.replace("Kkwans/fileway", "Kkwans/f\u0131leway"),
            asset.url.replace("Kkwans/fileway", "Kkwan\u017F/fileway"),
            asset.url.replace("Kkwans/fileway", "\u212Akwans/fileway"),
            asset.url.replace("Kkwans/fileway", "Kkwans/F\u0130leway"),
            asset.url.replace("releases/download", "Releases/download"),
            asset.url.replace(item.tag, item.tag.uppercase()),
            asset.url.replace(asset.name, asset.name.uppercase()),
        )) assertFalse(url, UpdatePolicy.trusted(asset.copy(url = url), item.tag))
    }
    @Test fun previewRevisionsSortNumericallyAndStableWinsEverySameCorePreview() {
        val legacy = release("0.7.5")
        val r2 = release("0.7.5", revision = 2).let { it.copy(assets = it.assets.map { asset -> asset.copy(id = 999) }) }
        val r10 = release("0.7.5", revision = 10).let { it.copy(assets = it.assets.map { asset -> asset.copy(id = 1) }) }
        val abis = listOf("arm64-v8a")
        assertEquals(r10, UpdatePolicy.latest(listOf(r2, legacy, r10), "0.7.5-preview", abis)?.release)
        assertEquals(r10, UpdatePolicy.latest(listOf(r2, legacy, r10), "0.7.5-preview-r2", abis)?.release)
        assertNull(UpdatePolicy.latest(listOf(legacy, r2, r10), "0.7.5-preview-r10", abis))
        assertNull(UpdatePolicy.latest(listOf(legacy, r2, r10), "0.7.5", abis))
        assertEquals(r2, UpdatePolicy.latest(listOf(r2), "0.7.4", abis)?.release)
        val stable = release("0.7.5", preview = false)
        assertEquals(stable, UpdatePolicy.latest(listOf(r2, stable, r10), "0.7.5-preview-r10", abis)?.release)
        assertEquals(listOf(stable, r10), UpdatePolicy.updates(listOf(stable, r2, r10), "0.7.4-preview", abis).map { it.release })
    }
    @Test fun revisionTagsKeepTheCoreFieldAndRequireExactRevisionAssets() {
        val r2 = release("0.7.5", revision = 2)
        assertEquals("0.7.5", UpdatePolicy.tagVersion(r2.tag))
        assertEquals(2, UpdatePolicy.tagRevision(r2.tag))
        assertEquals("0.7.5", r2.version)
        assertEquals("0.7.5-preview-r2", r2.displayVersion)
        assertEquals(r2.assets.single(), UpdatePolicy.compatible(r2, listOf("arm64-v8a")))
        assertEquals(r2.assets.single(), UpdatePolicy.compatible(r2, listOf("x86_64")))
        assertNull(UpdatePolicy.compatible(r2.copy(previewRevision = null), listOf("arm64-v8a")))
        assertNull(UpdatePolicy.compatible(r2.copy(preview = false), listOf("arm64-v8a")))
        assertNull(UpdatePolicy.compatible(r2.copy(version = "0.7.5-preview-r2"), listOf("arm64-v8a")))
        for (name in listOf("fileway-android-0.7.5-preview.apk", "fileway-android-0.7.5-preview-r3.apk", "fileway-android-0.7.5-preview-r2-preview.apk")) {
            val asset = r2.assets.single().copy(name = name, url = "https://github.com/Kkwans/Fileway/releases/download/${r2.tag}/$name")
            assertTrue(UpdatePolicy.trusted(asset, r2.tag))
            assertNull(UpdatePolicy.compatible(r2.copy(assets = listOf(asset)), listOf("arm64-v8a")))
        }
        val arm = release("0.7.5", abi = "-arm64-v8a", revision = 2)
        assertEquals(arm.assets.single(), UpdatePolicy.compatible(r2.copy(assets = r2.assets + arm.assets), listOf("arm64-v8a")))
        assertNull(UpdatePolicy.compatible(arm, listOf("x86_64")))
    }
    @Test fun malformedRevisionNumbersNeverFallBackToLegacyPreview() {
        for (suffix in listOf("r0", "r-1", "r+2", "r02", "r2x", "r1000001", "r999999999999999999999")) {
            assertNull(suffix, AppVersion.parse("0.7.5-preview-$suffix"))
            assertNull(suffix, UpdatePolicy.tagVersion("android-preview-0.7.5-$suffix-1234567"))
        }
        assertNull(UpdatePolicy.tagVersion("android-0.7.5-r2-1234567"))
        assertNull(AppVersion.parse("0.7.5-preview.2"))
        assertEquals(AppVersion(0, 7, 5, 1), AppVersion.parse("0.7.5-preview-real-media-diagnostic"))
        assertEquals(AppVersion(0, 7, 5, 2), AppVersion.parse("0.7.5-preview-r2-native-diagnostic"))
    }
    @Test fun archiveIdentityIncludesPreviewRevisionAndChannel() {
        val legacy = release("0.7.5"); val r2 = release("0.7.5", revision = 2)
        assertEquals(AppVersion.parse("0.7.5-preview"), UpdatePolicy.releaseVersion(legacy))
        assertEquals(AppVersion.parse("0.7.5-preview-r2"), UpdatePolicy.releaseVersion(r2))
        assertNotEquals(AppVersion.parse("0.7.5-preview"), UpdatePolicy.releaseVersion(r2))
        assertNotEquals(AppVersion.parse("0.7.5-preview-r3"), UpdatePolicy.releaseVersion(r2))
        assertNotEquals(AppVersion.parse("0.7.5"), UpdatePolicy.releaseVersion(r2))
        assertEquals(AppVersion.parse("0.7.5"), UpdatePolicy.releaseVersion(legacy.copy(preview = false)))
    }
    @Test fun originalStableTagAndUnversionedPreviewAssetsRemainCompatible() {
        val old = release("0.7", preview = false)
        val tag = old.tag.replace("android-preview-", "android-")
        val stable = old.copy(tag = tag, assets = old.assets.map { it.copy(url = it.url.replace(old.tag, tag)) })
        assertEquals("0.7", UpdatePolicy.tagVersion(tag))
        assertNull(UpdatePolicy.tagRevision(tag))
        assertEquals(stable.assets.single(), UpdatePolicy.compatible(stable, listOf("arm64-v8a")))
        assertEquals(AppVersion(0, 7, 0), UpdatePolicy.releaseVersion(stable))
    }
}

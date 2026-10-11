package io.github.kkwans.nasfilebrowser.update

import java.net.URI

internal data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString() = "$major.$minor.$patch"
    companion object {
        fun parse(value: String): AppVersion? {
            val match = Regex("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-preview(?:-.*)?)?$").matchEntire(value) ?: return null
            val parts = (1..3).map { index -> match.groupValues[index].ifEmpty { "0" }.toIntOrNull()?.takeIf { it <= 1_000_000 } ?: return null }
            return AppVersion(parts[0], parts[1], parts[2])
        }
    }
}

internal data class UpdateAsset(val id: Long, val name: String, val url: String, val size: Long)
internal data class AppRelease(val tag: String, val version: String, val notes: String, val preview: Boolean, val assets: List<UpdateAsset>)
internal data class AppUpdate(val release: AppRelease, val asset: UpdateAsset)

/** Public Android release contract. Never send server credentials to GitHub. */
internal object UpdatePolicy {
    const val REPOSITORY = "https://github.com/Kkwans/Fileway"
    const val RELEASES = "https://api.github.com/repos/Kkwans/Fileway/releases?per_page=100"
    const val MAX_APK_BYTES = 512L * 1024 * 1024
    private val tagPattern = Regex("^android-(?:preview-)?(\\d+\\.\\d+(?:\\.\\d+)?)(?:-[0-9a-f]{7,40})?$")
    fun tagVersion(tag: String): String? = tagPattern.matchEntire(tag)?.groupValues?.get(1)?.takeIf { AppVersion.parse(it) != null }
    fun trusted(asset: UpdateAsset, tag: String): Boolean = runCatching {
        val uri = URI(asset.url)
        val path = uri.rawPath?.split('/').orEmpty()
        asset.id > 0 && asset.size in 1..MAX_APK_BYTES && tagVersion(tag) != null &&
            Regex("[A-Za-z0-9._-]+\\.apk").matches(asset.name) &&
            uri.scheme == "https" && uri.host == "github.com" && uri.port in listOf(-1, 443) &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            // GitHub owner/repository names are case insensitive. Release tags
            // and asset names retain their exact identity; never lowercase the URL.
            path.size == 7 && path[0].isEmpty() &&
            path[1].equals("Kkwans", ignoreCase = true) && path[2].equals("Fileway", ignoreCase = true) &&
            path.drop(3) == listOf("releases", "download", tag, asset.name)
    }.getOrDefault(false)

    fun compatible(release: AppRelease, abis: List<String>): UpdateAsset? {
        if (tagVersion(release.tag) != release.version) return null
        val versions = listOf(release.version, AppVersion.parse(release.version).toString()).distinct()
        val prefixes = versions.flatMap { listOf("fileway-android-$it-preview", "fileway-android-$it") }
        // Prefer a supported ABI-specific APK; current releases contain one universal APK.
        val suffixes = abis.filter { it in listOf("arm64-v8a", "x86_64") }.distinct().map { "-$it.apk" } + listOf("-universal.apk", ".apk")
        return suffixes.firstNotNullOfOrNull { suffix -> release.assets.singleOrNull { asset ->
            prefixes.any { asset.name == it + suffix } && trusted(asset, release.tag)
        } }
    }

    fun latest(releases: List<AppRelease>, installed: String, abis: List<String>): AppUpdate? {
        return updates(releases, installed, abis).firstOrNull()
    }

    /** One newest installable version per channel, in recommendation order. */
    fun updates(releases: List<AppRelease>, installed: String, abis: List<String>): List<AppUpdate> {
        val current = AppVersion.parse(installed) ?: error("当前版本信息无法识别，请反馈此问题")
        return releases.mapNotNull { release -> compatible(release, abis)?.let { AppUpdate(release, it) } }
            .filter { val version = AppVersion.parse(it.release.version)!!
                version > current || (version == current && "preview" in installed && !it.release.preview) }
            .groupBy { it.release.preview }.values.map { channel -> channel.maxWith(compareBy<AppUpdate> {
                AppVersion.parse(it.release.version)!! }.thenBy { it.asset.id }) }
            .sortedWith(compareByDescending<AppUpdate> { AppVersion.parse(it.release.version)!! }.thenBy { it.release.preview })
    }
}

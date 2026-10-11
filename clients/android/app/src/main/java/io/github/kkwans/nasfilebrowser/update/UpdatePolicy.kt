package io.github.kkwans.nasfilebrowser.update

import java.net.URI

internal data class AppVersion(val major: Int, val minor: Int, val patch: Int, val previewRevision: Int? = null) : Comparable<AppVersion> {
    val coreVersion: String get() = "$major.$minor.$patch"
    override fun compareTo(other: AppVersion): Int {
        val core = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        return if (core != 0) core else compareValues(previewRevision ?: Int.MAX_VALUE, other.previewRevision ?: Int.MAX_VALUE)
    }
    override fun toString() = coreVersion + when (previewRevision) {
        null -> ""
        1 -> "-preview"
        else -> "-preview-r$previewRevision"
    }
    companion object {
        const val MAX_REVISION = 1_000_000
        fun parse(value: String): AppVersion? {
            val match = Regex("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?(-preview(?:-(.*))?)?$").matchEntire(value) ?: return null
            val parts = (1..3).map { index -> match.groupValues[index].ifEmpty { "0" }.toIntOrNull()?.takeIf { it <= 1_000_000 } ?: return null }
            val revision = if (match.groupValues[4].isEmpty()) null else {
                // Preserve existing diagnostic suffixes while recognizing an explicit
                // numeric revision. Malformed r0/r-1/r2x must not become legacy r1.
                val token = match.groupValues[5].substringBefore('-')
                if (Regex("r(?:[0-9+].*|$)").matches(token)) {
                    val digits = Regex("r([1-9][0-9]*)").matchEntire(token)?.groupValues?.get(1) ?: return null
                    digits.toIntOrNull()?.takeIf { it in 1..MAX_REVISION } ?: return null
                } else 1
            }
            return AppVersion(parts[0], parts[1], parts[2], revision)
        }
    }
}

internal data class UpdateAsset(val id: Long, val name: String, val url: String, val size: Long)
internal data class AppRelease(val tag: String, val version: String, val notes: String, val preview: Boolean,
    val assets: List<UpdateAsset>, val previewRevision: Int? = null) {
    // Keep version as the original core-version field. Revision identity comes
    // from the immutable tag, including when restoring the old persisted JSON.
    val displayVersion: String get() = if (preview && previewRevision != null) "$version-preview-r$previewRevision" else version
}
internal data class AppUpdate(val release: AppRelease, val asset: UpdateAsset)

/** Public Android release contract. Never send server credentials to GitHub. */
internal object UpdatePolicy {
    const val REPOSITORY = "https://github.com/Kkwans/Fileway"
    const val RELEASES = "https://api.github.com/repos/Kkwans/Fileway/releases?per_page=100"
    const val MAX_APK_BYTES = 512L * 1024 * 1024
    private data class ReleaseTag(val version: String, val revision: Int?)
    private val tagPattern = Regex("^android-(preview-)?(\\d+\\.\\d+(?:\\.\\d+)?)(?:-r([1-9][0-9]*))?(?:-[0-9a-f]{7,40})?$")
    private fun releaseTag(tag: String): ReleaseTag? {
        val match = tagPattern.matchEntire(tag) ?: return null
        val version = match.groupValues[2].takeIf { AppVersion.parse(it) != null } ?: return null
        val digits = match.groupValues[3]
        val revision = if (digits.isEmpty()) null else {
            if (match.groupValues[1].isEmpty()) return null
            digits.toIntOrNull()?.takeIf { it in 1..AppVersion.MAX_REVISION } ?: return null
        }
        return ReleaseTag(version, revision)
    }
    fun tagVersion(tag: String): String? = releaseTag(tag)?.version
    fun tagRevision(tag: String): Int? = releaseTag(tag)?.revision
    fun releaseVersion(release: AppRelease): AppVersion? {
        val tag = releaseTag(release.tag) ?: return null
        if (tag.version != release.version || tag.revision != release.previewRevision ||
            (tag.revision != null && !release.preview)) return null
        val core = AppVersion.parse(release.version) ?: return null
        return core.copy(previewRevision = if (release.preview) tag.revision ?: 1 else null)
    }
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
            path.subList(1, 3).all { segment -> segment.all { character ->
                character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' || character == '-' || character == '_'
            } } &&
            path[1].equals("Kkwans", ignoreCase = true) && path[2].equals("Fileway", ignoreCase = true) &&
            path.drop(3) == listOf("releases", "download", tag, asset.name)
    }.getOrDefault(false)

    fun compatible(release: AppRelease, abis: List<String>): UpdateAsset? {
        val version = releaseVersion(release) ?: return null
        val versions = listOf(release.version, version.coreVersion).distinct()
        val prefixes = if (release.previewRevision != null) versions.map { "fileway-android-$it-preview-r${release.previewRevision}" }
            else versions.flatMap { listOf("fileway-android-$it-preview", "fileway-android-$it") }
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
            .filter { releaseVersion(it.release)!! > current }
            .groupBy { it.release.preview }.values.map { channel -> channel.maxWith(compareBy<AppUpdate> {
                releaseVersion(it.release)!! }.thenBy { it.asset.id }) }
            .sortedWith(compareByDescending<AppUpdate> { releaseVersion(it.release)!! }.thenBy { it.release.preview })
    }
}

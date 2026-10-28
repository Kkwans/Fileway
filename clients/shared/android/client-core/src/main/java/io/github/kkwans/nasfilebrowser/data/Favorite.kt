package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef

data class FavoritePathIdentity(val wirePath: String = "", val openable: Boolean = false)

/** Reuse the existing strict segment/UTF-8 checks, never decode opaque bytes
 * into a display string to choose a resource. Legacy lost-byte rows stay closed. */
fun favoritePathIdentity(path: String, wirePath: String?, verified: Boolean?): FavoritePathIdentity {
    if (verified == false || wirePath.isNullOrEmpty() && (verified == true || path.contains('\uFFFD'))) return FavoritePathIdentity()
    return runCatching {
        val target = favoriteRecordTarget(ResourceRef(path, wirePath.orEmpty(), "favorite", false, "", 0))
        FavoritePathIdentity(target.wirePath, true)
    }.getOrDefault(FavoritePathIdentity())
}

fun favoriteRecordTarget(file: ResourceRef): RecentAccessTarget = try {
    recentAccessRecordTarget(file)
} catch (error: IllegalArgumentException) {
    throw IllegalArgumentException("收藏原始路径无法确认，请从文件列表重新选择", error)
}

fun favoriteWireIdentity(wirePath: String): String = recentAccessWireIdentity(wirePath)

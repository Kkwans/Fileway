package io.github.kkwans.nasfilebrowser.data

data class ServerPermissions(val known: Boolean = false, val admin: Boolean = false, val create: Boolean = false,
    val delete: Boolean = false, val modify: Boolean = false, val download: Boolean = false, val rename: Boolean = false, val execute: Boolean = false)

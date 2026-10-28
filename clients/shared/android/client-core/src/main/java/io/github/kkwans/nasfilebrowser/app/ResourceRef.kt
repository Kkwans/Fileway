package io.github.kkwans.nasfilebrowser.app

data class ResourceRef(val path: String, val wirePath: String, val name: String, val directory: Boolean, val type: String, val size: Long, val modified: String = "", val downloadId: String = "", val previewWidth: Int = 0, val previewHeight: Int = 0, val created: String = "")

package io.github.kkwans.fileway.tv

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.mediaKey
import io.github.kkwans.nasfilebrowser.app.mediaKind
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

private const val FILE_PAGE_BYTES = 24 * 1024

internal fun remoteFileId(file: ResourceRef): String = MessageDigest.getInstance("SHA-256")
    .digest(file.mediaKey.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun remoteFileJson(file: ResourceRef, name: String = file.name): JSONObject = JSONObject()
    .put("id", remoteFileId(file)).put("name", name).put("directory", file.directory)
    .put("kind", file.mediaKind()?.name ?: "FILE").put("size", file.size)

internal fun remoteBrowsePage(files: List<ResourceRef>, offset: Int, count: Int): JSONObject {
    val items = JSONArray()
    val page = JSONObject().put("files", items).put("total", files.size)
    val envelopeBytes = remoteWireBytes(page.toString())
    var bytes = envelopeBytes
    val start = offset.coerceIn(0, files.size)
    for (file in files.subList(start, files.size).asSequence().take(count)) {
        val row = remoteFileJson(file)
        var rowBytes = remoteWireBytes(row.toString())
        if (rowBytes + envelopeBytes > FILE_PAGE_BYTES) {
            // A server name larger than a whole page must not stall pagination.
            row.put("name", file.name.take(120).trimEnd { it.isHighSurrogate() } + "…")
            rowBytes = remoteWireBytes(row.toString())
        }
        val next = bytes + rowBytes + if (items.length() > 0) 1 else 0
        if (next > FILE_PAGE_BYTES) break
        items.put(row)
        bytes = next
    }
    return page
}

/** The Go encoder re-escapes these characters when it wraps the payload. */
private fun remoteWireBytes(json: String): Int {
    var bytes = json.toByteArray(Charsets.UTF_8).size
    for (char in json) bytes += when (char) {
        '<', '>', '&' -> 5
        '\u2028', '\u2029' -> 3
        else -> 0
    }
    return bytes
}

/** Leave room for the native host's connection envelope under its 48 KiB limit.
 * Prefixes are previews; total counts and selected IDs remain authoritative. */
internal fun boundRemoteSnapshot(value:JSONObject) {
    val content=value.getJSONObject("content")
    fun clip(objectValue:JSONObject?,key:String) {
        val text=objectValue?.opt(key)
        if(text is String&&text.length>2048)objectValue.put(key,text.take(2048))
    }
    clip(content,"error");clip(content,"notice");clip(content.optJSONObject("handoff"),"message")
    val playback=content.optJSONObject("playback")
    val arrays=listOfNotNull(content.optJSONArray("files"),playback?.optJSONArray("audio"),playback?.optJSONArray("subtitles"),
        playback?.optJSONArray("sourceSubtitles"),playback?.optJSONArray("sidecars"),content.optJSONObject("queue")?.optJSONArray("items"))
    var bytes=remoteWireBytes(value.toString())
    data class Preview(val array:JSONArray,var size:Int)
    val sizes=arrays.map{Preview(it,remoteWireBytes(it.toString()))}
    while(bytes>44*1024) {
        val preview=sizes.filter{it.array.length()>0}.maxByOrNull{it.size}?:break
        val array=preview.array
        val last=remoteWireBytes(array.getJSONObject(array.length()-1).toString())
        val removed=last+if(array.length()>1)1 else 0
        array.remove(array.length()-1);bytes-=removed;preview.size-=removed
    }
    check(remoteWireBytes(value.toString())<=44*1024){"遥控状态超出容量"}
}

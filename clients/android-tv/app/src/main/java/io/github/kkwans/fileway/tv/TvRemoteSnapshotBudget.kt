package io.github.kkwans.fileway.tv

import org.json.JSONArray
import org.json.JSONObject

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
    var bytes=value.toString().toByteArray(Charsets.UTF_8).size
    data class Preview(val array:JSONArray,var size:Int)
    val sizes=arrays.map{Preview(it,it.toString().toByteArray(Charsets.UTF_8).size)}
    while(bytes>44*1024) {
        val preview=sizes.filter{it.array.length()>0}.maxByOrNull{it.size}?:break
        val array=preview.array
        val last=array.getJSONObject(array.length()-1).toString().toByteArray(Charsets.UTF_8).size
        val removed=last+if(array.length()>1)1 else 0
        array.remove(array.length()-1);bytes-=removed;preview.size-=removed
    }
    check(value.toString().toByteArray(Charsets.UTF_8).size<=44*1024){"遥控状态超出容量"}
}

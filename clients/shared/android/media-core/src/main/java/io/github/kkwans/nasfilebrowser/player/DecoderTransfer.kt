package io.github.kkwans.nasfilebrowser.player

import androidx.media3.common.C
import org.json.JSONObject

/** Source Format metadata only: no player track handles, URLs or credentials. */
fun DecoderTrackIdentity.transferJson()=JSONObject().put("type",type).put("id",id).put("label",label)
    .put("language",language).put("mime",mime).put("codecs",codecs).put("roleFlags",roleFlags)
    .put("selectionFlags",selectionFlags).put("channels",channels).put("sampleRate",sampleRate)
    .put("accessibilityChannel",accessibilityChannel)

fun decoderTransferTrack(value:JSONObject,type:Int):DecoderTrackIdentity {
    require(type in setOf(C.TRACK_TYPE_AUDIO,C.TRACK_TYPE_TEXT) && value.getInt("type")==type)
    fun field(name:String):String?=if(!value.has(name)||value.isNull(name))null else value.getString(name).also{require(it.length<=1024)}
    return DecoderTrackIdentity(type,field("id"),field("label"),field("language"),field("mime"),field("codecs"),
        value.getInt("roleFlags"),value.getInt("selectionFlags"),value.getInt("channels"),value.getInt("sampleRate"),value.getInt("accessibilityChannel"))
}

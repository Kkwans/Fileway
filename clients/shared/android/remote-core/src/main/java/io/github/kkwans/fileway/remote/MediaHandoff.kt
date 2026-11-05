package io.github.kkwans.fileway.remote

import io.github.kkwans.nasfilebrowser.app.MediaKind
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.mediaKind
import io.github.kkwans.nasfilebrowser.data.recentAccessWireIdentity
import io.github.kkwans.nasfilebrowser.data.resourceWireBytes
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Additive payloads for media.open / queue.set. No origin, credentials or media URL. */
const val HANDOFF_QUEUE_LIMIT = 10_000
const val HANDOFF_QUEUE_BYTES = 2 * 1024 * 1024
const val HANDOFF_CHUNK_BYTES = 40 * 1024
private const val HANDOFF_ITEM_BYTES = 24 * 1024
private val handoffId = Regex("[A-Za-z0-9_-]{16,96}")
private val digestPattern = Regex("[a-f0-9]{64}")

data class HandoffItem(val path:String,val wirePath:String,val name:String,val type:String,val size:Long,val modified:String) {
    init {
        require(path.startsWith('/') && path.length<=8192 && !path.contains('\u0000')) { "文件路径无效" }
        require(wirePath.length<=16384 && resourceWireBytes(wirePath).isNotEmpty()) { "原始文件路径无效" }
        require(!wirePath.startsWith("/@archive-preview/")) { "包内临时文件不能直接交给 TV" }
        require(name.isNotBlank() && name.length<=1024 && type.length<=32 && size>=0 && modified.length<=128) { "媒体描述无效" }
        require(mediaKind(name,false,type)!=null) { "TV 接力仅支持视频、音乐和图片" }
        require(canonical().size<=HANDOFF_ITEM_BYTES) { "媒体路径过长，无法通过遥控通道传送" }
    }
    val key:String get()=recentAccessWireIdentity(wirePath)
    val kind:MediaKind get()=requireNotNull(mediaKind(name,false,type))
    fun resource()=ResourceRef(path,wirePath,name,false,type,size,modified)
    fun json()=JSONObject().put("path",path).put("wirePath",wirePath).put("name",name).put("type",type).put("size",size).put("modified",modified)
    // Ordered fields, independent of JSONObject's key iteration order. Preserve
    // opaque wire bytes; never decode them into a lossy display path.
    fun canonical():ByteArray=JSONArray(listOf(path,wirePath,name,type,size,modified)).toString().toByteArray(Charsets.UTF_8)
    companion object {
        fun from(value:JSONObject)=HandoffItem(value.getString("path"),value.getString("wirePath"),value.getString("name"),value.getString("type"),value.getLong("size"),value.getString("modified"))
        fun from(file:ResourceRef):HandoffItem {
            require(!file.directory && file.downloadId.isEmpty()) { "本机下载和文件夹不能交给 TV 原生播放" }
            return HandoffItem(file.path,file.wirePath,file.name,file.type,file.size,file.modified)
        }
    }
}

data class HandoffQueueManifest(val count:Int,val index:Int,val sha256:String) {
    init { require(count in 1..HANDOFF_QUEUE_LIMIT && index in 0 until count && digestPattern.matches(sha256)) { "播放列表描述无效" } }
    fun json()=JSONObject().put("count",count).put("index",index).put("sha256",sha256)
    companion object { fun from(value:JSONObject)=HandoffQueueManifest(value.getInt("count"),value.getInt("index"),value.getString("sha256")) }
}

data class HandoffFence(val connectionId:String,val sessionEpoch:Long,val mediaEpoch:Long)

/** Complete immutable phone snapshot, validated before any TV state changes. */
class HandoffQueue private constructor(val items:List<HandoffItem>,val manifest:HandoffQueueManifest) {
    fun chunks(id:String):List<JSONObject> {
        require(handoffId.matches(id))
        val result=mutableListOf<JSONObject>()
        var offset=0
        while(offset<items.size) {
            val page=JSONArray()
            val body=JSONObject().put("handoffId",id).put("offset",offset).put("items",page)
            var count=0
            while(offset+count<items.size) {
                page.put(items[offset+count].json())
                if(body.toString().toByteArray(Charsets.UTF_8).size>HANDOFF_CHUNK_BYTES) { page.remove(page.length()-1);break }
                count++
            }
            check(count>0) { "媒体路径过长，无法传输播放列表" }
            result+=body;offset+=count
        }
        return result
    }
    companion object {
        fun snapshot(items:List<HandoffItem>,index:Int):HandoffQueue {
            require(items.size in 1..HANDOFF_QUEUE_LIMIT && index in items.indices) { "播放列表过大或当前项已变化" }
            require(items.map{it.key}.distinct().size==items.size) { "播放列表包含重复资源" }
            require(items.all{it.kind==items[index].kind}) { "播放列表的媒体类型不一致" }
            val digest=MessageDigest.getInstance("SHA-256")
            var bytes=0
            items.forEach { val encoded=it.canonical();bytes+=encoded.size+1;require(bytes<=HANDOFF_QUEUE_BYTES){"播放列表超过 2 MiB，请缩小列表后重试"};digest.update(encoded);digest.update(10.toByte()) }
            return HandoffQueue(items.toList(),HandoffQueueManifest(items.size,index,digest.digest().hex()))
        }
    }
}

/** One generation-bound transaction. A partial queue never replaces playback. */
class HandoffQueueReceiver(
    val id:String,private val fence:HandoffFence,private val manifest:HandoffQueueManifest,
    private val selected:HandoffItem,private val nowMs:()->Long = {System.nanoTime()/1_000_000},
) {
    private val startedAt=nowMs()
    private val rows=mutableListOf<HandoffItem>()
    private val keys=mutableSetOf<String>()
    private var bytes=0
    private var closed=false
    val received:Int @Synchronized get()=rows.size
    init { require(handoffId.matches(id)) { "接力标识无效" } }
    @Synchronized fun append(current:HandoffFence,value:JSONObject):Int {
        check(!closed && current==fence && nowMs()-startedAt in 0..120_000) { "播放列表接力已失效，请重新发起" }
        require(value.getString("handoffId")==id) { "播放列表接力已变化" }
        require(value.toString().toByteArray(Charsets.UTF_8).size<=HANDOFF_CHUNK_BYTES) { "播放列表分片过大" }
        val offset=value.getInt("offset")
        val input=value.getJSONArray("items")
        require(input.length() in 1..manifest.count && offset>=0 && offset<=rows.size && offset.toLong()+input.length()<=manifest.count) { "播放列表分片顺序无效" }
        val incoming=(0 until input.length()).map{HandoffItem.from(input.getJSONObject(it))}
        if(offset<rows.size) {
            require(offset+incoming.size<=rows.size && rows.subList(offset,offset+incoming.size)==incoming) { "重复分片内容不一致" }
            return rows.size
        }
        require(incoming.all{it.kind==selected.kind}) { "播放列表的媒体类型不一致" }
        val newKeys=incoming.map{it.key}
        require(newKeys.distinct().size==newKeys.size && newKeys.none{it in keys}) { "播放列表包含重复资源" }
        val added=incoming.sumOf{it.canonical().size+1}
        require(bytes+added<=HANDOFF_QUEUE_BYTES) { "播放列表超过 TV 的接收预算" }
        rows.addAll(incoming);keys.addAll(newKeys);bytes+=added
        return rows.size
    }
    @Synchronized fun commit(current:HandoffFence):List<ResourceRef> {
        check(!closed && current==fence && nowMs()-startedAt in 0..120_000) { "播放列表接力已失效" }
        check(rows.size==manifest.count) { "播放列表尚未接收完整" }
        require(rows[manifest.index]==selected) { "接力当前项与播放列表不一致" }
        require(HandoffQueue.snapshot(rows,manifest.index).manifest==manifest) { "播放列表完整性校验失败" }
        val result=rows.map{it.resource()}
        cancel()
        return result
    }
    @Synchronized fun cancel() { closed=true;rows.clear();keys.clear();bytes=0 }
}

private fun ByteArray.hex()=joinToString(""){"%02x".format(it)}

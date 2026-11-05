package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.fileway.remote.HandoffItem
import io.github.kkwans.fileway.remote.HandoffQueue
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Phone-owned immutable request. The closures never cross the control channel. */
internal class PhoneTvHandoff(
    val source:SessionContext,val item:HandoffItem,val queue:HandoffQueue,val identity:String,
    private val playback:JSONObject,private val external:JSONObject?,
    private val current:()->Boolean,private val accepted:()->Unit,
) {
    val id:String=UUID.randomUUID().toString()
    private val completed=AtomicBoolean()
    fun requireCurrent() { check(current()){ "手机服务器或播放内容已变化，请重新发起接力" } }
    fun payload(instance:JSONObject):JSONObject {
        requireCurrent()
        return JSONObject().put("handoff",JSONObject().put("id",id).put("instance",JSONObject()
            .put("id",instance.getString("id")).put("publicKey",instance.getString("publicKey")))
            .put("item",item.json()).put("queue",queue.manifest.json()).put("identity",identity)
            .put("playback",JSONObject(playback.toString())).apply { external?.let{put("externalSubtitle",JSONObject(it.toString()))} })
    }
    fun confirmReady() { requireCurrent();if(completed.compareAndSet(false,true))accepted() }
}

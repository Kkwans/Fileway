package io.github.kkwans.fileway.remote

import android.content.Context
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import io.github.kkwans.nasfilebrowser.data.NasSession
import io.github.kkwans.nasfilebrowser.data.ServerProfile
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private fun authMessage(purpose:String,vararg fields:String)=JSONArray(listOf("fileway.device-auth.v1",purpose)+fields).toString().toByteArray(Charsets.UTF_8)
private fun authNonce()=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also{SecureRandom().nextBytes(it)})
data class DeviceGrantRequest(val origin:String,val instance:JSONObject,val request:JSONObject,val controllerId:String)

/** Separate device authorization transport. Every endpoint is fixed and same
 * origin; discovery never supplies an authenticated resource URL. */
class DeviceAuthorization(context:Context,private val store:RemoteStore=RemoteStore(context)){
    private suspend fun <T> source(origin:String,block:suspend (String)->T):T{
        val uri=URI(origin)
        require(uri.scheme in setOf("http","https")&&uri.host!=null&&uri.userInfo==null&&uri.rawQuery==null&&uri.fragment==null&&uri.port in -1..65535){"服务地址无效"}
        // TV remains direct-only. A Tailscale-only phone address cannot be
        // silently replaced by an unauthenticated LAN advertisement.
        val session=NativeTransport.call(JSONObject().put("op","open").put("baseUrl",origin).put("network","direct")) as String
        try{return block(session)}finally{withContext(NonCancellable){NativeTransport.call(JSONObject().put("op","close_session").put("session",session))}}
    }
    private suspend fun post(session:String,endpoint:String,body:JSONObject):JSONObject{
        val result=NativeTransport.call(JSONObject().put("op","request").put("session",session).put("method","POST").put("endpoint",endpoint).put("body",body)) as JSONObject
        check(result.getInt("status")==200){when(result.getInt("status")){401,403->"设备授权已失效，请在手机重新授权";404,405,501->"当前服务端不支持手机授权登录，请升级服务端";else->"服务端暂未完成设备授权"}}
        return JSONObject(result.getString("body"))
    }
    private suspend fun verifyInstance(session:String,instance:JSONObject){
        val nonce=authNonce();val proof=post(session,"/api/device-auth/instance-proof",JSONObject().put("nonce",nonce))
        check(proof.getString("id")==instance.getString("id")&&proof.getString("publicKey")==instance.getString("publicKey")){"TV连接的服务实例与手机不一致"}
        withContext(Dispatchers.IO){
            val key=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getUrlDecoder().decode(instance.getString("publicKey"))))
            val valid=Signature.getInstance("SHA256withECDSA").run{initVerify(key);update(authMessage("instance",instance.getString("id"),nonce));verify(Base64.getUrlDecoder().decode(proof.getString("signature")))}
            check(valid){"服务实例身份证明无效"}
        }
    }
    private suspend fun publicIdentity():Pair<String,String>{
        val identity=store.tvIdentity()
        val key=withContext(Dispatchers.IO){CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(identity.getString("certificatePem").toByteArray(Charsets.US_ASCII))).publicKey.encoded}
        return identity.getString("deviceId") to Base64.getUrlEncoder().withoutPadding().encodeToString(key)
    }
    private suspend fun sign(message:ByteArray):String=withContext(Dispatchers.IO){
        val identity=store.tvIdentity()
        val pem=identity.getString("privateKeyPem").replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replace(Regex("\\s"),"")
        val bytes=Base64.getDecoder().decode(pem)
        try{
            val key=KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
            Base64.getUrlEncoder().withoutPadding().encodeToString(Signature.getInstance("SHA256withECDSA").run{initSign(key);update(message);sign()})
        }finally{bytes.fill(0)}
    }
    suspend fun request(origin:String,instance:JSONObject,controllerId:String):DeviceGrantRequest=source(origin){session->
        verifyInstance(session,instance)
        val (id,key)=publicIdentity()
        val request=post(session,"/api/device-auth/requests",JSONObject().put("deviceId",id).put("publicKey",key).put("name",android.os.Build.MODEL.take(40)))
        check(request.getString("deviceId")==id&&request.getString("publicKey")==key){"服务端返回的TV身份不匹配"}
        DeviceGrantRequest(origin,JSONObject(instance.toString()),request,controllerId)
    }
    suspend fun exchange(pending:DeviceGrantRequest):JSONObject=source(pending.origin){session->
        verifyInstance(session,pending.instance)
        val r=pending.request
        // Keep this nonce on the pending request for uncertain-result retries.
        val nonce=r.optString("localExchangeNonce").ifBlank{authNonce().also{r.put("localExchangeNonce",it)}}
        val proof=sign(authMessage("exchange",pending.instance.getString("id"),r.getString("id"),r.getString("challenge"),nonce))
        val response=post(session,"/api/device-auth/exchange",JSONObject().put("id",r.getString("id")).put("nonce",nonce).put("proof",proof))
        check(response.getString("serverId")==pending.instance.getString("id")){"设备授权来源不一致"}
        response.put("instance",pending.instance).put("origin",pending.origin)
    }
    suspend fun save(profile:ServerProfile,credential:JSONObject):StoredDeviceGrant=withContext(Dispatchers.IO){
        val principal=NasSession.parseIdentity(credential.getString("token"))
        check(principal.id==credential.getLong("userId")){"设备账号不一致"}
        val ref="tv-device-${credential.getString("serverId")}-${principal.id}-${credential.getString("sessionId")}"
        store.vault.write(ref,credential.toString().toByteArray(Charsets.UTF_8))
        val row=StoredDeviceGrant(credential.getString("serverId"),principal.id,profile.address,profile.id,credential.getString("sessionId"),ref,"PREPARED",System.currentTimeMillis())
        store.dao.saveGrant(row);row
    }
    suspend fun refresh(profileId:String):JSONObject?{
        val row=store.dao.profileGrant(profileId)?:return null
        val credential=withContext(Dispatchers.IO){JSONObject((store.vault.read(row.credentialRef)?:error("设备凭据读取失败，请在手机重新授权")).toString(Charsets.UTF_8))}
        val nonce=credential.optString("pendingRefreshNonce").ifBlank{authNonce().also{credential.put("pendingRefreshNonce",it)}}
        // Persist intent before rotating remotely. A process death can repeat
        // exactly this nonce rather than stranding the previous refresh token.
        withContext(Dispatchers.IO){store.vault.write(row.credentialRef,credential.toString().toByteArray(Charsets.UTF_8))}
        return source(row.origin){session->
            verifyInstance(session,credential.getJSONObject("instance"))
            val secret=credential.getString("refresh")
            val proof=sign(authMessage("refresh",row.serverId,row.sessionId,secret,nonce))
            val next=post(session,"/api/device-auth/refresh",JSONObject().put("id",row.sessionId).put("refresh",secret).put("nonce",nonce).put("proof",proof))
            check(next.getString("serverId")==row.serverId&&next.getLong("userId")==row.userId&&next.getString("sessionId")==row.sessionId){"设备续期身份不匹配"}
            next.put("instance",credential.getJSONObject("instance")).put("origin",row.origin)
            withContext(Dispatchers.IO){store.vault.write(row.credentialRef,next.toString().toByteArray(Charsets.UTF_8))}
            next
        }
    }
}

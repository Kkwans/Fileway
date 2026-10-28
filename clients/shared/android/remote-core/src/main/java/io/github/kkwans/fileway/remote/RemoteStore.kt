package io.github.kkwans.fileway.remote

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.*
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import io.github.kkwans.nasfilebrowser.data.CredentialVault
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Entity(tableName = "paired_tvs")
data class PairedTv(
    @PrimaryKey val id: String, val name: String, val serverName: String, val address: String, val port: Int,
    val fingerprint: String, val trusted: Boolean, val updatedAt: Long,
) {
    fun descriptor() = TvDescriptor(id, name, serverName, address, port, fingerprint)
    companion object { fun from(value: TvDescriptor, trusted: Boolean) = PairedTv(value.id, value.name, value.serverName, value.address, value.port, value.fingerprint, trusted, System.currentTimeMillis()) }
}

@Entity(tableName = "trusted_controllers")
data class TrustedController(@PrimaryKey val id: String, val name: String, val publicKey: String, val pairedAt: Long) {
    fun json() = JSONObject().put("id", id).put("name", name).put("publicKey", publicKey)
    companion object {
        fun from(value: JSONObject) = TrustedController(value.getString("id"), value.getString("name"), value.getString("publicKey"), System.currentTimeMillis())
    }
}

@Entity(tableName = "device_grants", primaryKeys = ["serverId", "userId"])
data class StoredDeviceGrant(
    val serverId: String, val userId: Long, val origin: String, val profileId: String,
    val sessionId: String, val credentialRef: String, val phase: String, val updatedAt: Long,
)

@Dao interface RemoteDao {
    @Query("SELECT * FROM paired_tvs ORDER BY updatedAt DESC") fun devices(): Flow<List<PairedTv>>
    @Query("SELECT * FROM paired_tvs WHERE id=:id") suspend fun device(id: String): PairedTv?
    @Upsert suspend fun saveDevice(value: PairedTv)
    @Query("DELETE FROM paired_tvs WHERE id=:id") suspend fun removeDevice(id: String)
    @Query("SELECT * FROM trusted_controllers ORDER BY pairedAt DESC") fun controllers(): Flow<List<TrustedController>>
    @Query("SELECT * FROM trusted_controllers ORDER BY pairedAt DESC") suspend fun currentControllers(): List<TrustedController>
    @Query("SELECT * FROM trusted_controllers WHERE id=:id") suspend fun controller(id: String): TrustedController?
    @Upsert suspend fun saveController(value: TrustedController)
    @Query("DELETE FROM trusted_controllers WHERE id=:id") suspend fun removeController(id: String)
    @Query("SELECT * FROM device_grants WHERE serverId=:serverId AND userId=:userId") suspend fun grant(serverId: String, userId: Long): StoredDeviceGrant?
    @Query("SELECT * FROM device_grants WHERE profileId=:profileId ORDER BY updatedAt DESC LIMIT 1") suspend fun profileGrant(profileId: String): StoredDeviceGrant?
    @Upsert suspend fun saveGrant(value: StoredDeviceGrant)
}

@Database(entities = [PairedTv::class, TrustedController::class, StoredDeviceGrant::class], version = 1, exportSchema = true)
abstract class RemoteDatabase : RoomDatabase() {
    abstract fun remote(): RemoteDao
    companion object {
        @Volatile private var instance: RemoteDatabase? = null
        fun get(context: Context): RemoteDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, RemoteDatabase::class.java, "fileway-remote.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build().also { instance = it }
        }
    }
}

class RemoteStore(context: Context) {
    val database = RemoteDatabase.get(context)
    val dao = database.remote()
    val vault = CredentialVault(context)
    companion object { private val identityLock=Mutex() }

    suspend fun tvIdentity(): JSONObject = identityLock.withLock {
        val stored = withContext(Dispatchers.IO) { vault.read("tv-control-identity-v1") }
        if (stored != null) return@withLock JSONObject(stored.toString(Charsets.UTF_8))
        val identity = NativeTransport.call(JSONObject().put("op", "remote_identity")) as JSONObject
        withContext(Dispatchers.IO) { vault.write("tv-control-identity-v1", identity.toString().toByteArray(Charsets.UTF_8)) }
        identity
    }
}

/** A phone's installation identity remains independent of its selected NAS user. */
class ControllerIdentity {
    private val alias = "fileway-tv-controller-v1"
    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun ensureKey() = synchronized(lock) {
        if (keyStore().containsAlias(alias)) return@synchronized
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(false).build())
        }.generateKeyPair()
    }
    suspend fun publicController(): JSONObject = withContext(Dispatchers.IO) {
        ensureKey()
        val encoded = keyStore().getCertificate(alias).publicKey.encoded
        val id = MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
        val name = "${Build.MANUFACTURER} ${Build.MODEL}".filterNot { it.isISOControl() }.trim().take(40).ifBlank { "栖卷手机" }
        JSONObject().put("id", id).put("name", name).put("publicKey", Base64.getUrlEncoder().withoutPadding().encodeToString(encoded))
    }
    suspend fun sign(deviceId: String, controllerId: String, nonce: String): String = withContext(Dispatchers.IO) {
        ensureKey()
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyStore().getKey(alias, null) as PrivateKey)
            update(proofMessage("connect", deviceId, controllerId, nonce))
        }.sign()
        Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
    }
    companion object { private val lock = Any() }
}

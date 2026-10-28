package io.github.kkwans.nasfilebrowser.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore encrypts data keys and credentials; private files are excluded from backup. */
class CredentialVault constructor(context: Context, private val atomicFile: (File) -> AtomicFile) {
    constructor(context: Context) : this(context, { AtomicFile(it) })
    private val directory = File(context.noBackupFilesDir, "vault").apply { mkdirs() }
    companion object { private val lock = Any(); private const val alias = "nfb-client-vault-v1" }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    private fun file(id: String): AtomicFile {
        val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return atomicFile(File(directory, "$name.bin"))
    }
    fun read(id: String): ByteArray? = synchronized(lock) {
        val entry = file(id)
        if (!entry.baseFile.exists() && !File(entry.baseFile.path + ".bak").exists()) return@synchronized null
        val data = entry.readFully()
        check(data.size >= 29 && data[0] == 1.toByte()) { "本机凭据无法读取，请重新登录" }
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(1, 13)))
            updateAAD(id.toByteArray(Charsets.UTF_8))
            doFinal(data.copyOfRange(13, data.size))
        }
    }
    fun write(id: String, value: ByteArray) = synchronized(lock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key()); updateAAD(id.toByteArray(Charsets.UTF_8))
        }
        val payload = byteArrayOf(1) + cipher.iv + cipher.doFinal(value)
        val entry = file(id); val output = entry.startWrite()
        try {
            output.write(payload)
            // AtomicFile.finishWrite logs sync/close/rename errors instead of
            // throwing. Explicit sync and verification must precede our ACK.
            output.fd.sync()
            entry.finishWrite(output)
            check(entry.baseFile.isFile && entry.baseFile.length() == payload.size.toLong() &&
                !File(entry.baseFile.path + ".new").exists() && !File(entry.baseFile.path + ".bak").exists() &&
                entry.baseFile.readBytes().contentEquals(payload)) { "本机登录凭据未能保存，请重试" }
        } catch (error: Exception) {
            runCatching { entry.failWrite(output) }.onFailure(error::addSuppressed)
            throw IllegalStateException("本机登录凭据未能保存，请重试", error)
        }
    }
    fun remove(id: String) = synchronized(lock) {
        val entry = file(id)
        entry.delete()
        check(listOf(entry.baseFile, File(entry.baseFile.path + ".bak"), File(entry.baseFile.path + ".new")).none { it.exists() }) { "凭据无法删除，请重试" }
    }
    fun nodeDataKey(): ByteArray = synchronized(lock) {
        read("node-data-key") ?: ByteArray(32).also { SecureRandom().nextBytes(it); write("node-data-key", it) }
    }
}

package io.github.kkwans.nasfilebrowser.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.UUID

/** SQLite holds metadata only. JWTs remain in the Keystore-backed vault. */
class ProfileStore(private val database: ClientAccountDatabase, private val vault: CredentialVault) {
    private val dao = database.profiles()
    val profiles = dao.profiles()

    suspend fun save(input: ServerProfile): ServerProfile {
        require(input.name.isNotBlank()) { "请输入服务器名称" }
        val address = input.address.trim()
        val uri = runCatching { URI(address) }.getOrNull()
        require(uri != null && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
            && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.port in -1..65535) { "请输入包含 http:// 或 https:// 的完整服务器地址" }
        return database.withTransaction {
            val old = dao.profile(input.id)
            val sourceChanged = old != null && (old.address != address || old.backend != input.backend)
            val profile = input.copy(name = input.name.trim(), address = address,
                sourceRevision = (old?.sourceRevision ?: 0) + if (sourceChanged) 1 else 0,
                updatedAt = System.currentTimeMillis())
            dao.saveProfile(profile)
            profile
        }
    }

    suspend fun profile(id: String) = dao.profile(id)
    suspend fun account(key: String) = dao.account(key)
    suspend fun active(): Pair<ServerProfile, AccountRecord>? = database.withTransaction {
        val key = dao.activeSession()?.accountKey ?: return@withTransaction null
        val account = dao.account(key) ?: return@withTransaction null
        val profile = dao.profile(account.profileId) ?: return@withTransaction null
        if (profile.sourceRevision != account.sourceRevision) {
            dao.clearActiveSession(key)
            return@withTransaction null
        }
        profile to account
    }
    suspend fun activate(profile: ServerProfile, account: AccountRecord, owner: String = UUID.randomUUID().toString()) = database.withTransaction {
        check(dao.profile(profile.id) == profile && dao.account(account.key) == account) { "登录来源已变化，请重新连接" }
        dao.saveActiveSession(ActiveSession(accountKey = account.key, owner = owner))
    }
    suspend fun deactivate(account: AccountRecord, owner: String) = dao.releaseActiveSession(account.key, owner)
    suspend fun accounts(profile: ServerProfile) = dao.accounts(profile.id, profile.sourceRevision)
    suspend fun directory(account: AccountRecord) = dao.directory(account.key)
    suspend fun saveDirectory(account: AccountRecord, path: String, wirePath: String) = database.withTransaction {
        val old = dao.directory(account.key)
        dao.saveDirectory(DirectoryState(account.key, path, wirePath, old?.fileLayout ?: FileLayout.COVER))
    }
    suspend fun saveFileLayout(account: AccountRecord, layout: FileLayout) = database.withTransaction {
        val old = dao.directory(account.key) ?: DirectoryState(account.key, "/", "/")
        dao.saveDirectory(old.copy(fileLayout = layout))
    }

    // Call only after the source has authenticated this identity. These fields
    // select local storage; service permissions always remain server-enforced.
    suspend fun saveLogin(profile: ServerProfile, userId: Long, username: String, token: String): AccountRecord = withContext(Dispatchers.IO) {
        require(userId >= 0 && username.isNotBlank() && token.isNotBlank()) { "服务器账号信息无效" }
        var createdRef: String? = null
        try {
            database.withTransaction {
                val current = dao.profile(profile.id)
                check(current != null && current.sourceRevision == profile.sourceRevision && current.address == profile.address
                    && current.backend == profile.backend && current.network == profile.network) { "服务器档案已变化，请重新连接" }
                val key = "${profile.id}/${profile.sourceRevision}/$userId"
                val old = dao.account(key)
                val ref = old?.credentialRef ?: "service-${UUID.randomUUID()}".also { createdRef = it }
                vault.write(ref, token.toByteArray(Charsets.UTF_8))
                val account = AccountRecord(key, profile.id, profile.sourceRevision, userId, username, ref, System.currentTimeMillis())
                dao.saveAccount(account)
                account
            }
        } catch (error: Exception) {
            createdRef?.let { vault.remove(it) }
            throw error
        }
    }

    suspend fun token(profile: ServerProfile, account: AccountRecord): String? = withContext(Dispatchers.IO) {
        val stored = matchingAccount(profile, account) ?: return@withContext null
        vault.read(stored.credentialRef)?.toString(Charsets.UTF_8)
    }

    private suspend fun matchingAccount(profile: ServerProfile, account: AccountRecord): AccountRecord? {
        if (account.profileId != profile.id || account.sourceRevision != profile.sourceRevision) return null
        val current = dao.profile(profile.id) ?: return null
        if (current.sourceRevision != profile.sourceRevision || current.address != profile.address || current.backend != profile.backend
            || current.network != profile.network) return null
        val stored = dao.account(account.key) ?: return null
        if (stored.profileId != profile.id || stored.sourceRevision != profile.sourceRevision || stored.userId != account.userId
            || stored.credentialRef != account.credentialRef) return null
        return stored
    }

    /** Native requests and Range streams can renew independently of the login UI. */
    suspend fun refreshToken(profile: ServerProfile, account: AccountRecord, token: String): Boolean = withContext(Dispatchers.IO) {
        val identity = NasSession.parseIdentity(token)
        check(identity.id == account.userId) { "续期账号不匹配" }
        database.withTransaction {
            val stored = matchingAccount(profile, account) ?: return@withTransaction false
            // Explicit sign-out deletes the credential. A late native callback
            // must never recreate it, even though the account's history remains.
            val previous = vault.read(stored.credentialRef)?.toString(Charsets.UTF_8) ?: return@withTransaction false
            if (NasSession.issuedAt(previous) > NasSession.issuedAt(token)) return@withTransaction false
            if (previous != token) vault.write(stored.credentialRef, token.toByteArray(Charsets.UTF_8))
            if (stored.username != identity.username) dao.saveAccount(stored.copy(username = identity.username, updatedAt = System.currentTimeMillis()))
            true
        }
    }

    suspend fun rememberPassword(profile: ServerProfile, account: AccountRecord, password: String?) = withContext(Dispatchers.IO) {
        require(password == null || password.isNotEmpty())
        database.withTransaction {
            val stored = matchingAccount(profile, account) ?: error("服务器档案已变化，请重新连接")
            check(vault.read(stored.credentialRef) != null) { "登录记录已退出，请重新登录" }
            val ref = stored.credentialRef + ":password"
            if (password == null) vault.remove(ref) else vault.write(ref, password.toByteArray(Charsets.UTF_8))
        }
    }

    suspend fun password(profile: ServerProfile, account: AccountRecord): String? = withContext(Dispatchers.IO) {
        database.withTransaction {
            val stored = matchingAccount(profile, account) ?: return@withTransaction null
            if (vault.read(stored.credentialRef) == null) return@withTransaction null
            vault.read(stored.credentialRef + ":password")?.toString(Charsets.UTF_8)
        }
    }

    suspend fun forgetRejectedPassword(profile: ServerProfile, account: AccountRecord, rejected: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val stored = matchingAccount(profile, account) ?: return@withTransaction
            val ref = stored.credentialRef + ":password"
            // A failed old attempt must not erase a password saved by a newer login.
            if (vault.read(ref)?.toString(Charsets.UTF_8) == rejected) vault.remove(ref)
        }
    }

    suspend fun signOut(account: AccountRecord, owner: String? = null) = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (owner != null && (dao.activeSession()?.owner != owner || dao.account(account.key) != account)) return@withTransaction
            dao.account(account.key)?.let { vault.remove(it.credentialRef); vault.remove(it.credentialRef + ":password") }
            dao.clearActiveSession(account.key)
        }
    }

    suspend fun remove(profile: ServerProfile) = withContext(Dispatchers.IO) {
        database.withTransaction {
            // A failed credential deletion leaves the profile visible for retry.
            dao.credentialRefs(profile.id).forEach { vault.remove(it); vault.remove(it + ":password") }
            dao.deleteProfile(profile.id)
        }
    }
}

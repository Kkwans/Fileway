package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.*

data class SessionContext(val profile: ServerProfile, val account: AccountRecord, val api: NasSession, val generation: Int, val owner: String = java.util.UUID.randomUUID().toString())

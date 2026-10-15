package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

data class ShellState(val scope: String = "", val open: Boolean = false, val directory: DirectoryCrumb = DirectoryCrumb("根目录", "/", "/"),
    val enabled: Boolean = false, val execute: Boolean = false, val commands: List<String> = emptyList(), val input: String = "",
    val loading: Boolean = false, val running: Boolean = false, val output: ShellBuffer = ShellBuffer(), val remoteDropped: Long = 0,
    val message: String? = null, val error: String? = null, val policyKnown: Boolean = false) {
    val configurationMessage: String? get() = if (policyKnown) shellCapabilityMessage(enabled, execute, commands) else null
}

internal fun shellCapabilityMessage(enabled: Boolean, execute: Boolean, commands: List<String>): String? = when {
    !enabled -> "服务器未启用命令功能"
    !execute -> "当前账号没有执行命令权限"
    commands.isEmpty() -> "当前账号没有允许执行的命令，请在服务器管理白名单"
    else -> null
}

class ShellController(private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean) {
    private val mutable = MutableStateFlow(ShellState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var read: Job? = null
    private var stream: Job? = null
    private var revision = 0L
    fun bind(owner: SessionContext?) {
        if (bound === owner) return
        read?.cancel(); stream?.cancel(); revision++; bound = owner
        mutable.value = ShellState(scope = owner?.owner.orEmpty())
    }
    private fun current(owner: SessionContext, epoch: Long) = bound === owner && isCurrent(owner) && revision == epoch
    fun open(directory: DirectoryCrumb, sourceScope: String) {
        val owner = bound ?: return
        if (!isCurrent(owner) || owner.api.id != sourceScope) return
        disconnect()
        mutable.value = ShellState(scope = owner.owner, open = true, directory = directory)
        refresh()
    }
    fun close() { disconnect(); mutable.value = mutable.value.copy(open = false) }
    fun disconnect() {
        read?.cancel(); stream?.cancel(); revision++
        mutable.value = mutable.value.copy(loading = false, running = false,
            message = if (mutable.value.running || mutable.value.loading) "已断开输出；服务端进程可能仍在运行，请先核对再运行其他命令" else mutable.value.message)
    }
    fun input(value: String) {
        if (!mutable.value.running && !mutable.value.loading) mutable.value = mutable.value.copy(input = value, error = null)
    }
    private suspend fun policy(owner: SessionContext, directory: DirectoryCrumb): Triple<Boolean, Boolean, List<String>> {
        check(owner.account.userId == owner.api.identity.id) { "账号来源不一致，请重新连接" }
        val capabilities = owner.api.request("GET", "/api/client-capabilities")
        val enabled = capabilities.get("enableExec") as? Boolean ?: error("服务器命令能力格式无效")
        if (!enabled) return Triple(false, false, emptyList())
        val user = owner.api.request("GET", "/api/users/${owner.account.userId}")
        check(user.getLong("id") == owner.account.userId) { "服务器返回了其他账号资料" }
        val execute = user.getJSONObject("perm").opt("execute") == true && owner.api.permissions().execute
        if (!execute) return Triple(true, false, emptyList())
        val commands = user.optJSONArray("commands")
        val allowed = if (commands == null) emptyList() else (0 until commands.length()).map {
            (commands.get(it) as? String ?: error("服务器命令白名单无效")).also { name -> require(name.isNotBlank()) }
        }.distinct()
        val wire = requireNotNull(directory.wirePath) { "工作目录的原始路径不可用" }
        resourceWireBytes(wire)
        val metadata = owner.api.request("GET", "/api/resources$wire?metadata=1")
        check(metadata.getBoolean("isDir") && resourceWireBytes(metadata.optString("wirePath").ifEmpty { SearchResult.encodePath(metadata.getString("path")) })
            .contentEquals(resourceWireBytes(wire))) { "工作目录已变化，请返回文件页重新打开命令功能" }
        return Triple(true, true, allowed)
    }
    private fun policyError(enabled: Boolean, execute: Boolean, commands: List<String>) = shellCapabilityMessage(enabled, execute, commands)
    fun refresh() {
        val owner = bound ?: return
        val before = mutable.value
        if (!isCurrent(owner) || before.loading || before.running) return
        val epoch = ++revision
        mutable.value = before.copy(loading = true, error = null, policyKnown = false)
        read = scope.launch {
            try {
                val (enabled, execute, commands) = policy(owner, before.directory)
                if (current(owner, epoch)) mutable.value = mutable.value.copy(loading = false, enabled = enabled, execute = execute, commands = commands,
                    error = policyError(enabled, execute, commands), policyKnown = true)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(owner, epoch)) mutable.value = mutable.value.copy(loading = false, enabled = false, execute = false, error = error.message ?: "无法读取命令权限，请重试")
            }
        }
    }
    fun run() {
        val owner = bound ?: return
        val before = mutable.value; val command = before.input.trim()
        if (!isCurrent(owner) || before.loading || before.running) return
        shellCommandError(command)?.let { mutable.value = before.copy(error = it); return }
        val epoch = ++revision
        mutable.value = before.copy(loading = true, error = null, message = null)
        stream = scope.launch {
            try {
                val (enabled, execute, allowed) = policy(owner, before.directory)
                if (current(owner, epoch)) mutable.value = mutable.value.copy(enabled = enabled, execute = execute, commands = allowed, policyKnown = true)
                check(policyError(enabled, execute, allowed) == null) { policyError(enabled, execute, allowed).orEmpty() }
                if (!current(owner, epoch)) return@launch
                mutable.value = mutable.value.copy(enabled = enabled, execute = execute, commands = allowed, loading = false, running = true,
                    output = ShellBuffer(), remoteDropped = 0, message = "正在连接并接收命令输出…")
                // The server owns OS-specific parsing and its exact allowlist check.
                owner.api.command(before.directory.path, before.directory.wirePath!!, command).collect { update ->
                    if (current(owner, epoch)) mutable.value = mutable.value.copy(output = appendShellOutput(mutable.value.output, update.lines),
                        remoteDropped = update.dropped, running = !update.done,
                        message = update.message ?: "正在接收输出…", error = null)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(owner, epoch)) mutable.value = mutable.value.copy(loading = false, running = false,
                    error = error.message ?: "输出连接中断，无法判断命令是否仍运行，请先核对")
            }
        }
    }
    fun clearOutput() {
        if (!mutable.value.running) mutable.value = mutable.value.copy(output = ShellBuffer(), remoteDropped = 0, message = null)
    }
}

package io.github.kkwans.nasfilebrowser.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

const val SHELL_OUTPUT_CHARACTERS = 131_072
data class ShellOutput(val lines: List<String>, val dropped: Long, val done: Boolean, val state: String, val message: String?)
data class ShellBuffer(val lines: List<String> = emptyList(), val dropped: Long = 0)

fun shellCommandError(command: String): String? = when {
    command.isBlank() -> "请输入要运行的命令"
    command.toByteArray().size > 8192 -> "命令超过 8192 字节，请缩短输入"
    command.toByteArray().toString(Charsets.UTF_8) != command -> "命令包含无法发送的文字，请重新输入"
    command.any { it == '\u0000' || it == '\n' || it == '\r' } -> "每次只能发送一行命令"
    else -> null
}

fun appendShellOutput(before: ShellBuffer, added: List<String>): ShellBuffer {
    val lines = before.lines.toMutableList()
    val ansi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    added.forEach { lines.add(ansi.replace(it, "")) }
    var size = lines.sumOf { it.length + 1 }; var dropped = before.dropped
    while (lines.isNotEmpty() && (lines.size > 2000 || size > SHELL_OUTPUT_CHARACTERS)) { size -= lines.removeAt(0).length + 1; dropped++ }
    return ShellBuffer(lines, dropped)
}

fun shellCommandFlow(path: String, wirePath: String, command: String, identity: suspend () -> Unit,
    native: suspend (JSONObject) -> Any?): Flow<ShellOutput> = flow {
    require(shellCommandError(command) == null) { shellCommandError(command).orEmpty() }
    var handle: String? = null
    try {
        identity()
        handle = native(JSONObject().put("op", "command_start").put("path", path).put("wirePath", wirePath).put("command", command)) as? String
            ?: error("无法创建命令输出连接")
        require(handle.isNotEmpty()) { "命令输出连接无效" }
        while (true) {
            currentCoroutineContext().ensureActive(); identity()
            val batch = native(JSONObject().put("op", "command_poll").put("commandHandle", handle)) as? JSONObject ?: error("无法读取命令输出")
            identity()
            val values = batch.getJSONArray("lines")
            require(values.length() <= 32) { "命令输出批次无效" }
            val lines = (0 until values.length()).map { index ->
                val line = values.get(index) as? String ?: error("命令输出格式无效")
                require(line.toByteArray().size <= 65536) { "单行输出超过查看限制" }; line
            }
            val done = batch.get("done") as? Boolean ?: error("命令输出状态无效")
            val state = batch.getString("state")
            require(state in setOf("running", "closed", "failed") && (!done || state != "running")) { "命令输出状态无效" }
            val dropped = batch.getLong("dropped").also { require(it >= 0) }
            val message = if (!done) null else if (state == "closed") "输出连接已关闭；服务器未提供退出码，不能据此判断执行成败"
                else when (batch.optInt("httpStatus")) {
                    401 -> "登录已过期，输出连接未建立"
                    403 -> "没有访问此工作目录或执行命令的权限"
                    404 -> "工作目录或命令服务不存在"
                    else -> "输出连接未能完成，命令可能已提交；请核对后再运行"
                }
            if (lines.isNotEmpty() || done) emit(ShellOutput(lines, dropped, done, state, message))
            if (done) break
            delay(100)
        }
    } finally {
        handle?.let { id -> withContext(NonCancellable) {
            runCatching { withTimeout(2000) { native(JSONObject().put("op", "command_cancel").put("commandHandle", id)) } }
        } }
    }
}

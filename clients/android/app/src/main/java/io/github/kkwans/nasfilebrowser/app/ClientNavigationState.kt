package io.github.kkwans.nasfilebrowser.app

/** Typed views over ClientState's existing route fields; no second current destination. */
enum class ClientMainDestination(val route: String, val label: String) {
    FILES("files", "文件"), RECENT("recent", "最近"), LIBRARY("library", "资料"), TASKS("taskcenter", "任务"), MY("settings", "我的");
}

enum class ClientDestination(val route: String, val main: ClientMainDestination? = null) {
    FILES("files", ClientMainDestination.FILES), RECENT("recent", ClientMainDestination.RECENT),
    LIBRARY("library", ClientMainDestination.LIBRARY), DOWNLOADS("downloads", ClientMainDestination.TASKS),
    UPLOADS("uploads", ClientMainDestination.TASKS), TASKS("tasks", ClientMainDestination.TASKS),
    HISTORY("history", ClientMainDestination.TASKS), CACHE_IMAGES("cache-images"), SETTINGS("settings", ClientMainDestination.MY), ACCOUNT("account"),
    ADMIN_USERS("admin-users"), SERVER_SETTINGS("server-settings"), UPDATES("updates"), ABOUT("about"),
    SHELL("shell"), SEARCH("search", ClientMainDestination.FILES);

    companion object {
        fun forRoute(route: String, taskSection: TaskCenterSection = TaskCenterSection.DOWNLOADS): ClientDestination? =
            entries.firstOrNull { it.route == if (route == "taskcenter") taskSection.route else route }
    }
}

val ClientState.destination: ClientDestination
    get() = if (searchDisplayed) ClientDestination.SEARCH else ClientDestination.forRoute(tab, taskCenterSection) ?: ClientDestination.FILES

internal fun ClientDestination.allowed(connected: Boolean, admin: Boolean, execute: Boolean): Boolean = when (this) {
    ClientDestination.ACCOUNT, ClientDestination.LIBRARY, ClientDestination.SEARCH -> connected
    ClientDestination.ADMIN_USERS, ClientDestination.SERVER_SETTINGS -> connected && admin
    ClientDestination.SHELL -> connected && execute
    else -> true
}

/** Immutable return context contains identities, never credentials or mutable Controller state. */
internal data class ClientNavigationPoint(
    val route: String,
    val library: LibrarySection,
    val recent: RecentSection,
    val task: TaskCenterSection,
    val path: String,
    val wirePath: String,
    val category: FileCategory,
    val order: FileOrder,
    val searchDisplayed: Boolean,
    val categories: Set<FileCategory> = emptySet(),
) {
    companion object {
        fun from(state: ClientState) = ClientNavigationPoint(state.tab, state.librarySection, state.recentSection,
            state.taskCenterSection, state.path, state.wirePath, state.fileCategory, state.fileOrder, state.searchDisplayed, state.selectedFileCategories)
    }
}

internal enum class ClientReturnKind { AUXILIARY, DIRECTORY, TASK_DETAIL }
internal data class ClientNavigationReturn(
    val kind: ClientReturnKind,
    val point: ClientNavigationPoint,
    val directoryHistory: List<Pair<String, String>> = emptyList(),
)

/** A source stack, not a route store. A server/account generation change invalidates all sources. */
internal class ClientNavigationHistory {
    private var owner = ""
    private val entries = ArrayDeque<ClientNavigationReturn>()
    private fun bind(scope: String) { if (owner != scope) { entries.clear(); owner = scope } }
    fun push(scope: String, entry: ClientNavigationReturn) {
        bind(scope)
        if (entries.lastOrNull() != entry) entries.addLast(entry)
        while (entries.size > 32) entries.removeFirst()
    }
    fun peek(scope: String): ClientNavigationReturn? { bind(scope); return entries.lastOrNull() }
    fun pop(scope: String, kind: ClientReturnKind? = null): ClientNavigationReturn? {
        bind(scope)
        if (kind != null && entries.lastOrNull()?.kind != kind) return null
        return if (entries.isEmpty()) null else entries.removeLast()
    }
    fun keepsSearch(scope: String): Boolean { bind(scope); return entries.any { it.point.searchDisplayed } }
    fun clear() { entries.clear(); owner = "" }
}

/** Length prefixes avoid collisions from separators inside opaque wire identities and queries. */
internal fun clientPageStateKey(vararg parts: String): String = parts.joinToString("") { "${it.length}:$it" }

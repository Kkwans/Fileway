package io.github.kkwans.nasfilebrowser.ui.adaptive

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-customizable tablet workspace and layout preferences.
 * Every preference is pure, persisted in SharedPreferences, and broadcast reactively via StateFlow
 * so changes apply immediately across all mounted tablet workspaces without restarting.
 */
enum class TabletSplitMode(val label: String, val summary: String) {
    AUTO("自适应双栏", "横屏大屏自动开启可拖动左右分栏，竖屏自动切单栏"),
    ALWAYS_SINGLE("单栏专注", "文件与资料页保持全宽大网格，点击直接全屏打开"),
}

enum class TabletSplitPreset(val ratio: Float, val label: String, val ratioText: String) {
    COMPACT_LIST(0.32f, "预览优先", "32 : 68"),
    BALANCED(0.38f, "黄金比例", "38 : 62"),
    EQUAL(0.50f, "均分对开", "50 : 50"),
    WIDE_LIST(0.58f, "列表优先", "58 : 42"),
}

enum class TabletSidebarStyle(val label: String, val summary: String) {
    AUTO("智能适应", "≥1200dp 展开完整侧边栏，中等大屏使用紧凑图标栏"),
    EXPANDED("优先展开", "横屏大屏默认展开带文字标签的常驻侧边栏"),
    RAIL("始终紧凑", "始终保持 80dp 紧凑图标导航栏，释放最大内容画幅"),
}

enum class TabletContentDensity(val label: String, val summary: String) {
    COMFORTABLE("舒适宽松", "更大卡片与触控留白，适合手持浏览"),
    STANDARD("标准均衡", "默认推荐的网格列数与间距"),
    COMPACT("高密紧凑", "同屏展示更多列数与条目，适合键鼠高效管理"),
}

enum class TabletVideoTapBehavior(val label: String, val summary: String) {
    STAGE_FIRST("右侧海报舞台", "单击视频先在右侧展示封面、续播与信息，避免误触出声"),
    PLAY_EMBEDDED("直接内嵌播放", "单击视频直接在右侧内嵌播放器开始播放"),
    PLAY_FULLSCREEN("直接全屏播放", "单击视频一步直达全屏沉浸播放"),
}

@Immutable
data class TabletLayoutConfig(
    val splitMode: TabletSplitMode = TabletSplitMode.AUTO,
    val splitPreset: TabletSplitPreset = TabletSplitPreset.BALANCED,
    val sidebarStyle: TabletSidebarStyle = TabletSidebarStyle.AUTO,
    val density: TabletContentDensity = TabletContentDensity.STANDARD,
    val videoTapBehavior: TabletVideoTapBehavior = TabletVideoTapBehavior.STAGE_FIRST,
    val showDragRatioHud: Boolean = true,
    val magneticSnapEnabled: Boolean = true,
    val allowEdgeCollapse: Boolean = true,
    val showWorkspaceHints: Boolean = true,
    val resetRevision: Int = 0,
) {
    /** Whether content workspaces (Files, Search, Recent, Library, Tasks) should use dual-pane split. */
    fun useWorkspaceDualPane(window: FilewayWindowLayout): Boolean =
        window.useDualPane && splitMode == TabletSplitMode.AUTO

    /** Whether the sidebar toggle can expand into the 232dp text rail on this window size. */
    fun canToggleExpandedSidebar(window: FilewayWindowLayout): Boolean =
        when (sidebarStyle) {
            TabletSidebarStyle.RAIL -> false
            TabletSidebarStyle.EXPANDED -> window.isExpanded || window.isLarge
            TabletSidebarStyle.AUTO -> window.isLarge
        }

    /** Default expanded state when entering a window tier that supports the expanded sidebar. */
    fun defaultSidebarExpanded(window: FilewayWindowLayout): Boolean =
        when (sidebarStyle) {
            TabletSidebarStyle.RAIL -> false
            TabletSidebarStyle.EXPANDED -> window.isExpanded || window.isLarge
            TabletSidebarStyle.AUTO -> window.isLarge
        }

    /** Resolves a default split ratio for primary file workspaces, honoring user's chosen preset. */
    fun primaryWorkspaceDefaultRatio(): Float = splitPreset.ratio

    /** Resolves effective click action for a file tap based on user's video tap preference. */
    fun resolveClickAction(baseAction: TabletClickAction): TabletClickAction =
        if (baseAction != TabletClickAction.STAGE_VIDEO) {
            baseAction
        } else {
            when (videoTapBehavior) {
                TabletVideoTapBehavior.STAGE_FIRST -> TabletClickAction.STAGE_VIDEO
                TabletVideoTapBehavior.PLAY_EMBEDDED -> TabletClickAction.OPEN_EMBEDDED
                TabletVideoTapBehavior.PLAY_FULLSCREEN -> TabletClickAction.OPEN_FULLSCREEN
            }
        }
}

val LocalTabletLayoutConfig = staticCompositionLocalOf { TabletLayoutConfig() }

internal object TabletLayoutPreferences {
    private const val PREFS_NAME = "fileway-tablet-layout"
    private const val KEY_SPLIT_MODE = "pref_split_mode"
    private const val KEY_SPLIT_PRESET = "pref_split_preset"
    private const val KEY_SIDEBAR_STYLE = "pref_sidebar_style"
    private const val KEY_DENSITY = "pref_density"
    private const val KEY_VIDEO_TAP = "pref_video_tap"
    private const val KEY_DRAG_HUD = "pref_drag_hud"
    private const val KEY_MAGNETIC_SNAP = "pref_magnetic_snap"
    private const val KEY_EDGE_COLLAPSE = "pref_edge_collapse"
    private const val KEY_WORKSPACE_HINTS = "pref_workspace_hints"

    @Volatile
    private var loaded = false
    private val _config = MutableStateFlow(TabletLayoutConfig())

    fun observe(context: Context): StateFlow<TabletLayoutConfig> {
        ensureLoaded(context)
        return _config.asStateFlow()
    }

    fun current(context: Context): TabletLayoutConfig {
        ensureLoaded(context)
        return _config.value
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _config.value = TabletLayoutConfig(
                splitMode = enumOrDefault(prefs.getString(KEY_SPLIT_MODE, null), TabletSplitMode.AUTO),
                splitPreset = enumOrDefault(prefs.getString(KEY_SPLIT_PRESET, null), TabletSplitPreset.BALANCED),
                sidebarStyle = enumOrDefault(prefs.getString(KEY_SIDEBAR_STYLE, null), TabletSidebarStyle.AUTO),
                density = enumOrDefault(prefs.getString(KEY_DENSITY, null), TabletContentDensity.STANDARD),
                videoTapBehavior = enumOrDefault(prefs.getString(KEY_VIDEO_TAP, null), TabletVideoTapBehavior.STAGE_FIRST),
                showDragRatioHud = prefs.getBoolean(KEY_DRAG_HUD, true),
                magneticSnapEnabled = prefs.getBoolean(KEY_MAGNETIC_SNAP, true),
                allowEdgeCollapse = prefs.getBoolean(KEY_EDGE_COLLAPSE, true),
                showWorkspaceHints = prefs.getBoolean(KEY_WORKSPACE_HINTS, true),
            )
            loaded = true
        }
    }

    fun update(
        context: Context,
        bumpResetRevision: Boolean = false,
        transform: (TabletLayoutConfig) -> TabletLayoutConfig,
    ) {
        ensureLoaded(context)
        val nextBase = transform(_config.value)
        val next = if (bumpResetRevision) nextBase.copy(resetRevision = nextBase.resetRevision + 1) else nextBase
        _config.value = next
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SPLIT_MODE, next.splitMode.name)
            .putString(KEY_SPLIT_PRESET, next.splitPreset.name)
            .putString(KEY_SIDEBAR_STYLE, next.sidebarStyle.name)
            .putString(KEY_DENSITY, next.density.name)
            .putString(KEY_VIDEO_TAP, next.videoTapBehavior.name)
            .putBoolean(KEY_DRAG_HUD, next.showDragRatioHud)
            .putBoolean(KEY_MAGNETIC_SNAP, next.magneticSnapEnabled)
            .putBoolean(KEY_EDGE_COLLAPSE, next.allowEdgeCollapse)
            .putBoolean(KEY_WORKSPACE_HINTS, next.showWorkspaceHints)
            .apply()
    }

    fun applySplitPreset(context: Context, preset: TabletSplitPreset) {
        TabletLayoutStore.clearSavedRatios(context)
        update(context, bumpResetRevision = true) { it.copy(splitPreset = preset) }
    }

    fun resetDefaults(context: Context) {
        ensureLoaded(context)
        val nextRev = _config.value.resetRevision + 1
        _config.value = TabletLayoutConfig(resetRevision = nextRev)
        loaded = true
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, fallback: T): T =
        raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
}

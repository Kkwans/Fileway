using System.Globalization;

namespace Fileway.App;

internal enum AppLanguage { FollowSystem = 0, Chinese = 1, English = 2 }

/// <summary>
/// Application language preference and string lookup. The zh-CN dictionary is the source of truth
/// (the product's primary language); English translations cover migrated keys and fall back to
/// Chinese for keys not yet translated — an honest "partially localized" state rather than a
/// fabricated one. New surfaces register their keys here as they migrate.
/// </summary>
internal static class Localization
{
    internal static event Action? CultureChanged;

    internal static AppLanguage Language
    {
        get => (AppLanguage)Math.Clamp(ReadLanguage(), 0, 2);
        set
        {
            if (ReadLanguage() == (int)value) return;
            Microsoft.Win32.Registry.SetValue(Key, "Language", (int)value);
            ApplyCulture();
            CultureChanged?.Invoke();
        }
    }

    private const string Key = @"HKEY_CURRENT_USER\Software\Fileway\Preview\Presentation";

    private static int ReadLanguage() => Microsoft.Win32.Registry.GetValue(Key, "Language", 0) is int value ? Math.Clamp(value, 0, 2) : 0;

    internal static bool IsEnglish => Language switch
    {
        AppLanguage.English => true,
        AppLanguage.Chinese => false,
        _ => !CultureInfo.CurrentUICulture.TwoLetterISOLanguageName.Equals("zh", StringComparison.OrdinalIgnoreCase),
    };

    /// <summary>Applies the chosen UI culture at startup; re-run after a language change.</summary>
    internal static void ApplyCulture()
    {
        var culture = Language switch
        {
            AppLanguage.Chinese => new CultureInfo("zh-CN"),
            AppLanguage.English => new CultureInfo("en-US"),
            _ => CultureInfo.CurrentUICulture,
        };
        CultureInfo.CurrentUICulture = culture;
    }

    /// <summary>Resolves a UI string in the active language; English falls back to the Chinese source when untranslated.</summary>
    internal static string T(string key) => !IsEnglish ? key
        : English.TryGetValue(key, out var value) ? value : key;

    // English dictionary: keys are the Chinese source strings. Keys not present here remain
    // Chinese in the English UI until migrated.
    internal static readonly IReadOnlyDictionary<string, string> English = new Dictionary<string, string>
    {
        // Menu bar — 文件
        ["新建"] = "New",
        ["文件夹"] = "Folder",
        ["空文件"] = "Empty file",
        ["上传文件"] = "Upload",
        ["选择文件…"] = "Choose file…",
        ["选择文件夹…"] = "Choose folder…",
        ["下载"] = "Download",
        ["下载记录"] = "Download history",
        ["上传记录"] = "Upload history",
        ["详细信息"] = "Details",
        ["收藏"] = "Favorite",
        ["标记"] = "Tag",
        ["上次操作的完整结果"] = "Last operation result",
        // 侧栏
        ["文件任务"] = "File tasks",
        ["存储工具"] = "Storage tools",
        // 应用菜单
        ["回收站"] = "Trash",
        ["管理收藏与标签…"] = "Manage favorites & tags…",
        ["修改密码…"] = "Change password…",
        ["退出登录"] = "Sign out",
        ["缓存与存储…"] = "Cache & storage…",
        ["帮助"] = "Help",
        ["键盘快捷键"] = "Keyboard shortcuts",
        ["关于"] = "About",
        // 视图
        ["视图"] = "View",
        ["详细网格"] = "Detailed grid",
        ["紧凑网格"] = "Compact grid",
        ["无界网格"] = "Windows icons",
        ["详细列表"] = "Details list",
        ["紧凑列表"] = "Compact list",
        ["粘贴"] = "Paste",
        ["刷新"] = "Refresh",
        ["全选"] = "Select all",
        // 批量/详情
        ["打开"] = "Open",
        ["下载到默认目录…"] = "Download to default folder…",
        ["下载另存为…"] = "Download as…",
        ["下载为 ZIP…"] = "Download as ZIP…",
        ["复制"] = "Copy",
        ["剪切"] = "Cut",
        ["移动到…"] = "Move to…",
        ["复制到…"] = "Copy to…",
        ["重命名"] = "Rename",
        ["批量重命名…"] = "Batch rename…",
        ["移入回收站…"] = "Move to trash…",
        // 语言选择器
        ["语言"] = "Language",
        ["跟随系统"] = "Follow system",
        ["简体中文"] = "简体中文",
        ["English"] = "English",
        // 存储/传输常见
        ["暂停"] = "Pause",
        ["继续"] = "Resume",
        ["取消"] = "Cancel",
        ["删除"] = "Delete",
        ["确定"] = "OK",
        ["保存"] = "Save",
        ["关闭"] = "Close",
        ["正在加载…"] = "Loading…",
    };
}

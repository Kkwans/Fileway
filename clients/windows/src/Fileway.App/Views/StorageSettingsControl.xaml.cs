using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.Windows.Storage.Pickers;

namespace Fileway.App.Views;

public sealed partial class StorageSettingsControl : UserControl, IDisposable
{
    private const double BytesPerGb = 1_000_000_000;
    private readonly ILocalStorageSettingsService _settings;
    private readonly nint _ownerHwnd;
    private readonly CancellationTokenSource _lifetime;
    private LocalCacheUsage? _lastCacheUsage;
    private bool _ready;
    private bool _busy;
    private bool _disposed;

    public StorageSettingsControl(ILocalStorageSettingsService settings, nint ownerHwnd, CancellationToken cancellationToken)
    {
        _settings = settings; _ownerHwnd = ownerHwnd;
        _lifetime = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        InitializeComponent();
        MaximumCacheGbBox.Maximum = ClientStorageSettings.MaximumSupportedCacheBytes / BytesPerGb;
    }

    public event EventHandler? StateChanged;
    public bool CanSave => _ready && !_busy && !_disposed && !_lifetime.IsCancellationRequested;
    private bool CanUpdate => !_disposed && !_lifetime.IsCancellationRequested;

    public async Task LoadAsync()
    {
        if (!CanUpdate || _busy) return;
        SetBusy(true);
        try
        {
            var state = await _settings.ReadAsync(_lifetime.Token);
            if (!CanUpdate) return;
            ApplyState(state); _ready = true;
            if (!state.RestartRequired) StatusNotice.IsOpen = false;
            await RefreshCacheUsageAsync();
        }
        catch (OperationCanceledException) { }
        catch (Exception)
        {
            if (CanUpdate)
            {
                RenderCacheUsage(stale: true);
                ShowStatus("无法读取存储设置。请关闭后重试。", InfoBarSeverity.Error);
            }
        }
        finally { if (CanUpdate) SetBusy(false); }
    }

    public async Task SaveAsync()
    {
        if (!CanSave) return;
        var bytes = Math.Round(MaximumCacheGbBox.Value * BytesPerGb);
        if (!double.IsFinite(bytes) || bytes < 0 || bytes > ClientStorageSettings.MaximumSupportedCacheBytes)
        {
            ShowStatus("请输入有效的缓存容量，范围为 0 至 1099.51 GB。", InfoBarSeverity.Error);
            return;
        }
        if (string.IsNullOrWhiteSpace(CacheDirectoryBox.Text) || string.IsNullOrWhiteSpace(DownloadDirectoryBox.Text))
        {
            ShowStatus("请选择缓存目录和默认下载目录。", InfoBarSeverity.Error);
            return;
        }
        var requested = new ClientStorageSettings(CacheDirectoryBox.Text, DownloadDirectoryBox.Text, checked((long)bytes));
        SetBusy(true);
        try
        {
            var state = await _settings.SaveAsync(requested, _lifetime.Token);
            if (!CanUpdate) return;
            ApplyState(state);
            if (_lastCacheUsage is { } usage)
                _lastCacheUsage = usage with { MaximumCacheBytes = state.Active.MaximumCacheBytes };
            RenderCacheUsage(stale: true);
            ShowStatus(state.RestartRequired ? "设置已保存。待生效配置将在下次启动后使用；当前生效配置保持如下。" : "存储设置已保存。", InfoBarSeverity.Success);
        }
        catch (OperationCanceledException) { }
        catch (Exception) { if (CanUpdate) ShowStatus("存储设置未能保存。请检查目录可用性和写入权限后重试。", InfoBarSeverity.Error); }
        finally { if (CanUpdate) SetBusy(false); }
    }

    private void ApplyState(StorageSettingsState state)
    {
        CacheDirectoryBox.Text = state.Pending.CacheDirectory;
        DownloadDirectoryBox.Text = state.Pending.DownloadDirectory;
        MaximumCacheGbBox.Value = state.Pending.MaximumCacheBytes / BytesPerGb;
        ActiveSettingsText.Text = $"缓存：{state.Active.CacheDirectory}\n容量：{state.Active.MaximumCacheBytes / BytesPerGb:0.##} GB\n下载：{state.Active.DownloadDirectory}";
        PendingSettingsText.Text = state.RestartRequired
            ? $"已保存待生效\n缓存：{state.Pending.CacheDirectory}\n容量：{state.Pending.MaximumCacheBytes / BytesPerGb:0.##} GB\n下载：{state.Pending.DownloadDirectory}\n本次编辑请点击保存。"
            : "当前无待生效配置。本次编辑请点击保存。";
        if (state.RestartRequired) ShowStatus("存在待生效配置，将在下次启动后使用。", InfoBarSeverity.Informational);
    }

    private async void RefreshCache_Click(object sender, RoutedEventArgs args)
    {
        if (!CanSave) return;
        SetBusy(true);
        try
        {
            ShowCacheStatus("正在读取缓存占用…", InfoBarSeverity.Informational);
            if (await RefreshCacheUsageAsync() && CanUpdate) CacheNotice.IsOpen = false;
        }
        finally { if (CanUpdate) SetBusy(false); }
    }

    private async void ClearCache_Click(object sender, RoutedEventArgs args)
    {
        if (!CanSave || _settings is not ILocalCacheManagementService management) return;
        SetBusy(true);
        bool cleared = false;
        try
        {
            ShowCacheStatus("正在清理未使用缓存，活跃播放缓存、下载文件和未知文件将保留。", InfoBarSeverity.Informational);
            RenderCacheUsage(stale: true);
            try
            {
                var usage = await management.ClearInactiveCacheAsync(_lifetime.Token);
                if (!CanUpdate) return;
                _lastCacheUsage = usage;
                RenderCacheUsage(stale: false);
                cleared = true;
            }
            catch (Exception)
            {
                if (!CanUpdate) return;
                ShowCacheStatus("清理未能全部完成，部分缓存可能已移除。活跃播放缓存、下载文件和未知文件会保留；正在重新读取占用。", InfoBarSeverity.Warning);
            }
            if (!CanUpdate) return;
            bool refreshed = await RefreshCacheUsageAsync(showError: false);
            if (!CanUpdate) return;
            string unavailable = _lastCacheUsage is null
                ? "缓存占用暂不可用，请刷新后确认。"
                : "下方仅显示上次读取结果，请刷新后确认。";
            ShowCacheStatus(cleared
                ? refreshed
                    ? "未使用缓存清理已完成，当前占用已刷新。活跃播放缓存、下载文件和未知文件已保留。"
                    : $"清理已完成，但当前占用未能刷新。{unavailable}"
                : refreshed
                    ? "清理未能全部完成，部分缓存可能已移除。下方已更新为当前占用；活跃播放缓存、下载文件和未知文件会保留。"
                    : $"清理未能全部完成，部分缓存可能已移除，当前占用也未能刷新。{unavailable}",
                cleared && refreshed ? InfoBarSeverity.Success : InfoBarSeverity.Warning);
        }
        finally { if (CanUpdate) SetBusy(false); }
    }

    private async Task<bool> RefreshCacheUsageAsync(bool showError = true)
    {
        if (!CanUpdate) return false;
        if (_settings is not ILocalCacheManagementService management)
        {
            RenderCacheUsage(stale: true);
            if (showError) ShowCacheStatus("当前服务不支持缓存管理。", InfoBarSeverity.Warning);
            return false;
        }
        try
        {
            var usage = await management.ReadCacheUsageAsync(_lifetime.Token);
            if (!CanUpdate) return false;
            _lastCacheUsage = usage;
            RenderCacheUsage(stale: false);
            if (showError) CacheNotice.IsOpen = false;
            return true;
        }
        catch (Exception)
        {
            if (!CanUpdate) return false;
            RenderCacheUsage(stale: true);
            if (showError) ShowCacheStatus(_lastCacheUsage is null
                ? "无法读取当前缓存占用，请稍后刷新重试。"
                : "无法读取当前缓存占用。已有数值仅为上次读取结果，请刷新后确认。", InfoBarSeverity.Warning);
            return false;
        }
    }

    private void RenderCacheUsage(bool stale)
    {
        if (_lastCacheUsage is not { } usage)
        {
            CacheUsageText.Text = "当前缓存池占用暂不可用。";
            CacheBlocksText.Text = "缓存块与受保护状态暂不可用。";
            PreviousRootsText.Text = "旧缓存根目录额外占用与不可用数量暂不可用。";
            return;
        }
        string prefix = stale ? "上次读取 · " : string.Empty;
        CacheUsageText.Text = $"{prefix}当前缓存池：{FormatBytes(usage.ActiveDataBytes)} / {FormatBytes(usage.MaximumCacheBytes)} 上限";
        CacheBlocksText.Text = $"{prefix}缓存块：{usage.BlockCount:N0}；受保护块：{usage.ProtectedBlockCount:N0}";
        PreviousRootsText.Text = $"{prefix}旧缓存根目录额外占用：{FormatBytes(usage.PreviousRootDataBytes)}；不可用目录：{usage.UnavailablePreviousRoots:N0}";
    }

    private static string FormatBytes(long bytes) => bytes switch
    {
        < 1_000 => $"{bytes:N0} B",
        < 1_000_000 => $"{bytes / 1_000d:0.##} KB",
        < 1_000_000_000 => $"{bytes / 1_000_000d:0.##} MB",
        _ => $"{bytes / BytesPerGb:0.##} GB",
    };

    private void ShowCacheStatus(string message, InfoBarSeverity severity)
    {
        CacheNotice.Message = message; CacheNotice.Severity = severity; CacheNotice.IsOpen = true;
    }

    private async void ChooseCache_Click(object sender, RoutedEventArgs args) => await ChooseFolderAsync(CacheDirectoryBox, "选择缓存根目录");
    private async void ChooseDownload_Click(object sender, RoutedEventArgs args) => await ChooseFolderAsync(DownloadDirectoryBox, "选择默认下载目录");
    private async Task ChooseFolderAsync(TextBox target, string title)
    {
        if (!CanSave) return;
        SetBusy(true);
        try
        {
            var picker = new FolderPicker(Microsoft.UI.Win32Interop.GetWindowIdFromWindow(_ownerHwnd))
            {
                SuggestedStartLocation = PickerLocationId.ComputerFolder,
                Title = title,
                CommitButtonText = "选择此文件夹",
            };
            var folder = await picker.PickSingleFolderAsync().AsTask(_lifetime.Token);
            if (CanUpdate && folder is not null) target.Text = folder.Path;
        }
        catch (OperationCanceledException) { }
        catch (Exception) { if (CanUpdate) ShowStatus("文件夹选择器未能打开。请关闭设置后重试。", InfoBarSeverity.Error); }
        finally { if (CanUpdate) SetBusy(false); }
    }

    private void SetBusy(bool busy)
    {
        _busy = busy;
        SettingsFields.IsEnabled = _ready && !busy;
        CacheActions.IsEnabled = _ready && !busy && _settings is ILocalCacheManagementService;
        StateChanged?.Invoke(this, EventArgs.Empty);
    }

    private void ShowStatus(string message, InfoBarSeverity severity)
    {
        StatusNotice.Message = message; StatusNotice.Severity = severity; StatusNotice.IsOpen = true;
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true; _lifetime.Cancel(); _lifetime.Dispose();
    }
}

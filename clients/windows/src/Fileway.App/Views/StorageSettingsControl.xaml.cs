using System.Globalization;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.Windows.Storage.Pickers;

namespace Fileway.App.Views;

public sealed partial class StorageSettingsControl : UserControl, IDisposable
{
    private const decimal BytesPerGb = 1_000_000_000m;
    private readonly ILocalStorageSettingsService _settings;
    private readonly nint _ownerHwnd;
    private readonly CancellationTokenSource _lifetime;
    private StorageSettingsState? _state;
    private LocalCacheUsage? _lastCacheUsage;
    private XamlRoot? _hostRoot;
    private string _saveFeedback = "";
    private bool _applyingState;
    private bool _hasDraft;
    private bool _capacityInvalid;
    private bool _busy;
    private bool _disposed;

    public StorageSettingsControl(ILocalStorageSettingsService settings, nint ownerHwnd, CancellationToken cancellationToken)
    {
        _settings = settings;
        _ownerHwnd = ownerHwnd;
        _lifetime = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        InitializeComponent();
        CacheDirectoryBox.TextChanged += (_, _) => DraftChanged();
        DownloadDirectoryBox.TextChanged += (_, _) => DraftChanged();
        MaximumCacheGbBox.TextChanged += (_, _) => DraftChanged();
        AutoResumeBox.Toggled += (_, _) => DraftChanged();
        foreach (var bar in new[] { StatusNotice, CacheNotice })
            bar.RegisterPropertyChangedCallback(InfoBar.IsOpenProperty, static (sender, _) =>
            {
                var notice = (InfoBar)sender;
                notice.Visibility = notice.IsOpen ? Visibility.Visible : Visibility.Collapsed;
            });
        Loaded += OnLoaded;
    }

    public event EventHandler? SaveRequested;
    public event EventHandler? CloseRequested;
    public bool CanSave => CanInteract && _hasDraft && !_capacityInvalid;
    private bool CanInteract => _state is not null && !_busy && CanUpdate;
    private bool CanUpdate => !_disposed && !_lifetime.IsCancellationRequested;

    private void OnLoaded(object sender, RoutedEventArgs args)
    {
        Loaded -= OnLoaded;
        _hostRoot = XamlRoot;
        if (_hostRoot is null) return;
        _hostRoot.Changed += HostChanged;
        ResizeToHost(_hostRoot);
    }

    private void HostChanged(XamlRoot sender, XamlRootChangedEventArgs args) => ResizeToHost(sender);

    private void ResizeToHost(XamlRoot root)
    {
        LayoutRoot.Width = Math.Clamp(root.Size.Width - 80, 340, 500);
        SetMaxContentHeight(root.Size.Height - 224);
    }

    public async Task LoadAsync()
    {
        if (!CanUpdate || _busy) return;
        _saveFeedback = "正在读取设置…";
        SetBusy(true);
        try
        {
            var state = await _settings.ReadAsync(_lifetime.Token);
            if (!CanUpdate) return;
            _saveFeedback = "";
            ApplyState(state);
            StatusNotice.IsOpen = false;
            await RefreshCacheUsageAsync();
        }
        catch (OperationCanceledException) { }
        catch (Exception)
        {
            if (!CanUpdate) return;
            _saveFeedback = "";
            RenderCacheUsage(stale: true);
            ShowStatus("无法读取存储设置。请关闭后重试。", InfoBarSeverity.Error);
        }
        finally { if (CanUpdate) SetBusy(false); }
    }

    public async Task SaveAsync()
    {
        if (!CanSave || _state is not { } before || !TryReadCapacity(out long bytes)) return;
        if (string.IsNullOrWhiteSpace(CacheDirectoryBox.Text) || string.IsNullOrWhiteSpace(DownloadDirectoryBox.Text))
        {
            ShowStatus("请选择缓存目录和默认下载目录。", InfoBarSeverity.Error);
            return;
        }
        var requested = new ClientStorageSettings(CacheDirectoryBox.Text, DownloadDirectoryBox.Text, bytes, AutoResumeBox.IsOn);
        _saveFeedback = "正在保存…";
        StatusNotice.IsOpen = false;
        SetBusy(true);
        try
        {
            var state = await _settings.SaveAsync(requested, _lifetime.Token);
            if (!CanUpdate) return;
            _saveFeedback = "存储设置已保存。";
            ApplyState(state);
            // A failed limit change can still have evicted blocks before the old limit was restored.
            bool attemptedLimitChange = SameDirectory(before.Active.CacheDirectory, state.Pending.CacheDirectory) &&
                before.Active.MaximumCacheBytes != state.Pending.MaximumCacheBytes;
            if (attemptedLimitChange || before.Active.MaximumCacheBytes != state.Active.MaximumCacheBytes ||
                !SameDirectory(before.Active.CacheDirectory, state.Active.CacheDirectory))
                await RefreshCacheUsageAsync();
        }
        catch (OperationCanceledException) { }
        catch (FilewayException error) when (error.Error.Code == ErrorCode.InvalidRequest)
        {
            if (CanUpdate)
                ShowStatus("请选择有效的本地目录。下载目录不能位于 Fileway 管理的缓存文件夹内。", InfoBarSeverity.Error);
        }
        catch (Exception)
        {
            if (CanUpdate)
                ShowStatus("存储设置未能保存。请检查目录可用性和写入权限后重试。", InfoBarSeverity.Error);
        }
        finally
        {
            if (CanUpdate)
            {
                if (_saveFeedback == "正在保存…") _saveFeedback = "";
                SetBusy(false);
            }
        }
    }

    private void ApplyState(StorageSettingsState state)
    {
        _state = state;
        _applyingState = true;
        try
        {
            CacheDirectoryBox.Text = state.Pending.CacheDirectory;
            DownloadDirectoryBox.Text = state.Pending.DownloadDirectory;
            MaximumCacheGbBox.Text = FormatGb(state.Pending.MaximumCacheBytes);
            AutoResumeBox.IsOn = state.Pending.AutoResumeDownloads;
        }
        finally { _applyingState = false; }
        RenderDraft();
    }

    private void DraftChanged()
    {
        if (_applyingState || _state is null || !CanUpdate) return;
        _saveFeedback = "";
        StatusNotice.IsOpen = false;
        RenderDraft();
    }

    private void RenderDraft()
    {
        if (_state is not { } state) return;

        bool validCapacity = TryReadCapacity(out long bytes);
        bool cacheEdited = !SameDirectory(CacheDirectoryBox.Text, state.Pending.CacheDirectory);
        bool downloadEdited = !SameDirectory(DownloadDirectoryBox.Text, state.Pending.DownloadDirectory);
        bool capacityEdited = !validCapacity || bytes != state.Pending.MaximumCacheBytes;
        _capacityInvalid = !validCapacity;
        _hasDraft = cacheEdited || downloadEdited || capacityEdited || AutoResumeBox.IsOn != state.Pending.AutoResumeDownloads;
        CapacityError.Text = validCapacity ? "" : $"请输入 0 到 {FormatGb(ClientStorageSettings.MaximumSupportedCacheBytes)} GB 之间的数值。";
        CapacityError.Visibility = validCapacity ? Visibility.Collapsed : Visibility.Visible;
        PendingSettingsText.Text = state.RestartRequired ? "已保存的待生效配置将在下次启动后使用。" : "";
        PendingSettingsText.Visibility = state.RestartRequired ? Visibility.Visible : Visibility.Collapsed;
        PreferencesHint.Visibility = state.RestartRequired ? Visibility.Collapsed : Visibility.Visible;
        SetPendingValue(ActiveCacheDirectory, state.Active.CacheDirectory, state.Pending.CacheDirectory,
            !SameDirectory(state.Active.CacheDirectory, state.Pending.CacheDirectory), cacheEdited);
        SetPendingValue(ActiveDownloadDirectory, state.Active.DownloadDirectory, state.Pending.DownloadDirectory,
            !SameDirectory(state.Active.DownloadDirectory, state.Pending.DownloadDirectory), downloadEdited);
        SetPendingValue(ActiveCapacity, $"{FormatGb(state.Active.MaximumCacheBytes)} GB", $"{FormatGb(state.Pending.MaximumCacheBytes)} GB",
            state.Active.MaximumCacheBytes != state.Pending.MaximumCacheBytes, capacityEdited);
        ToolTipService.SetToolTip(CacheDirectoryBox, CacheDirectoryBox.Text);
        ToolTipService.SetToolTip(DownloadDirectoryBox, DownloadDirectoryBox.Text);
        UpdateCommands();
    }

    private static void SetPendingValue(TextBlock target, string active, string pending, bool changed, bool editing)
    {
        target.Text = changed
            ? editing ? $"当前：{active}\n已保存待生效：{pending}" : $"当前：{active}"
            : "";
        target.Visibility = changed ? Visibility.Visible : Visibility.Collapsed;
    }

    private bool TryReadCapacity(out long bytes)
    {
        bytes = 0;
        if (!decimal.TryParse(MaximumCacheGbBox.Text.Trim(), NumberStyles.AllowDecimalPoint, CultureInfo.CurrentCulture, out decimal gb) ||
            gb > ClientStorageSettings.MaximumSupportedCacheBytes / BytesPerGb)
            return false;
        bytes = decimal.ToInt64(Math.Round(gb * BytesPerGb));
        return true;
    }

    private static string FormatGb(long bytes) =>
        (bytes / BytesPerGb).ToString("0.#########", CultureInfo.CurrentCulture);

    private static bool SameDirectory(string left, string right) => StringComparer.OrdinalIgnoreCase.Equals(left, right);

    private async void RefreshCache_Click(object sender, RoutedEventArgs args)
    {
        if (!CanInteract) return;
        SetBusy(true);
        try
        {
            ShowCacheStatus("正在读取缓存占用…", InfoBarSeverity.Informational);
            await RefreshCacheUsageAsync();
        }
        finally { if (CanUpdate) SetBusy(false); }
    }

    private async void ClearCache_Click(object sender, RoutedEventArgs args)
    {
        if (!CanInteract || _settings is not ILocalCacheManagementService management) return;
        SetBusy(true);
        ShowCacheStatus("正在清理未使用的缓存…", InfoBarSeverity.Informational);
        try
        {
            var usage = await management.ClearInactiveCacheAsync(_lifetime.Token);
            if (!await ApplyCacheUsageAsync(usage)) return;
            ShowCacheStatus("未使用的缓存已清理。", InfoBarSeverity.Success);
        }
        catch (OperationCanceledException) { }
        catch (Exception)
        {
            if (!CanUpdate) return;
            bool refreshed = await RefreshCacheUsageAsync(showError: false);
            if (!CanUpdate) return;
            ShowCacheStatus(refreshed
                ? "清理未能全部完成，部分缓存可能已移除。已重新读取占用。"
                : "清理和刷新占用均未完成。部分缓存可能已移除，请重试读取。", InfoBarSeverity.Warning);
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
            if (!await ApplyCacheUsageAsync(usage)) return false;
            if (showError) CacheNotice.IsOpen = false;
            return true;
        }
        catch (OperationCanceledException) when (!CanUpdate) { return false; }
        catch (Exception)
        {
            if (!CanUpdate) return false;
            RenderCacheUsage(stale: true);
            if (showError)
                ShowCacheStatus(_lastCacheUsage is null ? "无法读取当前缓存占用，请刷新重试。"
                    : "无法读取当前缓存占用，以下为上次读取结果。", InfoBarSeverity.Warning);
            return false;
        }
    }

    private async Task<bool> ApplyCacheUsageAsync(LocalCacheUsage usage)
    {
        if (!CanUpdate) return false;
        if (_state is { } state && state.Active.MaximumCacheBytes != usage.MaximumCacheBytes)
        {
            try
            {
                var current = await _settings.ReadAsync(_lifetime.Token);
                if (!CanUpdate) return false;
                _state = current;
                RenderDraft();
            }
            catch (Exception)
            {
                if (!CanUpdate) return false;
                ShowStatus("缓存占用已更新，暂时无法核对生效配置。", InfoBarSeverity.Warning);
            }
        }
        _lastCacheUsage = usage;
        RenderCacheUsage(stale: false);
        return true;
    }

    private void RenderCacheUsage(bool stale)
    {
        if (_lastCacheUsage is not { } usage)
        {
            CacheUsageText.Text = "当前缓存池占用暂不可用。";
            CacheBlocksText.Visibility = PreviousRootsText.Visibility = Visibility.Collapsed;
            return;
        }
        string prefix = stale ? "上次读取 · " : "";
        CacheUsageText.Text = $"{prefix}当前缓存池：{FormatBytes(usage.ActiveDataBytes)} / {FormatBytes(usage.MaximumCacheBytes)} 上限";
        CacheBlocksText.Text = $"{usage.BlockCount:N0} 个缓存块 · {usage.ProtectedBlockCount:N0} 个受保护";
        CacheBlocksText.Visibility = Visibility.Visible;
        PreviousRootsText.Text = $"旧缓存根目录额外占用：{FormatBytes(usage.PreviousRootDataBytes)} · 不可用目录：{usage.UnavailablePreviousRoots:N0}";
        PreviousRootsText.Visibility = usage.PreviousRootDataBytes > 0 || usage.UnavailablePreviousRoots > 0
            ? Visibility.Visible : Visibility.Collapsed;
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
        CacheNotice.Message = message;
        CacheNotice.Severity = severity;
        CacheNotice.IsOpen = true;
    }

    private async void ChooseCache_Click(object sender, RoutedEventArgs args) => await ChooseFolderAsync(CacheDirectoryBox, "选择缓存根目录");
    private async void ChooseDownload_Click(object sender, RoutedEventArgs args) => await ChooseFolderAsync(DownloadDirectoryBox, "选择默认下载目录");

    private async Task ChooseFolderAsync(TextBox target, string title)
    {
        if (!CanInteract) return;
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
        catch (Exception)
        {
            if (CanUpdate) ShowStatus("文件夹选择器未能打开，请重试。", InfoBarSeverity.Error);
        }
        finally { if (CanUpdate) SetBusy(false); }
    }

    private void SetBusy(bool busy)
    {
        _busy = busy;
        UpdateCommands();
    }

    private void UpdateCommands()
    {
        SettingsFields.IsEnabled = CanInteract;
        CacheActions.IsEnabled = CanInteract && _settings is ILocalCacheManagementService;
        CacheNotice.IsClosable = !_busy;
        SaveButton.IsEnabled = CanSave;
        bool showSave = CachePanel.Visibility != Visibility.Visible || _hasDraft;
        Footer.Visibility = showSave || StatusNotice.IsOpen ? Visibility.Visible : Visibility.Collapsed;
        FooterActions.Visibility = showSave ? Visibility.Visible : Visibility.Collapsed;
        UsageCloseButton.Visibility = showSave ? Visibility.Collapsed : Visibility.Visible;
        SaveStatusText.Text = !showSave ? "" : _saveFeedback.Length > 0 ? _saveFeedback : _hasDraft ? "有未保存的更改" : "";
    }

    private void PreferencesTab_Click(object sender, RoutedEventArgs args) => ShowTab(usage: false);
    private void UsageTab_Click(object sender, RoutedEventArgs args) => ShowTab(usage: true);

    private void ShowTab(bool usage)
    {
        PreferencesTab.IsChecked = !usage;
        UsageTab.IsChecked = usage;
        SettingsFields.Visibility = usage ? Visibility.Collapsed : Visibility.Visible;
        CachePanel.Visibility = usage ? Visibility.Visible : Visibility.Collapsed;
        EffectHints.Visibility = usage ? Visibility.Collapsed : Visibility.Visible;
        BodyScroll.ChangeView(null, 0, null, true);
        UpdateCommands();
    }

    internal void ShowCacheUsage() => ShowTab(usage: true);
    private void Save_Click(object sender, RoutedEventArgs args) => SaveRequested?.Invoke(this, EventArgs.Empty);
    private void Close_Click(object sender, RoutedEventArgs args) => CloseRequested?.Invoke(this, EventArgs.Empty);

    private void ShowStatus(string message, InfoBarSeverity severity)
    {
        StatusNotice.Message = message;
        StatusNotice.Severity = severity;
        StatusNotice.IsOpen = true;
        UpdateCommands();
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        Loaded -= OnLoaded;
        if (_hostRoot is not null) _hostRoot.Changed -= HostChanged;
        _lifetime.Cancel();
        _lifetime.Dispose();
    }

    internal void FocusInitial() =>
        (UsageCloseButton.Visibility == Visibility.Visible ? UsageCloseButton : CloseButton).Focus(FocusState.Programmatic);
    internal void SetMaxContentHeight(double value) => BodyScroll.MaxHeight = Math.Clamp(value, 150, 470);
}

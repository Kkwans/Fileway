using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Storage.Pickers;

namespace Fileway.App.Views;

public sealed partial class StorageSettingsControl : UserControl, IDisposable
{
    private const double BytesPerGb = 1_000_000_000;
    private readonly ILocalStorageSettingsService _settings;
    private readonly nint _ownerHwnd;
    private readonly CancellationTokenSource _lifetime;
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
        if (!CanUpdate) return;
        SetBusy(true);
        try
        {
            var state = await _settings.ReadAsync(_lifetime.Token);
            if (!CanUpdate) return;
            ApplyState(state); _ready = true;
            if (!state.RestartRequired) StatusNotice.IsOpen = false;
        }
        catch (OperationCanceledException) { }
        catch (Exception) { if (CanUpdate) ShowStatus("无法读取存储设置。请关闭后重试。", InfoBarSeverity.Error); }
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

    private async void ChooseCache_Click(object sender, RoutedEventArgs args) => await ChooseFolderAsync(CacheDirectoryBox);
    private async void ChooseDownload_Click(object sender, RoutedEventArgs args) => await ChooseFolderAsync(DownloadDirectoryBox);
    private async Task ChooseFolderAsync(TextBox target)
    {
        if (!CanSave) return;
        SetBusy(true);
        try
        {
            var picker = new FolderPicker { SuggestedStartLocation = PickerLocationId.ComputerFolder };
            picker.FileTypeFilter.Add("*");
            WinRT.Interop.InitializeWithWindow.Initialize(picker, _ownerHwnd);
            var folder = await picker.PickSingleFolderAsync();
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

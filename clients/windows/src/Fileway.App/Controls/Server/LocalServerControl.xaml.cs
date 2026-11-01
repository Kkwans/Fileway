using System.Globalization;
using Fileway.Core.Contracts;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Windows.ApplicationModel.DataTransfer;
using Windows.System;

namespace Fileway.App.Controls.Server;

public sealed record LocalServerConnectRequest(string Endpoint, string Username);

/// <summary>Deployment, lifecycle, boot settings and log inspection for the managed local server.</summary>
public sealed partial class LocalServerControl : UserControl, IDisposable
{
    private const double NarrowDetailsWidth = 900;
    private static readonly string MaskedSecret = new('•', 16);

    private readonly Debounce _queryDebounce = new(TimeSpan.FromMilliseconds(260));
    private readonly DispatcherTimer _followTimer = new() { Interval = TimeSpan.FromSeconds(2) };
    private readonly CancellationTokenSource _lifetime = new();

    private ILocalServerManagementService? _service;
    private nint _ownerHwnd;
    private LocalServerStatus? _status;
    private LocalServerCredential? _credential;
    private string? _revealedSecret;
    private bool _errorsOnly;
    private bool _loading;
    private bool _attached;

    public event EventHandler? CloseRequested;
    public event EventHandler<LocalServerConnectRequest>? ConnectRequested;

    public LocalServerControl()
    {
        InitializeComponent();
        _followTimer.Tick += async (_, _) => await ReloadLogsAsync();
        Unloaded += (_, _) => Dispose();
    }

    public void Dispose()
    {
        _followTimer.Stop();
        _queryDebounce.Cancel();
        SuspendView();
        _lifetime.Cancel();
        _lifetime.Dispose();
    }

    public void SetContext(ILocalServerManagementService service, nint ownerHwnd)
    {
        _service = service;
        _ownerHwnd = ownerHwnd;
        PrefillDeployment(service.GetDefaults());
    }

    /// <summary>Attaches to background status changes and repaints the page.</summary>
    public async Task ShowAsync()
    {
        if (_service is null) return;
        if (!_attached)
        {
            _service.StatusChanged += Status_Changed;
            _attached = true;
        }
        ArrangeDetails(ActualWidth);
        await RefreshAsync();
        if (FollowCheck.IsChecked == true) _followTimer.Start();
    }

    public void SuspendView()
    {
        _followTimer.Stop();
        if (_attached && _service is not null)
        {
            _service.StatusChanged -= Status_Changed;
            _attached = false;
        }
    }

    /// <summary>Where “返回” leads differs before and after a session exists; the label must not lie.</summary>
    public void SetReturnContext(bool fileBrowserAvailable)
    {
        string label = fileBrowserAvailable ? "返回文件" : "返回连接";
        CloseButton.Content = label;
        AutomationProperties.SetName(CloseButton, "离开本地服务端，" + label);
    }

    private void Panel_SizeChanged(object sender, SizeChangedEventArgs e) => ArrangeDetails(e.NewSize.Width);

    /// <summary>The account card drops below the build card instead of squeezing both columns.</summary>
    private void ArrangeDetails(double width)
    {
        if (width <= 0) return;
        bool narrow = width < NarrowDetailsWidth;
        Grid.SetColumn(StartupCard, narrow ? 0 : 1);
        Grid.SetRow(StartupCard, narrow ? 1 : 0);
        Grid.SetColumnSpan(StartupCard, narrow ? 2 : 1);
        Grid.SetColumnSpan(BuildCard, narrow ? 2 : 1);
        DetailSecondColumn.Width = narrow ? new GridLength(0) : new GridLength(1, GridUnitType.Star);
    }

    private void Status_Changed(object? sender, LocalServerStatus status) =>
        DispatcherQueue.TryEnqueue(async () => await RenderAsync(status));

    private void PrefillDeployment(LocalServerDefaults defaults)
    {
        PortBox.Text = defaults.Port.ToString(CultureInfo.InvariantCulture);
        RootBox.Text = defaults.FilesRoot;
        AccountBox.Text = defaults.Username;
        SecretBox.Password = defaults.Password;
    }

    private async Task RefreshAsync()
    {
        if (_service is null) return;
        SetBusy(true);
        try
        {
            await RenderAsync(await _service.ReadStatusAsync(_lifetime.Token));
        }
        catch (Exception error) when (error is FilewayException or OperationCanceledException)
        {
            if (error is FilewayException failure) ShowNotice(failure.Error.Message, InfoBarSeverity.Warning);
        }
        finally { SetBusy(false); }
    }

    private async Task RenderAsync(LocalServerStatus status)
    {
        _status = status;
        bool deployed = status.State != LocalServerState.NotDeployed;
        bool busy = status.State is LocalServerState.Starting or LocalServerState.Stopping || _loading;
        bool running = status.State == LocalServerState.Running;

        StatusCard.Visibility = deployed ? Visibility.Visible : Visibility.Collapsed;
        DetailGrid.Visibility = deployed ? Visibility.Visible : Visibility.Collapsed;
        DeployCard.Visibility = deployed ? Visibility.Collapsed : Visibility.Visible;
        ArrangeDetails(ActualWidth);

        StatusIcon.IconName = status.State switch
        {
            LocalServerState.Running => "activity",
            LocalServerState.Starting or LocalServerState.Stopping => "clock",
            LocalServerState.Failed or LocalServerState.ComponentMissing or LocalServerState.OccupiedByOther => "alert-triangle",
            _ => "server",
        };
        StatusTitle.Text = TitleFor(status.State);
        StatusDetail.Text = DetailFor(status);

        PrimaryButton.IsEnabled = deployed && !busy;
        PrimaryButton.Content = PrimaryLabelFor(status.State);
        AutomationProperties.SetName(PrimaryButton, AutomationFor(status.State));
        StopButton.IsEnabled = running && !busy;
        StopButton.Content = status.State == LocalServerState.Stopping ? "正在停止…" : "停止服务";

        AutoStartCheck.IsChecked = status.AutoStartEnabled;
        AutoRestartCheck.IsChecked = status.AutoRestartEnabled;
        AutoStartCheck.IsEnabled = deployed && !busy;
        AutoRestartCheck.IsEnabled = deployed && !busy;
        AutoRestartNote.Text = status.RestartAttempts > 0
            ? "由运行中的栖卷客户端看护；本轮已自动重启 " + status.RestartAttempts.ToString(CultureInfo.InvariantCulture) + " 次。"
            : "由运行中的栖卷客户端看护，关闭客户端后不再重拉；连续 5 次失败会暂停并在上方提示。";

        ErrorCard.Visibility = status.LastError is null ? Visibility.Collapsed : Visibility.Visible;
        if (status.LastError is not null) ErrorText.Text = status.LastError.Message;
        RenderFacts(status);

        await ReloadLogsAsync();
        await LoadCredentialAsync(status);
    }

    private void RenderFacts(LocalServerStatus status)
    {
        LocalServerDeployment? deployment = status.Deployment;
        AddressValue.Text = status.Endpoint ?? string.Empty;
        FilesValue.Text = deployment?.FilesRoot ?? "—";
        DataValue.Text = deployment?.DataDirectory ?? "—";
        LogsValue.Text = deployment?.LogDirectory ?? "—";
        VersionValue.Text = status.ServerVersion is null ? "回执缺失" : "Fileway-" + status.ServerVersion;
        AccountValue.Text = deployment?.AdminUsername ?? "—";
        SecretValue.Text = _revealedSecret ?? MaskedSecret;
        ToolTipService.SetToolTip(FilesValue, FilesValue.Text);
        ToolTipService.SetToolTip(DataValue, DataValue.Text);
        ToolTipService.SetToolTip(LogsValue, LogsValue.Text);
        ToolTipService.SetToolTip(AddressValue, AddressValue.Text);
    }

    private async Task LoadCredentialAsync(LocalServerStatus status)
    {
        if (_service is null || status.State == LocalServerState.NotDeployed) { _credential = null; return; }
        try
        {
            _credential = await _service.ReadCredentialAsync(_lifetime.Token);
            RenderFacts(status);
        }
        catch (Exception error) when (error is FilewayException or OperationCanceledException)
        {
            _credential = null;
        }
    }

    private static string TitleFor(LocalServerState state) => state switch
    {
        LocalServerState.Running => "运行中",
        LocalServerState.Starting => "正在启动…",
        LocalServerState.Stopping => "正在停止…",
        LocalServerState.Stopped => "已停止",
        LocalServerState.Failed => "异常退出",
        LocalServerState.OccupiedByOther => "端口被其他进程占用",
        LocalServerState.ComponentMissing => "服务端组件缺失",
        _ => "尚未部署",
    };

    private static string PrimaryLabelFor(LocalServerState state) => state switch
    {
        LocalServerState.Running => "连接到本地服务端",
        LocalServerState.Failed => "重试启动",
        LocalServerState.Starting => "正在启动…",
        LocalServerState.Stopping => "正在停止…",
        LocalServerState.ComponentMissing => "组件缺失",
        LocalServerState.OccupiedByOther => "无法接管",
        _ => "启动服务",
    };

    private static string AutomationFor(LocalServerState state) => state switch
    {
        LocalServerState.Running => "用本地服务端地址打开连接页",
        LocalServerState.Failed => "重试启动本地服务端",
        _ => "启动本地服务端",
    };

    private static string DetailFor(LocalServerStatus status)
    {
        if (status.State == LocalServerState.ComponentMissing)
            return "安装包内的 fileway-server.exe 缺失或与回执不一致";
        LocalServerDeployment? deployment = status.Deployment;
        if (deployment is null) return status.Endpoint ?? string.Empty;
        var parts = new List<string> { status.Endpoint ?? string.Empty };
        if (status.ProcessId is int pid) parts.Add("PID " + pid.ToString(CultureInfo.InvariantCulture));
        if (status.State == LocalServerState.Running && status.Uptime is TimeSpan up) parts.Add("已运行 " + FormatUptime(up));
        if (status.LastExitCode is int code and not 0) parts.Add("退出码 " + code.ToString(CultureInfo.InvariantCulture));
        parts.Add("共享 " + new DirectoryInfo(deployment.FilesRoot).Name);
        return string.Join("  ·  ", parts.Where(part => !string.IsNullOrWhiteSpace(part)));
    }

    private static string FormatUptime(TimeSpan value)
    {
        if (value.TotalMinutes < 1) return Math.Max(1, (int)value.TotalSeconds).ToString(CultureInfo.InvariantCulture) + " 秒";
        if (value.TotalHours < 1) return (int)value.TotalMinutes + " 分";
        return (int)value.TotalHours + " 小时 " + value.Minutes + " 分";
    }

    private async Task RunAsync(Func<CancellationToken, Task<LocalServerStatus>> action, string failureFallback)
    {
        if (_service is null || _loading) return;
        _loading = true;
        SetBusy(true);
        LocalServerStatus? result = null;
        string? failure = null;
        try { result = await action(_lifetime.Token); }
        catch (Exception error) when (error is FilewayException or OperationCanceledException or IOException)
        {
            failure = error is FilewayException typed ? typed.Error.Message : failureFallback;
        }
        finally
        {
            // Interactivity is derived from _loading, so it must be cleared before the final render.
            _loading = false;
            SetBusy(false);
        }

        if (result is not null)
        {
            await RenderAsync(result);
            Notice.IsOpen = false;
            return;
        }
        ShowNotice(failure ?? failureFallback, InfoBarSeverity.Warning);
        try { await RenderAsync(await _service.ReadStatusAsync(_lifetime.Token)); }
        catch (Exception inner) when (inner is FilewayException or OperationCanceledException) { _ = inner; }
    }

    private async void Primary_Click(object sender, RoutedEventArgs e)
    {
        if (_service is null || _status is null) return;
        if (_status.State == LocalServerState.Running)
        {
            string? endpoint = await _service.GetConnectableEndpointAsync(_lifetime.Token);
            if (string.IsNullOrEmpty(endpoint))
            {
                ShowNotice("本地服务端当前不可连接，请刷新状态。", InfoBarSeverity.Warning);
                return;
            }
            ConnectRequested?.Invoke(this, new LocalServerConnectRequest(endpoint, _status.Deployment?.AdminUsername ?? string.Empty));
            return;
        }
        ILocalServerManagementService service = _service;
        await RunAsync(_status.State == LocalServerState.Failed
            ? token => service.RetryStartAsync(token)
            : token => service.StartAsync(token), "启动本地服务端失败。");
    }

    private async void Stop_Click(object sender, RoutedEventArgs e)
    {
        if (_service is null) return;
        ILocalServerManagementService service = _service;
        await RunAsync(service.StopAsync, "停止本地服务端失败。");
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e) => await RefreshAsync();

    private void Close_Click(object sender, RoutedEventArgs e) => CloseRequested?.Invoke(this, EventArgs.Empty);

    private async void Deploy_Click(object sender, RoutedEventArgs e)
    {
        if (_service is null) return;
        if (!int.TryParse(PortBox.Text.Trim(), NumberStyles.None, CultureInfo.InvariantCulture, out int port) || port is < 1024 or > 65535)
        {
            ShowNotice("端口需要是 1024-65535 之间的数字。", InfoBarSeverity.Warning);
            _ = PortBox.Focus(FocusState.Programmatic);
            return;
        }
        string files = RootBox.Text.Trim();
        if (files.Length == 0 || !Path.IsPathFullyQualified(files))
        {
            ShowNotice("共享文件夹需要一个完整路径。", InfoBarSeverity.Warning);
            _ = RootBox.Focus(FocusState.Programmatic);
            return;
        }
        var request = new LocalServerDeploymentRequest(port, files, AccountBox.Text.Trim(), SecretBox.Password);
        ILocalServerManagementService service = _service;
        DeployButton.IsEnabled = false;
        try
        {
            await RunAsync(token => service.DeployAndStartAsync(request, token), "部署本地服务端失败。");
        }
        finally { DeployButton.IsEnabled = true; }
        if (_status is not null && _status.State != LocalServerState.NotDeployed)
        {
            // Generated credentials stay on the page only until the deployment exists.
            PortBox.Text = _status.Deployment?.Port.ToString(CultureInfo.InvariantCulture) ?? PortBox.Text;
        }
    }

    private async void BrowseRoot_Click(object sender, RoutedEventArgs e)
    {
        if (_ownerHwnd == 0) return;
        var picker = new Microsoft.Windows.Storage.Pickers.FolderPicker(Microsoft.UI.Win32Interop.GetWindowIdFromWindow(_ownerHwnd))
        {
            SuggestedStartLocation = Microsoft.Windows.Storage.Pickers.PickerLocationId.ComputerFolder,
            Title = "选择共享文件夹",
            CommitButtonText = "使用此文件夹",
        };
        try
        {
            var folder = await picker.PickSingleFolderAsync().AsTask(_lifetime.Token);
            if (folder is not null) RootBox.Text = folder.Path;
        }
        catch (Exception error) when (error is OperationCanceledException or TaskCanceledException)
        {
            // Closing the picker without a choice is a normal outcome.
        }
    }

    private void RevealSecret_Click(object sender, RoutedEventArgs e)
    {
        SecretBox.PasswordRevealMode = SecretBox.PasswordRevealMode == PasswordRevealMode.Hidden
            ? PasswordRevealMode.Peek : PasswordRevealMode.Hidden;
        ToolTipService.SetToolTip(RevealSecretButton, SecretBox.PasswordRevealMode == PasswordRevealMode.Hidden ? "显示密码" : "隐藏密码");
    }

    private void Regenerate_Click(object sender, RoutedEventArgs e)
    {
        if (_service is null) return;
        LocalServerDefaults defaults = _service.GetDefaults();
        SecretBox.Password = defaults.Password;
        AccountBox.Text = defaults.Username;
    }

    private void RevealStored_Click(object sender, RoutedEventArgs e)
    {
        if (_status is null) return;
        _revealedSecret = _revealedSecret is null ? _credential?.Password : null;
        ToolTipService.SetToolTip(RevealStoredButton, _revealedSecret is null ? "显示密码" : "隐藏密码");
        RenderFacts(_status);
    }

    private void CopyAccount_Click(object sender, RoutedEventArgs e) => CopyToClipboard(_status?.Deployment?.AdminUsername);

    private void CopySecret_Click(object sender, RoutedEventArgs e) => CopyToClipboard(_revealedSecret ?? _credential?.Password);

    private void CopyToClipboard(string? text)
    {
        if (string.IsNullOrEmpty(text)) return;
        var package = new DataPackage();
        package.SetText(text);
        Clipboard.SetContent(package);
        ShowNotice("已复制到剪贴板。", InfoBarSeverity.Success);
    }

    private async void OpenLogs_Click(object sender, RoutedEventArgs e)
    {
        string? directory = _status?.Deployment?.LogDirectory;
        if (string.IsNullOrEmpty(directory) || !Directory.Exists(directory)) return;
        try { await Launcher.LaunchFolderPathAsync(directory); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            ShowNotice("无法打开日志文件夹。", InfoBarSeverity.Warning);
        }
    }

    private async void AutoStart_Changed(object sender, RoutedEventArgs e)
    {
        if (_service is null || AutoStartCheck.IsChecked == _status?.AutoStartEnabled) return;
        bool enabled = AutoStartCheck.IsChecked == true;
        ILocalServerManagementService service = _service;
        await RunAsync(token => service.SetAutoStartAsync(enabled, token), "无法更新开机自启设置。");
    }

    private async void AutoRestart_Changed(object sender, RoutedEventArgs e)
    {
        if (_service is null || AutoRestartCheck.IsChecked == _status?.AutoRestartEnabled) return;
        bool enabled = AutoRestartCheck.IsChecked == true;
        ILocalServerManagementService service = _service;
        await RunAsync(token => service.SetAutoRestartAsync(enabled, token), "无法更新自动重启设置。");
    }

    private async void AllLogs_Click(object sender, RoutedEventArgs e)
    {
        if (!_errorsOnly) return;
        _errorsOnly = false;
        ErrorLogsTab.IsChecked = false;
        AllLogsTab.IsChecked = true;
        await ReloadLogsAsync();
    }

    private async void ErrorLogs_Click(object sender, RoutedEventArgs e)
    {
        if (_errorsOnly) return;
        _errorsOnly = true;
        AllLogsTab.IsChecked = false;
        ErrorLogsTab.IsChecked = true;
        await ReloadLogsAsync();
    }

    private void Query_Changed(object sender, TextChangedEventArgs e) => _queryDebounce.Schedule(() => _ = ReloadLogsAsync());

    /// <summary>The error card lands the reader on the failing lines, not merely on the log panel.</summary>
    private async void ErrorLog_Click(object sender, RoutedEventArgs e)
    {
        if (!_errorsOnly)
        {
            _errorsOnly = true;
            AllLogsTab.IsChecked = false;
            ErrorLogsTab.IsChecked = true;
        }
        await ReloadLogsAsync();
        _ = LogList.Focus(FocusState.Keyboard);
    }

    private async void RefreshLogs_Click(object sender, RoutedEventArgs e) => await ReloadLogsAsync();

    private void Follow_Click(object sender, RoutedEventArgs e)
    {
        if (FollowCheck.IsChecked == true) _followTimer.Start();
        else _followTimer.Stop();
    }

    private async Task ReloadLogsAsync()
    {
        if (_service is null) return;
        try
        {
            var filter = new LocalServerFilter(_errorsOnly, LogQueryBox.Text.Trim());
            LocalServerLogPage page = await _service.ReadLogsAsync(filter, 240, _lifetime.Token);
            var rows = page.Lines.Select(LogRow.From).ToList();
            LogList.ItemsSource = rows;
            LogSummary.Text = page.TotalCount == 0
                ? string.Empty
                : page.TotalCount.ToString(CultureInfo.InvariantCulture) + " 行 · 错误 " +
                  page.ErrorCount.ToString(CultureInfo.InvariantCulture) + " 行";
            bool empty = rows.Count == 0;
            LogsEmpty.Visibility = empty ? Visibility.Visible : Visibility.Collapsed;
            LogList.Visibility = empty ? Visibility.Collapsed : Visibility.Visible;
            LogsEmptyTitle.Text = _errorsOnly ? "没有错误日志" : "还没有日志";
            LogsEmptyBody.Text = _errorsOnly
                ? "当前只有常规运行记录；错误、警告与诊断输出会标记在这里。"
                : "启动服务端后，运行日志与诊断输出会出现在这里。";
            if (!empty && FollowCheck.IsChecked == true && LogList.Items.Count > 0)
                LogList.ScrollIntoView(LogList.Items[^1]);
        }
        catch (Exception error) when (error is FilewayException or OperationCanceledException)
        {
            _ = error; // Log reading is best-effort; the status card still reports the lifecycle.
        }
    }

    private void SetBusy(bool busy) => BusyRing.IsActive = busy;

    private void ShowNotice(string message, InfoBarSeverity severity)
    {
        Notice.Message = message;
        Notice.Severity = severity;
        Notice.IsOpen = true;
    }

    /// <summary>One rendered log line; markers come from theme resources, not captured brushes.</summary>
    internal sealed record LogRow(string Time, string Text, string Tooltip, string Channel, Visibility PlainVisibility, Visibility AlertVisibility)
    {
        internal static LogRow From(LocalServerLogLine line)
        {
            bool alert = line.Severity == LocalServerLogSeverity.Error;
            string channel = line.Channel == LocalServerLogChannel.Diagnostic ? "诊断" : "运行";
            string body = line.Text;
            if (line.Timestamp is DateTimeOffset stamp)
            {
                string prefix = stamp.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture);
                if (body.StartsWith(prefix, StringComparison.Ordinal)) body = body[prefix.Length..].TrimStart();
            }
            return new LogRow(
                line.Timestamp?.ToString("MM-dd HH:mm:ss", CultureInfo.InvariantCulture) ?? "—",
                body,
                line.Text,
                channel,
                alert ? Visibility.Collapsed : Visibility.Visible,
                alert ? Visibility.Visible : Visibility.Collapsed);
        }
    }

    /// <summary>Collapses rapid keystrokes into a single log reload.</summary>
    internal sealed class Debounce
    {
        private readonly DispatcherTimer _timer;
        private Action? _action;

        internal Debounce(TimeSpan interval)
        {
            _timer = new DispatcherTimer { Interval = interval };
            _timer.Tick += (_, _) => { _timer.Stop(); _action?.Invoke(); };
        }

        internal void Schedule(Action action)
        {
            _action = action;
            _timer.Stop();
            _timer.Start();
        }

        internal void Cancel() => _timer.Stop();
    }
}

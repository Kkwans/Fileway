using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.System;

namespace Fileway.App;

public sealed partial class MainPage
{
    private EmbeddedTailnetStatus? _tailnetStatus;
    private CancellationTokenSource? _tailnetObservation;
    private CancellationTokenSource? _tailnetActionCancellation;
    private bool _tailnetBusy;
    private bool _tailnetNeedsReconcile;
    private bool _tailnetReadFailed;
    private string? _tailnetOperation;
    private string? _tailnetProblem;
    private (EmbeddedTailnetState State, bool Authenticated)? _tailnetProblemContext;

    private NetworkMode SelectedConnectionNetwork => ConnectionNetworkBox?.SelectedIndex switch
    {
        1 => NetworkMode.SystemTailnet,
        2 => NetworkMode.EmbeddedTailnet,
        _ => NetworkMode.SystemNetwork,
    };

    private void ConnectionNetwork_Changed(object sender, SelectionChangedEventArgs args)
    {
        if (_applyingConnectionForm) return;
        UpdateNetworkPane();
        ConnectionDraftChanged(sender, args);
    }

    private void UpdateNetworkPane()
    {
        if (EmbeddedTailnetCard is null || ConnectionNetworkBox is null || SystemTailnetHint is null) return;
        var network = SelectedConnectionNetwork;
        SystemTailnetHint.Visibility = network == NetworkMode.SystemTailnet ? Visibility.Visible : Visibility.Collapsed;
        EmbeddedTailnetCard.Visibility = network == NetworkMode.EmbeddedTailnet ? Visibility.Visible : Visibility.Collapsed;
        bool embedded = !_disposed && !_connecting && _currentPane == MainPane.Connection && network == NetworkMode.EmbeddedTailnet;
        if (!embedded || _tailnetBusy) StopTailnetObservation();
        else if (_files is IEmbeddedTailnetService service && _tailnetObservation is null) _ = ObserveTailnetAsync(service);
        RenderTailnetStatus();
    }

    private void RenderTailnetStatus()
    {
        if (TailnetStateText is null || _disposed) return;
        bool available = _files is IEmbeddedTailnetService;
        bool ready = !_connecting && !_tailnetBusy && available;
        ConnectionNetworkBox.IsEnabled = !_connecting && !_tailnetBusy;
        UpdateConnectionPrimaryAction();
        var status = _tailnetStatus;
        var state = status?.State;
        var (title, hint) = TailnetStatusCopy(available, status);
        if (TailnetStateText.Text != title) TailnetStateText.Text = title;
        TailnetHintText.Text = hint;
        TailnetHintText.Visibility = hint.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        TailnetAddressText.Text = !_tailnetBusy && status is { State: EmbeddedTailnetState.Running }
            ? string.Join(" · ", status.Addresses.Concat(status.AcceptSubnets ? ["接收已批准的子网路由"] : [])) : "";
        TailnetAddressText.Visibility = TailnetAddressText.Text.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        TailnetFeedbackText.Text = _tailnetProblem ?? "";
        TailnetFeedbackText.Visibility = TailnetFeedbackText.Text.Length == 0 ? Visibility.Collapsed : Visibility.Visible;

        bool needsRead = _tailnetNeedsReconcile || _tailnetReadFailed;
        bool showProgress = EmbeddedTailnetCard.Visibility == Visibility.Visible && _currentPane == MainPane.Connection &&
            (_tailnetBusy || available && !needsRead && status is null);
        TailnetProgressRing.IsActive = showProgress;
        TailnetProgressRing.Visibility = showProgress ? Visibility.Visible : Visibility.Collapsed;
        bool showActions = available && !_tailnetBusy && !needsRead && status is not null;
        bool canStart = state is EmbeddedTailnetState.Unconfigured or EmbeddedTailnetState.Configured or
            EmbeddedTailnetState.Stopped or EmbeddedTailnetState.Unavailable ||
            state == EmbeddedTailnetState.NeedsLogin && status?.ApprovalUri is null;
        bool canStop = state is EmbeddedTailnetState.Running or EmbeddedTailnetState.Starting or
            EmbeddedTailnetState.NeedsApproval or EmbeddedTailnetState.NeedsLogin;
        TailnetStartButton.Content = state == EmbeddedTailnetState.NeedsLogin ? "重试授权" : "启动";
        TailnetStartButton.Visibility = showActions && canStart ? Visibility.Visible : Visibility.Collapsed;
        TailnetStopButton.Visibility = showActions && canStop ? Visibility.Visible : Visibility.Collapsed;
        TailnetAuthorizeButton.Visibility = showActions && status?.ApprovalUri is not null ? Visibility.Visible : Visibility.Collapsed;
        TailnetLogoutButton.Visibility = showActions && status?.Authenticated == true ? Visibility.Visible : Visibility.Collapsed;
        TailnetReadButton.Visibility = available && needsRead && !_tailnetBusy ? Visibility.Visible : Visibility.Collapsed;
        TailnetStartButton.IsEnabled = TailnetStopButton.IsEnabled = TailnetAuthorizeButton.IsEnabled =
            TailnetLogoutButton.IsEnabled = ready && !needsRead;
        TailnetReadButton.IsEnabled = ready && needsRead;
    }

    private (string Title, string Hint) TailnetStatusCopy(bool available, EmbeddedTailnetStatus? status)
    {
        if (!available) return ("此安装不支持内嵌网络", "可改用系统网络。");
        if (_tailnetBusy) return (_tailnetOperation ?? "正在处理内嵌网络…", "");
        if (_tailnetReadFailed) return ("暂时无法读取内嵌网络", "读取状态后再继续。");
        if (_tailnetNeedsReconcile) return ("正在核对内嵌网络", "请先确认当前状态。");
        return status?.State switch
        {
            EmbeddedTailnetState.Running => ("内嵌网络已连接", "可连接 Tailnet 内的服务器。"),
            EmbeddedTailnetState.NeedsLogin when status.ApprovalUri is null => ("等待授权链接", "可重试获取授权。"),
            EmbeddedTailnetState.NeedsLogin => ("等待登录授权", "授权后回到栖卷。"),
            EmbeddedTailnetState.NeedsApproval => ("等待设备批准", "请联系 Tailnet 管理员。"),
            EmbeddedTailnetState.Starting => ("正在启动内嵌网络…", ""),
            EmbeddedTailnetState.Unavailable => ("内嵌网络暂不可用", "检查网络后可重试。"),
            EmbeddedTailnetState.Stopped => ("内嵌网络已停止", "设备身份保留在本机。"),
            null => ("正在读取内嵌网络…", ""),
            _ => ("内嵌 Tailscale 未连接", "使用独立的设备身份。"),
        };
    }

    private async Task ObserveTailnetAsync(IEmbeddedTailnetService service)
    {
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        var token = lifetime.Token;
        _tailnetObservation = lifetime;
        try
        {
            using var timer = new PeriodicTimer(TimeSpan.FromSeconds(2));
            do
            {
                var status = await service.ReadTailnetAsync(token);
                if (!ReferenceEquals(_tailnetObservation, lifetime) || token.IsCancellationRequested) return;
                ApplyTailnetStatus(status);
                RenderTailnetStatus();
                if (status.State is EmbeddedTailnetState.Unconfigured or EmbeddedTailnetState.Configured or EmbeddedTailnetState.Stopped)
                    return;
            } while (await timer.WaitForNextTickAsync(token));
        }
        catch (Exception) when (token.IsCancellationRequested) { }
        catch (Exception)
        {
            if (ReferenceEquals(_tailnetObservation, lifetime))
            {
                _tailnetReadFailed = true;
                _tailnetStatus = null;
                RenderTailnetStatus();
            }
        }
        finally
        {
            if (ReferenceEquals(_tailnetObservation, lifetime)) _tailnetObservation = null;
        }
    }

    private void StopTailnetObservation()
    {
        var observation = _tailnetObservation;
        _tailnetObservation = null;
        observation?.Cancel();
    }

    private void ApplyTailnetStatus(EmbeddedTailnetStatus status)
    {
        if (_tailnetProblemContext is { } context && context != (status.State, status.Authenticated))
            SetTailnetProblem(null, null);
        _tailnetStatus = status;
        _tailnetReadFailed = _tailnetNeedsReconcile = false;
    }

    private void SetTailnetProblem(string? message, EmbeddedTailnetStatus? context)
    {
        _tailnetProblem = message;
        _tailnetProblemContext = message is not null && context is not null
            ? (context.State, context.Authenticated) : null;
    }

    private async Task ChangeTailnetAsync(string label,
        Func<IEmbeddedTailnetService, CancellationToken, Task<EmbeddedTailnetStatus>> action, bool readOnly = false)
    {
        if (_tailnetBusy || _connecting || _disposed || _files is not IEmbeddedTailnetService service ||
            !readOnly && (_tailnetNeedsReconcile || _tailnetReadFailed)) return;
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        _tailnetActionCancellation = cancellation;
        _tailnetBusy = true;
        _tailnetOperation = label;
        var previous = _tailnetStatus;
        if (!readOnly) SetTailnetProblem(null, null);
        UpdateNetworkPane();
        try { ApplyTailnetStatus(await action(service, cancellation.Token)); }
        catch (Exception error)
        {
            if (!_disposed)
            {
                _tailnetStatus = null;
                if (readOnly) _tailnetReadFailed = true;
                else
                {
                    _tailnetNeedsReconcile = true;
                    SetTailnetProblem(error is FilewayException { Error.Code: ErrorCode.OutcomeUnknown } ? null
                        : error is FilewayException { Error.Code: ErrorCode.LocalStorageUnavailable } known
                            ? known.Error.Message : "内嵌网络操作未完成。", previous);
                }
            }
        }
        finally
        {
            _tailnetActionCancellation = null;
            _tailnetBusy = false;
            _tailnetOperation = null;
            if (!_disposed) UpdateNetworkPane();
        }
    }

    private async void StartTailnet_Click(object sender, RoutedEventArgs e) =>
        await ChangeTailnetAsync("正在启动内嵌网络…", static (service, token) => service.StartTailnetAsync(token));

    private async void StopTailnet_Click(object sender, RoutedEventArgs e) =>
        await ChangeTailnetAsync("正在停止内嵌网络…", static (service, token) => service.StopTailnetAsync(token));

    private async void ReadTailnet_Click(object sender, RoutedEventArgs e) =>
        await ChangeTailnetAsync("正在读取内嵌网络…", static (service, token) => service.ReadTailnetAsync(token), readOnly: true);

    private async void AuthorizeTailnet_Click(object sender, RoutedEventArgs e)
    {
        if (_disposed || _tailnetBusy || _tailnetStatus?.ApprovalUri is not { } uri) return;
        try
        {
            if (await Launcher.LaunchUriAsync(uri))
            {
                SetTailnetProblem(null, null);
                RenderTailnetStatus();
                return;
            }
        }
        catch (Exception) { /* Never log the authorization URI. */ }
        if (_disposed || _tailnetStatus is not { State: EmbeddedTailnetState.NeedsLogin } current || current.ApprovalUri != uri) return;
        SetTailnetProblem("未能打开浏览器，请检查默认浏览器后重试。", current);
        RenderTailnetStatus();
    }

    private async void LogoutTailnet_Click(object sender, RoutedEventArgs e)
    {
        if (_disposed || _tailnetBusy || _tailnetStatus?.Authenticated != true) return;
        var result = await ShowBrowserDialogAsync(new ContentDialog
        {
            Title = "注销内嵌 Tailscale 设备？",
            Content = new TextBlock
            {
                Text = "当前内嵌连接会断开。系统 Tailscale 和已保存的服务器账号不会受到影响。",
                TextWrapping = TextWrapping.Wrap, MaxWidth = 380,
            },
            PrimaryButtonText = "注销设备", CloseButtonText = "取消", DefaultButton = ContentDialogButton.None,
        });
        if (result == ContentDialogResult.Primary)
            await ChangeTailnetAsync("正在注销内嵌设备…", static (service, token) => service.LogoutTailnetAsync(token));
    }
}

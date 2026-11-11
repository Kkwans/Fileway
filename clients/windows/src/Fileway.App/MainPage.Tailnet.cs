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
    private string? _tailnetProblem;

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
        bool ready = !_connecting && !_tailnetBusy && _files is IEmbeddedTailnetService;
        ConnectionNetworkBox.IsEnabled = !_connecting && !_tailnetBusy;
        UpdateConnectionPrimaryAction();
        var status = _tailnetStatus;
        TailnetStateText.Text = _tailnetBusy ? "正在处理内嵌网络…"
            : _tailnetProblem ?? (status?.State switch
            {
                EmbeddedTailnetState.Running => "内嵌 Tailscale 已连接。可输入 Tailnet 内的服务器地址。",
                EmbeddedTailnetState.NeedsLogin => status.ApprovalUri is null
                    ? "正在获取登录授权。若迟迟没有出现，可重试授权。"
                    : "等待登录授权。完成后回到栖卷。",
                EmbeddedTailnetState.NeedsApproval => "等待 Tailnet 管理员批准此设备。",
                EmbeddedTailnetState.Starting => "正在启动内嵌 Tailscale…",
                EmbeddedTailnetState.Unavailable => "内嵌网络暂不可用，请重试启动。",
                _ => "内嵌 Tailscale 未连接。启动后可在栖卷内使用独立设备身份。",
            });
        TailnetAddressText.Text = status is { State: EmbeddedTailnetState.Running }
            ? string.Join(" · ", status.Addresses.Concat(status.AcceptSubnets ? ["接收已批准的子网路由"] : [])) : "";
        TailnetAddressText.Visibility = TailnetAddressText.Text.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        var state = status?.State;
        bool canStart = state is null or EmbeddedTailnetState.Unconfigured or EmbeddedTailnetState.Configured or
            EmbeddedTailnetState.Stopped or EmbeddedTailnetState.Unavailable ||
            state == EmbeddedTailnetState.NeedsLogin && status?.ApprovalUri is null;
        bool canStop = state is EmbeddedTailnetState.Running or EmbeddedTailnetState.Starting or
            EmbeddedTailnetState.NeedsApproval or EmbeddedTailnetState.NeedsLogin;
        TailnetStartButton.Content = state == EmbeddedTailnetState.NeedsLogin ? "重试授权" : "启动";
        TailnetStartButton.Visibility = canStart ? Visibility.Visible : Visibility.Collapsed;
        TailnetStopButton.Visibility = canStop ? Visibility.Visible : Visibility.Collapsed;
        TailnetAuthorizeButton.Visibility = status?.ApprovalUri is not null ? Visibility.Visible : Visibility.Collapsed;
        TailnetLogoutButton.Visibility = status?.Authenticated == true ? Visibility.Visible : Visibility.Collapsed;
        TailnetStartButton.IsEnabled = TailnetStopButton.IsEnabled = TailnetAuthorizeButton.IsEnabled = TailnetLogoutButton.IsEnabled = ready;
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
                _tailnetStatus = status;
                if (_tailnetNeedsReconcile) { _tailnetNeedsReconcile = false; _tailnetProblem = null; }
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
                _tailnetProblem = "内嵌网络状态暂不可读取，请重试。";
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

    private async Task ChangeTailnetAsync(Func<IEmbeddedTailnetService, CancellationToken, Task<EmbeddedTailnetStatus>> action)
    {
        if (_tailnetBusy || _connecting || _disposed || _files is not IEmbeddedTailnetService service) return;
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        _tailnetActionCancellation = cancellation;
        _tailnetBusy = true;
        _tailnetNeedsReconcile = false;
        _tailnetProblem = null;
        UpdateNetworkPane();
        try { _tailnetStatus = await action(service, cancellation.Token); }
        catch (Exception error)
        {
            if (!_disposed)
            {
                _tailnetNeedsReconcile = error is FilewayException { Error.Code: ErrorCode.OutcomeUnknown };
                _tailnetProblem = _tailnetNeedsReconcile
                    ? "操作结果尚未确认，正在重新读取网络状态。"
                    : error is FilewayException { Error.Code: ErrorCode.LocalStorageUnavailable } known
                        ? known.Error.Message : "内嵌 Tailscale 暂不可用，请检查网络后重试。";
                _tailnetStatus = null;
            }
        }
        finally
        {
            _tailnetActionCancellation = null;
            _tailnetBusy = false;
            if (!_disposed) UpdateNetworkPane();
        }
    }

    private async void StartTailnet_Click(object sender, RoutedEventArgs e) =>
        await ChangeTailnetAsync(static (service, token) => service.StartTailnetAsync(token));

    private async void StopTailnet_Click(object sender, RoutedEventArgs e) =>
        await ChangeTailnetAsync(static (service, token) => service.StopTailnetAsync(token));

    private async void AuthorizeTailnet_Click(object sender, RoutedEventArgs e)
    {
        if (_disposed || _tailnetBusy || _tailnetStatus?.ApprovalUri is not { } uri) return;
        try
        {
            if (await Launcher.LaunchUriAsync(uri)) return;
        }
        catch (Exception) { /* Never log the authorization URI. */ }
        if (_disposed) return;
        _tailnetProblem = "未能打开浏览器，请检查默认浏览器后重试。";
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
            await ChangeTailnetAsync(static (service, token) => service.LogoutTailnetAsync(token));
    }
}

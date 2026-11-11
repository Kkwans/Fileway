using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App;

public sealed partial class MainPage
{
    private void SetNotice(string title, string message, InfoBarSeverity severity, bool repeat = true) =>
        PresentNotice(Notice, NoticeErrorDetailsButton, title, message, severity, repeat: repeat);

    private void SetNotice(string title, Exception exception, InfoBarSeverity severity) =>
        SetNotice(title, DescribeError(exception), severity);

    private void SetNotice(string title, ErrorInfo error, InfoBarSeverity severity) =>
        PresentNotice(Notice, NoticeErrorDetailsButton, title, error.Message, severity, error);

    private void LoginError(string message) =>
        PresentNotice(LoginNotice, LoginErrorDetailsButton, "", message, InfoBarSeverity.Error);

    private void LoginError(ErrorInfo error, bool automatic = false, bool restoring = false)
    {
        string message = restoring && (error.Code is ErrorCode.NetworkUnavailable or ErrorCode.Timeout)
            ? "服务恢复后可重试。" : error.Message;
        PresentNotice(LoginNotice, LoginErrorDetailsButton, automatic ? "自动恢复登录失败" : "",
            message, InfoBarSeverity.Error, error);
    }

    private void PresentNotice(InfoBar bar, Button details, string title, string message, InfoBarSeverity severity,
        ErrorInfo? error = null, bool repeat = true)
    {
        bar.Title = title;
        bar.Message = message;
        bar.Severity = severity;
        details.Tag = error;
        details.Visibility = error is null ? Visibility.Collapsed : Visibility.Visible;
        ShowToast(bar, title + message, repeat);
        if (ReferenceEquals(bar, LoginNotice)) UpdateConnectionSecondaryActions();
    }

    private async void NoticeErrorDetails_Click(object sender, RoutedEventArgs args)
    {
        if (_disposed || sender is not Button { Tag: ErrorInfo error }) return;
        string type = error.Code switch
        {
            ErrorCode.AuthenticationExpired or ErrorCode.CredentialsUnavailable => "登录与凭据",
            ErrorCode.PermissionDenied => "访问权限",
            ErrorCode.NetworkUnavailable or ErrorCode.Timeout => "网络连接",
            ErrorCode.Conflict or ErrorCode.SourceChanged => "文件或状态变化",
            ErrorCode.LocalStorageUnavailable => "本地存储",
            ErrorCode.CapabilityMissing or ErrorCode.ProtocolMismatch or ErrorCode.ResponseTooLarge => "服务器能力与响应",
            ErrorCode.HostExited => "本地服务",
            ErrorCode.InvalidRequest => "请求参数",
            ErrorCode.OutcomeUnknown => "结果待核实",
            ErrorCode.Canceled or ErrorCode.Busy => "操作状态",
            _ => "其他错误",
        };
        var status = error.HttpStatus is >= 100 and <= 599 ? $" · HTTP {error.HttpStatus}" : "";
        var text = new TextBlock
        {
            Text = $"{type} · {error.Code}{status}\n{error.Message}",
            TextWrapping = TextWrapping.Wrap,
            IsTextSelectionEnabled = true,
            MaxWidth = 440,
        };
        await ShowBrowserDialogAsync(new ContentDialog { Title = "错误详情", CloseButtonText = "关闭", Content = text });
    }

    internal static string ErrorMessage(Exception exception) => DescribeError(exception).Message;

    private static ErrorInfo DescribeError(Exception exception) => exception switch
    {
        FilewayException known => known.Error,
        TimeoutException => new(ErrorCode.Timeout, "等待响应超时。"),
        System.Net.Http.HttpRequestException => new(ErrorCode.NetworkUnavailable, "暂时无法与服务器通信。"),
        UnauthorizedAccessException => new(ErrorCode.PermissionDenied, "访问被拒绝，请检查权限。"),
        OperationCanceledException => new(ErrorCode.Canceled, "操作已取消。"),
        _ => new(ErrorCode.Unknown, "操作未能完成。"),
    };
}

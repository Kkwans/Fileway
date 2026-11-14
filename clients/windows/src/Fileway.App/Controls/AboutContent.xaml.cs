using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.System;

namespace Fileway.App.Controls;

public sealed partial class AboutContent : UserControl, IDisposable
{
    internal event EventHandler? CloseRequested;
    private readonly IAppUpdateService _updates;
    private readonly Version? _installed;
    private readonly CancellationTokenSource _cancellation;
    private readonly CancellationToken _token;

    public AboutContent(string? version, IAppUpdateService updates, CancellationToken lifetime)
    {
        _updates = updates;
        _installed = Version.TryParse(version, out var parsed) && parsed.Build >= 0
            ? new Version(parsed.Major, parsed.Minor, parsed.Build) : null;
        _cancellation = CancellationTokenSource.CreateLinkedTokenSource(lifetime);
        _token = _cancellation.Token;
        InitializeComponent();
        VersionText.Text = _installed?.ToString() ?? "未知";
    }

    private async void CheckUpdate_Click(object sender, RoutedEventArgs e)
    {
        CheckButton.IsEnabled = false;
        CheckButton.Content = "检查更新";
        ReleaseLink.Visibility = Visibility.Collapsed;
        ReleaseLink.NavigateUri = null;
        UpdateProgress.Visibility = Visibility.Visible;
        UpdateProgress.IsIndeterminate = true;
        UpdateStatus.Text = "正在检查 GitHub 公开发布…";
        try
        {
            var release = await _updates.GetLatestWindowsReleaseAsync(_token);
            if (_token.IsCancellationRequested) return;
            if (release is null)
                UpdateStatus.Text = "GitHub 暂无可用的 Windows x64 发布。本机预览包不在公开发布目录中。";
            else
            {
                string channel = release.Preview ? "预览版" : "正式版";
                UpdateStatus.Text = _installed is null
                    ? $"公开{channel}为 {release.Version}，无法识别本机版本，请手动核对。"
                    : release.Version > _installed
                        ? $"发现{channel} {release.Version}。请在发布页查看说明并手动下载。"
                        : $"没有更高版本。GitHub 最新{channel}为 {release.Version}。";
                ReleaseLink.NavigateUri = release.ReleasePage;
                ReleaseLink.Visibility = Visibility.Visible;
            }
        }
        catch (OperationCanceledException) when (_token.IsCancellationRequested) { }
        catch (Exception error)
        {
            if (_token.IsCancellationRequested) return;
            UpdateStatus.Text = error is FilewayException known ? known.Error.Message : "检查更新未完成，请重试。";
            CheckButton.Content = "重试";
        }
        finally
        {
            if (!_token.IsCancellationRequested)
            {
                UpdateProgress.IsIndeterminate = false;
                UpdateProgress.Visibility = Visibility.Collapsed;
                CheckButton.IsEnabled = true;
            }
        }
    }

    public void Dispose()
    {
        _cancellation.Cancel();
        _cancellation.Dispose();
    }

    private async void OpenSource_Click(object sender, RoutedEventArgs e) =>
        await Launcher.LaunchUriAsync(new Uri("https://github.com/Kkwans/Fileway"));

    private async void OpenLicense_Click(object sender, RoutedEventArgs e) =>
        await Launcher.LaunchUriAsync(new Uri("https://github.com/Kkwans/Fileway/blob/codex/windows-native-client/clients/windows/LICENSE"));

    private void Close_Click(object sender, RoutedEventArgs e) => CloseRequested?.Invoke(this, EventArgs.Empty);
}

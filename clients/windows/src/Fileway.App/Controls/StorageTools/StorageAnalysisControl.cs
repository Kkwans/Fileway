using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App.Controls.StorageTools;

/// <summary>
/// Server-side storage analysis panel: one bounded scan task per run, polled through the server's
/// own task status, with the completed report rendered from server-provided numbers only.
/// </summary>
public sealed class StorageAnalysisControl : UserControl, IDisposable
{
    private readonly CancellationTokenSource _lifetime = new();
    private readonly TextBox _scopeBox = new() { Header = "分析范围（服务器路径）", IsReadOnly = true };
    private readonly Button _start = new() { Content = "开始分析" };
    private readonly InfoBar _notice = new() { IsOpen = false, IsClosable = false, Severity = InfoBarSeverity.Informational };
    private readonly ProgressRing _progress = new() { IsActive = false, Visibility = Visibility.Collapsed };
    private readonly StackPanel _results = new() { Spacing = 10 };
    private RemoteResourceRef? _directory;
    private bool _busy;

    public StorageAnalysisControl()
    {
        var root = new StackPanel { Spacing = 12, Padding = new Thickness(24, 18, 24, 24), MaxWidth = 920, HorizontalAlignment = HorizontalAlignment.Stretch };
        root.Children.Add(new TextBlock { Text = "存储分析", FontSize = 20, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
        root.Children.Add(new TextBlock { Text = "由服务器统计范围内的文件数、目录数与总大小，并列出最大的文件与目录；分析在服务器后台执行，客户端只呈现服务器给出的数字。", TextWrapping = TextWrapping.Wrap, Foreground = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Gray) });
        root.Children.Add(_scopeBox);
        _start.Click += async (_, _) => await StartAsync();
        root.Children.Add(_start);
        root.Children.Add(_notice);
        root.Children.Add(_progress);
        root.Children.Add(_results);
        Content = new ScrollViewer { Content = root, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
    }

    public void SetContext(RemoteResourceRef? directory)
    {
        _directory = directory;
        _scopeBox.Text = directory?.DisplayPath ?? "连接后可选择要分析的目录";
        _start.IsEnabled = directory is not null && !_busy;
        if (directory is null) { _results.Children.Clear(); _notice.IsOpen = false; }
    }

    public void SuspendView() { _lifetime.Cancel(); _start.IsEnabled = false; }

    private void SetNotice(string message, InfoBarSeverity severity)
    {
        _notice.Message = message;
        _notice.Severity = severity;
        _notice.IsOpen = true;
    }

    private async Task StartAsync()
    {
        if (_busy || _lifetime.IsCancellationRequested) return;
        if (_directory is not { } directory) return;
        _busy = true;
        _start.IsEnabled = false;
        _results.Children.Clear();
        _progress.IsActive = true;
        _progress.Visibility = Visibility.Visible;
        try
        {
            var task = await _service.StartAsync(directory, _lifetime.Token);
            SetNotice("分析任务已提交，等待服务器完成…", InfoBarSeverity.Informational);
            var completed = await WaitAsync(task);
            if (completed is null) return;
            if (completed.Status != ServerTaskStatus.Completed)
            {
                SetNotice($"分析未完成：{completed.Title}（{completed.Status}）" +
                    (completed.Error is { } error ? "：" + error.Message : ""), InfoBarSeverity.Error);
                return;
            }
            var report = await _service.GetReportAsync(completed.Reference, _lifetime.Token);
            if (report is null)
            {
                SetNotice("服务器尚未发布分析结果，请稍后在文件任务中重试读取。", InfoBarSeverity.Warning);
                return;
            }
            SetNotice($"分析完成：{report.ScannedFiles:N0} 个文件 · {report.ScannedDirectories:N0} 个目录 · {FormatBytes(report.ScannedBytes)}" +
                (report.Truncated ? "（结果被服务器截断）" : "") +
                (report.SkippedCount > 0 ? $" · {report.SkippedCount} 项跳过" : ""), InfoBarSeverity.Success);
            Render(report);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            SetNotice(error is FilewayException fileway ? fileway.Error.Message : "存储分析未能完成，请检查连接后重试。", InfoBarSeverity.Error);
        }
        finally
        {
            _busy = false;
            _progress.IsActive = false;
            _progress.Visibility = Visibility.Collapsed;
            if (!_lifetime.IsCancellationRequested) _start.IsEnabled = _directory is not null;
        }
    }

    private async Task<ServerTask?> WaitAsync(ServerTaskRef task)
    {
        var deadline = System.Diagnostics.Stopwatch.GetTimestamp() + 600L * System.Diagnostics.Stopwatch.Frequency;
        while (true)
        {
            _lifetime.Token.ThrowIfCancellationRequested();
            if (System.Diagnostics.Stopwatch.GetTimestamp() >= deadline) throw new FilewayException(new ErrorInfo(ErrorCode.Timeout, "分析超过 10 分钟仍未完成，请稍后在文件任务中查看结果。"));
            var current = await _service.GetTaskAsync(task, _lifetime.Token);
            if (current.Status is ServerTaskStatus.Completed or ServerTaskStatus.Failed or ServerTaskStatus.Canceled or ServerTaskStatus.Interrupted)
                return current;
            await Task.Delay(TimeSpan.FromSeconds(1.5), _lifetime.Token);
        }
    }

    private void Render(StorageAnalysisReport report)
    {
        _results.Children.Clear();
        foreach (var scope in report.Scopes)
        {
            _results.Children.Add(new Border
            {
                Padding = new Thickness(12, 8, 12, 8),
                CornerRadius = new CornerRadius(6),
                Background = (Microsoft.UI.Xaml.Media.Brush)Microsoft.UI.Xaml.Application.Current.Resources["LayerFillColorDefaultBrush"],
                Child = new TextBlock { Text = $"{scope.Path}\n{FormatBytes(scope.Bytes)} · {scope.Files:N0} 个文件 · {scope.Directories:N0} 个目录", TextWrapping = TextWrapping.Wrap },
            });
        }
        if (report.LargestFiles.Count > 0)
        {
            _results.Children.Add(Section("最大的文件"));
            foreach (var file in report.LargestFiles.Take(10))
                _results.Children.Add(new TextBlock { Text = $"{FormatBytes(file.Size)} · {file.Path}", TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(8, 0, 0, 2) });
        }
        if (report.LargestDirectories.Count > 0)
        {
            _results.Children.Add(Section("最大的目录"));
            foreach (var directory in report.LargestDirectories.Take(10))
                _results.Children.Add(new TextBlock { Text = $"{FormatBytes(directory.Bytes)} · {directory.Files:N0} 个文件 · {directory.Path}", TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(8, 0, 0, 2) });
        }
    }

    private static TextBlock Section(string title) => new() { Text = title, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, Margin = new Thickness(0, 8, 0, 0) };

    public void Dispose()
    {
        _lifetime.Cancel();
        _lifetime.Dispose();
    }

    internal static string FormatBytes(long bytes) => bytes switch
    {
        >= 1_000_000_000_000 => $"{bytes / 1_000_000_000_000.0:0.##} TB",
        >= 1_000_000_000 => $"{bytes / 1_000_000_000.0:0.##} GB",
        >= 1_000_000 => $"{bytes / 1_000_000.0:0.##} MB",
        >= 1_000 => $"{bytes / 1_000.0:0.##} KB",
        _ => $"{bytes} B",
    };

    private IStorageAnalysisService _service = null!;

    public void Attach(IStorageAnalysisService service) => _service = service;
}

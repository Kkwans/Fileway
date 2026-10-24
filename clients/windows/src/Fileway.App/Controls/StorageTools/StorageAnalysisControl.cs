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
    private IDuplicateAnalysisService? _duplicates;
    private string? _duplicateReportId;
    private ServerTaskRef? _duplicateTask;
    private readonly StackPanel _duplicatesRoot = new() { Spacing = 12 };
    private readonly TextBox _duplicateScopeBox = new() { Header = "扫描范围（服务器路径）", IsReadOnly = true };
    private readonly Button _duplicateStart = new() { Content = "开始扫描" };
    private readonly ScrollViewer _duplicateScroll = new() { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, MaxHeight = 560 };
    private readonly StackPanel _duplicateResults = new() { Spacing = 10 };
    private readonly Dictionary<string, List<RadioButton>> _keepChoices = [];
    private readonly RadioButton _analysisTabButton = new() { Content = "容量分析" };
    private readonly RadioButton _duplicatesTabButton = new() { Content = "重复文件" };

    public StorageAnalysisControl()
    {
        var analysisRoot = new StackPanel { Spacing = 12 };
        analysisRoot.Children.Add(new TextBlock { Text = "由服务器统计范围内的文件数、目录数与总大小，并列出最大的文件与目录；分析在服务器后台执行，客户端只呈现服务器给出的数字。", TextWrapping = TextWrapping.Wrap, Foreground = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Gray) });
        analysisRoot.Children.Add(_scopeBox);
        _start.Click += async (_, _) => await StartAsync();
        analysisRoot.Children.Add(_start);
        analysisRoot.Children.Add(_notice);
        analysisRoot.Children.Add(_progress);
        analysisRoot.Children.Add(_results);

        _duplicateStart.Click += async (_, _) => await StartDuplicateScanAsync();
        _duplicatesRoot.Children.Add(new TextBlock { Text = "服务器按内容找出重复文件并给出保留建议；勾选每组要保留的副本后提交，其余副本移入回收站（可恢复）。绝不直接删除，也不会改动组外文件。", TextWrapping = TextWrapping.Wrap, Foreground = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Gray) });
        _duplicatesRoot.Children.Add(_duplicateScopeBox);
        _duplicatesRoot.Children.Add(_duplicateStart);
        _duplicateScroll.Content = _duplicateResults;
        _duplicatesRoot.Children.Add(_duplicateScroll);

        var analysisHost = new Grid();
        analysisHost.Children.Add(analysisRoot);
        var duplicatesHost = new Grid { Visibility = Visibility.Collapsed };
        duplicatesHost.Children.Add(_duplicatesRoot);
        analysisHost.Children.Add(duplicatesHost);

        var root = new StackPanel { Spacing = 12, Padding = new Thickness(24, 18, 24, 24), MaxWidth = 920, HorizontalAlignment = HorizontalAlignment.Stretch };
        root.Children.Add(new TextBlock { Text = "存储工具", FontSize = 20, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
        var tabs = new RadioButtons { SelectedIndex = 0 };
        tabs.Items.Add(_analysisTabButton);
        tabs.Items.Add(_duplicatesTabButton);
        tabs.SelectionChanged += (_, _) =>
        {
            bool duplicates = tabs.SelectedIndex == 1;
            analysisHost.Visibility = duplicates ? Visibility.Collapsed : Visibility.Visible;
            duplicatesHost.Visibility = duplicates ? Visibility.Visible : Visibility.Collapsed;
        };
        root.Children.Add(tabs);
        root.Children.Add(analysisHost);
        root.Children.Add(duplicatesHost);
        Content = new ScrollViewer { Content = root, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
    }

    public void SetContext(RemoteResourceRef? directory)
    {
        _directory = directory;
        _scopeBox.Text = directory?.DisplayPath ?? "连接后可选择要分析的目录";
        _duplicateScopeBox.Text = _scopeBox.Text;
        _duplicateStart.IsEnabled = _duplicates is not null && directory is not null && !_busy;
        _start.IsEnabled = directory is not null && !_busy;
        _duplicateReportId = null;
        _duplicateTask = null;
        _keepChoices.Clear();
        _duplicateResults.Children.Clear();
        if (directory is null) { _results.Children.Clear(); _notice.IsOpen = false; }
    }

    public void SuspendView() { _lifetime.Cancel(); _start.IsEnabled = false; }

    private void SetNotice(string message, InfoBarSeverity severity)
    {
        _notice.Message = message;
        _notice.Severity = severity;
        _notice.IsOpen = true;
    }


    private async Task StartDuplicateScanAsync()
    {
        if (_busy || _duplicates is null || _lifetime.IsCancellationRequested) return;
        if (_directory is not { } directory) return;
        _busy = true;
        _duplicateStart.IsEnabled = false;
        _duplicateResults.Children.Clear();
        _keepChoices.Clear();
        try
        {
            var task = await _duplicates.StartScanAsync(directory, _lifetime.Token);
            _duplicateReportId = task.Id;
            _duplicateTask = task;
            AddDuplicateStatus("扫描任务已提交，等待服务器完成…");
            var deadline = System.Diagnostics.Stopwatch.GetTimestamp() + 900L * System.Diagnostics.Stopwatch.Frequency;
            while (true)
            {
                _lifetime.Token.ThrowIfCancellationRequested();
                var current = await _duplicates.GetTaskAsync(task, _lifetime.Token);
                if (current.Status is ServerTaskStatus.Completed or ServerTaskStatus.Failed or ServerTaskStatus.Canceled or ServerTaskStatus.Interrupted)
                {
                    if (current.Status != ServerTaskStatus.Completed)
                        throw new FilewayException(new ErrorInfo(ErrorCode.Unknown,
                            $"扫描未完成（{current.Status}）" + (current.Error is { } taskError ? "：" + taskError.Message : "")));
                    break;
                }
                if (System.Diagnostics.Stopwatch.GetTimestamp() >= deadline)
                    throw new FilewayException(new ErrorInfo(ErrorCode.Timeout, "扫描超过 15 分钟仍未完成，请稍后再试。"));
                await Task.Delay(TimeSpan.FromSeconds(1.5), _lifetime.Token);
            }
            var report = await _duplicates.GetReportAsync(task, _lifetime.Token)
                ?? throw new FilewayException(new ErrorInfo(ErrorCode.OutcomeUnknown, "服务器尚未发布扫描结果，请稍后重试。"));
            RenderDuplicateReport(report);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            AddDuplicateStatus(error is FilewayException fileway ? fileway.Error.Message : "重复扫描未能完成，请检查连接后重试。", InfoBarSeverity.Error);
        }
        finally { _busy = false; _duplicateStart.IsEnabled = _duplicates is not null && _directory is not null; }
    }

    private void RenderDuplicateReport(DuplicateReport report)
    {
        AddDuplicateStatus($"扫描完成：{report.ScannedFiles:N0} 个文件 · {FormatBytes(report.ScannedBytes)} · " +
            $"{report.DuplicateGroups} 组重复 · 可回收 {FormatBytes(report.ReclaimableBytes)}" +
            (report.Truncated ? "（结果被服务器截断）" : "") + (report.SkippedCount > 0 ? $" · {report.SkippedCount} 项跳过" : ""),
            report.DuplicateGroups > 0 ? InfoBarSeverity.Warning : InfoBarSeverity.Success);
        if (report.Groups.Count == 0) return;
        var submit = new Button { Content = "提交清理（移入回收站）", HorizontalAlignment = HorizontalAlignment.Left };
        submit.Click += async (_, _) => await SubmitCleanupAsync(report);
        _duplicateResults.Children.Add(submit);
        for (int groupIndex = 0; groupIndex < report.Groups.Count; groupIndex++)
        {
            var group = report.Groups[groupIndex];
            var card = new StackPanel { Spacing = 4 };
            card.Children.Add(new TextBlock
            {
                Text = $"第 {groupIndex + 1} 组 · 每个副本 {FormatBytes(group.Size)} · 可回收 {FormatBytes(group.ReclaimableBytes)}" +
                    (group.KeepReason is { } reason ? $" · 服务器建议保留：{KeepReasonLabel(reason)}" : ""),
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap,
            });
            var choices = new List<RadioButton>();
            foreach (var file in group.Files)
            {
                var choice = new RadioButton
                {
                    GroupName = "keep-" + group.Sha256,
                    Content = file.Path,
                    IsChecked = group.SuggestedKeepPath is { } suggested
                        ? string.Equals(file.Path, suggested, StringComparison.Ordinal)
                        : file == group.Files[0],
                };
                choices.Add(choice);
                card.Children.Add(choice);
            }
            _keepChoices[group.Sha256] = choices;
            _duplicateResults.Children.Add(new Border
            {
                Padding = new Thickness(10, 8, 10, 8),
                CornerRadius = new CornerRadius(6),
                Background = (Microsoft.UI.Xaml.Media.Brush)Microsoft.UI.Xaml.Application.Current.Resources["LayerFillColorDefaultBrush"],
                Child = card,
            });
        }
        var confirmBar = new Button { Content = "提交清理（移入回收站）", HorizontalAlignment = HorizontalAlignment.Left };
        confirmBar.Click += async (_, _) => await SubmitCleanupAsync(report);
        _duplicateResults.Children.Add(confirmBar);
    }

    private static string KeepReasonLabel(string reason) => reason switch
    {
        "oldest-created" => "最早的创建时间",
        "missing-created" => "缺少创建时间",
        "tied-created" => "创建时间相同",
        _ => reason,
    };

    private async Task SubmitCleanupAsync(DuplicateReport report)
    {
        if (_duplicates is null || _duplicateReportId is not { } reportId || _busy) return;
        var selections = new List<DuplicateCleanupSelection>();
        foreach (var group in report.Groups)
        {
            if (!_keepChoices.TryGetValue(group.Sha256, out var choices)) continue;
            var keep = choices.FirstOrDefault(choice => choice.IsChecked == true);
            if (keep is null) { AddDuplicateStatus($"第 {group.Sha256[..8]}… 组未选择保留副本，已跳过。", InfoBarSeverity.Warning); continue; }
            selections.Add(new(group.Sha256, keep.Content.ToString()!));
        }
        if (selections.Count == 0) return;
        long reclaimable = report.Groups.Where(group => selections.Any(selection => selection.Sha256 == group.Sha256))
            .Sum(group => group.ReclaimableBytes);
        var panel = new StackPanel { Spacing = 10, MinWidth = 320, MaxWidth = 440 };
        panel.Children.Add(new TextBlock { Text = $"将 {selections.Count} 组重复文件中未保留的副本移入回收站？可回收 {FormatBytes(reclaimable)}。", TextWrapping = TextWrapping.Wrap });
        panel.Children.Add(new TextBlock { Text = "清理只动所选组内未保留的副本，经回收站可恢复；保留的副本与组外文件不受影响。", FontSize = 12, Opacity = 0.75, TextWrapping = TextWrapping.Wrap });
        var confirm = new ContentDialog { Title = "提交清理", Content = panel, PrimaryButtonText = "移入回收站", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary };
        if (await confirm.ShowAsync() != ContentDialogResult.Primary) return;
        _busy = true;
        _duplicateStart.IsEnabled = false;
        AddDuplicateStatus("清理任务已提交，等待服务器逐文件执行…", InfoBarSeverity.Informational);
        try
        {
            var cleanupTask = await _duplicates.StartCleanupAsync(_directory!.Account, reportId, selections, _lifetime.Token);
            var deadline = System.Diagnostics.Stopwatch.GetTimestamp() + 600L * System.Diagnostics.Stopwatch.Frequency;
            while (true)
            {
                _lifetime.Token.ThrowIfCancellationRequested();
                var result = await _duplicates.GetCleanupResultAsync(cleanupTask, _lifetime.Token);
                if (result is { } finished)
                {
                    int success = 0, skipped = 0, failed = 0;
                    foreach (var (_, _, files) in finished.Groups)
                        foreach (var file in files)
                            switch (file.Status) { case "success": success++; break; case "skipped": skipped++; break; default: failed++; break; }
                    AddDuplicateStatus($"清理完成：{success} 个移入回收站 · {skipped} 个跳过 · {failed} 个失败。可在回收站中恢复任何条目。",
                        failed > 0 ? InfoBarSeverity.Warning : InfoBarSeverity.Success);
                    break;
                }
                if (System.Diagnostics.Stopwatch.GetTimestamp() >= deadline)
                    throw new FilewayException(new ErrorInfo(ErrorCode.Timeout, "清理超过 10 分钟仍未完成，请稍后在文件任务中查看结果。"));
                await Task.Delay(TimeSpan.FromSeconds(1.5), _lifetime.Token);
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            AddDuplicateStatus(error is FilewayException fileway ? fileway.Error.Message : "清理未能完成，请检查连接后重试。", InfoBarSeverity.Error);
        }
        finally { _busy = false; _duplicateStart.IsEnabled = _duplicates is not null && _directory is not null; }
    }

    private void AddDuplicateStatus(string message, InfoBarSeverity severity = InfoBarSeverity.Informational)
    {
        _duplicateResults.Children.Insert(0, new TextBlock
        {
            Text = message, TextWrapping = TextWrapping.Wrap, FontSize = 13,
            Foreground = severity switch
            {
                InfoBarSeverity.Error => new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.OrangeRed),
                InfoBarSeverity.Warning => new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Goldenrod),
                InfoBarSeverity.Success => new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Green),
                _ => (Microsoft.UI.Xaml.Media.Brush)Microsoft.UI.Xaml.Application.Current.Resources["TextFillColorSecondaryBrush"],
            },
        });
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

    public void Attach(IStorageAnalysisService service, IDuplicateAnalysisService? duplicates)
    {
        _service = service;
        _duplicates = duplicates;
        _duplicateStart.IsEnabled = duplicates is not null && _directory is not null && !_busy;
    }
}

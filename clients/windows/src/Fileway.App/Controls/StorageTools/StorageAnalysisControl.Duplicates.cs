using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App.Controls.StorageTools;

public sealed partial class StorageAnalysisControl
{
    private sealed record DuplicateChoice(CheckBox Include, List<RadioButton> Copies, bool Eligible);
    private sealed record CleanupRun(string ReportId, DuplicateReport Report, IReadOnlyList<DuplicateCleanupSelection> Selections);

    private readonly Dictionary<string, DuplicateChoice> _keepChoices = [];
    private readonly Button _cleanupRetry = new() { Visibility = Visibility.Collapsed };
    private Button? _cleanupSubmit;
    private TextBlock? _cleanupSelection;
    private CleanupRun? _cleanup;
    private ServerTaskRef? _cleanupTask;
    private bool _cleanupFinished;
    private bool _cleanupSubmissionConfirmed;

    private async Task StartDuplicateScanAsync()
    {
        if (!_duplicateStart.IsEnabled || _duplicates is not { } service || _directory is not { } directory) return;
        _duplicateTask = _cleanupTask = null;
        _cleanup = null;
        _cleanupFinished = _cleanupSubmissionConfirmed = false;
        _duplicateRetry.Visibility = _cleanupRetry.Visibility = Visibility.Collapsed;
        _duplicateNotice.ActionButton = _duplicateRetry;
        ClearDuplicateResults();
        _duplicateStart.Content = "开始扫描";
        _duplicateStart.Style = SurfaceGeometry.Style("FwPrimaryButtonStyle");
        SetBusy(true);
        try
        {
            AddDuplicateStatus("正在提交扫描…");
            _duplicateTask = await service.StartScanAsync(directory, _token);
            await ObserveDuplicateScanAsync(service, _duplicateTask.Value);
        }
        catch (OperationCanceledException) { }
        catch (Exception error) { ReportScanReadFailure(error); }
        finally { SetBusy(false); }
    }

    private async Task ReadDuplicateScanAsync()
    {
        if (_busy || !_canDownload || _duplicateTask is not { } task || _duplicates is not { } service) return;
        SetBusy(true);
        _duplicateRetry.Visibility = Visibility.Collapsed;
        try { await ObserveDuplicateScanAsync(service, task); }
        catch (OperationCanceledException) { }
        catch (Exception error) { ReportScanReadFailure(error); }
        finally { SetBusy(false); }
    }

    private async Task ObserveDuplicateScanAsync(IDuplicateAnalysisService service, ServerTaskRef task)
    {
        AddDuplicateStatus("正在读取扫描进度…");
        var terminal = await WaitForTaskAsync(task, service.GetTaskAsync, TimeSpan.FromMinutes(15), "重复扫描", AddDuplicateStatus);
        if (terminal.Status != ServerTaskStatus.Completed)
        {
            AddDuplicateStatus(TaskStateLabel("扫描", terminal), InfoBarSeverity.Warning);
            return;
        }

        var report = await service.GetReportAsync(task, _token);
        await WaitForViewAsync();
        _token.ThrowIfCancellationRequested();
        if (report is null)
        {
            _duplicateRetry.Visibility = Visibility.Visible;
            AddDuplicateStatus("扫描已结束，报告暂未发布。", InfoBarSeverity.Warning);
            return;
        }
        _duplicateNotice.IsOpen = false;
        RenderDuplicateReport(report, task.Id);
    }

    private void ReportScanReadFailure(Exception error)
    {
        if (_token.IsCancellationRequested) return;
        _duplicateRetry.Visibility = _duplicateTask is null ? Visibility.Collapsed : Visibility.Visible;
        AddDuplicateStatus(error is FilewayException known ? known.Error.Message : "暂时无法读取重复扫描，请检查连接。", InfoBarSeverity.Warning);
    }

    private void RenderDuplicateReport(DuplicateReport report, string reportId)
    {
        ClearDuplicateResults();
        _duplicateStart.Content = "重新扫描";
        _duplicateStart.Style = SurfaceGeometry.Style("FwSecondaryButtonStyle");
        var summary = new StackPanel { Spacing = 4 };
        summary.Children.Add(Section("重复文件报告"));
        summary.Children.Add(ReportCaption($"{report.DuplicateGroups:N0} 组重复 · 重复副本 {FormatBytes(report.ReclaimableBytes)} · 已扫描 {report.ScannedFiles:N0} 个文件"));
        if (report.Truncated || report.SkippedCount > 0)
            summary.Children.Add(ReportCaption((report.Truncated ? "报告仅显示服务器返回的部分结果。" : "") +
                (report.SkippedCount > 0 ? $"扫描跳过 {report.SkippedCount:N0} 项。" : "")));
        _cleanupSelection = ReportCaption("");
        summary.Children.Add(_cleanupSelection);
        _cleanupSubmit = new Button
        {
            Content = "提交清理", Style = SurfaceGeometry.Style("FwPrimaryButtonStyle"), VerticalAlignment = VerticalAlignment.Center,
            Visibility = report.Groups.Count > 0 ? Visibility.Visible : Visibility.Collapsed,
        };
        AutomationProperties.SetAutomationId(_cleanupSubmit, "StorageDuplicateCleanupTop");
        _cleanupSubmit.Click += async (_, _) => await SubmitCleanupAsync(report, reportId);
        var header = new Grid { ColumnSpacing = 12 };
        header.ColumnDefinitions.Add(new ColumnDefinition());
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        header.Children.Add(summary);
        Grid.SetColumn(_cleanupSubmit, 1);
        header.Children.Add(_cleanupSubmit);
        _duplicateResults.Children.Add(header);

        for (int index = 0; index < report.Groups.Count; index++)
        {
            var group = report.Groups[index];
            var eligible = group.TotalFiles >= 2 && group.Files.Count == group.TotalFiles && group.Files.All(file => file.HasIdentity);
            var include = new CheckBox { Content = $"纳入第 {index + 1} 组", IsChecked = eligible };
            AutomationProperties.SetAutomationId(include, $"StorageDuplicateGroupInclude{index + 1}");
            include.Checked += (_, _) => UpdateCleanupControls();
            include.Unchecked += (_, _) => UpdateCleanupControls();
            var choices = new List<RadioButton>();
            var card = new StackPanel { Spacing = 4 };
            card.Children.Add(include);
            card.Children.Add(ReportCaption($"{group.TotalFiles:N0} 份 · 每份 {FormatBytes(group.Size)}" +
                (eligible ? " · 选择保留的副本" : " · 组不完整或文件身份不可核对，无法清理")));
            string? suggested = group.Files.FirstOrDefault(file => file.Path == group.SuggestedKeepPath)?.Path
                ?? (group.Files.Count > 0 ? group.Files[0].Path : null);
            for (int fileIndex = 0; fileIndex < group.Files.Count; fileIndex++)
            {
                var file = group.Files[fileIndex];
                var (name, directory) = ReportPath(file.Path);
                var labels = new StackPanel { Spacing = 2 };
                labels.Children.Add(new TextBlock { Text = name, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                    TextTrimming = TextTrimming.CharacterEllipsis });
                if (directory.Length > 0)
                {
                    var folder = ReportCaption(directory);
                    folder.TextWrapping = TextWrapping.NoWrap;
                    folder.TextTrimming = TextTrimming.CharacterEllipsis;
                    labels.Children.Add(folder);
                }
                var choice = new RadioButton
                {
                    GroupName = "keep-" + group.Sha256, IsChecked = file.Path == suggested, Tag = file,
                    HorizontalContentAlignment = HorizontalAlignment.Stretch, Content = labels,
                };
                ToolTipService.SetToolTip(choice, file.Path);
                AutomationProperties.SetAutomationId(choice, $"StorageDuplicateGroupKeep{index + 1}_{fileIndex + 1}");
                AutomationProperties.SetName(choice, $"保留 {file.Path}");
                choices.Add(choice);
                card.Children.Add(choice);
            }
            _keepChoices[group.Sha256] = new(include, choices, eligible);
            _duplicateResults.Children.Add(ReportCard(card));
        }
        UpdateCleanupControls();
    }

    private void ClearDuplicateResults()
    {
        _keepChoices.Clear();
        _duplicateResults.Children.Clear();
        _cleanupSubmit = null;
        _cleanupSelection = null;
    }

    private void UpdateCleanupControls()
    {
        bool editable = !_busy && _canDownload && _canDelete && _cleanup is null && !_token.IsCancellationRequested;
        foreach (var choice in _keepChoices.Values)
        {
            choice.Include.IsEnabled = editable && choice.Eligible;
            foreach (var copy in choice.Copies) copy.IsEnabled = choice.Include.IsEnabled && choice.Include.IsChecked == true;
        }
        int selected = _keepChoices.Values.Count(choice => choice.Eligible && choice.Include.IsChecked == true);
        if (_cleanupSubmit is not null)
        {
            _cleanupSubmit.IsEnabled = editable && selected > 0;
            if (_cleanup is null)
                _duplicateStart.Style = SurfaceGeometry.Style(_cleanupSubmit.IsEnabled ? "FwSecondaryButtonStyle" : "FwPrimaryButtonStyle");
        }
        _cleanupRetry.IsEnabled = !_busy && _canDownload && !_token.IsCancellationRequested;
        if (_cleanupSelection is not null)
        {
            _cleanupSelection.Visibility = _keepChoices.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
            _cleanupSelection.Text = !_canDownload ? "缺少下载权限，无法清理。"
                : !_canDelete ? "缺少删除权限，无法移入回收站。"
                : $"已选 {selected:N0} 组";
        }
    }

    private async Task SubmitCleanupAsync(DuplicateReport report, string reportId)
    {
        if (_cleanupSubmit?.IsEnabled != true || _duplicates is not { } service || _directory is not { } directory) return;
        var selections = new List<DuplicateCleanupSelection>();
        int copyCount = 0;
        long bytes = 0;
        foreach (var group in report.Groups)
        {
            if (!_keepChoices.TryGetValue(group.Sha256, out var choice) || !choice.Eligible || choice.Include.IsChecked != true) continue;
            if (choice.Copies.FirstOrDefault(copy => copy.IsChecked == true)?.Tag is not DuplicateFileInfo keep) continue;
            selections.Add(new(group.Sha256, keep.Path));
            copyCount += group.Files.Count - 1;
            bytes += group.ReclaimableBytes;
        }
        if (selections.Count == 0) return;
        var panel = new StackPanel { Spacing = 10, MaxWidth = 440 };
        panel.Children.Add(new TextBlock { Text = $"将 {selections.Count:N0} 组中的 {copyCount:N0} 个副本移入回收站，共 {FormatBytes(bytes)}。", TextWrapping = TextWrapping.Wrap });
        panel.Children.Add(ReportCaption("保留所选副本。移入回收站的文件可在回收站恢复。"));
        var kept = new StackPanel { Spacing = 6 };
        foreach (var selection in selections) kept.Children.Add(ReportRow(selection.KeepPath, "file", "保留"));
        panel.Children.Add(new ScrollViewer { Content = kept, MaxHeight = 160, VerticalScrollBarVisibility = ScrollBarVisibility.Auto });
        var confirm = new ContentDialog
        {
            Title = "提交清理", Content = panel, PrimaryButtonText = "移入回收站", CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.None,
        };
        DialogOrdering.FocusCloseButton(confirm);
        if (await ShowDialogAsync(confirm) != ContentDialogResult.Primary || _token.IsCancellationRequested ||
            _resumeObservation is not null || _cleanupSubmit?.IsEnabled != true) return;

        _cleanup = new(reportId, report, selections);
        SetBusy(true);
        AddDuplicateStatus("正在提交清理任务…");
        try
        {
            _cleanupTask = await service.StartCleanupAsync(directory.Account, reportId, selections, _token);
            _cleanupSubmissionConfirmed = true;
        }
        catch (OperationCanceledException) when (_token.IsCancellationRequested) { SetBusy(false); return; }
        catch (FilewayException error) when (error.Error.HttpStatus is 400 or 403 or 404 or 412 or 422)
        {
            _cleanup = null;
            AddDuplicateStatus(error.Error.Message, InfoBarSeverity.Warning);
            SetBusy(false);
            return;
        }
        catch (Exception)
        {
            ShowCleanupRetry("提交结果待核实", "请查询原报告是否已生成清理任务。");
            SetBusy(false);
            return;
        }

        await ReadCleanupCoreAsync(service, directory.Account);
    }

    private async Task RetryCleanupAsync()
    {
        if (_busy || !_canDownload || _cleanup is null || _duplicates is not { } service || _directory is not { } directory) return;
        SetBusy(true);
        await ReadCleanupCoreAsync(service, directory.Account);
    }

    private async Task ReadCleanupCoreAsync(IDuplicateAnalysisService service, AccountKey account)
    {
        _cleanupRetry.Visibility = Visibility.Collapsed;
        try
        {
            if (_cleanup is not { } run) return;
            AddDuplicateStatus("正在读取清理进度…");
            await WaitForViewAsync();
            _token.ThrowIfCancellationRequested();
            _cleanupTask ??= (await service.GetCleanupForReportAsync(account, run.ReportId, _token))?.Reference;
            if (_cleanupTask is not { } task)
            {
                ShowCleanupRetry("尚未查到清理任务", "保留本次选择，可以再次核实原报告。");
                return;
            }
            var terminal = await WaitForTaskAsync(task, service.GetTaskAsync, TimeSpan.FromMinutes(10), "清理", AddDuplicateStatus);
            var result = await service.GetCleanupResultAsync(task, _token);
            await WaitForViewAsync();
            _token.ThrowIfCancellationRequested();
            if (result is not null && result.ReportId != run.ReportId)
                throw new FilewayException(new(ErrorCode.ProtocolMismatch, "服务器返回了其他报告的结果。"));
            if (result is null && terminal.Status == ServerTaskStatus.Completed)
            {
                ShowCleanupRetry("清理任务已结束", "逐文件结果暂未发布，可以重试读取。");
                return;
            }
            _cleanupFinished = true;
            _duplicateNotice.IsOpen = false;
            RenderCleanupReport(run, terminal, result);
        }
        catch (OperationCanceledException) when (_token.IsCancellationRequested) { }
        catch (Exception error)
        {
            ShowCleanupRetry("无法读取清理结果", error is FilewayException known ? known.Error.Message : "检查连接后可以重试读取。");
        }
        finally { SetBusy(false); }
    }

    private void ShowCleanupRetry(string title, string message)
    {
        if (_token.IsCancellationRequested) return;
        _cleanupRetry.Content = _cleanupTask is null ? "核实清理任务" : "重试读取结果";
        _cleanupRetry.Visibility = Visibility.Visible;
        _duplicateNotice.ActionButton = _cleanupRetry;
        AddDuplicateStatus($"{title}。{message}", InfoBarSeverity.Warning);
    }

    private void RenderCleanupReport(CleanupRun run, ServerTask terminal, DuplicateCleanupResult? result)
    {
        ClearDuplicateResults();
        _duplicateStart.Style = SurfaceGeometry.Style("FwPrimaryButtonStyle");
        var serverGroups = result?.Groups.ToLookup(group => group.Sha256, StringComparer.Ordinal);
        var selections = result?.Groups.Select(group => new DuplicateCleanupSelection(group.Sha256, group.KeepPath)).ToList() ?? [];
        if (_cleanupSubmissionConfirmed)
            selections.AddRange(run.Selections.Where(selection => serverGroups?.Contains(selection.Sha256) != true));
        var body = new StackPanel { Spacing = 10 };
        var title = Section(TaskStateLabel("清理", terminal));
        body.Children.Add(title);
        int moved = 0, skipped = 0, failed = 0, unprocessed = 0;
        var scanGroups = run.Report.Groups.ToLookup(group => group.Sha256, StringComparer.Ordinal);
        foreach (var selection in selections)
        {
            var outcomes = serverGroups?[selection.Sha256].FirstOrDefault().Files ?? [];
            var paths = scanGroups[selection.Sha256].SelectMany(group => group.Files).Select(file => file.Path)
                .Concat(outcomes.Select(file => file.Path)).Where(path => path != selection.KeepPath).Distinct(StringComparer.Ordinal);
            var card = new StackPanel { Spacing = 4 };
            card.Children.Add(ReportRow(selection.KeepPath, "file", "保留项"));
            foreach (var path in paths)
            {
                var outcome = outcomes.FirstOrDefault(file => file.Path == path);
                string status;
                switch (outcome?.Status)
                {
                    case "success": moved++; status = "已移入回收站"; break;
                    case "skipped": skipped++; status = "已跳过"; break;
                    case "failed": failed++; status = "未完成"; break;
                    default: unprocessed++; status = "未取得处理结果"; break;
                }
                var row = ReportRow(path, "file", status);
                row.Hint = outcome?.Reason ?? "";
                card.Children.Add(row);
            }
            body.Children.Add(ReportCard(card));
        }
        var counts = new List<string> { $"{moved:N0} 个移入回收站" };
        if (skipped > 0) counts.Add($"{skipped:N0} 个跳过");
        if (failed > 0) counts.Add($"{failed:N0} 个未完成");
        if (unprocessed > 0) counts.Add($"{unprocessed:N0} 个无结果");
        body.Children.Insert(1, ReportCaption(selections.Count == 0 ? "服务器未发布逐文件结果。" : string.Join(" · ", counts)));
        if (terminal.Error is { } error) body.Children.Insert(2, ReportCaption(error.Message));
        var host = new ContentControl { Content = body, HorizontalContentAlignment = HorizontalAlignment.Stretch, IsTabStop = false };
        AutomationProperties.SetName(host, "重复文件清理结果");
        AutomationProperties.SetAutomationId(host, "StorageDuplicateCleanupResult");
        _duplicateResults.Children.Add(host);
    }
}

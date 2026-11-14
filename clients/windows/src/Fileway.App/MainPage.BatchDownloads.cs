using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Fileway.App;

public sealed partial class MainPage
{
    private const int MaximumBatchItems = 100;

    private async Task DownloadBatchSelectionAsync()
    {
        if (!CanDownloadSelection || _downloads is not { } service || _session is not { } session ||
            _directory is not { } directory || ActiveDownloadContext is not { } context) return;
        var selection = SelectedRows().Select(row => (row.File.Resource, row.File.IsDirectory)).ToArray();
        var files = selection.Where(row => !row.IsDirectory).Select(row => row.Resource).ToArray();
        var directories = selection.Where(row => row.IsDirectory).Select(row => row.Resource).ToArray();
        if (files.Length == 0 && directories.Length == 0) return;
        if (files.Length + directories.Length > MaximumBatchItems)
        { SetNotice($"每次最多下载 {MaximumBatchItems} 项", string.Empty, InfoBarSeverity.Warning); return; }
        var generation = _generation; var view = _downloadViewGeneration;
        bool Current() => view == _downloadViewGeneration && IsCurrentDownloadContext(context) &&
            SameBrowser(session, directory, generation) && _session?.Permissions.Download == true &&
            SelectedRows().Select(row => (row.File.Resource, row.File.IsDirectory)).SequenceEqual(selection);
        var previews = new List<DownloadPreview>(); var failures = new List<string>();
        var treeDrafts = new List<DownloadPreview>();
        var treeEntries = new List<(RemoteResourceRef Root, DownloadTreeEntry Entry)>();
        bool confirmedBatch = false;
        bool truncated = false;
        var targets = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        // A slow metadata preflight must not disable pause/cancel for existing tasks.
        _downloadPreparing = true; UpdateDownloadContext();
        try
        {
            long total = 0;
            foreach (var source in files)
            {
                if (!Current()) return;
                try
                {
                    var preview = await service.PrepareAsync(context, source, _lifetime.Token);
                    if (!Current()) return;
                    if (!targets.Add(preview.DestinationDisplayPath))
                    { failures.Add(source.DisplayName + "：本地目标同名，请单独另存为。"); continue; }
                    total = checked(total + preview.TotalBytes); previews.Add(preview);
                }
                catch (Exception error)
                {
                    if (BatchPreflightFatal(error)) throw;
                    failures.Add(source.DisplayName + "：" + ErrorMessage(error));
                }
            }
            // Directory selections enumerate a bounded tree first; enumeration never starts transfers.
            foreach (var root in directories)
            {
                if (!Current()) return;
                DownloadTreeEnumeration? enumeration = null;
                try { enumeration = await service.EnumerateTreeAsync(context, root, _lifetime.Token); }
                catch (Exception error)
                {
                    if (BatchPreflightFatal(error)) throw;
                    failures.Add(root.DisplayName + "：" + ErrorMessage(error));
                }
                if (enumeration is null || !Current()) continue;
                foreach (var entry in enumeration.Files) treeEntries.Add((root, entry));
                foreach (var failed in enumeration.FailedDirectories)
                    failures.Add(root.DisplayName + (failed == "/" ? "" : "/" + failed.Replace('/', '\\')) + "：无法列出，该子目录未包含。");
                if (enumeration.Truncated) truncated = true;
            }
            if (!Current()) return;
            if (previews.Count + treeEntries.Count > MaximumBatchItems)
            {
                int slots = Math.Max(0, MaximumBatchItems - previews.Count);
                if (slots < treeEntries.Count) { treeEntries = treeEntries[..slots]; truncated = true; }
            }
            if (treeEntries.Count != 0)
            {
                var batchId = Guid.NewGuid();
                for (int index = 0; index < treeEntries.Count; index++)
                {
                    if (!Current()) return;
                    var (root, entry) = treeEntries[index];
                    try
                    {
                        var preview = await service.PrepareTreeAsync(context, root, entry, batchId, index, _lifetime.Token);
                        if (!Current()) return;
                        if (!targets.Add(preview.DestinationDisplayPath))
                        { failures.Add(root.DisplayName + "\\" + entry.RelativePath.Replace('/', '\\') + "：映射后本地目标同名。"); continue; }
                        total = checked(total + preview.TotalBytes);
                        treeDrafts.Add(preview);
                    }
                    catch (Exception error)
                    {
                        if (BatchPreflightFatal(error)) throw;
                        failures.Add(root.DisplayName + "\\" + entry.RelativePath.Replace('/', '\\') + "：" + ErrorMessage(error));
                    }
                }
            }
            if (!Current()) return;
            int addable = previews.Count + treeDrafts.Count;
            var destinations = previews.Select(item => item.DestinationDisplayPath)
                .Concat(treeDrafts.Select(item => item.DestinationDisplayPath)).ToArray();
            var commonDirectory = FindBatchDownloadDirectory(destinations);
            var caption = (Style)Application.Current.Resources["FwCaptionTextStyle"];
            var panel = new StackPanel { Spacing = 12, MinWidth = 320, MaxWidth = 480 };
            var heading = new StackPanel { Spacing = 4 };
            heading.Children.Add(new TextBlock { Text = $"可添加 {addable} 个文件 · {DownloadPresentationPolicy.FormatBytes(total)}", FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
            heading.Children.Add(new TextBlock { Text = "按顺序下载，同名文件会保留并报告冲突。" +
                (treeDrafts.Count > 0 ? " 保留目录层级，已映射 Windows 不支持的名称。" : ""),
                TextWrapping = TextWrapping.Wrap, FontSize = 12, Style = caption });
            panel.Children.Add(heading);
            if (commonDirectory is not null)
            {
                var destinationField = new StackPanel { Spacing = 2 };
                destinationField.Children.Add(new TextBlock { Text = "保存到", Style = caption });
                destinationField.Children.Add(new TextBlock { Text = commonDirectory, FontSize = 13, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true });
                panel.Children.Add(destinationField);
            }
            if (truncated) panel.Children.Add(new TextBlock { Text = "枚举达到上限，仅包含前列条目；请选择更具体的子目录分批下载。", TextWrapping = TextWrapping.Wrap, FontSize = 12, Style = caption });
            if (previews.Count + treeDrafts.Count + failures.Count < files.Length + treeEntries.Count)
                panel.Children.Add(new TextBlock { Text = "另有部分条目未完成预检，本次不会添加。", TextWrapping = TextWrapping.Wrap, FontSize = 12, Style = caption });
            if (addable > 0)
            {
                var entries = new StackPanel { Spacing = 10 };
                foreach (var preview in previews.Take(12).Concat(treeDrafts.Take(24)))
                {
                    var entry = new StackPanel { Spacing = 2 };
                    var relativeName = commonDirectory is null ? preview.Name :
                        Path.GetRelativePath(commonDirectory, preview.DestinationDisplayPath);
                    entry.Children.Add(new TextBlock { Text = relativeName + " · " + DownloadPresentationPolicy.FormatBytes(preview.TotalBytes), FontSize = 13, TextWrapping = TextWrapping.Wrap });
                    if (commonDirectory is null) entry.Children.Add(new TextBlock { Text = preview.DestinationDisplayPath, FontSize = 12, TextWrapping = TextWrapping.Wrap, Style = caption, IsTextSelectionEnabled = true });
                    entries.Children.Add(entry);
                }
                int hidden = Math.Max(0, previews.Count - 12) + Math.Max(0, treeDrafts.Count - 24);
                if (hidden > 0) entries.Children.Add(new TextBlock { Text = $"其余 {hidden} 个文件略。", FontSize = 12, Style = caption });
                panel.Children.Add(entries);
            }
            if (failures.Count > 0)
            {
                panel.Children.Add(new TextBlock { Text = $"未添加 {failures.Count} 项", FontSize = 12, Style = caption });
                var failuresPanel = new StackPanel { Spacing = 8 };
                foreach (var failure in failures.Take(12))
                    failuresPanel.Children.Add(new TextBlock { Text = failure, TextWrapping = TextWrapping.Wrap, FontSize = 12, Style = caption, IsTextSelectionEnabled = true });
                if (failures.Count > 12) failuresPanel.Children.Add(new TextBlock { Text = $"另有 {failures.Count - 12} 条失败/跳过项未列出。", FontSize = 12, Style = caption });
                panel.Children.Add(failuresPanel);
            }
            var confirm = new ContentDialog { Title = "确认批量下载", PrimaryButtonText = "添加到下载队列", CloseButtonText = "取消", DefaultButton = ContentDialogButton.None,
                IsPrimaryButtonEnabled = addable > 0, Content = new ScrollViewer { Content = panel, MaxHeight = Fileway.App.Controls.DialogOrdering.ContentMaxHeight(this, 400), VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled } };
            _downloadDialog = confirm;
            DownloadNotice.IsOpen = false;
            ContentDialogResult choice;
            try { choice = await ShowBrowserDialogAsync(confirm); }
            finally { if (ReferenceEquals(_downloadDialog, confirm)) _downloadDialog = null; }
            if (choice != ContentDialogResult.Primary || !Current()) return;
            confirmedBatch = true;
            var feedback = new BatchDownloadFeedback();
            int added = 0, unconfirmed = 0;
            foreach (var preview in previews.Concat(treeDrafts))
            {
                // Confirmation freezes these drafts. Browsing another directory must not
                // discard the rest of an accepted batch; account/session/permission still fence it.
                if (!IsCurrentDownloadContext(context) || _session?.Permissions.Download != true) break;
                _batchDownloadToastTasks[new TransferTaskRef(context.Account, preview.Draft.Id)] = feedback;
                try
                {
                    var item = await service.SubmitAsync(preview, _lifetime.Token);
                    if (IsCurrentDownloadContext(context)) AcceptDownloadItem(item);
                    added++;
                }
                catch (Exception error) when (error is OperationCanceledException or FilewayException { Error.Code: ErrorCode.Canceled })
                {
                    unconfirmed++;
                    break;
                }
                catch (Exception) { unconfirmed++; } // Never automatically replay an unconfirmed draft.
            }
            feedback.AdmissionFinished = true;
            if (IsCurrentDownloadContext(context))
            {
                var complete = added == addable && !feedback.HasAttention;
                var title = unconfirmed > 0
                    ? added + unconfirmed < addable ? $"已停止，{unconfirmed} 项下载待核实" : $"{unconfirmed} 项下载待核实"
                    : complete ? $"已添加 {added} 项下载"
                    : feedback.HasAttention ? "批量下载有待处理项" : added > 0 ? "部分下载未添加" : "下载未添加";
                _pendingBatchDownloadNotices.Enqueue((context, title, complete ? InfoBarSeverity.Success : InfoBarSeverity.Warning));
            }
        }
        catch (OperationCanceledException) { }
        catch (FilewayException error) when (error.Error.Code == ErrorCode.Canceled) { }
        catch (Exception error)
        {
            if (confirmedBatch ? IsCurrentDownloadContext(context) : Current())
                ShowDownloadError("批量下载未完成", error);
        }
        finally { if (view == _downloadViewGeneration) _downloadPreparing = false; if (!_disposed) UpdateDownloadContext(); }
    }

    private static string? FindBatchDownloadDirectory(string[] destinations)
    {
        if (destinations.Length == 0) return null;
        for (var directory = Path.GetDirectoryName(destinations[0]); directory is not null; directory = Path.GetDirectoryName(directory))
        {
            var prefix = Path.EndsInDirectorySeparator(directory) ? directory : directory + Path.DirectorySeparatorChar;
            if (destinations.All(path => path.StartsWith(prefix, StringComparison.Ordinal))) return directory;
        }
        return null;
    }

    private static bool BatchPreflightFatal(Exception error) => error is OperationCanceledException ||
        error is FilewayException { Error.Code: ErrorCode.NetworkUnavailable or ErrorCode.Timeout or ErrorCode.HostExited or
            ErrorCode.AuthenticationExpired or ErrorCode.CredentialsUnavailable or ErrorCode.Canceled };
}

using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

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
        { SetNotice("选择的项目过多", $"每次最多选择 {MaximumBatchItems} 项（文件或目录），请分批选择。", InfoBarSeverity.Warning); return; }
        var generation = _generation; var view = _downloadViewGeneration;
        bool Current() => view == _downloadViewGeneration && IsCurrentDownloadContext(context) &&
            SameBrowser(session, directory, generation) && _session?.Permissions.Download == true &&
            SelectedRows().Select(row => (row.File.Resource, row.File.IsDirectory)).SequenceEqual(selection);
        var previews = new List<DownloadPreview>(); var failures = new List<string>();
        var treeDrafts = new List<DownloadPreview>();
        var treeEntries = new List<(RemoteResourceRef Root, DownloadTreeEntry Entry)>();
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
                    failures.Add(source.DisplayName + "：" + ErrorMessage(error));
                    if (BatchPreflightFatal(error)) return;
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
                    failures.Add(root.DisplayName + "：" + ErrorMessage(error));
                    if (BatchPreflightFatal(error)) return;
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
                        failures.Add(root.DisplayName + "\\" + entry.RelativePath.Replace('/', '\\') + "：" + ErrorMessage(error));
                        if (BatchPreflightFatal(error)) return;
                    }
                }
            }
            if (!Current()) return;
            int addable = previews.Count + treeDrafts.Count;
            var panel = new StackPanel { Spacing = 12, MinWidth = 320, MaxWidth = 480 };
            panel.Children.Add(new TextBlock { Text = $"可添加 {addable} 个文件 · {DownloadPresentationPolicy.FormatBytes(total)}", FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
            panel.Children.Add(new TextBlock { Text = "按顺序下载到已生效的默认目录；同名文件保留并报告冲突。" + (treeDrafts.Count > 0 ? " 目录内容会保留相对结构，非法名称已映射。" : ""), TextWrapping = TextWrapping.Wrap, FontSize = 12 });
            if (truncated) panel.Children.Add(new TextBlock { Text = "枚举达到上限，仅包含前列条目；请选择更具体的子目录分批下载。", TextWrapping = TextWrapping.Wrap, FontSize = 12 });
            if (previews.Count + treeDrafts.Count + failures.Count < files.Length + treeEntries.Count)
                panel.Children.Add(new TextBlock { Text = "另有部分条目未完成预检，本次不会添加。", TextWrapping = TextWrapping.Wrap, FontSize = 12 });
            foreach (var preview in previews.Take(12))
                panel.Children.Add(new TextBlock { Text = preview.Name + " · " + DownloadPresentationPolicy.FormatBytes(preview.TotalBytes) + "\n" + preview.DestinationDisplayPath, TextWrapping = TextWrapping.Wrap, FontSize = 12 });
            foreach (var preview in treeDrafts.Take(24))
                panel.Children.Add(new TextBlock { Text = preview.Name + " · " + DownloadPresentationPolicy.FormatBytes(preview.TotalBytes) + "\n" + preview.DestinationDisplayPath, TextWrapping = TextWrapping.Wrap, FontSize = 12 });
            int hidden = Math.Max(0, previews.Count - 12) + Math.Max(0, treeDrafts.Count - 24);
            if (hidden > 0) panel.Children.Add(new TextBlock { Text = $"其余 {hidden} 个文件略。", FontSize = 12 });
            foreach (var failure in failures.Take(12))
                panel.Children.Add(new TextBlock { Text = failure, TextWrapping = TextWrapping.Wrap, FontSize = 12 });
            if (failures.Count > 12) panel.Children.Add(new TextBlock { Text = $"另有 {failures.Count - 12} 条失败/跳过项未列出。", FontSize = 12 });
            var confirm = new ContentDialog { Title = "确认批量下载", PrimaryButtonText = "添加到下载队列", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close,
                IsPrimaryButtonEnabled = addable > 0, Content = new ScrollViewer { Content = panel, MaxHeight = 400, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled } };
            _downloadDialog = confirm;
            ContentDialogResult choice;
            try { choice = await ShowBrowserDialogAsync(confirm); }
            finally { if (ReferenceEquals(_downloadDialog, confirm)) _downloadDialog = null; }
            if (choice != ContentDialogResult.Primary || !Current()) return;
            int added = 0, unconfirmed = 0;
            foreach (var preview in previews.Concat(treeDrafts))
            {
                // Confirmation freezes these drafts. Browsing another directory must not
                // discard the rest of an accepted batch; account/session/permission still fence it.
                if (!IsCurrentDownloadContext(context) || _session?.Permissions.Download != true) break;
                try
                {
                    var item = await service.SubmitAsync(preview, _lifetime.Token);
                    if (IsCurrentDownloadContext(context)) AcceptDownloadItem(item);
                    added++;
                }
                catch (Exception) { unconfirmed++; } // Never automatically replay an unconfirmed draft.
            }
            if (IsCurrentDownloadContext(context))
                SetNotice("批量下载", $"已添加 {added} 项。" + (unconfirmed > 0 ? $"{unconfirmed} 项添加结果未确认，请到传输中心核实，勿重复添加。" : "") +
                    (added + unconfirmed < addable ? "其余文件未继续添加。" : ""), unconfirmed > 0 ? InfoBarSeverity.Warning : InfoBarSeverity.Success);
        }
        catch (Exception error) { if (IsCurrentDownloadContext(context)) ShowDownloadError("批量下载未完成", error); }
        finally { if (view == _downloadViewGeneration) _downloadPreparing = false; if (!_disposed) UpdateDownloadContext(); }
    }

    private static bool BatchPreflightFatal(Exception error) => error is OperationCanceledException ||
        error is FilewayException { Error.Code: ErrorCode.NetworkUnavailable or ErrorCode.Timeout or ErrorCode.HostExited or
            ErrorCode.AuthenticationExpired or ErrorCode.CredentialsUnavailable };
}

using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App;

public sealed partial class MainPage
{
    private async Task DownloadBatchSelectionAsync()
    {
        if (!CanDownloadSelection || _downloads is not { } service || _session is not { } session ||
            _directory is not { } directory || ActiveDownloadContext is not { } context) return;
        var selection = SelectedRows().Select(row => (row.File.Resource, row.File.IsDirectory)).ToArray();
        var files = selection.Where(row => !row.IsDirectory).Select(row => row.Resource).ToArray();
        if (files.Length == 0) { SetNotice("请选择普通文件", "目录下载尚未接入，本次没有创建任务。", InfoBarSeverity.Informational); return; }
        if (files.Length > 100) { SetNotice("选择的文件过多", "每次最多添加 100 个文件，请分批选择。", InfoBarSeverity.Warning); return; }
        var generation = _generation; var view = _downloadViewGeneration;
        bool Current() => view == _downloadViewGeneration && IsCurrentDownloadContext(context) &&
            SameBrowser(session, directory, generation) && _session?.Permissions.Download == true &&
            SelectedRows().Select(row => (row.File.Resource, row.File.IsDirectory)).SequenceEqual(selection);
        var previews = new List<DownloadPreview>(); var failures = new List<string>();
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
                    if (_lifetime.IsCancellationRequested || error is FilewayException { Error.Code: ErrorCode.NetworkUnavailable or ErrorCode.Timeout or ErrorCode.HostExited or ErrorCode.AuthenticationExpired or ErrorCode.CredentialsUnavailable }) break;
                }
            }
            if (!Current()) return;
            var panel = new StackPanel { Spacing = 12, MinWidth = 320, MaxWidth = 480 };
            panel.Children.Add(new TextBlock { Text = $"可添加 {previews.Count} 个文件 · {DownloadPresentationPolicy.FormatBytes(total)}", FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
            panel.Children.Add(new TextBlock { Text = "按顺序下载到已生效的默认目录；同名文件保留并报告冲突。", TextWrapping = TextWrapping.Wrap, FontSize = 12 });
            if (selection.Length != files.Length) panel.Children.Add(new TextBlock { Text = $"跳过 {selection.Length - files.Length} 个目录，未递归下载。", TextWrapping = TextWrapping.Wrap });
            if (previews.Count + failures.Count < files.Length) panel.Children.Add(new TextBlock { Text = $"另有 {files.Length - previews.Count - failures.Count} 项未完成预检，本次不会添加。", TextWrapping = TextWrapping.Wrap });
            foreach (var preview in previews)
                panel.Children.Add(new TextBlock { Text = preview.Name + " · " + DownloadPresentationPolicy.FormatBytes(preview.TotalBytes) + "\n" + preview.DestinationDisplayPath, TextWrapping = TextWrapping.Wrap, FontSize = 12 });
            foreach (var failure in failures) panel.Children.Add(new TextBlock { Text = failure, TextWrapping = TextWrapping.Wrap, FontSize = 12 });
            var confirm = new ContentDialog { Title = "确认批量下载", PrimaryButtonText = "添加到下载队列", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close,
                IsPrimaryButtonEnabled = previews.Count > 0, Content = new ScrollViewer { Content = panel, MaxHeight = 400, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled } };
            _downloadDialog = confirm;
            ContentDialogResult choice;
            try { choice = await ShowBrowserDialogAsync(confirm); }
            finally { if (ReferenceEquals(_downloadDialog, confirm)) _downloadDialog = null; }
            if (choice != ContentDialogResult.Primary || !Current()) return;
            int added = 0, unconfirmed = 0;
            foreach (var preview in previews)
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
                    (added + unconfirmed < previews.Count ? "其余文件未继续添加。" : ""), unconfirmed > 0 ? InfoBarSeverity.Warning : InfoBarSeverity.Success);
        }
        catch (Exception error) { if (IsCurrentDownloadContext(context)) ShowDownloadError("批量下载未完成", error); }
        finally { if (view == _downloadViewGeneration) _downloadPreparing = false; if (!_disposed) UpdateDownloadContext(); }
    }
}

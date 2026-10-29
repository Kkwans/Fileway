using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using Windows.ApplicationModel.DataTransfer;

namespace Fileway.App;

public sealed partial class MainPage
{
    private FileRow[] _draggingRows = [];

    private void FileItems_DragItemsStarting(object sender, DragItemsStartingEventArgs args)
    {
        _draggingRows = args.Items.OfType<FileRow>().ToArray();
        if (_draggingRows.Length == 0 || _session?.Permissions is not { Rename: true, Create: true })
        {
            args.Cancel = true;
            _draggingRows = [];
            return;
        }
        args.Data.RequestedOperation = DataPackageOperation.Move;
        args.Data.SetText(string.Join('\n', _draggingRows.Select(row => row.Name)));
    }

    private void FileItems_DragItemsCompleted(ListViewBase sender, DragItemsCompletedEventArgs args) => _draggingRows = [];

    private void FileItems_DragOver(object sender, DragEventArgs args)
    {
        var target = DropTargetFolder(args.OriginalSource);
        var movable = target is not null && _draggingRows.Length > 0 &&
            !_draggingRows.Any(row => row.File.Resource == target.File.Resource) &&
            _session?.Permissions is { Rename: true, Create: true };
        args.AcceptedOperation = movable ? DataPackageOperation.Move : DataPackageOperation.None;
        if (movable)
        {
            args.DragUIOverride.Caption = $"移动到 {target!.Name}";
            args.DragUIOverride.IsCaptionVisible = true;
        }
    }

    private async void FileItems_Drop(object sender, DragEventArgs args)
    {
        var target = DropTargetFolder(args.OriginalSource);
        var rows = _draggingRows;
        _draggingRows = [];
        if (target is null || rows.Length == 0 || _files is not IFileManagementService management ||
            _session is not { } session || _directory is not { } directory) return;
        if (rows.Any(row => row.File.Resource == target.File.Resource)) return;
        var generation = _generation;
        try
        {
            var items = rows.Select(row => new FileTransferItem(row.File.Resource, FileConflictPolicy.Fail)).ToArray();
            var result = await management.SubmitTransferAsync(new FileTransferRequest(FileTransferAction.Move, target.File.Resource, items), _lifetime.Token);
            if (result.Outcome == ServerTaskSubmissionOutcome.Accepted && result.AcceptedTask is { } accepted)
            {
                _submittedTasks[session.Account] = accepted.Reference;
                if (_transferPolls.Add(accepted.Reference)) _ = PollTransferAsync(session, directory, generation, accepted.Reference);
                SetNotice("已提交移动", $"已提交 {rows.Length} 项到「{target.Name}」，完成后会自动刷新当前目录。", InfoBarSeverity.Informational);
            }
            else
            {
                SetNotice("移动未提交", result.Error?.Message ?? "服务器未确认移动任务。", InfoBarSeverity.Warning);
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (!_disposed) SetNotice("移动未完成", ErrorMessage(error), InfoBarSeverity.Warning);
        }
    }

    private static FileRow? DropTargetFolder(object? source)
    {
        for (var current = source as DependencyObject; current is not null; current = VisualTreeHelper.GetParent(current))
        {
            if (current is SelectorItem { Content: FileRow row } && row.File.IsDirectory) return row;
            if (current is ListViewBase) break;
        }
        return null;
    }
}

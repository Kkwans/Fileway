using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using Windows.ApplicationModel.DataTransfer;
using Windows.Foundation;

namespace Fileway.App;

public sealed partial class MainPage
{
    private const string FileMoveFormat = "Fileway.RemoteMove.v1";

    private sealed record FileMoveDrag(ListViewBase Owner, ConnectionSession Session, RemoteResourceRef Directory,
        long Generation, RemoteResourceRef[] Sources, string Marker);

    private FileMoveDrag? _fileMoveDrag;

    private static bool CanMoveFiles(PermissionSet permissions) => permissions is { Rename: true, Create: true };

    private void FileItems_DragItemsStarting(object sender, DragItemsStartingEventArgs args)
    {
        ClearFileMoveDrag();
        if (sender is not ListViewBase owner || !ReferenceEquals(owner, ActiveFiles) ||
            !CanWrite(CanMoveFiles) || FileManagement is null || _session is not { } session ||
            _directory is not { } directory)
        {
            args.Cancel = true;
            return;
        }

        var sources = args.Items.OfType<FileRow>().Select(row => row.File.Resource).ToArray();
        if (sources.Length == 0 || sources.Length != args.Items.Count ||
            sources.Any(resource => resource.Account != session.Account))
        {
            args.Cancel = true;
            return;
        }

        // The OS drag package carries only a gesture marker. Remote references remain in the initiating view.
        var marker = Guid.NewGuid().ToString("N");
        args.Data.SetData(FileMoveFormat, marker);
        args.Data.RequestedOperation = DataPackageOperation.Move;
        _fileMoveDrag = new FileMoveDrag(owner, session, directory, _generation, sources, marker);
    }

    private void FileItems_DragItemsCompleted(ListViewBase sender, DragItemsCompletedEventArgs args) => ClearFileMoveDrag();

    private void ClearFileMoveDrag()
    {
        _fileMoveDrag = null;
        ClearMoveDropTarget();
    }

    private void ClearMoveDropTarget() => MoveDropTarget.Visibility = Visibility.Collapsed;

    private bool IsFileMove(DragEventArgs args) =>
        _fileMoveDrag is not null && args.DataView?.Contains(FileMoveFormat) == true;

    private void FileItems_DragOver(object sender, DragEventArgs args)
    {
        if (IsFileMove(args)) MoveFilesDragOver(args);
    }

    private void MoveFilesDragOver(DragEventArgs args)
    {
        args.Handled = true;
        var target = FindMoveTarget(args, _fileMoveDrag!);
        args.AcceptedOperation = target is null ? DataPackageOperation.None : DataPackageOperation.Move;
        if (args.DragUIOverride is { } appearance)
        {
            appearance.IsContentVisible = false;
            appearance.Caption = target?.Content is FileRow row ? $"移动到 {row.Name}" : "无法移动到此处";
            appearance.IsCaptionVisible = true;
        }
        ShowMoveDropTarget(target);
    }

    private async void FileItems_Drop(object sender, DragEventArgs args)
    {
        if (IsFileMove(args)) await MoveFilesDropAsync(args);
    }

    private async Task MoveFilesDropAsync(DragEventArgs args)
    {
        args.Handled = true;
        var drag = _fileMoveDrag!;
        var target = FindMoveTarget(args, drag)?.Content as FileRow;
        var data = args.DataView;
        args.AcceptedOperation = DataPackageOperation.None;
        ClearFileMoveDrag();
        if (target is null || data is null || FileManagement is not { } management) return;

        // Complete the OLE gesture once its marker is checked. Server work continues independently.
        var deferral = args.GetDeferral();
        try
        {
            var marker = await data.GetDataAsync(FileMoveFormat) as string;
            if (marker != drag.Marker || !SameBrowser(drag.Session, drag.Directory, drag.Generation) ||
                !ReferenceEquals(drag.Owner, ActiveFiles) || !CanWrite(CanMoveFiles) ||
                !ReferenceEquals(FileManagement, management))
                return;
            args.AcceptedOperation = DataPackageOperation.Move;
            _fileWriteBusy = true;
        }
        catch (Exception) { return; }
        finally { deferral.Complete(); }

        UpdateBrowserCommands();
        try
        {
            var items = drag.Sources.Select(source => new FileTransferItem(source, FileConflictPolicy.Fail)).ToArray();
            var request = new FileTransferRequest(FileTransferAction.Move, target.File.Resource, items);
            var result = await management.SubmitTransferAsync(request, _lifetime.Token);
            RecordTransferSubmission(drag.Session, drag.Directory, drag.Generation, result);
            if (!SameBrowser(drag.Session, drag.Directory, drag.Generation)) return;

            if (result.Outcome == ServerTaskSubmissionOutcome.Accepted && result.AcceptedTask is not null)
                SetNotice($"已提交 {items.Length} 项移动", string.Empty, InfoBarSeverity.Informational);
            else if (result.Outcome == ServerTaskSubmissionOutcome.Rejected && result.Error is { Code: ErrorCode.Conflict } conflict)
                SetNotice("移动遇到冲突", conflict.Message + " 可用「移动到」选择同名处理方式。", InfoBarSeverity.Warning);
            else
                ShowTransferSubmissionFailure(result);
            UpdateOperationAction();
        }
        catch (Exception error)
        {
            PresentWriteException(drag.Session, drag.Directory, drag.Generation, "移动", error);
        }
        finally
        {
            _fileWriteBusy = false;
            UpdateBrowserCommands();
        }
    }

    private SelectorItem? FindMoveTarget(DragEventArgs args, FileMoveDrag drag)
    {
        if (!ReferenceEquals(drag.Owner, ActiveFiles) || !SameBrowser(drag.Session, drag.Directory, drag.Generation) ||
            !CanWrite(CanMoveFiles) || (args.AllowedOperations & DataPackageOperation.Move) == 0)
            return null;

        var target = FindDropItem(args.OriginalSource, drag.Owner);
        if (target is null)
        {
            var point = Shell.TransformToVisual(null).TransformPoint(args.GetPosition(Shell));
            var hit = VisualTreeHelper.FindElementsInHostCoordinates(point, drag.Owner).FirstOrDefault();
            target = FindDropItem(hit, drag.Owner);
        }
        if (target?.Content is not FileRow { File.IsDirectory: true } row ||
            drag.Sources.Any(source => source.WirePath == row.File.Resource.WirePath))
            return null;
        var viewport = MoveTargetViewport(target);
        var position = args.GetPosition(viewport);
        return position.X >= 0 && position.X < viewport.ActualWidth &&
            position.Y >= 0 && position.Y < viewport.ActualHeight ? target : null;
    }

    private FrameworkElement MoveTargetViewport(SelectorItem target)
    {
        var owner = ActiveFiles;
        for (var parent = VisualTreeHelper.GetParent(target);
             parent is not null && !ReferenceEquals(parent, owner); parent = VisualTreeHelper.GetParent(parent))
            if (parent is ScrollContentPresenter presenter) return presenter;
        return owner;
    }

    private static SelectorItem? FindDropItem(object? source, ListViewBase owner)
    {
        for (var item = source as DependencyObject; item is not null && !ReferenceEquals(item, owner);
             item = VisualTreeHelper.GetParent(item))
            if (item is SelectorItem container && ReferenceEquals(ItemsControl.ItemsControlFromItemContainer(container), owner) &&
                container.Content is FileRow row && ReferenceEquals(owner.ItemFromContainer(container), row))
                return container;
        return null;
    }

    private void ShowMoveDropTarget(SelectorItem? target)
    {
        if (target is null)
        {
            ClearMoveDropTarget();
            return;
        }

        var bounds = target.TransformToVisual(MoveDropTargetLayer)
            .TransformBounds(new Rect(0, 0, target.ActualWidth, target.ActualHeight));
        var viewport = MoveTargetViewport(target);
        var visible = viewport.TransformToVisual(MoveDropTargetLayer)
            .TransformBounds(new Rect(0, 0, viewport.ActualWidth, viewport.ActualHeight));
        var left = Math.Max(visible.X, bounds.X);
        var top = Math.Max(visible.Y, bounds.Y);
        var width = Math.Min(visible.Right, bounds.Right) - left;
        var height = Math.Min(visible.Bottom, bounds.Bottom) - top;
        if (width <= 0 || height <= 0)
        {
            ClearMoveDropTarget();
            return;
        }
        MoveDropTarget.Width = width;
        MoveDropTarget.Height = height;
        Canvas.SetLeft(MoveDropTarget, left);
        Canvas.SetTop(MoveDropTarget, top);
        MoveDropTarget.Visibility = Visibility.Visible;
    }
}

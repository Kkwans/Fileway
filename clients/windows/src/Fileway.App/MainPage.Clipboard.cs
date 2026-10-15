using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Windows.ApplicationModel.DataTransfer;
using Windows.System;

namespace Fileway.App;

public sealed partial class MainPage
{
    private const string RemoteClipboardFormat = "Fileway.RemoteSelection.v1";
    private readonly RemoteClipboardState _remoteClipboard = new();
    private bool _readingClipboard;
    private bool ClipboardCommandsAvailable => BrowserReady && !_fileWriteBusy && !_readingClipboard &&
        _browserDialog is null && _storageDialog is null && !LoadingRing.IsActive && FileManagement is not null;
    private bool CanCaptureClipboard(FileTransferAction action) => ClipboardCommandsAvailable && SelectedRows().Length is > 0 and <= 500 &&
        _session!.Permissions.Create && (action != FileTransferAction.Move || _session.Permissions.Rename);
    private bool CanPasteClipboard => ClipboardCommandsAvailable && !HasUnresolvedWrite && _remoteClipboard.Current is { } current &&
        _remoteClipboard.IsCurrent(current, _session) && _session!.Permissions.Create &&
        (current.Action != FileTransferAction.Move || _session.Permissions.Rename);

    private void CaptureClipboard(FileTransferAction action)
    {
        if (!CanCaptureClipboard(action) || _session is not { } session) return;
        try
        {
            var snapshot = _remoteClipboard.Capture(session, action, SelectedRows().Select(row => row.File.Resource));
            var package = new DataPackage { RequestedOperation = action == FileTransferAction.Move ? DataPackageOperation.Move : DataPackageOperation.Copy };
            package.SetData(RemoteClipboardFormat, snapshot.Marker);
            if (!Clipboard.SetContentWithOptions(package, new ClipboardContentOptions { IsAllowedInHistory = false, IsRoamable = false }))
                throw new InvalidOperationException("clipboard_busy");
            SetNotice(action == FileTransferAction.Move ? "已剪切" : "已复制", $"{snapshot.Sources.Count} 个项目。打开目标文件夹后粘贴；任务提交前会确认冲突策略。", InfoBarSeverity.Informational);
        }
        catch (Exception) { _remoteClipboard.Clear(); SetNotice("剪贴板暂不可用", "请稍后重新选择项目并重试。", InfoBarSeverity.Warning); }
        UpdateApplicationMenus();
    }
    private async Task PasteClipboardAsync()
    {
        if (!CanPasteClipboard || _session is not { } session || _directory is not { } directory) return;
        var generation = _generation;
        RemoteClipboardSnapshot? snapshot = null;
        _readingClipboard = true; UpdateApplicationMenus();
        try
        {
            var content = Clipboard.GetContent();
            if (content.Contains(RemoteClipboardFormat))
            {
                var marker = await content.GetDataAsync(RemoteClipboardFormat).AsTask(_lifetime.Token)
                    .WaitAsync(TimeSpan.FromSeconds(3), _lifetime.Token);
                if (SameBrowser(session, directory, generation)) snapshot = _remoteClipboard.Resolve(marker as string, _session);
            }
        }
        catch (Exception) { if (SameBrowser(session, directory, generation)) SetNotice("无法读取剪贴板", "请重新复制或剪切文件后重试。", InfoBarSeverity.Warning); }
        finally { _readingClipboard = false; UpdateApplicationMenus(); }
        if (!SameBrowser(session, directory, generation)) return;
        if (snapshot is null)
        {
            _remoteClipboard.Clear();
            SetNotice("没有可粘贴的文件", "请在当前连接中重新复制或剪切文件。", InfoBarSeverity.Informational);
            UpdateApplicationMenus(); return;
        }
        await TransferAsync(snapshot.Action, snapshot);
    }
    private void CopyClipboard_Click(object sender, RoutedEventArgs args) => CaptureClipboard(FileTransferAction.Copy);
    private void CutClipboard_Click(object sender, RoutedEventArgs args) => CaptureClipboard(FileTransferAction.Move);
    private async void PasteClipboard_Click(object sender, RoutedEventArgs args) => await PasteClipboardAsync();
    private void AddClipboardAccelerators(UIElement owner)
    {
        foreach (var key in new[] { VirtualKey.C, VirtualKey.X, VirtualKey.V })
        {
            var accelerator = new KeyboardAccelerator { Key = key, Modifiers = VirtualKeyModifiers.Control };
            accelerator.Invoked += async (_, args) =>
            {
                // Text editors, path fields and modal confirmations retain their native clipboard.
                if (args.Handled || XamlRoot is null || !ClipboardCommandsAvailable || IsTextInput(FocusManager.GetFocusedElement(XamlRoot) as DependencyObject)) return;
                args.Handled = true;
                if (key == VirtualKey.V) await PasteClipboardAsync();
                else CaptureClipboard(key == VirtualKey.X ? FileTransferAction.Move : FileTransferAction.Copy);
            };
            owner.KeyboardAccelerators.Add(accelerator);
        }
    }
}

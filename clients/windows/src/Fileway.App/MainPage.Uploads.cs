using Fileway.Core.Contracts;
using static Fileway.App.UploadPresentationPolicy;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Markup;
using Microsoft.Windows.Storage.Pickers;

namespace Fileway.App;

public sealed partial class MainPage
{
    private IUploadService? _uploads;
    private Microsoft.UI.WindowId _uploadOwner;
    private UploadContext? _uploadContext;
    private readonly Dictionary<TransferTaskRef, UploadItem> _uploadItems = [];
    private readonly Dictionary<TransferTaskRef, UploadOrigin> _uploadOrigins = [];
    private readonly HashSet<TransferTaskRef> _uploadRefreshes = [];
    private UploadItem? _latestUpload;
    private ContentDialog? _uploadDialog;
    private UploadDetailsView? _uploadDetails;
    private TransferTaskRef? _uploadDetailTask;
    private bool _uploadPickerOpen;
    private bool _uploadCommandBusy;
    private long _uploadViewGeneration;
    private string? _uploadErrorTitle;
    private string? _uploadErrorMessage;

    public void SetUploadService(IUploadService uploads, Microsoft.UI.WindowId owner)
    {
        if (_disposed) return;
        if (_uploads is not null) _uploads.TaskChanged -= Upload_TaskChanged;
        ClearUploadPresentation();
        _uploads = uploads; _uploadOwner = owner;
        _uploads.TaskChanged += Upload_TaskChanged;
        UpdateUploadContext();
    }

    private UploadContext? ActiveUploadContext => !_disposed && !_connecting && _session is { } session
        && _uploads?.CurrentContext is { } context && context.Account == session.Account && context.SessionId == session.Id
        ? context : null;
    private bool IsCurrentUploadContext(UploadContext context) => ActiveUploadContext == context;
    private bool CanSubmitUpload => _uploads is not null && _uploadOwner.Value != 0 && ActiveUploadContext is not null
        && CanWrite(p => p.Create) && !_uploadPickerOpen && !_uploadCommandBusy;

    private void UpdateUploadContext()
    {
        if (UploadFileButton is null) return;
        var current = ActiveUploadContext;
        if (_uploadContext != current)
        {
            ClearUploadPresentation();
            _uploadContext = current;
        }
        UploadFileButton.IsEnabled = CanSubmitUpload;
        UploadFileButton.Visibility = _session?.Permissions.Create == false ? Visibility.Collapsed : Visibility.Visible;
        UploadHistoryButton.IsEnabled = current is not null && !_uploadCommandBusy;
        RenderUploadNotice();
        RenderUploadDetails();
    }

    private void ClearUploadPresentation()
    {
        ++_uploadViewGeneration;
        _uploadDialog?.Hide();
        _uploadDialog = null; _uploadDetails = null; _uploadDetailTask = null;
        _uploadContext = null; _latestUpload = null;
        _uploadErrorTitle = _uploadErrorMessage = null;
        _uploadItems.Clear(); _uploadOrigins.Clear(); _uploadRefreshes.Clear();
        if (UploadNotice is not null) UploadNotice.IsOpen = false;
    }

    private void DisposeUploadPresentation()
    {
        if (_uploads is not null) _uploads.TaskChanged -= Upload_TaskChanged;
        ClearUploadPresentation();
    }

    private async void UploadFile_Click(object sender, RoutedEventArgs args)
    {
        if (!CanSubmitUpload || _uploads is not { } service || _session is not { } session
            || _directory is not { } parent || ActiveUploadContext is not { } context) return;
        var generation = _generation;
        var viewGeneration = _uploadViewGeneration;
        bool CurrentDestination() => viewGeneration == _uploadViewGeneration && IsCurrentUploadContext(context)
            && SameBrowser(session, parent, generation) && _session?.Permissions.Create == true && !HasUnresolvedWrite;
        _uploadPickerOpen = true; UpdateUploadContext();
        try
        {
            var picker = new FileOpenPicker(_uploadOwner)
            {
                Title = "选择要上传的文件", CommitButtonText = "选择文件",
                SuggestedStartLocation = PickerLocationId.DocumentsLibrary, ViewMode = PickerViewMode.List,
            };
            picker.FileTypeFilter.Add("*");
            var file = await picker.PickSingleFileAsync().AsTask(_lifetime.Token);
            if (file is null || !CurrentDestination()) return;
            var name = Path.GetFileName(file.Path);
            var content = new StackPanel { Spacing = 12, MinWidth = 320, MaxWidth = 440 };
            content.Children.Add(new TextBlock { Text = name, FontSize = 18, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap });
            content.Children.Add(new TextBlock { Text = $"上传到：{parent.DisplayPath}", TextWrapping = TextWrapping.Wrap });
            content.Children.Add(new TextBlock { Text = "仅上传这个文件。遇到同名项目会停止并报告冲突，不覆盖现有文件。", TextWrapping = TextWrapping.Wrap, FontSize = 12 });
            var confirm = new ContentDialog { Title = "确认上传目标", Content = content, PrimaryButtonText = "开始上传", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close };
            _uploadDialog = confirm;
            ContentDialogResult confirmed;
            try { confirmed = await ShowBrowserDialogAsync(confirm); }
            finally { if (ReferenceEquals(_uploadDialog, confirm)) _uploadDialog = null; }
            if (confirmed != ContentDialogResult.Primary || !CurrentDestination()) return;
            _uploadErrorTitle = _uploadErrorMessage = null;
            _uploadCommandBusy = true; UpdateUploadContext();
            var item = await service.SubmitAsync(new UploadSubmission(Guid.NewGuid(), context, parent, file.Path), _lifetime.Token);
            if (!IsCurrentUploadContext(context)) return;
            _uploadOrigins[item.Snapshot.Task] = new UploadOrigin(session, parent, generation);
            AcceptUploadItem(item);
            _uploadCommandBusy = false; UpdateUploadContext();
            await ShowUploadDetailsAsync(item.Snapshot.Task);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (CurrentDestination()) ShowUploadError("上传未能提交", error);
        }
        finally
        {
            _uploadPickerOpen = false; _uploadCommandBusy = false;
            if (!_disposed) UpdateUploadContext();
        }
    }

    private void Upload_TaskChanged(object? sender, UploadItem item) => DispatcherQueue.TryEnqueue(() =>
    {
        if (!ReferenceEquals(sender, _uploads) || !IsCurrentUploadContext(item.Context)) return;
        UpdateUploadContext();
        AcceptUploadItem(item);
    });

    private void AcceptUploadItem(UploadItem item)
    {
        if (!IsCurrentUploadContext(item.Context) || item.Snapshot.Task.Account != item.Context.Account) return;
        if (_uploadItems.TryGetValue(item.Snapshot.Task, out var previous)
            && ReferenceEquals(PreferUploadItem(previous, item), previous))
        {
            // Submission may register its origin after a newer completion event was already displayed.
            TryRefreshCompletedUpload(previous);
            return;
        }
        _uploadErrorTitle = _uploadErrorMessage = null;
        _uploadItems[item.Snapshot.Task] = item; _latestUpload = item;
        RenderUploadNotice(); RenderUploadDetails();
        TryRefreshCompletedUpload(item);
    }

    private void TryRefreshCompletedUpload(UploadItem item)
    {
        if (item.Status == UploadStatus.Completed && item.Snapshot.RemotePublished
            && _uploadOrigins.TryGetValue(item.Snapshot.Task, out var origin)
            && SameBrowser(origin.Session, origin.Parent, origin.Generation)
            && _uploadRefreshes.Add(item.Snapshot.Task))
            _ = RefreshCompletedUploadAsync(item, origin);
    }

    private async Task RefreshCompletedUploadAsync(UploadItem item, UploadOrigin origin)
    {
        try
        {
            if (IsCurrentUploadContext(item.Context) && SameBrowser(origin.Session, origin.Parent, origin.Generation))
                await LoadAsync(origin.Parent);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (IsCurrentUploadContext(item.Context)) ShowUploadError("上传已完成，目录刷新失败", error);
        }
    }

    private void RenderUploadNotice()
    {
        if (UploadNotice is null) return;
        if (_uploadErrorMessage is { } message && ActiveUploadContext is not null)
        {
            UploadNotice.IsOpen = true; UploadNotice.Title = _uploadErrorTitle; UploadNotice.Message = message;
            UploadNotice.Severity = InfoBarSeverity.Warning; UploadDetailsButton.IsEnabled = _latestUpload is not null && !_uploadCommandBusy;
            return;
        }
        if (_latestUpload is not { } item || !IsCurrentUploadContext(item.Context)) { UploadNotice.IsOpen = false; return; }
        UploadNotice.IsOpen = true;
        UploadNotice.Title = UploadItemStatusLabel(item);
        UploadNotice.Message = $"{item.Name} · {ConfirmedBytesLabel(item)}";
        UploadNotice.Severity = Tone(item) switch
        {
            UploadPresentationTone.Success => InfoBarSeverity.Success,
            UploadPresentationTone.Warning => InfoBarSeverity.Warning,
            _ => InfoBarSeverity.Informational,
        };
        UploadDetailsButton.IsEnabled = !_uploadCommandBusy;
    }

    private async void UploadDetails_Click(object sender, RoutedEventArgs args)
    {
        if (_latestUpload is { } item) await ShowUploadDetailsAsync(item.Snapshot.Task);
    }

    private async Task ShowUploadDetailsAsync(TransferTaskRef task)
    {
        if (_browserDialog is not null || _uploadDialog is not null || !_uploadItems.TryGetValue(task, out var item)
            || !IsCurrentUploadContext(item.Context)) return;
        UploadDetailsView view;
        try { view = new UploadDetailsView(); }
        catch (Exception error) { ShowUploadError("上传详情未能打开", error); return; }
        var dialog = new ContentDialog { Title = "文件上传", Content = view.Content, CloseButtonText = "关闭", DefaultButton = ContentDialogButton.Close };
        _uploadDialog = dialog; _uploadDetails = view; _uploadDetailTask = task;
        view.Cancel.Click += (_, _) => { view.CancelConfirmation.Visibility = Visibility.Visible; view.Cancel.IsEnabled = false; };
        view.Keep.Click += (_, _) => { view.CancelConfirmation.Visibility = Visibility.Collapsed; RenderUploadDetails(); };
        view.ConfirmCancel.Click += async (_, _) => await RunUploadCommandAsync(task, UploadCommand.Cancel);
        dialog.PrimaryButtonClick += async (_, click) =>
        {
            click.Cancel = true;
            var deferral = click.GetDeferral();
            try
            {
                if (_uploadItems.TryGetValue(task, out var current) && PrimaryUploadCommand(current) is { } command)
                    await RunUploadCommandAsync(task, command);
            }
            finally { deferral.Complete(); }
        };
        RenderUploadDetails();
        try { await ShowBrowserDialogAsync(dialog); }
        finally
        {
            if (ReferenceEquals(_uploadDialog, dialog)) { _uploadDialog = null; _uploadDetails = null; _uploadDetailTask = null; }
        }
    }

    private void RenderUploadDetails()
    {
        if (_uploadDialog is not { } dialog || _uploadDetails is not { } view || _uploadDetailTask is not { } task
            || !_uploadItems.TryGetValue(task, out var item) || !IsCurrentUploadContext(item.Context)) return;
        view.Name.Text = item.Name;
        view.Destination.Text = $"目标：{item.DestinationDisplayPath}";
        view.Stage.Text = UploadItemStatusLabel(item);
        var progress = Progress(item);
        view.Confirmed.Text = progress.ConfirmedText;
        view.Progress.IsIndeterminate = progress.IsIndeterminate;
        view.Progress.Value = progress.Value;
        view.Detail.Text = _uploadErrorMessage ?? UploadDetailLabel(item);
        dialog.PrimaryButtonText = PrimaryButtonLabel(item);
        dialog.IsPrimaryButtonEnabled = !_uploadCommandBusy && CanUsePrimary(item, _session?.Permissions.Create == true);
        view.Cancel.Visibility = CanCancelUpload(item.Status) ? Visibility.Visible : Visibility.Collapsed;
        view.Cancel.IsEnabled = CanCancelUpload(item.Status) && !_uploadCommandBusy && view.CancelConfirmation.Visibility != Visibility.Visible;
        view.ConfirmCancel.IsEnabled = CanCancelUpload(item.Status) && !_uploadCommandBusy;
        if (!CanCancelUpload(item.Status)) view.CancelConfirmation.Visibility = Visibility.Collapsed;
    }

    private async Task RunUploadCommandAsync(TransferTaskRef task, UploadCommand command)
    {
        if (_uploadCommandBusy || _uploads is not { } service || !_uploadItems.TryGetValue(task, out var item)
            || !IsCurrentUploadContext(item.Context)) return;
        if (command == UploadCommand.Cancel ? !CanCancelUpload(item.Status) : PrimaryUploadCommand(item) != command) return;
        var context = item.Context;
        _uploadErrorTitle = _uploadErrorMessage = null;
        _uploadCommandBusy = true; UpdateUploadContext();
        try
        {
            if ((command is UploadCommand.Resume or UploadCommand.Reconcile) && _session is { } session && _directory is { } directory
                && directory.Account == item.Parent.Account && string.Equals(directory.WirePath, item.Parent.WirePath, StringComparison.Ordinal))
                _uploadOrigins[task] = new UploadOrigin(session, directory, _generation);
            var changed = command switch
            {
                UploadCommand.Pause => await service.PauseAsync(context, task, _lifetime.Token),
                UploadCommand.Cancel => await service.CancelAsync(context, task, _lifetime.Token),
                _ => await service.ResumeAsync(context, task, _lifetime.Token),
            };
            AcceptUploadItem(changed);
            if (_uploadDetails is { } view) view.CancelConfirmation.Visibility = Visibility.Collapsed;
        }
        catch (OperationCanceledException) { }
        catch (Exception error) { if (IsCurrentUploadContext(context)) ShowUploadError("上传操作未能完成", error); }
        finally { _uploadCommandBusy = false; if (!_disposed) UpdateUploadContext(); }
    }

    private async void UploadHistory_Click(object sender, RoutedEventArgs args)
    {
        if (_uploads is not { } service || ActiveUploadContext is not { } context || _browserDialog is not null || _uploadDialog is not null) return;
        var viewGeneration = _uploadViewGeneration;
        bool Current() => viewGeneration == _uploadViewGeneration && IsCurrentUploadContext(context);
        var entries = new StackPanel { Spacing = 8 };
        var hint = new TextBlock { Text = "只显示当前账号的上传记录。打开记录不会开始上传；选择一项后可手动恢复或核实。", TextWrapping = TextWrapping.Wrap, FontSize = 12 };
        var more = new Button { Content = "加载更多", HorizontalAlignment = HorizontalAlignment.Left, Visibility = Visibility.Collapsed };
        var content = new StackPanel { Spacing = 12, MinWidth = 320, MaxWidth = 480 };
        content.Children.Add(hint); content.Children.Add(new ScrollViewer { Content = entries, MaxHeight = 340, VerticalScrollBarVisibility = ScrollBarVisibility.Auto }); content.Children.Add(more);
        var dialog = new ContentDialog { Title = "上传记录", Content = content, CloseButtonText = "关闭", DefaultButton = ContentDialogButton.Close };
        _uploadDialog = dialog;
        TransferTaskRef? selected = null;
        string? cursor = null;
        var seenCursors = new HashSet<string>(StringComparer.Ordinal);
        var seenTasks = new HashSet<TransferTaskRef>();
        var loading = false;
        async Task LoadPageAsync()
        {
            if (loading || !Current()) return;
            loading = true; more.IsEnabled = false;
            try
            {
                var page = await service.ListAsync(context, cursor, 30, _lifetime.Token);
                if (!Current() || !ReferenceEquals(_uploadDialog, dialog)) return;
                foreach (var entry in page.Items)
                {
                    if (entry.Context != context || entry.Snapshot.Task.Account != context.Account) continue;
                    if (!seenTasks.Add(entry.Snapshot.Task)) continue;
                    var visible = entry;
                    if (_uploadItems.TryGetValue(entry.Snapshot.Task, out var known)) visible = PreferUploadItem(known, entry);
                    _uploadItems[entry.Snapshot.Task] = visible;
                    var text = new StackPanel { Spacing = 4 };
                    text.Children.Add(new TextBlock { Text = visible.Name, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis, MaxWidth = 420 });
                    text.Children.Add(new TextBlock { Text = $"{UploadItemStatusLabel(visible)} · {ConfirmedBytesLabel(visible)}", FontSize = 12, TextWrapping = TextWrapping.Wrap });
                    text.Children.Add(new TextBlock { Text = visible.DestinationDisplayPath, FontSize = 12, TextTrimming = TextTrimming.CharacterEllipsis, MaxWidth = 420 });
                    var button = new Button { Content = text, HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Left, Padding = new Thickness(12) };
                    button.Click += (_, _) => { if (Current()) { selected = entry.Snapshot.Task; dialog.Hide(); } };
                    entries.Children.Add(button);
                }
                cursor = page.NextCursor;
                if (cursor is not null && !seenCursors.Add(cursor)) throw new FilewayException(new ErrorInfo(ErrorCode.ProtocolMismatch, "上传记录返回了重复的分页标记。"));
                more.Visibility = cursor is null ? Visibility.Collapsed : Visibility.Visible;
                if (entries.Children.Count == 0) hint.Text = "当前账号还没有上传记录。";
            }
            catch (OperationCanceledException) { }
            catch (Exception error) { if (Current()) { hint.Text = "上传记录未能加载。" + ErrorMessage(error); more.Content = "重试"; more.Visibility = Visibility.Visible; } }
            finally { loading = false; more.IsEnabled = true; }
        }
        more.Click += async (_, _) => await LoadPageAsync();
        dialog.Opened += async (_, _) => await LoadPageAsync();
        try { await ShowBrowserDialogAsync(dialog); }
        finally { if (ReferenceEquals(_uploadDialog, dialog)) _uploadDialog = null; }
        if (Current() && selected is { } task) await ShowUploadDetailsAsync(task);
    }

    private void ShowUploadError(string title, Exception error)
    {
        _uploadErrorTitle = title; _uploadErrorMessage = ErrorMessage(error);
        RenderUploadNotice(); RenderUploadDetails();
    }

    private sealed record UploadOrigin(ConnectionSession Session, RemoteResourceRef Parent, long Generation);

    // Theme resources are resolved by WinUI; the view never controls execution on dismissal.
    private sealed class UploadDetailsView
    {
        public StackPanel Content { get; } = (StackPanel)XamlReader.Load("""
            <StackPanel xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml" Spacing="16" MinWidth="320" MaxWidth="440">
              <StackPanel Spacing="4"><TextBlock x:Name="Name" FontSize="18" FontWeight="SemiBold" TextWrapping="Wrap"/><TextBlock x:Name="Destination" FontSize="12" TextWrapping="Wrap" Foreground="{ThemeResource TextFillColorSecondaryBrush}"/></StackPanel>
              <Border Padding="16" CornerRadius="8" Background="{ThemeResource LayerFillColorDefaultBrush}" BorderBrush="{ThemeResource CardStrokeColorDefaultBrush}" BorderThickness="1"><StackPanel Spacing="8"><TextBlock x:Name="Stage" FontWeight="SemiBold" TextWrapping="Wrap"/><ProgressBar x:Name="Progress" Minimum="0" Maximum="100" Height="4"/><TextBlock x:Name="Confirmed" FontSize="12" TextWrapping="Wrap" Foreground="{ThemeResource TextFillColorSecondaryBrush}"/></StackPanel></Border>
              <TextBlock x:Name="Detail" FontSize="12" TextWrapping="Wrap" Foreground="{ThemeResource TextFillColorSecondaryBrush}"/>
              <Button x:Name="Cancel" Content="取消上传…" HorizontalAlignment="Left"/>
              <Border x:Name="CancelConfirmation" Visibility="Collapsed" Padding="12" CornerRadius="8" BorderThickness="1" BorderBrush="{ThemeResource ControlStrokeColorDefaultBrush}"><StackPanel Spacing="10"><TextBlock Text="确认取消这个上传？已传输的数据需要由服务器核实并清理；取消请求不代表远端已取消。" TextWrapping="Wrap"/><StackPanel Orientation="Horizontal" Spacing="8"><Button x:Name="Keep" Content="继续上传"/><Button x:Name="ConfirmCancel" Content="确认取消"/></StackPanel></StackPanel></Border>
            </StackPanel>
            """);
        public TextBlock Name => (TextBlock)Content.FindName("Name");
        public TextBlock Destination => (TextBlock)Content.FindName("Destination");
        public TextBlock Stage => (TextBlock)Content.FindName("Stage");
        public ProgressBar Progress => (ProgressBar)Content.FindName("Progress");
        public TextBlock Confirmed => (TextBlock)Content.FindName("Confirmed");
        public TextBlock Detail => (TextBlock)Content.FindName("Detail");
        public Button Cancel => (Button)Content.FindName("Cancel");
        public Border CancelConfirmation => (Border)Content.FindName("CancelConfirmation");
        public Button Keep => (Button)Content.FindName("Keep");
        public Button ConfirmCancel => (Button)Content.FindName("ConfirmCancel");
    }
}

using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Markup;
using DownloadPolicy = Fileway.App.DownloadPresentationPolicy;

namespace Fileway.App;

public sealed partial class MainPage
{
    private IDownloadService? _downloads;
    private DownloadContext? _downloadContext;
    private readonly Dictionary<TransferTaskRef, DownloadItem> _downloadItems = [];
    private DownloadItem? _latestDownload;
    private ContentDialog? _downloadDialog;
    private DownloadDetailsView? _downloadDetails;
    private TransferTaskRef? _downloadDetailTask;
    private bool _downloadOperationBusy;
    private bool _downloadPreparing;
    private long _downloadViewGeneration;
    private string? _downloadErrorTitle;
    private string? _downloadErrorMessage;

    public void SetDownloadService(IDownloadService downloads)
    {
        if (_disposed) return;
        if (_downloads is not null) _downloads.TaskChanged -= Download_TaskChanged;
        ClearDownloadPresentation(); _downloads = downloads;
        _downloads.TaskChanged += Download_TaskChanged;
        UpdateDownloadContext();
    }

    private DownloadContext? ActiveDownloadContext => !_disposed && !_connecting && _session is { } session
        && _downloads?.CurrentContext is { } context && context.Account == session.Account && context.SessionId == session.Id ? context : null;
    private bool IsCurrentDownloadContext(DownloadContext context) => ActiveDownloadContext == context;
    private bool CanDownloadSelection
    {
        get
        {
            if (_disposed || _downloads is null) return false;
            var selected = SelectedRows();
            return DownloadPolicy.CanPrepare(_session?.Permissions, ActiveDownloadContext is not null, BrowserReady,
                LoadingRing.IsActive, _downloadOperationBusy || _downloadItems.Values.Any(item => DownloadPolicy.IsActive(DownloadPolicy.EffectiveStatus(item))),
                selected.Length, selected.Length != 1 || selected[0].File.IsDirectory);
        }
    }

    private void UpdateDownloadContext()
    {
        if (DownloadFileButton is null) return;
        var current = ActiveDownloadContext;
        if (_downloadContext != current) { ClearDownloadPresentation(); _downloadContext = current; }
        DownloadFileButton.IsEnabled = CanDownloadSelection;
        DownloadFileButton.Visibility = _session?.Permissions.Download == false ? Visibility.Collapsed : Visibility.Visible;
        DownloadHistoryButton.IsEnabled = current is not null && !_downloadOperationBusy;
        RenderDownloadNotice(); RenderDownloadDetails();
    }

    private void ClearDownloadPresentation()
    {
        ++_downloadViewGeneration;
        _downloadDialog?.Hide(); _downloadDialog = null; _downloadDetails = null; _downloadDetailTask = null;
        _downloadContext = null; _latestDownload = null; _downloadItems.Clear(); _downloadPreparing = false;
        _downloadErrorTitle = _downloadErrorMessage = null;
        if (DownloadNotice is not null) DownloadNotice.IsOpen = false;
    }

    private void DisposeDownloadPresentation()
    {
        if (_downloads is not null) _downloads.TaskChanged -= Download_TaskChanged;
        ClearDownloadPresentation();
    }

    private async void DownloadFile_Click(object sender, RoutedEventArgs args)
    {
        if (!CanDownloadSelection || _downloads is not { } service || _session is not { } session
            || _directory is not { } directory || ActiveDownloadContext is not { } context) return;
        var source = SelectedRows()[0].File.Resource;
        var generation = _generation; var viewGeneration = _downloadViewGeneration;
        var submitted = false;
        bool CurrentSource()
        {
            if (viewGeneration != _downloadViewGeneration || !IsCurrentDownloadContext(context)
                || !SameBrowser(session, directory, generation) || _session?.Permissions.Download != true) return false;
            var selected = SelectedRows();
            return selected.Length == 1 && !selected[0].File.IsDirectory && selected[0].File.Resource == source;
        }
        _downloadErrorTitle = _downloadErrorMessage = null;
        _downloadOperationBusy = true; _downloadPreparing = true; UpdateDownloadContext();
        try
        {
            var preview = await service.PrepareAsync(context, source, _lifetime.Token);
            _downloadPreparing = false; UpdateDownloadContext();
            if (!CurrentSource()) return;
            if (preview.Context != context || preview.Source.Account != source.Account
                || !string.Equals(preview.Source.WirePath, source.WirePath, StringComparison.Ordinal))
                throw new FilewayException(new ErrorInfo(ErrorCode.ProtocolMismatch, "下载确认的源文件与原选择不一致。"));
            var content = new StackPanel { Spacing = 12, MinWidth = 320, MaxWidth = 440 };
            content.Children.Add(new TextBlock { Text = preview.Name, FontSize = 18, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap });
            content.Children.Add(new TextBlock { Text = $"大小：{DownloadPolicy.FormatBytes(preview.TotalBytes)}", TextWrapping = TextWrapping.Wrap });
            content.Children.Add(new TextBlock { Text = $"保存到：{preview.DestinationDisplayPath}", TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true });
            content.Children.Add(new TextBlock { Text = "使用已生效的默认下载目录。仅下载这个文件；同名文件会保留并报告冲突。", FontSize = 12, TextWrapping = TextWrapping.Wrap });
            var confirm = new ContentDialog { Title = "确认下载位置", Content = new ScrollViewer { Content = content, MaxHeight = 400, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled }, PrimaryButtonText = "开始下载", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close };
            _downloadDialog = confirm;
            ContentDialogResult result;
            try { result = await ShowBrowserDialogAsync(confirm); }
            finally { if (ReferenceEquals(_downloadDialog, confirm)) _downloadDialog = null; }
            if (result != ContentDialogResult.Primary || !CurrentSource()) return;
            submitted = true;
            var item = await service.SubmitAsync(preview, _lifetime.Token);
            if (!IsCurrentDownloadContext(context)) return;
            if (item.Snapshot.Task.Id != preview.Draft.Id)
                throw new FilewayException(new ErrorInfo(ErrorCode.ProtocolMismatch, "下载任务与确认记录不一致。"));
            AcceptDownloadItem(item);
            _downloadOperationBusy = false; UpdateDownloadContext();
            await ShowDownloadDetailsAsync(item.Snapshot.Task);
        }
        catch (OperationCanceledException) { }
        catch (Exception error) { if (submitted ? IsCurrentDownloadContext(context) : CurrentSource()) ShowDownloadError("下载未能提交", error); }
        finally { _downloadPreparing = false; _downloadOperationBusy = false; if (!_disposed) UpdateDownloadContext(); }
    }

    private void Download_TaskChanged(object? sender, DownloadItem item) => DispatcherQueue.TryEnqueue(() =>
    {
        if (!ReferenceEquals(sender, _downloads) || !IsCurrentDownloadContext(item.Context)) return;
        UpdateDownloadContext(); AcceptDownloadItem(item);
    });

    private void AcceptDownloadItem(DownloadItem item)
    {
        if (!IsCurrentDownloadContext(item.Context) || item.Snapshot.Task.Account != item.Context.Account) return;
        if (_downloadItems.TryGetValue(item.Snapshot.Task, out var previous)
            && ReferenceEquals(DownloadPolicy.Prefer(previous, item), previous))
        {
            UpdateDownloadContext();
            return;
        }
        _downloadItems[item.Snapshot.Task] = item; _latestDownload = item;
        _downloadErrorTitle = _downloadErrorMessage = null;
        UpdateDownloadContext();
    }

    private void RenderDownloadNotice()
    {
        if (DownloadNotice is null) return;
        if (_downloadErrorMessage is { } error && ActiveDownloadContext is not null)
        {
            DownloadNotice.IsOpen = true; DownloadNotice.Title = _downloadErrorTitle; DownloadNotice.Message = error;
            DownloadNotice.Severity = InfoBarSeverity.Warning; DownloadDetailsButton.IsEnabled = _latestDownload is not null && !_downloadOperationBusy;
            return;
        }
        if (_downloadPreparing && ActiveDownloadContext is not null)
        {
            DownloadNotice.IsOpen = true; DownloadNotice.Title = "正在核对下载目标";
            DownloadNotice.Message = "正在读取文件信息与已生效的默认下载目录…";
            DownloadNotice.Severity = InfoBarSeverity.Informational; DownloadDetailsButton.IsEnabled = false;
            return;
        }
        if (_latestDownload is not { } item || !IsCurrentDownloadContext(item.Context)) { DownloadNotice.IsOpen = false; return; }
        DownloadNotice.IsOpen = true; DownloadNotice.Title = DownloadPolicy.Stage(item);
        DownloadNotice.Message = $"{item.Name} · {DownloadPolicy.Progress(item).ActivityText}";
        DownloadNotice.Severity = DownloadPolicy.Tone(item) switch
        {
            DownloadPresentationTone.Success => InfoBarSeverity.Success,
            DownloadPresentationTone.Warning => InfoBarSeverity.Warning,
            _ => InfoBarSeverity.Informational,
        };
        DownloadDetailsButton.IsEnabled = !_downloadOperationBusy;
    }

    private async void DownloadDetails_Click(object sender, RoutedEventArgs args)
    {
        if (_latestDownload is { } item) await ShowDownloadDetailsAsync(item.Snapshot.Task);
    }

    private async Task ShowDownloadDetailsAsync(TransferTaskRef task)
    {
        if (_browserDialog is not null || _downloadDialog is not null || !_downloadItems.TryGetValue(task, out var item)
            || !IsCurrentDownloadContext(item.Context)) return;
        DownloadDetailsView view;
        try { view = new DownloadDetailsView(); }
        catch (Exception error) { ShowDownloadError("下载详情未能打开", error); return; }
        var dialog = new ContentDialog { Title = "文件下载", Content = new ScrollViewer { Content = view.Content, MaxHeight = 400, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled }, CloseButtonText = "关闭", DefaultButton = ContentDialogButton.Close };
        _downloadDialog = dialog; _downloadDetails = view; _downloadDetailTask = task;
        view.Cancel.Click += (_, _) => { view.CancelConfirmation.Visibility = Visibility.Visible; view.Cancel.IsEnabled = false; };
        view.Keep.Click += (_, _) => { view.CancelConfirmation.Visibility = Visibility.Collapsed; RenderDownloadDetails(); };
        view.ConfirmCancel.Click += async (_, _) => await RunDownloadControlAsync(task, cancel: true);
        dialog.PrimaryButtonClick += async (_, click) =>
        {
            click.Cancel = true; var deferral = click.GetDeferral();
            try { await RunDownloadControlAsync(task, cancel: false); }
            finally { deferral.Complete(); }
        };
        RenderDownloadDetails();
        try { await ShowBrowserDialogAsync(dialog); }
        finally { if (ReferenceEquals(_downloadDialog, dialog)) { _downloadDialog = null; _downloadDetails = null; _downloadDetailTask = null; } }
    }

    private void RenderDownloadDetails()
    {
        if (_downloadDialog is not { } dialog || _downloadDetails is not { } view || _downloadDetailTask is not { } task
            || !_downloadItems.TryGetValue(task, out var item) || !IsCurrentDownloadContext(item.Context)) return;
        view.Name.Text = item.Name; view.Destination.Text = $"保存到：{item.DestinationDisplayPath}";
        view.Stage.Text = DownloadPolicy.Stage(item);
        var progress = DownloadPolicy.Progress(item);
        view.Progress.IsIndeterminate = progress.IsIndeterminate; view.Progress.Value = progress.Value;
        view.Activity.Text = progress.ActivityText; view.Flushed.Text = progress.FlushedText;
        view.Detail.Text = _downloadErrorMessage ?? DownloadPolicy.Detail(item);
        dialog.PrimaryButtonText = DownloadPolicy.PrimaryLabel(item);
        dialog.IsPrimaryButtonEnabled = !_downloadOperationBusy && DownloadPolicy.CanReconcile(item);
        var cancel = DownloadPolicy.CanCancel(item);
        view.Cancel.Visibility = cancel ? Visibility.Visible : Visibility.Collapsed;
        view.Cancel.IsEnabled = cancel && !_downloadOperationBusy && view.CancelConfirmation.Visibility != Visibility.Visible;
        view.ConfirmCancel.IsEnabled = cancel && !_downloadOperationBusy;
        if (!cancel) view.CancelConfirmation.Visibility = Visibility.Collapsed;
    }

    private async Task RunDownloadControlAsync(TransferTaskRef task, bool cancel)
    {
        if (_downloadOperationBusy || _downloads is not { } service || !_downloadItems.TryGetValue(task, out var item)
            || !IsCurrentDownloadContext(item.Context) || (cancel ? !DownloadPolicy.CanCancel(item) : !DownloadPolicy.CanReconcile(item))) return;
        var context = item.Context;
        _downloadErrorTitle = _downloadErrorMessage = null;
        _downloadOperationBusy = true; UpdateDownloadContext();
        try
        {
            var changed = cancel ? await service.CancelAsync(context, task, _lifetime.Token)
                : await service.ReconcileLocalAsync(context, task, _lifetime.Token);
            AcceptDownloadItem(changed);
            if (_downloadDetails is { } view) view.CancelConfirmation.Visibility = Visibility.Collapsed;
        }
        catch (OperationCanceledException) { }
        catch (Exception error) { if (IsCurrentDownloadContext(context)) ShowDownloadError("下载操作未能完成", error); }
        finally { _downloadOperationBusy = false; if (!_disposed) UpdateDownloadContext(); }
    }

    private async void DownloadHistory_Click(object sender, RoutedEventArgs args)
    {
        if (_downloads is not { } service || ActiveDownloadContext is not { } context || _browserDialog is not null || _downloadDialog is not null) return;
        var viewGeneration = _downloadViewGeneration;
        bool Current() => viewGeneration == _downloadViewGeneration && IsCurrentDownloadContext(context);
        var entries = new StackPanel { Spacing = 8 };
        var hint = new TextBlock { Text = "只显示当前账号的下载记录。打开记录不会下载；未确认的本地结果可手动核实。", TextWrapping = TextWrapping.Wrap, FontSize = 12 };
        var more = new Button { Content = "加载更多", HorizontalAlignment = HorizontalAlignment.Left, Visibility = Visibility.Collapsed };
        var content = new StackPanel { Spacing = 12, MinWidth = 320, MaxWidth = 480 };
        content.Children.Add(hint); content.Children.Add(new ScrollViewer { Content = entries, MaxHeight = 340, VerticalScrollBarVisibility = ScrollBarVisibility.Auto }); content.Children.Add(more);
        var dialog = new ContentDialog { Title = "下载记录", Content = content, CloseButtonText = "关闭", DefaultButton = ContentDialogButton.Close };
        _downloadDialog = dialog;
        TransferTaskRef? selected = null; string? cursor = null; var loading = false;
        var seenCursors = new HashSet<string>(StringComparer.Ordinal); var seenTasks = new HashSet<TransferTaskRef>();
        async Task LoadPageAsync()
        {
            if (loading || !Current()) return;
            loading = true; more.IsEnabled = false;
            try
            {
                var page = await service.ListAsync(context, cursor, 30, _lifetime.Token);
                if (!Current() || !ReferenceEquals(_downloadDialog, dialog)) return;
                foreach (var entry in page.Items)
                {
                    if (entry.Context != context || entry.Snapshot.Task.Account != context.Account || !seenTasks.Add(entry.Snapshot.Task)) continue;
                    var visible = _downloadItems.TryGetValue(entry.Snapshot.Task, out var known) ? DownloadPolicy.Prefer(known, entry) : entry;
                    _downloadItems[entry.Snapshot.Task] = visible;
                    var text = new StackPanel { Spacing = 4 };
                    text.Children.Add(new TextBlock { Text = visible.Name, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis, MaxWidth = 420 });
                    text.Children.Add(new TextBlock { Text = DownloadPolicy.Stage(visible), FontSize = 12, TextWrapping = TextWrapping.Wrap });
                    text.Children.Add(new TextBlock { Text = visible.DestinationDisplayPath, FontSize = 12, TextTrimming = TextTrimming.CharacterEllipsis, MaxWidth = 420 });
                    var button = new Button { Content = text, HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Left, Padding = new Thickness(12) };
                    button.Click += (_, _) => { if (Current()) { selected = entry.Snapshot.Task; dialog.Hide(); } }; entries.Children.Add(button);
                }
                cursor = page.NextCursor;
                if (cursor is not null && !seenCursors.Add(cursor)) throw new FilewayException(new ErrorInfo(ErrorCode.ProtocolMismatch, "下载记录返回了重复的分页标记。"));
                more.Visibility = cursor is null ? Visibility.Collapsed : Visibility.Visible;
                if (entries.Children.Count == 0) hint.Text = "当前账号还没有下载记录。";
                UpdateDownloadContext();
            }
            catch (OperationCanceledException) { }
            catch (Exception error) { if (Current()) { hint.Text = "下载记录未能加载。" + ErrorMessage(error); more.Content = "重试"; more.Visibility = Visibility.Visible; } }
            finally { loading = false; more.IsEnabled = true; }
        }
        more.Click += async (_, _) => await LoadPageAsync(); dialog.Opened += async (_, _) => await LoadPageAsync();
        try { await ShowBrowserDialogAsync(dialog); }
        finally { if (ReferenceEquals(_downloadDialog, dialog)) _downloadDialog = null; }
        if (Current() && selected is { } task) await ShowDownloadDetailsAsync(task);
    }

    private void ShowDownloadError(string title, Exception error)
    {
        _downloadErrorTitle = title; _downloadErrorMessage = ErrorMessage(error);
        RenderDownloadNotice(); RenderDownloadDetails();
    }

    private sealed class DownloadDetailsView
    {
        public StackPanel Content { get; } = (StackPanel)XamlReader.Load("""
            <StackPanel xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml" Spacing="16" MinWidth="320" MaxWidth="440">
              <StackPanel Spacing="4"><TextBlock x:Name="Name" FontSize="18" FontWeight="SemiBold" TextWrapping="Wrap"/><TextBlock x:Name="Destination" FontSize="12" TextWrapping="Wrap" IsTextSelectionEnabled="True" Foreground="{ThemeResource TextFillColorSecondaryBrush}"/></StackPanel>
              <Border Padding="16" CornerRadius="8" Background="{ThemeResource LayerFillColorDefaultBrush}" BorderBrush="{ThemeResource CardStrokeColorDefaultBrush}" BorderThickness="1"><StackPanel Spacing="8"><TextBlock x:Name="Stage" FontWeight="SemiBold" TextWrapping="Wrap"/><ProgressBar x:Name="Progress" Minimum="0" Maximum="100" Height="4" AutomationProperties.Name="下载接收活动进度，完成还需校验与保存"/><TextBlock x:Name="Activity" FontSize="12" TextWrapping="Wrap"/><TextBlock x:Name="Flushed" FontSize="12" TextWrapping="Wrap" Foreground="{ThemeResource TextFillColorSecondaryBrush}"/></StackPanel></Border>
              <TextBlock x:Name="Detail" FontSize="12" TextWrapping="Wrap" Foreground="{ThemeResource TextFillColorSecondaryBrush}"/>
              <Button x:Name="Cancel" Content="取消下载…" HorizontalAlignment="Left"/>
              <Border x:Name="CancelConfirmation" Visibility="Collapsed" Padding="12" CornerRadius="8" BorderThickness="1" BorderBrush="{ThemeResource ControlStrokeColorDefaultBrush}"><StackPanel Spacing="10"><TextBlock Text="确认取消当前下载？只会清理本任务拥有的未发布临时文件，已经保存的文件会保留。" TextWrapping="Wrap"/><StackPanel Orientation="Horizontal" Spacing="8"><Button x:Name="Keep" Content="返回"/><Button x:Name="ConfirmCancel" Content="确认取消"/></StackPanel></StackPanel></Border>
            </StackPanel>
            """);
        public TextBlock Name => (TextBlock)Content.FindName("Name");
        public TextBlock Destination => (TextBlock)Content.FindName("Destination");
        public TextBlock Stage => (TextBlock)Content.FindName("Stage");
        public ProgressBar Progress => (ProgressBar)Content.FindName("Progress");
        public TextBlock Activity => (TextBlock)Content.FindName("Activity");
        public TextBlock Flushed => (TextBlock)Content.FindName("Flushed");
        public TextBlock Detail => (TextBlock)Content.FindName("Detail");
        public Button Cancel => (Button)Content.FindName("Cancel");
        public Border CancelConfirmation => (Border)Content.FindName("CancelConfirmation");
        public Button Keep => (Button)Content.FindName("Keep");
        public Button ConfirmCancel => (Button)Content.FindName("ConfirmCancel");
    }
}

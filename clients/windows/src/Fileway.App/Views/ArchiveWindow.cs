using Fileway.App.Branding;
using Fileway.App.Controls;
using Fileway.App.Windowing;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Fileway.App.Views;

/// <summary>
/// Read-only browser for one remote ZIP archive: bounded listing, multi-select extraction into the
/// effective download directory. Existing targets are never overwritten; every outcome is listed.
/// </summary>
public sealed class ArchiveWindow : Window, IAsyncDisposable
{
    private readonly IArchiveBrowseService _service;
    private readonly ILocalStorageSettingsService _settings;
    private readonly WindowTitleBar _titleBar = new();
    private readonly FramelessWindowChrome _chrome;
    private readonly RemoteResourceRef _archive;
    private readonly CancellationTokenSource _lifetime = new();
    private readonly InfoBar _notice = new() { IsOpen = true, IsClosable = false, Message = "正在读取压缩包…" };
    private readonly ProgressRing _progress = new() { IsActive = true, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
    private readonly ListView _list = new()
    {
        SelectionMode = ListViewSelectionMode.Extended,
        HorizontalContentAlignment = HorizontalAlignment.Stretch,
    };
    private IArchiveBrowseSession? _session;
    private bool _closed;
    private bool _started;
    private bool _busy;

    public ArchiveWindow(IArchiveBrowseService service, ILocalStorageSettingsService settings, RemoteResourceRef archive)
    {
        _service = service;
        _settings = settings;
        _archive = archive;
        Title = _titleBar.Caption = archive.DisplayName + " · " + AppBranding.DisplayName;
        SystemBackdrop = new MicaBackdrop();
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1000, 760));

        var commands = new CommandBar { DefaultLabelPosition = CommandBarDefaultLabelPosition.Right, Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent) };
        var extract = new AppBarButton { Label = "解压选中…", IsEnabled = false };
        var selectAll = new AppBarButton { Label = "全选", IsEnabled = false };
        extract.Click += async (_, _) => await ExtractSelectedAsync();
        selectAll.Click += (_, _) => { foreach (var item in _list.Items) _list.SelectedItems.Add(item); };
        commands.PrimaryCommands.Add(extract);
        commands.PrimaryCommands.Add(selectAll);
        _list.SelectionChanged += (_, _) => extract.IsEnabled = _list.SelectedItems.Count > 0 && !_busy;

        var root = new Grid { Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent), Padding = new Thickness(16, 12, 16, 16), RowSpacing = 10 };
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition());
        root.Children.Add(commands);
        Grid.SetRow(_notice, 1);
        root.Children.Add(_notice);
        var surface = new Grid();
        Grid.SetRow(surface, 2);
        surface.Children.Add(_list);
        surface.Children.Add(_progress);
        root.Children.Add(surface);

        var windowRoot = new Grid();
        windowRoot.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        windowRoot.RowDefinitions.Add(new RowDefinition());
        windowRoot.Children.Add(_titleBar);
        Grid.SetRow(root, 1);
        windowRoot.Children.Add(root);
        Content = windowRoot;
        _chrome = new FramelessWindowChrome(this, _titleBar);
        root.Loaded += async (_, _) => { if (_started) return; _started = true; await LoadAsync(); };
        Closed += async (_, _) =>
        {
            _closed = true;
            _lifetime.Cancel();
            if (_session is { } session) await session.DisposeAsync().ConfigureAwait(false);
            _chrome.Dispose();
            _lifetime.Dispose();
        };
    }

    public ValueTask DisposeAsync()
    {
        // The Closed handler owns teardown; this covers an explicit dispose before closing.
        if (!_closed) { _closed = true; _lifetime.Cancel(); _chrome.Dispose(); _lifetime.Dispose(); }
        return ValueTask.CompletedTask;
    }

    public void SetTheme(ElementTheme theme)
    {
        if (!_closed && Content is FrameworkElement root) root.RequestedTheme = theme;
    }

    private async Task LoadAsync()
    {
        try
        {
            var session = await _service.OpenAsync(_archive, _lifetime.Token);
            if (_closed) { await session.DisposeAsync(); return; }
            _session = session;
            _progress.IsActive = false;
            _progress.Visibility = Visibility.Collapsed;
            foreach (var entry in session.Entries)
            {
                var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12, Padding = new Thickness(8, 3, 8, 3), Tag = entry.Index };
                row.Children.Add(new TextBlock
                {
                    Text = entry.IsDirectory ? "[目录] " + entry.FullName : entry.FullName,
                    TextTrimming = TextTrimming.CharacterEllipsis,
                    HorizontalAlignment = HorizontalAlignment.Stretch,
                });
                row.Children.Add(new TextBlock
                {
                    Text = entry.IsDirectory ? "" : DownloadPresentationPolicy.FormatBytes(entry.Size),
                    FontSize = 12, Opacity = 0.65, VerticalAlignment = VerticalAlignment.Center,
                });
                _list.Items.Add(row);
            }
            _notice.IsOpen = false;
            SetNoticeStatus($"{session.Entries.Count} 个条目 · 解压总量上限 {DownloadPresentationPolicy.FormatBytes(session.TotalUncompressedBytes)}");
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (_closed) return;
            _progress.IsActive = false;
            _notice.Severity = InfoBarSeverity.Error;
            _notice.Message = error is FilewayException fileway ? fileway.Error.Message : "压缩包未能打开，请检查连接后重试。";
        }
    }

    private async Task ExtractSelectedAsync()
    {
        if (_session is not { } session || _busy || _closed || _list.SelectedItems.Count == 0) return;
        try
        {
            var state = await _settings.ReadAsync(_lifetime.Token);
            if (_closed) return;
            string leaf = System.IO.Path.GetFileNameWithoutExtension(_archive.DisplayName);
            leaf = DownloadPresentationPolicy.IsSafeLocalName(leaf) ? leaf : "archive";
            string target = state.Active.DownloadDirectory.TrimEnd('\\') + "\\archive-extract\\" + leaf;
            var panel = new StackPanel { Spacing = 10, MinWidth = 320, MaxWidth = 440 };
            panel.Children.Add(new TextBlock { Text = $"解压 {_list.SelectedItems.Count} 个条目到：", TextWrapping = TextWrapping.Wrap });
            panel.Children.Add(new TextBlock { Text = target, TextWrapping = TextWrapping.Wrap, FontSize = 12, IsTextSelectionEnabled = true });
            panel.Children.Add(new TextBlock { Text = "已存在的同名文件将跳过且不覆盖；解压结果逐条列明。", FontSize = 12, Opacity = 0.75, TextWrapping = TextWrapping.Wrap });
            var confirm = new ContentDialog { Title = "解压选中条目", Content = panel, PrimaryButtonText = "开始解压", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary };
            if (await confirm.ShowAsync() != ContentDialogResult.Primary || _closed) return;
            var indexes = new List<int>();
            foreach (var selected in _list.SelectedItems)
                if (selected is StackPanel { Tag: int index }) indexes.Add(index);
            if (indexes.Count == 0) return;
            _busy = true;
            _notice.Severity = InfoBarSeverity.Informational;
            _notice.Message = "正在解压…";
            _notice.IsOpen = true;
            var result = await session.ExtractAsync(indexes, target, _lifetime.Token);
            if (_closed) return;
            _notice.Severity = result.Failed > 0 ? InfoBarSeverity.Warning : InfoBarSeverity.Success;
            _notice.Message = $"解压完成：{result.Extracted} 成功 · {result.Skipped} 跳过 · {result.Failed} 失败。目标：{target}";
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (_closed) return;
            _notice.Severity = InfoBarSeverity.Error;
            _notice.Message = error is FilewayException fileway ? fileway.Error.Message : "解压未能完成，请重试。";
        }
        finally { _busy = false; }
    }

    private void SetNoticeStatus(string message)
    {
        _notice.Severity = InfoBarSeverity.Informational;
        _notice.Message = message;
        _notice.IsOpen = true;
    }
}

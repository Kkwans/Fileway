using Fileway.App.Branding;
using Fileway.App.Controls;
using Fileway.App.Windowing;
using Fileway.Core.Contracts;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Fileway.App.Views;

/// <summary>
/// Bounded text preview with native Markdown/CSV projections and explicit conditional editing.
/// A successful decoded load and a current server observation are required before any write.
/// </summary>
public sealed class TextWindow : Window, IAsyncDisposable
{
    public const long MaximumTextBytes = 4 * 1024 * 1024;
    public static readonly HashSet<string> Extensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".txt", ".md", ".markdown", ".log", ".json", ".xml", ".yaml", ".yml", ".toml", ".ini", ".cfg", ".conf",
        ".csv", ".tsv", ".srt", ".ass", ".vtt", ".lrc", ".py", ".cs", ".go", ".js", ".ts", ".c", ".h", ".cpp",
        ".hpp", ".java", ".kt", ".rs", ".sh", ".bat", ".cmd", ".ps1", ".sql", ".html", ".htm", ".css", ".properties",
    };

    private readonly ITextContentService _service;
    private readonly WindowTitleBar _titleBar = new();
    private readonly FramelessWindowChrome _chrome;
    private readonly RemoteResourceRef _resource;
    private readonly CancellationTokenSource _lifetime = new();
    private readonly TextBox _text = new()
    {
        IsReadOnly = true, AcceptsReturn = true, TextWrapping = TextWrapping.NoWrap,
        FontFamily = new FontFamily("Cascadia Code, Consolas, Courier New"),
        FontSize = 13.5,
        IsSpellCheckEnabled = false, IsTextPredictionEnabled = false,
        HorizontalAlignment = HorizontalAlignment.Stretch, VerticalAlignment = VerticalAlignment.Stretch,
        Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
        BorderThickness = new Thickness(0), Padding = new Thickness(20, 16, 20, 24),
    };
    private readonly TextBlock _title = new() { FontSize = 14, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly TextBlock _summary = new() { FontSize = 12 };
    private readonly TextBlock _status = new() { FontSize = 12, VerticalAlignment = VerticalAlignment.Center };
    private readonly ScrollViewer _renderScroll = new() { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
    private readonly StackPanel _renderHost = new() { Spacing = 0 };
    private readonly ScrollViewer _tableScroll = new()
    {
        VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
        Visibility = Visibility.Collapsed,
    };
    private readonly Grid _editor = new();
    private readonly Grid _content = new();
    private readonly Border _textFrame;
    private readonly ProgressRing _loading = new() { IsActive = false, Width = 32, Height = 32, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Visibility = Visibility.Collapsed };
    private readonly Border _toolbar;
    private readonly Border _banner;
    private readonly TextBlock _bannerText;
    private readonly Button _renderButton;
    private readonly Button _tableButton;
    private readonly Button _wrapButton;
    private readonly Button _copyButton;
    private readonly Button _editButton;
    private readonly Button _saveButton;
    private readonly Button _discardButton;
    private readonly Grid _root;
    private readonly Grid _windowRoot;
    private bool _editing;
    private bool _dirty;
    private bool _loaded;
    private bool _saving;
    private bool _confirmingClose;
    private long _contentLength;
    private Fileway.Core.Contracts.TextEditExpectation? _expectation;
    private bool _isMarkdown;
    private bool _isTable;
    private string _encodingName = "UTF-8";
    private CsvGridParser.Grid? _grid;
    private string _rawText = "";
    private string _lineEnding = "\r\n";
    private bool _showRendered = true;
    private bool _showTable = true;
    private bool _closed;
    private bool _started;
    private bool _wrapped;
    private ElementTheme _theme = ElementTheme.Dark;
    private Microsoft.UI.Dispatching.DispatcherQueueTimer? _bannerTimer;

    public TextWindow(ITextContentService service, RemoteResourceRef resource)
    {
        _service = service;
        _resource = resource;
        Title = _titleBar.Caption = resource.DisplayName + " · " + AppBranding.DisplayName;
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1080, 800));

        _root = new Grid();
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _root.RowDefinitions.Add(new RowDefinition());
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        _title.Text = resource.DisplayName;
        var titleStack = new StackPanel { Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
        titleStack.Children.Add(_title);
        titleStack.Children.Add(_summary);
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4, VerticalAlignment = VerticalAlignment.Center };

        _renderButton = TextAction("源码", "切换渲染与源码", ToggleRendered);
        _tableButton = TextAction("纯文本", "切换表格与纯文本", ToggleTable);
        _wrapButton = TextAction("换行", "切换自动换行", ToggleWrap);
        _copyButton = TextAction("复制", "复制全文", CopyAll);
        _editButton = TextAction("编辑", "编辑文本", EnterEditMode);
        _saveButton = new Button { Content = "保存", Style = (Style)Application.Current.Resources["FwPrimaryButtonStyle"], Height = 32, MinHeight = 32, Padding = new Thickness(16, 0, 16, 0), Visibility = Visibility.Collapsed };
        _discardButton = new Button { Content = "放弃", Style = (Style)Application.Current.Resources["FwSecondaryButtonStyle"], Height = 32, MinHeight = 32, Padding = new Thickness(14, 0, 14, 0), Visibility = Visibility.Collapsed };
        _saveButton.Click += (_, _) => _ = SaveAsync();
        _discardButton.Click += (_, _) => DiscardEdit();
        AutomationProperties.SetAutomationId(_editButton, "EditTextButton");
        AutomationProperties.SetAutomationId(_saveButton, "SaveTextButton");
        AutomationProperties.SetAutomationId(_discardButton, "DiscardTextButton");
        AutomationProperties.SetAutomationId(_renderButton, "RenderTextToggle");
        AutomationProperties.SetAutomationId(_tableButton, "TableTextToggle");
        AutomationProperties.SetAutomationId(_text, "TextEditor");
        AutomationProperties.SetName(_text, "文本内容");
        actions.Children.Add(_renderButton);
        actions.Children.Add(_tableButton);
        actions.Children.Add(_wrapButton);
        actions.Children.Add(_copyButton);
        actions.Children.Add(_discardButton);
        actions.Children.Add(_saveButton);
        actions.Children.Add(_editButton);

        var toolbarGrid = new Grid { Padding = new Thickness(20, 0, 16, 0), Height = 58 };
        toolbarGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        toolbarGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        toolbarGrid.Children.Add(titleStack);
        Grid.SetColumn(actions, 1);
        toolbarGrid.Children.Add(actions);
        _toolbar = new Border { Child = toolbarGrid, BorderThickness = new Thickness(0, 0, 0, 1) };
        _root.Children.Add(_toolbar);

        _bannerText = new TextBlock { FontSize = 13, TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center };
        _banner = new Border
        {
            Visibility = Visibility.Collapsed,
            Padding = new Thickness(20, 10, 16, 10),
            BorderThickness = new Thickness(0, 0, 0, 1),
            Child = _bannerText,
        };
        Grid.SetRow(_banner, 1);
        _root.Children.Add(_banner);

        _textFrame = new Border
        {
            Margin = new Thickness(20, 16, 20, 16),
            CornerRadius = new CornerRadius(12),
            BorderThickness = new Thickness(1),
            Child = _text,
        };
        _editor.Children.Add(_textFrame);
        var renderColumn = new Grid { MaxWidth = 780, HorizontalAlignment = HorizontalAlignment.Center, Padding = new Thickness(48, 36, 48, 64) };
        _renderScroll.Content = renderColumn;
        renderColumn.Children.Add(_renderHost);
        _editor.Children.Add(_renderScroll);
        _editor.Children.Add(_tableScroll);
        _editor.Children.Add(_loading);
        Grid.SetRow(_editor, 2);
        _root.Children.Add(_editor);

        var statusBar = new Border { BorderThickness = new Thickness(0, 1, 0, 0), Padding = new Thickness(20, 0, 20, 0), Height = 30 };
        var statusGrid = new Grid();
        statusGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        statusGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        statusGrid.Children.Add(_status);
        statusBar.Child = statusGrid;
        Grid.SetRow(statusBar, 3);
        _root.Children.Add(statusBar);

        _windowRoot = new Grid();
        _windowRoot.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _windowRoot.RowDefinitions.Add(new RowDefinition());
        _windowRoot.Children.Add(_titleBar);
        Grid.SetRow(_root, 1);
        _windowRoot.Children.Add(_root);
        Content = _windowRoot;
        _chrome = new FramelessWindowChrome(this, _titleBar);
        ApplyTheme(_theme);
        root_Loaded();
        AppWindow.Closing += (window, closingArgs) =>
        {
            if (_saving) { closingArgs.Cancel = true; return; }
            if (_closed || !_editing || !_dirty) return;
            closingArgs.Cancel = true;
            if (!_confirmingClose) _ = ConfirmDiscardAndCloseAsync();
        };
        Closed += (_, _) =>
        {
            _closed = true;
            _bannerTimer?.Stop();
            _lifetime.Cancel();
            _chrome.Dispose();
            _lifetime.Dispose();
        };
        UpdateCommands();
    }

    private async void root_Loaded()
    {
        _root.Loaded += async (_, _) =>
        {
            if (_started) return;
            _started = true;
            await LoadAsync();
        };
    }

    private static Button TextAction(string text, string tooltip, Action click)
    {
        var button = new Button
        {
            Content = new TextBlock { Text = text, FontSize = 13 },
            Style = (Style)Application.Current.Resources["FwTextIconButtonStyle"],
        };
        ToolTipService.SetToolTip(button, tooltip);
        AutomationProperties.SetName(button, tooltip);
        button.Click += (_, _) => click();
        return button;
    }

    public ValueTask DisposeAsync()
    {
        // The Closed handler owns teardown; this covers an explicit dispose before closing.
        if (!_closed) { _closed = true; _lifetime.Cancel(); _chrome.Dispose(); _lifetime.Dispose(); }
        return ValueTask.CompletedTask;
    }

    public void SetTheme(ElementTheme theme)
    {
        if (_closed) return;
        // The reader uses the dark editorial palette in both app themes; the light
        // variant proved unreadable when the rest of the shell was dark.
        _theme = ElementTheme.Dark;
        if (Content is FrameworkElement root) root.RequestedTheme = ElementTheme.Dark;
        ApplyTheme(ElementTheme.Dark);
    }

    private Brush ThemeBrush(string key)
    {
        var name = "Default";
        if (Application.Current.Resources.ThemeDictionaries.TryGetValue(name, out var dictionary) &&
            dictionary is ResourceDictionary resources && resources.TryGetValue(key, out var value) && value is Brush brush)
            return brush;
        return Application.Current.Resources.TryGetValue(key, out var fallback) && fallback is Brush brushValue
            ? brushValue
            : new SolidColorBrush(_theme == ElementTheme.Light ? Windows.UI.Color.FromArgb(255, 243, 244, 246) : Windows.UI.Color.FromArgb(255, 23, 25, 29));
    }

    private void ApplyTheme(ElementTheme theme)
    {
        // The reader is intentionally a dark editorial surface; explicit colors keep it
        // readable even when the host window's theme or resource lookup lags behind.
        _theme = ElementTheme.Dark;
        if (Content is FrameworkElement rootContent) rootContent.RequestedTheme = ElementTheme.Dark;
        var canvas = Brush(11, 12, 15);
        _root.Background = canvas;
        _windowRoot.Background = canvas;
        _title.Foreground = Brush(244, 246, 248);
        _summary.Foreground = Brush(168, 174, 184);
        _status.Foreground = Brush(168, 174, 184);
        _bannerText.Foreground = Brush(244, 246, 248);
        _banner.Background = Brush(31, 34, 40);
        _banner.BorderBrush = Brush(255, 255, 255, 31);
        _textFrame.Background = Brush(23, 25, 29);
        _textFrame.BorderBrush = Brush(255, 255, 255, 31);
        _text.Foreground = Brush(244, 246, 248);
        _toolbar.Background = Brush(23, 25, 29);
        _toolbar.BorderBrush = Brush(255, 255, 255, 31);
        _titleBar.RequestedTheme = ElementTheme.Dark;
        foreach (var action in new[] { _renderButton, _tableButton, _wrapButton, _copyButton, _editButton })
            action.Foreground = Brush(244, 246, 248);
    }

    private static SolidColorBrush Brush(byte red, byte green, byte blue, byte alpha = 255)
        => new(Windows.UI.Color.FromArgb(alpha, red, green, blue));

    private void ShowBanner(string message, bool error)
    {
        _bannerText.Text = message;
        _banner.BorderBrush = error ? ThemeBrush("FwDangerBrush") : ThemeBrush("FwStrokeBrush");
        _banner.Visibility = Visibility.Visible;
        _bannerTimer?.Stop();
        if (error) return;
        _bannerTimer ??= DispatcherQueue.CreateTimer();
        _bannerTimer.IsRepeating = false;
        _bannerTimer.Interval = TimeSpan.FromSeconds(4);
        _bannerTimer.Tick -= BannerTimer_Tick;
        _bannerTimer.Tick += BannerTimer_Tick;
        _bannerTimer.Start();
    }

    private void BannerTimer_Tick(object? sender, object args)
    {
        _bannerTimer?.Stop();
        _banner.Visibility = Visibility.Collapsed;
    }

    private void CopyAll()
    {
        try
        {
            var package = new Windows.ApplicationModel.DataTransfer.DataPackage();
            package.SetText(_rawText);
            Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(package);
            ShowBanner("已复制全文。", error: false);
        }
        catch (Exception) { ShowBanner("复制未能完成，请重试。", error: true); }
    }

    private void ToggleRendered()
    {
        _showRendered = !_showRendered;
        _renderButton.Content = new TextBlock { Text = _showRendered ? "源码" : "渲染", FontSize = 13 };
        ApplyViewMode();
    }

    private void ToggleTable()
    {
        _showTable = !_showTable;
        _tableButton.Content = new TextBlock { Text = _showTable ? "纯文本" : "表格", FontSize = 13 };
        ApplyViewMode();
    }

    private void ToggleWrap()
    {
        _wrapped = !_wrapped;
        _text.TextWrapping = _wrapped ? TextWrapping.Wrap : TextWrapping.NoWrap;
        _wrapButton.Content = new TextBlock { Text = _wrapped ? "不换行" : "换行", FontSize = 13 };
    }

    private async Task ConfirmDiscardAndCloseAsync()
    {
        _confirmingClose = true;
        UpdateCommands();
        try
        {
            var confirm = new ContentDialog { XamlRoot = _text.XamlRoot, RequestedTheme = _text.ActualTheme, Title = "有未保存的修改", Content = "关闭将放弃本次编辑，且不会写入服务器。", PrimaryButtonText = "放弃并关闭", CloseButtonText = "返回编辑", DefaultButton = ContentDialogButton.Close };
            var choice = await confirm.ShowAsync();
            if (!_closed && choice == ContentDialogResult.Primary) { _editing = false; Close(); }
        }
        catch (Exception) { if (!_closed) ShowBanner("确认窗口未能打开，请关闭其他对话框后重试。", error: true); }
        finally { _confirmingClose = false; if (!_closed) UpdateCommands(); }
    }

    private void UpdateCommands()
    {
        var ready = _loaded && !_closed && !_saving && !_confirmingClose;
        _renderButton.Visibility = _isMarkdown && !_editing ? Visibility.Visible : Visibility.Collapsed;
        _renderButton.IsEnabled = ready;
        _tableButton.Visibility = _grid is not null && !_editing ? Visibility.Visible : Visibility.Collapsed;
        _tableButton.IsEnabled = ready;
        _wrapButton.Visibility = (!_isMarkdown || !_showRendered) && !_editing ? Visibility.Visible : Visibility.Collapsed;
        _wrapButton.IsEnabled = ready;
        _copyButton.Visibility = !_editing ? Visibility.Visible : Visibility.Collapsed;
        _copyButton.IsEnabled = ready;
        _editButton.Visibility = !_editing ? Visibility.Visible : Visibility.Collapsed;
        _editButton.IsEnabled = ready && _expectation is not null;
        _saveButton.Visibility = _editing ? Visibility.Visible : Visibility.Collapsed;
        _saveButton.IsEnabled = ready && _dirty && _expectation is not null;
        _discardButton.Visibility = _editing ? Visibility.Visible : Visibility.Collapsed;
        _discardButton.IsEnabled = ready;
        var mode = _editing ? (_dirty ? "编辑中 · 未保存" : "编辑中") : (_expectation is null ? "只读" : "可编辑");
        _summary.Text = $"{_encodingName} · {DownloadPresentationPolicy.FormatBytes(_contentLength)} · {mode}";
        _status.Text = _isMarkdown ? (_showRendered ? "Markdown 渲染视图" : "源码视图") : _isTable ? (_showTable ? "表格视图" : "纯文本视图") : "纯文本";
        _status.Text += _wrapped ? " · 自动换行" : "";
    }

    private void UpdateEditCommands()
    {
        if (_loaded) UpdateCommands();
    }

    private void EnterEditMode()
    {
        if (!_loaded || _editing || _closed || _saving || _confirmingClose) return;
        if (_expectation is null)
        {
            ShowBanner("此文件暂不可编辑（服务器状态未取得或读取期间已变化），请重新打开。", error: true);
            return;
        }
        _editing = true;
        _dirty = false;
        _text.IsReadOnly = false;
        // Editing happens in plain text; rendering/table projections are suspended.
        _textFrame.Visibility = Visibility.Visible;
        _renderScroll.Visibility = Visibility.Collapsed;
        _tableScroll.Visibility = Visibility.Collapsed;
        UpdateCommands();
        _text.Focus(FocusState.Programmatic);
    }

    private void DiscardEdit()
    {
        if (!_editing || _saving || _confirmingClose) return;
        _text.IsReadOnly = true;
        _editing = false;
        _dirty = false;
        _text.Text = _rawText;
        ApplyViewMode();
        UpdateCommands();
        _banner.Visibility = Visibility.Collapsed;
    }

    private bool ContentEncodingIsLegacy() => _encodingName is not ("UTF-8" or "UTF-8 BOM");

    private string NormalizeLineEndings(string text) => text.Replace("\r\n", "\n", StringComparison.Ordinal)
        .Replace('\r', '\n').Replace("\n", _lineEnding, StringComparison.Ordinal);

    private async Task SaveAsync()
    {
        if (!_loaded || !_editing || !_dirty || _expectation is not { } expected || _closed || _saving || _confirmingClose) return;
        _saving = true;
        _text.IsReadOnly = true;
        UpdateCommands();
        try
        {
            bool reencode = ContentEncodingIsLegacy();
            var panel = new StackPanel { Spacing = 12, MinWidth = 320, MaxWidth = 420 };
            panel.Children.Add(new TextBlock { Text = "保存对服务器上文件的修改？" + (reencode ? "\n原文件不是 UTF-8：保存将以 UTF-8 重写。" : ""), TextWrapping = TextWrapping.Wrap });
            panel.Children.Add(new TextBlock { Text = "若期间文件已在服务器上被修改，保存会被拒绝且不会覆盖。", FontSize = 12, Opacity = 0.75, TextWrapping = TextWrapping.Wrap });
            var confirm = new ContentDialog { XamlRoot = _text.XamlRoot, RequestedTheme = _text.ActualTheme, Title = "保存修改", Content = panel, PrimaryButtonText = "保存", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary };
            var choice = await confirm.ShowAsync();
            if (choice != ContentDialogResult.Primary || !_editing || _closed) return;
            ShowBanner("正在保存…", error: false);
            // WinUI TextBox normalizes edited line breaks to CR. Keep the file's line format
            // instead of sending CR-only text to the server or the Markdown/CSV projections.
            var editedText = NormalizeLineEndings(_text.Text);
            _expectation = await _service.SaveAsync(_resource, expected, editedText, _lifetime.Token);
            if (_closed) return;
            _rawText = editedText;
            _encodingName = "UTF-8";
            _contentLength = _expectation.Size;
            if (_isTable) _grid = CsvGridParser.Parse(_rawText, CsvGridParser.DetectDelimiter(_rawText, _resource.DisplayName));
            _dirty = false;
            _text.IsReadOnly = true;
            _editing = false;
            ApplyViewMode();
            UpdateCommands();
            ShowBanner("修改已保存。", error: false);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (_closed) return;
            if (error is FilewayException { Error.Code: ErrorCode.SourceChanged or ErrorCode.OutcomeUnknown }) _expectation = null;
            var message = error is FilewayException fileway ? fileway.Error.Message : "保存未能完成，请检查连接后重试。";
            ShowBanner(message, error: true);
            UpdateCommands();
        }
        finally
        {
            _saving = false;
            if (!_closed) { _text.IsReadOnly = !_editing; UpdateCommands(); }
        }
    }

    /// <summary>Renders the bounded markdown subset natively; plain text stays one click away.</summary>
    private void ApplyViewMode()
    {
        if (_editing) return;
        if (_isTable && _showTable && _grid is { } grid)
        {
            _textFrame.Visibility = Visibility.Collapsed;
            _renderScroll.Visibility = Visibility.Collapsed;
            _tableScroll.Visibility = Visibility.Visible;
            BuildTable(grid);
            return;
        }
        if (!_isMarkdown || !_showRendered)
        {
            _textFrame.Visibility = Visibility.Visible;
            _renderScroll.Visibility = Visibility.Collapsed;
            _tableScroll.Visibility = Visibility.Collapsed;
            return;
        }
        _textFrame.Visibility = Visibility.Collapsed;
        _renderScroll.Visibility = Visibility.Visible;
        _renderHost.Children.Clear();
        try
        {
            foreach (var block in MarkdownBlockRenderer.Render(_rawText))
                _renderHost.Children.Add(block);
        }
        catch (Exception)
        {
            // Rendering is a presentation aid; any fault falls back to the plain text.
            _textFrame.Visibility = Visibility.Visible;
            _renderScroll.Visibility = Visibility.Collapsed;
            _tableScroll.Visibility = Visibility.Collapsed;
            _showRendered = false;
        }
    }

    private void BuildTable(CsvGridParser.Grid grid)
    {
        if (_tableScroll.Content is Grid built) { _tableScroll.Content = null; built.Children.Clear(); }
        var layout = new Grid { Margin = new Thickness(20, 16, 20, 20), RowSpacing = 0 };
        var header = new StackPanel { Orientation = Orientation.Horizontal };
        for (int column = 0; column < grid.TotalColumns; column++)
        {
            header.Children.Add(new TextBlock
            {
                Text = column < grid.Rows[0].Length ? grid.Rows[0][column] : "",
                FontWeight = FontWeights.SemiBold,
                FontSize = 13,
                TextWrapping = TextWrapping.NoWrap, TextTrimming = TextTrimming.CharacterEllipsis,
                Width = 220, Padding = new Thickness(12, 8, 12, 8),
            });
        }
        var rows = new List<string[]>();
        for (int index = 1; index < grid.Rows.Count; index++) rows.Add(grid.Rows[index]);
        var cells = new System.Text.StringBuilder();
        for (int column = 0; column < grid.TotalColumns; column++)
            cells.Append("<TextBlock Text=\"{Binding [").Append(column).Append("]}\" Width=\"220\" FontSize=\"13\" TextWrapping=\"NoWrap\" TextTrimming=\"CharacterEllipsis\" Padding=\"12,6\"/>");
        string templateXaml = "<DataTemplate" +
            " xmlns=\"http://schemas.microsoft.com/winfx/2006/xaml/presentation\">" +
            "<StackPanel Orientation=\"Horizontal\">" + cells + "</StackPanel></DataTemplate>";
        var list = new ListView
        {
            ItemsSource = rows,
            ItemTemplate = (DataTemplate)Microsoft.UI.Xaml.Markup.XamlReader.Load(templateXaml),
            SelectionMode = ListViewSelectionMode.None,
            IsItemClickEnabled = false,
        };
        var table = new StackPanel { Spacing = 0 };
        table.Children.Add(new Border
        {
            Background = (Brush)Application.Current.Resources["LayerFillColorDefaultBrush"],
            CornerRadius = new CornerRadius(10, 10, 0, 0),
            Child = header,
        });
        table.Children.Add(list);
        string note = (grid.RowsTruncated ? $"已截取前 {grid.Rows.Count} 行；" : "") +
            (grid.TotalColumns >= CsvGridParser.MaximumColumns ? $"仅显示前 {grid.TotalColumns} 列；" : "") +
            $"共 {grid.TotalRows} 行 × 至少 {grid.TotalColumns} 列";
        table.Children.Add(new TextBlock { Text = note, FontSize = 12, Opacity = 0.7, Margin = new Thickness(12, 8, 0, 6) });
        layout.Children.Add(table);
        _tableScroll.Content = layout;
    }

    private async Task LoadAsync()
    {
        _loading.Visibility = Visibility.Visible;
        _loading.IsActive = true;
        try
        {
            var content = await _service.OpenAsync(_resource, _lifetime.Token);
            if (_closed) return;
            _rawText = content.Text;
            var firstLf = content.Text.IndexOf('\n');
            _lineEnding = firstLf >= 0 ? (firstLf > 0 && content.Text[firstLf - 1] == '\r' ? "\r\n" : "\n")
                : content.Text.Contains('\r') ? "\r" : Environment.NewLine;
            _text.Text = content.Text;
            _contentLength = content.DeclaredLength;
            _loaded = true;
            _isMarkdown = _resource.DisplayName.EndsWith(".md", StringComparison.OrdinalIgnoreCase) ||
                _resource.DisplayName.EndsWith(".markdown", StringComparison.OrdinalIgnoreCase);
            _isTable = !_isMarkdown && (_resource.DisplayName.EndsWith(".csv", StringComparison.OrdinalIgnoreCase) ||
                _resource.DisplayName.EndsWith(".tsv", StringComparison.OrdinalIgnoreCase));
            if (_isTable)
            {
                char delimiter = CsvGridParser.DetectDelimiter(content.Text, _resource.DisplayName);
                _grid = CsvGridParser.Parse(content.Text, delimiter);
            }
            _expectation = content.Edit;
            _encodingName = content.EncodingName;
            UpdateCommands();
            _text.TextChanged += (_, _) => { if (_editing && !_saving) { _dirty = !string.Equals(NormalizeLineEndings(_text.Text), NormalizeLineEndings(_rawText), StringComparison.Ordinal); UpdateCommands(); } };
            _banner.Visibility = Visibility.Collapsed;
            ApplyViewMode();
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (_closed) return;
            var message = error is FilewayException fileway ? fileway.Error.Message : "文本未能加载，请检查连接后重试。";
            ShowBanner(message, error: true);
        }
        finally
        {
            _loading.IsActive = false;
            _loading.Visibility = Visibility.Collapsed;
        }
    }
}

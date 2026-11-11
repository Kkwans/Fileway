using Fileway.App.Branding;
using Fileway.App.Controls;
using Fileway.App.Windowing;
using Fileway.Core.Contracts;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.System;

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
    private readonly TextBlock _mode = new() { FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.NoWrap };
    private readonly TextBlock _summary = new() { TextWrapping = TextWrapping.NoWrap, TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly ScrollViewer _renderScroll = new()
    {
        VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
        Visibility = Visibility.Collapsed,
    };
    private readonly StackPanel _renderHost = new() { Spacing = 0 };
    private readonly ScrollViewer _tableScroll = new()
    {
        VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
        Visibility = Visibility.Collapsed,
    };
    private readonly Grid _editor = new();
    private readonly Grid _content = new();
    private readonly Border _textFrame;
    private readonly Border _emptyState;
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
    private ElementTheme _theme = ElementTheme.Default;
    private readonly InfoBar _toast = new()
    {
        IsClosable = true, IsOpen = false, Severity = InfoBarSeverity.Success,
        HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Top,
        Margin = new Thickness(12, 12, 12, 0), MaxWidth = 400,
    };

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

        var metadata = new Grid { ColumnSpacing = 10, VerticalAlignment = VerticalAlignment.Center };
        metadata.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        metadata.ColumnDefinitions.Add(new ColumnDefinition());
        metadata.Children.Add(_mode);
        Grid.SetColumn(_summary, 1);
        metadata.Children.Add(_summary);
        var actions = new StackPanel
        {
            Orientation = Orientation.Horizontal, Spacing = 4,
            HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center,
        };

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
        AutomationProperties.SetAutomationId(_mode, "TextViewMode");
        AutomationProperties.SetAutomationId(_summary, "TextViewMetadata");
        AutomationProperties.SetAutomationId(_text, "TextEditor");
        AutomationProperties.SetName(_text, "文本内容");
        actions.Children.Add(_renderButton);
        actions.Children.Add(_tableButton);
        actions.Children.Add(_wrapButton);
        actions.Children.Add(_copyButton);
        actions.Children.Add(_discardButton);
        actions.Children.Add(_saveButton);
        actions.Children.Add(_editButton);

        var toolbarGrid = new Grid { Padding = new Thickness(20, 6, 16, 6), MinHeight = 46, ColumnSpacing = 12 };
        toolbarGrid.ColumnDefinitions.Add(new ColumnDefinition());
        toolbarGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        toolbarGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        toolbarGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        toolbarGrid.Children.Add(metadata);
        Grid.SetColumn(actions, 1);
        toolbarGrid.Children.Add(actions);
        toolbarGrid.SizeChanged += (_, args) =>
        {
            var stackCommands = args.NewSize.Width < 460;
            if (stackCommands == (Grid.GetRow(actions) == 1)) return;
            toolbarGrid.RowSpacing = stackCommands ? 4 : 0;
            Grid.SetColumnSpan(metadata, stackCommands ? 2 : 1);
            Grid.SetColumn(actions, stackCommands ? 0 : 1);
            Grid.SetColumnSpan(actions, stackCommands ? 2 : 1);
            Grid.SetRow(actions, stackCommands ? 1 : 0);
        };
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
        var emptyPanel = new StackPanel { Spacing = 14, MaxWidth = 420, Margin = new Thickness(12), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        emptyPanel.Children.Add(new Border
        {
            Width = 72, Height = 72, CornerRadius = new CornerRadius(20),
            Style = (Style)Application.Current.Resources["FwIconTileStyle"],
            Child = new LucideIcon { IconName = "file", Width = 32, Height = 32, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center },
        });
        emptyPanel.Children.Add(new TextBlock { Text = "文件为空", FontSize = 20, FontWeight = FontWeights.SemiBold, TextAlignment = TextAlignment.Center });
        emptyPanel.Children.Add(new TextBlock
        {
            Text = "这个文件没有可显示的内容。", FontSize = 13, LineHeight = 20, TextAlignment = TextAlignment.Center,
            Style = (Style)Application.Current.Resources["FwCaptionTextStyle"],
        });
        _emptyState = new Border { Child = emptyPanel, Visibility = Visibility.Collapsed, IsHitTestVisible = false };
        _editor.Children.Add(_emptyState);
        _editor.Children.Add(_loading);
        FloatingNotice.Attach(_toast, automatic: false);
        Canvas.SetZIndex(_toast, 5);
        _editor.Children.Add(_toast);
        Grid.SetRow(_editor, 2);
        _root.Children.Add(_editor);

        _windowRoot = new Grid();
        _windowRoot.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _windowRoot.RowDefinitions.Add(new RowDefinition());
        _windowRoot.Children.Add(_titleBar);
        Grid.SetRow(_root, 1);
        _windowRoot.Children.Add(_root);
        Content = _windowRoot;
        _windowRoot.PreviewKeyDown += OnPreviewKeyDown;
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
            FloatingNotice.Detach(_toast);
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
            FocusContent();
        };
    }

    private void FocusContent()
    {
        if (_closed || _editing) return;
        // Left alone, WinUI gives a new window keyboard focus to the first caption button,
        // so Enter/Space would minimise the reader before the content was ever reachable.
        // The root holds focus instead: focusing the read-only editor draws the TextBox
        // template's accent underline, which reads as a stray line under the document panel.
        _root.IsTabStop = true;
        _ = _root.Focus(FocusState.Programmatic);
        DispatcherQueue.TryEnqueue(() =>
        {
            if (!_closed && !_editing) _ = _root.Focus(FocusState.Programmatic);
        });
    }

    private void OnPreviewKeyDown(object sender, KeyRoutedEventArgs args)
    {
        // While editing, caret and clipboard keys belong to the text box itself.
        if (_editing) return;
        if (args.Key == VirtualKey.Escape)
        {
            Close();
            args.Handled = true;
            return;
        }

        if (args.Key is VirtualKey.Space or VirtualKey.Enter)
            for (var element = args.OriginalSource as DependencyObject; element is not null && element != _windowRoot;
                 element = VisualTreeHelper.GetParent(element))
                if (element is ButtonBase) return;

        if (ActiveScrollHost() is not { } host) return;
        var page = Math.Max(80, host.ViewportHeight * 0.9);
        const double line = 24;
        double? target = args.Key switch
        {
            VirtualKey.Up => host.VerticalOffset - line,
            VirtualKey.Down => host.VerticalOffset + line,
            VirtualKey.PageUp => host.VerticalOffset - page,
            VirtualKey.PageDown or VirtualKey.Space => host.VerticalOffset + page,
            VirtualKey.Home => 0,
            VirtualKey.End => host.ScrollableHeight,
            _ => null,
        };
        if (target is null) return;
        host.ChangeView(null, Math.Clamp(target.Value, 0, host.ScrollableHeight), null, false);
        args.Handled = true;
    }

    private ScrollViewer? ActiveScrollHost()
    {
        if (_renderScroll.Visibility == Visibility.Visible) return _renderScroll;
        if (_tableScroll.Visibility == Visibility.Visible) return _tableScroll;
        return _textFrame.Visibility == Visibility.Visible ? PlainTextScrollHost() : null;
    }

    private ScrollViewer? PlainTextScrollHost()
    {
        // The read-only editor owns its scroll host, which is only reachable once the template
        // exists. Focusing the text box instead would draw its accent underline inside the
        // rounded document panel, so the reader keeps focus on the root and drives this host.
        return FindScrollHost(_text);

        static ScrollViewer? FindScrollHost(DependencyObject node)
        {
            var children = VisualTreeHelper.GetChildrenCount(node);
            for (var index = 0; index < children; index++)
            {
                var child = VisualTreeHelper.GetChild(node, index);
                if (child is ScrollViewer viewer) return viewer;
                if (FindScrollHost(child) is { } found) return found;
            }

            return null;
        }
    }

    private static Button TextAction(string text, string tooltip, Action click)
    {
        var button = new Button
        {
            Content = new TextBlock { Text = text, FontSize = 13 },
            Style = (Style)Application.Current.Resources["FwSecondaryButtonStyle"],
            Height = 32, MinHeight = 32, Padding = new Thickness(12, 0, 12, 0),
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
        ApplyTheme(theme);
    }

    private SolidColorBrush ThemeBrush(string key) => Controls.ThemePalette.Brush(key, _theme);

    private void ApplyTheme(ElementTheme theme)
    {
        // The reader follows the app theme. Every surface below resolves a ThemeResource
        // style, so nothing is captured from the application dictionary at construction time.
        _theme = theme;
        if (Content is FrameworkElement rootContent) rootContent.RequestedTheme = theme;
        var resources = Application.Current.Resources;
        _root.Style = (Style)resources["FwViewerCanvasStyle"];
        _windowRoot.Style = (Style)resources["FwViewerCanvasStyle"];
        _toolbar.Style = (Style)resources["FwDocumentPanelStyle"];
        _textFrame.Style = (Style)resources["FwDocumentPanelStyle"];
        _banner.Style = (Style)resources["FwDocumentNoticeStyle"];
        _mode.Style = (Style)resources["FwCaptionTextStyle"];
        _summary.Style = (Style)resources["FwCaptionTextStyle"];
        _titleBar.RequestedTheme = theme;
    }

    private void ShowBanner(string message, bool error)
    {
        _bannerText.Text = message;
        _banner.BorderBrush = error ? ThemeBrush("FwDangerBrush") : ThemeBrush("FwStrokeBrush");
        _banner.Visibility = Visibility.Visible;
        _toast.IsOpen = false;
    }

    private void ShowToast(string message)
    {
        _toast.Title = message;
        FloatingNotice.Show(_toast, message, repeat: true, compact: true);
    }

    private void CopyAll()
    {
        try
        {
            var package = new Windows.ApplicationModel.DataTransfer.DataPackage();
            package.SetText(_rawText);
            Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(package);
            ShowToast("已复制全文。");
        }
        catch (Exception) { ShowBanner("复制未能完成，请重试。", error: true); }
    }

    private void ToggleRendered()
    {
        _showRendered = !_showRendered;
        ApplyViewMode();
        UpdateCommands();
    }

    private void ToggleTable()
    {
        _showTable = !_showTable;
        ApplyViewMode();
        UpdateCommands();
    }

    private void ToggleWrap()
    {
        _wrapped = !_wrapped;
        _text.TextWrapping = _wrapped ? TextWrapping.Wrap : TextWrapping.NoWrap;
        UpdateCommands();
    }

    private async Task ConfirmDiscardAndCloseAsync()
    {
        _confirmingClose = true;
        UpdateCommands();
        try
        {
            var confirm = new ContentDialog
            {
                XamlRoot = _text.XamlRoot, RequestedTheme = _text.ActualTheme,
                Title = "有未保存的修改", Content = "关闭将放弃本次编辑，且不会写入服务器。",
                PrimaryButtonText = "放弃并关闭", CloseButtonText = "返回编辑", DefaultButton = ContentDialogButton.Close,
            };
            DialogOrdering.FocusCloseButton(confirm);
            var choice = await confirm.ShowAsync();
            if (!_closed && choice == ContentDialogResult.Primary) { _editing = false; Close(); }
        }
        catch (Exception) { if (!_closed) ShowBanner("确认窗口未能打开，请关闭其他对话框后重试。", error: true); }
        finally
        {
            _confirmingClose = false;
            if (!_closed)
            {
                UpdateCommands();
                if (_editing) _text.Focus(FocusState.Programmatic);
            }
        }
    }

    private void UpdateCommands()
    {
        var ready = _loaded && !_closed && !_saving && !_confirmingClose;
        var rendered = _renderScroll.Visibility == Visibility.Visible;
        var table = _tableScroll.Visibility == Visibility.Visible;
        ((TextBlock)_renderButton.Content).Text = rendered ? "源码" : "渲染";
        ((TextBlock)_tableButton.Content).Text = table ? "纯文本" : "表格";
        ((TextBlock)_wrapButton.Content).Text = _wrapped ? "不换行" : "换行";
        _renderButton.Visibility = _isMarkdown && !_editing ? Visibility.Visible : Visibility.Collapsed;
        _renderButton.IsEnabled = ready;
        _tableButton.Visibility = _grid is not null && !_editing ? Visibility.Visible : Visibility.Collapsed;
        _tableButton.IsEnabled = ready;
        _wrapButton.Visibility = !rendered && !table && !_editing ? Visibility.Visible : Visibility.Collapsed;
        _wrapButton.IsEnabled = ready;
        _copyButton.Visibility = !_editing ? Visibility.Visible : Visibility.Collapsed;
        _copyButton.IsEnabled = ready;
        _editButton.Visibility = !_editing ? Visibility.Visible : Visibility.Collapsed;
        _editButton.IsEnabled = ready && _expectation is not null;
        _saveButton.Visibility = _editing ? Visibility.Visible : Visibility.Collapsed;
        _saveButton.IsEnabled = ready && _dirty && _expectation is not null;
        _discardButton.Visibility = _editing ? Visibility.Visible : Visibility.Collapsed;
        _discardButton.IsEnabled = ready;
        if (!_loaded) return;

        var view = rendered ? "Markdown 渲染视图" : table ? "表格视图" : _isMarkdown ? "源码视图" : _isTable ? "纯文本视图" : "纯文本";
        if (_editing) view = _dirty ? "编辑中 · 未保存" : "编辑中";
        _mode.Text = view + (_wrapped && !rendered && !table ? " · 自动换行" : "");
        var access = _editing ? "" : _expectation is null ? " · 只读" : " · 可编辑";
        var summary = $"{_encodingName} · {DownloadPresentationPolicy.FormatBytes(_contentLength)}{access}";
        if (_summary.Text != summary)
        {
            _summary.Text = summary;
            ToolTipService.SetToolTip(_summary, summary);
        }
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
        _emptyState.Visibility = Visibility.Collapsed;
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
        FocusContent();
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
            var result = await _service.SaveAsync(_resource, expected, editedText, _lifetime.Token);
            if (_closed) return;
            _expectation = result.NextExpectation;
            _rawText = editedText;
            _encodingName = "UTF-8";
            _contentLength = result.SavedSize;
            if (_isTable) _grid = CsvGridParser.Parse(_rawText, CsvGridParser.DetectDelimiter(_rawText, _resource.DisplayName));
            _dirty = false;
            _text.IsReadOnly = true;
            _editing = false;
            ApplyViewMode();
            UpdateCommands();
            FocusContent();
            if (result.NextExpectation is null)
                ShowBanner("修改已保存。重新打开后可继续编辑。", error: false);
            else
            {
                _banner.Visibility = Visibility.Collapsed;
                ShowToast("修改已保存。");
            }
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
        _emptyState.Visibility = _rawText.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
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
        var viewport = _tableScroll.ActualWidth > 0 ? _tableScroll.ActualWidth : _textFrame.ActualWidth;
        var columnWidth = grid.TotalColumns == 0 ? 220 : Math.Clamp((viewport - 40) / grid.TotalColumns, 140, 220);
        var columnWidthText = columnWidth.ToString("0.##", System.Globalization.CultureInfo.InvariantCulture);
        var header = new StackPanel { Orientation = Orientation.Horizontal };
        for (int column = 0; column < grid.TotalColumns; column++)
        {
            header.Children.Add(new TextBlock
            {
                Text = column < grid.Rows[0].Length ? grid.Rows[0][column] : "",
                FontWeight = FontWeights.SemiBold,
                FontSize = 13,
                TextWrapping = TextWrapping.NoWrap, TextTrimming = TextTrimming.CharacterEllipsis,
                Width = columnWidth, Padding = new Thickness(12, 8, 12, 8),
            });
        }
        var rows = new List<string[]>();
        for (int index = 1; index < grid.Rows.Count; index++) rows.Add(grid.Rows[index]);
        var cells = new System.Text.StringBuilder();
        for (int column = 0; column < grid.TotalColumns; column++)
            cells.Append("<TextBlock Text=\"{Binding [").Append(column).Append("]}\" Width=\"").Append(columnWidthText).Append("\" FontSize=\"13\" TextWrapping=\"NoWrap\" TextTrimming=\"CharacterEllipsis\" Padding=\"12,6\"/>");
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
            Style = (Style)Application.Current.Resources["FwSubtlePanelStyle"],
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
            _text.TextChanged += (_, _) => { if (_editing && !_saving) { _dirty = !string.Equals(NormalizeLineEndings(_text.Text), NormalizeLineEndings(_rawText), StringComparison.Ordinal); UpdateCommands(); } };
            _banner.Visibility = Visibility.Collapsed;
            ApplyViewMode();
            UpdateCommands();
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

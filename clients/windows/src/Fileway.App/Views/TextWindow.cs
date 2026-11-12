using Fileway.App.Branding;
using Fileway.App.Controls;
using Fileway.App.Windowing;
using Fileway.Core.Contracts;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Data;
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
    private readonly ToggleButton _wrapButton;
    private readonly Button _copyButton;
    private readonly Button _editButton;
    private readonly Button _saveButton;
    private readonly Button _discardButton;
    private readonly Button? _codeOptions;
    private readonly string? _codeLanguage;
    private CodePreview? _codePreview;
    private MarkdownVisualEditor? _markdownEditor;
    private bool _visualEditing;
    private bool _switchingMarkdown;
    private string _visualDraft = "";
    private MenuFlyout? _codeMenu;
    private bool _codeMenuOpen;
    private readonly Grid _root;
    private readonly Grid _windowRoot;
    private bool _editing;
    private bool _dirty;
    private bool _loaded;
    private bool _saving;
    private bool _confirmingClose;
    private long _contentLength;
    private Fileway.Core.Contracts.TextEditExpectation? _expectation;
    private readonly bool _isMarkdown;
    private readonly bool _isTable;
    private readonly string _sourceLabel;
    private string _encodingName = "UTF-8";
    private CsvGridParser.Grid? _grid;
    private string _rawText = "";
    private string _lineEnding = "\r\n";
    private bool _showRendered = true;
    private bool _showTable = true;
    private bool _closed;
    private bool _started;
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
        var extension = Path.GetExtension(resource.DisplayName).ToLowerInvariant();
        _isMarkdown = extension is ".md" or ".markdown";
        _isTable = extension is ".csv" or ".tsv";
        _sourceLabel = SourceLabel(extension);
        _codeLanguage = CodePreview.LanguageFor(extension);
        _titleBar.SetViewerCaption();
        _titleBar.Caption = resource.DisplayName;
        Title = resource.DisplayName + " · " + AppBranding.DisplayName;
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

        _renderButton = ViewerAction("file-code", "查看源码", () => _ = ToggleRenderedAsync());
        _tableButton = ViewerAction("article", "查看纯文本", ToggleTable);
        _wrapButton = new ToggleButton
        {
            Style = SurfaceGeometry.Style("FwSegmentedChoiceStyle"),
            Width = 34, Height = 32, MinHeight = 32, Padding = new Thickness(0), CornerRadius = new CornerRadius(10),
        };
        SetViewerActionIcon(_wrapButton, "text-wrap", "自动换行");
        AutomationProperties.SetAutomationId(_wrapButton, "WrapTextToggle");
        _wrapButton.Checked += (_, _) => { _text.TextWrapping = TextWrapping.Wrap; RefreshCodePreview(); };
        _wrapButton.Unchecked += (_, _) => { _text.TextWrapping = TextWrapping.NoWrap; RefreshCodePreview(); };
        _wrapButton.IsChecked = extension == ".txt";
        _copyButton = ViewerAction("copy", "复制全文", CopyAll);
        _editButton = ViewerAction("pencil-simple", "编辑文本", EnterEditMode);
        _saveButton = ViewerAction("floppy-disk", "保存修改", () => _ = SaveAsync(), "FwPrimaryButtonStyle");
        _discardButton = ViewerAction("x", "放弃修改", DiscardEdit);
        AutomationProperties.SetAutomationId(_copyButton, "CopyTextButton");
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
        if (_codeLanguage is not null)
        {
            _codeOptions = BuildCodeOptions();
            actions.Children.Add(_codeOptions);
        }

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
            if (_saving || _switchingMarkdown) { closingArgs.Cancel = true; return; }
            if (_closed || !_editing || (!_dirty && !_visualEditing)) return;
            closingArgs.Cancel = true;
            if (!_confirmingClose) _ = ConfirmDiscardAndCloseAsync();
        };
        Closed += (_, _) =>
        {
            _closed = true;
            FloatingNotice.Detach(_toast);
            _codePreview?.Dispose();
            _markdownEditor?.Dispose();
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
        if (_markdownEditor is { IsReady: true, View.Visibility: Visibility.Visible } markdown)
        {
            markdown.Focus();
            return;
        }
        if (_codePreview is { IsReady: true, View.Visibility: Visibility.Visible } preview)
        {
            preview.Focus();
            return;
        }
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
            if (_codeMenuOpen) _codeMenu?.Hide();
            else Close();
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

    private static Button ViewerAction(string icon, string label, Action? click = null, string style = "FwSecondaryButtonStyle")
    {
        var button = new Button
        {
            Style = SurfaceGeometry.Style(style),
            Width = 34, Height = 32, MinHeight = 32, Padding = new Thickness(0),
        };
        SetViewerActionIcon(button, icon, label);
        if (click is not null) button.Click += (_, _) => click();
        return button;
    }

    private static void SetViewerActionIcon(ContentControl control, string icon, string label)
    {
        if (control.Content is LucideActionIcon existing) existing.IconName = icon;
        else
        {
            var glyph = LucideIcon.Action(icon);
            glyph.Width = glyph.Height = 16;
            glyph.SetBinding(IconElement.ForegroundProperty, new Binding { Source = control, Path = new PropertyPath(nameof(Control.Foreground)) });
            control.Content = glyph;
        }
        ToolTipService.SetToolTip(control, label);
        AutomationProperties.SetName(control, label);
    }

    private static string SourceLabel(string extension) => extension switch
    {
        ".json" => "JSON",
        ".xml" => "XML",
        ".yaml" or ".yml" => "YAML",
        ".toml" => "TOML",
        ".ini" => "INI",
        ".log" => "日志",
        ".cs" => "C# 源码",
        ".py" => "Python 源码",
        ".go" => "Go 源码",
        ".js" => "JavaScript 源码",
        ".ts" => "TypeScript 源码",
        ".c" => "C 源码",
        ".cpp" or ".hpp" => "C++ 源码",
        ".h" => "C / C++ 头文件",
        ".java" => "Java 源码",
        ".kt" => "Kotlin 源码",
        ".rs" => "Rust 源码",
        ".ps1" => "PowerShell 脚本",
        ".sh" => "Shell 脚本",
        ".bat" or ".cmd" => "批处理脚本",
        ".sql" => "SQL",
        ".html" or ".htm" => "HTML 源码",
        ".css" => "CSS 源码",
        _ => "纯文本",
    };

    public ValueTask DisposeAsync()
    {
        // The Closed handler owns teardown; this covers an explicit dispose before closing.
        if (!_closed)
        {
            _closed = true;
            _codePreview?.Dispose();
            _markdownEditor?.Dispose();
            _lifetime.Cancel();
            _chrome.Dispose();
            _lifetime.Dispose();
        }
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

    private async Task ToggleRenderedAsync()
    {
        if (!_editing)
        {
            _showRendered = !_showRendered;
            ApplyViewMode();
            UpdateCommands();
            return;
        }
        if (_switchingMarkdown || _saving || _confirmingClose || _markdownEditor is not { IsConnected: true } editor) return;
        _switchingMarkdown = true;
        _text.IsReadOnly = true;
        UpdateCommands();
        try
        {
            if (_visualEditing)
            {
                editor.SetEditable(false);
                var draft = await editor.SnapshotAsync(_lifetime.Token);
                if (_closed) return;
                _visualEditing = false;
                _showRendered = false;
                _text.Text = draft;
                _dirty = IsEdited(draft);
                SetMarkdownSurfaceVisible(false);
                _textFrame.Visibility = Visibility.Visible;
            }
            else
            {
                var draft = NormalizeLineEndings(_text.Text);
                if (!await editor.LoadAsync(draft, _lifetime.Token))
                {
                    if (!_closed) ShowBanner("此文档有未能往返保留的结构。可继续在源码中编辑。", error: false);
                    return;
                }
                if (_closed) return;
                _visualDraft = draft;
                _visualEditing = true;
                _showRendered = true;
                _dirty = IsEdited(draft);
                SetMarkdownSurfaceVisible(true);
                _textFrame.Visibility = Visibility.Collapsed;
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception) { if (!_closed) ShowBanner("未能切换编辑视图，当前草稿已保留。", error: true); }
        finally
        {
            _switchingMarkdown = false;
            if (!_closed) { RestoreEditingInput(); UpdateCommands(); }
        }
    }

    private void ToggleTable()
    {
        _showTable = !_showTable;
        ApplyViewMode();
        UpdateCommands();
    }

    private async Task ConfirmDiscardAndCloseAsync()
    {
        _confirmingClose = true;
        UpdateCommands();
        try
        {
            if (_visualEditing && _markdownEditor is { } editor)
            {
                editor.SetEditable(false);
                _visualDraft = await editor.SnapshotAsync(_lifetime.Token);
                _dirty = IsEdited(_visualDraft);
                if (!_dirty) { _editing = false; Close(); return; }
            }
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
        catch (Exception) { if (!_closed) ShowBanner("未能核对当前草稿，请重试关闭。", error: true); }
        finally
        {
            _confirmingClose = false;
            if (!_closed)
            {
                UpdateCommands();
                if (_editing) RestoreEditingInput();
            }
        }
    }

    private void UpdateCommands()
    {
        var ready = _loaded && !_closed && !_saving && !_confirmingClose && !_switchingMarkdown;
        var rendered = _renderScroll.Visibility == Visibility.Visible || _markdownEditor?.View.Visibility == Visibility.Visible;
        var table = _tableScroll.Visibility == Visibility.Visible;
        SetViewerActionIcon(_renderButton, rendered ? "file-code" : "book-open", rendered ? "查看源码" : "查看渲染");
        SetViewerActionIcon(_tableButton, table ? "article" : "table-2", table ? "查看纯文本" : "查看表格");
        _renderButton.Visibility = _isMarkdown && (!_editing || _markdownEditor is { IsConnected: true })
            ? Visibility.Visible : Visibility.Collapsed;
        _renderButton.IsEnabled = ready;
        _tableButton.Visibility = _grid is not null && !_editing ? Visibility.Visible : Visibility.Collapsed;
        _tableButton.IsEnabled = ready;
        _wrapButton.Visibility = !rendered && !table && !_editing ? Visibility.Visible : Visibility.Collapsed;
        _wrapButton.IsEnabled = ready;
        if (_codeOptions is { } codeOptions)
        {
            codeOptions.Visibility = !_editing && _codePreview is { IsReady: true } && _rawText.Length != 0
                ? Visibility.Visible : Visibility.Collapsed;
            codeOptions.IsEnabled = ready;
        }
        _copyButton.Visibility = !_editing ? Visibility.Visible : Visibility.Collapsed;
        _copyButton.IsEnabled = ready;
        _editButton.Visibility = !_editing ? Visibility.Visible : Visibility.Collapsed;
        _editButton.IsEnabled = ready && _expectation is not null;
        _saveButton.Visibility = _editing ? Visibility.Visible : Visibility.Collapsed;
        _saveButton.IsEnabled = ready && _dirty && _expectation is not null;
        _discardButton.Visibility = _editing ? Visibility.Visible : Visibility.Collapsed;
        _discardButton.IsEnabled = ready;
        if (!_loaded) return;

        var view = rendered ? "Markdown 渲染视图" : table ? "表格视图" : _isMarkdown ? "源码视图" : _isTable ? "纯文本视图" : _sourceLabel;
        if (_editing) view = _dirty ? "编辑中 · 未保存" : "编辑中";
        _mode.Text = view;
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
        if (_showRendered && _markdownEditor is { IsReady: true, View.Visibility: Visibility.Visible } markdown)
        {
            _visualEditing = true;
            _visualDraft = _rawText;
            markdown.SetEditable(true);
            UpdateCommands();
            markdown.Focus();
            return;
        }
        _text.IsReadOnly = false;
        // Editing happens in plain text; rendering/table projections are suspended.
        _textFrame.Visibility = Visibility.Visible;
        if (_codePreview is { } code) code.View.Visibility = Visibility.Collapsed;
        _renderScroll.Visibility = Visibility.Collapsed;
        _tableScroll.Visibility = Visibility.Collapsed;
        UpdateCommands();
        _text.Focus(FocusState.Programmatic);
    }

    private void DiscardEdit()
    {
        if (!_editing || _saving || _confirmingClose || _switchingMarkdown) return;
        _text.IsReadOnly = true;
        _visualEditing = false;
        _editing = false;
        _dirty = false;
        _text.Text = _rawText;
        if (_markdownEditor is not null) _ = ReloadMarkdownAsync(_rawText);
        ApplyViewMode();
        UpdateCommands();
        _banner.Visibility = Visibility.Collapsed;
        FocusContent();
    }

    private bool ContentEncodingIsLegacy() => _encodingName is not ("UTF-8" or "UTF-8 BOM");

    private string NormalizeLineEndings(string text) => text.Replace("\r\n", "\n", StringComparison.Ordinal)
        .Replace('\r', '\n').Replace("\n", _lineEnding, StringComparison.Ordinal);

    private bool IsEdited(string draft) => !string.Equals(NormalizeLineEndings(draft),
        NormalizeLineEndings(_rawText), StringComparison.Ordinal);

    private async Task SaveAsync()
    {
        if (!_loaded || !_editing || (!_dirty && !_visualEditing) || _expectation is not { } expected ||
            _closed || _saving || _confirmingClose || _switchingMarkdown) return;
        _saving = true;
        _text.IsReadOnly = true;
        UpdateCommands();
        try
        {
            var editedText = _text.Text;
            if (_visualEditing && _markdownEditor is { } editor)
            {
                editor.SetEditable(false);
                editedText = _visualDraft = await editor.SnapshotAsync(_lifetime.Token);
                _dirty = IsEdited(editedText);
                if (!_dirty) return;
            }
            editedText = NormalizeLineEndings(editedText);
            bool reencode = ContentEncodingIsLegacy();
            var panel = new StackPanel { Spacing = 12, MinWidth = 320, MaxWidth = 420 };
            panel.Children.Add(new TextBlock { Text = "保存对服务器上文件的修改？" + (reencode ? "\n原文件不是 UTF-8：保存将以 UTF-8 重写。" : ""), TextWrapping = TextWrapping.Wrap });
            panel.Children.Add(new TextBlock { Text = "若期间文件已在服务器上被修改，保存会被拒绝且不会覆盖。", FontSize = 12, Opacity = 0.75, TextWrapping = TextWrapping.Wrap });
            var confirm = new ContentDialog { XamlRoot = _text.XamlRoot, RequestedTheme = _text.ActualTheme, Title = "保存修改", Content = panel, PrimaryButtonText = "保存", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary };
            var choice = await confirm.ShowAsync();
            if (choice != ContentDialogResult.Primary || !_editing || _closed) return;
            ShowBanner("正在保存…", error: false);
            var result = await _service.SaveAsync(_resource, expected, editedText, _lifetime.Token);
            if (_closed) return;
            _expectation = result.NextExpectation;
            _rawText = editedText;
            _encodingName = "UTF-8";
            _contentLength = result.SavedSize;
            if (_isTable) _grid = CsvGridParser.Parse(_rawText, CsvGridParser.DetectDelimiter(_rawText, _resource.DisplayName));
            _dirty = false;
            _text.IsReadOnly = true;
            _visualEditing = false;
            _editing = false;
            _text.Text = _rawText;
            if (_markdownEditor is not null) _ = ReloadMarkdownAsync(_rawText);
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
            var message = error switch
            {
                FilewayException fileway => fileway.Error.Message,
                TimeoutException when _visualEditing => "未能取得当前 Markdown 草稿，请重试保存。",
                _ => "保存未能完成，请检查连接后重试。",
            };
            ShowBanner(message, error: true);
            UpdateCommands();
        }
        finally
        {
            _saving = false;
            if (!_closed)
            {
                _text.IsReadOnly = !_editing || _visualEditing;
                if (_visualEditing) RestoreEditingInput();
                UpdateCommands();
            }
        }
    }

    /// <summary>Renders the bounded markdown subset natively; plain text stays one click away.</summary>
    private void ApplyViewMode()
    {
        if (_editing) return;
        var useVisualMarkdown = _showRendered && _rawText.Length != 0 && _markdownEditor is { IsReady: true };
        SetMarkdownSurfaceVisible(useVisualMarkdown);
        if (useVisualMarkdown)
        {
            _emptyState.Visibility = _textFrame.Visibility = _renderScroll.Visibility = _tableScroll.Visibility = Visibility.Collapsed;
            return;
        }
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
            UpdateCodeVisibility();
            RefreshCodePreview();
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
            if (_isTable)
            {
                char delimiter = CsvGridParser.DetectDelimiter(content.Text, _resource.DisplayName);
                _grid = CsvGridParser.Parse(content.Text, delimiter);
            }
            _expectation = content.Edit;
            _encodingName = content.EncodingName;
            _text.TextChanged += (_, _) => { if (_editing && !_saving && !_visualEditing) { _dirty = IsEdited(_text.Text); UpdateCommands(); } };
            _banner.Visibility = Visibility.Collapsed;
            if (_codeLanguage is { } language && content.Text.Length != 0 && !new Windows.UI.ViewManagement.AccessibilitySettings().HighContrast)
                StartCodePreview(language);
            if (_isMarkdown && !new Windows.UI.ViewManagement.AccessibilitySettings().HighContrast)
                StartMarkdownEditor();
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

    private Button BuildCodeOptions()
    {
        var syntax = new ToggleMenuFlyoutItem { Text = "语法着色", IsChecked = PresentationPreferences.CodeSyntaxHighlighting };
        var numbers = new ToggleMenuFlyoutItem { Text = "显示行号", IsChecked = PresentationPreferences.CodeLineNumbers };
        AutomationProperties.SetAutomationId(syntax, "CodeSyntaxHighlighting");
        AutomationProperties.SetAutomationId(numbers, "CodeLineNumbers");
        bool synchronizing = false;
        syntax.RegisterPropertyChangedCallback(ToggleMenuFlyoutItem.IsCheckedProperty,
            (_, _) => { if (!synchronizing) SaveCodeOption(() => PresentationPreferences.CodeSyntaxHighlighting = syntax.IsChecked); });
        numbers.RegisterPropertyChangedCallback(ToggleMenuFlyoutItem.IsCheckedProperty,
            (_, _) => { if (!synchronizing) SaveCodeOption(() => PresentationPreferences.CodeLineNumbers = numbers.IsChecked); });
        var menu = new MenuFlyout();
        menu.Items.Add(syntax);
        menu.Items.Add(numbers);
        menu.Opening += (_, _) =>
        {
            _codeMenuOpen = true;
            synchronizing = true;
            syntax.IsChecked = PresentationPreferences.CodeSyntaxHighlighting;
            numbers.IsChecked = PresentationPreferences.CodeLineNumbers;
            synchronizing = false;
            RefreshCodePreview();
        };
        menu.Closed += (_, _) => _codeMenuOpen = false;
        _codeMenu = menu;
        var button = ViewerAction("gear", "代码显示设置");
        button.Flyout = menu;
        button.Visibility = Visibility.Collapsed;
        AutomationProperties.SetAutomationId(button, "CodeDisplayOptions");
        return button;
    }

    private void SaveCodeOption(Action save)
    {
        try { save(); }
        catch (Exception) { ShowBanner("代码显示设置未能保存，请重试。", error: true); }
        RefreshCodePreview();
    }

    private void StartCodePreview(string language)
    {
        var preview = new CodePreview(language);
        _codePreview = preview;
        _editor.Children.Insert(1, preview.View);
        _windowRoot.ActualThemeChanged += (_, _) => RefreshCodePreview();
        preview.Ready += () =>
        {
            if (_closed) return;
            UpdateCodeVisibility();
            UpdateCommands();
            if (!_editing && _root.XamlRoot is { } root && ReferenceEquals(FocusManager.GetFocusedElement(root), _root))
                preview.Focus();
        };
        preview.Failed += () =>
        {
            if (_closed || !ReferenceEquals(_codePreview, preview)) return;
            _codePreview = null;
            _editor.Children.Remove(preview.View);
            preview.Dispose();
            _textFrame.Visibility = Visibility.Visible;
            UpdateCommands();
        };
        preview.CloseRequested += () => { if (!_closed && !_editing) Close(); };
        _ = preview.InitializeAsync();
    }

    private void UpdateCodeVisibility()
    {
        if (_codePreview is not { } preview) return;
        var visible = !_editing && _rawText.Length != 0;
        preview.View.Visibility = visible ? Visibility.Visible : Visibility.Collapsed;
        preview.View.IsHitTestVisible = preview.View.IsTabStop = visible && preview.IsReady;
        preview.View.Opacity = preview.IsReady ? 1 : 0;
        if (visible && preview.IsReady) _textFrame.Visibility = Visibility.Collapsed;
    }

    private void RefreshCodePreview()
    {
        _codePreview?.SetDocument(_rawText, _windowRoot.ActualTheme == ElementTheme.Dark,
            PresentationPreferences.CodeSyntaxHighlighting, PresentationPreferences.CodeLineNumbers, _wrapButton.IsChecked == true);
    }

    private void StartMarkdownEditor()
    {
        var editor = new MarkdownVisualEditor();
        _markdownEditor = editor;
        _editor.Children.Insert(1, editor.View);
        editor.SetTheme(_windowRoot.ActualTheme == ElementTheme.Dark);
        _windowRoot.ActualThemeChanged += (_, _) => _markdownEditor?.SetTheme(_windowRoot.ActualTheme == ElementTheme.Dark);
        editor.DraftChanged += draft =>
        {
            if (_closed || !_visualEditing || _saving) return;
            _visualDraft = draft;
            _dirty = IsEdited(draft);
            UpdateCommands();
        };
        editor.CloseRequested += () => { if (!_closed) Close(); };
        editor.SaveRequested += () => { if (_visualEditing) _ = SaveAsync(); };
        editor.LinkRequested += async uri =>
        {
            try { if (!_closed) await Windows.System.Launcher.LaunchUriAsync(uri); }
            catch (Exception) { if (!_closed) ShowBanner("链接未能打开。", error: true); }
        };
        editor.Failed += () =>
        {
            if (_closed || !ReferenceEquals(_markdownEditor, editor)) return;
            _markdownEditor = null;
            _editor.Children.Remove(editor.View);
            editor.Dispose();
            if (_visualEditing)
            {
                _visualEditing = false;
                _showRendered = false;
                _text.Text = _visualDraft;
                _dirty = IsEdited(_visualDraft);
                _textFrame.Visibility = Visibility.Visible;
                _text.IsReadOnly = _saving || _confirmingClose || _switchingMarkdown;
                ShowBanner("图形编辑已停止，已在源码中保留最近同步的草稿。", error: true);
            }
            else ApplyViewMode();
            UpdateCommands();
        };
        _ = ReloadMarkdownAsync(_rawText);
    }

    private async Task ReloadMarkdownAsync(string text)
    {
        if (_markdownEditor is not { } editor || _closed) return;
        SetMarkdownSurfaceVisible(false);
        try
        {
            if (!await editor.LoadAsync(text, _lifetime.Token) || _closed) return;
            if (!_editing) ApplyViewMode();
            UpdateCommands();
            if (_root.XamlRoot is { } root && ReferenceEquals(FocusManager.GetFocusedElement(root), _root))
                FocusContent();
        }
        catch (OperationCanceledException) { }
        catch (Exception error) { System.Diagnostics.Debug.WriteLine($"Markdown preview: {error.GetType().Name}"); }
    }

    private void SetMarkdownSurfaceVisible(bool visible)
    {
        if (_markdownEditor is not { } editor) return;
        editor.View.Visibility = visible || !editor.IsConnected ? Visibility.Visible : Visibility.Collapsed;
        editor.View.IsTabStop = editor.View.IsHitTestVisible = visible;
        editor.View.Opacity = visible ? 1 : 0;
    }

    private void RestoreEditingInput()
    {
        if (!_editing) return;
        if (_visualEditing && _markdownEditor is { } editor)
        {
            editor.SetEditable(true);
            editor.Focus();
        }
        else
        {
            _text.IsReadOnly = false;
            _text.Focus(FocusState.Programmatic);
        }
    }
}

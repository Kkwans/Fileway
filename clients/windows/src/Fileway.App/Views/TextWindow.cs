using Fileway.App.Branding;
using Fileway.App.Controls;
using Fileway.App.Windowing;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
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
        IsSpellCheckEnabled = false, IsTextPredictionEnabled = false,
        HorizontalAlignment = HorizontalAlignment.Stretch, VerticalAlignment = VerticalAlignment.Stretch,
    };
    private readonly InfoBar _notice = new() { IsOpen = true, IsClosable = false, Message = "正在加载文本…" };
    private readonly TextBlock _summary = new() { FontSize = 12, Opacity = 0.7 };
    private readonly ScrollViewer _renderScroll = new() { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, Padding = new Thickness(4, 0, 8, 0) };
    private readonly StackPanel _renderHost = new() { Spacing = 2 };
    private readonly ScrollViewer _tableScroll = new()
    {
        VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
        Visibility = Visibility.Collapsed,
    };
    private readonly Grid _editor = new() { MinHeight = 200 };
    private AppBarButton? _editButton;
    private AppBarButton? _saveButton;
    private AppBarButton? _discardButton;
    private AppBarToggleButton? _renderToggle;
    private AppBarToggleButton? _tableToggle;
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

    public TextWindow(ITextContentService service, RemoteResourceRef resource)
    {
        _service = service;
        _resource = resource;
        Title = _titleBar.Caption = resource.DisplayName + " · " + AppBranding.DisplayName;
        SystemBackdrop = new MicaBackdrop();
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1000, 760));

        var commands = new CommandBar { DefaultLabelPosition = CommandBarDefaultLabelPosition.Right, Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent) };
        var edit = new AppBarButton { Label = "编辑", IsEnabled = false };
        var save = new AppBarButton { Label = "保存", IsEnabled = false };
        var discard = new AppBarButton { Label = "放弃修改", IsEnabled = false };
        var renderToggle = new AppBarToggleButton { Label = "渲染视图" };
        var wrap = new AppBarButton { Label = "自动换行", Icon = new FontIcon { Glyph = "" } };
        var copy = new AppBarButton { Label = "复制全文", Icon = new SymbolIcon(Symbol.Copy) };
        wrap.Click += (_, _) =>
        {
            _wrapped = !_wrapped;
            _text.TextWrapping = _wrapped ? TextWrapping.Wrap : TextWrapping.NoWrap;
            ToolTipService.SetToolTip(wrap, _wrapped ? "当前：自动换行" : "当前：不换行");
        };
        copy.Click += async (_, _) =>
        {
            try
            {
                var package = new Windows.ApplicationModel.DataTransfer.DataPackage();
                package.SetText(_text.Text);
                Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(package);
            }
            catch (Exception) { _notice.Severity = InfoBarSeverity.Warning; _notice.Message = "复制未能完成，请重试。"; _notice.IsOpen = true; }
        };
        var tableToggle = new AppBarToggleButton { Label = "表格视图" };
        tableToggle.Click += (_, _) =>
        {
            _showTable = tableToggle.IsChecked == true;
            ApplyViewMode();
        };
        tableToggle.Visibility = Visibility.Collapsed;
        _tableToggle = tableToggle;
        commands.PrimaryCommands.Add(edit);
        commands.PrimaryCommands.Add(save);
        commands.PrimaryCommands.Add(discard);
        commands.PrimaryCommands.Add(renderToggle);
        commands.PrimaryCommands.Add(tableToggle);
        commands.PrimaryCommands.Add(wrap);
        _editButton = edit; _saveButton = save; _discardButton = discard;
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetAutomationId(_text, "TextEditor");
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(_text, "文本内容");
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetAutomationId(edit, "EditTextButton");
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetAutomationId(save, "SaveTextButton");
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetAutomationId(discard, "DiscardTextButton");
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetAutomationId(renderToggle, "RenderTextToggle");
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetAutomationId(tableToggle, "TableTextToggle");
        edit.Click += (_, _) => EnterEditMode();
        save.Click += (_, _) => _ = SaveAsync();
        discard.Click += (_, _) => DiscardEdit();
        commands.PrimaryCommands.Add(copy);
        renderToggle.Click += (_, _) =>
        {
            _showRendered = renderToggle.IsChecked == true;
            ApplyViewMode();
        };
        renderToggle.Visibility = Visibility.Collapsed;
        _renderToggle = renderToggle;

        var root = new Grid { Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent), Padding = new Thickness(16, 12, 16, 16), RowSpacing = 10 };
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition());
        root.Children.Add(commands);
        Grid.SetRow(_notice, 1);
        root.Children.Add(_notice);
        Grid.SetRow(_summary, 2);
        root.Children.Add(_summary);
        Grid.SetRow(_editor, 3);
        _editor.Children.Add(_text);
        _renderScroll.Content = _renderHost;
        _editor.Children.Add(_renderScroll);
        _editor.Children.Add(_tableScroll);
        root.Children.Add(_editor);

        var windowRoot = new Grid();
        windowRoot.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        windowRoot.RowDefinitions.Add(new RowDefinition());
        windowRoot.Children.Add(_titleBar);
        Grid.SetRow(root, 1);
        windowRoot.Children.Add(root);
        Content = windowRoot;
        _chrome = new FramelessWindowChrome(this, _titleBar);
        root.Loaded += async (_, _) => { if (_started) return; _started = true; await LoadAsync(); };
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
            _lifetime.Cancel();
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

    private async Task ConfirmDiscardAndCloseAsync()
    {
        _confirmingClose = true;
        UpdateEditCommands();
        try
        {
            var confirm = new ContentDialog { XamlRoot = _text.XamlRoot, RequestedTheme = _text.ActualTheme, Title = "有未保存的修改", Content = "关闭将放弃本次编辑，且不会写入服务器。", PrimaryButtonText = "放弃并关闭", CloseButtonText = "返回编辑", DefaultButton = ContentDialogButton.Close };
            var choice = await confirm.ShowAsync();
            if (!_closed && choice == ContentDialogResult.Primary) { _editing = false; Close(); }
        }
        catch (Exception) { if (!_closed) { _notice.Severity = InfoBarSeverity.Warning; _notice.Message = "确认窗口未能打开，请关闭其他对话框后重试。"; _notice.IsOpen = true; } }
        finally { _confirmingClose = false; if (!_closed) UpdateEditCommands(); }
    }

    public void SetTheme(ElementTheme theme)
    {
        if (!_closed && Content is FrameworkElement root) root.RequestedTheme = theme;
    }

    private void UpdateEditCommands()
    {
        var ready = _loaded && !_closed && !_saving && !_confirmingClose;
        if (_editButton is { } edit) edit.IsEnabled = ready && !_editing && _expectation is not null;
        if (_saveButton is { } save) save.IsEnabled = ready && _editing && _dirty && _expectation is not null;
        if (_discardButton is { } discard) { discard.IsEnabled = ready && _editing; discard.Label = _dirty ? "放弃修改" : "结束编辑"; }
        if (_renderToggle is { } render) render.IsEnabled = ready && !_editing;
        if (_tableToggle is { } table) table.IsEnabled = ready && !_editing;
        var mode = _editing ? (_dirty ? "编辑中 · 未保存" : "编辑中") : (_expectation is null ? "只读预览" : "可编辑预览");
        if (_loaded) _summary.Text = $"{_encodingName} · {DownloadPresentationPolicy.FormatBytes(_contentLength)} · {mode}";
    }

    private void EnterEditMode()
    {
        if (!_loaded || _editing || _closed || _saving || _confirmingClose) return;
        if (_expectation is null)
        {
            _notice.Severity = InfoBarSeverity.Informational;
            _notice.Message = "此文件暂不可编辑（服务器状态未取得或读取期间已变化），请重新打开。";
            _notice.IsOpen = true;
            return;
        }
        _editing = true;
        _dirty = false;
        _text.IsReadOnly = false;
        // Editing happens in plain text; rendering/table projections are suspended.
        _text.Visibility = Visibility.Visible;
        _renderScroll.Visibility = Visibility.Collapsed;
        _tableScroll.Visibility = Visibility.Collapsed;
        UpdateEditCommands();
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
        UpdateEditCommands();
        _notice.IsOpen = false;
    }

    private bool ContentEncodingIsLegacy() => _encodingName is not ("UTF-8" or "UTF-8 BOM");

    private string NormalizeLineEndings(string text) => text.Replace("\r\n", "\n", StringComparison.Ordinal)
        .Replace('\r', '\n').Replace("\n", _lineEnding, StringComparison.Ordinal);

    private async Task SaveAsync()
    {
        if (!_loaded || !_editing || !_dirty || _expectation is not { } expected || _closed || _saving || _confirmingClose) return;
        _saving = true;
        _text.IsReadOnly = true;
        UpdateEditCommands();
        try
        {
            bool reencode = ContentEncodingIsLegacy();
            var panel = new StackPanel { Spacing = 12, MinWidth = 320, MaxWidth = 420 };
            panel.Children.Add(new TextBlock { Text = "保存对服务器上文件的修改？" + (reencode ? "\n原文件不是 UTF-8：保存将以 UTF-8 重写。" : ""), TextWrapping = TextWrapping.Wrap });
            panel.Children.Add(new TextBlock { Text = "若期间文件已在服务器上被修改，保存会被拒绝且不会覆盖。", FontSize = 12, Opacity = 0.75, TextWrapping = TextWrapping.Wrap });
            var confirm = new ContentDialog { XamlRoot = _text.XamlRoot, RequestedTheme = _text.ActualTheme, Title = "保存修改", Content = panel, PrimaryButtonText = "保存", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary };
            var choice = await confirm.ShowAsync();
            if (choice != ContentDialogResult.Primary || !_editing || _closed) return;
            _notice.Severity = InfoBarSeverity.Informational;
            _notice.Message = "正在保存…";
            _notice.IsOpen = true;
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
            UpdateEditCommands();
            _notice.Severity = InfoBarSeverity.Success;
            _notice.Message = "修改已保存。";
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (_closed) return;
            if (error is FilewayException { Error.Code: ErrorCode.SourceChanged or ErrorCode.OutcomeUnknown }) _expectation = null;
            var message = error is FilewayException fileway ? fileway.Error.Message : "保存未能完成，请检查连接后重试。";
            _notice.Severity = InfoBarSeverity.Error;
            _notice.Message = message;
            UpdateEditCommands();
        }
        finally
        {
            _saving = false;
            if (!_closed) { _text.IsReadOnly = !_editing; UpdateEditCommands(); }
        }
    }

    /// <summary>Renders the bounded markdown subset natively; plain text stays one click away.</summary>
    private void ApplyViewMode()
    {
        if (_editing) return;
        if (_isTable && _showTable && _grid is { } grid)
        {
            _text.Visibility = Visibility.Collapsed;
            _renderScroll.Visibility = Visibility.Collapsed;
            _tableScroll.Visibility = Visibility.Visible;
            BuildTable(grid);
            return;
        }
        if (!_isMarkdown || !_showRendered)
        {
            _text.Visibility = Visibility.Visible;
            _renderScroll.Visibility = Visibility.Collapsed;
            _tableScroll.Visibility = Visibility.Collapsed;
            return;
        }
        _text.Visibility = Visibility.Collapsed;
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
            _text.Visibility = Visibility.Visible;
            _renderScroll.Visibility = Visibility.Collapsed;
            _tableScroll.Visibility = Visibility.Collapsed;
            if (_renderToggle is { } toggle) toggle.IsChecked = false;
        }
    }

    private void BuildTable(CsvGridParser.Grid grid)
    {
        if (_tableScroll.Content is Grid built) { _tableScroll.Content = null; built.Children.Clear(); }
        var layout = new Grid { ColumnSpacing = 0 };
        for (int column = 0; column < grid.TotalColumns; column++)
            layout.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(200) });
        var header = new StackPanel { Orientation = Orientation.Horizontal };
        for (int column = 0; column < grid.TotalColumns; column++)
        {
            header.Children.Add(new TextBlock
            {
                Text = column < grid.Rows[0].Length ? grid.Rows[0][column] : "",
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                TextWrapping = TextWrapping.NoWrap, TextTrimming = TextTrimming.CharacterEllipsis,
                Width = 200, Padding = new Thickness(8, 6, 8, 6),
            });
        }
        var rows = new List<string[]>();
        for (int index = 1; index < grid.Rows.Count; index++) rows.Add(grid.Rows[index]);
        // WinUI 3 has no per-container hook; a generated DataTemplate binds each column by index
        // and ListView virtualization keeps bounded rows cheap.
        var cells = new System.Text.StringBuilder();
        for (int column = 0; column < grid.TotalColumns; column++)
            cells.Append("<TextBlock Text=\"{Binding [").Append(column).Append("]}\" Width=\"200\" TextWrapping=\"NoWrap\" TextTrimming=\"CharacterEllipsis\" Padding=\"8,4\"/>");
        string templateXaml = "<DataTemplate" +
            " xmlns=\"http://schemas.microsoft.com/winfx/2006/xaml/presentation\">" +
            "<StackPanel Orientation=\"Horizontal\">" + cells + "</StackPanel></DataTemplate>";
        var list = new ListView { ItemsSource = rows, ItemTemplate = (DataTemplate)Microsoft.UI.Xaml.Markup.XamlReader.Load(templateXaml) };
        var table = new StackPanel { Spacing = 0 };
        table.Children.Add(new Border
        {
            Background = (Brush)Microsoft.UI.Xaml.Application.Current.Resources["LayerFillColorDefaultBrush"],
            Child = header,
        });
        table.Children.Add(list);
        string note = (grid.RowsTruncated ? $"已截取前 {grid.Rows.Count} 行；" : "") +
            (grid.TotalColumns >= CsvGridParser.MaximumColumns ? $"仅显示前 {grid.TotalColumns} 列；" : "") +
            $"共 {grid.TotalRows} 行 × 至少 {grid.TotalColumns} 列";
        table.Children.Add(new TextBlock { Text = note, FontSize = 12, Opacity = 0.7, Margin = new Thickness(8, 6, 0, 6) });
        Grid.SetColumnSpan(table, grid.TotalColumns);
        layout.Children.Add(table);
        _tableScroll.Content = layout;
    }

    private async Task LoadAsync()
    {
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
            if (_renderToggle is { } toggle) toggle.Visibility = _isMarkdown ? Visibility.Visible : Visibility.Collapsed;
            _expectation = content.Edit;
            _encodingName = content.EncodingName;
            UpdateEditCommands();
            _text.TextChanged += (_, _) => { if (_editing && !_saving) { _dirty = !string.Equals(NormalizeLineEndings(_text.Text), NormalizeLineEndings(_rawText), StringComparison.Ordinal); UpdateEditCommands(); } };
            if (_tableToggle is { } tableButton) tableButton.Visibility = _grid is not null ? Visibility.Visible : Visibility.Collapsed;
            if (_renderToggle is { } render) render.IsChecked = _showRendered;
            if (_tableToggle is { } table) table.IsChecked = _showTable;
            _notice.IsOpen = false;
            ApplyViewMode();
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (_closed) return;
            var message = error is FilewayException fileway ? fileway.Error.Message : "文本未能加载，请检查连接后重试。";
            _notice.Severity = InfoBarSeverity.Error;
            _notice.Message = message;
        }
    }
}

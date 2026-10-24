using Fileway.App.Branding;
using Fileway.App.Controls;
using Fileway.App.Windowing;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Fileway.App.Views;

/// <summary>
/// Read-only viewer for remote text, code and (for now) Markdown-as-plain-text. One bounded
/// decoded load per open; no editing, no write authority, no fabricated rendering.
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
    private readonly Grid _editor = new() { MinHeight = 200 };
    private AppBarToggleButton? _renderToggle;
    private bool _isMarkdown;
    private string _rawText = "";
    private bool _showRendered = true;
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
        commands.PrimaryCommands.Add(renderToggle);
        commands.PrimaryCommands.Add(wrap);
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

    public void SetTheme(ElementTheme theme)
    {
        if (!_closed && Content is FrameworkElement root) root.RequestedTheme = theme;
    }

    /// <summary>Renders the bounded markdown subset natively; plain text stays one click away.</summary>
    private void ApplyViewMode()
    {
        if (!_isMarkdown || !_showRendered)
        {
            _text.Visibility = Visibility.Visible;
            _renderScroll.Visibility = Visibility.Collapsed;
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
            if (_renderToggle is { } toggle) toggle.IsChecked = false;
        }
    }

    private async Task LoadAsync()
    {
        try
        {
            var content = await _service.OpenAsync(_resource, _lifetime.Token);
            if (_closed) return;
            _text.Text = content.Text;
            _rawText = content.Text;
            _isMarkdown = _resource.DisplayName.EndsWith(".md", StringComparison.OrdinalIgnoreCase) ||
                _resource.DisplayName.EndsWith(".markdown", StringComparison.OrdinalIgnoreCase);
            if (_renderToggle is { } toggle) toggle.Visibility = _isMarkdown ? Visibility.Visible : Visibility.Collapsed;
            _summary.Text = $"{content.EncodingName} · {DownloadPresentationPolicy.FormatBytes(content.DeclaredLength)} · 只读预览";
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

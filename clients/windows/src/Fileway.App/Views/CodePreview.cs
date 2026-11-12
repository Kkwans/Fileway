using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.Web.WebView2.Core;

namespace Fileway.App.Views;

internal sealed class CodePreview : IDisposable
{
    private const string Host = "fileway-code.local";
    private const string Page = "https://" + Host + "/preview.html";
    private static readonly Lazy<Task<CoreWebView2Environment>> Environment = new(() =>
        CoreWebView2Environment.CreateWithOptionsAsync(null, Path.Combine(App.DataDirectory(), "code-preview-webview2"), null).AsTask());
    private readonly string _language;
    private CoreWebView2? _core;
    private string? _text;
    private string? _sentText;
    private bool _dark, _highlight, _numbers, _wrap, _connected, _disposed;

    internal WebView2 View { get; } = new() { Opacity = 0, IsHitTestVisible = false, IsTabStop = false };
    internal bool IsReady { get; private set; }
    internal event Action? Ready;
    internal event Action? Failed;
    internal event Action? CloseRequested;

    internal CodePreview(string language)
    {
        _language = language;
        AutomationProperties.SetAutomationId(View, "CodePreviewWebView");
        AutomationProperties.SetName(View, "代码预览");
    }

    internal async Task InitializeAsync()
    {
        try
        {
            var assets = Path.Combine(AppContext.BaseDirectory, "Assets", "CodePreview");
            if (!File.Exists(Path.Combine(assets, "ace", "ace.js"))) { Failed?.Invoke(); return; }
            var environment = await Environment.Value;
            if (_disposed) return;
            await View.EnsureCoreWebView2Async(environment);
            if (_disposed) return;
            var core = View.CoreWebView2;
            _core = core;
            core.Settings.AreHostObjectsAllowed = false;
            core.Settings.AreDevToolsEnabled = false;
            core.Settings.AreDefaultScriptDialogsEnabled = false;
            core.SetVirtualHostNameToFolderMapping(Host, assets, CoreWebView2HostResourceAccessKind.Deny);
            core.NavigationStarting += (_, args) => args.Cancel = args.Uri != Page;
            core.FrameNavigationStarting += (_, args) => args.Cancel = true;
            core.NewWindowRequested += (_, args) => args.Handled = true;
            core.NavigationCompleted += (_, args) => { if (!args.IsSuccess) Failed?.Invoke(); };
            core.ProcessFailed += (_, _) => Failed?.Invoke();
            core.WebMessageReceived += ReceiveMessage;
            View.Source = new Uri(Page);
        }
        catch (Exception error)
        {
            System.Diagnostics.Debug.WriteLine($"Code preview initialization: {error.GetType().Name}");
            if (!_disposed) Failed?.Invoke();
        }
    }

    internal void SetDocument(string text, bool dark, bool highlight, bool numbers, bool wrap)
    {
        _text = text;
        _dark = dark;
        _highlight = highlight;
        _numbers = numbers;
        _wrap = wrap;
        SendDocument();
    }

    private void SendDocument()
    {
        if (!_connected || _disposed || _core is not { Source: Page } core || _text is null) return;
        var text = ReferenceEquals(_sentText, _text) ? null : _text;
        core.PostWebMessageAsJson(JsonSerializer.Serialize(new
        {
            text, dark = _dark, mode = _highlight ? _language : "text", lineNumbers = _numbers, wrap = _wrap,
        }));
        _sentText = _text;
    }

    internal void Focus()
    {
        if (!IsReady || _disposed || _core is not { Source: Page } core) return;
        View.Focus(FocusState.Programmatic);
        core.PostWebMessageAsJson("\"focus\"");
    }

    private void ReceiveMessage(CoreWebView2 sender, CoreWebView2WebMessageReceivedEventArgs args)
    {
        if (_disposed || args.Source != Page || sender.Source != Page) return;
        var message = args.WebMessageAsJson;
        if (message == "\"ready\"" && !_connected)
        {
            _connected = true;
            SendDocument();
        }
        else if (message == "\"painted\"" && _connected && !IsReady)
        {
            IsReady = true;
            Ready?.Invoke();
        }
        else if (message == "\"close\"" && IsReady) CloseRequested?.Invoke();
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        if (_core is { } core) core.WebMessageReceived -= ReceiveMessage;
        View.Close();
    }

    internal static string? LanguageFor(string extension) => extension switch
    {
        ".cs" => "csharp", ".c" or ".h" or ".cpp" or ".hpp" => "c_cpp",
        ".go" => "golang", ".js" => "javascript", ".ts" => "typescript",
        ".json" => "json", ".xml" => "xml", ".yaml" or ".yml" => "yaml", ".toml" => "toml", ".ini" => "ini",
        ".py" => "python", ".java" => "java", ".kt" => "kotlin", ".rs" => "rust", ".sh" => "sh",
        ".bat" or ".cmd" => "batchfile", ".ps1" => "powershell", ".sql" => "sql",
        ".html" or ".htm" => "html", ".css" => "css",
        _ => null,
    };
}

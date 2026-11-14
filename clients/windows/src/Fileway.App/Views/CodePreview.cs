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
        DocumentWebView.CreateEnvironmentAsync("code-preview-webview2"));
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
    internal event Action? FindRequested;
    internal event Action<int, int, int>? FindResult;

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
            DocumentWebView.UseLocalAssets(core, Host, Page, assets);
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

    internal void Find(string query, int step, int request)
    {
        if (IsReady && !_disposed && _core is { Source: Page } core)
            core.PostWebMessageAsJson(JsonSerializer.Serialize(new { kind = "find", query, step, request }));
    }

    internal void ClearFind() => Find("", 0, -1);

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
        else if (message == "\"find\"" && IsReady) FindRequested?.Invoke();
        else if (IsReady && message.Length <= 256 && message.StartsWith('{'))
        {
            try
            {
                using var document = JsonDocument.Parse(message);
                var value = document.RootElement;
                if (value.TryGetProperty("kind", out var kind) && kind.ValueEquals("findResult") &&
                    value.TryGetProperty("request", out var request) && request.TryGetInt32(out var id) &&
                    value.TryGetProperty("ordinal", out var ordinal) && ordinal.TryGetInt32(out var current) &&
                    value.TryGetProperty("total", out var total) && total.TryGetInt32(out var count) &&
                    current >= 0 && count >= current)
                    FindResult?.Invoke(id, current, count);
            }
            catch (Exception error) when (error is JsonException or InvalidOperationException) { }
        }
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

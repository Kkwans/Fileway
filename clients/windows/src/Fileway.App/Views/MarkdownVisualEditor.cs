using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.Web.WebView2.Core;

namespace Fileway.App.Views;

internal sealed class MarkdownVisualEditor : IDisposable
{
    private const string Host = "fileway-markdown.local";
    private const string Page = "https://" + Host + "/index.html";
    private static readonly Lazy<Task<CoreWebView2Environment>> Environment = new(() =>
        DocumentWebView.CreateEnvironmentAsync("markdown-editor-webview2"));
    private readonly TaskCompletionSource<bool> _connected = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private CoreWebView2? _core;
    private CoreWebView2Find? _finder;
    private TaskCompletionSource<bool>? _loaded;
    private TaskCompletionSource<string>? _snapshot;
    private int _documentId, _requestId;
    private int? _findRequest;
    private bool _findStarted;
    private bool _started, _disposed, _dark;

    internal WebView2 View { get; } = new() { Opacity = 0, IsHitTestVisible = false, IsTabStop = false };
    internal bool IsReady { get; private set; }
    internal bool IsConnected => _connected.Task.IsCompletedSuccessfully && _connected.Task.Result && !_disposed;
    internal event Action<string>? DraftChanged;
    internal event Action? Failed;
    internal event Action? CloseRequested;
    internal event Action? FindRequested;
    internal event Action<int, int, int>? FindResult;
    internal event Action? SaveRequested;
    internal event Action<Uri>? LinkRequested;

    internal MarkdownVisualEditor()
    {
        AutomationProperties.SetAutomationId(View, "MarkdownVisualEditor");
        AutomationProperties.SetName(View, "Markdown 阅读与编辑");
    }

    internal async Task<bool> LoadAsync(string text, CancellationToken token)
    {
        if (_disposed) return false;
        if (!_started) { _started = true; _ = InitializeAsync(); }
        if (!await _connected.Task.WaitAsync(TimeSpan.FromSeconds(18), token) || _disposed) return false;
        ClearFind();
        IsReady = false;
        _loaded?.TrySetResult(false);
        var load = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        _loaded = load;
        Send(new { kind = "load", id = ++_documentId, text });
        return await load.Task.WaitAsync(TimeSpan.FromSeconds(12), token);
    }

    internal async Task<string> SnapshotAsync(CancellationToken token)
    {
        if (!IsReady || _disposed) throw new InvalidOperationException("Markdown editor is not ready.");
        var snapshot = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        _snapshot = snapshot;
        Send(new { kind = "snapshot", id = _documentId, request = ++_requestId });
        try { return await snapshot.Task.WaitAsync(TimeSpan.FromSeconds(5), token); }
        finally { if (ReferenceEquals(_snapshot, snapshot)) _snapshot = null; }
    }

    internal void SetEditable(bool value)
    {
        if (IsReady) Send(new { kind = "editable", id = _documentId, value });
    }

    internal void SetTheme(bool dark)
    {
        _dark = dark;
        if (IsConnected) Send(new { kind = "theme", dark });
    }

    internal void Focus()
    {
        if (!IsReady || _disposed) return;
        View.Focus(FocusState.Programmatic);
        Send(new { kind = "focus", id = _documentId });
    }

    internal async Task FindAsync(string query, int step, int request, CancellationToken token)
    {
        if (!IsReady || _disposed || _core is not { Source: Page } core) return;
        if (_finder is null)
        {
            _finder = core.Find;
            _finder.ActiveMatchIndexChanged += OnFindChanged;
            _finder.MatchCountChanged += OnFindChanged;
        }
        var finder = _finder;
        _findRequest = request;
        if (!_findStarted || step == 0)
        {
            _findStarted = false;
            var options = core.Environment.CreateFindOptions();
            options.FindTerm = query;
            options.SuppressDefaultFindDialog = true;
            options.ShouldHighlightAllMatches = true;
            await finder.StartAsync(options).AsTask(token);
            if (_findRequest != request || _disposed) return;
            _findStarted = true;
        }
        else if (step > 0) finder.FindNext();
        else finder.FindPrevious();
        ReportFind();
    }

    internal void ClearFind()
    {
        if (_findRequest is null) return;
        _findRequest = null;
        _findStarted = false;
        if (!_disposed) _finder?.Stop();
    }

    private void OnFindChanged(CoreWebView2Find sender, object args) => ReportFind();

    private void ReportFind()
    {
        if (_findStarted && !_disposed && _finder is { } finder && _findRequest is { } request && finder.MatchCount >= 0)
            FindResult?.Invoke(request, finder.ActiveMatchIndex, finder.MatchCount);
    }

    private async Task InitializeAsync()
    {
        try
        {
            var assets = Path.Combine(AppContext.BaseDirectory, "Assets", "MarkdownEditor");
            if (!File.Exists(Path.Combine(assets, "index.html"))) { Fail(); return; }
            var environment = await Environment.Value;
            if (_disposed) return;
            await View.EnsureCoreWebView2Async(environment);
            if (_disposed) return;
            var core = View.CoreWebView2;
            _core = core;
            DocumentWebView.UseLocalAssets(core, Host, Page, assets);
            core.NavigationCompleted += (_, args) => { if (!args.IsSuccess) Fail(); };
            core.ProcessFailed += (_, _) => Fail();
            core.WebMessageReceived += Receive;
            View.Source = new Uri(Page);
        }
        catch (Exception error)
        {
            System.Diagnostics.Debug.WriteLine($"Markdown editor initialization: {error.GetType().Name}");
            Fail();
        }
    }

    private void Send<T>(T value)
    {
        if (!_disposed && _core is { Source: Page } core) core.PostWebMessageAsJson(JsonSerializer.Serialize(value));
    }

    private void Receive(CoreWebView2 sender, CoreWebView2WebMessageReceivedEventArgs args)
    {
        if (_disposed || args.Source != Page || sender.Source != Page) return;
        var json = args.WebMessageAsJson;
        if (json.Length > TextWindow.MaximumTextBytes * 6 + 1024) return;
        try
        {
            using var document = JsonDocument.Parse(json);
            var message = document.RootElement;
            if (!message.TryGetProperty("kind", out var kindValue)) return;
            var kind = kindValue.GetString();
            if (kind == "ready") { _connected.TrySetResult(true); SetTheme(_dark); return; }
            if (kind == "close" && IsReady) { CloseRequested?.Invoke(); return; }
            if (kind == "find" && IsReady) { FindRequested?.Invoke(); return; }
            if (kind == "save" && IsReady) { SaveRequested?.Invoke(); return; }
            if (!message.TryGetProperty("id", out var id) || !id.TryGetInt32(out int documentId) || documentId != _documentId) return;
            switch (kind)
            {
                case "loaded":
                    IsReady = true;
                    _loaded?.TrySetResult(true);
                    break;
                case "unsupported":
                case "failed":
                    _loaded?.TrySetResult(false);
                    break;
                case "dirty" when IsReady:
                    if (ReadText(message) is { } draft) DraftChanged?.Invoke(draft);
                    break;
                case "snapshot" when IsReady:
                    if (message.TryGetProperty("request", out var request) && request.TryGetInt32(out int requestId) && requestId == _requestId &&
                        ReadText(message) is { } text)
                        _snapshot?.TrySetResult(text);
                    break;
                case "link" when IsReady:
                    if (message.TryGetProperty("url", out var url) && url.GetString() is { Length: <= 4096 } address &&
                        Uri.TryCreate(address, UriKind.Absolute, out var uri) && uri.Scheme is "http" or "https")
                        LinkRequested?.Invoke(uri);
                    break;
            }
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException) { }
    }

    private static string? ReadText(JsonElement message) =>
        message.TryGetProperty("text", out var value) && value.ValueKind == JsonValueKind.String &&
        value.GetString() is { } text && text.Length <= TextWindow.MaximumTextBytes ? text : null;

    private void Fail()
    {
        if (_disposed) return;
        _connected.TrySetResult(false);
        _loaded?.TrySetResult(false);
        _snapshot?.TrySetException(new InvalidOperationException("Markdown editor stopped."));
        IsReady = false;
        Failed?.Invoke();
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _connected.TrySetResult(false);
        _loaded?.TrySetResult(false);
        _snapshot?.TrySetCanceled();
        if (_core is { } core) core.WebMessageReceived -= Receive;
        if (_finder is { } finder)
        {
            finder.ActiveMatchIndexChanged -= OnFindChanged;
            finder.MatchCountChanged -= OnFindChanged;
        }
        View.Close();
    }
}

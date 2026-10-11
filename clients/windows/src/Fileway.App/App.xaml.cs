using Fileway.App.MediaSurfaces;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Ipc;
using Fileway.Infrastructure.Services;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Credentials;
using Fileway.Playback;
using System.Runtime.InteropServices;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;

namespace Fileway.App;

public partial class App : Application, IAsyncDisposable
{
    private readonly CancellationTokenSource _lifetime = new();
    private MainWindow? _window;
    private GoHostClient? _host;
    private FilewayClientServices? _services;
    private MpvPlaybackService? _playback;
    private ILocalStorageSettingsService? _storageSettings;
    private FileStream? _dataDirectoryLock;
    private Task _startup = Task.CompletedTask;
    private Task? _shutdown;
    private bool _closing;
    private bool _closed;

    public App()
    {
        UnhandledException += (_, args) => RecordStartupFailure(args.Exception);
        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        try
        {
            _window = new MainWindow();
            _window.AppWindow.Closing += MainWindow_Closing;
            _window.Activate();
            _startup = InitializeServicesAsync();
        }
        catch (Exception exception)
        {
            RecordStartupFailure(exception);
            throw;
        }
    }

    private static void RecordStartupFailure(Exception exception)
    {
        try
        {
            var root = DataDirectory();
            var directory = Path.Combine(root, "diagnostics");
            Directory.CreateDirectory(directory);
            // Method names and error codes diagnose native initialization without
            // persisting exception text, arguments, file paths or capability URLs.
            var failures = new List<object>();
            for (Exception? current = exception; current is not null && failures.Count < 4; current = current.InnerException)
            {
                var methods = new System.Diagnostics.StackTrace(current, false).GetFrames()
                    ?.Take(32).Select(frame => frame.GetMethod())
                    .Select(method => method is null ? null : method.DeclaringType?.FullName + "." + method.Name).ToArray();
                // This app loads only compiled, application-owned XAML; it never
                // parses remote/user markup. Its bounded parser diagnostic helps
                // identify a failing static resource or control constructor.
                var markupDiagnostic = current is Microsoft.UI.Xaml.Markup.XamlParseException
                    ? current.Message[..Math.Min(current.Message.Length, 1200)] : null;
                failures.Add(new { type = current.GetType().FullName, current.HResult, methods, markupDiagnostic });
            }
            File.WriteAllText(Path.Combine(directory, "startup.log"), System.Text.Json.JsonSerializer.Serialize(failures));
        }
        catch (Exception) { }
    }

    private async Task InitializeServicesAsync()
    {
        try
        {
            var directory = AppContext.BaseDirectory;
            var dataDirectory = DataDirectory();
            Directory.CreateDirectory(dataDirectory);
            try
            {
                // All versions sharing this private data root must have one writer.
                // File sharing is enforced across processes and Windows sessions.
                _dataDirectoryLock = new FileStream(Path.Combine(dataDirectory, ".client.lock"), FileMode.OpenOrCreate,
                    FileAccess.ReadWrite, FileShare.None);
            }
            catch (IOException)
            {
                throw new FilewayException(new ErrorInfo(ErrorCode.Busy, "已有栖卷窗口使用此数据目录。请使用现有窗口，或关闭它后重新打开。"));
            }
            _host = await GoHostClient.StartAsync(new HostClientOptions(
                Path.Combine(directory, "fileway-host.exe"),
                Path.Combine(directory, "host-build.json")), _lifetime.Token);
            _lifetime.Token.ThrowIfCancellationRequested();
            var profiles = new SqliteProfileStore(Path.Combine(dataDirectory, "client.db"));
            var storageSettings = new LocalStorageSettingsService(profiles,
                new ClientStorageSettings(Path.Combine(dataDirectory, "cache"), DefaultDownloadDirectory()), _host);
            _storageSettings = storageSettings;
            _services = new FilewayClientServices(_host, profiles,
                new DpapiCredentialVault(Path.Combine(dataDirectory, "credentials")), storageSettings);
            _playback = new MpvPlaybackService(_services,
                new PlaybackWindowFactory(_window!.DispatcherQueue),
                Path.Combine(directory, "media"), _services);
            _window.SetStorageSettings(storageSettings);
            await _window.SetServicesAsync(_services, _services, _services, _playback, _services);
            await OpenLaunchFileAsync();
        }
        catch (OperationCanceledException) when (_closing) { }
        catch (Exception exception)
        {
            RecordStartupFailure(exception);
            if (!_closing)
            {
                var message = exception is FilewayException known
                    ? known.Error.Message
                    : "无法启动原生核心或媒体组件。请完整解压预览包后运行 Fileway.App.exe。";
                _window?.ShowStartupError(message);
            }
        }
    }

    private async Task OpenLaunchFileAsync()
    {
        // Desktop file activation also works offline: a completed local file needs no server session.
        var arguments = Environment.GetCommandLineArgs();
        if (arguments.Length != 2 || !Path.IsPathFullyQualified(arguments[1])) return;
        var path = Path.GetFullPath(arguments[1]);
        if (!File.Exists(path))
        {
            _window?.ShowStartupError("本地文件不存在或不可读取，请重新选择文件。");
            return;
        }
        await _playback!.OpenAsync(new PlaybackOpenRequest(new LocalPlaybackInput(path, Path.GetFileName(path))), _lifetime.Token);
    }

    private async void MainWindow_Closing(AppWindow sender, AppWindowClosingEventArgs args)
    {
        if (_closed) return;
        args.Cancel = true;
        if (_closing) return;
        await DisposeAsync();
        _closed = true;
        _window?.Close();
    }

    public async ValueTask DisposeAsync()
    {
        await (_shutdown ??= ShutdownServicesAsync());
        GC.SuppressFinalize(this);
    }

    private async Task ShutdownServicesAsync()
    {
        _closing = true;
        _lifetime.Cancel();
        _window?.RequestShutdown();
        await _startup;
        if (_window is not null) await _window.WaitForConnectionAsync();
        // Stop native readers before revoking their HTTP resource leases and the Host.
        try { if (_playback is not null) await _playback.DisposeAsync(); }
        catch (Exception) { /* The Host Job Object still bounds process lifetime. */ }
        try { if (_services is not null) await _services.DisposeAsync(); }
        catch (Exception) { }
        try
        {
            if (_storageSettings is IAsyncDisposable asyncSettings) await asyncSettings.DisposeAsync();
            else if (_storageSettings is IDisposable settings) settings.Dispose();
        }
        catch (Exception) { }
        try { if (_host is not null) await _host.DisposeAsync(); }
        catch (Exception) { }
        _dataDirectoryLock?.Dispose();
        _lifetime.Dispose();
    }

    private static string DataDirectory() => Environment.GetEnvironmentVariable("FILEWAY_WINDOWS_DATA_ROOT")
        ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Fileway", "Preview");

    private static string DefaultDownloadDirectory()
    {
        var downloads = new Guid("374DE290-123F-4565-9164-39C4925E467B");
        nint path = 0;
        try
        {
            // DONT_VERIFY preserves a user's redirected location even if the volume is offline.
            if (SHGetKnownFolderPath(ref downloads, 0x4000, 0, out path) >= 0
                && path != 0 && Marshal.PtrToStringUni(path) is { } folder && Path.IsPathFullyQualified(folder))
                return folder;
        }
        finally { if (path != 0) Marshal.FreeCoTaskMem(path); }
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
    }

    [DllImport("shell32.dll", ExactSpelling = true)]
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    private static extern int SHGetKnownFolderPath(ref Guid folder, uint flags, nint token, out nint path);
}

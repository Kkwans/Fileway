using Fileway.App.MediaSurfaces;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Ipc;
using Fileway.Infrastructure.Services;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Credentials;
using Fileway.Infrastructure.Transfers;
using Fileway.Playback;
using System.Runtime.InteropServices;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Fileway.App.Windowing;

namespace Fileway.App;

public partial class App : Application, IAsyncDisposable
{
    private readonly CancellationTokenSource _lifetime = new();
    private MainWindow? _window;
    private ApplicationTray? _tray;
    private GoHostClient? _host;
    private FilewayClientServices? _services;
    private UploadCoordinator? _uploadCoordinator;
    private FileDownloadRunner? _downloadRunner;
    private UploadApplicationService? _uploads;
    private MpvPlaybackService? _playback;
    private ILocalStorageSettingsService? _storageSettings;
    private FileStream? _dataDirectoryLock;
    private Task _startup = Task.CompletedTask;
    private Task? _shutdown;
    private bool _closing;
    private bool _closeRequested;
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
            UiPreferences.Configure(DataDirectory());
            _window = new MainWindow();
            _window.CloseRequested += MainWindow_CloseRequested;
            _window.ExitRequested += (_, _) => RequestExit();
            try { _tray = new ApplicationTray(ShowMainWindow, RequestExit); }
            catch (Exception error) { RecordStartupFailure(error); }
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
            var executionDirectory = Path.Combine(dataDirectory, "transfer-locks");
            Directory.CreateDirectory(executionDirectory);
            var uploadFiles = new WindowsUploadFileAccess(executionDirectory);
            var uploadRunner = new FileUploadRunner(profiles, _services, uploadFiles, uploadFiles, _services);
            _uploadCoordinator = new UploadCoordinator(profiles, uploadRunner);
            _downloadRunner = new FileDownloadRunner(profiles, _services, new WindowsDownloadFileAccess(), uploadFiles);
            _uploads = new UploadApplicationService(_services, _services, _services, profiles, _uploadCoordinator,
                downloads: new DownloadApplicationDependencies(_downloadRunner, _services, storageSettings));
            _playback = new MpvPlaybackService(_services,
                new PlaybackWindowFactory(_window!.DispatcherQueue, _services, new Fileway.Infrastructure.Services.RemoteSubtitleCatalog(_services), _services),
                Path.Combine(directory, "media"), _services, recentAccess: _services, downloads: _uploads);
            _window.SetStorageSettings(storageSettings);
            _window.SetUploadService(_uploads);
            _window.SetDownloadService(_uploads);
            await _window.SetServicesAsync(_uploads, _services, _services, _playback, _uploads, _services, _services, _services, _services, _services, _services);
            try { await storageSettings.InitializeCacheAsync(_lifetime.Token); }
            catch (OperationCanceledException) when (_closing) { }
            catch (FilewayException)
            {
                if (!_closing) _window.ShowStorageWarning("本地缓存暂不可用，连接与在线浏览仍可继续。请检查缓存目录后重试。");
            }
        }
        catch (OperationCanceledException) when (_closing) { return; }
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
            return;
        }
        if (!_closing) await OpenLaunchFileAsync();
    }

    private async Task OpenLaunchFileAsync()
    {
        // Desktop file activation also works offline: a completed local file needs no server session.
        var arguments = Environment.GetCommandLineArgs();
        if (arguments.Length != 2 || !Path.IsPathFullyQualified(arguments[1])) return;
        var displayName = "本地文件";
        try
        {
            var path = Path.GetFullPath(arguments[1]);
            displayName = Path.GetFileName(path);
            if (path.StartsWith(@"\\", StringComparison.Ordinal) || !File.Exists(path))
                throw new FilewayException(new ErrorInfo(ErrorCode.InvalidRequest, "本地文件不存在或不可读取，请重新选择文件。"));
            await _playback!.OpenAsync(new PlaybackOpenRequest(new LocalPlaybackInput(path, displayName)), _lifetime.Token);
        }
        catch (OperationCanceledException) when (_closing) { }
        catch (Exception error)
        {
            if (!_closing) _window?.ShowFileOpenError(displayName,
                error is FilewayException known ? known.Error.Message : "文件未能打开，请重新选择文件后重试。");
        }
    }

    private void MainWindow_CloseRequested(object? sender, EventArgs args)
    {
        if (_closed) return;
        if (!_closing && _tray is { CloseToTray: true })
        {
            if (_window is not null)
            {
                _window.AppWindow.IsShownInSwitchers = false;
                _window.AppWindow.Hide();
            }
            return;
        }
        RequestExit();
    }

    private void ShowMainWindow()
    {
        if (_closing || _closed || _window is null) return;
        _window.AppWindow.IsShownInSwitchers = true;
        _window.AppWindow.Show();
        if (_window.AppWindow.Presenter is OverlappedPresenter presenter && presenter.State == OverlappedPresenterState.Minimized)
            presenter.Restore();
        _window.Activate();
    }

    internal bool CloseToTray => _tray?.CloseToTray ?? false;
    internal void SetCloseToTray(bool enabled)
    {
        if (_tray is not null) _tray.CloseToTray = enabled;
    }

    internal void RequestExit()
    {
        if (_closed) return;
        if (_closeRequested) return;
        _closeRequested = true;
        // WM_CLOSE is intercepted by the window subclass. This avoids the
        // AppWindow.Closing path, which has no async deferral in WinUI 3.
        _ = CompleteCloseAsync();
    }

    private async Task CompleteCloseAsync()
    {
        try { await DisposeAsync().ConfigureAwait(true); }
        catch (Exception error)
        {
            RecordStartupFailure(error);
            _closeRequested = false;
            return;
        }
        _closed = true;
        var window = _window;
        if (window is not null && !window.DispatcherQueue.TryEnqueue(() =>
        {
            // Leave the originating WM_CLOSE callback before final destruction.
            // Preparation removes our interception so this close can proceed.
            var shutdownUiThread = window.DispatcherQueue.HasThreadAccess;
            ShutdownDiagnostics.Record(ShutdownDiagnosticStage.WindowCloseBegin, shutdownUiThread);
            window.PrepareForClose();
            _tray?.Dispose();
            _tray = null;
            window.Close();
            ShutdownDiagnostics.Record(ShutdownDiagnosticStage.WindowCloseReturned, shutdownUiThread);
        }))
        {
            _closed = false;
            _closeRequested = false;
            throw new InvalidOperationException("主窗口关闭调度器已停止，无法安全完成关闭。");
        }
    }

    public async ValueTask DisposeAsync()
    {
        await (_shutdown ??= ShutdownServicesAsync());
        GC.SuppressFinalize(this);
    }

    private async Task ShutdownServicesAsync()
    {
        ShutdownDiagnostics.Record(ShutdownDiagnosticStage.ShutdownEntered, _window?.DispatcherQueue.HasThreadAccess == true);
        _closing = true;
        _lifetime.Cancel();
        _window?.RequestShutdown();
        await _startup;
        if (_window is not null) await _window.WaitForConnectionAsync();
        ShutdownDiagnostics.Record(ShutdownDiagnosticStage.ApplicationTasksDrained, _window?.DispatcherQueue.HasThreadAccess == true);
        // Stop file writers and release their native source/ownership handles
        // before retiring the account's resource leases or its Host process.
        try
        {
            if (_uploads is not null) await _uploads.DisposeAsync();
            else
            {
                try { if (_uploadCoordinator is not null) await _uploadCoordinator.DisposeAsync(); }
                finally { if (_downloadRunner is not null) await _downloadRunner.DisposeAsync(); }
            }
        }
        catch (Exception error) { RecordStartupFailure(error); }
        ShutdownDiagnostics.Record(ShutdownDiagnosticStage.TransfersDisposed, _window?.DispatcherQueue.HasThreadAccess == true);
        _window?.CompleteShutdown();
        // Stop native readers before revoking their HTTP resource leases and the Host.
        ShutdownDiagnostics.Record(ShutdownDiagnosticStage.PlaybackDisposeBegin, _window?.DispatcherQueue.HasThreadAccess == true);
        try { if (_playback is not null) await _playback.DisposeAsync(); }
        catch (Exception) { /* The Host Job Object still bounds process lifetime. */ }
        ShutdownDiagnostics.Record(ShutdownDiagnosticStage.PlaybackDisposeReturned, _window?.DispatcherQueue.HasThreadAccess == true);
        try { if (_services is not null) await _services.DisposeAsync(); }
        catch (Exception) { }
        try
        {
            if (_storageSettings is IAsyncDisposable asyncSettings) await asyncSettings.DisposeAsync();
            else if (_storageSettings is IDisposable settings) settings.Dispose();
        }
        catch (Exception) { }
        ShutdownDiagnostics.Record(ShutdownDiagnosticStage.ServicesAndSettingsDisposed, _window?.DispatcherQueue.HasThreadAccess == true);
        try { if (_host is not null) await _host.DisposeAsync(); }
        catch (Exception) { }
        ShutdownDiagnostics.Record(ShutdownDiagnosticStage.HostDisposed, _window?.DispatcherQueue.HasThreadAccess == true);
        _dataDirectoryLock?.Dispose();
        _lifetime.Dispose();
    }

    internal static string DataDirectory() => Environment.GetEnvironmentVariable("FILEWAY_WINDOWS_DATA_ROOT")
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

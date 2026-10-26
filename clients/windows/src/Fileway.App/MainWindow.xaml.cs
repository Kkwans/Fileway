using Fileway.Core.Contracts;
using Fileway.App.MediaSurfaces;
using Fileway.App.Branding;
using Fileway.App.Windowing;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
namespace Fileway.App;

public sealed partial class MainWindow : Window, IDisposable
{
    private readonly FramelessWindowChrome _chrome;
    private readonly Microsoft.UI.Dispatching.DispatcherQueue _shutdownDispatcher;

    public MainWindow()
    {
        InitializeComponent();
        _shutdownDispatcher = DispatcherQueue;
        Title = AppTitleBar.Caption = AppBranding.DisplayName;
        _chrome = new FramelessWindowChrome(this, AppTitleBar, recordShutdownStages: true);
        _chrome.CloseRequested += Chrome_CloseRequested;
        _chrome.ExitRequested += Chrome_ExitRequested;
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        var scale = NativeVideoWindow.GetDpiForWindow(WinRT.Interop.WindowNative.GetWindowHandle(this)) / 96d;
        var work = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Primary).WorkArea;
        var margin = (int)Math.Round(24 * scale);
        var width = Math.Min((int)Math.Round(1120 * scale), Math.Max(1, work.Width - margin * 2));
        var height = Math.Min((int)Math.Round(760 * scale), Math.Max(1, work.Height - margin * 2));
        AppWindow.MoveAndResize(new Windows.Graphics.RectInt32(work.X + (work.Width - width) / 2, work.Y + (work.Height - height) / 2, width, height));
        RootFrame.Navigate(typeof(MainPage));
        ((MainPage)RootFrame.Content).AttachTitleBar(AppTitleBar);
        InitializeAppearance();
        Closed += MainWindow_Closed;
    }
    public Task SetServicesAsync(IConnectionService connection, IFileRepository files, IImageContentService images,
        IPlaybackService playback, IConnectionCatalog catalog, IThumbnailContentService? thumbnails = null,
        ITextContentService? texts = null, IArchiveBrowseService? archiveBrowse = null, IFileBytesService? fileBytes = null,
        IStorageAnalysisService? storageAnalysis = null, IAccountSecurityService? accountSecurity = null)
        => ((MainPage)RootFrame.Content).SetServicesAsync(connection, files, images, playback, catalog, thumbnails, texts, archiveBrowse, fileBytes, storageAnalysis, accountSecurity);
    public void SetDownloadService(IDownloadService downloads)
        => ((MainPage)RootFrame.Content).SetDownloadService(downloads);
    public void SetUploadService(IUploadService uploads)
        => ((MainPage)RootFrame.Content).SetUploadService(uploads, AppWindow.Id);
    public void SetStorageSettings(ILocalStorageSettingsService settings)
        => ((MainPage)RootFrame.Content).SetStorageSettings(settings, WinRT.Interop.WindowNative.GetWindowHandle(this));
    public void ShowStorageWarning(string message) => ((MainPage)RootFrame.Content).ShowStorageWarning(message);
    public void RequestShutdown() => ((MainPage)RootFrame.Content).BeginShutdown();
    public void CompleteShutdown() => ((MainPage)RootFrame.Content).Shutdown();
    public void PrepareForClose()
    {
        DisposeAppearance();
        // MicaBackdrop owns a composition target callback. Release it while
        // the Window is still alive instead of letting XAML tear it down from
        // inside the native destroy path.
        SystemBackdrop = null;
        _chrome.PrepareForClose();
    }
    public event EventHandler? CloseRequested;
    public event EventHandler? ExitRequested;
    public Task WaitForConnectionAsync() => ((MainPage)RootFrame.Content).ConnectionCompletion;
    public void ShowStartupError(string message) => ((MainPage)RootFrame.Content).ShowStartupError(message);
    public void ShowFileOpenError(string name, string message) => ((MainPage)RootFrame.Content).ShowFileOpenError(name, message);
    private void Chrome_CloseRequested(object? sender, EventArgs args) => CloseRequested?.Invoke(this, args);
    private void Chrome_ExitRequested(object? sender, EventArgs args) => ExitRequested?.Invoke(this, args);

    private void MainWindow_Closed(object sender, WindowEventArgs args)
    {
        var uiThread = _shutdownDispatcher.HasThreadAccess;
        ShutdownDiagnostics.Record(ShutdownDiagnosticStage.MainWindowClosedEntered, uiThread);
        // All content and native resources are released before Close. The
        // Closed callback only records the boundary; it must not re-enter the
        // XAML tree while native destruction is still unwinding.
        ShutdownDiagnostics.Record(ShutdownDiagnosticStage.MainWindowClosedReturned, uiThread);
    }

    public void Dispose()
    {
        _chrome.CloseRequested -= Chrome_CloseRequested;
        _chrome.ExitRequested -= Chrome_ExitRequested;
        _chrome.Dispose();
        if (RootFrame.Content is MainPage page) page.Shutdown();
        GC.SuppressFinalize(this);
    }
}

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

    public MainWindow()
    {
        InitializeComponent();
        Title = AppTitleBar.Caption = AppBranding.DisplayName;
        _chrome = new FramelessWindowChrome(this, AppTitleBar);
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        var scale = NativeVideoWindow.GetDpiForWindow(WinRT.Interop.WindowNative.GetWindowHandle(this)) / 96d;
        var work = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Primary).WorkArea;
        var margin = (int)Math.Round(24 * scale);
        var width = Math.Min((int)Math.Round(1120 * scale), Math.Max(1, work.Width - margin * 2));
        var height = Math.Min((int)Math.Round(760 * scale), Math.Max(1, work.Height - margin * 2));
        AppWindow.MoveAndResize(new Windows.Graphics.RectInt32(work.X + (work.Width - width) / 2, work.Y + (work.Height - height) / 2, width, height));
        RootFrame.Navigate(typeof(MainPage));
        Closed += (_, _) => Dispose();
    }
    public Task SetServicesAsync(IConnectionService connection, IFileRepository files, IImageContentService images, IPlaybackService playback, IConnectionCatalog catalog, IThumbnailContentService? thumbnails = null)
        => ((MainPage)RootFrame.Content).SetServicesAsync(connection, files, images, playback, catalog, thumbnails);
    public void SetStorageSettings(ILocalStorageSettingsService settings)
        => ((MainPage)RootFrame.Content).SetStorageSettings(settings, WinRT.Interop.WindowNative.GetWindowHandle(this));
    public void ShowStorageWarning(string message) => ((MainPage)RootFrame.Content).ShowStorageWarning(message);
    public void RequestShutdown() => ((MainPage)RootFrame.Content).Shutdown();
    public Task WaitForConnectionAsync() => ((MainPage)RootFrame.Content).ConnectionCompletion;
    public void ShowStartupError(string message) => ((MainPage)RootFrame.Content).ShowStartupError(message);
    public void Dispose()
    {
        _chrome.Dispose();
        if (RootFrame.Content is MainPage page) page.Shutdown();
        GC.SuppressFinalize(this);
    }
}

using Fileway.Core.Contracts;
using Fileway.App.MediaSurfaces;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
namespace Fileway.App;

public sealed partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        var scale = NativeVideoWindow.GetDpiForWindow(WinRT.Interop.WindowNative.GetWindowHandle(this)) / 96d;
        var work = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Primary).WorkArea;
        var margin = (int)Math.Round(24 * scale);
        var width = Math.Min((int)Math.Round(1120 * scale), Math.Max(1, work.Width - margin * 2));
        var height = Math.Min((int)Math.Round(760 * scale), Math.Max(1, work.Height - margin * 2));
        AppWindow.MoveAndResize(new Windows.Graphics.RectInt32(work.X + (work.Width - width) / 2, work.Y + (work.Height - height) / 2, width, height));
        RootFrame.Navigate(typeof(MainPage));
        Closed += (_, _) => ((MainPage)RootFrame.Content).Shutdown();
    }
    public void SetServices(IConnectionService connection, IFileRepository files, IImageContentService images, IPlaybackService playback)
        => ((MainPage)RootFrame.Content).SetServices(connection, files, images, playback);
    public void ShowStartupError(string message) => ((MainPage)RootFrame.Content).ShowStartupError(message);
}

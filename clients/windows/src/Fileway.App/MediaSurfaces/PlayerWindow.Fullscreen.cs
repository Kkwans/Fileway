using Fileway.Core.Contracts;
using Microsoft.UI.Windowing;
using Windows.Graphics;

namespace Fileway.App.MediaSurfaces;

internal sealed partial class PlayerWindow
{
    private bool _fullscreenApplyingFrame;
    private bool _fullscreenBoundsFailed;
    private bool DesiredFullscreen => AppWindow.Presenter.Kind == AppWindowPresenterKind.FullScreen;
    private bool FullscreenGeometry => DesiredFullscreen;

    private ValueTask SetNativeFullscreenAsync(bool fullscreen, CancellationToken cancellationToken) => OnUiAsync(() =>
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (_disposed || _detached || _closing || fullscreen == DesiredFullscreen) return;
        var previous = AppWindow.Presenter;
        _responsiveness?.Record(PlaybackDiagnosticEvent.FullscreenUiEntered, fullscreen ? 1 : 0);
        _fullscreenApplyingFrame = true; _fullscreenBoundsFailed = false;
        try
        {
            if (fullscreen)
            {
                _windowedPresenter = previous as OverlappedPresenter;
                _wasMaximized = _windowedPresenter?.State == OverlappedPresenterState.Maximized;
                _windowedBounds = new RectInt32(AppWindow.Position.X, AppWindow.Position.Y, AppWindow.Size.Width, AppWindow.Size.Height);
                AppWindow.SetPresenter(AppWindowPresenterKind.FullScreen);
            }
            else
            {
                AppWindow.SetPresenter(_windowedPresenter ?? OverlappedPresenter.Create());
                if (_wasMaximized && AppWindow.Presenter is OverlappedPresenter presenter) presenter.Maximize();
                else AppWindow.MoveAndResize(_windowedBounds);
                ApplyFramelessChrome();
                if (_miniMode && AppWindow.Presenter is OverlappedPresenter restored) restored.IsResizable = false;
            }
            _overlay.SetFullscreen(DesiredFullscreen);
            UpdateBoundsCore();
            if (_fullscreenBoundsFailed) throw FullscreenFailure();
            FullscreenChanged?.Invoke(this, DesiredFullscreen);
            FocusVideo();
        }
        catch
        {
            // Restore the actual presenter, not a separately simulated fullscreen flag.
            AppWindow.SetPresenter(previous);
            _overlay.SetFullscreen(DesiredFullscreen);
            FullscreenChanged?.Invoke(this, DesiredFullscreen);
            throw;
        }
        finally
        {
            _fullscreenApplyingFrame = false;
            _responsiveness?.Record(PlaybackDiagnosticEvent.FullscreenPresenterEnd, DesiredFullscreen ? 1 : 0);
        }
    });

    private static FilewayException FullscreenFailure() => new(new(ErrorCode.Unknown, "窗口显示状态未能更新，请重试全屏切换。"));
    private void FullscreenDisplayChanged()
    {
        if (!_disposed && !_detached && !_closing)
            DispatcherQueue.TryEnqueue(UpdateBounds); // Presenter owns monitor/DPI rebasing.
    }
}

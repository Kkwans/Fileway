using Fileway.Core.Contracts;
using Microsoft.UI.Windowing;
using Fileway.App.Windowing;

namespace Fileway.App.MediaSurfaces;

internal sealed partial class PlayerWindow
{
    private bool _fullscreenApplyingFrame;
    private bool _fullscreenBoundsFailed;
    private WindowedPlacement? _windowedPlacement;
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
                _windowedPlacement = null;
                _windowedPlacement = WindowedPlacement.Capture(_window);
                AppWindow.SetPresenter(AppWindowPresenterKind.FullScreen);
            }
            else
            {
                RestoreWindowedPresentation(_windowedPresenter ?? OverlappedPresenter.Create());
            }
            _overlay.SetFullscreen(DesiredFullscreen);
            UpdateBoundsCore();
            if (_fullscreenBoundsFailed) throw FullscreenFailure();
            FullscreenChanged?.Invoke(this, DesiredFullscreen);
            FocusVideo();
            if (!fullscreen) _windowedPlacement = null;
        }
        catch
        {
            // Restore the actual presenter, not a separately simulated fullscreen flag.
            if (previous is OverlappedPresenter presenter && _windowedPlacement is not null)
                RestoreWindowedPresentation(presenter);
            else AppWindow.SetPresenter(previous);
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

    private void RestoreWindowedPresentation(OverlappedPresenter presenter)
    {
        if (_windowedPlacement is not { } placement) throw FullscreenFailure();
        AppWindow.SetPresenter(presenter);
        try
        {
            ApplyFramelessChrome();
            placement.Restore(_window, presenter);
        }
        finally
        {
            if (_miniMode) presenter.IsResizable = false;
        }
    }

    private static FilewayException FullscreenFailure() => new(new(ErrorCode.Unknown, "窗口显示状态未能更新，请重试全屏切换。"));
    private void FullscreenDisplayChanged()
    {
        if (!_disposed && !_detached && !_closing)
            DispatcherQueue.TryEnqueue(UpdateBounds); // Presenter owns monitor/DPI rebasing.
    }
}

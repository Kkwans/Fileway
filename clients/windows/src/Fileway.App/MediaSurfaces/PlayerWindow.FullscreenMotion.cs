using Microsoft.UI.Xaml.Media;
using Windows.UI.ViewManagement;

namespace Fileway.App.MediaSurfaces;

internal sealed partial class PlayerWindow
{
    // 400ms in the pure model reaches most visible geometry too quickly. This starting response
    // puts the substantial live-window motion around 300–400ms; native acceptance decides tuning.
    private static readonly TimeSpan FullscreenResponse = TimeSpan.FromMilliseconds(900);
    private readonly long _fullscreenClockOrigin = TimeProvider.System.GetTimestamp();
    private readonly UISettings _fullscreenUiSettings = new();
    private PlaybackFullscreenMotionPolicy? _fullscreenMotion;
    private NativeFullscreenWindow? _fullscreenShell;
    private NativeFullscreenWindow.NativePlacement _fullscreenPlacement;
    private FullscreenPixelRect _fullscreenMonitor;
    private TaskCompletionSource? _fullscreenCompletion;
    private CancellationTokenRegistration _fullscreenCallerCancellation;
    private long _fullscreenRequestEpoch;
    private bool _fullscreenRestoreCaptured;
    private bool _fullscreenCommitted;
    private bool _fullscreenRendering;
    private bool _fullscreenApplyingFrame;
    private bool _fullscreenDisplayChangeQueued;
    private bool _fullscreenFailing;
    private bool _fullscreenMaximizeAfterRestore;

    private bool DesiredFullscreen => _fullscreenMotion is { Current.IsClosed: false } motion
        ? motion.Current.DesiredFullscreen : _fullscreenCommitted;
    private bool FullscreenGeometry => _fullscreenCommitted || DesiredFullscreen || _fullscreenMotion?.Current.IsAnimating == true;
    private TimeSpan FullscreenNow => TimeProvider.System.GetElapsedTime(_fullscreenClockOrigin);

    private async Task DispatchFullscreenAsync(bool fullscreen, CancellationToken cancellationToken)
    {
        Task transition = Task.CompletedTask;
        await OnUiAsync(() => transition = RequestFullscreenMotion(fullscreen, cancellationToken)).ConfigureAwait(false);
        await transition.ConfigureAwait(false);
    }

    private Task RequestFullscreenMotion(bool fullscreen, CancellationToken caller)
    {
        caller.ThrowIfCancellationRequested();
        if (_disposed || _detached || _closing) return Task.FromCanceled(new CancellationToken(true));
        if (_fullscreenMotion?.Current.IsAnimating == true && fullscreen == DesiredFullscreen && _fullscreenCompletion is { } existing)
            return existing.Task.WaitAsync(caller);
        if (_fullscreenMotion?.Current.IsAnimating != true && fullscreen == _fullscreenCommitted) return Task.CompletedTask;

        try
        {
        _responsiveness?.Record(PlaybackDiagnosticEvent.FullscreenUiEntered, fullscreen ? 1 : 0);
        if (!_fullscreenRestoreCaptured && fullscreen)
        {
            if (AppWindow.Presenter is not Microsoft.UI.Windowing.OverlappedPresenter) throw NativeFullscreenWindow.Failure();
            _fullscreenPlacement = NativeFullscreenWindow.Placement(_window);
            var bounds = NativeFullscreenWindow.Bounds(_window);
            _windowedBounds = new(bounds.X, bounds.Y, bounds.Width, bounds.Height);
            _wasMaximized = NativePlayerOverlay.IsZoomed(_window);
            _windowedPresenter = AppWindow.Presenter as Microsoft.UI.Windowing.OverlappedPresenter;
            _fullscreenMonitor = NativeFullscreenWindow.MonitorBounds(_window);
            _fullscreenRestoreCaptured = true;
        }
        if (_fullscreenMotion?.Current.IsAnimating != true)
            _fullscreenMotion = new(NativeFullscreenWindow.Bounds(_window), _fullscreenCommitted, FullscreenResponse);
        _fullscreenShell ??= new();
        if (fullscreen) _fullscreenShell.Mark(_window, true);

        _fullscreenCallerCancellation.Dispose();
        _fullscreenCompletion?.TrySetCanceled(CancellationToken.None); // Superseded independently of the new caller's token.
        var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        _fullscreenCompletion = completion;
        long epoch = ++_fullscreenRequestEpoch;
        var target = fullscreen ? _fullscreenMonitor : new FullscreenPixelRect(_windowedBounds.X, _windowedBounds.Y,
            _windowedBounds.Width, _windowedBounds.Height);
        var motion = _fullscreenMotion!;
        _ = motion.SetAnimationsEnabled(FullscreenAnimationsEnabled(), FullscreenNow);
        var frame = motion.Retarget(target, fullscreen, FullscreenNow, FullscreenResponse);
        _overlay.SetFullscreen(FullscreenGeometry);
        _responsiveness?.Record(PlaybackDiagnosticEvent.FullscreenPresenterBegin, fullscreen ? 1 : 0);
        try
        {
            ApplyFullscreenFrame(frame);
            if (frame.IsAnimating) StartFullscreenClock();
            else CompleteFullscreenMotion(frame);
            if (caller.CanBeCanceled && !completion.Task.IsCompleted)
                _fullscreenCallerCancellation = caller.Register(() =>
                    DispatcherQueue.TryEnqueue(() => CancelFullscreenWait(epoch, caller)));
        }
        catch (Exception)
        {
            FailFullscreenMotion();
        }
        return completion.Task;
        }
        catch (Exception)
        {
            FailFullscreenMotion();
            return Task.FromException(NativeFullscreenWindow.Failure());
        }
    }

    private bool FullscreenAnimationsEnabled()
    {
        try { return _fullscreenUiSettings.AnimationsEnabled; }
        catch (System.Runtime.InteropServices.COMException) { return false; }
    }

    private void StartFullscreenClock()
    {
        if (_fullscreenRendering) return;
        _fullscreenRendering = true;
        CompositionTarget.Rendering += Fullscreen_Rendering;
    }

    private void StopFullscreenClock()
    {
        if (!_fullscreenRendering) return;
        _fullscreenRendering = false;
        CompositionTarget.Rendering -= Fullscreen_Rendering;
    }

    private void Fullscreen_Rendering(object? sender, object args)
    {
        if (_disposed || _detached || _closing) { StopFullscreenMotionForClose(); return; }
        if (NativePlayerOverlay.IsIconic(_window)) { SettleMinimizedFullscreen(); return; }
        var motion = _fullscreenMotion;
        if (motion is null) { StopFullscreenClock(); return; }
        try
        {
            var frame = !FullscreenAnimationsEnabled() ? motion.SetAnimationsEnabled(false, FullscreenNow) : motion.Current;
            if (!motion.TryAdvance(frame.Generation, FullscreenNow, out frame)) return;
            ApplyFullscreenFrame(frame);
            if (!frame.IsAnimating) CompleteFullscreenMotion(frame);
        }
        catch (Exception) { FailFullscreenMotion(); }
    }

    private void ApplyFullscreenFrame(FullscreenMotionFrame frame)
    {
        if (!DispatcherQueue.HasThreadAccess || _disposed || _detached || _closing ||
            _fullscreenMotion?.Current.Generation != frame.Generation) return;
        _fullscreenApplyingFrame = true;
        try
        {
            NativeFullscreenWindow.Move(_window, frame.Bounds);
            if (_fullscreenMotion?.Current.IsClosed == true) throw NativeFullscreenWindow.Failure();
            // Native client geometry owns the child/HUD for this same frame. XAML layout
            // notifications may arrive later and must never write a stale ActualSize back.
            UpdateBoundsCore(); // Direct call stays inside the rendering/request failure boundary.
        }
        finally { _fullscreenApplyingFrame = false; }
    }

    private void CompleteFullscreenMotion(FullscreenMotionFrame frame)
    {
        if (_disposed || _detached || _closing || _fullscreenMotion?.Current.Generation != frame.Generation) return;
        StopFullscreenClock();
        if (!frame.DesiredFullscreen && _fullscreenRestoreCaptured)
        {
            // Keep the original presenter and its maximized state throughout the transition.
            // Restore both show state and normal placement; never Restore() before the motion.
            if (_windowedPresenter is null || AppWindow.Presenter is not Microsoft.UI.Windowing.OverlappedPresenter)
                throw NativeFullscreenWindow.Failure();
            RestoreFullscreenPlacement();
        }
        _fullscreenShell?.Mark(_window, frame.DesiredFullscreen);
        int corners = frame.DesiredFullscreen ? 1 : 2;
        _ = NativeVideoWindow.DwmSetWindowAttribute(_window, 33, ref corners, sizeof(int));
        _fullscreenCommitted = frame.DesiredFullscreen;
        if (!_fullscreenCommitted) _fullscreenRestoreCaptured = false;
        _overlay.SetFullscreen(_fullscreenCommitted);
        UpdateBounds();
        _responsiveness?.Record(PlaybackDiagnosticEvent.FullscreenPresenterEnd, _fullscreenCommitted ? 1 : 0);
        _fullscreenCallerCancellation.Dispose();
        var completion = _fullscreenCompletion; _fullscreenCompletion = null;
        completion?.TrySetResult();
        FullscreenChanged?.Invoke(this, _fullscreenCommitted); // Host owns deduplication, including reversed native intent.
    }

    private void CancelFullscreenWait(long epoch, CancellationToken caller)
    {
        if (epoch != _fullscreenRequestEpoch || _fullscreenCompletion is not { } completion) return;
        if (_disposed || _detached || _closing) { StopFullscreenMotionForClose(); return; }
        try
        {
            var frame = _fullscreenMotion!.Cancel(FullscreenNow);
            ApplyFullscreenFrame(frame);
            completion.TrySetCanceled(caller);
            CompleteFullscreenMotion(frame);
        }
        catch (Exception) { FailFullscreenMotion(); }
    }

    private void FailFullscreenMotion()
    {
        if (_fullscreenFailing) return;
        _fullscreenFailing = true;
        try
        {
        StopFullscreenClock();
        _fullscreenMotion?.Close();
        ++_fullscreenRequestEpoch;
        _fullscreenCallerCancellation.Dispose();
        _fullscreenCompletion?.TrySetException(NativeFullscreenWindow.Failure());
        _fullscreenCompletion = null;
        // A surviving native window is stabilized at its last committed state, not by replaying input.
        if (!_disposed && !_detached && !_closing)
        {
            try
            {
                if (NativePlayerOverlay.IsIconic(_window) && _fullscreenRestoreCaptured)
                {
                    NativeFullscreenWindow.StageMinimizedRestore(_window, _fullscreenPlacement, _fullscreenCommitted,
                        NativeFullscreenWindow.MonitorBounds(_window));
                    _fullscreenMaximizeAfterRestore = !_fullscreenCommitted && _wasMaximized;
                }
                else if (!_fullscreenCommitted && _fullscreenRestoreCaptured)
                { RestoreFullscreenPlacement(); _fullscreenRestoreCaptured = false; }
                else if (_fullscreenCommitted) NativeFullscreenWindow.Move(_window, NativeFullscreenWindow.MonitorBounds(_window));
                _fullscreenShell?.Mark(_window, _fullscreenCommitted);
                _overlay.SetFullscreen(_fullscreenCommitted);
                UpdateBoundsCore(); // Recovery failure must not recursively reenter the callback boundary.
                FullscreenChanged?.Invoke(this, _fullscreenCommitted);
            }
            catch (Exception) { /* Keep the safe failure; close still owns renderer teardown. */ }
        }
        }
        finally { _fullscreenFailing = false; }
    }

    private void StopFullscreenMotionForClose()
    {
        StopFullscreenClock();
        ++_fullscreenRequestEpoch;
        _fullscreenMotion?.Close();
        _fullscreenCallerCancellation.Dispose();
        _fullscreenCompletion?.TrySetCanceled(); _fullscreenCompletion = null;
    }

    private void SettleFullscreenBeforeMinimize()
    {
        if (_fullscreenMotion?.Current.IsAnimating != true) return;
        try
        {
            var frame = _fullscreenMotion.Cancel(FullscreenNow);
            ApplyFullscreenFrame(frame); CompleteFullscreenMotion(frame);
        }
        catch (Exception) { FailFullscreenMotion(); }
    }

    private void SettleMinimizedFullscreen()
    {
        StopFullscreenClock();
        if (_fullscreenMotion is not { } motion || !_fullscreenRestoreCaptured) return;
        try
        {
            bool fullscreen = DesiredFullscreen;
            _fullscreenMonitor = NativeFullscreenWindow.MonitorBounds(_window);
            NativeFullscreenWindow.StageMinimizedRestore(_window, _fullscreenPlacement, fullscreen, _fullscreenMonitor);
            _fullscreenMaximizeAfterRestore = !fullscreen && _wasMaximized;
            var frame = motion.Cancel(FullscreenNow);
            _fullscreenShell?.Mark(_window, fullscreen);
            _fullscreenCommitted = fullscreen;
            if (!fullscreen) _fullscreenRestoreCaptured = false;
            _overlay.SetFullscreen(fullscreen);
            if (NativePlayerOverlay.GetWindowRect(_child, out var bounds)) _overlay.SyncMotionBounds(bounds, visible: false);
            _fullscreenCallerCancellation.Dispose();
            var completion = _fullscreenCompletion; _fullscreenCompletion = null;
            completion?.TrySetResult();
            FullscreenChanged?.Invoke(this, frame.CommittedFullscreen);
        }
        catch (Exception) { FailFullscreenMotion(); }
    }

    private void DisposeFullscreenMotion()
    {
        StopFullscreenMotionForClose();
        _fullscreenShell?.Dispose(); _fullscreenShell = null;
    }

    private void RestoreFullscreenPlacement()
    {
        var placement = _fullscreenPlacement;
        placement.ShowCommand = _wasMaximized ? 3u : 4u; // Maximize / show normally without activation.
        NativeFullscreenWindow.Restore(_window, placement);
        _fullscreenMaximizeAfterRestore = false;
    }

    private void RestoreDeferredFullscreenMaximize()
    {
        if (!_fullscreenMaximizeAfterRestore || _disposed || _detached || _closing || FullscreenGeometry ||
            NativePlayerOverlay.IsIconic(_window)) return;
        var foreground = NativePlayerOverlay.GetForegroundWindow();
        if (foreground == 0 || NativePlayerOverlay.GetAncestor(foreground, 3) != _window) return;
        // Maximize only after the user's restored window group is already foreground.
        // Never use a maximized show command to bring a background/minimized owner forward.
        _fullscreenMaximizeAfterRestore = false;
        try { RestoreFullscreenPlacement(); UpdateBounds(); }
        catch (Exception) { _fullscreenMaximizeAfterRestore = true; FailFullscreenMotion(); }
    }

    private void FullscreenDisplayChanged()
    {
        if (_fullscreenDisplayChangeQueued || _disposed || _detached || _closing || !FullscreenGeometry) return;
        _fullscreenDisplayChangeQueued = true;
        DispatcherQueue.TryEnqueue(() =>
        {
            _fullscreenDisplayChangeQueued = false;
            if (_disposed || _detached || _closing || !FullscreenGeometry || _fullscreenMotion is not { } motion) return;
            try
            {
                // OS DPI/display rebasing is authoritative. Settle its latest valid endpoint
                // immediately rather than sending an old monitor trajectory over the OS suggestion.
                _fullscreenMonitor = NativeFullscreenWindow.MonitorBounds(_window);
                FullscreenPixelRect target;
                if (DesiredFullscreen) target = _fullscreenMonitor;
                else
                {
                    // Let Windows map the saved placement to a surviving display before
                    // writing geometry; never animate an obsolete off-screen window target.
                    RestoreFullscreenPlacement();
                    target = NativeFullscreenWindow.Bounds(_window);
                    _windowedBounds = new(target.X, target.Y, target.Width, target.Height);
                }
                _ = motion.Retarget(target, DesiredFullscreen, FullscreenNow);
                var frame = motion.Cancel(FullscreenNow);
                ApplyFullscreenFrame(frame); CompleteFullscreenMotion(frame);
            }
            catch (Exception) { FailFullscreenMotion(); }
        });
    }
}

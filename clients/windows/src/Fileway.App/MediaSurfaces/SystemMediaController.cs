using Windows.Media;
using Windows.Storage;

namespace Fileway.App.MediaSurfaces;

/// <summary>
/// Windows system media transport integration: hardware media keys and the OS media overlay
/// control this player. It never touches the system volume; the player's own volume stays
/// independent. All button callbacks arrive on a background thread and marshal themselves.
/// </summary>
internal sealed class SystemMediaController : IDisposable
{
    private readonly SystemMediaTransportControls _controls;
    private readonly Action _play, _pause, _next, _previous, _stop;
    private MediaPlaybackStatus _status;
    private string? _title;
    private bool _disposed;

    private SystemMediaController(SystemMediaTransportControls controls, Action play, Action pause,
        Action next, Action previous, Action stop)
    {
        _controls = controls;
        _play = play; _pause = pause; _next = next; _previous = previous; _stop = stop;
        _controls.ButtonPressed += OnButtonPressed;
        _controls.IsEnabled = true;
        _controls.IsPlayEnabled = true;
        _controls.IsPauseEnabled = true;
        _controls.IsStopEnabled = true;
        _controls.IsNextEnabled = false;
        _controls.IsPreviousEnabled = false;
    }

    /// <summary>Returns null when this desktop cannot provide SMTC; playback continues without it.</summary>
    public static SystemMediaController? TryCreate(nint windowHandle, Action play, Action pause,
        Action next, Action previous, Action stop)
    {
        if (windowHandle == 0) return null;
        try
        {
            var controls = SystemMediaTransportControlsInterop.GetForWindow(windowHandle);
            return new SystemMediaController(controls, play, pause, next, previous, stop);
        }
        catch (Exception) { return null; }
    }

    public bool NextEnabled { set { if (!_disposed) _controls.IsNextEnabled = value; } }
    public bool PreviousEnabled { set { if (!_disposed) _controls.IsPreviousEnabled = value; } }

    public void Update(string title, MediaPlaybackStatus status)
    {
        if (_disposed) return;
        bool metadataChanged = !string.Equals(_title, title, StringComparison.Ordinal);
        _title = title;
        if (metadataChanged || _status != status)
        {
            _status = status;
            var updater = _controls.DisplayUpdater;
            updater.Type = MediaPlaybackType.Video;
            updater.VideoProperties.Title = title ?? "";
            updater.Update();
        }
        if (_controls.PlaybackStatus != status) _controls.PlaybackStatus = status;
    }

    private void OnButtonPressed(SystemMediaTransportControls sender,
        SystemMediaTransportControlsButtonPressedEventArgs args)
    {
        switch (args.Button)
        {
            case SystemMediaTransportControlsButton.Play: _play(); break;
            case SystemMediaTransportControlsButton.Pause: _pause(); break;
            case SystemMediaTransportControlsButton.Stop: _stop(); break;
            case SystemMediaTransportControlsButton.Next: _next(); break;
            case SystemMediaTransportControlsButton.Previous: _previous(); break;
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        try
        {
            _controls.ButtonPressed -= OnButtonPressed;
            _controls.IsEnabled = false;
            _controls.DisplayUpdater.ClearAll();
        }
        catch (Exception) { /* SMTC teardown is best effort; the window is closing anyway. */ }
    }
}

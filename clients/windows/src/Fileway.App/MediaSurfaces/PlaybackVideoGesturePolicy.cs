namespace Fileway.App.MediaSurfaces;

internal enum PlaybackVideoGesture
{
    None,
    TogglePause,
    ToggleFullscreen,
}

/// <summary>Separates video clicks using the system's time and physical-pixel rectangles.</summary>
internal sealed class PlaybackVideoGesturePolicy
{
    private readonly TimeSpan _doubleClickInterval;
    private readonly int _doubleClickWidth, _doubleClickHeight, _dragWidth, _dragHeight;
    private Press? _pressed;
    private Press? _pending;
    private bool _suppressRelease;

    private readonly record struct Press(TimeSpan Timestamp, int X, int Y);

    internal PlaybackVideoGesturePolicy(TimeSpan doubleClickInterval, int doubleClickWidth,
        int doubleClickHeight, int dragWidth, int dragHeight)
    {
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(doubleClickInterval, TimeSpan.Zero);
        ArgumentOutOfRangeException.ThrowIfLessThan(doubleClickWidth, 1);
        ArgumentOutOfRangeException.ThrowIfLessThan(doubleClickHeight, 1);
        ArgumentOutOfRangeException.ThrowIfLessThan(dragWidth, 1);
        ArgumentOutOfRangeException.ThrowIfLessThan(dragHeight, 1);
        _doubleClickInterval = doubleClickInterval;
        _doubleClickWidth = doubleClickWidth;
        _doubleClickHeight = doubleClickHeight;
        _dragWidth = dragWidth;
        _dragHeight = dragHeight;
    }

    internal PlaybackVideoGesture PointerPressed(TimeSpan now, int x, int y)
    {
        _pressed = null;
        _suppressRelease = false;
        if (_pending is { } previous)
        {
            _pending = null;
            if (now >= previous.Timestamp && now <= previous.Timestamp + _doubleClickInterval
                && InsideRectangle(previous, x, y, _doubleClickWidth, _doubleClickHeight))
            {
                // Native classes may deliver either a second DOWN or DBLCLK.
                // Both cancel the single click before the second button release.
                _suppressRelease = true;
                return PlaybackVideoGesture.ToggleFullscreen;
            }
            _pressed = new Press(now, x, y);
            return PlaybackVideoGesture.TogglePause;
        }
        _pressed = new Press(now, x, y);
        return PlaybackVideoGesture.None;
    }

    internal PlaybackVideoGesture PointerReleased(TimeSpan now, int x, int y)
    {
        if (_suppressRelease)
        {
            _suppressRelease = false;
            return PlaybackVideoGesture.None;
        }
        var pressed = _pressed;
        _pressed = null;
        if (pressed is not { } click || now < click.Timestamp
            || !InsideRectangle(click, x, y, _dragWidth, _dragHeight)) return PlaybackVideoGesture.None;
        if (now >= click.Timestamp + _doubleClickInterval) return PlaybackVideoGesture.TogglePause;
        _pending = click;
        return PlaybackVideoGesture.None;
    }

    internal PlaybackVideoGesture Elapse(TimeSpan now)
    {
        if (_pending is not { } click || now < click.Timestamp + _doubleClickInterval)
            return PlaybackVideoGesture.None;
        _pending = null;
        return PlaybackVideoGesture.TogglePause;
    }

    internal TimeSpan? PendingDelay(TimeSpan now)
    {
        if (_pending is not { } click) return null;
        var delay = click.Timestamp + _doubleClickInterval - now;
        return delay > TimeSpan.Zero ? delay : TimeSpan.Zero;
    }

    internal void Cancel()
    {
        _pressed = null;
        _pending = null;
        _suppressRelease = false;
    }

    private static bool InsideRectangle(Press origin, int x, int y, int width, int height) =>
        Math.Abs((long)x - origin.X) * 2 <= width && Math.Abs((long)y - origin.Y) * 2 <= height;
}

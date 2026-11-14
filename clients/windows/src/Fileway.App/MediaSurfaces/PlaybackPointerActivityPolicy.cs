namespace Fileway.App.MediaSurfaces;

/// <summary>
/// One mouse baseline for the video owner and its HUD, expressed in screen physical pixels.
/// HWND visibility/hit-test changes can deliver movement messages without physical movement.
/// </summary>
internal sealed class PlaybackPointerActivityPolicy
{
    private (int X, int Y)? _position;

    // Visibility/owned-focus synchronization must not consume an already pending real move.
    internal void InitializePosition(int screenX, int screenY) => _position ??= (screenX, screenY);

    // Explicit input already counts as activity and starts a fresh mouse baseline.
    internal void SynchronizePosition(int screenX, int screenY) => _position = (screenX, screenY);

    internal bool ObserveMouseMove(int screenX, int screenY)
    {
        var previous = _position;
        _position = (screenX, screenY);
        return previous is { } point && (point.X != screenX || point.Y != screenY);
    }
}

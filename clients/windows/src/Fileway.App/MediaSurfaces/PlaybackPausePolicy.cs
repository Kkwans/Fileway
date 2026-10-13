using Fileway.Core.Contracts;

namespace Fileway.App.MediaSurfaces;

internal readonly record struct PlaybackPauseAction(bool Restart, bool TargetPaused)
{
    internal string IconName => TargetPaused ? "Pause" : "Play";
    internal string AutomationName => Restart ? "重新播放" : TargetPaused ? "暂停播放" : "开始播放";
}

/// <summary>Buffering and seeking do not replace the engine-confirmed pause state.</summary>
internal static class PlaybackPausePolicy
{
    internal static PlaybackPauseAction GetAction(PlaybackSnapshot snapshot) =>
        snapshot.Status == PlaybackStatus.Ended
            ? new(Restart: true, TargetPaused: false)
            : new(Restart: false, TargetPaused: !(snapshot.IsPaused ?? snapshot.Status == PlaybackStatus.Paused));
}

namespace Fileway.Core.Contracts;

public enum PlaybackResumeMode
{
    Resume,
    FromStart,
    Ask,
}

public sealed record PlaybackBookmark(
    TimeSpan Position,
    TimeSpan? Duration,
    string? SourceIdentity,
    PlaybackResumeMode Mode = PlaybackResumeMode.Resume,
    TimeSpan? MinimumPosition = null);

public sealed record PlaybackResumePrompt(TimeSpan Position, PlaybackResumeMode Mode);

/// <summary>Account-bound progress for a remote source. Mutations are never automatically replayed.</summary>
public interface IPlaybackProgressStore
{
    Task<PlaybackBookmark?> ReadAsync(RemoteResourceRef resource, CancellationToken cancellationToken);
    Task WriteAsync(RemoteResourceRef resource, TimeSpan confirmedPosition, TimeSpan? duration, CancellationToken cancellationToken);
    Task ClearAsync(RemoteResourceRef resource, CancellationToken cancellationToken);
}

/// <summary>Keeps clearing remote history and restarting behind the playback boundary.</summary>
public interface IPlaybackResumeControl
{
    Task RestartFromBeginningAsync(CancellationToken cancellationToken);
}

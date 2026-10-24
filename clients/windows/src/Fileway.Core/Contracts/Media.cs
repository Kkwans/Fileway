using System.Text.Json.Serialization;

namespace Fileway.Core.Contracts;

public abstract record PlaybackInput;

public sealed record RemotePlaybackInput(RemoteResourceRef Resource) : PlaybackInput
{
    public override string ToString() => "RemotePlaybackInput (source redacted)";
}

/// <summary>References an already-active download; no partial filename or implicit download start.</summary>
public sealed record DownloadPlaybackInput(DownloadContext Context, TransferTaskRef Task) : PlaybackInput
{
    public override string ToString() => "DownloadPlaybackInput (task redacted)";
}

public sealed record LocalPlaybackInput(
    [property: JsonIgnore] string FullPath,
    string DisplayName,
    RemoteResourceRef? Origin = null) : PlaybackInput
{
    public override string ToString() => "LocalPlaybackInput (source redacted)";
}

public sealed record PlaybackOpenRequest(PlaybackInput Input, TimeSpan? ResumePosition = null);

public enum PlaybackStatus
{
    Preparing,
    Buffering,
    Playing,
    Paused,
    Seeking,
    Ended,
    Stopped,
    Failed,
    Closing,
}

public enum MediaTrackKind
{
    Video,
    Audio,
    Subtitle,
}

public sealed record MediaTrack(long Id, MediaTrackKind Kind, string? Title, string? Language, string? Codec, bool Selected, bool External);

public sealed record PlaybackChapter(string Title, TimeSpan Position);

public sealed record SubtitleCapabilities(bool CanChangeFont, bool CanScale, bool CanPosition, bool CanDelay, bool PreservesScriptStyle);

public sealed record SubtitlePreferences(
    double Scale = 1.0,
    double VerticalPositionPercent = 95.0,
    string? FontFamily = null,
    TimeSpan Delay = default,
    bool OverrideScriptStyle = false,
    bool UseWindowArea = true,
    double FontSize = 38);

/// <summary>Each value is observed or null. Input HDR metadata never implies HDR display output.</summary>
public sealed record PlaybackDiagnostics(
    string? VideoCodec = null,
    string? AudioCodec = null,
    string? Decoder = null,
    string? SourceDynamicRange = null,
    bool? DisplayHdrSupported = null,
    bool? DisplayHdrEnabled = null,
    string? SwapChainFormat = null,
    string? SwapChainColorSpace = null,
    string? ActualOutputMode = null,
    int? VideoWidth = null,
    int? VideoHeight = null,
    ErrorInfo? ProgressError = null);

/// <summary>An engine-observed seekable buffer interval; never inferred from downloaded byte ratios.</summary>
public readonly record struct BufferedTimeRange(TimeSpan Start, TimeSpan End);

/// <summary>
/// Actual download task evidence, separate from engine buffering and indexed playable time.
/// ReadablePrefixBytes is null when current read authorization is unavailable or unconfirmed;
/// Item may still retain historical transfer evidence after playback has stopped.
/// </summary>
public sealed record PlaybackDownloadState(DownloadItem Item, long? ReadablePrefixBytes)
{
    public override string ToString() => "PlaybackDownloadState (task details redacted)";
}

public interface ISubtitleViewportControl
{
    Task SetSubtitleViewportAsync(double width, double height, CancellationToken cancellationToken);
}

public sealed record PlaybackSnapshot(
    Guid SessionId,
    long Generation,
    string DisplayName,
    PlaybackStatus Status,
    TimeSpan Position,
    TimeSpan? ConfirmedPosition,
    TimeSpan? Duration,
    bool Seekable,
    IReadOnlyList<MediaTrack> Tracks,
    SubtitleCapabilities SubtitleCapabilities,
    PlaybackDiagnostics Diagnostics,
    ErrorInfo? Error = null,
    double PlaybackRate = 1.0,
    double Volume = 100.0,
    TimeSpan? BufferedUntil = null,
    IReadOnlyList<PlaybackChapter>? Chapters = null,
    bool IsMuted = false,
    double? DisplayAspectRatio = null,
    PlaybackResumePrompt? ResumePrompt = null,
    IReadOnlyList<BufferedTimeRange>? BufferedRanges = null,
    double? DownloadBytesPerSecond = null,
    // Engine-confirmed pause is independent of transient buffering/seeking; null means not observed yet.
    bool? IsPaused = null,
    PlaybackDownloadState? Download = null,
    double? BufferingPercent = null,
    RemoteResourceRef? Origin = null)
{
    public override string ToString() => "PlaybackSnapshot (private media details redacted)";
}

/// <summary>One same-directory subtitle candidate for the playing remote media.</summary>
public sealed record RemoteSubtitleOption(RemoteResourceRef Resource, long Size)
{
    public override string ToString() => "RemoteSubtitleOption (private locations redacted)";
}

/// <summary>
/// Lists same-directory external subtitle candidates for a remote media resource. Listing never
/// opens a lease; the player separately acquires one when a candidate is explicitly chosen.
/// </summary>
public interface IRemoteSubtitleSource
{
    Task<IReadOnlyList<RemoteSubtitleOption>> ListAsync(RemoteResourceRef media, CancellationToken cancellationToken);
}

/// <summary>One same-directory playable media candidate (episode) relative to the current media.</summary>
public sealed record RemoteMediaOption(RemoteResourceRef Resource, long Size, bool IsCurrent)
{
    public override string ToString() => "RemoteMediaOption (private locations redacted)";
}

/// <summary>
/// Lists same-directory media episodes for the playing remote resource. Listing is bounded and
/// read-only; switching happens through an explicit session play request, never here.
/// </summary>
public interface IRemoteEpisodeSource
{
    Task<IReadOnlyList<RemoteMediaOption>> ListEpisodesAsync(RemoteResourceRef media, CancellationToken cancellationToken);
}

public interface IPlaybackSession : IAsyncDisposable
{
    Guid Id { get; }
    PlaybackSnapshot Snapshot { get; }
    IAsyncEnumerable<PlaybackSnapshot> WatchAsync(CancellationToken cancellationToken);
    Task SetPausedAsync(bool paused, CancellationToken cancellationToken);
    Task SeekAsync(TimeSpan position, CancellationToken cancellationToken);
    /// <summary>Sets volume as a percentage from 0 to 200; 100 is the system mixer level and above it is engine-side amplification.</summary>
    Task SetVolumeAsync(double volume, CancellationToken cancellationToken);
    Task SetMutedAsync(bool muted, CancellationToken cancellationToken);
    /// <summary>Sets the playback multiplier; the snapshot reports the engine-confirmed value.</summary>
    Task SetRateAsync(double rate, CancellationToken cancellationToken);
    Task SelectTrackAsync(MediaTrackKind kind, long? trackId, CancellationToken cancellationToken);
    Task SetSubtitlesAsync(SubtitlePreferences preferences, CancellationToken cancellationToken);
    /// <summary>Temporarily reserves a fraction of the viewport for controls without changing saved subtitle preferences.</summary>
    Task SetSubtitleSafeAreaAsync(double bottomInsetFraction, CancellationToken cancellationToken);
    Task AddSubtitleAsync(PlaybackInput subtitle, CancellationToken cancellationToken);
    Task StopAsync(CancellationToken cancellationToken);
    /// <summary>
    /// Switches this session's primary source to another remote media resource (mpv loadfile
    /// replace). Only remote-to-remote switching is supported; the session keeps its window and
    /// lifecycle, and the previous primary lease is released after the new source is accepted.
    /// </summary>
    Task PlayAsync(PlaybackInput input, CancellationToken cancellationToken);
}

public interface IPlaybackService : IAsyncDisposable
{
    Task<IPlaybackSession> OpenAsync(PlaybackOpenRequest request, CancellationToken cancellationToken);
    Task CloseAccountAsync(AccountKey account, CancellationToken cancellationToken);
}

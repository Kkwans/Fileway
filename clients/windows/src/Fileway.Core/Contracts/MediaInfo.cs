namespace Fileway.Core.Contracts;

public sealed record MediaTrackInfo(int Index, string Codec, string? Language, string? Title, bool Default, bool Forced);

/// <summary>Server-probed media metadata. Location is populated only when the user explicitly requests it. TechnicalError is a partial-failure note, not a request failure.</summary>
public sealed record MediaInfo(
    RemoteResourceRef Resource,
    string Type,
    long Size,
    DateTimeOffset? Modified,
    int? Width,
    int? Height,
    double? DurationSeconds,
    string? Format,
    string? VideoCodec,
    int? VideoBitDepth,
    bool Hdr,
    string? AudioCodec,
    IReadOnlyList<MediaTrackInfo> AudioTracks,
    IReadOnlyList<MediaTrackInfo> SubtitleTracks,
    long? BitRate,
    int? Channels,
    int? SampleRate,
    string? Title,
    string? Artist,
    string? Album,
    string? Date,
    string? Location,
    string? TechnicalError)
{
    public override string ToString() => "MediaInfo (resource redacted)";
}

public interface IMediaInfoService
{
    Task<MediaInfo> GetMediaInfoAsync(RemoteResourceRef resource, bool includeLocation, CancellationToken cancellationToken);
}
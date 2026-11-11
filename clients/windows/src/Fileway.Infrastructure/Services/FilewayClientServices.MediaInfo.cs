using System.Globalization;
using System.Text.Json;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : IMediaInfoService
{
    public async Task<MediaInfo> GetMediaInfoAsync(RemoteResourceRef resource, bool includeLocation, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        var state = GetResourceState(resource);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            string endpoint = MediaEndpoint(resource, "info") + (includeLocation ? "&includeLocation=true" : "");
            var response = await RequestAsync(state, endpoint, linked.Token, requireDownload: true).ConfigureAwait(false);
            RequireExactStatus(response, 200);
            using var body = ParseBody(response);
            EnsureActive(state);
            return ParseMediaInfo(body.RootElement, resource);
        }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    private static MediaInfo ParseMediaInfo(JsonElement row, RemoteResourceRef resource)
    {
        int? width = null, height = null;
        if (row.TryGetProperty("resolution", out var resolution) && resolution.ValueKind == JsonValueKind.Object)
        { width = OptionalInt(resolution, "width"); height = OptionalInt(resolution, "height"); }
        DateTimeOffset? modified = null;
        if (OptionalText(row, "modified") is { } date &&
            DateTimeOffset.TryParse(date, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var parsed)) modified = parsed;
        return new MediaInfo(resource,
            OptionalText(row, "type") ?? "", OptionalLong(row, "size") ?? 0, modified,
            width, height, OptionalDouble(row, "duration"), OptionalText(row, "format"),
            OptionalText(row, "videoCodec"), OptionalInt(row, "videoBitDepth"), OptionalBool(row, "hdr"),
            OptionalText(row, "audioCodec"), ParseMediaTracks(row, "audioTracks"), ParseMediaTracks(row, "subtitleTracks"),
            OptionalLong(row, "bitRate"), OptionalInt(row, "channels"), OptionalInt(row, "sampleRate"),
            OptionalText(row, "title"), OptionalText(row, "artist"), OptionalText(row, "album"), OptionalText(row, "date"),
            OptionalText(row, "location"), OptionalText(row, "technicalError"));
    }

    private static List<MediaTrackInfo> ParseMediaTracks(JsonElement row, string name)
    {
        if (!row.TryGetProperty(name, out var array) || array.ValueKind != JsonValueKind.Array) return [];
        var tracks = new List<MediaTrackInfo>();
        foreach (var track in array.EnumerateArray())
            tracks.Add(new MediaTrackInfo(OptionalInt(track, "index") ?? 0, OptionalText(track, "codec") ?? "",
                OptionalText(track, "language"), OptionalText(track, "title"), OptionalBool(track, "default"), OptionalBool(track, "forced")));
        return tracks;
    }

    private static int? OptionalInt(JsonElement row, string name) =>
        row.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number ? value.GetInt32() : null;
    private static long? OptionalLong(JsonElement row, string name) =>
        row.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number ? value.GetInt64() : null;
    private static double? OptionalDouble(JsonElement row, string name) =>
        row.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number ? value.GetDouble() : null;
    private static bool OptionalBool(JsonElement row, string name) =>
        row.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.True;
}
using System.Diagnostics;
using System.Net;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : ICompatibilityPlaybackSource
{
    private static readonly string[] CompatibilityQualities = ["2160p", "1080p", "720p", "480p"];
    private static readonly TimeSpan CompatibilityOpenTimeout = TimeSpan.FromMinutes(3);
    private static readonly TimeSpan CompatibilityPollInterval = TimeSpan.FromMilliseconds(700);

    internal static IReadOnlyList<string> CompatibilityQualityChoices => CompatibilityQualities;

    /// <summary>
    /// Starts (or reuses) the server compatibility task for this media and quality, waits until the
    /// playlist is streamable, and returns its loopback lease URI. The lease stays bound to the
    /// session and is revoked with it; explicit switches cancel their own superseded task.
    /// </summary>
    async Task<CompatibilityPlaybackSource> ICompatibilityPlaybackSource.OpenAsync(RemoteResourceRef media,
        string quality, CancellationToken cancellationToken) => await OpenCompatibilityPlaybackAsync(media, quality, cancellationToken).ConfigureAwait(false);

    private async Task<CompatibilityPlaybackSource> OpenCompatibilityPlaybackAsync(RemoteResourceRef media,
        string quality, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(media);
        ValidateConditionalDownloadSource(media);
        string normalized = quality?.Trim().ToLowerInvariant() ?? "";
        if (normalized != "source" && !CompatibilityQualities.Contains(normalized))
            throw Safe(ErrorCode.InvalidRequest, Message(ErrorCode.InvalidRequest));
        var state = GetResourceState(media);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            RequireDownload(state);
            var response = await SessionRequestAsync(state, new
            {
                method = "POST",
                endpoint = "/media/hls",
                body = new { path = media.DisplayPath, quality = normalized },
            }, requireDownload: true, HostCallSafety.Control, linked.Token, operation: "request").ConfigureAwait(false);
            RequireExactStatus(response, 200);
            using var started = ParseBody(response);
            string id = RequiredText(started.RootElement.GetProperty("id"));
            string reported = OptionalText(started.RootElement, "state") ?? "queued";
            string? playlist = OptionalText(started.RootElement, "playlistUrl");

            // Streamable is the honest play gate: the server says its playlist and initial
            // segments are ready. Transcoding continues in the background afterwards.
            var deadline = Stopwatch.GetTimestamp() + (long)(CompatibilityOpenTimeout.TotalSeconds * Stopwatch.Frequency);
            while (playlist is null && reported is not ("streamable" or "completed" or "failed" or "canceled"))
            {
                linked.Token.ThrowIfCancellationRequested();
                if (Stopwatch.GetTimestamp() >= deadline) throw Safe(ErrorCode.Timeout, Message(ErrorCode.Timeout));
                await Task.Delay(CompatibilityPollInterval, linked.Token).ConfigureAwait(false);
                RequireDownload(state);
                var poll = await SessionRequestAsync(state, new
                {
                    method = "GET",
                    endpoint = "/media/hls/" + Uri.EscapeDataString(id),
                }, requireDownload: true, HostCallSafety.Control, linked.Token, operation: "request").ConfigureAwait(false);
                RequireExactStatus(poll, 200);
                using var status = ParseBody(poll);
                reported = OptionalText(status.RootElement, "state") ?? "queued";
                playlist = OptionalText(status.RootElement, "playlistUrl");
            }
            if (playlist is null || reported is "failed" or "canceled")
                throw Safe(ErrorCode.SourceChanged, "兼容播放任务未能就绪，请重试或直接播放原文件。");
            var lease = await SessionRequestAsync(state, new { endpoint = playlist }, requireDownload: true,
                HostCallSafety.ReadOnly, linked.Token, operation: "asset").ConfigureAwait(false);
            string address = RequiredText(lease);
            if (!Uri.TryCreate(address, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttp ||
                uri.Host != "127.0.0.1" || uri.Port <= 0 || uri.IsDefaultPort || uri.UserInfo.Length != 0 ||
                uri.Fragment.Length != 0)
                throw InvalidResponse();
            lock (_gate)
            {
                linked.Token.ThrowIfCancellationRequested();
                RequireDownload(state);
            }
            return new(uri, id, normalized);
        }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
    }

    async Task ICompatibilityPlaybackSource.CancelAsync(RemoteResourceRef media, string taskId,
        CancellationToken cancellationToken) => await CancelCompatibilityPlaybackAsync(media, taskId, cancellationToken).ConfigureAwait(false);

    private async Task CancelCompatibilityPlaybackAsync(RemoteResourceRef media, string taskId, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(media);
        if (string.IsNullOrEmpty(taskId)) return;
        var state = GetResourceState(media);
        try
        {
            _ = await SessionRequestAsync(state, new
            {
                method = "POST",
                endpoint = "/media/hls/" + Uri.EscapeDataString(taskId) + "/cancel",
            }, requireDownload: true, HostCallSafety.Control, cancellationToken, operation: "request").ConfigureAwait(false);
        }
        catch (Exception error) when (error is FilewayException { Error.Code: ErrorCode.ProtocolMismatch or
            ErrorCode.SourceChanged or ErrorCode.InvalidRequest } or OperationCanceledException)
        { /* A finished or already-canceled task is the expected race; nothing to report. */ }
    }
}

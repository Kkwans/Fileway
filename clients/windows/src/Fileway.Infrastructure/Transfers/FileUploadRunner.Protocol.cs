using System.Diagnostics;
using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Transfers;

public sealed partial class FileUploadRunner
{
    private async Task<TransferTaskRecord> UploadAsync(RunState state, IUploadSource source)
    {
        IUploadLease? lease = null;
        try
        {
            await CheckIntentAsync(state, cancellation: false).ConfigureAwait(false);
            var pinned = state.Record.Checkpoint.Source ?? throw Safe(ErrorCode.SourceChanged);
            bool resume = state.Record.Checkpoint.ConfirmedOffset is not null ||
                state.Record.Checkpoint.Uncertainty != TransferUncertainty.None;
            if (!resume && state.Record.Plan.Conflict == FileConflictPolicy.Skip && await TargetExistsAsync(state).ConfigureAwait(false))
            {
                await SaveAsync(state, state.Record.Checkpoint with { Phase = TransferPhase.Skipped, Error = null }, release: true).ConfigureAwait(false);
                return state.Record;
            }
            if (resume && state.Record.Checkpoint.Phase != TransferPhase.Reconciling)
                await SaveAsync(state, state.Record.Checkpoint with { Phase = TransferPhase.Reconciling }, release: false).ConfigureAwait(false);
            await VerifySourceAsync(source, pinned, state.Execution.Stop.Token).ConfigureAwait(false);
            lease = await AcquireLeaseAsync(state, resume).ConfigureAwait(false);
            if (!resume)
            {
                using var create = Request(HttpMethod.Post, lease);
                create.Content = new ByteArrayContent(Array.Empty<byte>());
                create.Headers.Add("Upload-Length", Number(lease.Length));
                var dispatch = await DispatchAsync(state, lease, create, cancellation: false).ConfigureAwait(false);
                using var response = dispatch.Response;
                if (response.StatusCode != HttpStatusCode.Created)
                    await ThrowStatusAsync(state, dispatch).ConfigureAwait(false);
                if (Header(response, "Location") != lease.Address.AbsoluteUri || !ValidTusVersion(response))
                    throw Safe(ErrorCode.ProtocolMismatch);
                // Creation has no confirmed offset and can already publish an empty file.
            }
            long offset = await ReadHeadAsync(state, lease, cancellation: false).ConfigureAwait(false);
            long length = pinned.Length;
            if (offset == length) return await CompleteAsync(state).ConfigureAwait(false);
            var buffer = new byte[_options.ChunkSizeBytes];
            while (offset < length)
            {
                await CheckIntentAsync(state, cancellation: false).ConfigureAwait(false);
                await VerifySourceAsync(source, pinned, state.Execution.Stop.Token).ConfigureAwait(false);
                int count = checked((int)Math.Min(buffer.Length, length - offset));
                await ReadChunkAsync(source.Content, offset, buffer.AsMemory(0, count), state.Execution.Stop.Token).ConfigureAwait(false);
                await VerifySourceAsync(source, pinned, state.Execution.Stop.Token).ConfigureAwait(false);
                var activity = new RequestActivity();
                using var patch = Request(HttpMethod.Patch, lease);
                using var body = new ChunkContent(buffer.AsMemory(0, count), bytes =>
                {
                    Interlocked.Add(ref state.Execution.SentBytes, bytes);
                    activity.Touch();
                    QueueProgress(state);
                });
                patch.Content = body;
                patch.Headers.Add("Upload-Offset", Number(offset));
                var dispatch = await DispatchAsync(state, lease, patch, cancellation: false, activity).ConfigureAwait(false);
                using var response = dispatch.Response;
                if (response.StatusCode != HttpStatusCode.NoContent)
                    await ThrowStatusAsync(state, dispatch).ConfigureAwait(false);
                long acknowledged = DecimalHeader(response, "Upload-Offset");
                if (!ValidTusVersion(response) || acknowledged != offset + count || body.SentBytes != count)
                    throw Safe(ErrorCode.ProtocolMismatch);
                offset = acknowledged;
                await SaveAsync(state, state.Record.Checkpoint with
                {
                    Phase = TransferPhase.Running, ConfirmedOffset = offset, Error = null,
                    Uncertainty = offset == length ? TransferUncertainty.RemoteWrite : TransferUncertainty.None,
                }, release: false).ConfigureAwait(false);
                // The original acknowledgement is durable before observing a late stop or session change.
                await CheckIntentAsync(state, cancellation: false).ConfigureAwait(false);
                await VerifySourceAsync(source, pinned, state.Execution.Stop.Token).ConfigureAwait(false);
            }
            await DisposeLeaseAsync(lease).ConfigureAwait(false);
            lease = null;
            await SaveAsync(state, state.Record.Checkpoint with { Phase = TransferPhase.Reconciling }, release: false).ConfigureAwait(false);
            lease = await AcquireLeaseAsync(state, resume: true).ConfigureAwait(false);
            if (await ReadHeadAsync(state, lease, cancellation: false).ConfigureAwait(false) != length)
            {
                await SaveAsync(state, state.Record.Checkpoint with
                { Phase = TransferPhase.Reconciling, Uncertainty = TransferUncertainty.RemoteWrite }, release: false).ConfigureAwait(false);
                throw Safe(ErrorCode.Conflict);
            }
            return await CompleteAsync(state).ConfigureAwait(false);
        }
        finally
        {
            if (lease is not null) await CleanupLeaseAsync(lease).ConfigureAwait(false);
        }
    }

    private async Task<TransferTaskRecord> ExecuteCancellationAsync(Execution execution, TransferTaskRecord record)
    {
        record = await ClaimAsync(record, recovery: true, execution.Stop.Token).ConfigureAwait(false);
        if (IsTerminal(record) || record.Run is null) return record;
        var state = new RunState(execution, record);
        IUploadLease? lease = null;
        try
        {
            if (record.Checkpoint.RemotePublished) return await CompleteAsync(state).ConfigureAwait(false);
            if (record.Checkpoint.RemoteCanceled || record.Checkpoint.ConfirmedOffset is null &&
                record.Checkpoint.Uncertainty == TransferUncertainty.None)
            {
                await SaveAsync(state, record.Checkpoint with { Phase = TransferPhase.Canceled, Error = null }, release: true).ConfigureAwait(false);
                return state.Record;
            }
            if (record.Checkpoint.Source is null) throw Safe(ErrorCode.OutcomeUnknown);
            lease = await AcquireLeaseAsync(state, resume: true).ConfigureAwait(false);
            long offset = await ReadHeadAsync(state, lease, cancellation: true).ConfigureAwait(false);
            if (offset == lease.Length) return await CompleteAsync(state).ConfigureAwait(false);
            await SaveAsync(state, state.Record.Checkpoint with { Phase = TransferPhase.CancelRequested }, release: false).ConfigureAwait(false);
            using var delete = Request(HttpMethod.Delete, lease);
            var dispatch = await DispatchAsync(state, lease, delete, cancellation: true).ConfigureAwait(false);
            using var response = dispatch.Response;
            if (response.StatusCode == HttpStatusCode.NoContent)
            {
                if (!ValidTusVersion(response)) throw Safe(ErrorCode.ProtocolMismatch);
                await SaveAsync(state, state.Record.Checkpoint with
                { Phase = TransferPhase.Canceled, RemoteCanceled = true, Uncertainty = TransferUncertainty.None, Error = null }, release: true).ConfigureAwait(false);
                return state.Record;
            }
            if (response.StatusCode is HttpStatusCode.Forbidden or HttpStatusCode.Unauthorized &&
                dispatch.PreviousUncertainty == TransferUncertainty.None)
            {
                await SaveAsync(state, state.Record.Checkpoint with
                {
                    Phase = TransferPhase.Canceled, RemoteCanceled = false, Uncertainty = TransferUncertainty.None,
                    Error = Safe(response.StatusCode == HttpStatusCode.Forbidden ? ErrorCode.PermissionDenied : ErrorCode.AuthenticationExpired,
                        (int)response.StatusCode).Error,
                }, release: true).ConfigureAwait(false);
                return state.Record;
            }
            await ThrowStatusAsync(state, dispatch).ConfigureAwait(false);
            throw Safe(ErrorCode.ProtocolMismatch);
        }
        catch (Exception error) when (error is FilewayException or IOException or HttpRequestException or OperationCanceledException)
        { return await ReleaseFailureAsync(state, Normalize(error)).ConfigureAwait(false); }
        finally
        {
            if (lease is not null) await CleanupLeaseAsync(lease).ConfigureAwait(false);
        }
    }

    private async Task<bool> TargetExistsAsync(RunState state)
    {
        using var budget = CancellationTokenSource.CreateLinkedTokenSource(state.Execution.Stop.Token);
        budget.CancelAfter(_options.ControlCallTimeout);
        try
        {
            var existing = await _files.GetAsync(state.Target, budget.Token).WaitAsync(budget.Token).ConfigureAwait(false);
            if (existing.Resource.Account != state.Target.Account || existing.Resource.WirePath != state.Target.WirePath)
                throw Safe(ErrorCode.ProtocolMismatch);
            return true;
        }
        catch (FilewayException error) when (error.Error.HttpStatus == 404) { return false; }
        catch (OperationCanceledException) { throw Safe(state.Execution.Stop.IsCancellationRequested ? ErrorCode.Canceled : ErrorCode.Timeout); }
        catch (Exception error) { throw Normalize(error); }
    }

    private async Task<IUploadLease> AcquireLeaseAsync(RunState state, bool resume)
    {
        long length = state.Record.Checkpoint.Source?.Length ?? throw Safe(ErrorCode.InvalidRequest);
        var request = new UploadLeaseRequest(state.Target, length, state.Run.Task.Id,
            Overwrite: state.Record.Plan.Conflict == FileConflictPolicy.Replace, Resume: resume);
        var lease = await AcquireOwnedAsync(token => _leases.AcquireUploadAsync(request, token), state.Execution.Stop.Token).ConfigureAwait(false);
        if (lease.TransferId == request.TransferId && lease.Length == length && lease.IsResume == resume &&
            lease.Resource.Account == state.Target.Account && lease.Resource.WirePath == state.Target.WirePath && ValidAddress(lease.Address))
            return lease;
        await CleanupLeaseAsync(lease).ConfigureAwait(false);
        throw Safe(ErrorCode.ProtocolMismatch);
    }

    private static bool ValidAddress(Uri address)
    {
        if (address is null || !address.IsAbsoluteUri) return false;
        string original = address.OriginalString;
        const string prefix = "http://127.0.0.1:";
        if (!original.StartsWith(prefix, StringComparison.Ordinal)) return false;
        int pathStart = original.IndexOf('/', prefix.Length);
        if (pathStart < 0 || !int.TryParse(original.AsSpan(prefix.Length, pathStart - prefix.Length), NumberStyles.None,
            CultureInfo.InvariantCulture, out int port) || port is <= 0 or > 65535 or 80) return false;
        string path = original[pathStart..];
        const string stream = "/stream/";
        if (!path.StartsWith(stream, StringComparison.Ordinal) || path.Length == stream.Length) return false;
        foreach (char character in path.AsSpan(stream.Length))
            if (!(char.IsAsciiLetterOrDigit(character) || character is '-' or '.' or '_' or '~')) return false;
        return address.Scheme == Uri.UriSchemeHttp && address.Host == "127.0.0.1" && address.Port == port && !address.IsDefaultPort &&
            address.UserInfo.Length == 0 && address.Query.Length == 0 && address.Fragment.Length == 0 && address.AbsolutePath == path;
    }

    private async Task<long> ReadHeadAsync(RunState state, IUploadLease lease, bool cancellation)
    {
        using var head = Request(HttpMethod.Head, lease);
        var dispatch = await DispatchAsync(state, lease, head, cancellation).ConfigureAwait(false);
        using var response = dispatch.Response;
        if (response.StatusCode != HttpStatusCode.OK)
        {
            if (response.StatusCode is HttpStatusCode.NotFound or HttpStatusCode.Gone) throw Safe(ErrorCode.OutcomeUnknown, (int)response.StatusCode);
            await ThrowStatusAsync(state, dispatch).ConfigureAwait(false);
        }
        long offset = DecimalHeader(response, "Upload-Offset");
        if (!ValidTusVersion(response) || DecimalHeader(response, "Upload-Length") != lease.Length || offset > lease.Length)
            throw Safe(ErrorCode.ProtocolMismatch);
        await SaveAsync(state, state.Record.Checkpoint with
        { Phase = TransferPhase.Running, ConfirmedOffset = offset, Uncertainty = TransferUncertainty.None, Error = null }, release: false).ConfigureAwait(false);
        return offset;
    }

    private async Task<TransferTaskRecord> CompleteAsync(RunState state)
    {
        await SaveAsync(state, state.Record.Checkpoint with
        { Phase = TransferPhase.Completed, RemotePublished = true, Uncertainty = TransferUncertainty.None, Error = null }, release: true).ConfigureAwait(false);
        return state.Record;
    }

    private async Task<Dispatch> DispatchAsync(RunState state, IUploadLease lease, HttpRequestMessage request,
        bool cancellation, RequestActivity? activity = null)
    {
        await CheckIntentAsync(state, cancellation).ConfigureAwait(false);
        var before = state.Record.Checkpoint.Uncertainty;
        await SaveAsync(state, state.Record.Checkpoint with
        { Uncertainty = before | TransferUncertainty.RemoteWrite, Error = null }, release: false).ConfigureAwait(false);
        try { await CheckIntentAsync(state, cancellation).ConfigureAwait(false); }
        catch (Exception error) when (error is OperationCanceledException or FilewayException { Error.Code: ErrorCode.Canceled })
        {
            await SaveAsync(state, state.Record.Checkpoint with { Uncertainty = before }, release: false).ConfigureAwait(false);
            throw;
        }
        using var requestStop = CancellationTokenSource.CreateLinkedTokenSource(state.Execution.Stop.Token);
        using var monitorStop = CancellationTokenSource.CreateLinkedTokenSource(requestStop.Token);
        activity ??= new();
        activity.Touch();
        Task<HttpResponseMessage> pending;
        try { pending = _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, requestStop.Token); }
        catch (Exception error) { throw Normalize(error); }
        var watchdog = WatchRequestAsync(lease, activity, monitorStop.Token);
        try
        {
            _ = await Task.WhenAny(pending, watchdog).ConfigureAwait(false);
            if (pending.IsCompleted) return new(await pending.ConfigureAwait(false), before);
            FilewayException failure;
            try { await watchdog.ConfigureAwait(false); failure = Safe(ErrorCode.Canceled); }
            catch (Exception error) { failure = Normalize(error); }
            await requestStop.CancelAsync().ConfigureAwait(false);
            // A response already in flight still gets its original ACK processed after a late stop.
            try { return new(await pending.WaitAsync(_options.ControlCallTimeout).ConfigureAwait(false), before); }
            catch (TimeoutException) { _ = DisposeLateResponseAsync(pending); throw failure; }
            catch (Exception) { throw failure; }
        }
        finally
        {
            await monitorStop.CancelAsync().ConfigureAwait(false);
            try { await watchdog.ConfigureAwait(false); }
            catch (Exception) { /* A valid HTTP ACK has priority over a late statistics failure. */ }
        }
    }

    private async Task WatchRequestAsync(IUploadLease lease, RequestActivity activity, CancellationToken cancellationToken)
    {
        long previousSent = 0;
        long lastSample = Stopwatch.GetTimestamp();
        while (true)
        {
            TimeSpan remaining = _options.RequestIdleTimeout - activity.Idle;
            if (remaining <= TimeSpan.Zero) throw Safe(ErrorCode.Timeout);
            TimeSpan untilSample = _options.ProgressInterval - Stopwatch.GetElapsedTime(lastSample);
            TimeSpan delay = untilSample <= TimeSpan.Zero ? TimeSpan.Zero : remaining < untilSample ? remaining : untilSample;
            await Task.Delay(delay, cancellationToken).ConfigureAwait(false);
            if (activity.Idle >= _options.RequestIdleTimeout) throw Safe(ErrorCode.Timeout);
            if (Stopwatch.GetElapsedTime(lastSample) < _options.ProgressInterval) continue;
            lastSample = Stopwatch.GetTimestamp();
            remaining = _options.RequestIdleTimeout - activity.Idle;
            if (remaining <= TimeSpan.Zero) throw Safe(ErrorCode.Timeout);
            using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            budget.CancelAfter(remaining < _options.ControlCallTimeout ? remaining : _options.ControlCallTimeout);
            try
            {
                var statistics = await lease.ReadUploadStatisticsAsync(budget.Token).WaitAsync(budget.Token).ConfigureAwait(false);
                if (statistics is null) throw Safe(ErrorCode.SourceChanged);
                if (statistics.SentBytes > previousSent) activity.Touch();
                previousSent = Math.Max(previousSent, statistics.SentBytes);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { throw Safe(ErrorCode.Timeout); }
        }
    }

    private async Task ReadChunkAsync(Stream source, long offset, Memory<byte> buffer, CancellationToken cancellationToken)
    {
        try
        {
            if (source.Seek(offset, SeekOrigin.Begin) != offset) throw Safe(ErrorCode.SourceChanged);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or NotSupportedException or InvalidOperationException)
        { throw Safe(ErrorCode.SourceChanged); }
        int read = 0;
        while (read < buffer.Length)
        {
            using var idle = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            idle.CancelAfter(_options.RequestIdleTimeout);
            int count;
            try
            {
                count = await source.ReadAsync(buffer.Slice(read, Math.Min(64 * 1024, buffer.Length - read)), idle.Token)
                    .AsTask().WaitAsync(idle.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) { throw Safe(cancellationToken.IsCancellationRequested ? ErrorCode.Canceled : ErrorCode.Timeout); }
            catch (Exception error) { throw Normalize(error, source: true); }
            if (count == 0) throw Safe(ErrorCode.SourceChanged);
            read += count;
        }
    }

    private async Task ThrowStatusAsync(RunState state, Dispatch dispatch)
    {
        int status = (int)dispatch.Response.StatusCode;
        // These readable rejections precede a data mutation. An earlier unknown request remains protected.
        if (dispatch.PreviousUncertainty == TransferUncertainty.None && status is 400 or 401 or 403 or 405 or 412 or 413 or 415)
            await SaveAsync(state, state.Record.Checkpoint with { Uncertainty = TransferUncertainty.None }, release: false).ConfigureAwait(false);
        throw Safe(status switch
        {
            401 => ErrorCode.AuthenticationExpired,
            403 => ErrorCode.PermissionDenied,
            408 or 504 => ErrorCode.Timeout,
            409 => ErrorCode.Conflict,
            410 or 412 => ErrorCode.SourceChanged,
            429 => ErrorCode.Busy,
            400 or 413 => ErrorCode.InvalidRequest,
            405 or 415 => ErrorCode.ProtocolMismatch,
            >= 500 => ErrorCode.NetworkUnavailable,
            _ => ErrorCode.ProtocolMismatch,
        }, status);
    }

    private async Task DisposeLeaseAsync(IUploadLease lease)
    {
        try { await lease.DisposeAsync().AsTask().WaitAsync(_options.ControlCallTimeout).ConfigureAwait(false); }
        catch (TimeoutException) { throw Safe(ErrorCode.Timeout); }
        catch (Exception error) { throw Normalize(error); }
    }

    private async Task CleanupLeaseAsync(IUploadLease lease)
    {
        try { await DisposeLeaseAsync(lease).ConfigureAwait(false); }
        catch (FilewayException) { /* Cleanup cannot erase a durable ACK or replace the original failure. */ }
    }

    private static async Task DisposeLateResponseAsync(Task<HttpResponseMessage> response)
    {
        try { (await response.ConfigureAwait(false)).Dispose(); }
        catch (Exception) { }
    }

    private static HttpRequestMessage Request(HttpMethod method, IUploadLease lease)
    {
        var request = new HttpRequestMessage(method, lease.Address) { Version = HttpVersion.Version11,
            VersionPolicy = HttpVersionPolicy.RequestVersionExact };
        request.Headers.Add("Tus-Resumable", "1.0.0");
        return request;
    }

    private static string Number(long value) => value.ToString(CultureInfo.InvariantCulture);

    private static string? Header(HttpResponseMessage response, string name)
    {
        if (!response.Headers.TryGetValues(name, out var values)) return null;
        using var iterator = values.GetEnumerator();
        if (!iterator.MoveNext()) return null;
        string value = iterator.Current;
        return iterator.MoveNext() ? null : value;
    }

    private static long DecimalHeader(HttpResponseMessage response, string name)
    {
        string? text = Header(response, name);
        if (string.IsNullOrEmpty(text)) throw Safe(ErrorCode.ProtocolMismatch);
        foreach (char character in text)
            if (character is < '0' or > '9') throw Safe(ErrorCode.ProtocolMismatch);
        return long.TryParse(text, NumberStyles.None, CultureInfo.InvariantCulture, out long value) && value >= 0
            ? value : throw Safe(ErrorCode.ProtocolMismatch);
    }

    private static bool ValidTusVersion(HttpResponseMessage response) =>
        !response.Headers.Contains("Tus-Resumable") || Header(response, "Tus-Resumable") == "1.0.0";

    private sealed record Dispatch(HttpResponseMessage Response, TransferUncertainty PreviousUncertainty);

    private sealed class RequestActivity
    {
        private long _last = Stopwatch.GetTimestamp();
        internal TimeSpan Idle => Stopwatch.GetElapsedTime(Interlocked.Read(ref _last));
        internal void Touch() => Interlocked.Exchange(ref _last, Stopwatch.GetTimestamp());
    }

    private sealed class ChunkContent : HttpContent
    {
        private readonly ReadOnlyMemory<byte> _bytes;
        private readonly Action<int> _sent;
        private long _sentBytes;

        internal ChunkContent(ReadOnlyMemory<byte> bytes, Action<int> sent)
        {
            _bytes = bytes;
            _sent = sent;
            Headers.ContentType = new MediaTypeHeaderValue("application/offset+octet-stream");
            Headers.ContentLength = bytes.Length;
        }

        internal long SentBytes => Interlocked.Read(ref _sentBytes);
        protected override bool TryComputeLength(out long length) { length = _bytes.Length; return true; }
        protected override Task SerializeToStreamAsync(Stream stream, TransportContext? context) =>
            SerializeToStreamAsync(stream, context, CancellationToken.None);

        protected override async Task SerializeToStreamAsync(Stream stream, TransportContext? context, CancellationToken cancellationToken)
        {
            int written = 0;
            while (written < _bytes.Length)
            {
                int count = Math.Min(64 * 1024, _bytes.Length - written);
                await stream.WriteAsync(_bytes.Slice(written, count), cancellationToken).ConfigureAwait(false);
                Interlocked.Add(ref _sentBytes, count);
                _sent(count);
                written += count;
            }
        }
    }
}

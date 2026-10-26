using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;

namespace Fileway.Infrastructure.Transfers;

public sealed partial class FileDownloadRunner
{
    /// <summary>
    /// Durable resume entry. The record must carry pinned-version partial evidence; the claim
    /// fences any stale run from a previous process and re-enters the single-writer admission.
    /// </summary>
    public async Task<TransferTaskRecord> ResumeAsync(TransferTaskRef task, CancellationToken cancellationToken)
    {
        long epoch = AdmissionEpoch();
        var record = await ReadRequiredAsync(task, cancellationToken).ConfigureAwait(false);
        cancellationToken.ThrowIfCancellationRequested();
        RequirePlan(record);
        if (IsTerminal(record)) return record;
        RequireResumable(record);
        return await StartExecution(task, ExecutionMode.Resume, epoch, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>Resume-capable source access, or null when this server/route has no conditional protocol.</summary>
    private IConditionalDownloadSourceProvider? ConditionalProvider(RunState state) =>
        _sources is IConditionalDownloadSourceProvider conditional && conditional.CanUseConditionalDownload(state.Source)
            ? conditional : null;

    private static void RequireResumable(TransferTaskRecord record)
    {
        _ = TransferTaskValidation.Plan(record.Plan);
        if (record.Plan.Direction != TransferDirection.Download || record.Plan.Conflict != FileConflictPolicy.Fail ||
            record.Plan.BatchId is not null) throw Safe(ErrorCode.InvalidRequest);
        if (record.Intent == TransferIntent.Cancel) throw Safe(ErrorCode.InvalidRequest);
        var checkpoint = record.Checkpoint;
        // Resume authority is the durable evidence, never a UI state: a pinned source version,
        // a positively owned unpublished partial and a confirmed flushed offset. Reconciling is
        // accepted only right after this runner's own recovery claim has fenced a stale run.
        if (checkpoint.Uncertainty != TransferUncertainty.None ||
            checkpoint.Files is not { PartialPath: not null, PartialIdentity: not null, PublishedFileIdentity: null } ||
            checkpoint.Source is not { } source || source.Length < 0 || string.IsNullOrEmpty(source.SourceVersion) ||
            checkpoint.ConfirmedOffset is not { } offset || offset < 0 || offset > source.Length ||
            checkpoint.LocalSha256 is not null && offset != source.Length)
            throw Safe(ErrorCode.InvalidRequest);
        if (checkpoint.Phase is not (TransferPhase.Paused or TransferPhase.Running or TransferPhase.Reconciling or
            TransferPhase.WaitingNetwork or TransferPhase.WaitingAuthentication or TransferPhase.Failed))
            throw Safe(ErrorCode.InvalidRequest);
    }

    private async Task<TransferTaskRecord> ResumeDownloadAsync(RunState state)
    {
        IDownloadDestination? destination = null;
        IDownloadLease? lease = null;
        try
        {
            await CheckIntentAsync(state).ConfigureAwait(false);
            var checkpoint = state.Record.Checkpoint;
            RequireResumable(state.Record);
            string version = checkpoint.Source?.SourceVersion ?? throw Safe(ErrorCode.InvalidRequest);
            state.ConditionalVersion = version;
            var provider = ConditionalProvider(state) ?? throw Safe(ErrorCode.CapabilityMissing);
            // Reopens only positively identified owned partial evidence; it never creates files.
            // Read admission covers exactly the verified prefix below the confirmed offset.
            destination = await AcquireOwnedAsync(token => _destinations.OpenOwnedReadableAsync(
                state.Record.Plan.LocalPath, checkpoint.Files!, token), state.Execution.Stop.Token).ConfigureAwait(false);
            RequireOwnedEvidence(state, destination);
            if (destination.IsPublished) { state.ResumeEvidenceBroken = true; throw Safe(ErrorCode.InvalidRequest); }
            long confirmed = checkpoint.ConfirmedOffset!.Value;
            if (destination.Length < confirmed)
            {
                // The confirmed prefix itself is gone; this evidence can never be trusted again.
                state.ResumeEvidenceBroken = true;
                throw Safe(ErrorCode.ProtocolMismatch);
            }
            if (destination.Length > confirmed)
                await destination.TruncateAsync(confirmed, state.Execution.Stop.Token).ConfigureAwait(false);
            state.Destination = destination;
            state.DestinationAccepted = true;
            await SaveAsync(state, state.Record.Checkpoint with { Phase = TransferPhase.Running }, release: false).ConfigureAwait(false);
            // After truncation the readable prefix is exactly the durable confirmed offset, so
            // progressive playback may resume alongside the continuing transfer.
            AcceptReaderDestination(state, destination);
            await CheckIntentAsync(state).ConfigureAwait(false);

            // Re-observe the source through the conditional protocol; any version, length or
            // timestamp drift rejects the resume instead of extending a mismatched prefix.
            var metadata = await ControlAsync(token => provider.PrepareConditionalDownloadAsync(state.Source, token),
                state.Execution.Stop.Token).ConfigureAwait(false);
            RequireRemoteConditional(state, metadata.Resource, version);
            if (metadata.Size != checkpoint.Source.Length || metadata.Modified?.ToUniversalTime() != checkpoint.Source.Modified)
                throw Safe(ErrorCode.SourceChanged);
            await CheckIntentAsync(state).ConfigureAwait(false);
            if (confirmed < checkpoint.Source.Length)
            {
                lease = await AcquireOwnedAsync(token => provider.AcquireConditionalDownloadAsync(
                    new(state.Source with { SourceVersion = version }, checkpoint.Source.Length, version), token),
                    state, state.Execution.Stop.Token).ConfigureAwait(false);
                RequireRemoteConditional(state, lease.Resource, version);
                if (lease.Length != checkpoint.Source.Length) throw Safe(ErrorCode.SourceChanged);
                RequireAddress(lease.Address);
                await ReceiveRangeAsync(state, destination, lease, confirmed).ConfigureAwait(false);
            }
            await CheckIntentAsync(state).ConfigureAwait(false);
            long flushed = await destination.FlushToDiskAsync(state.Execution.Stop.Token).ConfigureAwait(false);
            if (flushed != checkpoint.Source.Length || destination.Length != checkpoint.Source.Length)
                throw Safe(ErrorCode.SourceChanged);
            var local = await destination.CalculateSha256Async(state.Execution.Stop.Token).ConfigureAwait(false);
            if (local.Length != checkpoint.Source.Length) throw Safe(ErrorCode.SourceChanged);
            string localHash = RequireHash(local.Sha256);
            await SaveAsync(state, state.Record.Checkpoint with
            { Phase = TransferPhase.Verifying, ConfirmedOffset = flushed, LocalSha256 = localHash }, release: false).ConfigureAwait(false);
            await CheckIntentAsync(state).ConfigureAwait(false);
            var server = await provider.CalculateConditionalDownloadChecksumAsync(
                new(state.Source with { SourceVersion = version }, checkpoint.Source.Length, version), state.Execution.Stop.Token).ConfigureAwait(false);
            RequireRemoteConditional(state, server.Resource, version);
            if (server.Algorithm != ChecksumAlgorithm.Sha256 || server.Size != checkpoint.Source.Length ||
                server.Modified?.ToUniversalTime() != checkpoint.Source.Modified)
                throw Safe(ErrorCode.SourceChanged);
            string serverHash = RequireHash(server.Digest);
            if (!StringComparer.Ordinal.Equals(localHash, serverHash)) throw Safe(ErrorCode.SourceChanged);
            await SaveAsync(state, state.Record.Checkpoint with { ServerSha256 = serverHash }, release: false).ConfigureAwait(false);
            await CheckIntentAsync(state).ConfigureAwait(false);
            await SaveAsync(state, state.Record.Checkpoint with
            { Phase = TransferPhase.Publishing, Uncertainty = TransferUncertainty.LocalPublish }, release: false).ConfigureAwait(false);
            await CheckIntentAsync(state).ConfigureAwait(false);
            state.PublishAttempted = true;
            string identity = await destination.PublishNoReplaceAsync(state.Execution.Stop.Token).ConfigureAwait(false);
            RequirePublication(state, destination, identity);
            await CompleteAsync(state, destination, identity).ConfigureAwait(false);
            return state.Record;
        }
        catch (Exception error) when (error is FilewayException or OperationCanceledException or IOException or
            UnauthorizedAccessException or HttpRequestException or InvalidOperationException)
        {
            var result = await FinishFailureAsync(state, destination, Normalize(error)).ConfigureAwait(false);
            if (state.RejectedAcquisitionReleaseFailure is { } releaseFailure) throw releaseFailure;
            return result;
        }
        finally
        {
            try { if (lease is not null) await lease.DisposeAsync().ConfigureAwait(false); }
            finally { if (destination is not null) await destination.DisposeAsync().ConfigureAwait(false); }
        }
    }

    /// <summary>Version-bound receive. A full-body 200 (no request Range) or a 206 with an exact
    /// Content-Range is accepted; a 200 answering a Range request is never appended.</summary>
    private async Task ReceiveRangeAsync(RunState state, IDownloadDestination destination, IDownloadLease lease, long fromOffset)
    {
        string version = state.ConditionalVersion ?? throw Safe(ErrorCode.CapabilityMissing);
        long total = lease.Length;
        if (fromOffset < 0 || fromOffset > total || destination.Length != fromOffset) throw Safe(ErrorCode.ProtocolMismatch);
        using var request = new HttpRequestMessage(HttpMethod.Get, lease.Address);
        bool partial = fromOffset > 0;
        if (partial) request.Headers.Range = new(fromOffset, total - 1);
        request.Headers.AcceptEncoding.Add(new("identity"));
        request.Headers.CacheControl = new() { NoCache = true, NoStore = true };
        using var headersBudget = CancellationTokenSource.CreateLinkedTokenSource(state.Execution.Stop.Token);
        headersBudget.CancelAfter(_options.RequestIdleTimeout);
        HttpResponseMessage response;
        try { response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, headersBudget.Token).ConfigureAwait(false); }
        catch (OperationCanceledException)
        { throw Safe(state.Execution.Stop.IsCancellationRequested ? ErrorCode.Canceled : ErrorCode.Timeout); }
        using (response)
        {
            if (response.StatusCode is not (HttpStatusCode.OK or HttpStatusCode.PartialContent) || partial && response.StatusCode != HttpStatusCode.PartialContent)
                throw Safe(response.StatusCode switch
                {
                    HttpStatusCode.Unauthorized => ErrorCode.AuthenticationExpired,
                    HttpStatusCode.Forbidden => ErrorCode.PermissionDenied,
                    HttpStatusCode.NotFound or HttpStatusCode.PreconditionFailed or
                        HttpStatusCode.RequestedRangeNotSatisfiable or HttpStatusCode.OK => ErrorCode.SourceChanged,
                    _ => ErrorCode.ProtocolMismatch,
                }, (int)response.StatusCode);
            // A 200 answering a Range request is a full body; appending it at the confirmed
            // offset would corrupt the file, so it is rejected rather than restarted blindly.
            if (!partial && response.StatusCode == HttpStatusCode.PartialContent) throw Safe(ErrorCode.ProtocolMismatch);
            if (!StringComparer.Ordinal.Equals(VersionHeader(response), version))
                throw Safe(VersionHeader(response) is null ? ErrorCode.ProtocolMismatch : ErrorCode.SourceChanged);
            if (response.Headers.Contains("Transfer-Encoding")) throw Safe(ErrorCode.ProtocolMismatch);
            var content = response.Content.Headers;
            if (partial)
            {
                string? range = SingleContentHeader(content, "Content-Range");
                if (!ContentRangeHeaderValue.TryParse(range, out var parsed) ||
                    !parsed.Unit.Equals("bytes", StringComparison.OrdinalIgnoreCase) || !parsed.HasRange || !parsed.HasLength ||
                    parsed.From != fromOffset || parsed.To != total - 1 || parsed.Length != total)
                    throw Safe(ErrorCode.ProtocolMismatch);
            }
            else if (content.Contains("Content-Range")) throw Safe(ErrorCode.ProtocolMismatch);
            long? declared = content.ContentLength;
            long expected = total - fromOffset;
            if (declared is null || declared != expected) throw Safe(ErrorCode.ProtocolMismatch);
            await using var body = await response.Content.ReadAsStreamAsync(state.Execution.Stop.Token).ConfigureAwait(false);
            byte[] buffer = new byte[_options.BufferSizeBytes];
            long received = 0;
            long lastCheckpoint = Stopwatch.GetTimestamp();
            while (true)
            {
                await CheckIntentAsync(state).ConfigureAwait(false);
                long remaining = expected - received;
                int capacity = remaining >= buffer.Length ? buffer.Length : checked((int)remaining + 1);
                int count;
                using (var idle = CancellationTokenSource.CreateLinkedTokenSource(state.Execution.Stop.Token))
                {
                    idle.CancelAfter(_options.RequestIdleTimeout);
                    try { count = await body.ReadAsync(buffer.AsMemory(0, capacity), idle.Token).ConfigureAwait(false); }
                    catch (OperationCanceledException)
                    { throw Safe(state.Execution.Stop.IsCancellationRequested ? ErrorCode.Canceled : ErrorCode.Timeout); }
                    catch (IOException) { throw Safe(ErrorCode.NetworkUnavailable); }
                }
                if (count == 0)
                {
                    if (received != expected) throw Safe(ErrorCode.ProtocolMismatch);
                    return;
                }
                if (count < 0 || count > capacity || count > remaining) throw Safe(ErrorCode.ProtocolMismatch);
                await CheckIntentAsync(state).ConfigureAwait(false);
                await destination.WriteAsync(buffer.AsMemory(0, count), state.Execution.Stop.Token).ConfigureAwait(false);
                received = checked(received + count);
                Interlocked.Exchange(ref state.Execution.ReceivedBytes, fromOffset + received);
                QueueProgress(state);
                // Bound restart loss: only a successfully flushed prefix becomes the durable
                // confirmed offset; a crash discards at most this interval of received bytes.
                if (Stopwatch.GetElapsedTime(lastCheckpoint) >= _options.CheckpointInterval)
                {
                    lastCheckpoint = Stopwatch.GetTimestamp();
                    long flushed = await destination.FlushToDiskAsync(state.Execution.Stop.Token).ConfigureAwait(false);
                    await SaveAsync(state, state.Record.Checkpoint with
                    { Phase = TransferPhase.Running, ConfirmedOffset = flushed }, release: false).ConfigureAwait(false);
                    await CheckIntentAsync(state).ConfigureAwait(false);
                }
            }
        }
    }

    private static string? VersionHeader(HttpResponseMessage response)
    {
        string? value = null;
        foreach (HttpHeaders headers in new HttpHeaders[] { response.Headers, response.Content.Headers })
        {
            if (!headers.NonValidated.TryGetValues("X-Fileway-Source-Version", out var values)) continue;
            string[] raw = values.ToArray();
            if (raw.Length != 1 || string.IsNullOrWhiteSpace(raw[0])) throw Safe(ErrorCode.ProtocolMismatch);
            if (value is not null && !StringComparer.Ordinal.Equals(value, raw[0])) throw Safe(ErrorCode.ProtocolMismatch);
            value = raw[0];
        }
        return value;
    }

    private static string SingleContentHeader(HttpHeaders headers, string name)
    {
        if (!headers.NonValidated.TryGetValues(name, out var values)) throw Safe(ErrorCode.ProtocolMismatch);
        string[] raw = values.ToArray();
        if (raw.Length != 1 || string.IsNullOrWhiteSpace(raw[0])) throw Safe(ErrorCode.ProtocolMismatch);
        return raw[0];
    }

    /// <summary>Whether an interrupted execution keeps its partial for later resume instead of deleting it.</summary>
    private static bool PreservesResumePartial(RunState state, FilewayException failure)
    {
        if (state.Destination is not { } destination || destination.IsPublished || state.PublishAttempted ||
            state.ResumeEvidenceBroken || state.Record.Checkpoint.Source?.SourceVersion is null ||
            // Without durably persisted Files evidence a kept partial could never be re-identified;
            // such an early failure must clean up through the normal deletion path instead.
            state.Record.Checkpoint.Files is not { PartialPath: not null, PartialIdentity: not null }) return false;
        // Changed content invalidates the prefix; explicit user cancellation still cleans up.
        if (failure.Error.Code == ErrorCode.SourceChanged) return false;
        if (failure.Error.Code == ErrorCode.Canceled) return state.Execution.SuspendRequested;
        return true;
    }

    private async Task<FilewayException?> SuspendOrStopAsync(Execution execution)
    {
        bool suspended = false;
        FilewayException? failure = null;
        try { suspended = await TrySuspendAsync(execution).ConfigureAwait(false); }
        catch (Exception error) { failure = Normalize(error); }
        if (!suspended)
        {
            try { _ = await SetCancelAsync(execution.Task, CancellationToken.None).ConfigureAwait(false); }
            catch (Exception error) { failure ??= Normalize(error); }
        }
        // A metadata failure must not prevent native writer/HTTP resource release.
        var stopFailure = await StopAsync(execution).ConfigureAwait(false);
        return failure ?? stopFailure;
    }

    /// <summary>
    /// Persists Pause intent for an eligible versioned execution so its confirmed offset and
    /// partial survive the drain; an ineligible execution returns false for cancel semantics.
    /// </summary>
    private async Task<bool> TrySuspendAsync(Execution execution)
    {
        for (int attempt = 0; attempt < MetadataAttempts; attempt++)
        {
            var record = await ReadRequiredAsync(execution.Task, CancellationToken.None).ConfigureAwait(false);
            RequirePlan(record);
            // An in-process user pause already holds Pause intent; it suspends the same way.
            if (IsTerminal(record) || record.Run is null || record.Checkpoint.Source?.SourceVersion is null ||
                record.Checkpoint.Files?.PartialPath is null || record.Intent == TransferIntent.Cancel ||
                record.Checkpoint.Phase is not (TransferPhase.Running or TransferPhase.Verifying)) return false;
            try
            {
                using var budget = new CancellationTokenSource(_options.PersistenceTimeout);
                var paused = await _store.SetTransferIntentAsync(execution.Task, record.Revision,
                    TransferIntent.Pause, budget.Token).WaitAsync(budget.Token).ConfigureAwait(false);
                execution.SuspendRequested = true;
                // Bounded wait for the writer's own durable flush; StopAsync preserves the rest.
                await Task.WhenAny(execution.Paused.Task, execution.Completion.Task)
                    .WaitAsync(TimeSpan.FromSeconds(10)).ConfigureAwait(false);
                return true;
            }
            catch (TimeoutException) { return true; }
            catch (FilewayException error) when (error.Error.Code == ErrorCode.Conflict) { }
            catch (OperationCanceledException) { throw Safe(ErrorCode.LocalStorageUnavailable); }
            catch (FilewayException error) { throw Safe(error.Error.Code, error.Error.HttpStatus); }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException)
            { throw Safe(ErrorCode.LocalStorageUnavailable); }
        }
        return false;
    }

    /// <summary>Flips a suspended task back to Run intent under its own task lock, before claiming.</summary>
    private async Task<TransferTaskRecord> SetIntentRunAsync(TransferTaskRecord record, CancellationToken cancellationToken)
    {
        if (record.Intent == TransferIntent.Run) return record;
        for (int attempt = 0; attempt < MetadataAttempts; attempt++)
        {
            var current = await ReadRequiredAsync(record.Plan.Reference, cancellationToken).ConfigureAwait(false);
            RequirePlan(current);
            if (current.Plan != record.Plan) throw Safe(ErrorCode.Conflict);
            if (current.Intent == TransferIntent.Run) return current;
            if (current.Intent == TransferIntent.Cancel || current.Run != record.Run) throw Safe(ErrorCode.Conflict);
            try
            {
                using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                budget.CancelAfter(_options.PersistenceTimeout);
                return await _store.SetTransferIntentAsync(record.Plan.Reference, current.Revision,
                    TransferIntent.Run, budget.Token).WaitAsync(budget.Token).ConfigureAwait(false);
            }
            catch (FilewayException error) when (error.Error.Code == ErrorCode.Conflict) { }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            { throw Safe(ErrorCode.LocalStorageUnavailable); }
            catch (FilewayException error) { throw Safe(error.Error.Code, error.Error.HttpStatus); }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException)
            { throw Safe(ErrorCode.LocalStorageUnavailable); }
        }
        throw Safe(ErrorCode.Conflict);
    }

    private static void RequireRemoteConditional(RunState state, RemoteResourceRef observed, string expectedVersion)
    {
        if (observed is null) throw Safe(ErrorCode.ProtocolMismatch);
        if (observed.SourceVersion is null || !StringComparer.Ordinal.Equals(observed.SourceVersion, expectedVersion))
            throw Safe(ErrorCode.SourceChanged);
        _ = TransferTaskValidation.Plan(state.Record.Plan with { Remote = observed });
        if (observed.Account != state.Source.Account || !WireBytes(state.Source.WirePath).AsSpan().SequenceEqual(WireBytes(observed.WirePath)))
            throw Safe(ErrorCode.ProtocolMismatch);
    }
}

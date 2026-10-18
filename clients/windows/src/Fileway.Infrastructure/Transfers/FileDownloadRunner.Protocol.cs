using System.Buffers;
using System.Globalization;
using System.Net;
using System.Text;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;

namespace Fileway.Infrastructure.Transfers;

public sealed partial class FileDownloadRunner
{
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);
    private static readonly SearchValues<char> CapabilityCharacters =
        SearchValues.Create("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_");

    private static void RequirePlan(TransferTaskRecord record)
    {
        _ = TransferTaskValidation.Plan(record.Plan);
        if (record.Plan.Direction != TransferDirection.Download || record.Plan.Conflict != FileConflictPolicy.Fail ||
            record.Plan.AutomaticResume || record.Plan.BatchId is not null) throw Safe(ErrorCode.InvalidRequest);
    }

    private static void RequireFresh(TransferTaskRecord record)
    {
        if (record.Checkpoint.Phase != TransferPhase.Queued || record.Run is not null || record.Intent != TransferIntent.Run ||
            record.Checkpoint.Source is not null || record.Checkpoint.Files is not null || record.Checkpoint.ConfirmedOffset is not null ||
            record.Checkpoint.LocalSha256 is not null || record.Checkpoint.ServerSha256 is not null ||
            record.Checkpoint.Uncertainty != TransferUncertainty.None) throw Safe(ErrorCode.InvalidRequest);
    }

    private static void RequireRemote(RunState state, RemoteResourceRef observed)
    {
        if (observed is null) throw Safe(ErrorCode.ProtocolMismatch);
        _ = TransferTaskValidation.Plan(state.Record.Plan with { Remote = observed });
        if (observed.Account != state.Source.Account || !WireBytes(state.Source.WirePath).AsSpan().SequenceEqual(WireBytes(observed.WirePath)))
            throw Safe(ErrorCode.ProtocolMismatch);
        if (!StringComparer.Ordinal.Equals(observed.SourceVersion, state.Source.SourceVersion))
            throw Safe(ErrorCode.SourceChanged);
    }

    // Keep opaque bytes: Uri.UnescapeDataString could collapse distinct invalid UTF-8 resource names.
    private static byte[] WireBytes(string wire)
    {
        byte[] encoded = StrictUtf8.GetBytes(wire);
        using var decoded = new MemoryStream(encoded.Length);
        for (int index = 0; index < encoded.Length; index++)
        {
            byte value = encoded[index];
            if (value == '%')
            {
                if (index + 2 >= encoded.Length || !char.IsAsciiHexDigit((char)encoded[index + 1]) ||
                    !char.IsAsciiHexDigit((char)encoded[index + 2])) throw Safe(ErrorCode.ProtocolMismatch);
                value = checked((byte)int.Parse(Encoding.ASCII.GetString(encoded, index + 1, 2), NumberStyles.HexNumber,
                    CultureInfo.InvariantCulture));
                index += 2;
            }
            decoded.WriteByte(value);
        }
        return decoded.ToArray();
    }

    private static string RequireHash(string value)
    {
        if (value is null || value.Length != 64 || value.Any(character => !char.IsAsciiHexDigit(character)))
            throw Safe(ErrorCode.ProtocolMismatch);
        return value.ToLowerInvariant();
    }

    private static void RequireFreshDestination(RunState state, IDownloadDestination destination)
    {
        if (destination.IsPublished || destination.Length != 0) throw Safe(ErrorCode.ProtocolMismatch);
        var files = destination.Files;
        if (files.PartialPath is null || string.IsNullOrWhiteSpace(files.PartialIdentity) || files.PublishedFileIdentity is not null ||
            files.OriginalTargetIdentity is not null || files.BackupPath is not null || files.BackupIdentity is not null)
            throw Safe(ErrorCode.ProtocolMismatch);
        string final = TransferTaskValidation.LocalFilePath(state.Record.Plan.LocalPath);
        string partial = TransferTaskValidation.LocalFilePath(files.PartialPath);
        if (StringComparer.OrdinalIgnoreCase.Equals(final, partial) || !StringComparer.OrdinalIgnoreCase.Equals(
            final[..final.LastIndexOf('\\')], partial[..partial.LastIndexOf('\\')])) throw Safe(ErrorCode.ProtocolMismatch);
    }

    private static void RequireOwnedEvidence(RunState state, IDownloadDestination destination)
    {
        if (state.Record.Checkpoint.Files is not { } original || destination.Files.PartialPath != original.PartialPath ||
            destination.Files.PartialIdentity != original.PartialIdentity || string.IsNullOrWhiteSpace(original.PartialIdentity) ||
            destination.Files.OriginalTargetIdentity != original.OriginalTargetIdentity ||
            destination.Files.BackupPath != original.BackupPath || destination.Files.BackupIdentity != original.BackupIdentity ||
            original.PublishedFileIdentity is not null && destination.Files.PublishedFileIdentity != original.PublishedFileIdentity)
            throw Safe(ErrorCode.OutcomeUnknown);
    }

    private static void RequirePublication(RunState state, IDownloadDestination destination, string identity)
    {
        RequireOwnedEvidence(state, destination);
        if (!destination.IsPublished || string.IsNullOrWhiteSpace(identity) || identity != destination.Files.PublishedFileIdentity ||
            identity != destination.Files.PartialIdentity || destination.Length != state.Record.Checkpoint.Source?.Length)
            throw Safe(ErrorCode.OutcomeUnknown);
    }

    private static void RequireAddress(Uri address)
    {
        const string prefix = "/stream/";
        if (address is null || !address.IsAbsoluteUri || address.Scheme != Uri.UriSchemeHttp || address.Host != "127.0.0.1" ||
            address.IsDefaultPort || address.Port <= 0 || address.UserInfo.Length != 0 || address.Query.Length != 0 || address.Fragment.Length != 0 ||
            !address.AbsolutePath.StartsWith(prefix, StringComparison.Ordinal) || address.AbsolutePath.Length == prefix.Length ||
            address.AbsolutePath.AsSpan(prefix.Length).ContainsAnyExcept(CapabilityCharacters))
            throw Safe(ErrorCode.ProtocolMismatch);
    }

    private async Task ReceiveAsync(RunState state, IDownloadDestination destination, IDownloadLease lease)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, lease.Address);
        using var headersBudget = CancellationTokenSource.CreateLinkedTokenSource(state.Execution.Stop.Token);
        headersBudget.CancelAfter(_options.RequestIdleTimeout);
        HttpResponseMessage response;
        try { response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, headersBudget.Token).ConfigureAwait(false); }
        catch (OperationCanceledException)
        { throw Safe(state.Execution.Stop.IsCancellationRequested ? ErrorCode.Canceled : ErrorCode.Timeout); }
        using (response)
        {
            if (response.StatusCode != HttpStatusCode.OK)
                throw Safe(response.StatusCode switch
                {
                    HttpStatusCode.Unauthorized => ErrorCode.AuthenticationExpired,
                    HttpStatusCode.Forbidden => ErrorCode.PermissionDenied,
                    HttpStatusCode.NotFound => ErrorCode.SourceChanged,
                    _ => ErrorCode.ProtocolMismatch,
                }, (int)response.StatusCode);
            if (response.Content.Headers.Contains("Content-Range") ||
                response.Content.Headers.Contains("Content-Length") && response.Content.Headers.ContentLength is null ||
                response.Content.Headers.ContentEncoding.Any(value => !value.Equals("identity", StringComparison.OrdinalIgnoreCase)) ||
                response.Content.Headers.ContentLength is { } declared && declared != lease.Length)
                throw Safe(ErrorCode.ProtocolMismatch);
            // This is an unsupported extension, not a server-version contract or permission to pin/resume.
            if (response.Headers.Contains("X-Fileway-Source-Version") || response.Content.Headers.Contains("X-Fileway-Source-Version"))
                throw Safe(ErrorCode.CapabilityMissing);
            await using var body = await response.Content.ReadAsStreamAsync(state.Execution.Stop.Token).ConfigureAwait(false);
            byte[] buffer = new byte[_options.BufferSizeBytes];
            long received = 0;
            while (true)
            {
                await CheckIntentAsync(state).ConfigureAwait(false);
                long remaining = lease.Length - received;
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
                    if (received != lease.Length) throw Safe(ErrorCode.ProtocolMismatch);
                    return;
                }
                if (count < 0 || count > capacity || count > remaining) throw Safe(ErrorCode.ProtocolMismatch);
                await CheckIntentAsync(state).ConfigureAwait(false);
                await destination.WriteAsync(buffer.AsMemory(0, count), state.Execution.Stop.Token).ConfigureAwait(false);
                received = checked(received + count);
                Interlocked.Exchange(ref state.Execution.ReceivedBytes, received);
                QueueProgress(state);
            }
        }
    }
}

using System.Text.Json;
using System.Threading.Channels;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Ipc;

/// <summary>A single supervised Host lifetime. Failure invalidates all its sessions and leases.</summary>
public sealed class GoHostClient : IHostControlClient
{
    private const int BusinessCapacity = 72;
    private const int PublicControlCapacity = 18;
    private const int ControlCapacity = BusinessCapacity + PublicControlCapacity + 2;
    private static readonly TimeSpan DefaultCallTimeout = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan CancellationGrace = TimeSpan.FromSeconds(3);
    private readonly object _gate = new();
    private readonly NativeHostProcess _process;
    private readonly TimeSpan _shutdownTimeout;
    private readonly CancellationTokenSource _lifetime = new();
    private readonly Dictionary<string, Pending> _pending = new(StringComparer.Ordinal);
    private readonly Channel<Pending> _business = Channel.CreateBounded<Pending>(BusinessCapacity);
    private readonly Channel<Pending> _control = Channel.CreateBounded<Pending>(ControlCapacity);
    private readonly SemaphoreSlim _writeWake = new(0, 1);
    private readonly TaskCompletionSource<HostExit> _completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly Task _reader;
    private int _businessCount, _controlCount, _publicControlCount;
    private bool _terminal, _shutdownRequested;
    private Task? _shutdown;

    private enum Phase { Queued, Writing, Sent, Completed }

    private sealed class Pending
    {
        internal required string Id { get; init; }
        internal required HostCall Call { get; init; }
        internal required byte[] Frame { get; init; }
        internal required bool Control { get; init; }
        internal required bool Internal { get; init; }
        internal required bool Mutation { get; init; }
        internal Phase Phase;
        internal bool CancelRequested;
        internal bool TimedOut;
        internal readonly TaskCompletionSource<HostReply> Reply = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal CancellationTokenRegistration CallerCancellation;
        internal Timer? Timeout, CancelTimeout;

        internal void Release()
        {
            CallerCancellation.Unregister();
            Timeout?.Dispose();
            CancelTimeout?.Dispose();
        }
    }

    private GoHostClient(NativeHostProcess process, TimeSpan shutdownTimeout)
    {
        _process = process;
        _shutdownTimeout = shutdownTimeout;
        _reader = Task.Run(ReadLoopAsync);
        _ = Task.Run(WriteLoopAsync);
        _ = Task.Run(DrainDiagnosticsAsync);
        _ = Task.Run(async () =>
        {
            await _process.Exit.ConfigureAwait(false);
            await _reader.ConfigureAwait(false); // Drain the last full response before observing exit.
            Fail(new ErrorInfo(ErrorCode.HostExited, "The Host process exited."));
        });
    }

    public int ProcessId => _process.ProcessId;
    public HostHandshake Handshake { get; private set; } = null!;
    public Task<HostExit> Completion => _completion.Task;

    public static async Task<GoHostClient> StartAsync(HostClientOptions options, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(options);
        TimeSpan startup = ValidTimeout(options.StartupTimeout ?? TimeSpan.FromSeconds(10));
        TimeSpan shutdown = ValidTimeout(options.ShutdownTimeout ?? TimeSpan.FromSeconds(5));
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(startup);
        GoHostClient? client = null;
        NativeHostProcess? process = null;
        try
        {
            using var build = await HostBuild.VerifyAsync(options, deadline.Token).ConfigureAwait(false);
            process = await Task.Run(() => NativeHostProcess.Start(Path.GetFullPath(options.ExecutablePath)), deadline.Token).ConfigureAwait(false);
            client = new GoHostClient(process, shutdown);
            process = null;
            HostReply reply = await client.Queue(new HostCall("init", Safety: HostCallSafety.Control, Timeout: startup),
                true, CancellationToken.None).WaitAsync(deadline.Token).ConfigureAwait(false);
            if (!reply.IsSuccess || reply.Result is not { } result) throw HostWire.InvalidResponse();
            client.Handshake = build.Handshake(result);
            deadline.Token.ThrowIfCancellationRequested();
            return client;
        }
        catch (OperationCanceledException)
        {
            client?.Fail(new ErrorInfo(ErrorCode.Timeout, "Host startup did not complete."));
            process?.Dispose();
            if (client is not null) await client.Completion.ConfigureAwait(false);
            throw HostWire.SafeError(cancellationToken.IsCancellationRequested ? ErrorCode.Canceled : ErrorCode.Timeout, "Host startup did not complete.");
        }
        catch (FilewayException)
        {
            client?.Fail(new ErrorInfo(ErrorCode.ProtocolMismatch, "Host startup verification failed."));
            process?.Dispose();
            if (client is not null) await client.Completion.ConfigureAwait(false);
            throw;
        }
        catch (Exception)
        {
            client?.Fail(new ErrorInfo(ErrorCode.ProtocolMismatch, "Host startup verification failed."));
            process?.Dispose();
            if (client is not null) await client.Completion.ConfigureAwait(false);
            throw HostWire.SafeError(ErrorCode.ProtocolMismatch, "Host startup verification failed.");
        }
    }

    public Task<HostReply> CallAsync(HostCall request, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        return Queue(request, false, cancellationToken);
    }

    private Task<HostReply> Queue(HostCall call, bool internalCall, CancellationToken cancellationToken)
    {
        TimeSpan timeout = ValidTimeout(call.Timeout ?? DefaultCallTimeout);
        if (cancellationToken.IsCancellationRequested)
            return Task.FromException<HostReply>(HostWire.SafeError(ErrorCode.Canceled, "The request was canceled before dispatch."));
        string id = Guid.NewGuid().ToString("N");
        bool control = call.Operation is "init" or "health" or "cancel" or "close_session" or "shutdown";
        Pending pending;
        try
        {
            // Own parameters and encode before returning; caller documents may be disposed immediately.
            var owned = call with { Parameters = call.Parameters?.Clone() };
            pending = new Pending { Id = id, Call = owned, Frame = HostWire.Encode(id, owned), Control = control, Internal = internalCall, Mutation = IsMutation(owned) };
        }
        catch (FilewayException) { throw; }
        catch (Exception) { throw HostWire.SafeError(ErrorCode.InvalidRequest, "Invalid Host request parameters."); }
        lock (_gate)
        {
            if (_terminal || (_shutdownRequested && !internalCall))
                return Task.FromException<HostReply>(HostWire.SafeError(ErrorCode.HostExited, "The Host lifetime has ended."));
            if ((!control && _businessCount >= BusinessCapacity) ||
                (control && (_controlCount >= ControlCapacity || (!internalCall && _publicControlCount >= PublicControlCapacity))))
                return Task.FromException<HostReply>(new FilewayException(new ErrorInfo(ErrorCode.Busy, "Host client request capacity is full.", Retryable: true)));
            _pending.Add(id, pending);
            if (control) { _controlCount++; if (!internalCall) _publicControlCount++; }
            else _businessCount++;
            pending.Timeout = new Timer(_ => Timeout(pending), null, timeout, System.Threading.Timeout.InfiniteTimeSpan);
            pending.CallerCancellation = cancellationToken.Register(() => Cancel(pending));
            // Register invokes synchronously when cancellation won the entrance
            // race. Its callback may already have removed this exact owner.
            if (pending.Phase == Phase.Completed || !_pending.TryGetValue(id, out var registered) || !ReferenceEquals(registered, pending))
            {
                pending.Release();
                return pending.Reply.Task;
            }
            if (!(control ? _control : _business).Writer.TryWrite(pending))
            {
                RemoveLocked(pending);
                pending.Release();
                return Task.FromException<HostReply>(new FilewayException(new ErrorInfo(ErrorCode.Busy, "Host client write capacity is full.", Retryable: true)));
            }
            try { _writeWake.Release(); } catch (SemaphoreFullException) { }
        }
        return pending.Reply.Task;
    }

    private static bool IsMutation(HostCall call)
    {
        if (call.Safety == HostCallSafety.Mutation) return true;
        if (call.Operation is "login" or "command_start") return true;
        if (call.Operation != "request") return false;
        return call.Parameters is not { } parameters || !parameters.TryGetProperty("method", out var method) ||
            method.ValueKind != JsonValueKind.String || method.GetString()?.ToUpperInvariant() is not ("GET" or "HEAD" or "OPTIONS");
    }

    private async Task WriteLoopAsync()
    {
        try
        {
            while (!_lifetime.IsCancellationRequested)
            {
                Pending? pending;
                if (!_control.Reader.TryRead(out pending) && !_business.Reader.TryRead(out pending))
                {
                    await _writeWake.WaitAsync(_lifetime.Token).ConfigureAwait(false);
                    continue;
                }
                lock (_gate)
                {
                    if (_terminal || pending.Phase == Phase.Completed) continue;
                    pending.Phase = Phase.Writing;
                }
                // Only lifetime cancellation can interrupt a frame. Caller cancellation
                // is a separate control frame, never a token on this byte write.
                await _process.Input.WriteAsync(pending.Frame, _lifetime.Token).ConfigureAwait(false);
                lock (_gate)
                    if (pending.Phase == Phase.Writing) pending.Phase = Phase.Sent;
            }
        }
        catch (Exception) { Fail(new ErrorInfo(ErrorCode.HostExited, "Host input transport failed.")); }
    }

    private async Task ReadLoopAsync()
    {
        try
        {
            while (!_lifetime.IsCancellationRequested)
            {
                using var document = await HostWire.ReadAsync(_process.Output, _lifetime.Token).ConfigureAwait(false);
                HostReply reply = HostWire.Decode(document.RootElement);
                Pending pending;
                lock (_gate)
                {
                    if (!_pending.TryGetValue(reply.RequestId, out pending!) || pending.Call.Generation != reply.Generation ||
                        pending.Phase is Phase.Queued or Phase.Completed) throw HostWire.InvalidResponse();
                    RemoveLocked(pending);
                }
                if (pending.Mutation && !reply.IsSuccess && reply.Error?.Code is
                    "Canceled" or "ResponseTooLarge" or "HostFailure" or "CoreRejected" or "DuplicateRequest")
                    pending.Reply.TrySetException(HostWire.SafeError(ErrorCode.OutcomeUnknown, "The dispatched write outcome is unknown; reconcile before retrying."));
                else pending.Reply.TrySetResult(reply);
                pending.Release();
            }
        }
        catch (EndOfStreamException) { Fail(new ErrorInfo(ErrorCode.HostExited, "Host output ended.")); }
        catch (Exception) { Fail(new ErrorInfo(ErrorCode.ProtocolMismatch, "Host output transport failed validation.")); }
    }

    private async Task DrainDiagnosticsAsync()
    {
        byte[] discard = new byte[4096];
        try
        {
            // Never decode, retain, log, or export inherited stderr contents.
            while (await _process.Error.ReadAsync(discard, _lifetime.Token).ConfigureAwait(false) != 0) { }
        }
        catch (Exception) { /* Lifetime closure also interrupts stderr draining. */ }
        finally { Array.Clear(discard); }
    }

    private void Cancel(Pending pending)
    {
        bool queued;
        lock (_gate)
        {
            if (_terminal || pending.Phase == Phase.Completed || pending.CancelRequested || pending.TimedOut) return;
            pending.CancelRequested = true;
            queued = pending.Phase == Phase.Queued;
            if (queued) RemoveLocked(pending);
            else pending.CancelTimeout = new Timer(_ => Timeout(pending), null, CancellationGrace, System.Threading.Timeout.InfiniteTimeSpan);
        }
        if (queued)
        {
            pending.Reply.TrySetException(HostWire.SafeError(ErrorCode.Canceled, "The request was canceled before dispatch."));
            pending.Release();
            return;
        }
        try
        {
            var parameters = JsonSerializer.SerializeToElement(new { targetRequestId = pending.Id });
            _ = ObserveCancelAsync(Queue(new HostCall("cancel", parameters, Safety: HostCallSafety.Control, Timeout: CancellationGrace), true, CancellationToken.None));
        }
        catch (FilewayException) { /* Its original deadline still bounds the accepted call. */ }
    }

    private static async Task ObserveCancelAsync(Task<HostReply> cancellation)
    {
        try { _ = await cancellation.ConfigureAwait(false); }
        catch (Exception) { /* A cancellation acknowledgement never asserts rollback. */ }
    }

    private void Timeout(Pending pending)
    {
        bool queued;
        lock (_gate)
        {
            if (_terminal || pending.Phase == Phase.Completed || pending.TimedOut) return;
            queued = pending.Phase == Phase.Queued;
            if (queued) RemoveLocked(pending);
            else pending.TimedOut = true; // Retain bounded correlation until reply or disconnect.
        }
        pending.Reply.TrySetException(HostWire.SafeError(!queued && pending.Mutation ? ErrorCode.OutcomeUnknown : ErrorCode.Timeout,
            !queued && pending.Mutation ? "The dispatched write outcome is unknown; reconcile before retrying." : "Host response deadline expired."));
        pending.Release();
    }

    private void RemoveLocked(Pending pending)
    {
        if (!_pending.TryGetValue(pending.Id, out var owner) || !ReferenceEquals(owner, pending)) return;
        pending.Phase = Phase.Completed;
        _pending.Remove(pending.Id);
        if (pending.Control) { _controlCount--; if (!pending.Internal) _publicControlCount--; }
        else _businessCount--;
    }

    private void Fail(ErrorInfo error)
    {
        Pending[] pending;
        bool expected;
        lock (_gate)
        {
            if (_terminal) return;
            _terminal = true;
            expected = _shutdownRequested;
            pending = _pending.Values.ToArray();
            _pending.Clear();
            _business.Writer.TryComplete();
            _control.Writer.TryComplete();
        }
        foreach (var call in pending)
        {
            ErrorInfo failure = call.Mutation && call.Phase is Phase.Writing or Phase.Sent
                ? new ErrorInfo(ErrorCode.OutcomeUnknown, "The dispatched write outcome is unknown; reconcile before retrying.") : error;
            call.Reply.TrySetException(new FilewayException(failure));
            call.Release();
        }
        _lifetime.Cancel();
        _process.Dispose();
        _ = CompleteExitAsync(expected, error);
    }

    private async Task CompleteExitAsync(bool expected, ErrorInfo error)
    {
        int? code = null;
        try { code = await _process.Exit.WaitAsync(TimeSpan.FromSeconds(1)).ConfigureAwait(false); }
        catch (TimeoutException) { }
        _completion.TrySetResult(new HostExit(expected, code, expected ? null : error));
    }

    public async Task ShutdownAsync(CancellationToken cancellationToken)
    {
        Task shutdown;
        lock (_gate)
        {
            if (_shutdown is null)
            {
                _shutdownRequested = true;
                var deadline = new CancellationTokenSource(_shutdownTimeout);
                _shutdown = Task.Run(() => ShutdownCoreAsync(deadline), CancellationToken.None);
            }
            shutdown = _shutdown;
        }
        try { await shutdown.WaitAsync(cancellationToken).ConfigureAwait(false); }
        catch (OperationCanceledException)
        {
            Fail(new ErrorInfo(ErrorCode.Canceled, "Host shutdown was interrupted."));
            throw;
        }
    }

    private async Task ShutdownCoreAsync(CancellationTokenSource deadline)
    {
        using (deadline)
        {
            try
            {
                if (!_completion.Task.IsCompleted)
                {
                    await Queue(new HostCall("shutdown", Safety: HostCallSafety.Control, Timeout: _shutdownTimeout), true, CancellationToken.None)
                        .WaitAsync(deadline.Token).ConfigureAwait(false);
                    await _completion.Task.WaitAsync(deadline.Token).ConfigureAwait(false);
                }
            }
            catch (Exception) { Fail(new ErrorInfo(ErrorCode.HostExited, "Host shutdown reached its deadline.")); }
            finally { Fail(new ErrorInfo(ErrorCode.HostExited, "Host lifetime ended.")); }
        }
    }

    public ValueTask DisposeAsync() => new(ShutdownAsync(CancellationToken.None));

    private static TimeSpan ValidTimeout(TimeSpan timeout)
    {
        if (timeout <= TimeSpan.Zero || timeout > TimeSpan.FromMinutes(30))
            throw HostWire.SafeError(ErrorCode.InvalidRequest, "Host timeout is outside the supported finite range.");
        return timeout;
    }
}

using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Credentials;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Services;
using Fileway.Infrastructure.Transfers;

namespace Fileway.Infrastructure.Tests.Services;

public sealed partial class DownloadApplicationServiceTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task RealUnknownDownloadLeaseRetiresBothAuthoritiesAndWaitsOwnedRunnerReleaseWithoutSelfDeadlock(bool failClose)
    {
        var store = new InMemoryProfileStore();
        const string wire = "/original%252F.bin";
        await using var host = new CoreReadHost
        {
            Dispatch = (call, _) => call.Operation switch
            {
                "lease" => Task.FromException<object>(new FilewayException(new(ErrorCode.Timeout, "private lease timeout"))),
                "request" => Task.FromResult(CoreReadHost.Http(CoreReadHost.Metadata(wire, size: 17))),
                _ => Task.FromException<object>(new InvalidOperationException("Unexpected download retirement command.")),
            },
            ErrorForCall = call => failClose && call.Operation == "close_session" ? new("timeout", "private close timeout", false) : null,
        };
        var inner = new FilewayClientServices(new RetirementHost(host, failClose), store, new InMemoryCredentialVault());
        var destinations = new RetirementDestinations();
        var locks = new RetirementLocks();
        var http = new NoDownloadHttp();
        var runner = new FileDownloadRunner(store, inner, destinations, locks, http);
        var uploads = new FakeUpload { HoldStop = true };
        var bridge = new UploadApplicationService(inner, inner, inner, store, uploads,
            downloads: new(runner, inner, new FakeSettings()));
        IDownloadService downloads = bridge;
        try
        {
            var session = await bridge.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
            var context = Assert.IsType<DownloadContext>(downloads.CurrentContext);
            var preview = await downloads.PrepareAsync(context, new(session.Account, wire, "display.bin", "/display.bin"), Token);
            var issue = new TaskCompletionSource<ConnectionIssue>(TaskCreationOptions.RunContinuationsAsynchronously);
            bridge.ConnectionIssue += (_, notification) => issue.TrySetResult(notification);
            try { await downloads.SubmitAsync(preview, Token); }
            catch (FilewayException error) { Assert.Equal(ErrorCode.SourceChanged, error.Error.Code); }
            var retired = await issue.Task.WaitAsync(Timeout, Token);
            await Task.WhenAll(destinations.DisposalEntered.Task, uploads.StopEntered.Task).WaitAsync(Timeout, Token);
            Assert.Equal(context.Account, retired.Account);
            Assert.Equal(context.SessionId, retired.SessionId);
            Assert.True(retired.SessionInvalidated);
            Assert.Equal(failClose ? ErrorCode.HostExited : ErrorCode.Timeout, retired.Error.Code);
            Assert.DoesNotContain("private", retired.Error.Message, StringComparison.Ordinal);
            Assert.Null(bridge.CurrentContext);
            Assert.Null(downloads.CurrentContext);
            Assert.True(locks.Held);
            Assert.False(destinations.Disposed);
            Assert.Equal(1, destinations.DeleteCalls);
            Assert.Equal(0, http.Requests);
            Assert.Single(host.Calls, call => call.Operation == "lease");
            Assert.Single(host.Calls, call => call.Operation == "close_session");
            var disposal = bridge.DisposeAsync().AsTask();
            Assert.False(disposal.IsCompleted);
            uploads.StopRelease.TrySetResult();
            Assert.False(disposal.IsCompleted);
            destinations.DisposalRelease.TrySetResult();
            await disposal.WaitAsync(Timeout, Token);
            Assert.True(destinations.Disposed);
            Assert.False(locks.Held);
            Assert.DoesNotContain(host.Calls, call => call.Operation == "revoke");
            Assert.Equal(0, http.Requests);
        }
        finally
        {
            uploads.StopRelease.TrySetResult();
            destinations.DisposalRelease.TrySetResult();
            await bridge.DisposeAsync().AsTask().WaitAsync(Timeout, Token);
            try { await inner.DisposeAsync(); }
            catch (FilewayException error) when (failClose) { Assert.Equal(ErrorCode.HostExited, error.Error.Code); }
        }
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task CapturedDownloadLeaseUnknownRevokeAlwaysInvalidatesOriginalApplicationOwner(bool failClose)
    {
        const string wire = "/captured.bin";
        await using var host = new CoreReadHost
        {
            Dispatch = (call, _) => call.Operation switch
            {
                "lease" => Task.FromResult<object>("http://127.0.0.1:18765/stream/fixture-original"),
                "request" => Task.FromResult(CoreReadHost.Http(CoreReadHost.Metadata(wire))),
                "revoke" => Task.FromException<object>(new FilewayException(new(ErrorCode.Timeout, "private revoke timeout"))),
                _ => Task.FromException<object>(new InvalidOperationException("Unexpected captured lease command.")),
            },
            ErrorForCall = call => failClose && call.Operation == "close_session" ? new("timeout", "private close timeout", false) : null,
        };
        var store = new InMemoryProfileStore();
        var inner = new FilewayClientServices(new RetirementHost(host, failClose), store, new InMemoryCredentialVault());
        var uploads = new FakeUpload();
        var runner = new FakeDownload(store);
        var bridge = new UploadApplicationService(inner, inner, inner, store, uploads,
            downloads: new(runner, inner, new FakeSettings()));
        IDownloadService downloads = bridge;
        try
        {
            var session = await bridge.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
            var issue = new TaskCompletionSource<ConnectionIssue>(TaskCreationOptions.RunContinuationsAsynchronously);
            bridge.ConnectionIssue += (_, notification) => issue.TrySetResult(notification);
            var lease = await inner.AcquireDownloadAsync(new(new(session.Account, wire, "file.bin", "/file.bin"), 17), Token);
            var releasing = lease.DisposeAsync().AsTask();
            if (failClose) await FailureAsync(ErrorCode.HostExited, () => releasing);
            else await releasing.WaitAsync(Timeout, Token);
            var retired = await issue.Task.WaitAsync(Timeout, Token);
            Assert.Equal(session.Account, retired.Account);
            Assert.Equal(session.Id, retired.SessionId);
            Assert.True(retired.SessionInvalidated);
            Assert.Null(bridge.CurrentContext);
            Assert.Null(downloads.CurrentContext);
            await Task.WhenAll(uploads.StopEntered.Task, runner.StopEntered.Task).WaitAsync(Timeout, Token);
            Assert.Single(host.Calls, call => call.Operation == "revoke");
            Assert.Single(host.Calls, call => call.Operation == "close_session");
            var repeated = lease.DisposeAsync().AsTask();
            if (failClose) await FailureAsync(ErrorCode.HostExited, () => repeated);
            else await repeated.WaitAsync(Timeout, Token);
            Assert.Single(host.Calls, call => call.Operation == "revoke");
        }
        finally
        {
            await bridge.DisposeAsync().AsTask().WaitAsync(Timeout, Token);
            try { await inner.DisposeAsync(); }
            catch (FilewayException error) when (failClose) { Assert.Equal(ErrorCode.HostExited, error.Error.Code); }
        }
    }

    private sealed class RetirementHost(CoreReadHost inner, bool failShutdown) : IHostControlClient
    {
        public HostHandshake Handshake => inner.Handshake;
        public Task<HostExit> Completion => inner.Completion;
        public Task<HostReply> CallAsync(HostCall request, CancellationToken cancellationToken) => inner.CallAsync(request, cancellationToken);
        public Task ShutdownAsync(CancellationToken cancellationToken) => failShutdown
            ? Task.FromException(new FilewayException(new(ErrorCode.HostExited, "private shutdown failure")))
            : inner.ShutdownAsync(cancellationToken);
        public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    }

    private sealed class NoDownloadHttp : HttpMessageHandler
    {
        internal int Requests;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        { Interlocked.Increment(ref Requests); return Task.FromException<HttpResponseMessage>(new InvalidOperationException("No GET after unknown acquisition.")); }
    }

    private sealed class RetirementLocks : ITransferExecutionLockProvider
    {
        internal bool Held { get; private set; }
        public Task<ITransferExecutionLock> AcquireAsync(TransferTaskRef task, CancellationToken cancellationToken)
        { cancellationToken.ThrowIfCancellationRequested(); Held = true; return Task.FromResult<ITransferExecutionLock>(new Ownership(this, task)); }
        private sealed class Ownership(RetirementLocks owner, TransferTaskRef task) : ITransferExecutionLock
        {
            public TransferTaskRef Task { get; } = task;
            public ValueTask DisposeAsync() { owner.Held = false; return ValueTask.CompletedTask; }
        }
    }

    private sealed class RetirementDestinations : IDownloadDestinationProvider, IDownloadDestination
    {
        internal TaskCompletionSource DisposalEntered { get; } = Signal();
        internal TaskCompletionSource DisposalRelease { get; } = Signal();
        internal bool Disposed { get; private set; }
        internal int DeleteCalls { get; private set; }
        public TransferWorkFiles Files { get; private set; } = new();
        public bool IsPublished => false;
        public long Length => 0;
        public Task<IDownloadDestination> CreateFreshAsync(string finalPath, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Files = new(finalPath[..(finalPath.LastIndexOf('\\') + 1)] + "owned-fixture.part", "private-fixture-identity");
            return Task.FromResult<IDownloadDestination>(this);
        }
        public Task<IDownloadDestination> OpenOwnedAsync(string finalPath, TransferWorkFiles files, CancellationToken cancellationToken) =>
            throw new NotSupportedException();
        public Task WriteAsync(ReadOnlyMemory<byte> bytes, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<long> FlushToDiskAsync(CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DownloadLocalFileHash> CalculateSha256Async(CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<string> PublishNoReplaceAsync(CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task DeleteOwnedPartialAsync(CancellationToken cancellationToken) { DeleteCalls++; return Task.CompletedTask; }
        public async ValueTask DisposeAsync()
        { DisposalEntered.TrySetResult(); await DisposalRelease.Task; Disposed = true; }
    }
}

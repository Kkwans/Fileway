using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Services;

namespace Fileway.Infrastructure.Tests.Services;

public sealed partial class DownloadSourceServiceTests
{
    [Fact]
    public async Task ExplicitDisposalAndOriginalSessionRetirementRevokeExactlyOnce()
    {
        await using var host = DownloadHost();
        await using var services = new FilewayClientServices(host);
        var profile = CoreReadHost.Profile();
        var session = await Connect(services, profile);
        var original = Request(session);
        var disposed = await Provider(services).AcquireDownloadAsync(original, Token);
        await disposed.DisposeAsync();
        await disposed.DisposeAsync();
        var revoke = Assert.Single(host.Calls, call => call.Operation == "revoke");
        Assert.Equal(Capability, revoke.Parameters!.Value.GetProperty("url").GetString());
        Assert.Null(revoke.Session);
        Assert.Equal(HostCallSafety.Control, revoke.Safety);

        var retired = await Provider(services).AcquireDownloadAsync(original, Token);
        var replacement = await Connect(services, profile);
        Assert.Equal(2, host.Calls.Count(call => call.Operation == "revoke"));
        await retired.DisposeAsync();
        Assert.Equal(2, host.Calls.Count(call => call.Operation == "revoke"));
        var stale = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).AcquireDownloadAsync(original, Token));
        Assert.Equal(ErrorCode.SourceChanged, stale.Error.Code);
        await using var fresh = await Provider(services).AcquireDownloadAsync(Request(replacement), Token);
        Assert.Equal(3, LeaseCalls(host).Count());
    }

    [Theory]
    [InlineData("permission", ErrorCode.PermissionDenied)]
    [InlineData("refresh", ErrorCode.NetworkUnavailable)]
    [InlineData("cancel", ErrorCode.Canceled)]
    public async Task CapturedCapabilityIsRevokedWhenFinalValidationFails(string scenario, ErrorCode expected)
    {
        await using var host = DownloadHost();
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) =>
        {
            if (call.Operation == "lease")
            {
                host.CurrentToken = CoreReadHost.Jwt("changed");
                if (scenario == "permission") host.Download = false;
                if (scenario == "refresh") host.UserReadStatus = 503;
                if (scenario == "cancel") cancellation.Cancel();
            }
            return initial(call, token);
        };

        var error = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).AcquireDownloadAsync(Request(session), cancellation.Token));

        Assert.Equal(expected, error.Error.Code);
        Assert.Single(LeaseCalls(host));
        var revoke = Assert.Single(host.Calls, call => call.Operation == "revoke");
        Assert.Equal(Capability, revoke.Parameters!.Value.GetProperty("url").GetString());
        if (scenario == "cancel") Assert.DoesNotContain(host.Calls, call => call.Operation == "close_session");
    }

    [Fact]
    public async Task LateCapabilityAfterReconnectIsRevokedInsteadOfBindingToReplacement()
    {
        await using var host = DownloadHost();
        await using var services = new FilewayClientServices(host);
        var profile = CoreReadHost.Profile();
        var first = await Connect(services, profile);
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource<object>(TaskCreationOptions.RunContinuationsAsynchronously);
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) =>
        {
            if (call.Operation != "lease") return initial(call, token);
            entered.TrySetResult();
            return release.Task;
        };
        var pending = Provider(services).AcquireDownloadAsync(Request(first), Token);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var replacement = await Connect(services, profile);
        release.TrySetResult(Capability);

        var error = await Assert.ThrowsAsync<FilewayException>(() => pending.WaitAsync(TimeSpan.FromSeconds(5), Token));

        Assert.Equal(ErrorCode.SourceChanged, error.Error.Code);
        var lease = Assert.Single(LeaseCalls(host));
        Assert.Equal(lease.Session, Assert.Single(host.Calls, call => call.Operation == "close_session").Session);
        Assert.Single(host.Calls, call => call.Operation == "revoke");
        host.Dispatch = initial;
        await using var fresh = await Provider(services).AcquireDownloadAsync(Request(replacement), Token);
        Assert.NotEqual(lease.Session, LeaseCalls(host).Last().Session);
    }

    [Theory]
    [InlineData(ErrorCode.Timeout)]
    [InlineData(ErrorCode.NetworkUnavailable)]
    [InlineData(ErrorCode.Canceled)]
    [InlineData(ErrorCode.OutcomeUnknown)]
    [InlineData(ErrorCode.ResponseTooLarge)]
    public async Task UnobservedControlResponseClosesOnlyOriginalSessionWithoutReplay(ErrorCode expected)
    {
        await using var host = DownloadHost();
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) => call.Operation == "lease"
            ? Task.FromException<object>(new FilewayException(new ErrorInfo(expected, "fixture.private.failure")))
            : initial(call, token);
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var request = Request(session);

        var error = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).AcquireDownloadAsync(request, Token));

        Assert.Equal(expected, error.Error.Code);
        Assert.DoesNotContain("fixture.private", error.Message);
        var lease = Assert.Single(LeaseCalls(host));
        Assert.Equal(lease.Session, Assert.Single(host.Calls, call => call.Operation == "close_session").Session);
        Assert.DoesNotContain(host.Calls, call => call.Operation == "revoke");
        var stale = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).AcquireDownloadAsync(request, Token));
        Assert.Equal(ErrorCode.SourceChanged, stale.Error.Code);
        Assert.Single(LeaseCalls(host));
    }

    [Theory]
    [InlineData("http://localhost:43127/stream/cap")]
    [InlineData("http://127.0.0.1:43127/stream/cap%2Fextra")]
    [InlineData("http://user@127.0.0.1:43127/stream/cap")]
    [InlineData("http://127.0.0.1:43127/stream/../cap")]
    public async Task MalformedCapabilityRetiresUnobservedAuthorityRatherThanReturningAUrl(string address)
    {
        await using var host = DownloadHost();
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) => call.Operation == "lease" ? Task.FromResult<object>(address) : initial(call, token);
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var error = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).AcquireDownloadAsync(Request(session), Token));

        Assert.Equal(ErrorCode.ProtocolMismatch, error.Error.Code);
        Assert.DoesNotContain(address, error.Message);
        Assert.Single(LeaseCalls(host));
        Assert.Single(host.Calls, call => call.Operation == "close_session");
        Assert.DoesNotContain(host.Calls, call => call.Operation == "revoke");
    }

    [Fact]
    public async Task PreDispatchCancellationAndDefinitiveBusyDoNotRetireExistingLeases()
    {
        await using var host = DownloadHost();
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        await using var existing = await Provider(services).AcquireDownloadAsync(Request(session), Token);
        using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
        await canceled.CancelAsync();
        var cancel = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).AcquireDownloadAsync(Request(session), canceled.Token));
        Assert.Equal(ErrorCode.Canceled, cancel.Error.Code);
        Assert.Single(LeaseCalls(host));

        host.ErrorForCall = call => call.Operation == "lease" ? new HostError("Busy", "fixture.private.busy", false) : null;
        var busy = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).AcquireDownloadAsync(Request(session), Token));
        Assert.Equal(ErrorCode.Busy, busy.Error.Code);
        Assert.DoesNotContain(host.Calls, call => call.Operation is "close_session" or "revoke");
        Assert.Equal(17, (await Provider(services).PrepareDownloadAsync(Source(session), Token)).Size);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task FailedRevokeRequiresConfirmedOriginalSessionClosure(bool cancelAfterCapture)
    {
        await using var host = DownloadHost();
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) =>
        {
            if (call.Operation == "lease" && cancelAfterCapture) cancellation.Cancel();
            return initial(call, token);
        };
        host.ErrorForCall = call => call.Operation == "revoke" ? new HostError("ResponseTooLarge", "fixture.private.revoke", false) : null;

        if (cancelAfterCapture)
        {
            var error = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).AcquireDownloadAsync(Request(session), cancellation.Token));
            Assert.Equal(ErrorCode.Canceled, error.Error.Code);
        }
        else
        {
            var lease = await Provider(services).AcquireDownloadAsync(Request(session), Token);
            await lease.DisposeAsync();
            await lease.DisposeAsync();
        }

        var acquisition = Assert.Single(LeaseCalls(host));
        Assert.Equal(acquisition.Session, Assert.Single(host.Calls, call => call.Operation == "close_session").Session);
        Assert.Single(host.Calls, call => call.Operation == "revoke");
        var stale = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).PrepareDownloadAsync(Source(session), Token));
        Assert.Equal(ErrorCode.SourceChanged, stale.Error.Code);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task RepeatedDisposalWaitsForTheSamePendingRevoke(bool failed)
    {
        await using var host = DownloadHost();
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var lease = await Provider(services).AcquireDownloadAsync(Request(session), Token);
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource<object>(TaskCreationOptions.RunContinuationsAsynchronously);
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) =>
        {
            if (call.Operation != "revoke") return initial(call, token);
            entered.TrySetResult();
            return release.Task;
        };

        var first = lease.DisposeAsync().AsTask();
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var second = lease.DisposeAsync().AsTask();
        Assert.Same(first, second);
        Assert.False(second.IsCompleted);
        Assert.Single(host.Calls, call => call.Operation == "revoke");
        if (failed) release.TrySetException(new FilewayException(new ErrorInfo(ErrorCode.Timeout, "fixture.private.revoke")));
        else release.TrySetResult(new { });
        await Task.WhenAll(first, second).WaitAsync(TimeSpan.FromSeconds(5), Token);
        await lease.DisposeAsync();
        Assert.Single(host.Calls, call => call.Operation == "revoke");
        Assert.Equal(failed ? 1 : 0, host.Calls.Count(call => call.Operation == "close_session"));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ConcurrentLogoutAndWrapperDisposalShareRevokeWithoutRetirementWaitCycle(bool failed)
    {
        await using var host = DownloadHost();
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var lease = await Provider(services).AcquireDownloadAsync(Request(session), Token);
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource<object>(TaskCreationOptions.RunContinuationsAsynchronously);
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) =>
        {
            if (call.Operation != "revoke") return initial(call, token);
            entered.TrySetResult();
            return release.Task;
        };

        // Start retirement first so its loop is awaiting the inner release.
        var logout = services.LogoutAsync(session.Account, Token);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var disposal = lease.DisposeAsync().AsTask();
        Assert.False(logout.IsCompleted);
        Assert.False(disposal.IsCompleted);
        if (failed) release.TrySetException(new FilewayException(new ErrorInfo(ErrorCode.Timeout, "fixture.private.revoke")));
        else release.TrySetResult(new { });

        await Task.WhenAll(logout, disposal).WaitAsync(TimeSpan.FromSeconds(5), Token);

        Assert.Single(host.Calls, call => call.Operation == "revoke");
        Assert.Single(host.Calls, call => call.Operation == "close_session");
        var stale = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).PrepareDownloadAsync(Source(session), Token));
        Assert.Equal(ErrorCode.SourceChanged, stale.Error.Code);
    }

    [Fact]
    public async Task RevokeAndCloseAndShutdownFailureCannotBeReportedAsSuccessfulDisposal()
    {
        await using var core = DownloadHost();
        var host = new UnclosableDownloadHost(core);
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var lease = await Provider(services).AcquireDownloadAsync(Request(session), Token);
        core.ErrorForCall = call => call.Operation is "revoke" or "close_session"
            ? new HostError("HostFailure", "fixture.private.cleanup", false) : null;

        var error = await Assert.ThrowsAsync<FilewayException>(() => lease.DisposeAsync().AsTask());
        var repeated = await Assert.ThrowsAsync<FilewayException>(() => lease.DisposeAsync().AsTask());

        Assert.Equal(ErrorCode.HostExited, error.Error.Code);
        Assert.Equal(ErrorCode.HostExited, repeated.Error.Code);
        Assert.DoesNotContain("fixture.private", error.Message);
        Assert.Single(core.Calls, call => call.Operation == "revoke");
        Assert.Single(core.Calls, call => call.Operation == "close_session");
        Assert.Equal(1, host.ShutdownCalls);
        var serviceError = await Assert.ThrowsAsync<FilewayException>(() => services.DisposeAsync().AsTask());
        Assert.Equal(ErrorCode.HostExited, serviceError.Error.Code);
    }

    private sealed class UnclosableDownloadHost(CoreReadHost inner) : IHostControlClient
    {
        internal int ShutdownCalls { get; private set; }
        public HostHandshake Handshake => inner.Handshake;
        public Task<HostExit> Completion => inner.Completion;
        public Task<HostReply> CallAsync(HostCall request, CancellationToken cancellationToken) => inner.CallAsync(request, cancellationToken);
        public Task ShutdownAsync(CancellationToken cancellationToken)
        {
            ShutdownCalls++;
            return Task.FromException(new FilewayException(new ErrorInfo(ErrorCode.HostExited, "fixture.private.shutdown")));
        }
        public ValueTask DisposeAsync() => inner.DisposeAsync();
    }
}

using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Credentials;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Services;
using Fileway.Infrastructure.Transfers;

namespace Fileway.Infrastructure.Tests.Services;

public sealed partial class UploadApplicationServiceTests
{
    [Fact]
    public async Task RealLeaseTimeoutRetirementFencesAuthorityWithoutAwaitingItsOwnRunnerCleanup()
    {
        var store = new InMemoryProfileStore();
        await using var host = new CoreReadHost
        {
            Dispatch = (call, _) => call.Operation switch
            {
                "upload_lease" => Task.FromException<object>(new FilewayException(new(ErrorCode.Timeout, "private lease timeout"))),
                "request" => Task.FromResult(CoreReadHost.Http(CoreReadHost.Metadata("/", directory: true))),
                _ => Task.FromException<object>(new InvalidOperationException("Unexpected retirement fixture command.")),
            },
        };
        await using var inner = new FilewayClientServices(host, store, new InMemoryCredentialVault());
        var sources = new RunnerSources { HoldDisposal = true };
        var locks = new RunnerLocks();
        var http = new NoUploadHttp();
        var runner = new FileUploadRunner(store, inner, sources, locks, inner, http);
        var queue = new UploadCoordinator(store, runner);
        var bridge = new UploadApplicationService(inner, inner, inner, store, queue);
        try
        {
            var session = await bridge.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
            var context = Assert.IsType<UploadContext>(bridge.CurrentContext);
            var parent = await inner.GetRootAsync(session, Token);
            var request = new UploadSubmission(Guid.NewGuid(), context, parent, PrivateLocalPath);
            var issue = new TaskCompletionSource<ConnectionIssue>(TaskCreationOptions.RunContinuationsAsynchronously);
            bridge.ConnectionIssue += (_, notification) => issue.TrySetResult(notification);
            try { _ = await bridge.SubmitAsync(request, Token); }
            catch (FilewayException error) { Assert.Equal(ErrorCode.SourceChanged, error.Error.Code); }

            var retired = await issue.Task.WaitAsync(GateTimeout, Token);
            await sources.DisposalEntered.Task.WaitAsync(GateTimeout, Token);
            Assert.Equal(context.Account, retired.Account);
            Assert.Equal(context.SessionId, retired.SessionId);
            Assert.True(retired.SessionInvalidated);
            Assert.Equal(ErrorCode.Timeout, retired.Error.Code);
            Assert.DoesNotContain("private", retired.Error.Message, StringComparison.Ordinal);
            Assert.Null(bridge.CurrentContext);
            Assert.Single(host.Calls, call => call.Operation == "upload_lease");
            Assert.Single(host.Calls, call => call.Operation == "close_session");
            Assert.True(locks.IsHeld(new(context.Account, request.RequestId)));
            Assert.False(Assert.Single(sources.Opened).Disposed);
            Assert.Equal(0, http.Requests);

            var disposal = bridge.DisposeAsync().AsTask();
            Assert.False(disposal.IsCompleted);
            sources.DisposalRelease.TrySetResult();
            await disposal.WaitAsync(GateTimeout, Token);
            Assert.True(Assert.Single(sources.Opened).Disposed);
            Assert.False(locks.IsHeld(new(context.Account, request.RequestId)));
            Assert.Equal(0, http.Requests);
            Assert.DoesNotContain(host.Calls, call => call.Operation == "revoke");
        }
        finally
        {
            sources.DisposalRelease.TrySetResult();
            await bridge.DisposeAsync().AsTask().WaitAsync(GateTimeout, Token);
        }
    }

    private sealed class NoUploadHttp : HttpMessageHandler
    {
        internal int Requests;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref Requests);
            return Task.FromException<HttpResponseMessage>(new InvalidOperationException("A failed lease cannot send a transfer body."));
        }
    }
}

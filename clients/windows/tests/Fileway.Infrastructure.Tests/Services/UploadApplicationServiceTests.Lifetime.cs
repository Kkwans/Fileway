using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Tests.Services;

public sealed partial class UploadApplicationServiceTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task SessionInvalidatedBeforeConnectReturnsCanNeverInstallUploadAuthority(bool restore)
    {
        await using var fixture = new Fixture();
        if (restore) await fixture.SaveProfileAsync();
        var emitted = fixture.Gate();
        fixture.Connection.BeforeReturn = (session, _) =>
        {
            fixture.Connection.EmitIssue(new(session.Account, session.Id,
                new(ErrorCode.AuthenticationExpired, "Opened fixture session was invalidated."), true));
            emitted.TrySetResult();
            return Task.CompletedTask;
        };

        var error = await Assert.ThrowsAsync<FilewayException>(() => restore
            ? fixture.Service.RestoreAsync(fixture.Profile.Id, Token) : fixture.ConnectAsync());

        Assert.True(emitted.Task.IsCompletedSuccessfully);
        Assert.True(error.Error.Code is ErrorCode.SourceChanged or ErrorCode.AuthenticationExpired);
        Assert.Null(fixture.Service.CurrentContext);
        Assert.Null(fixture.Connection.Active);
        Assert.Empty(fixture.Coordinator.Enqueued);
        Assert.Contains("disconnect", fixture.Connection.Calls);
    }

    [Fact]
    public async Task SessionInvalidatedWhileCoordinatorActivationIsAwaitedCannotInstallUploadAuthority()
    {
        await using var fixture = new Fixture();
        var entered = fixture.Gate();
        var release = fixture.Gate();
        fixture.Coordinator.BeforeActivate = async (_, _) =>
        {
            entered.TrySetResult();
            await release.Task.WaitAsync(GateTimeout, Token);
        };

        var connecting = fixture.ConnectAsync();
        await entered.Task.WaitAsync(GateTimeout, Token);
        var opened = Assert.IsType<ConnectionSession>(fixture.Connection.Active);
        fixture.Connection.EmitIssue(new(opened.Account, opened.Id,
            new(ErrorCode.AuthenticationExpired, "Opening fixture session expired."), true));
        Assert.Null(fixture.Service.CurrentContext);
        release.TrySetResult();
        var error = await Assert.ThrowsAsync<FilewayException>(() => connecting.WaitAsync(GateTimeout, Token));

        Assert.True(error.Error.Code is ErrorCode.SourceChanged or ErrorCode.AuthenticationExpired);
        Assert.Null(fixture.Service.CurrentContext);
        Assert.Null(fixture.Connection.Active);
        Assert.Null(fixture.Coordinator.Active);
        Assert.Single(fixture.Coordinator.Calls, call => call == "stop");
        Assert.Empty(fixture.Coordinator.Enqueued);
    }

    [Fact]
    public async Task VerifiedMetadataUpdatedDuringConnectAppliesBeforeSubmissionCanBeAccepted()
    {
        await using var fixture = new Fixture();
        fixture.Connection.BeforeReturn = (session, _) =>
        {
            fixture.Connection.EmitSession(session with { Permissions = session.Permissions with { Create = false } });
            return Task.CompletedTask;
        };

        var verified = await fixture.ConnectAsync();

        Assert.False(verified.Permissions.Create);
        await AssertSafeFailureAsync(ErrorCode.PermissionDenied, () => fixture.Service.SubmitAsync(fixture.Submission(), Token));
        Assert.Empty(fixture.Coordinator.Enqueued);
    }

    [Fact]
    public async Task UnrelatedInvalidationDuringConnectCannotRevokeTheNewVerifiedSession()
    {
        await using var fixture = new Fixture();
        fixture.Connection.BeforeReturn = (session, _) =>
        {
            fixture.Connection.EmitIssue(new(session.Account, Guid.NewGuid(),
                new(ErrorCode.AuthenticationExpired, "Other fixture session expired."), true));
            fixture.Connection.EmitIssue(new(session.Account with { ServerUserId = "different-user" }, session.Id,
                new(ErrorCode.AuthenticationExpired, "Other fixture account expired."), true));
            return Task.CompletedTask;
        };

        var opened = await fixture.ConnectAsync();

        Assert.Equal(opened.Id, fixture.Context.SessionId);
        Assert.DoesNotContain("stop", fixture.Coordinator.Calls);
        Assert.DoesNotContain("disconnect", fixture.Connection.Calls);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task InvalidationDuringSubmissionCannotAdmitTheRetiredEpochEvenIfPersistenceCompletes(bool duringPersistence)
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var context = fixture.Context;
        var entered = fixture.Gate();
        var release = fixture.Gate();
        if (duringPersistence)
        {
            fixture.Store.BeforeCreate = async (_, cancellationToken) =>
            {
                entered.TrySetResult();
                await release.Task.WaitAsync(GateTimeout, cancellationToken);
            };
        }
        else
        {
            fixture.Files.OnGet = async (parent, cancellationToken) =>
            {
                entered.TrySetResult();
                await release.Task.WaitAsync(GateTimeout, cancellationToken);
                return new(parent, true, 0, null);
            };
        }
        var submitting = fixture.Service.SubmitAsync(fixture.Submission(), Token);
        await entered.Task.WaitAsync(GateTimeout, Token);

        fixture.Connection.EmitIssue(new(context.Account, context.SessionId,
            new(ErrorCode.AuthenticationExpired, "Fixture session invalidated while submitting."), true));
        Assert.Null(fixture.Service.CurrentContext);
        release.TrySetResult();
        await AssertSafeFailureAsync(ErrorCode.SourceChanged, () => submitting.WaitAsync(GateTimeout, Token));

        Assert.Equal(duringPersistence ? 1 : 0, fixture.Store.Creates);
        Assert.Empty(fixture.Coordinator.Enqueued);
        await fixture.ConnectAsync();
        var history = await fixture.Service.ListAsync(fixture.Context, null, 50, Token);
        if (duringPersistence) Assert.Equal(UploadStatus.NotStarted, Assert.Single(history.Items).Status);
        else Assert.Empty(history.Items);
        Assert.Empty(fixture.Coordinator.Enqueued);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ReplacementConnectionWaitsForOldDrainBeforeInnerRetirementOrOpen(bool restore)
    {
        await using var fixture = new Fixture();
        var first = await fixture.ConnectAsync();
        var oldContext = fixture.Context;
        var entered = fixture.Gate();
        var release = fixture.Gate();
        fixture.Coordinator.BeforeStop = async (_, _) =>
        {
            entered.TrySetResult();
            await release.Task.WaitAsync(GateTimeout, Token);
        };

        var replacing = restore
            ? fixture.Service.RestoreAsync(fixture.Profile.Id, Token)
            : fixture.ConnectAsync();
        await entered.Task.WaitAsync(GateTimeout, Token);

        Assert.Null(fixture.Service.CurrentContext);
        Assert.False(replacing.IsCompleted);
        Assert.Equal(first, fixture.Connection.Active);
        Assert.Single(fixture.Connection.Calls);
        Assert.Single(fixture.Coordinator.Calls, call => call == "activate");
        release.TrySetResult();
        var second = await replacing.WaitAsync(GateTimeout, Token);

        Assert.NotEqual(first.Id, second.Id);
        Assert.NotEqual(oldContext.ActivationId, fixture.Context.ActivationId);
        Assert.Equal(new[] { "connect", "activate", "stop", "drained", "disconnect", restore ? "restore" : "connect", "activate" }, fixture.Order);
        await AssertSafeFailureAsync(ErrorCode.SourceChanged, () => fixture.Service.ListAsync(oldContext, null, 50, Token));
    }

    [Theory]
    [InlineData("disconnect")]
    [InlineData("logout")]
    [InlineData("forget")]
    public async Task TeardownWaitsForLocalDrainAndCannotAbandonItOnCallerCancellation(string operation)
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var entered = fixture.Gate();
        var release = fixture.Gate();
        fixture.Coordinator.BeforeStop = async (_, cancellationToken) =>
        {
            Assert.False(cancellationToken.IsCancellationRequested);
            entered.TrySetResult();
            await release.Task.WaitAsync(GateTimeout, Token);
        };

        var teardown = TeardownAsync(fixture, operation, caller.Token);
        await entered.Task.WaitAsync(GateTimeout, Token);
        await caller.CancelAsync();

        Assert.Null(fixture.Service.CurrentContext);
        Assert.False(teardown.IsCompleted);
        Assert.NotNull(fixture.Connection.Active);
        Assert.DoesNotContain(operation, fixture.Connection.Calls);
        release.TrySetResult();
        await teardown.WaitAsync(GateTimeout, Token);

        Assert.Null(fixture.Connection.Active);
        Assert.Equal(new[] { "connect", "activate", "stop", "drained", operation }, fixture.Order);
        Assert.Single(fixture.Coordinator.Calls, call => call == "stop");
    }

    [Theory]
    [InlineData("disconnect")]
    [InlineData("logout")]
    [InlineData("forget")]
    public async Task StorageDrainFailureStillAllowsInnerRetirementAndReturnsSafeFailure(string operation)
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        fixture.Coordinator.BeforeStop = (_, _) => throw new IOException("private transfer database location fixture");

        var error = await Assert.ThrowsAsync<FilewayException>(() => TeardownAsync(fixture, operation, Token));

        Assert.Equal(ErrorCode.LocalStorageUnavailable, error.Error.Code);
        Assert.DoesNotContain("private transfer database", error.Message, StringComparison.Ordinal);
        Assert.Null(fixture.Service.CurrentContext);
        Assert.Null(fixture.Connection.Active);
        Assert.Contains(operation, fixture.Connection.Calls);
    }

    [Fact]
    public async Task FailedOldDrainCannotOpenOrActivateAReplacementConnection()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        fixture.Coordinator.BeforeStop = (_, _) => throw new IOException("private transfer database fixture");

        await AssertSafeFailureAsync(ErrorCode.LocalStorageUnavailable, () => fixture.ConnectAsync());

        Assert.Null(fixture.Service.CurrentContext);
        Assert.Single(fixture.Connection.Calls, call => call == "connect");
        Assert.Contains("disconnect", fixture.Connection.Calls);
        Assert.Single(fixture.Coordinator.Calls, call => call == "activate");
    }

    [Fact]
    public async Task FailedInnerConnectLeavesNoNewAuthority()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        fixture.Connection.ConnectFailure = new FilewayException(new(ErrorCode.AuthenticationExpired, "Fixture authentication expired."));

        await AssertSafeFailureAsync(ErrorCode.AuthenticationExpired, () => fixture.ConnectAsync());

        Assert.Null(fixture.Service.CurrentContext);
        Assert.Single(fixture.Coordinator.Calls, call => call == "stop");
        Assert.Single(fixture.Coordinator.Calls, call => call == "activate");
    }

    [Fact]
    public async Task ControlsAndConnectionLifecycleAreSerialized()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var task = await fixture.CreateStoredAsync();
        var entered = fixture.Gate();
        var release = fixture.Gate();
        fixture.Coordinator.BeforeControl = async (_, _) =>
        {
            entered.TrySetResult();
            await release.Task.WaitAsync(GateTimeout, Token);
        };

        var pause = fixture.Service.PauseAsync(fixture.Context, task.Plan.Reference, Token);
        await entered.Task.WaitAsync(GateTimeout, Token);
        var reconnect = fixture.ConnectAsync();

        Assert.False(reconnect.IsCompleted);
        Assert.DoesNotContain("stop", fixture.Coordinator.Calls);
        Assert.Single(fixture.Connection.Calls);
        release.TrySetResult();
        await pause.WaitAsync(GateTimeout, Token);
        await reconnect.WaitAsync(GateTimeout, Token);

        Assert.Equal(ControlledReplacementOrder, fixture.Order);
    }

    [Fact]
    public async Task InvalidatedIssueSynchronouslyRevokesAuthorityAndDrainsIndependentlyOfItsEventHandler()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var context = fixture.Context;
        var entered = fixture.Gate();
        var release = fixture.Gate();
        fixture.Coordinator.BeforeStop = async (_, _) =>
        {
            entered.TrySetResult();
            await release.Task.WaitAsync(GateTimeout, Token);
        };
        var issue = new ConnectionIssue(context.Account, context.SessionId,
            new(ErrorCode.AuthenticationExpired, "Authentication expired."), SessionInvalidated: true);

        fixture.Connection.EmitIssue(issue);

        Assert.Null(fixture.Service.CurrentContext);
        await entered.Task.WaitAsync(GateTimeout, Token);
        await AssertSafeFailureAsync(ErrorCode.SourceChanged, () => fixture.Service.SubmitAsync(
            new(Guid.NewGuid(), context, fixture.Parent, PrivateLocalPath), Token));
        var reconnect = fixture.ConnectAsync();
        Assert.False(reconnect.IsCompleted);
        Assert.Single(fixture.Connection.Calls);
        release.TrySetResult();
        await reconnect.WaitAsync(GateTimeout, Token);

        Assert.NotEqual(context.ActivationId, fixture.Context.ActivationId);
        Assert.Single(fixture.Coordinator.Calls, call => call == "stop");
    }

    [Fact]
    public async Task OutdatedIssueCannotInvalidateAReplacementEpochOrStartAnotherDrain()
    {
        await using var fixture = new Fixture();
        var first = await fixture.ConnectAsync();
        await fixture.ConnectAsync();
        var current = fixture.Context;
        var stopCount = fixture.Coordinator.Calls.Count(call => call == "stop");

        fixture.Connection.EmitIssue(new(first.Account, first.Id,
            new(ErrorCode.AuthenticationExpired, "Old session expired."), true));
        fixture.Connection.EmitIssue(new(first.Account with { ServerUserId = "different-user" }, current.SessionId,
            new(ErrorCode.AuthenticationExpired, "Other account expired."), true));

        Assert.Equal(current, fixture.Service.CurrentContext);
        Assert.Equal(stopCount, fixture.Coordinator.Calls.Count(call => call == "stop"));
        var item = await fixture.Service.SubmitAsync(fixture.Submission(), Token);
        Assert.Equal(current, item.Context);
    }

    [Fact]
    public async Task NoninvalidatingIssuePreservesAuthority()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var current = fixture.Context;

        fixture.Connection.EmitIssue(new(current.Account, current.SessionId,
            new(ErrorCode.NetworkUnavailable, "Network unavailable.", Retryable: true), false));

        Assert.Equal(current, fixture.Service.CurrentContext);
        Assert.DoesNotContain("stop", fixture.Coordinator.Calls);
    }

    [Fact]
    public async Task DisposeWaitsForAnIndependentInvalidationDrainAndOwnsOnlyTheCoordinator()
    {
        var fixture = new Fixture();
        try
        {
            await fixture.ConnectAsync();
            var current = fixture.Context;
            var entered = fixture.Gate();
            var release = fixture.Gate();
            fixture.Coordinator.BeforeStop = async (_, _) =>
            {
                entered.TrySetResult();
                await release.Task.WaitAsync(GateTimeout, Token);
            };
            fixture.Connection.EmitIssue(new(current.Account, current.SessionId,
                new(ErrorCode.AuthenticationExpired, "Authentication expired."), true));
            await entered.Task.WaitAsync(GateTimeout, Token);

            var first = fixture.Service.DisposeAsync().AsTask();
            var second = fixture.Service.DisposeAsync().AsTask();
            Assert.False(first.IsCompleted);
            Assert.False(second.IsCompleted);
            Assert.Equal(0, fixture.Coordinator.Disposals);
            release.TrySetResult();
            await Task.WhenAll(first, second).WaitAsync(GateTimeout, Token);

            Assert.Null(fixture.Service.CurrentContext);
            Assert.Single(fixture.Coordinator.Calls, call => call == "stop");
            Assert.Equal(1, fixture.Coordinator.Disposals);
            Assert.Equal(0, fixture.Connection.Disposals);
            await Assert.ThrowsAsync<ObjectDisposedException>(() => fixture.ConnectAsync());
        }
        finally { await fixture.DisposeAsync(); }
    }

    [Fact]
    public async Task DisposeDrainsBeforeCoordinatorDisposalAndIsIdempotent()
    {
        var fixture = new Fixture();
        try
        {
            await fixture.ConnectAsync();
            var entered = fixture.Gate();
            var release = fixture.Gate();
            fixture.Coordinator.BeforeStop = async (_, _) =>
            {
                entered.TrySetResult();
                await release.Task.WaitAsync(GateTimeout, Token);
            };

            var first = fixture.Service.DisposeAsync().AsTask();
            var second = fixture.Service.DisposeAsync().AsTask();
            await entered.Task.WaitAsync(GateTimeout, Token);
            Assert.Null(fixture.Service.CurrentContext);
            Assert.Equal(0, fixture.Coordinator.Disposals);
            release.TrySetResult();
            await Task.WhenAll(first, second).WaitAsync(GateTimeout, Token);

            Assert.Equal(DisposeOrder, fixture.Order);
            Assert.Equal(1, fixture.Coordinator.Disposals);
            Assert.Equal(0, fixture.Connection.Disposals);
        }
        finally { await fixture.DisposeAsync(); }
    }

    private static Task TeardownAsync(Fixture fixture, string operation, CancellationToken cancellationToken) => operation switch
    {
        "disconnect" => fixture.Service.DisconnectAsync(fixture.Account, cancellationToken),
        "logout" => fixture.Service.LogoutAsync(fixture.Account, cancellationToken),
        "forget" => fixture.Service.ForgetAsync(fixture.Profile.Id, cancellationToken),
        _ => throw new ArgumentOutOfRangeException(nameof(operation)),
    };
}

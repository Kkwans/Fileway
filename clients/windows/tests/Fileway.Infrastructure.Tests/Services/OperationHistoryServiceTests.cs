using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Credentials;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Services;

namespace Fileway.Infrastructure.Tests.Services;

public sealed class OperationHistoryServiceTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Theory]
    [InlineData(OperationKind.TaskCancel)]
    [InlineData(OperationKind.TaskRetry)]
    [InlineData(OperationKind.TaskArchive)]
    [InlineData(OperationKind.TaskUnarchive)]
    public async Task ReopenedUnknownTaskActionOnlyReadsTheOriginalOwnTaskAndRemainsUnresolved(OperationKind kind)
    {
        var store = new InMemoryProfileStore();
        var profile = CoreReadHost.Profile();
        await using var originalHost = new CoreReadHost();
        var original = new FilewayClientServices(originalHost, store, new InMemoryCredentialVault());
        var first = await original.ConnectAsync(profile, CoreReadHost.Login, Token);
        var operation = await SeedAsync(store, first, kind, OperationPhase.OutcomeUnknown, options: new OperationOptions(RecordId: "original-task"));
        await original.DisposeAsync();
        await using var host = new CoreReadHost { Dispatch = (call, _) =>
            Task.FromResult(CoreReadHost.Http(CoreReadHost.TaskRow("original-task", status: "completed"))) };
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault());
        var current = await services.ConnectAsync(profile, CoreReadHost.Login, Token);
        Assert.NotEqual(first.Id, current.Id);
        host.Calls.Clear();

        var result = await services.ReconcileAsync(operation, Token);

        Assert.Equal(OperationResolution.ObservedTaskState, result.Resolution);
        Assert.Equal("original-task", result.Task!.Reference.Id);
        Assert.Equal(ErrorCode.OutcomeUnknown, result.Error!.Code);
        var call = Assert.Single(host.Calls, call => call.Operation == "request");
        Assert.Equal("GET", call.Parameters!.Value.GetProperty("method").GetString());
        Assert.Equal("/api/tasks/original-task", CoreReadHost.Endpoint(call));
        var record = (await store.GetOperationAsync(operation, Token))!;
        Assert.Equal(OperationPhase.OutcomeUnknown, record.Phase);
        Assert.Null(record.Acknowledgement);
        Assert.True(await store.HasUnresolvedOperationsAsync(current.Account, Token));
        var blocked = await Assert.ThrowsAsync<FilewayException>(() => services.ForgetAsync(profile.Id, Token));
        Assert.Equal(ErrorCode.Conflict, blocked.Error.Code);
    }

    [Theory]
    [InlineData(OperationPhase.Intent)]
    [InlineData(OperationPhase.DispatchMarked)]
    public async Task AnIntentFromTheCurrentLiveSessionIsNotClassifiedAsRestartRecovery(OperationPhase phase)
    {
        var store = new InMemoryProfileStore();
        await using var host = new CoreReadHost();
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault());
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var operation = await SeedAsync(store, session, OperationKind.TaskCancel, phase, options: new OperationOptions(RecordId: "task"));
        var before = (await store.GetOperationAsync(operation, Token))!;

        var result = await services.ReconcileAsync(operation, Token);

        Assert.Equal(OperationResolution.Pending, result.Resolution);
        Assert.Equal(ErrorCode.Busy, result.Error!.Code);
        var after = (await store.GetOperationAsync(operation, Token))!;
        Assert.Equal(before.Revision, after.Revision);
        Assert.Equal(phase, after.Phase);
        Assert.True(await store.HasUnresolvedOperationsAsync(session.Account, Token));
    }

    [Fact]
    public async Task APreDispatchIntentFromARetiredSessionCanBeProvedNotSent()
    {
        var store = new InMemoryProfileStore();
        var profile = CoreReadHost.Profile();
        await using var firstHost = new CoreReadHost();
        var firstServices = new FilewayClientServices(firstHost, store, new InMemoryCredentialVault());
        var first = await firstServices.ConnectAsync(profile, CoreReadHost.Login, Token);
        var operation = await SeedAsync(store, first, OperationKind.CreateEmptyFile, OperationPhase.Intent);
        await firstServices.DisposeAsync();
        await using var host = new CoreReadHost();
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault());
        var second = await services.ConnectAsync(profile, CoreReadHost.Login, Token);
        host.Calls.Clear();

        var result = await services.ReconcileAsync(operation, Token);

        Assert.Equal(OperationResolution.NotSent, result.Resolution);
        Assert.False(await store.HasUnresolvedOperationsAsync(second.Account, Token));
        Assert.DoesNotContain(host.Calls, call => call.Operation == "request");
    }

    [Fact]
    public async Task IdenticalResourceMetadataDoesNotProveAnUnknownTransferOrGuessANewTask()
    {
        var store = new InMemoryProfileStore();
        var profile = CoreReadHost.Profile();
        await using var firstHost = new CoreReadHost();
        var firstServices = new FilewayClientServices(firstHost, store, new InMemoryCredentialVault());
        var first = await firstServices.ConnectAsync(profile, CoreReadHost.Login, Token);
        RemoteResourceRef source = new(first.Account, "/%FF/source", "source", "/display/source");
        RemoteResourceRef target = new(first.Account, "/%FF/target", "target", "/display/target");
        var operation = await SeedAsync(store, first, OperationKind.Copy, OperationPhase.OutcomeUnknown,
            [new OperationItem(0, new OperationResourceSnapshot(source, false, 17), new OperationResourceSnapshot(target, false, 17))]);
        await firstServices.DisposeAsync();
        await using var host = new CoreReadHost { Dispatch = (call, _) =>
        {
            string endpoint = CoreReadHost.Endpoint(call);
            Assert.StartsWith("/api/resources/", endpoint);
            string wire = endpoint["/api/resources".Length..].Split('?')[0];
            return Task.FromResult(CoreReadHost.Http(CoreReadHost.Metadata(wire)));
        } };
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault());
        var current = await services.ConnectAsync(profile, CoreReadHost.Login, Token);
        host.Calls.Clear();

        var result = await services.ReconcileAsync(operation, Token);

        Assert.Equal(OperationResolution.NeedsReview, result.Resolution);
        Assert.Null(result.Task);
        Assert.True(await store.HasUnresolvedOperationsAsync(current.Account, Token));
        Assert.All(host.Calls.Where(call => call.Operation == "request"), call =>
            Assert.Equal("GET", call.Parameters!.Value.GetProperty("method").GetString()));
        Assert.DoesNotContain(host.Calls, call => call.Operation == "request" && CoreReadHost.Endpoint(call).StartsWith("/api/tasks", StringComparison.Ordinal));
    }

    [Fact]
    public async Task MissingTrashIdDoesNotProveRestorationOrConstructAResourceFromDisplay()
    {
        var store = new InMemoryProfileStore();
        var profile = CoreReadHost.Profile();
        await using var firstHost = new CoreReadHost();
        var firstServices = new FilewayClientServices(firstHost, store, new InMemoryCredentialVault());
        var first = await firstServices.ConnectAsync(profile, CoreReadHost.Login, Token);
        const string display = "/original/�%2F+";
        var operation = await SeedAsync(store, first, OperationKind.Restore, OperationPhase.OutcomeUnknown,
            options: new OperationOptions(RecordId: "missing-trash", Conflict: FileConflictPolicy.KeepBoth, DisplayPath: display));
        await firstServices.DisposeAsync();
        await using var host = new CoreReadHost { Dispatch = (_, _) => Task.FromResult(CoreReadHost.Http(Array.Empty<object>())) };
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault());
        var current = await services.ConnectAsync(profile, CoreReadHost.Login, Token);
        host.Calls.Clear();

        var result = await services.ReconcileAsync(operation, Token);

        Assert.Equal(OperationResolution.NeedsReview, result.Resolution);
        Assert.True(await store.HasUnresolvedOperationsAsync(current.Account, Token));
        var request = Assert.Single(host.Calls, call => call.Operation == "request");
        Assert.Equal("/api/trash", CoreReadHost.Endpoint(request));
        var record = (await store.GetOperationAsync(operation, Token))!;
        Assert.Equal(display, record.Intent.Options!.DisplayPath);
        Assert.Empty(record.Intent.Items);
        Assert.Null(record.Acknowledgement);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task FavoriteAndTagAcknowledgementsSurviveRetirementBeforeTheObservedReply(bool tag)
    {
        var started = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var retired = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var store = new InMemoryProfileStore();
        await using var host = new CoreReadHost();
        host.Dispatch = async (_, token) =>
        {
            using var registration = token.Register(() => retired.TrySetResult());
            started.TrySetResult();
            await release.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
            return tag ? CoreReadHost.Http(new
            {
                id = "tag", name = "tag", color = "#123456", createdAt = 1000L,
                pathRefs = new[] { new { path = "/�", wirePath = "/%FF", pathVerified = true } },
            }) : CoreReadHost.Http(new { id = "favorite", name = "favorite", groupId = "", order = 0,
                addedAt = 1000L, path = "/�", wirePath = "/%FF", pathVerified = true });
        };
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault());
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var write = tag ? services.UpdateTagAsync(session.Account, "tag", "tag", "#123456", Token)
            : services.AddFavoriteAsync(new RemoteResourceRef(session.Account, "/%FF", "�", "/�"), "favorite", null, Token);
        await started.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var disconnect = services.DisconnectAsync(session.Account, Token);
        await retired.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        release.TrySetResult();

        var result = await write.WaitAsync(TimeSpan.FromSeconds(5), Token);
        await disconnect.WaitAsync(TimeSpan.FromSeconds(5), Token);

        Assert.Equal(OperationOutcome.Confirmed, result.Outcome);
        Assert.Equal(tag ? "tag" : "favorite", result.RecordId);
        var record = (await store.GetOperationAsync(new OperationRef(session.Account, result.Receipt!.OperationId), Token))!;
        Assert.Equal(OperationPhase.Acknowledged, record.Phase);
        Assert.Equal(OperationAckKind.MetadataConfirmed, record.Acknowledgement!.Kind);
    }

    private static async Task<OperationRef> SeedAsync(InMemoryProfileStore store, ConnectionSession session, OperationKind kind,
        OperationPhase phase, IReadOnlyList<OperationItem>? items = null, OperationOptions? options = null)
    {
        var reference = new OperationRef(session.Account, Guid.NewGuid());
        var record = await store.CreateIntentAsync(new OperationIntent(reference, session.Id, kind, items ?? [], options), Token);
        if (phase != OperationPhase.Intent) record = await store.MarkDispatchAsync(reference, record.Revision, Token);
        if (phase == OperationPhase.OutcomeUnknown)
            _ = await store.RecordRejectedOrUnknownAsync(reference, record.Revision, phase,
                new ErrorInfo(ErrorCode.OutcomeUnknown, "fixture"), Token);
        return reference;
    }
}

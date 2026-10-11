using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Services;

namespace Fileway.Infrastructure.Tests.Services;

public sealed class ServerTaskTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact]
    public async Task ListAlwaysFiltersThePrincipalAndPreservesCursorCountsAndUnknownStatuses()
    {
        const string cursor = "opaque%2F+&=";
        await using var host = new CoreReadHost
        {
            Dispatch = (_, _) => Task.FromResult(CoreReadHost.Http(new
            {
                items = new[] { CoreReadHost.TaskRow(type: "future.task", status: "future-state") }, nextCursor = cursor,
                total = 9, counts = new { running = 4, future = 5 }, categoryCounts = new { file = 7, background = 2 },
            })),
        };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var page = await services.ListTasksAsync(session.Account, new ServerTaskQuery(Text: "x + &", Cursor: cursor,
            Statuses: [ServerTaskStatus.Running], Category: ServerTaskCategory.File), Token);

        var task = Assert.Single(page.Items);
        Assert.Equal(ServerTaskStatus.Unknown, task.Status);
        Assert.Equal("future-state", task.RawStatus);
        Assert.Equal("future.task", task.RawType);
        Assert.Equal(cursor, page.NextCursor);
        Assert.Equal(9, page.Total);
        Assert.Equal(7, page.CategoryCounts["file"]);
        string endpoint = CoreReadHost.Endpoint(Assert.Single(host.Calls, call => call.Operation == "request" &&
            CoreReadHost.Endpoint(call).StartsWith("/api/tasks?", StringComparison.Ordinal)));
        var query = endpoint.Split('?')[1].Split('&').Select(pair => pair.Split('=', 2))
            .ToDictionary(pair => pair[0], pair => Uri.UnescapeDataString(pair[1]));
        Assert.Equal(session.Account.ServerUserId, query["user"]);
        Assert.Equal(cursor, query["cursor"]);
        Assert.Equal("x + &", query["text"]);
        Assert.Equal("running", query["status"]);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ForeignTaskRowsAreRejectedEvenForAnAdministrator(bool list)
    {
        await using var host = new CoreReadHost
        {
            Dispatch = (_, _) => Task.FromResult(CoreReadHost.Http(list
                ? new { items = new[] { CoreReadHost.TaskRow(owner: 8) }, total = 1, counts = new { }, categoryCounts = new { } }
                : CoreReadHost.TaskRow(owner: 8))),
        };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var error = await Assert.ThrowsAsync<FilewayException>(async () =>
        {
            if (list) _ = await services.ListTasksAsync(session.Account, new ServerTaskQuery(), Token);
            else _ = await services.GetTaskAsync(new ServerTaskRef(session.Account, "fixture-task"), Token);
        });

        Assert.Equal(ErrorCode.PermissionDenied, error.Error.Code);
    }

    [Theory]
    [InlineData(ServerTaskActionKind.CancellationRequested, "cancel", 202)]
    [InlineData(ServerTaskActionKind.RetryAccepted, "retry", 202)]
    [InlineData(ServerTaskActionKind.Archived, "archive", 200)]
    [InlineData(ServerTaskActionKind.Unarchived, "unarchive", 200)]
    public async Task ActionsKeepExactAcknowledgementSemanticsAndOwnedIds(ServerTaskActionKind action, string suffix, int status)
    {
        const string originalId = "task%+&";
        await using var host = new CoreReadHost();
        host.Dispatch = (call, _) => Task.FromResult(CoreReadHost.Http(
            call.Parameters!.Value.GetProperty("method").GetString() == "GET" ? CoreReadHost.TaskRow(originalId) :
            CoreReadHost.TaskRow(action == ServerTaskActionKind.RetryAccepted ? "retry-id" : originalId,
                status: action is ServerTaskActionKind.Archived or ServerTaskActionKind.Unarchived ? "completed" : "running",
                retryOf: action == ServerTaskActionKind.RetryAccepted ? originalId : null,
                archivedAt: action == ServerTaskActionKind.Archived ? 1000 : null),
            call.Parameters.Value.GetProperty("method").GetString() == "GET" ? 200 : status));
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var result = await Act(services, new ServerTaskRef(session.Account, originalId), action);

        Assert.Equal(OperationOutcome.Confirmed, result.Outcome);
        Assert.Equal(action, result.Kind);
        Assert.NotNull(result.Receipt);
        if (action == ServerTaskActionKind.CancellationRequested) Assert.Equal(ServerTaskStatus.Running, result.Task!.Status);
        if (action == ServerTaskActionKind.RetryAccepted)
        {
            Assert.NotEqual(originalId, result.Task!.Reference.Id);
            Assert.Equal(originalId, result.Task.RetryOf);
        }
        var write = Assert.Single(host.Calls, IsWrite);
        Assert.Equal("/api/tasks/task%25%2B%26/" + suffix, CoreReadHost.Endpoint(write));
        Assert.False(write.Parameters!.Value.TryGetProperty("body", out _));
        var record = Assert.Single((await services.ListOperationsAsync(session.Account, null, 20, Token)).Items);
        Assert.Equal(OperationPhase.Acknowledged, record.Phase);
        Assert.Equal(result.Task!.Reference, record.Acknowledgement!.Task);
    }

    [Theory]
    [InlineData("foreign")]
    [InlineData("wrong-id")]
    [InlineData("wrong-retry-of")]
    public async Task InvalidRetryAcknowledgementStaysUnknownWithoutReplayingTheRequest(string kind)
    {
        await using var host = new CoreReadHost();
        host.Dispatch = (call, _) => Task.FromResult(CoreReadHost.Http(IsWrite(call)
            ? CoreReadHost.TaskRow(kind == "wrong-id" ? "original" : "new-task", owner: kind == "foreign" ? 8 : 7,
                retryOf: kind == "wrong-retry-of" ? "different" : "original") : CoreReadHost.TaskRow("original"), IsWrite(call) ? 202 : 200));
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var result = await services.RetryTaskAsync(new ServerTaskRef(session.Account, "original"), Token);

        Assert.Equal(OperationOutcome.OutcomeUnknown, result.Outcome);
        Assert.Equal(ErrorCode.OutcomeUnknown, result.Error!.Code);
        Assert.Single(host.Calls, IsWrite);
        Assert.Equal(OperationPhase.OutcomeUnknown, Assert.Single((await services.ListOperationsAsync(session.Account, null, 20, Token)).Items).Phase);
    }

    [Fact]
    public async Task ForeignPreflightPreventsTaskMutationAndArchiveConflictRemainsRejected()
    {
        await using var host = new CoreReadHost();
        host.Dispatch = (call, _) => Task.FromResult(CoreReadHost.Http(CoreReadHost.TaskRow(owner: 8)));
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var reference = new ServerTaskRef(session.Account, "fixture-task");

        var denied = await services.CancelTaskAsync(reference, Token);
        Assert.Equal(ErrorCode.PermissionDenied, denied.Error!.Code);
        Assert.DoesNotContain(host.Calls, IsWrite);
        host.Dispatch = (call, _) => Task.FromResult(CoreReadHost.Http(CoreReadHost.TaskRow(), IsWrite(call) ? 409 : 200));
        var rejected = await services.ArchiveTaskAsync(reference, Token);
        Assert.Equal(OperationOutcome.Partial, rejected.Outcome);
        Assert.Equal(ErrorCode.Conflict, rejected.Error!.Code);
        Assert.Single(host.Calls, IsWrite);
    }

    private static bool IsWrite(HostCall call) => call.Operation == "request" && call.Parameters!.Value.GetProperty("method").GetString() == "POST";
    private static Task<ServerTaskActionResult> Act(FilewayClientServices services, ServerTaskRef task, ServerTaskActionKind kind) => kind switch
    {
        ServerTaskActionKind.CancellationRequested => services.CancelTaskAsync(task, Token),
        ServerTaskActionKind.RetryAccepted => services.RetryTaskAsync(task, Token),
        ServerTaskActionKind.Archived => services.ArchiveTaskAsync(task, Token),
        _ => services.UnarchiveTaskAsync(task, Token),
    };
}

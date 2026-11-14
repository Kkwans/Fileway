using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Services;

namespace Fileway.Infrastructure.Tests.Services;

public sealed class TrashServiceTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private const string OriginalDisplay = "/display/�%2F+.bin";

    [Fact]
    public async Task ListFiltersForeignRowsAndKeepsUncertainDirectorySizesUnknown()
    {
        await using var host = TrashHost(directory: true, sizeState: "calculating");
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var item = Assert.Single(await services.ListTrashAsync(session.Account, Token));

        Assert.Equal("owned", item.Reference.Id);
        Assert.Equal("7", item.OwnerUserId);
        Assert.Null(item.Size);
        Assert.Equal("calculating", item.SizeState);
        Assert.Equal(OriginalDisplay, item.DisplayPath);
    }

    [Theory]
    [InlineData(FileConflictPolicy.Fail, "fail")]
    [InlineData(FileConflictPolicy.KeepBoth, "keep-both")]
    [InlineData(FileConflictPolicy.Replace, "replace")]
    [InlineData(FileConflictPolicy.Skip, "skip")]
    public async Task RestorePreservesConflictAndDisplayOnlyAcknowledgementInTheJournal(FileConflictPolicy conflict, string strategy)
    {
        await using var host = TrashHost();
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) => IsRestore(call)
            ? Task.FromResult(CoreReadHost.Http(new { path = OriginalDisplay, skipped = conflict == FileConflictPolicy.Skip }))
            : initial(call, token);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var result = await services.RestoreTrashAsync(new TrashItemRef(session.Account, "owned"), conflict, Token);

        Assert.Equal(OperationOutcome.Confirmed, result.Outcome);
        Assert.Equal(conflict == FileConflictPolicy.Skip, result.Skipped);
        Assert.Equal(OriginalDisplay, result.RestoredDisplayPath);
        var call = Assert.Single(host.Calls, IsRestore);
        Assert.Equal(strategy, call.Parameters!.Value.GetProperty("body").GetProperty("conflict").GetString());
        var record = Assert.Single((await services.ListOperationsAsync(session.Account, null, 20, Token)).Items);
        Assert.Equal(OperationKind.Restore, record.Intent.Kind);
        Assert.Empty(record.Intent.Items);
        Assert.Equal(OriginalDisplay, record.Intent.Options!.DisplayPath);
        Assert.Equal("owned", record.Intent.Options.RecordId);
        Assert.Equal(OperationAckKind.TrashRestored, record.Acknowledgement!.Kind);
        Assert.Equal(OriginalDisplay, record.Acknowledgement.DisplayPath);
    }

    [Theory]
    [InlineData("foreign", true, true)]
    [InlineData("owned", false, true)]
    [InlineData("owned", true, false)]
    public async Task ForeignRowsAndMissingCreateOrReplaceDeletePermissionNeverDispatch(string id, bool create, bool delete)
    {
        await using var host = TrashHost();
        host.Create = create;
        host.Delete = delete;
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var result = await services.RestoreTrashAsync(new TrashItemRef(session.Account, id), FileConflictPolicy.Replace, Token);

        Assert.Equal(OperationOutcome.Partial, result.Outcome);
        Assert.Equal(ErrorCode.PermissionDenied, result.Error!.Code);
        Assert.DoesNotContain(host.Calls, IsRestore);
    }

    [Theory]
    [InlineData(202, OperationOutcome.OutcomeUnknown)]
    [InlineData(500, OperationOutcome.OutcomeUnknown)]
    [InlineData(409, OperationOutcome.Partial)]
    public async Task NonSynchronousOrRejectedRestoreCannotClaimSuccessOrReplay(int status, OperationOutcome expected)
    {
        await using var host = TrashHost();
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) => IsRestore(call)
            ? Task.FromResult(CoreReadHost.Http(new { path = OriginalDisplay, skipped = false }, status)) : initial(call, token);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var result = await services.RestoreTrashAsync(new TrashItemRef(session.Account, "owned"), FileConflictPolicy.Fail, Token);

        Assert.Equal(expected, result.Outcome);
        Assert.NotNull(result.Receipt);
        Assert.Single(host.Calls, IsRestore);
    }

    [Fact]
    public async Task SuccessfulRestoreAcknowledgementSurvivesPostAckRefreshFailure()
    {
        await using var host = TrashHost();
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) =>
        {
            if (IsRestore(call))
            {
                host.CurrentToken = CoreReadHost.Jwt("changed");
                host.UserReadStatus = 503;
                return Task.FromResult(CoreReadHost.Http(new { path = OriginalDisplay, skipped = false }));
            }
            return initial(call, token);
        };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var result = await services.RestoreTrashAsync(new TrashItemRef(session.Account, "owned"), FileConflictPolicy.KeepBoth, Token);

        Assert.Equal(OperationOutcome.Confirmed, result.Outcome);
        Assert.Equal(OriginalDisplay, result.RestoredDisplayPath);
        Assert.Single(host.Calls, IsRestore);
    }

    private static CoreReadHost TrashHost(bool directory = false, string sizeState = "accurate") => new()
    {
        Dispatch = (_, _) => Task.FromResult(CoreReadHost.Http(new[]
        {
            new { id = "owned", userId = 7, originalPath = OriginalDisplay, status = "available", isDir = directory,
                size = 19, sizeState, deletedAt = 1000L },
            new { id = "foreign", userId = 8, originalPath = "/foreign/private", status = "available", isDir = false,
                size = 99, sizeState = "accurate", deletedAt = 1000L },
        })),
    };
    private static bool IsRestore(HostCall call) => call.Operation == "request" &&
        CoreReadHost.Endpoint(call).EndsWith("/restore", StringComparison.Ordinal);
}

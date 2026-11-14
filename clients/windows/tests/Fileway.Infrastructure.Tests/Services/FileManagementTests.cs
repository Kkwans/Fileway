using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Services;

namespace Fileway.Infrastructure.Tests.Services;

public sealed class FileManagementTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact]
    public async Task EmptyFilePreservesOpaqueParentOmitsBodyAndNeverOverwrites()
    {
        const string parentWire = "/%FF";
        const string target = parentWire + "/empty%252F%2B.bin";
        await using var host = ManagementHost(wire => wire == parentWire ? CoreReadHost.Metadata(wire, directory: true) : null);
        host.Download = false;
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var result = await services.CreateEmptyFileAsync(Ref(session, parentWire), "empty%2F+.bin", Token);
        Assert.Equal(OperationOutcome.Confirmed, result.Outcome);
        Assert.NotNull(result.Receipt);
        var write = Assert.Single(host.Calls, IsFileWrite);
        Assert.Equal("/api/resources" + target + "?override=false", CoreReadHost.Endpoint(write));
        Assert.False(write.Parameters!.Value.TryGetProperty("body", out _));
        var record = Assert.Single((await services.ListOperationsAsync(session.Account, null, 20, Token)).Items);
        Assert.Equal(OperationKind.CreateEmptyFile, record.Intent.Kind);
        Assert.Equal(target, Assert.Single(record.Intent.Items).Target!.Resource.WirePath);
    }

    [Fact]
    public async Task ExistingEmptyFileTargetIsRejectedBeforeAnyWrite()
    {
        await using var host = ManagementHost(wire => CoreReadHost.Metadata(wire, directory: wire == "/parent"));
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var error = await Assert.ThrowsAsync<FilewayException>(() => services.CreateEmptyFileAsync(Ref(session, "/parent"), "existing", Token));
        Assert.Equal(ErrorCode.Conflict, error.Error.Code);
        Assert.DoesNotContain(host.Calls, IsFileWrite);
        Assert.Empty((await services.ListOperationsAsync(session.Account, null, 20, Token)).Items);
    }

    [Fact]
    public async Task MetadataBatchKeepsInputOrderAndPerItemFailuresWithoutAWriteJournal()
    {
        await using var host = ManagementHost(wire => wire == "/missing" ? null : CoreReadHost.Metadata(wire));
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        RemoteResourceRef[] requested = [Ref(session, "/%FF"), Ref(session, "/missing"), Ref(session, "/literal%252F%2B")];
        var result = await services.GetMetadataBatchAsync(requested, Token);
        Assert.Equal(requested, result.Select(row => row.Requested));
        Assert.Equal("/%FF", result[0].File!.Resource.WirePath);
        Assert.Null(result[1].File);
        Assert.Equal(404, result[1].Error!.HttpStatus);
        Assert.Equal("/literal%252F%2B", result[2].File!.Resource.WirePath);
        Assert.Empty((await services.ListOperationsAsync(session.Account, null, 20, Token)).Items);
    }

    [Theory]
    [InlineData("count")]
    [InlineData("order")]
    [InlineData("item-identity")]
    public async Task MetadataBatchRejectsMissingReorderedOrForeignIdentityRows(string malformed)
    {
        await using var host = ManagementHost(wire => CoreReadHost.Metadata(wire));
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) => CoreReadHost.Endpoint(call) == "/api/resources/batch"
            ? Task.FromResult(CoreReadHost.Http(malformed == "count" ? Array.Empty<object>() : new[]
            {
                new { wirePath = malformed == "order" ? "/b" : "/a", status = 200,
                    item = CoreReadHost.Metadata(malformed == "item-identity" ? "/other" : "/a") },
                new { wirePath = "/b", status = 200, item = CoreReadHost.Metadata("/b") },
            })) : initial(call, token);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var error = await Assert.ThrowsAsync<FilewayException>(() => services.GetMetadataBatchAsync([Ref(session, "/a"), Ref(session, "/b")], Token));
        Assert.Equal(ErrorCode.ProtocolMismatch, error.Error.Code);
    }

    [Fact]
    public async Task PreviewAndExecuteUseAnImmutablePlanIncludingSwappedNames()
    {
        await using var host = ManagementHost(wire => CoreReadHost.Metadata(wire));
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        BatchRenameItem[] draftItems = [new(Ref(session, "/dir/A"), "B"), new(Ref(session, "/dir/B"), "A")];
        var preview = await services.PreviewBatchRenameAsync(new BatchRenameDraft(Guid.NewGuid(), draftItems), Token);
        Assert.True(preview.Valid);
        var original = preview.Items[0];
        Assert.Throws<NotSupportedException>(() => ((IList<BatchRenamePreviewItem>)preview.Items)[0] =
            original with { Target = Ref(session, "/dir/injected") });
        draftItems[0] = new BatchRenameItem(Ref(session, "/dir/injected"), "injected");
        var result = await services.ExecuteBatchRenameAsync(preview.Executable!.Value, Token);
        Assert.Equal(OperationOutcome.Confirmed, result.Outcome);
        Assert.True(result.Executed);
        var execute = Assert.Single(host.Calls, IsRenameExecute);
        var items = execute.Parameters!.Value.GetProperty("body").GetProperty("items");
        Assert.Equal("/dir/A", items[0].GetProperty("fromWirePath").GetString());
        Assert.Equal("/dir/B", items[0].GetProperty("toWirePath").GetString());
        Assert.Equal("/dir/B", items[1].GetProperty("fromWirePath").GetString());
        Assert.Equal("/dir/A", items[1].GetProperty("toWirePath").GetString());
    }

    [Fact]
    public async Task InvalidPreviewHasNoExecutableTokenAndKeepsPerItemErrors()
    {
        await using var host = ManagementHost(wire => CoreReadHost.Metadata(wire));
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) => CoreReadHost.Endpoint(call) == "/api/resources/batch-rename"
            ? Task.FromResult(RenameReply(call, valid: false)) : initial(call, token);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var result = await services.PreviewBatchRenameAsync(Draft(session), Token);
        Assert.False(result.Valid);
        Assert.Null(result.Executable);
        Assert.False(Assert.Single(result.Items).Valid);
        Assert.NotNull(result.Error);
        Assert.DoesNotContain(host.Calls, IsRenameExecute);
    }

    [Fact]
    public async Task SourceChangedAfterPreviewPreventsExecuteDispatch()
    {
        int size = 17;
        await using var host = ManagementHost(wire => CoreReadHost.Metadata(wire, size: size));
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var preview = await services.PreviewBatchRenameAsync(Draft(session), Token);
        size = 18;
        var result = await services.ExecuteBatchRenameAsync(preview.Executable!.Value, Token);
        Assert.Equal(OperationOutcome.Partial, result.Outcome);
        Assert.Equal(ErrorCode.Conflict, result.Error!.Code);
        Assert.DoesNotContain(host.Calls, IsRenameExecute);
    }

    [Fact]
    public async Task UnknownExecuteLocksTheTokenAndItsDraftWithoutAutomaticReplay()
    {
        await using var host = ManagementHost(wire => CoreReadHost.Metadata(wire));
        host.ErrorForCall = call => IsRenameExecute(call) ? new HostError("OutcomeUnknown", "fixture", false) : null;
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var draft = Draft(session);
        var preview = await services.PreviewBatchRenameAsync(draft, Token);
        var result = await services.ExecuteBatchRenameAsync(preview.Executable!.Value, Token);
        var retry = await Assert.ThrowsAsync<FilewayException>(() => services.ExecuteBatchRenameAsync(preview.Executable.Value, Token));
        var newPreview = await Assert.ThrowsAsync<FilewayException>(() => services.PreviewBatchRenameAsync(draft, Token));
        Assert.Equal(OperationOutcome.OutcomeUnknown, result.Outcome);
        Assert.Equal(ErrorCode.OutcomeUnknown, retry.Error.Code);
        Assert.Equal(ErrorCode.OutcomeUnknown, newPreview.Error.Code);
        Assert.Single(host.Calls, IsRenameExecute);
    }

    [Fact]
    public async Task ExecutingPlanPreventsAnotherPreviewReservationWithoutWaitingOnTheSessionGate()
    {
        var started = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(Token);
        deadline.CancelAfter(TimeSpan.FromSeconds(10));
        await using var host = ManagementHost(wire => CoreReadHost.Metadata(wire));
        var initial = host.Dispatch!;
        host.Dispatch = async (call, token) =>
        {
            if (IsRenameExecute(call))
            {
                started.TrySetResult();
                await release.Task.WaitAsync(token);
            }
            return await initial(call, token);
        };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var draft = Draft(session);
        var preview = await services.PreviewBatchRenameAsync(draft, Token);
        var execute = services.ExecuteBatchRenameAsync(preview.Executable!.Value, deadline.Token);
        await started.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var conflict = await Assert.ThrowsAsync<FilewayException>(() => services.PreviewBatchRenameAsync(draft, Token)
            .WaitAsync(TimeSpan.FromSeconds(3), Token));
        Assert.Equal(ErrorCode.Busy, conflict.Error.Code);
        release.TrySetResult();
        Assert.Equal(OperationOutcome.Confirmed, (await execute.WaitAsync(TimeSpan.FromSeconds(5), Token)).Outcome);
    }

    [Fact]
    public async Task LegacyPreviewFallbackIsUtf8OnlyAndExecuteUsesThatExactReviewedFormat()
    {
        await using var host = ManagementHost(wire => CoreReadHost.Metadata(wire));
        host.WireOperations = false;
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) => CoreReadHost.Endpoint(call) == "/api/resources/batch-rename" &&
            call.Parameters!.Value.GetProperty("body").GetProperty("items")[0].TryGetProperty("fromWirePath", out _)
            ? Task.FromResult(CoreReadHost.Http(null, 400)) : initial(call, token);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var preview = await services.PreviewBatchRenameAsync(Draft(session), Token);
        var result = await services.ExecuteBatchRenameAsync(preview.Executable!.Value, Token);
        Assert.Equal(OperationOutcome.Confirmed, result.Outcome);
        var execute = Assert.Single(host.Calls, IsRenameExecute);
        var row = execute.Parameters!.Value.GetProperty("body").GetProperty("items")[0];
        Assert.Equal("/dir/A", row.GetProperty("from").GetString());
        Assert.Equal("/dir/B", row.GetProperty("to").GetString());
        Assert.False(row.TryGetProperty("fromWirePath", out _));
    }

    [Fact]
    public async Task RenameWithTheSameDisplayButDifferentFilesystemBytesIsAllowed()
    {
        await using var host = ManagementHost(_ => new { name = "�", path = "/�", wirePath = "/%FF", isDir = false,
            size = 17, modified = "2026-10-11T11:00:00Z", isSymlink = false });
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var result = await services.RenameAsync(new RemoteResourceRef(session.Account, "/%FF", "�", "/�"), "�", Token);
        Assert.Equal(OperationOutcome.Confirmed, result.Outcome);
        var write = Assert.Single(host.Calls, IsFileWrite);
        Assert.Contains("destinationWirePath=%2F%25EF%25BF%25BD", CoreReadHost.Endpoint(write));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task CopyKeepBothWithinTheSameDirectoryAcceptsOpaqueIdentityWithoutModify(bool opaque)
    {
        string wire = opaque ? "/dst/item%FF.bin" : "/dst/literal%252F%2B.bin";
        await using var host = ManagementHost(path => CoreReadHost.Metadata(path, directory: path == "/dst"));
        host.Modify = false;
        host.Download = false;
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var result = await services.SubmitTransferAsync(new FileTransferRequest(FileTransferAction.Copy, Ref(session, "/dst"),
            [new FileTransferItem(Ref(session, wire), FileConflictPolicy.KeepBoth)]), Token);
        Assert.Equal(ServerTaskSubmissionOutcome.Accepted, result.Outcome);
        Assert.Equal(ServerTaskStatus.Running, result.AcceptedTask!.Status);
        var call = Assert.Single(host.Calls, IsTransfer);
        var item = call.Parameters!.Value.GetProperty("body").GetProperty("items")[0];
        Assert.Equal(wire, item.GetProperty("fromWirePath").GetString());
        Assert.Equal(wire, item.GetProperty("toWirePath").GetString());
        Assert.True(item.GetProperty("rename").GetBoolean());
        Assert.False(item.GetProperty("overwrite").GetBoolean());
        Assert.Equal("2026-10-11T11:00:00.000000001Z", item.GetProperty("modified").GetString());
    }

    [Theory]
    [InlineData(FileTransferAction.Copy, FileConflictPolicy.Fail)]
    [InlineData(FileTransferAction.Copy, FileConflictPolicy.Replace)]
    [InlineData(FileTransferAction.Move, FileConflictPolicy.KeepBoth)]
    public async Task UnsafeSameResourceTransfersAreRejectedBeforeDispatch(FileTransferAction action, FileConflictPolicy conflict)
    {
        await using var host = ManagementHost(path => CoreReadHost.Metadata(path, directory: path == "/dst"));
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var error = await Assert.ThrowsAsync<FilewayException>(() => services.SubmitTransferAsync(new FileTransferRequest(action,
            Ref(session, "/dst"), [new FileTransferItem(Ref(session, "/dst/file"), conflict)]), Token));
        Assert.Equal(ErrorCode.InvalidRequest, error.Error.Code);
        Assert.DoesNotContain(host.Calls, IsTransfer);
    }

    [Fact]
    public async Task TransferPreservesOpaqueBasenameAndSkipsUnsubmittedItems()
    {
        const string source = "/src/item%FF%252F%2B.bin";
        const string target = "/dst/item%FF%252F%2B.bin";
        await using var host = ManagementHost(wire => wire == "/dst" ? CoreReadHost.Metadata(wire, directory: true) :
            wire == source ? CoreReadHost.Metadata(wire) : null);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var result = await services.SubmitTransferAsync(new FileTransferRequest(FileTransferAction.Copy, Ref(session, "/dst"),
            [new FileTransferItem(Ref(session, source)), new FileTransferItem(Ref(session, "/src/skipped"), FileConflictPolicy.Skip)]), Token);
        Assert.Equal(ServerTaskSubmissionOutcome.Accepted, result.Outcome);
        var call = Assert.Single(host.Calls, IsTransfer);
        var items = call.Parameters!.Value.GetProperty("body").GetProperty("items");
        Assert.Equal(1, items.GetArrayLength());
        Assert.Equal(target, items[0].GetProperty("toWirePath").GetString());
        Assert.False(items[0].TryGetProperty("from", out _));
        var journal = Assert.Single((await services.ListOperationsAsync(session.Account, null, 20, Token)).Items);
        Assert.Single(journal.Intent.Items);
        Assert.Equal(OperationAckKind.TaskAccepted, journal.Acknowledgement!.Kind);
    }

    [Fact]
    public async Task AllSkippedTransferMakesNoResourceRequestOrJournal()
    {
        await using var host = ManagementHost(_ => throw new InvalidOperationException("Skipped transfer must not read metadata."));
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        int before = host.Calls.Count;
        var result = await services.SubmitTransferAsync(new FileTransferRequest(FileTransferAction.Move, Ref(session, "/dst"),
            [new FileTransferItem(Ref(session, "/src/item"), FileConflictPolicy.Skip)]), Token);
        Assert.Equal(ServerTaskSubmissionOutcome.NotSubmitted, result.Outcome);
        Assert.Equal(before, host.Calls.Count);
        Assert.Empty((await services.ListOperationsAsync(session.Account, null, 20, Token)).Items);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task MissingPermissionOrOpaqueCapabilityPreventsTransfer(bool capability)
    {
        await using var host = ManagementHost(wire => CoreReadHost.Metadata(wire, directory: wire == "/dst"));
        host.WireOperations = !capability;
        host.Modify = capability;
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var result = await services.SubmitTransferAsync(new FileTransferRequest(FileTransferAction.Copy, Ref(session, "/dst"),
            [new FileTransferItem(Ref(session, "/src/%FF"), FileConflictPolicy.Replace)]), Token);
        Assert.Equal(ServerTaskSubmissionOutcome.Rejected, result.Outcome);
        Assert.Equal(capability ? ErrorCode.CapabilityMissing : ErrorCode.PermissionDenied, result.Error!.Code);
        Assert.DoesNotContain(host.Calls, IsTransfer);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task HostUnknownForKnownReadOnlyPostAllowsAUserRequestedNewPreviewWithoutExecution(bool dryRun)
    {
        int pendingFailure = 1;
        await using var host = ManagementHost(wire => CoreReadHost.Metadata(wire));
        host.ErrorForCall = call => call.Operation == "request" &&
            CoreReadHost.Endpoint(call) == (dryRun ? "/api/resources/batch-rename" : "/api/resources/batch") &&
            Interlocked.Exchange(ref pendingFailure, 0) == 1 ? new HostError("OutcomeUnknown", "fixture", false) : null;
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var draft = Draft(session);
        var error = await Assert.ThrowsAsync<FilewayException>(() => services.PreviewBatchRenameAsync(draft, Token));
        Assert.Equal(ErrorCode.Unknown, error.Error.Code);
        Assert.True(error.Error.Retryable);
        Assert.Contains("未执行文件操作", error.Error.Message);
        var preview = await services.PreviewBatchRenameAsync(draft, Token);
        Assert.True(preview.Valid);
        Assert.DoesNotContain(host.Calls, IsRenameExecute);
        Assert.Empty((await services.ListOperationsAsync(session.Account, null, 20, Token)).Items);
    }

    private static CoreReadHost ManagementHost(Func<string, object?> metadata) => new()
    {
        Dispatch = (call, _) =>
        {
            string endpoint = CoreReadHost.Endpoint(call);
            var parameters = call.Parameters!.Value;
            if (endpoint == "/api/resources/batch")
                return Task.FromResult(CoreReadHost.Http(parameters.GetProperty("body").GetProperty("wirePaths").EnumerateArray().Select(path =>
                {
                    string wire = path.GetString()!;
                    object? item = metadata(wire);
                    return new { wirePath = wire, path = "/display", status = item is null ? 404 : 200, item };
                }).ToArray()));
            if (endpoint == "/api/resources/batch-rename") return Task.FromResult(RenameReply(call));
            if (endpoint == "/api/resources/transfer") return Task.FromResult(CoreReadHost.Http(CoreReadHost.TaskRow(
                type: "file." + parameters.GetProperty("body").GetProperty("action").GetString()), 202));
            if (parameters.GetProperty("method").GetString() != "GET") return Task.FromResult(CoreReadHost.Http(new { }));
            string requested = endpoint["/api/resources".Length..].Split('?')[0];
            object? value = metadata(requested);
            return Task.FromResult(CoreReadHost.Http(value, value is null ? 404 : 200));
        },
    };

    private static object RenameReply(HostCall call, bool valid = true)
    {
        var body = call.Parameters!.Value.GetProperty("body");
        bool dryRun = body.GetProperty("dryRun").GetBoolean();
        var items = body.GetProperty("items").EnumerateArray().Select(item =>
        {
            bool wire = item.TryGetProperty("fromWirePath", out var from);
            return new Dictionary<string, object?>
            {
                [wire ? "fromWirePath" : "from"] = wire ? from.GetString() : item.GetProperty("from").GetString(),
                [wire ? "toWirePath" : "to"] = item.GetProperty(wire ? "toWirePath" : "to").GetString(),
                ["status"] = valid ? dryRun ? "ready" : "completed" : "error",
            };
        }).ToArray();
        return CoreReadHost.Http(new { valid, executed = valid && !dryRun, items });
    }

    private static BatchRenameDraft Draft(ConnectionSession session) => new(Guid.NewGuid(), [new BatchRenameItem(Ref(session, "/dir/A"), "B")]);
    private static RemoteResourceRef Ref(ConnectionSession session, string wire) => new(session.Account, wire, wire.Split('/')[^1], wire);
    private static bool IsRenameExecute(HostCall call) => call.Operation == "request" && CoreReadHost.Endpoint(call) == "/api/resources/batch-rename" &&
        !call.Parameters!.Value.GetProperty("body").GetProperty("dryRun").GetBoolean();
    private static bool IsTransfer(HostCall call) => call.Operation == "request" && CoreReadHost.Endpoint(call) == "/api/resources/transfer";
    private static bool IsFileWrite(HostCall call) => call.Operation == "request" && call.Parameters!.Value.GetProperty("method").GetString() is ("POST" or "PATCH") &&
        CoreReadHost.Endpoint(call).StartsWith("/api/resources/", StringComparison.Ordinal) &&
        CoreReadHost.Endpoint(call) is not ("/api/resources/batch" or "/api/resources/batch-rename" or "/api/resources/transfer");
}

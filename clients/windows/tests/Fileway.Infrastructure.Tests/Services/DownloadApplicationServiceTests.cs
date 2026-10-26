using System.Collections.Concurrent;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Services;

namespace Fileway.Infrastructure.Tests.Services;

public sealed partial class DownloadApplicationServiceTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(10);
    private static TaskCompletionSource Signal() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    [Fact]
    public async Task PrepareIsReadOnlyUsesActiveDirectoryAndDownloadPermissionWithoutCreate()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();

        var preview = await fixture.Downloads.PrepareAsync(fixture.Context, fixture.Source, Token);

        Assert.Equal(@"C:\active-downloads\literal%2F #é.bin", preview.DestinationDisplayPath);
        Assert.Equal(8, preview.TotalBytes);
        Assert.Equal(fixture.Source.WirePath, preview.Source.WirePath);
        Assert.Equal(fixture.Context, preview.Context);
        Assert.NotEqual(Guid.Empty, preview.Draft.Id);
        Assert.Equal(1, fixture.Sources.Preparations);
        Assert.Empty(fixture.Runner.Runs);
        Assert.Empty((await fixture.Store.Inner.ListTransfersAsync(fixture.Context.Account, null, 50, Token)).Items);
        Assert.DoesNotContain(@"C:\active-downloads", preview.ToString(), StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("..")]
    [InlineData("../escape.bin")]
    [InlineData(@"..\escape.bin")]
    [InlineData(@"C:\escape.bin")]
    [InlineData("file.bin:stream")]
    [InlineData("CON")]
    [InlineData("NUL.txt")]
    [InlineData("bad:name.bin")]
    [InlineData("trailing.")]
    [InlineData("trailing ")]
    [InlineData("line\nfile.bin")]
    public async Task ExplicitInvalidLeafFailsBeforeReadingMetadataOrCreatingWork(string? localName)
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();

        await FailureAsync(ErrorCode.InvalidRequest, () => fixture.Downloads.PrepareAsAsync(fixture.Context, fixture.Source, localName!, Token));

        Assert.Equal(0, fixture.Sources.Preparations);
        Assert.Equal(0, fixture.Store.Creates);
        Assert.Empty(fixture.Runner.Runs);
        Assert.Empty((await fixture.Store.Inner.ListTransfersAsync(fixture.Context.Account, null, 50, Token)).Items);
    }

    [Fact]
    public async Task ExplicitMalformedUnicodeOrOverlongLeafFailsBeforeReadingMetadataOrCreatingWork()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        // Keep invalid UTF-16 out of theory attributes and their test-case serialization.
        string[] invalidNames = [new string('\ud800', 1), new string('\udc00', 1), new string('x', 256)];

        foreach (var localName in invalidNames)
            await FailureAsync(ErrorCode.InvalidRequest, () => fixture.Downloads.PrepareAsAsync(fixture.Context, fixture.Source, localName, Token));

        Assert.Equal(0, fixture.Sources.Preparations);
        Assert.Equal(0, fixture.Store.Creates);
        Assert.Empty(fixture.Runner.Runs);
        Assert.Empty((await fixture.Store.Inner.ListTransfersAsync(fixture.Context.Account, null, 50, Token)).Items);
    }

    [Fact]
    public async Task ExplicitUnicodeLeafPreservesSourceAndDurableOrderingWithoutDuplicateExecutionOrCreatePermission()
    {
        await using var fixture = new Fixture();
        var session = await fixture.ConnectAsync();
        Assert.True(session.Permissions.Download);
        Assert.False(session.Permissions.Create);
        var source = fixture.Source;
        const string localName = "保存 literal%2F #é.bin";
        var preview = await fixture.Downloads.PrepareAsAsync(fixture.Context, source, localName, Token);

        Assert.Equal(localName, preview.Name);
        Assert.Equal(@"C:\active-downloads\保存 literal%2F #é.bin", preview.DestinationDisplayPath);
        Assert.Equal(source, preview.Source);
        Assert.Equal(source, fixture.Sources.LastPrepared);
        Assert.Equal(fixture.Context, preview.Context);
        Assert.Equal(8, preview.TotalBytes);
        Assert.NotEqual(Guid.Empty, preview.Draft.Id);
        Assert.Equal(1, fixture.Sources.Preparations);
        Assert.Empty(fixture.Runner.Runs);
        Assert.Empty((await fixture.Store.Inner.ListTransfersAsync(fixture.Context.Account, null, 50, Token)).Items);
        var entered = Signal(); var release = fixture.Gate();
        fixture.Store.BeforeCreate = async (plan, cancellationToken) =>
        {
            Assert.Equal(source, plan.Remote);
            Assert.Equal(preview.DestinationDisplayPath, plan.LocalPath);
            entered.TrySetResult();
            await release.Task.WaitAsync(cancellationToken);
        };

        var submitting = fixture.Downloads.SubmitAsync(preview, Token);
        await entered.Task.WaitAsync(Timeout, Token);
        Assert.Empty(fixture.Runner.Runs);
        Assert.Equal(0, fixture.Store.Creates);
        release.TrySetResult();
        var submitted = await submitting.WaitAsync(Timeout, Token);
        await fixture.Runner.Started.Task.WaitAsync(Timeout, Token);
        var record = await fixture.ReadAsync(submitted.Snapshot.Task);
        Assert.Equal(preview.Draft.Id, record.Plan.Reference.Id);
        Assert.Equal(TransferDirection.Download, record.Plan.Direction);
        Assert.Equal(FileConflictPolicy.Fail, record.Plan.Conflict);
        Assert.False(record.Plan.AutomaticResume);
        Assert.Equal(preview.DestinationDisplayPath, record.Plan.LocalPath);
        Assert.Equal(source, record.Plan.Remote);
        Assert.Equal(source, submitted.Source);
        Assert.Equal(source.DisplayName, submitted.Name);
        Assert.Equal(preview.DestinationDisplayPath, submitted.DestinationDisplayPath);

        var duplicate = await fixture.Downloads.SubmitAsync(preview with { }, Token);
        Assert.Equal(submitted.Snapshot.Task, duplicate.Snapshot.Task);
        Assert.Equal(source.DisplayName, duplicate.Name);
        Assert.Equal(source, duplicate.Source);
        Assert.Single(fixture.Runner.Runs);
        Assert.Equal(1, fixture.Store.Creates);
        Assert.Equal(1, fixture.Sources.Preparations);

        fixture.Connection.EmitSession(session with { Permissions = session.Permissions with { Download = false } });
        await FailureAsync(ErrorCode.PermissionDenied, () => fixture.Downloads.PrepareAsAsync(fixture.Context, source, localName, Token));
        await FailureAsync(ErrorCode.PermissionDenied, () => fixture.Downloads.SubmitAsync(preview, Token));
        Assert.Equal(1, fixture.Sources.Preparations);
        Assert.Equal(1, fixture.Store.Creates);
        Assert.Single(fixture.Runner.Runs);
    }

    [Theory]
    [InlineData("NUL.txt")]
    [InlineData("remote:name.bin")]
    public async Task ExplicitSafeLeafCanMapUnsafeRemoteMetadataWithoutChangingWireIdentity(string remoteName)
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        fixture.Sources.Name = remoteName;
        var metadataPath = "/visible/" + remoteName;
        fixture.Sources.DisplayPath = metadataPath;
        var source = fixture.Source;
        const string localName = "safe copy.bin";
        await FailureAsync(ErrorCode.InvalidRequest, () => fixture.PreviewAsync());

        var preview = await fixture.Downloads.PrepareAsAsync(fixture.Context, source, localName, Token);

        var metadataSource = source with { DisplayName = remoteName, DisplayPath = metadataPath };
        Assert.Equal(localName, preview.Name);
        Assert.Equal(@"C:\active-downloads\safe copy.bin", preview.DestinationDisplayPath);
        Assert.Equal(metadataSource, preview.Source);
        Assert.Equal(source.WirePath, preview.Source.WirePath);
        Assert.Equal(source, fixture.Sources.LastPrepared);
        Assert.Equal(2, fixture.Sources.Preparations);
        Assert.Equal(0, fixture.Store.Creates);
        Assert.Empty(fixture.Runner.Runs);
        var submitted = await fixture.Downloads.SubmitAsync(preview, Token);
        await fixture.Runner.Started.Task.WaitAsync(Timeout, Token);
        var record = await fixture.ReadAsync(submitted.Snapshot.Task);
        Assert.Equal(metadataSource, record.Plan.Remote);
        Assert.Equal(preview.DestinationDisplayPath, record.Plan.LocalPath);
        Assert.Equal(FileConflictPolicy.Fail, record.Plan.Conflict);
        Assert.False(record.Plan.AutomaticResume);
        Assert.Equal(metadataSource, submitted.Source);
        Assert.Equal(remoteName, submitted.Name);
        Assert.Single(fixture.Runner.Runs);
    }

    [Theory]
    [InlineData("localName")]
    [InlineData("originalName")]
    [InlineData("source")]
    [InlineData("destination")]
    public async Task ExplicitNamedPreviewCannotBeForgedOrRetargeted(string change)
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var preview = await fixture.Downloads.PrepareAsAsync(fixture.Context, fixture.Source, "chosen.bin", Token);
        preview = change switch
        {
            "localName" => preview with { Name = "other.bin" },
            "originalName" => preview with { Source = preview.Source with { DisplayName = "different remote.bin" } },
            "source" => preview with { Source = preview.Source with { WirePath = "/another.bin" } },
            _ => preview with { DestinationDisplayPath = @"C:\other\chosen.bin" },
        };

        await FailureAsync(ErrorCode.InvalidRequest, () => fixture.Downloads.SubmitAsync(preview, Token));

        Assert.Equal(1, fixture.Sources.Preparations);
        Assert.Equal(0, fixture.Store.Creates);
        Assert.Empty(fixture.Runner.Runs);
        Assert.Empty((await fixture.Store.Inner.ListTransfersAsync(fixture.Context.Account, null, 50, Token)).Items);
    }

    [Fact]
    public async Task ExplicitNamedPreviewFencesEffectiveDirectoryChangesAndIgnoresPendingDirectory()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var preview = await fixture.Downloads.PrepareAsAsync(fixture.Context, fixture.Source, "chosen.bin", Token);
        fixture.Settings.Active = @"C:\changed-active";

        await FailureAsync(ErrorCode.SourceChanged, () => fixture.Downloads.SubmitAsync(preview, Token));

        Assert.Equal(0, fixture.Store.Creates);
        Assert.Empty(fixture.Runner.Runs);
        fixture.Settings.Active = @"C:\active-downloads";
        fixture.Settings.Pending = @"C:\another-pending";
        var submitted = await fixture.Downloads.SubmitAsync(preview, Token);
        await fixture.Runner.Started.Task.WaitAsync(Timeout, Token);
        var record = await fixture.ReadAsync(submitted.Snapshot.Task);
        Assert.Equal(@"C:\active-downloads\chosen.bin", submitted.DestinationDisplayPath);
        Assert.Equal(preview.DestinationDisplayPath, record.Plan.LocalPath);
        Assert.Equal("chosen.bin", preview.Name);
        Assert.Equal(fixture.Source.DisplayName, submitted.Name);
        Assert.Equal(1, fixture.Sources.Preparations);
        Assert.Equal(1, fixture.Store.Creates);
        Assert.Single(fixture.Runner.Runs);
    }

    [Theory]
    [InlineData("account")]
    [InlineData("session")]
    public async Task ExplicitNamedPreviewCannotSubmitAfterActualAccountOrSessionReplacement(string change)
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var oldContext = fixture.Context;
        var source = fixture.Source;
        var preview = await fixture.Downloads.PrepareAsAsync(oldContext, source, "chosen.bin", Token);
        if (change == "account") fixture.Connection.ServerUserId = "foreign";

        await fixture.ConnectAsync().WaitAsync(Timeout, Token);

        if (change == "account") Assert.NotEqual(oldContext.Account, fixture.Context.Account);
        else Assert.Equal(oldContext.Account, fixture.Context.Account);
        Assert.NotEqual(oldContext.SessionId, fixture.Context.SessionId);
        await FailureAsync(ErrorCode.SourceChanged, () => fixture.Downloads.SubmitAsync(preview, Token));
        await FailureAsync(ErrorCode.SourceChanged, () => fixture.Downloads.PrepareAsAsync(oldContext, source, "chosen.bin", Token));
        Assert.Equal(1, fixture.Sources.Preparations);
        Assert.Equal(0, fixture.Store.Creates);
        Assert.Empty(fixture.Runner.Runs);
        Assert.Empty((await fixture.Store.Inner.ListTransfersAsync(oldContext.Account, null, 50, Token)).Items);
        Assert.Empty((await fixture.Store.Inner.ListTransfersAsync(fixture.Context.Account, null, 50, Token)).Items);
    }

    [Fact]
    public async Task SubmissionPersistsFixedDraftBeforeExecutionAndDuplicateNeverRestarts()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var preview = await fixture.PreviewAsync();
        var entered = Signal(); var release = fixture.Gate();
        fixture.Store.BeforeCreate = async (_, cancellationToken) => { entered.TrySetResult(); await release.Task.WaitAsync(cancellationToken); };
        var submitting = fixture.Downloads.SubmitAsync(preview, Token);
        await entered.Task.WaitAsync(Timeout, Token);
        Assert.Empty(fixture.Runner.Runs);
        release.TrySetResult();
        var submitted = await submitting.WaitAsync(Timeout, Token);
        await fixture.Runner.Started.Task.WaitAsync(Timeout, Token);
        var record = await fixture.ReadAsync(submitted.Snapshot.Task);
        Assert.Equal(preview.Draft.Id, record.Plan.Reference.Id);
        Assert.Equal(preview.DestinationDisplayPath, record.Plan.LocalPath);
        Assert.Equal(TransferDirection.Download, record.Plan.Direction);
        Assert.Equal(FileConflictPolicy.Fail, record.Plan.Conflict);
        Assert.False(record.Plan.AutomaticResume);
        Assert.Equal(fixture.Source.WirePath, record.Plan.Remote.WirePath);

        var duplicate = await fixture.Downloads.SubmitAsync(preview, Token);
        Assert.Equal(submitted.Snapshot.Task, duplicate.Snapshot.Task);
        Assert.Single(fixture.Runner.Runs);
        Assert.Equal(1, fixture.Store.Creates);
    }

    [Theory]
    [InlineData("name")]
    [InlineData("destination")]
    [InlineData("size")]
    [InlineData("source")]
    [InlineData("draft")]
    public async Task ForgedOrRetargetedPreviewCannotCreateOrRun(string change)
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var preview = await fixture.PreviewAsync();
        preview = change switch
        {
            "name" => preview with { Name = "other.bin" },
            "destination" => preview with { DestinationDisplayPath = @"C:\other\other.bin" },
            "size" => preview with { TotalBytes = 999 },
            "source" => preview with { Source = preview.Source with { WirePath = "/another.bin" } },
            _ => preview with { Draft = new(Guid.NewGuid()) },
        };
        await FailureAsync(ErrorCode.InvalidRequest, () => fixture.Downloads.SubmitAsync(preview, Token));
        Assert.Equal(0, fixture.Store.Creates);
        Assert.Empty(fixture.Runner.Runs);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(2)]
    public async Task EveryContextDimensionFencesPreviewSubmitAndHistory(int dimension)
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var preview = await fixture.PreviewAsync();
        var context = dimension switch
        {
            0 => fixture.Context with { Account = fixture.Context.Account with { ServerUserId = "foreign" } },
            1 => fixture.Context with { SessionId = Guid.NewGuid() },
            _ => fixture.Context with { ActivationId = Guid.NewGuid() },
        };
        await FailureAsync(ErrorCode.SourceChanged, () => fixture.Downloads.PrepareAsync(context, fixture.Source, Token));
        await FailureAsync(ErrorCode.SourceChanged, () => fixture.Downloads.SubmitAsync(preview with { Context = context }, Token));
        await FailureAsync(ErrorCode.SourceChanged, () => fixture.Downloads.ListAsync(context, null, 50, Token));
        Assert.Empty(fixture.Runner.Runs);
    }

    [Theory]
    [InlineData("..")]
    [InlineData("../escape.bin")]
    [InlineData(@"..\escape.bin")]
    [InlineData(@"C:\escape.bin")]
    [InlineData("file.bin:stream")]
    [InlineData("NUL.txt")]
    [InlineData("trailing. ")]
    [InlineData("line\nfile.bin")]
    public async Task UnsafeRemoteDisplayLeafCannotEscapeOrAliasActiveDestination(string name)
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        fixture.Sources.Name = name;
        await FailureAsync(ErrorCode.InvalidRequest, () => fixture.PreviewAsync());
        Assert.Equal(0, fixture.Store.Creates);
        Assert.Empty(fixture.Runner.Runs);
    }

    [Fact]
    public async Task EffectiveDirectoryChangesRejectOldPreviewButPendingChangesDoNotRetargetIt()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var preview = await fixture.PreviewAsync();
        fixture.Settings.Active = @"C:\changed-active";
        await FailureAsync(ErrorCode.SourceChanged, () => fixture.Downloads.SubmitAsync(preview, Token));
        Assert.Equal(0, fixture.Store.Creates);
        fixture.Settings.Active = @"C:\active-downloads";
        fixture.Settings.Pending = @"C:\another-pending";
        var item = await fixture.Downloads.SubmitAsync(preview, Token);
        Assert.Equal(preview.DestinationDisplayPath, item.DestinationDisplayPath);
        Assert.DoesNotContain("pending", item.DestinationDisplayPath, StringComparison.Ordinal);
    }

    [Fact]
    public async Task FailedPersistenceAndPermissionLostWhilePersistingNeverStartRunner()
    {
        await using var fixture = new Fixture();
        var session = await fixture.ConnectAsync();
        var preview = await fixture.PreviewAsync();
        fixture.Store.BeforeCreate = (_, _) => throw new IOException("private database path");
        await FailureAsync(ErrorCode.LocalStorageUnavailable, () => fixture.Downloads.SubmitAsync(preview, Token));
        Assert.Empty(fixture.Runner.Runs);
        fixture.Store.BeforeCreate = (_, _) =>
        {
            fixture.Connection.EmitSession(session with { Permissions = session.Permissions with { Download = false } });
            return Task.CompletedTask;
        };
        await FailureAsync(ErrorCode.PermissionDenied, () => fixture.Downloads.SubmitAsync(preview, Token));
        Assert.Empty(fixture.Runner.Runs);
        Assert.Single((await fixture.Downloads.ListAsync(fixture.Context, null, 50, Token)).Items);
    }

    [Fact]
    public async Task OneWriterExecutesConfirmedDurableDownloadsInFifoOrder()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var first = await fixture.PreviewAsync(); var second = await fixture.PreviewAsync();
        var secondStarted = Signal();
        fixture.Runner.BeforeRun = (task, _) =>
        {
            if (task.Id == second.Draft.Id) secondStarted.TrySetResult();
            return Task.CompletedTask;
        };
        var firstItem = await fixture.Downloads.SubmitAsync(first, Token);
        await fixture.Runner.Started.Task.WaitAsync(Timeout, Token);
        var secondItem = await fixture.Downloads.SubmitAsync(second, Token);
        Assert.Equal(2, fixture.Store.Creates);
        Assert.Equal(TransferPhase.Queued, (await fixture.ReadAsync(secondItem.Snapshot.Task)).Checkpoint.Phase);
        Assert.Single(fixture.Runner.Runs);
        Assert.False(secondStarted.Task.IsCompleted);
        await fixture.Runner.CompleteAsync(firstItem.Snapshot.Task, Token);
        await secondStarted.Task.WaitAsync(Timeout, Token);
        Assert.Equal([firstItem.Snapshot.Task, secondItem.Snapshot.Task], fixture.Runner.Runs.ToArray());
        await fixture.Runner.CompleteAsync(secondItem.Snapshot.Task, Token);
    }

    [Fact]
    public async Task HistoryIsAccountBoundReadOnlyAndFiltersUploads()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var preview = await fixture.PreviewAsync();
        var download = await fixture.Store.Inner.CreateTransferAsync(new(new(fixture.Context.Account, preview.Draft.Id), TransferDirection.Download,
            preview.Source, preview.DestinationDisplayPath, AutomaticResume: true), Token);
        await fixture.Store.Inner.CreateTransferAsync(download.Plan with { Reference = new(fixture.Context.Account, Guid.NewGuid()), Direction = TransferDirection.Upload }, Token);

        var item = Assert.Single((await fixture.Downloads.ListAsync(fixture.Context, null, 50, Token)).Items);

        Assert.Equal(download.Plan.Reference, item.Snapshot.Task);
        Assert.Equal(DownloadStatus.NotStarted, item.Status);
        Assert.Null(item.Progress);
        Assert.False(item.Snapshot.CanReconcileLocally);
        Assert.Empty(fixture.Runner.Runs);
        Assert.Equal(1, fixture.Sources.Preparations);
    }

    private static async Task FailureAsync(ErrorCode code, Func<Task> action)
    {
        var error = await Assert.ThrowsAsync<FilewayException>(action);
        Assert.Equal(code, error.Error.Code);
        Assert.DoesNotContain("private", error.Message, StringComparison.Ordinal);
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly List<TaskCompletionSource> _gates = [];
        internal ObservedStore Store { get; } = new();
        internal FakeConnection Connection { get; }
        internal FakeSources Sources { get; } = new();
        internal FakeSettings Settings { get; } = new();
        internal FakeUpload Upload { get; } = new();
        internal FakeDownload Runner { get; }
        internal UploadApplicationService Bridge { get; }
        internal IDownloadService Downloads => Bridge;
        internal DownloadContext Context => Assert.IsType<DownloadContext>(Downloads.CurrentContext);
        internal RemoteResourceRef Source => new(Connection.Account, "/opaque%252F/literal%252F%20%23%C3%A9.bin", "literal%2F #é.bin", "/visible/literal%2F #é.bin");
        internal Fixture(IDownloadSourceProvider? sources = null, IConnectionService? connection = null, IConnectionCatalog? catalog = null)
        {
            Connection = new(Store.Inner);
            Runner = new(Store.Inner);
            Bridge = new(connection ?? Connection, catalog ?? Connection, Sources, Store, Upload,
                downloads: new(Runner, sources ?? Sources, Settings));
        }
        internal TaskCompletionSource Gate() { var gate = Signal(); _gates.Add(gate); return gate; }
        internal Task<ConnectionSession> ConnectAsync() => Bridge.ConnectAsync(Connection.Profile, new(AuthenticationMode.NoAuthentication), Token);
        internal Task<DownloadPreview> PreviewAsync() => Downloads.PrepareAsync(Context, Source, Token);
        internal async Task<TransferTaskRecord> ReadAsync(TransferTaskRef task) => Assert.IsType<TransferTaskRecord>(await Store.Inner.GetTransferAsync(task, Token));
        public async ValueTask DisposeAsync()
        {
            foreach (var gate in _gates) gate.TrySetResult();
            Runner.StopRelease.TrySetResult(); Upload.StopRelease.TrySetResult();
            await Bridge.DisposeAsync().AsTask().WaitAsync(Timeout, Token);
        }
    }

    private sealed class ObservedStore : ITransferTaskStore
    {
        internal InMemoryProfileStore Inner { get; } = new();
        internal Func<TransferPlan, CancellationToken, Task>? BeforeCreate { get; set; }
        internal int Creates;
        public bool IsPersistent => false;
        public async Task<TransferTaskRecord> CreateTransferAsync(TransferPlan plan, CancellationToken cancellationToken)
        { if (BeforeCreate is { } before) await before(plan, cancellationToken); var record = await Inner.CreateTransferAsync(plan, cancellationToken); Creates++; return record; }
        public Task<TransferTaskRecord?> GetTransferAsync(TransferTaskRef task, CancellationToken cancellationToken) => Inner.GetTransferAsync(task, cancellationToken);
        public Task<TransferTaskPage> ListTransfersAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken) => Inner.ListTransfersAsync(account, cursor, limit, cancellationToken);
        public Task<bool> HasProtectedTransfersAsync(AccountKey account, CancellationToken cancellationToken) => Inner.HasProtectedTransfersAsync(account, cancellationToken);
        public Task<TransferTaskRecord> SetTransferIntentAsync(TransferTaskRef task, long expectedRevision, TransferIntent intent, CancellationToken cancellationToken) => Inner.SetTransferIntentAsync(task, expectedRevision, intent, cancellationToken);
        public Task<TransferTaskRecord> ClaimTransferAsync(TransferTaskRef task, long expectedRevision, Guid runId, bool recovery, CancellationToken cancellationToken) => Inner.ClaimTransferAsync(task, expectedRevision, runId, recovery, cancellationToken);
        public Task<TransferTaskRecord> SaveTransferCheckpointAsync(TransferRunToken run, long expectedRevision, TransferCheckpoint checkpoint, bool releaseRun, CancellationToken cancellationToken) => Inner.SaveTransferCheckpointAsync(run, expectedRevision, checkpoint, releaseRun, cancellationToken);
    }

    private sealed class FakeConnection(InMemoryProfileStore store) : IConnectionService, IConnectionCatalog, IAsyncDisposable
    {
        internal ConnectionProfile Profile { get; } = new(Guid.NewGuid(), Guid.NewGuid(), "download fixture", new("https://fixture.invalid/"), NetworkMode.SystemNetwork, new(ProxyMode.Direct));
        internal string ServerUserId { get; set; } = "7";
        internal AccountKey Account => new(Profile.Id, Profile.SourceRevision, ServerUserId);
        internal ConnectionSession? Active { get; set; }
        internal ConcurrentQueue<string> Calls { get; } = new();
        internal int Disposals;
        public event EventHandler<ConnectionSession>? SessionUpdated;
        public event EventHandler<ConnectionIssue>? ConnectionIssue;
        internal void EmitSession(ConnectionSession session) { Active = session; SessionUpdated?.Invoke(this, session); }
        internal void Invalidate() { var session = Active!; ConnectionIssue?.Invoke(this, new(session.Account, session.Id, new(ErrorCode.AuthenticationExpired, "private expired token"), true)); }
        public async Task<ConnectionSession> ConnectAsync(ConnectionProfile profile, LoginInput login, CancellationToken cancellationToken)
        {
            Calls.Enqueue("connect");
            await store.SaveAsync(new(Profile, AuthenticationMode.NoAuthentication, "fixture", ServerUserId, false, DateTimeOffset.UtcNow), cancellationToken);
            return Active = new(Guid.NewGuid(), Account, "fixture", new(true, false, false, false, false, false), new());
        }
        public Task<ConnectionSession> RestoreAsync(Guid profileId, CancellationToken cancellationToken) => ConnectAsync(Profile, new(AuthenticationMode.NoAuthentication), cancellationToken);
        public Task DisconnectAsync(AccountKey account, CancellationToken cancellationToken) { Calls.Enqueue("disconnect"); Active = null; return Task.CompletedTask; }
        public Task LogoutAsync(AccountKey account, CancellationToken cancellationToken) { Calls.Enqueue("logout"); Active = null; return Task.CompletedTask; }
        public async Task ForgetAsync(Guid profileId, CancellationToken cancellationToken) { Calls.Enqueue("forget"); await store.ForgetAsync(Account, cancellationToken); Active = null; }
        public Task<IReadOnlyList<SavedConnection>> ListAsync(CancellationToken cancellationToken) => store.ListAsync(cancellationToken);
        public Task<SavedConnection?> GetAsync(Guid profileId, CancellationToken cancellationToken) => store.GetAsync(profileId, cancellationToken);
        public ValueTask DisposeAsync() { Disposals++; return ValueTask.CompletedTask; }
    }

    private sealed class FakeSettings : ILocalStorageSettingsService
    {
        internal string Active { get; set; } = @"C:\active-downloads";
        internal string Pending { get; set; } = @"C:\pending-downloads";
        public Task<StorageSettingsState> ReadAsync(CancellationToken cancellationToken) => Task.FromResult(new StorageSettingsState(
            new(@"C:\cache", Active), new(@"C:\cache", Pending), Active != Pending));
        public Task<StorageSettingsState> SaveAsync(ClientStorageSettings settings, CancellationToken cancellationToken) => throw new NotSupportedException();
    }

    private sealed class FakeSources : IDownloadSourceProvider, IFileRepository
    {
        internal string? Name { get; set; }
        internal string? DisplayPath { get; set; }
        internal RemoteResourceRef? LastPrepared { get; private set; }
        internal int Preparations;
        public Task<RemoteFile> PrepareDownloadAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
        {
            Preparations++;
            LastPrepared = resource;
            return Task.FromResult(new RemoteFile(resource with
                { DisplayName = Name ?? resource.DisplayName, DisplayPath = DisplayPath ?? resource.DisplayPath }, false, 8, null));
        }
        public Task<IDownloadLease> AcquireDownloadAsync(DownloadLeaseRequest request, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileChecksumResult> CalculateDownloadChecksumAsync(DownloadLeaseRequest request, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<RemoteFile> GetAsync(RemoteResourceRef resource, CancellationToken cancellationToken) => PrepareDownloadAsync(resource, cancellationToken);
        public Task<RemoteResourceRef> GetRootAsync(ConnectionSession session, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DirectoryPage> ListAsync(RemoteResourceRef directory, DirectoryQuery query, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileOperationResult> CreateDirectoryAsync(RemoteResourceRef parent, string name, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileOperationResult> RenameAsync(RemoteResourceRef resource, string name, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileOperationResult> TrashAsync(IReadOnlyList<RemoteResourceRef> resources, CancellationToken cancellationToken) => throw new NotSupportedException();
    }

    private sealed class FakeUpload : IUploadCoordinator
    {
        internal TaskCompletionSource StopEntered { get; } = Signal();
        internal TaskCompletionSource StopRelease { get; } = Signal();
        internal bool HoldStop { get; set; }
        internal int Disposals;
        public event EventHandler<UploadQueueUpdate>? TaskChanged { add { } remove { } }
        public Task<UploadActivationToken> ActivateAsync(ConnectionSession session, CancellationToken cancellationToken) => Task.FromResult(new UploadActivationToken(session.Account, session.Id, Guid.NewGuid()));
        public Task<UploadTaskSnapshot> EnqueueAsync(UploadActivationToken activation, TransferTaskRef task, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<UploadTaskSnapshot> ResumeAsync(UploadActivationToken activation, TransferTaskRef task, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<UploadTaskSnapshot> PauseAsync(UploadActivationToken activation, TransferTaskRef task, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<UploadTaskSnapshot> CancelAsync(UploadActivationToken activation, TransferTaskRef task, CancellationToken cancellationToken) => throw new NotSupportedException();
        public async Task StopAndDrainAsync(UploadActivationToken activation, CancellationToken cancellationToken)
        { StopEntered.TrySetResult(); if (HoldStop) await StopRelease.Task.WaitAsync(cancellationToken); }
        public ValueTask DisposeAsync() { Disposals++; return ValueTask.CompletedTask; }
    }

    private sealed class FakeDownload(InMemoryProfileStore store) : IFileDownloadRunner
    {
        internal ConcurrentQueue<TransferTaskRef> Runs { get; } = new();
        internal TaskCompletionSource Started { get; } = Signal();
        internal TaskCompletionSource StopEntered { get; } = Signal();
        internal TaskCompletionSource StopRelease { get; } = Signal();
        internal bool HoldStop { get; set; }
        internal bool CompleteBeforeReturn { get; set; }
        internal int Disposals;
        internal int Reconciles;
        internal int Cancels { get; private set; }
        internal Func<TransferTaskRef, CancellationToken, Task>? BeforeInitialRead { get; set; }
        internal Func<TransferTaskRef, CancellationToken, Task>? AfterCancelIntent { get; set; }
        internal Func<TransferTaskRef, CancellationToken, Task>? BeforeRun { get; set; }
        private readonly ConcurrentDictionary<TransferTaskRef, TaskCompletionSource<TransferTaskRecord>> _active = new();
        public event EventHandler<DownloadTaskProgress>? ProgressChanged;
        internal void Emit(DownloadTaskProgress progress) => ProgressChanged?.Invoke(this, progress);
        public async Task<TransferTaskRecord> RunAsync(TransferTaskRef task, CancellationToken cancellationToken)
        {
            if (BeforeInitialRead is { } beforeRead) await beforeRead(task, cancellationToken);
            var record = Assert.IsType<TransferTaskRecord>(await store.GetTransferAsync(task, cancellationToken));
            Assert.Equal(TransferPhase.Queued, record.Checkpoint.Phase);
            Runs.Enqueue(task);
            record = await store.ClaimTransferAsync(task, record.Revision, Guid.NewGuid(), false, cancellationToken);
            var partial = record.Plan.LocalPath[..(record.Plan.LocalPath.LastIndexOf('\\') + 1)] + "stage-" + task.Id.ToString("N") + ".part";
            record = await store.SaveTransferCheckpointAsync(record.Run!.Value, record.Revision,
                new(TransferPhase.Running, new(8), 0, new(partial, "private-partial-identity")), false, cancellationToken);
            var completion = new TaskCompletionSource<TransferTaskRecord>(TaskCreationOptions.RunContinuationsAsynchronously);
            _active[task] = completion;
            Started.TrySetResult();
            if (BeforeRun is { } before) await before(task, cancellationToken);
            if (CompleteBeforeReturn) await CompleteAsync(task, cancellationToken);
            return await completion.Task;
        }
        internal async Task<TransferTaskRecord> CompleteAsync(TransferTaskRef task, CancellationToken cancellationToken)
        {
            var record = Assert.IsType<TransferTaskRecord>(await store.GetTransferAsync(task, cancellationToken));
            var checkpoint = record.Checkpoint with { Phase = TransferPhase.Verifying, ConfirmedOffset = 8, LocalSha256 = new string('a', 64) };
            record = await store.SaveTransferCheckpointAsync(record.Run!.Value, record.Revision, checkpoint, false, cancellationToken);
            record = await store.SaveTransferCheckpointAsync(record.Run!.Value, record.Revision,
                checkpoint with { Phase = TransferPhase.Publishing, ServerSha256 = checkpoint.LocalSha256, Uncertainty = TransferUncertainty.LocalPublish }, false, cancellationToken);
            record = await store.SaveTransferCheckpointAsync(record.Run!.Value, record.Revision,
                record.Checkpoint with { Phase = TransferPhase.Completed, Uncertainty = TransferUncertainty.None,
                    Files = record.Checkpoint.Files! with { PublishedFileIdentity = "private-final-identity" } }, true, cancellationToken);
            if (_active.TryRemove(task, out var completion)) completion.TrySetResult(record);
            return record;
        }
        public async Task<TransferTaskRecord> CancelAsync(TransferTaskRef task, CancellationToken cancellationToken)
        {
            Cancels++;
            var record = Assert.IsType<TransferTaskRecord>(await store.GetTransferAsync(task, cancellationToken));
            if (record.Checkpoint.Phase is TransferPhase.Completed or TransferPhase.Canceled) return record;
            record = await store.SetTransferIntentAsync(task, record.Revision, TransferIntent.Cancel, cancellationToken);
            if (AfterCancelIntent is { } afterIntent) await afterIntent(task, cancellationToken);
            if (record.Run is null) record = await store.ClaimTransferAsync(task, record.Revision, Guid.NewGuid(), true, cancellationToken);
            record = await store.SaveTransferCheckpointAsync(record.Run!.Value, record.Revision,
                record.Checkpoint with { Phase = TransferPhase.Canceled, Uncertainty = TransferUncertainty.None }, true, cancellationToken);
            if (_active.TryRemove(task, out var completion)) completion.TrySetResult(record);
            return record;
        }
        public async Task<TransferTaskRecord> ReconcileAsync(TransferTaskRef task, CancellationToken cancellationToken)
        { Reconciles++; return Assert.IsType<TransferTaskRecord>(await store.GetTransferAsync(task, cancellationToken)); }
        public async Task StopAndDrainAsync(CancellationToken cancellationToken)
        {
            StopEntered.TrySetResult();
            if (HoldStop) await StopRelease.Task.WaitAsync(cancellationToken);
            foreach (var task in _active.Keys) _ = await CancelAsync(task, CancellationToken.None);
        }
        public async ValueTask DisposeAsync() { Disposals++; await StopAndDrainAsync(CancellationToken.None); }
    }
}

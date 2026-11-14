using Fileway.Core.Contracts;
using Fileway.Infrastructure.Credentials;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Services;
using Fileway.Infrastructure.Tests.Persistence;

namespace Fileway.Infrastructure.Tests.Services;

public sealed class TransferLifecycleGuardTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ProtectedTransferRejectsForgetBeforeCredentialsOrSessionAreRemoved(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        IProfileStore profiles = sqlite ? new SqliteProfileStore(directory.DatabasePath) : new InMemoryProfileStore();
        var tasks = (ITransferTaskStore)profiles;
        var vault = new InMemoryCredentialVault();
        await using var host = new CoreReadHost();
        await using var services = new FilewayClientServices(host, profiles, vault);
        var profile = CoreReadHost.Profile();
        var session = await services.ConnectAsync(profile, CoreReadHost.Login with { KeepSignedIn = true }, Token);
        var queued = await tasks.CreateTransferAsync(Plan(session.Account, directory), Token);
        _ = await tasks.ClaimTransferAsync(queued.Plan.Reference, queued.Revision, Guid.NewGuid(), recovery: false, Token);

        var failure = await Assert.ThrowsAsync<FilewayException>(() => services.ForgetAsync(profile.Id, Token));

        Assert.Equal(ErrorCode.Conflict, failure.Error.Code);
        Assert.NotNull(await vault.ReadTokenAsync(session.Account, Token));
        Assert.True((await profiles.GetAsync(profile.Id, Token))!.KeepSignedIn);
        Assert.Empty(await profiles.ListPendingLogoutsAsync(Token));
        Assert.DoesNotContain(host.Calls, call => call.Operation == "close_session");
        Assert.Equal(session.Account, (await services.GetRootAsync(session, Token)).Account);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task LogoutAllowsLateTransferEvidenceWithoutRevivingCredentialsOrSession(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        IProfileStore profiles = sqlite ? new SqliteProfileStore(directory.DatabasePath) : new InMemoryProfileStore();
        var tasks = (ITransferTaskStore)profiles;
        var vault = new InMemoryCredentialVault();
        await using var host = new CoreReadHost();
        await using var services = new FilewayClientServices(host, profiles, vault);
        var profile = CoreReadHost.Profile();
        var session = await services.ConnectAsync(profile, CoreReadHost.Login with { KeepSignedIn = true }, Token);
        var queued = await tasks.CreateTransferAsync(Plan(session.Account, directory), Token);
        var claimed = await tasks.ClaimTransferAsync(queued.Plan.Reference, queued.Revision, Guid.NewGuid(), recovery: false, Token);
        var run = claimed.Run ?? throw new InvalidOperationException("Expected an owned transfer run.");
        var source = new TransferSourceSnapshot(8, NativeFileIdentity: "fixture-source-identity");
        var running = await tasks.SaveTransferCheckpointAsync(run, claimed.Revision,
            new TransferCheckpoint(TransferPhase.Running, source, 0, Uncertainty: TransferUncertainty.RemoteWrite),
            releaseRun: false, Token);

        await services.LogoutAsync(session.Account, Token);
        var completed = await tasks.SaveTransferCheckpointAsync(run, running.Revision,
            new TransferCheckpoint(TransferPhase.Completed, source, 8, RemotePublished: true), releaseRun: true, Token);

        Assert.Equal(TransferPhase.Completed, completed.Checkpoint.Phase);
        Assert.Null(completed.Run);
        Assert.False(await tasks.HasProtectedTransfersAsync(session.Account, Token));
        Assert.Null(await vault.ReadTokenAsync(session.Account, Token));
        Assert.False((await profiles.GetAsync(profile.Id, Token))!.KeepSignedIn);
        Assert.Empty(await profiles.ListPendingLogoutsAsync(Token));
        var failure = await Assert.ThrowsAsync<FilewayException>(() => services.GetRootAsync(session, Token));
        Assert.Equal(ErrorCode.SourceChanged, failure.Error.Code);
    }

    private static TransferPlan Plan(AccountKey account, StorageTestDirectory directory) => new(new(account, Guid.NewGuid()),
        TransferDirection.Upload, new RemoteResourceRef(account, "/upload.bin", "upload.bin", "/upload.bin"),
        Path.Combine(directory.DirectoryPath, "upload-source.bin"));
}

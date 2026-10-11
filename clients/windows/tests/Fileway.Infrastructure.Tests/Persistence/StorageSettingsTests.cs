using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Services;
using Microsoft.Data.Sqlite;

namespace Fileway.Infrastructure.Tests.Persistence;

public sealed class StorageSettingsTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    private static ClientStorageSettings Settings(StorageTestDirectory directory, long maximum = ClientStorageSettings.DefaultMaximumCacheBytes) =>
        new(Path.Combine(directory.DirectoryPath, "cache"), Path.Combine(directory.DirectoryPath, "downloads"), maximum);

    [Theory]
    [InlineData(0L)]
    [InlineData(5_000_000_000L)]
    [InlineData(1_099_511_627_776L)]
    public async Task CapacityBoundariesSurviveSqliteReopen(long maximum)
    {
        using var directory = new StorageTestDirectory();
        var settings = Settings(directory, maximum);
        await new SqliteProfileStore(directory.DatabasePath).SaveStorageSettingsAsync(settings, Token);
        Assert.Equal(settings, await new SqliteProfileStore(directory.DatabasePath).ReadStorageSettingsAsync(Token));
    }

    [Fact]
    public async Task DefaultsAreFiveDecimalGigabytesAndDoNotCreateDirectoriesOrContactHost()
    {
        using var directory = new StorageTestDirectory();
        var defaults = new ClientStorageSettings(Path.Combine(directory.DirectoryPath, "cache"), Path.Combine(directory.DirectoryPath, "downloads"));
        await using var host = new UnusedHost();
        await using var service = new LocalStorageSettingsService(new InMemoryProfileStore(), defaults, host);

        var state = await service.ReadAsync(Token);

        Assert.Equal(5_000_000_000L, state.Active.MaximumCacheBytes);
        Assert.Equal(defaults, state.Active);
        Assert.Equal(state.Active, state.Pending);
        Assert.False(state.RestartRequired);
        Assert.False(Directory.Exists(defaults.CacheDirectory));
        Assert.False(Directory.Exists(defaults.DownloadDirectory));
    }

    [Theory]
    [InlineData(-1L)]
    [InlineData(1_099_511_627_777L)]
    public async Task InvalidCapacityLeavesDurableAndActiveSettingsUnchanged(long maximum)
    {
        using var directory = new StorageTestDirectory();
        var store = new InMemoryProfileStore();
        var original = Settings(directory);
        await store.SaveStorageSettingsAsync(original, Token);
        await using var host = new UnusedHost();
        await using var service = new LocalStorageSettingsService(store, original, host);

        var error = await Assert.ThrowsAsync<FilewayException>(() => service.SaveAsync(original with { MaximumCacheBytes = maximum }, Token));

        Assert.Equal(ErrorCode.InvalidRequest, error.Error.Code);
        Assert.Equal(original, await store.ReadStorageSettingsAsync(Token));
        var state = await service.ReadAsync(Token);
        Assert.Equal(original, state.Active);
        Assert.Equal(original, state.Pending);
        Assert.False(state.RestartRequired);
    }

    [Theory]
    [InlineData("fileway-cache-v1")]
    [InlineData("FILEWAY-CACHE-V1/child")]
    [InlineData("fileway-cache-v1./child")]
    [InlineData("fileway-cache-v1 /child")]
    public async Task DownloadsCannotEnterManagedCacheThroughAliases(string relativeDownload)
    {
        using var directory = new StorageTestDirectory();
        var original = Settings(directory);
        var store = new InMemoryProfileStore();
        await store.SaveStorageSettingsAsync(original, Token);
        var invalid = original with { DownloadDirectory = Path.Combine(original.CacheDirectory, relativeDownload) };

        var error = await Assert.ThrowsAsync<FilewayException>(() => store.SaveStorageSettingsAsync(invalid, Token));

        Assert.Equal(ErrorCode.InvalidRequest, error.Error.Code);
        Assert.Equal(original, await store.ReadStorageSettingsAsync(Token));
    }

    [Fact]
    public async Task SharedParentAndPoolNamePrefixRemainValidDownloadLocations()
    {
        using var directory = new StorageTestDirectory();
        var store = new InMemoryProfileStore();
        var settings = Settings(directory) with { DownloadDirectory = Settings(directory).CacheDirectory };
        await store.SaveStorageSettingsAsync(settings, Token);
        Assert.Equal(settings, await store.ReadStorageSettingsAsync(Token));

        settings = settings with { DownloadDirectory = Path.Combine(settings.CacheDirectory, "fileway-cache-v1-downloads") };
        await store.SaveStorageSettingsAsync(settings, Token);
        Assert.Equal(settings, await store.ReadStorageSettingsAsync(Token));
    }

    [Fact]
    public async Task DirectoryChangesStayPendingUntilRestartWhileCapacityCanApplyImmediately()
    {
        using var directory = new StorageTestDirectory();
        var store = new SqliteProfileStore(directory.DatabasePath);
        var defaults = Settings(directory);
        await using var host = new UnusedHost();
        var pending = defaults with
        {
            CacheDirectory = Path.Combine(directory.DirectoryPath, "next-cache"),
            DownloadDirectory = Path.Combine(directory.DirectoryPath, "next-downloads"),
            MaximumCacheBytes = 0,
        };
        await using (var service = new LocalStorageSettingsService(store, defaults, host))
        {
            var changed = await service.SaveAsync(pending, Token);
            Assert.Equal(defaults, changed.Active);
            Assert.Equal(pending, changed.Pending);
            Assert.True(changed.RestartRequired);
            Assert.Equal(changed, await service.ReadAsync(Token));
        }

        await using var restarted = new LocalStorageSettingsService(new SqliteProfileStore(directory.DatabasePath), defaults, host);
        var state = await restarted.ReadAsync(Token);
        Assert.Equal(pending, state.Active);
        Assert.Equal(pending, state.Pending);
        Assert.False(state.RestartRequired);

        var capacityOnly = pending with { MaximumCacheBytes = ClientStorageSettings.MaximumSupportedCacheBytes };
        state = await restarted.SaveAsync(capacityOnly, Token);
        Assert.Equal(capacityOnly, state.Active);
        Assert.Equal(capacityOnly, state.Pending);
        Assert.False(state.RestartRequired);
    }

    [Fact]
    public async Task DisposeRejectsNewRequestsButDrainsQueuedSavesAndCanceledWaiters()
    {
        using var directory = new StorageTestDirectory();
        var defaults = Settings(directory);
        var store = new BlockingStore();
        await using var host = new UnusedHost();
        await using var service = new LocalStorageSettingsService(store, defaults, host);
        Task<StorageSettingsState> reading = service.ReadAsync(Token);
        await store.ReadEntered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var firstSettings = defaults with { MaximumCacheBytes = 0 };
        var secondSettings = defaults with { MaximumCacheBytes = 1234 };
        Task<StorageSettingsState> first = service.SaveAsync(firstSettings, Token);
        Task<StorageSettingsState> second = service.SaveAsync(secondSettings, Token);
        using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
        Task<StorageSettingsState> canceledRead = service.ReadAsync(canceled.Token);
        Task? disposing = null;
        try
        {
            canceled.Cancel();
            await Assert.ThrowsAnyAsync<OperationCanceledException>(() => canceledRead.WaitAsync(TimeSpan.FromSeconds(5), Token));
            disposing = service.DisposeAsync().AsTask();
            Assert.False(disposing.IsCompleted);
            await Assert.ThrowsAsync<ObjectDisposedException>(() => service.ReadAsync(Token));
            await Assert.ThrowsAsync<ObjectDisposedException>(() => service.SaveAsync(defaults, Token));
        }
        finally { store.ReleaseRead.TrySetResult(); }

        await Task.WhenAll(reading, first, second, disposing!).WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.Equal(2, store.Saved.Count);
        Assert.Contains(firstSettings, store.Saved);
        Assert.Contains(secondSettings, store.Saved);
        Assert.Equal(firstSettings, (await first).Pending);
        Assert.Equal(secondSettings, (await second).Pending);
        await service.DisposeAsync();
        await Assert.ThrowsAsync<ObjectDisposedException>(() => service.ReadAsync(Token));
    }

    [Fact]
    public async Task SchemaOneMigrationBacksUpCommittedWalAndPreservesProfilesAndLogoutIntent()
    {
        using var directory = new StorageTestDirectory();
        var saved = new SavedConnection(new ConnectionProfile(Guid.NewGuid(), Guid.NewGuid(), "迁移连接", new Uri("https://fixture.invalid/nas/"),
            NetworkMode.SystemNetwork, new ProxyPolicy(ProxyMode.Direct)), AuthenticationMode.Password, "fixture.user", "7", false,
            new DateTimeOffset(2026, 10, 11, 1, 2, 3, TimeSpan.Zero));
        using var writer = OpenDatabase(directory.DatabasePath);
        using (var schema = writer.CreateCommand())
        {
            schema.CommandText = """
                PRAGMA journal_mode = WAL;
                PRAGMA wal_autocheckpoint = 0;
                CREATE TABLE saved_connections (
                    profile_id TEXT PRIMARY KEY, source_revision TEXT, display_name TEXT, base_address TEXT,
                    network_mode INTEGER, proxy_mode INTEGER, proxy_address TEXT, authentication_mode INTEGER,
                    username TEXT, server_user_id TEXT, keep_signed_in INTEGER, last_connected_at TEXT);
                CREATE TABLE pending_logouts (profile_id TEXT, source_revision TEXT, server_user_id TEXT,
                    PRIMARY KEY (profile_id, source_revision, server_user_id));
                PRAGMA user_version = 1;
                PRAGMA wal_checkpoint(TRUNCATE);
                """;
            schema.ExecuteNonQuery();
        }
        using (var insert = writer.CreateCommand())
        {
            insert.CommandText = """
                INSERT INTO saved_connections VALUES ($profile, $revision, '迁移连接', 'https://fixture.invalid/nas/',
                    0, 1, NULL, 0, 'fixture.user', '7', 0, $connected);
                INSERT INTO pending_logouts VALUES ($profile, $revision, '7');
                """;
            insert.Parameters.AddWithValue("$profile", saved.Profile.Id.ToString("D"));
            insert.Parameters.AddWithValue("$revision", saved.Profile.SourceRevision.ToString("D"));
            insert.Parameters.AddWithValue("$connected", saved.LastConnectedAt.ToString("O", System.Globalization.CultureInfo.InvariantCulture));
            insert.ExecuteNonQuery();
        }
        Assert.True(new FileInfo(directory.DatabasePath + "-wal").Length > 0);
        var store = new SqliteProfileStore(directory.DatabasePath);
        Assert.Null(await store.ReadStorageSettingsAsync(Token));
        var settings = Settings(directory);
        await store.SaveStorageSettingsAsync(settings, Token);

        string backupPath = Assert.Single(Directory.GetFiles(directory.DirectoryPath, "profiles.sqlite3.schema1-*.backup"));
        using (var backup = OpenDatabase(backupPath))
        using (var read = backup.CreateCommand())
        {
            read.CommandText = "PRAGMA user_version";
            Assert.Equal(1L, read.ExecuteScalar());
            read.CommandText = "SELECT display_name FROM saved_connections WHERE profile_id = $profile";
            read.Parameters.AddWithValue("$profile", saved.Profile.Id.ToString("D"));
            Assert.Equal(saved.Profile.DisplayName, read.ExecuteScalar());
            read.CommandText = "SELECT COUNT(*) FROM pending_logouts";
            Assert.Equal(1L, read.ExecuteScalar());
            read.CommandText = "SELECT COUNT(*) FROM sqlite_schema WHERE name = 'client_storage_settings'";
            Assert.Equal(0L, read.ExecuteScalar());
        }
        var reopened = new SqliteProfileStore(directory.DatabasePath);
        Assert.Equal(saved, await reopened.GetAsync(saved.Profile.Id, Token));
        Assert.Equal(saved.Account, Assert.Single(await reopened.ListPendingLogoutsAsync(Token)));
        Assert.Equal(settings, await reopened.ReadStorageSettingsAsync(Token));
        using var upgraded = OpenDatabase(directory.DatabasePath);
        using var version = upgraded.CreateCommand();
        version.CommandText = "PRAGMA user_version";
        Assert.Equal(3L, version.ExecuteScalar());
        Assert.Single(Directory.GetFiles(directory.DirectoryPath, "profiles.sqlite3.schema1-*.backup"));
    }

    [Fact]
    public async Task ImmediateFutureSchemaRejectsSettingsWriteWithoutChangingDatabase()
    {
        using var directory = new StorageTestDirectory();
        using (var database = OpenDatabase(directory.DatabasePath))
        using (var create = database.CreateCommand())
        {
            create.CommandText = "CREATE TABLE future_data (value TEXT); INSERT INTO future_data VALUES ('must survive'); PRAGMA user_version = 4;";
            create.ExecuteNonQuery();
        }
        byte[] before = await File.ReadAllBytesAsync(directory.DatabasePath, Token);
        var error = await Assert.ThrowsAsync<FilewayException>(() => new SqliteProfileStore(directory.DatabasePath).SaveStorageSettingsAsync(Settings(directory), Token));
        Assert.Equal(ErrorCode.LocalStorageUnavailable, error.Error.Code);
        Assert.Equal(before, await File.ReadAllBytesAsync(directory.DatabasePath, Token));
        Assert.Empty(Directory.GetFiles(directory.DirectoryPath, "*.backup"));
    }

    private static SqliteConnection OpenDatabase(string path)
    {
        var database = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path, Pooling = false }.ToString());
        database.Open();
        return database;
    }

    private sealed class BlockingStore : IStorageSettingsStore
    {
        internal TaskCompletionSource ReadEntered { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal TaskCompletionSource ReleaseRead { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal List<ClientStorageSettings> Saved { get; } = [];

        public async Task<ClientStorageSettings?> ReadStorageSettingsAsync(CancellationToken cancellationToken)
        {
            ReadEntered.TrySetResult();
            await ReleaseRead.Task.WaitAsync(cancellationToken);
            return null;
        }

        public Task SaveStorageSettingsAsync(ClientStorageSettings settings, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Saved.Add(settings);
            return Task.CompletedTask;
        }
    }

    private sealed class UnusedHost : IHostControlClient
    {
        public HostHandshake Handshake { get; } = new(1, 0, 1, "fixture", "fixture", "fixture", JsonSerializer.SerializeToElement(new { }));
        public Task<HostExit> Completion { get; } = Task.FromResult(new HostExit(true, 0, null));
        public Task<HostReply> CallAsync(HostCall request, CancellationToken cancellationToken) =>
            throw new InvalidOperationException("Reading or saving unconfigured storage settings must not contact Host.");
        public Task ShutdownAsync(CancellationToken cancellationToken) => Task.CompletedTask;
        public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    }
}

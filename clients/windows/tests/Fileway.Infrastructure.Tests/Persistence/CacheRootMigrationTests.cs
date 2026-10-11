using Fileway.Core.Contracts;
using Fileway.Infrastructure.Persistence;
using Microsoft.Data.Sqlite;

namespace Fileway.Infrastructure.Tests.Persistence;

public sealed class CacheRootMigrationTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact]
    public async Task SchemaTwoWalBackupPreservesMetadataAndNewLedgerUsesFullAccountAndRootIdentity()
    {
        using var directory = new StorageTestDirectory();
        var original = new SavedConnection(new ConnectionProfile(Guid.NewGuid(), Guid.NewGuid(), "迁移前", new Uri("https://fixture.invalid/nas/"),
            NetworkMode.SystemNetwork, new ProxyPolicy(ProxyMode.Direct)), AuthenticationMode.Password, "fixture.user", "7", true,
            new DateTimeOffset(2026, 10, 11, 1, 2, 3, TimeSpan.Zero));
        var settings = new ClientStorageSettings(Path.Combine(directory.DirectoryPath, "first-root"), Path.Combine(directory.DirectoryPath, "downloads"));
        var bootstrap = new SqliteProfileStore(directory.DatabasePath);
        await bootstrap.SaveAsync(original, Token);
        await bootstrap.SaveStorageSettingsAsync(settings, Token);
        var pending = original.Account with { ServerUserId = "8" };
        using var writer = OpenDatabase(directory.DatabasePath);
        using (var downgrade = writer.CreateCommand())
        {
            // Recreate the schema2 boundary while keeping the real profile/settings schema.
            downgrade.CommandText = "DROP TABLE transfer_tasks; DROP TABLE operation_journal_items; DROP TABLE operation_journal; DROP TABLE browser_preferences; DROP TABLE cache_roots; PRAGMA user_version = 2; PRAGMA wal_autocheckpoint = 0; PRAGMA wal_checkpoint(TRUNCATE);";
            downgrade.ExecuteNonQuery();
        }
        using (var committedWal = writer.CreateCommand())
        {
            committedWal.CommandText = """
                BEGIN IMMEDIATE;
                UPDATE saved_connections SET display_name = '已提交WAL';
                UPDATE client_storage_settings SET maximum_cache_bytes = 0;
                INSERT INTO pending_logouts VALUES ($profile, $revision, $user);
                COMMIT;
                """;
            committedWal.Parameters.AddWithValue("$profile", pending.ProfileId.ToString("D"));
            committedWal.Parameters.AddWithValue("$revision", pending.SourceRevision.ToString("D"));
            committedWal.Parameters.AddWithValue("$user", pending.ServerUserId);
            committedWal.ExecuteNonQuery();
        }
        Assert.True(new FileInfo(directory.DatabasePath + "-wal").Length > 0);
        var migrated = new SqliteProfileStore(directory.DatabasePath);
        Assert.Empty(await migrated.ListCacheRootsAsync(Token));
        string backupPath = Assert.Single(Directory.GetFiles(directory.DirectoryPath, "profiles.sqlite3.schema2-*.backup"));
        using (var backup = OpenDatabase(backupPath))
        using (var read = backup.CreateCommand())
        {
            read.CommandText = "PRAGMA user_version";
            Assert.Equal(2L, read.ExecuteScalar());
            read.CommandText = "SELECT display_name FROM saved_connections";
            Assert.Equal("已提交WAL", read.ExecuteScalar());
            read.CommandText = "SELECT maximum_cache_bytes FROM client_storage_settings";
            Assert.Equal(0L, read.ExecuteScalar());
            read.CommandText = "SELECT server_user_id FROM pending_logouts";
            Assert.Equal(pending.ServerUserId, read.ExecuteScalar());
            read.CommandText = "SELECT COUNT(*) FROM sqlite_schema WHERE name = 'cache_roots'";
            Assert.Equal(0L, read.ExecuteScalar());
        }
        var reopened = new SqliteProfileStore(directory.DatabasePath);
        Assert.Equal(original with { Profile = original.Profile with { DisplayName = "已提交WAL" } }, await reopened.GetAsync(original.Profile.Id, Token));
        Assert.Equal(settings with { MaximumCacheBytes = 0 }, await reopened.ReadStorageSettingsAsync(Token));
        Assert.Equal(pending, Assert.Single(await reopened.ListPendingLogoutsAsync(Token)));

        string secondRoot = Path.Combine(directory.DirectoryPath, "second-root");
        var differentRevision = original.Account with { SourceRevision = Guid.NewGuid() };
        var differentProfile = original.Account with { ProfileId = Guid.NewGuid() };
        await reopened.RegisterCacheRootAsync(original.Account, settings.CacheDirectory, Token);
        await reopened.RegisterCacheRootAsync(original.Account, settings.CacheDirectory.ToUpperInvariant() + Path.DirectorySeparatorChar, Token);
        await reopened.RegisterCacheRootAsync(original.Account, secondRoot, Token);
        await reopened.RegisterCacheRootAsync(differentRevision, settings.CacheDirectory, Token);
        await reopened.RegisterCacheRootAsync(differentProfile, settings.CacheDirectory, Token);
        await reopened.RegisterCacheRootForCleanupAsync(pending, settings.CacheDirectory, Token);
        Assert.Equal(5, (await new SqliteProfileStore(directory.DatabasePath).ListCacheRootsAsync(Token)).Count);
        var blocked = await Assert.ThrowsAsync<FilewayException>(() => reopened.RegisterCacheRootAsync(pending, secondRoot, Token));
        Assert.Equal(ErrorCode.LocalStorageUnavailable, blocked.Error.Code);
        await Assert.ThrowsAsync<FilewayException>(() => reopened.RegisterCacheRootForCleanupAsync(original.Account, secondRoot, Token));
        await Assert.ThrowsAsync<FilewayException>(() => reopened.RegisterCacheRootAsync(original.Account, "relative-root", Token));

        await reopened.RemoveCacheRootAsync(original.Account, settings.CacheDirectory.ToUpperInvariant(), Token);
        var retained = await new SqliteProfileStore(directory.DatabasePath).ListCacheRootsAsync(Token);
        Assert.Equal(4, retained.Count);
        Assert.Contains(new RegisteredCacheRoot(original.Account, secondRoot), retained);
        Assert.Contains(new RegisteredCacheRoot(differentRevision, settings.CacheDirectory), retained);
        Assert.Contains(new RegisteredCacheRoot(differentProfile, settings.CacheDirectory), retained);
        Assert.Contains(new RegisteredCacheRoot(pending, settings.CacheDirectory), retained);
        using var current = OpenDatabase(directory.DatabasePath);
        using var version = current.CreateCommand();
        version.CommandText = "PRAGMA user_version";
        Assert.Equal(6L, version.ExecuteScalar());
        Assert.Single(Directory.GetFiles(directory.DirectoryPath, "profiles.sqlite3.schema2-*.backup"));
    }

    private static SqliteConnection OpenDatabase(string path)
    {
        var connection = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path, Pooling = false }.ToString());
        connection.Open();
        return connection;
    }
}

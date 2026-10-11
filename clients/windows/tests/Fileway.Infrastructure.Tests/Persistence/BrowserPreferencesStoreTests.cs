using System.Globalization;
using Fileway.Core.Contracts;
using Fileway.Infrastructure.Persistence;
using Microsoft.Data.Sqlite;

namespace Fileway.Infrastructure.Tests.Persistence;

public sealed class BrowserPreferencesStoreTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private const string FirstDirectory = "2F612B";
    private const string SecondDirectory = "2F6225";
    private static readonly BrowserPreferences Chosen = new(BrowserViewMode.WindowsIcons, BrowserIconSize.Small,
        BrowserIconSize.ExtraLarge, BrowserFileTypeFilter.Config, FileSortField.Modified, true);

    [Fact]
    public async Task PreferencesSurviveReopenWithCanonicalHexAndNoSensitiveColumns()
    {
        using var directory = new StorageTestDirectory();
        var saved = SavedProfile();
        var store = new SqliteProfileStore(directory.DatabasePath);
        await store.SaveAsync(saved, Token);
        Assert.Null(await store.ReadBrowserPreferencesAsync(saved.Account, FirstDirectory, Token));

        await store.SaveBrowserPreferencesAsync(saved.Account, FirstDirectory.ToLowerInvariant(), Chosen, Token);

        var reopened = new SqliteProfileStore(directory.DatabasePath);
        Assert.Equal(Chosen, await reopened.ReadBrowserPreferencesAsync(saved.Account, FirstDirectory, Token));
        Assert.Equal(Chosen, await reopened.ReadBrowserPreferencesAsync(saved.Account, FirstDirectory.ToLowerInvariant(), Token));
        using var database = OpenDatabase(directory.DatabasePath);
        using var command = database.CreateCommand();
        command.CommandText = "SELECT directory_identity FROM browser_preferences";
        Assert.Equal(FirstDirectory, command.ExecuteScalar());
        command.CommandText = "PRAGMA table_info(browser_preferences)";
        using var reader = command.ExecuteReader();
        var columns = new List<string>();
        while (reader.Read()) columns.Add(reader.GetString(1));
        Assert.Equal(10, columns.Count);
        Assert.DoesNotContain(columns, column => column.Contains("token", StringComparison.OrdinalIgnoreCase) ||
            column.Contains("password", StringComparison.OrdinalIgnoreCase) || column.Contains("address", StringComparison.OrdinalIgnoreCase) ||
            column.Contains("url", StringComparison.OrdinalIgnoreCase));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task EveryAccountComponentAndDirectoryIsolatedAndForgetCannotDeleteReplacement(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = CreateStore(sqlite, directory);
        var preferences = (IBrowserPreferencesStore)store;
        var first = SavedProfile();
        var otherProfile = first with { Profile = first.Profile with { Id = Guid.NewGuid() } };
        var otherRevision = first with { Profile = first.Profile with { SourceRevision = Guid.NewGuid() } };
        var otherUser = otherRevision with { ServerUserId = "8" };
        await store.SaveAsync(first, Token);
        await store.SaveAsync(otherProfile, Token);
        await preferences.SaveBrowserPreferencesAsync(first.Account, FirstDirectory, Chosen, Token);
        var secondDirectory = Chosen with { ViewMode = BrowserViewMode.Details };
        await preferences.SaveBrowserPreferencesAsync(first.Account, SecondDirectory, secondDirectory, Token);
        Assert.Null(await preferences.ReadBrowserPreferencesAsync(otherProfile.Account, FirstDirectory, Token));
        var profileChoice = Chosen with { ViewMode = BrowserViewMode.CompactList };
        await preferences.SaveBrowserPreferencesAsync(otherProfile.Account, FirstDirectory, profileChoice, Token);
        await store.SaveAsync(otherRevision, Token);
        Assert.Null(await preferences.ReadBrowserPreferencesAsync(otherRevision.Account, FirstDirectory, Token));
        var revisionChoice = Chosen with { TypeFilter = BrowserFileTypeFilter.Audio };
        await preferences.SaveBrowserPreferencesAsync(otherRevision.Account, FirstDirectory, revisionChoice, Token);
        await store.SaveAsync(otherUser, Token);
        Assert.Null(await preferences.ReadBrowserPreferencesAsync(otherUser.Account, FirstDirectory, Token));
        var userChoice = Chosen with { TypeFilter = BrowserFileTypeFilter.Video };
        await preferences.SaveBrowserPreferencesAsync(otherUser.Account, FirstDirectory, userChoice, Token);

        var stale = await Assert.ThrowsAsync<FilewayException>(() => preferences.SaveBrowserPreferencesAsync(first.Account, FirstDirectory, new(), Token));
        Assert.Equal(ErrorCode.LocalStorageUnavailable, stale.Error.Code);
        Assert.Equal(Chosen, await preferences.ReadBrowserPreferencesAsync(first.Account, FirstDirectory, Token));
        Assert.Equal(secondDirectory, await preferences.ReadBrowserPreferencesAsync(first.Account, SecondDirectory, Token));
        Assert.Equal(profileChoice, await preferences.ReadBrowserPreferencesAsync(otherProfile.Account, FirstDirectory, Token));
        Assert.Equal(revisionChoice, await preferences.ReadBrowserPreferencesAsync(otherRevision.Account, FirstDirectory, Token));
        Assert.Equal(userChoice, await preferences.ReadBrowserPreferencesAsync(otherUser.Account, FirstDirectory, Token));
        await store.ForgetAsync(first.Account, Token);
        Assert.Null(await preferences.ReadBrowserPreferencesAsync(first.Account, FirstDirectory, Token));
        Assert.Null(await preferences.ReadBrowserPreferencesAsync(first.Account, SecondDirectory, Token));
        Assert.Equal(otherUser, await store.GetAsync(otherUser.Profile.Id, Token));
        Assert.Equal(userChoice, await preferences.ReadBrowserPreferencesAsync(otherUser.Account, FirstDirectory, Token));
        Assert.Equal(profileChoice, await preferences.ReadBrowserPreferencesAsync(otherProfile.Account, FirstDirectory, Token));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task PendingLogoutAndForgottenOwnerRejectStalePreferenceCallbacks(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = CreateStore(sqlite, directory);
        var preferences = (IBrowserPreferencesStore)store;
        var saved = SavedProfile();
        var missing = await Assert.ThrowsAsync<FilewayException>(() => preferences.SaveBrowserPreferencesAsync(saved.Account, FirstDirectory, Chosen, Token));
        Assert.Equal(ErrorCode.LocalStorageUnavailable, missing.Error.Code);
        await store.SaveAsync(saved, Token);
        await preferences.SaveBrowserPreferencesAsync(saved.Account, FirstDirectory, Chosen, Token);
        await store.BeginLogoutAsync(saved.Account, Token);
        var otherInstance = sqlite ? (IBrowserPreferencesStore)new SqliteProfileStore(directory.DatabasePath) : preferences;

        var failures = await Task.WhenAll(Enumerable.Range(0, 3).Select(async _ =>
            await Assert.ThrowsAsync<FilewayException>(() => otherInstance.SaveBrowserPreferencesAsync(saved.Account, FirstDirectory, new(), Token))));

        Assert.All(failures, error => Assert.Equal(ErrorCode.LocalStorageUnavailable, error.Error.Code));
        Assert.Equal(Chosen, await preferences.ReadBrowserPreferencesAsync(saved.Account, FirstDirectory, Token));
        await store.CompleteLogoutAsync(saved.Account, Token);
        await preferences.SaveBrowserPreferencesAsync(saved.Account, FirstDirectory, new(), Token);
        await store.ForgetAsync(saved.Account, Token);
        var forgotten = await Assert.ThrowsAsync<FilewayException>(() => otherInstance.SaveBrowserPreferencesAsync(saved.Account, FirstDirectory, Chosen, Token));
        Assert.Equal(ErrorCode.LocalStorageUnavailable, forgotten.Error.Code);
        Assert.Null(await preferences.ReadBrowserPreferencesAsync(saved.Account, FirstDirectory, Token));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task InvalidIdentityOwnerAndEnumsAreTypedAndDoNotAlterSavedPreferences(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = CreateStore(sqlite, directory);
        var preferences = (IBrowserPreferencesStore)store;
        var saved = SavedProfile();
        await store.SaveAsync(saved, Token);
        await preferences.SaveBrowserPreferencesAsync(saved.Account, FirstDirectory, Chosen, Token);
        string[] invalidIdentities = ["", "2", "2F-G", "/a", "https://fixture.invalid", "2F 61"];
        foreach (string identity in invalidIdentities)
        {
            var invalidRead = await Assert.ThrowsAsync<FilewayException>(() => preferences.ReadBrowserPreferencesAsync(saved.Account, identity, Token));
            var invalidWrite = await Assert.ThrowsAsync<FilewayException>(() => preferences.SaveBrowserPreferencesAsync(saved.Account, identity, new(), Token));
            Assert.Equal(ErrorCode.LocalStorageUnavailable, invalidRead.Error.Code);
            Assert.Equal(ErrorCode.LocalStorageUnavailable, invalidWrite.Error.Code);
        }
        AccountKey[] invalidAccounts = [saved.Account with { ProfileId = Guid.Empty }, saved.Account with { SourceRevision = Guid.Empty },
            saved.Account with { ServerUserId = "" }, saved.Account with { ServerUserId = "7\0" }];
        foreach (var account in invalidAccounts)
        {
            var error = await Assert.ThrowsAsync<FilewayException>(() => preferences.ReadBrowserPreferencesAsync(account, FirstDirectory, Token));
            Assert.Equal(ErrorCode.LocalStorageUnavailable, error.Error.Code);
        }
        BrowserPreferences[] invalidValues = [Chosen with { ViewMode = (BrowserViewMode)99 }, Chosen with { CompactGridSize = (BrowserIconSize)(-1) },
            Chosen with { WindowsIconSize = (BrowserIconSize)99 }, Chosen with { TypeFilter = (BrowserFileTypeFilter)99 }, Chosen with { SortBy = (FileSortField)99 }];
        foreach (var value in invalidValues)
        {
            var error = await Assert.ThrowsAsync<FilewayException>(() => preferences.SaveBrowserPreferencesAsync(saved.Account, FirstDirectory, value, Token));
            Assert.Equal(ErrorCode.LocalStorageUnavailable, error.Error.Code);
        }
        Assert.Equal(Chosen, await preferences.ReadBrowserPreferencesAsync(saved.Account, FirstDirectory, Token));
    }

    [Fact]
    public async Task SchemaThreeMigrationBacksUpCommittedWalAndPreservesAccountLedgers()
    {
        using var directory = new StorageTestDirectory();
        var saved = SavedProfile();
        var settings = new ClientStorageSettings(Path.Combine(directory.DirectoryPath, "cache"), Path.Combine(directory.DirectoryPath, "downloads"));
        var bootstrap = new SqliteProfileStore(directory.DatabasePath);
        await bootstrap.SaveAsync(saved, Token);
        await bootstrap.SaveStorageSettingsAsync(settings, Token);
        await bootstrap.RegisterCacheRootAsync(saved.Account, settings.CacheDirectory, Token);
        var pending = saved.Account with { ServerUserId = "8" };
        using var writer = OpenDatabase(directory.DatabasePath);
        using (var schemaThree = writer.CreateCommand())
        {
            schemaThree.CommandText = "DROP TABLE transfer_tasks; DROP TABLE operation_journal_items; DROP TABLE operation_journal; DROP TABLE browser_preferences; PRAGMA user_version = 3; PRAGMA wal_autocheckpoint = 0; PRAGMA wal_checkpoint(TRUNCATE);";
            schemaThree.ExecuteNonQuery();
        }
        using (var committed = writer.CreateCommand())
        {
            committed.CommandText = """
                BEGIN IMMEDIATE;
                UPDATE saved_connections SET display_name = '已提交WAL';
                UPDATE client_storage_settings SET maximum_cache_bytes = 0;
                INSERT INTO pending_logouts VALUES ($profile, $revision, $user);
                COMMIT;
                """;
            committed.Parameters.AddWithValue("$profile", pending.ProfileId.ToString("D"));
            committed.Parameters.AddWithValue("$revision", pending.SourceRevision.ToString("D"));
            committed.Parameters.AddWithValue("$user", pending.ServerUserId);
            committed.ExecuteNonQuery();
        }
        Assert.True(new FileInfo(directory.DatabasePath + "-wal").Length > 0);

        var migrated = new SqliteProfileStore(directory.DatabasePath);
        Assert.Null(await migrated.ReadBrowserPreferencesAsync(saved.Account, FirstDirectory, Token));

        string backupPath = Assert.Single(Directory.GetFiles(directory.DirectoryPath, "profiles.sqlite3.schema3-*.backup"));
        using (var backup = OpenDatabase(backupPath))
        using (var command = backup.CreateCommand())
        {
            command.CommandText = "PRAGMA user_version";
            Assert.Equal(3L, command.ExecuteScalar());
            command.CommandText = "PRAGMA integrity_check";
            Assert.Equal("ok", command.ExecuteScalar());
            command.CommandText = "SELECT display_name FROM saved_connections";
            Assert.Equal("已提交WAL", command.ExecuteScalar());
            command.CommandText = "SELECT maximum_cache_bytes FROM client_storage_settings";
            Assert.Equal(0L, command.ExecuteScalar());
            command.CommandText = "SELECT server_user_id FROM pending_logouts";
            Assert.Equal(pending.ServerUserId, command.ExecuteScalar());
            command.CommandText = "SELECT cache_root FROM cache_roots";
            Assert.Equal(settings.CacheDirectory, command.ExecuteScalar());
            command.CommandText = "SELECT COUNT(*) FROM sqlite_schema WHERE name = 'browser_preferences'";
            Assert.Equal(0L, command.ExecuteScalar());
        }
        Assert.Equal(saved with { Profile = saved.Profile with { DisplayName = "已提交WAL" } }, await migrated.GetAsync(saved.Profile.Id, Token));
        Assert.Equal(settings with { MaximumCacheBytes = 0 }, await migrated.ReadStorageSettingsAsync(Token));
        Assert.Equal(pending, Assert.Single(await migrated.ListPendingLogoutsAsync(Token)));
        Assert.Equal(new RegisteredCacheRoot(saved.Account, settings.CacheDirectory), Assert.Single(await migrated.ListCacheRootsAsync(Token)));
        await migrated.SaveBrowserPreferencesAsync(saved.Account, FirstDirectory, Chosen, Token);
        Assert.Equal(Chosen, await new SqliteProfileStore(directory.DatabasePath).ReadBrowserPreferencesAsync(saved.Account, FirstDirectory, Token));
        using var upgraded = OpenDatabase(directory.DatabasePath);
        using var version = upgraded.CreateCommand();
        version.CommandText = "PRAGMA user_version";
        Assert.Equal(6L, version.ExecuteScalar());
        Assert.Single(Directory.GetFiles(directory.DirectoryPath, "*.backup"));
    }

    [Theory]
    [InlineData("view_mode", "5")]
    [InlineData("compact_grid_size", "4")]
    [InlineData("windows_icon_size", "-1")]
    [InlineData("type_filter", "8")]
    [InlineData("sort_by", "4")]
    [InlineData("descending", "2")]
    [InlineData("directory_identity", "'2f612b'")]
    [InlineData("directory_identity", "'2F-G'")]
    [InlineData("directory_identity", "'2F6'")]
    public async Task DatabaseChecksRejectInvalidDurablePreferences(string column, string literal)
    {
        using var directory = new StorageTestDirectory();
        var store = new SqliteProfileStore(directory.DatabasePath);
        var saved = SavedProfile();
        await store.SaveAsync(saved, Token);
        await store.SaveBrowserPreferencesAsync(saved.Account, FirstDirectory, Chosen, Token);
        using (var database = OpenDatabase(directory.DatabasePath))
        using (var command = database.CreateCommand())
        {
            // Both strings are fixed InlineData fixtures, never product or user SQL.
            command.CommandText = "UPDATE browser_preferences SET " + column + " = " + literal;
            Assert.Throws<SqliteException>(() => command.ExecuteNonQuery());
        }
        Assert.Equal(Chosen, await store.ReadBrowserPreferencesAsync(saved.Account, FirstDirectory, Token));
    }

    private static IProfileStore CreateStore(bool sqlite, StorageTestDirectory directory) => sqlite
        ? new SqliteProfileStore(directory.DatabasePath) : new InMemoryProfileStore();

    private static SavedConnection SavedProfile() => new(new ConnectionProfile(Guid.NewGuid(), Guid.NewGuid(), "浏览偏好测试",
        new Uri("https://fixture.invalid/nas/"), NetworkMode.SystemNetwork, new ProxyPolicy(ProxyMode.Direct)),
        AuthenticationMode.Password, "fixture.user", "7", true,
        DateTimeOffset.ParseExact("2026-10-11T10:20:30.0000000+00:00", "O", CultureInfo.InvariantCulture));

    private static SqliteConnection OpenDatabase(string path)
    {
        var database = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path, Pooling = false }.ToString());
        database.Open();
        return database;
    }
}

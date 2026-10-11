using Fileway.Core.Contracts;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Tests.Ipc;
using Microsoft.Data.Sqlite;

namespace Fileway.Infrastructure.Tests.Persistence;

public sealed class SqliteProfileStoreTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact]
    public async Task MetadataSurvivesReopenAndSchemaHasNoCredentialColumns()
    {
        using var directory = new StorageTestDirectory();
        var saved = SavedProfile();
        await new SqliteProfileStore(directory.DatabasePath).SaveAsync(saved, Token);

        var reopened = new SqliteProfileStore(directory.DatabasePath);
        Assert.Equal(saved, await reopened.GetAsync(saved.Profile.Id, Token));
        Assert.Equal(saved, Assert.Single(await reopened.ListAsync(Token)));

        using var database = OpenDatabase(directory.DatabasePath);
        using var command = database.CreateCommand();
        command.CommandText = "PRAGMA table_info(saved_connections)";
        using var reader = command.ExecuteReader();
        var columns = new List<string>();
        while (reader.Read()) columns.Add(reader.GetString(1));
        Assert.DoesNotContain(columns, column => column.Contains("token", StringComparison.OrdinalIgnoreCase)
            || column.Contains("password", StringComparison.OrdinalIgnoreCase)
            || column.Contains("credential", StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public async Task FutureSchemaRejectsWritesWithoutChangingExistingDatabase()
    {
        using var directory = new StorageTestDirectory();
        using (var database = OpenDatabase(directory.DatabasePath))
        {
            using var command = database.CreateCommand();
            command.CommandText = "CREATE TABLE future_data (value TEXT); INSERT INTO future_data VALUES ('must survive'); PRAGMA user_version = 99;";
            command.ExecuteNonQuery();
        }
        var before = await File.ReadAllBytesAsync(directory.DatabasePath, Token);

        var failure = await Assert.ThrowsAsync<FilewayException>(() =>
            new SqliteProfileStore(directory.DatabasePath).SaveAsync(SavedProfile(), Token));

        Assert.Equal(ErrorCode.LocalStorageUnavailable, failure.Error.Code);
        Assert.Equal(before, await File.ReadAllBytesAsync(directory.DatabasePath, Token));
        using var reopened = OpenDatabase(directory.DatabasePath);
        using var read = reopened.CreateCommand();
        read.CommandText = "SELECT value FROM future_data";
        Assert.Equal("must survive", read.ExecuteScalar());
    }

    [Fact]
    public async Task DurableLogoutBlocksConcurrentStaleSavesFromOtherStoreInstances()
    {
        using var directory = new StorageTestDirectory();
        var saved = SavedProfile();
        var store = new SqliteProfileStore(directory.DatabasePath);
        await store.SaveAsync(saved, Token);
        await store.BeginLogoutAsync(saved.Account, Token);

        var failures = await Task.WhenAll(Enumerable.Range(0, 3).Select(async _ =>
            await Assert.ThrowsAsync<FilewayException>(() =>
                new SqliteProfileStore(directory.DatabasePath).SaveAsync(saved, Token))));

        Assert.All(failures, failure => Assert.Equal(ErrorCode.LocalStorageUnavailable, failure.Error.Code));
        var reopened = new SqliteProfileStore(directory.DatabasePath);
        Assert.False(Assert.IsType<SavedConnection>(await reopened.GetAsync(saved.Profile.Id, Token)).KeepSignedIn);
        Assert.Equal(saved.Account, Assert.Single(await reopened.ListPendingLogoutsAsync(Token)));
        await reopened.CompleteLogoutAsync(saved.Account, Token);
        Assert.Empty(await new SqliteProfileStore(directory.DatabasePath).ListPendingLogoutsAsync(Token));
    }

    private static SavedConnection SavedProfile() => new(
        new ConnectionProfile(Guid.NewGuid(), Guid.NewGuid(), "已保存连接", new Uri("https://fixture.invalid/nas/"),
            NetworkMode.SystemNetwork, new ProxyPolicy(ProxyMode.Direct)),
        AuthenticationMode.Password, "真实用户", "7", true, new DateTimeOffset(2026, 10, 11, 10, 20, 30, TimeSpan.Zero));

    private static SqliteConnection OpenDatabase(string path)
    {
        var connection = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path, Pooling = false }.ToString());
        connection.Open();
        return connection;
    }
}

internal sealed class StorageTestDirectory : IDisposable
{
    internal StorageTestDirectory()
    {
        DirectoryPath = Path.Combine(HarnessFixture.OutputRoot, "saved-connection-fixtures", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(DirectoryPath);
    }

    internal string DirectoryPath { get; }
    internal string DatabasePath => Path.Combine(DirectoryPath, "profiles.sqlite3");
    internal string VaultPath => Path.Combine(DirectoryPath, "credentials");

    public void Dispose()
    {
        Directory.Delete(DirectoryPath, recursive: true);
        GC.SuppressFinalize(this);
    }
}

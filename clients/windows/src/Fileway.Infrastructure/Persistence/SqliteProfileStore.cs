using System.Globalization;
using System.Security;
using Fileway.Core.Contracts;
using Microsoft.Data.Sqlite;

namespace Fileway.Infrastructure.Persistence;

/// <summary>Versioned, credential-free profile storage with durable logout intent.</summary>
public sealed partial class SqliteProfileStore : IProfileStore, IStorageSettingsStore, ICacheRootStore, IBrowserPreferencesStore, IOperationJournalStore, ITransferTaskStore
{
    private const int SchemaVersion = 7;
    private const int BusyTimeoutSeconds = 3;
    private const string Columns = "profile_id, source_revision, display_name, base_address, network_mode, proxy_mode, proxy_address, authentication_mode, username, server_user_id, keep_signed_in, last_connected_at";
    private const string AccountPredicate = "profile_id = $profile AND source_revision = $revision AND server_user_id = $user";
    private readonly object _gate = new();
    private readonly string _databasePath;

    // Opening/initialization is lazy so composition never touches SQLite on the UI thread.
    public SqliteProfileStore(string databasePath)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(databasePath)) throw ProfileStoreValidation.Unavailable();
            _databasePath = Path.GetFullPath(databasePath);
        }
        catch (Exception error) when (IsStorageFailure(error)) { throw ProfileStoreValidation.Unavailable(); }
    }

    public Task<IReadOnlyList<SavedConnection>> ListAsync(CancellationToken cancellationToken) =>
        RunAsync<IReadOnlyList<SavedConnection>>(connection =>
        {
            using var command = Command(connection, "SELECT " + Columns + " FROM saved_connections");
            using var reader = command.ExecuteReader();
            var values = new List<SavedConnection>();
            while (reader.Read())
            {
                cancellationToken.ThrowIfCancellationRequested();
                values.Add(ReadConnection(reader));
            }
            return values.OrderByDescending(value => value.LastConnectedAt).ThenBy(value => value.Profile.Id).ToArray();
        }, cancellationToken);

    public Task<SavedConnection?> GetAsync(Guid profileId, CancellationToken cancellationToken) =>
        RunAsync(connection =>
        {
            using var command = Command(connection, "SELECT " + Columns + " FROM saved_connections WHERE profile_id = $profile");
            command.Parameters.AddWithValue("$profile", profileId.ToString("D"));
            using var reader = command.ExecuteReader();
            return reader.Read() ? ReadConnection(reader) : null;
        }, cancellationToken);

    public Task SaveAsync(SavedConnection connection, CancellationToken cancellationToken) => RunAsync(database =>
    {
        ProfileStoreValidation.Validate(connection);
        // An immediate transaction makes the pending check and upsert indivisible across instances.
        using var transaction = database.BeginTransaction(deferred: false);
        GuardJournalAccountReplacement(database, transaction, connection.Account);
        GuardTransferAccountReplacement(database, transaction, connection);
        using (var pending = Command(database, "SELECT 1 FROM pending_logouts WHERE " + AccountPredicate, transaction))
        {
            BindAccount(pending, connection.Account);
            if (pending.ExecuteScalar() is not null) throw ProfileStoreValidation.Unavailable();
        }
        using var command = Command(database, """
            INSERT INTO saved_connections
                (profile_id, source_revision, display_name, base_address, network_mode, proxy_mode,
                 proxy_address, authentication_mode, username, server_user_id, keep_signed_in, last_connected_at)
            VALUES ($profile, $revision, $display, $address, $network, $proxy, $proxyAddress,
                    $authentication, $username, $user, $keep, $connected)
            ON CONFLICT(profile_id) DO UPDATE SET
                source_revision = excluded.source_revision, display_name = excluded.display_name,
                base_address = excluded.base_address, network_mode = excluded.network_mode,
                proxy_mode = excluded.proxy_mode, proxy_address = excluded.proxy_address,
                authentication_mode = excluded.authentication_mode, username = excluded.username,
                server_user_id = excluded.server_user_id, keep_signed_in = excluded.keep_signed_in,
                last_connected_at = excluded.last_connected_at
            """, transaction);
        BindAccount(command, connection.Account);
        command.Parameters.AddWithValue("$display", connection.Profile.DisplayName);
        command.Parameters.AddWithValue("$address", connection.Profile.BaseAddress.OriginalString);
        command.Parameters.AddWithValue("$network", (int)connection.Profile.Network);
        command.Parameters.AddWithValue("$proxy", (int)connection.Profile.Proxy.Mode);
        command.Parameters.AddWithValue("$proxyAddress", (object?)connection.Profile.Proxy.ManualAddress?.OriginalString ?? DBNull.Value);
        command.Parameters.AddWithValue("$authentication", (int)connection.AuthenticationMode);
        command.Parameters.AddWithValue("$username", connection.Username);
        command.Parameters.AddWithValue("$keep", connection.KeepSignedIn ? 1 : 0);
        command.Parameters.AddWithValue("$connected", connection.LastConnectedAt.ToString("O", CultureInfo.InvariantCulture));
        command.ExecuteNonQuery();
        cancellationToken.ThrowIfCancellationRequested();
        transaction.Commit();
        return true;
    }, cancellationToken);

    public Task ForgetAsync(AccountKey account, CancellationToken cancellationToken) => RunAsync(connection =>
    {
        ProfileStoreValidation.Validate(account);
        using var transaction = connection.BeginTransaction(deferred: false);
        if (HasUnresolvedOperations(connection, transaction, account)) throw OperationJournalValidation.UnresolvedConflict();
        if (HasProtectedTransfers(connection, transaction, account)) throw TransferTaskValidation.ProtectedConflict();
        using var command = Command(connection, "DELETE FROM saved_connections WHERE " + AccountPredicate, transaction);
        BindAccount(command, account);
        command.ExecuteNonQuery();
        using var preferences = Command(connection, "DELETE FROM browser_preferences WHERE " + AccountPredicate, transaction);
        BindAccount(preferences, account);
        preferences.ExecuteNonQuery();
        cancellationToken.ThrowIfCancellationRequested();
        transaction.Commit();
        return true;
    }, cancellationToken);

    public Task<IReadOnlyList<AccountKey>> ListPendingLogoutsAsync(CancellationToken cancellationToken) =>
        RunAsync<IReadOnlyList<AccountKey>>(connection =>
        {
            using var command = Command(connection, "SELECT profile_id, source_revision, server_user_id FROM pending_logouts ORDER BY profile_id, source_revision, server_user_id");
            using var reader = command.ExecuteReader();
            var values = new List<AccountKey>();
            while (reader.Read())
            {
                cancellationToken.ThrowIfCancellationRequested();
                var account = new AccountKey(Guid.ParseExact(reader.GetString(0), "D"), Guid.ParseExact(reader.GetString(1), "D"), reader.GetString(2));
                ProfileStoreValidation.Validate(account);
                values.Add(account);
            }
            return values;
        }, cancellationToken);

    public Task BeginLogoutAsync(AccountKey account, CancellationToken cancellationToken) => RunAsync(connection =>
    {
        ProfileStoreValidation.Validate(account);
        using var transaction = connection.BeginTransaction(deferred: false);
        using (var pending = Command(connection, "INSERT INTO pending_logouts (profile_id, source_revision, server_user_id) VALUES ($profile, $revision, $user) ON CONFLICT DO NOTHING", transaction))
        {
            BindAccount(pending, account);
            pending.ExecuteNonQuery();
        }
        using (var saved = Command(connection, "UPDATE saved_connections SET keep_signed_in = 0 WHERE " + AccountPredicate, transaction))
        {
            BindAccount(saved, account);
            saved.ExecuteNonQuery();
        }
        cancellationToken.ThrowIfCancellationRequested();
        transaction.Commit();
        return true;
    }, cancellationToken);

    public Task CompleteLogoutAsync(AccountKey account, CancellationToken cancellationToken) => RunAsync(connection =>
    {
        ProfileStoreValidation.Validate(account);
        using var command = Command(connection, "DELETE FROM pending_logouts WHERE " + AccountPredicate);
        BindAccount(command, account);
        command.ExecuteNonQuery();
        return true;
    }, cancellationToken);

    public Task<ClientStorageSettings?> ReadStorageSettingsAsync(CancellationToken cancellationToken) => RunAsync(connection =>
    {
        using var command = Command(connection, "SELECT cache_directory, download_directory, maximum_cache_bytes, auto_resume_downloads FROM client_storage_settings WHERE id = 1");
        using var reader = command.ExecuteReader();
        return reader.Read() ? StorageSettingsValidation.Normalize(new ClientStorageSettings(
            reader.GetString(0), reader.GetString(1), reader.GetInt64(2), reader.GetInt64(3) != 0)) : null;
    }, cancellationToken);

    public Task SaveStorageSettingsAsync(ClientStorageSettings settings, CancellationToken cancellationToken) => RunAsync(connection =>
    {
        var normalized = StorageSettingsValidation.Normalize(settings);
        using var transaction = connection.BeginTransaction(deferred: false);
        using var command = Command(connection, """
            INSERT INTO client_storage_settings (id, cache_directory, download_directory, maximum_cache_bytes, auto_resume_downloads)
            VALUES (1, $cache, $download, $maximum, $autoResume)
            ON CONFLICT(id) DO UPDATE SET cache_directory = excluded.cache_directory,
                download_directory = excluded.download_directory, maximum_cache_bytes = excluded.maximum_cache_bytes,
                auto_resume_downloads = excluded.auto_resume_downloads
            """, transaction);
        command.Parameters.AddWithValue("$cache", normalized.CacheDirectory);
        command.Parameters.AddWithValue("$download", normalized.DownloadDirectory);
        command.Parameters.AddWithValue("$maximum", normalized.MaximumCacheBytes);
        command.Parameters.AddWithValue("$autoResume", normalized.AutoResumeDownloads ? 1 : 0);
        command.ExecuteNonQuery();
        cancellationToken.ThrowIfCancellationRequested();
        transaction.Commit();
        return true;
    }, cancellationToken);

    public Task<IReadOnlyList<RegisteredCacheRoot>> ListCacheRootsAsync(CancellationToken cancellationToken) =>
        RunAsync<IReadOnlyList<RegisteredCacheRoot>>(connection =>
        {
            using var command = Command(connection, "SELECT profile_id, source_revision, server_user_id, cache_root FROM cache_roots ORDER BY cache_root, profile_id, source_revision, server_user_id");
            using var reader = command.ExecuteReader();
            var values = new List<RegisteredCacheRoot>();
            while (reader.Read())
            {
                cancellationToken.ThrowIfCancellationRequested();
                var account = new AccountKey(Guid.ParseExact(reader.GetString(0), "D"), Guid.ParseExact(reader.GetString(1), "D"), reader.GetString(2));
                ProfileStoreValidation.Validate(account);
                values.Add(new(account, StorageSettingsValidation.DirectoryPath(reader.GetString(3))));
            }
            return values;
        }, cancellationToken);

    public Task RegisterCacheRootAsync(AccountKey account, string cacheRoot, CancellationToken cancellationToken) =>
        RegisterCacheRootAsync(account, cacheRoot, forCleanup: false, cancellationToken);

    public Task RegisterCacheRootForCleanupAsync(AccountKey account, string cacheRoot, CancellationToken cancellationToken) =>
        RegisterCacheRootAsync(account, cacheRoot, forCleanup: true, cancellationToken);

    private Task<bool> RegisterCacheRootAsync(AccountKey account, string cacheRoot, bool forCleanup, CancellationToken cancellationToken) => RunAsync(connection =>
    {
        ProfileStoreValidation.Validate(account);
        string normalized = StorageSettingsValidation.DirectoryPath(cacheRoot);
        using var transaction = connection.BeginTransaction(deferred: false);
        using (var pending = Command(connection, "SELECT 1 FROM pending_logouts WHERE " + AccountPredicate, transaction))
        {
            BindAccount(pending, account);
            if ((pending.ExecuteScalar() is not null) != forCleanup) throw ProfileStoreValidation.Unavailable();
        }
        using var command = Command(connection, "INSERT INTO cache_roots (profile_id, source_revision, server_user_id, cache_root) VALUES ($profile, $revision, $user, $root) ON CONFLICT DO NOTHING", transaction);
        BindAccount(command, account);
        command.Parameters.AddWithValue("$root", normalized);
        command.ExecuteNonQuery();
        cancellationToken.ThrowIfCancellationRequested();
        transaction.Commit();
        return true;
    }, cancellationToken);

    public Task RemoveCacheRootAsync(AccountKey account, string cacheRoot, CancellationToken cancellationToken) => RunAsync(connection =>
    {
        ProfileStoreValidation.Validate(account);
        string normalized = StorageSettingsValidation.DirectoryPath(cacheRoot);
        using var command = Command(connection, "DELETE FROM cache_roots WHERE " + AccountPredicate + " AND cache_root = $root");
        BindAccount(command, account);
        command.Parameters.AddWithValue("$root", normalized);
        command.ExecuteNonQuery();
        return true;
    }, cancellationToken);

    private Task<T> RunAsync<T>(Func<SqliteConnection, T> operation, CancellationToken cancellationToken) => Task.Run(() =>
    {
        lock (_gate)
        {
            cancellationToken.ThrowIfCancellationRequested();
            try
            {
                using var connection = Open();
                cancellationToken.ThrowIfCancellationRequested();
                return operation(connection);
            }
            catch (Exception error) when (IsStorageFailure(error)) { throw ProfileStoreValidation.Unavailable(); }
        }
    }, cancellationToken);

    private SqliteConnection Open()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_databasePath)!);
        var connection = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = SqliteDataSourcePath(_databasePath),
            Mode = SqliteOpenMode.ReadWriteCreate,
            DefaultTimeout = BusyTimeoutSeconds,
            Pooling = false,
        }.ToString());
        try
        {
            connection.Open();
            long initialVersion = ReadVersion(connection);
            if (initialVersion is < 0 or > SchemaVersion) throw ProfileStoreValidation.Unavailable();
            if (initialVersion is >= 1 and < SchemaVersion) BackupBeforeMigration(connection, initialVersion);
            using var transaction = connection.BeginTransaction(deferred: false);
            // Another instance may have upgraded while the consistent backup was being taken.
            long version = ReadVersion(connection, transaction);
            if (version is < 0 or > SchemaVersion) throw ProfileStoreValidation.Unavailable();
            if (version is >= 1 and < SchemaVersion && initialVersion is not (>= 1 and < SchemaVersion)) throw ProfileStoreValidation.Unavailable();
            if (version == 0)
            {
                using var existing = Command(connection, "SELECT COUNT(*) FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%'", transaction);
                if (Convert.ToInt64(existing.ExecuteScalar(), CultureInfo.InvariantCulture) != 0) throw ProfileStoreValidation.Unavailable();
                using var create = Command(connection, """
                    CREATE TABLE saved_connections (
                        profile_id TEXT NOT NULL PRIMARY KEY,
                        source_revision TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        base_address TEXT NOT NULL,
                        network_mode INTEGER NOT NULL CHECK (network_mode IN (0, 1, 2)),
                        proxy_mode INTEGER NOT NULL CHECK (proxy_mode IN (0, 1, 2)),
                        proxy_address TEXT NULL,
                        authentication_mode INTEGER NOT NULL CHECK (authentication_mode IN (0, 1)),
                        username TEXT NOT NULL,
                        server_user_id TEXT NOT NULL,
                        keep_signed_in INTEGER NOT NULL CHECK (keep_signed_in IN (0, 1)),
                        last_connected_at TEXT NOT NULL
                    );
                    CREATE TABLE pending_logouts (
                        profile_id TEXT NOT NULL,
                        source_revision TEXT NOT NULL,
                        server_user_id TEXT NOT NULL,
                        PRIMARY KEY (profile_id, source_revision, server_user_id)
                    );
                    """, transaction);
                create.ExecuteNonQuery();
            }
            if (version < 2)
            {
                using var migrate = Command(connection, """
                    CREATE TABLE client_storage_settings (
                        id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
                        cache_directory TEXT NOT NULL CHECK (length(cache_directory) > 0),
                        download_directory TEXT NOT NULL CHECK (length(download_directory) > 0),
                        maximum_cache_bytes INTEGER NOT NULL CHECK (maximum_cache_bytes >= 0 AND maximum_cache_bytes <= 1099511627776)
                    );
                    PRAGMA user_version = 2;
                    """, transaction);
                migrate.ExecuteNonQuery();
            }
            if (version < 3)
            {
                using var migrate = Command(connection, """
                    CREATE TABLE cache_roots (
                        profile_id TEXT NOT NULL,
                        source_revision TEXT NOT NULL,
                        server_user_id TEXT NOT NULL,
                        cache_root TEXT NOT NULL COLLATE NOCASE CHECK (length(cache_root) > 0),
                        PRIMARY KEY (profile_id, source_revision, server_user_id, cache_root)
                    );
                    PRAGMA user_version = 3;
                    """, transaction);
                migrate.ExecuteNonQuery();
            }
            if (version < 4)
            {
                using var migrate = Command(connection, """
                    CREATE TABLE browser_preferences (
                        profile_id TEXT NOT NULL CHECK (length(profile_id) = 36),
                        source_revision TEXT NOT NULL CHECK (length(source_revision) = 36),
                        server_user_id TEXT NOT NULL CHECK (length(server_user_id) > 0),
                        directory_identity TEXT NOT NULL CHECK (
                            length(directory_identity) > 0 AND length(directory_identity) % 2 = 0 AND
                            directory_identity = upper(directory_identity) AND directory_identity NOT GLOB '*[^0-9A-F]*'),
                        view_mode INTEGER NOT NULL CHECK (typeof(view_mode) = 'integer' AND view_mode IN (0, 1, 2, 3, 4)),
                        compact_grid_size INTEGER NOT NULL CHECK (typeof(compact_grid_size) = 'integer' AND compact_grid_size IN (0, 1, 2, 3)),
                        windows_icon_size INTEGER NOT NULL CHECK (typeof(windows_icon_size) = 'integer' AND windows_icon_size IN (0, 1, 2, 3)),
                        type_filter INTEGER NOT NULL CHECK (typeof(type_filter) = 'integer' AND type_filter IN (0, 1, 2, 3, 4, 5, 6, 7)),
                        sort_by INTEGER NOT NULL CHECK (typeof(sort_by) = 'integer' AND sort_by IN (0, 1, 2, 3)),
                        descending INTEGER NOT NULL CHECK (typeof(descending) = 'integer' AND descending IN (0, 1)),
                        PRIMARY KEY (profile_id, source_revision, server_user_id, directory_identity)
                    );
                    PRAGMA user_version = 4;
                    """, transaction);
                migrate.ExecuteNonQuery();
            }
            if (version < 5) CreateOperationJournal(connection, transaction);
            if (version < 6) CreateTransferTasks(connection, transaction);
            if (version < 7)
            {
                using var migrate = Command(connection, """
                    ALTER TABLE client_storage_settings ADD COLUMN auto_resume_downloads INTEGER NOT NULL DEFAULT 0
                        CHECK (typeof(auto_resume_downloads) = 'integer' AND auto_resume_downloads IN (0, 1));
                    PRAGMA user_version = 7;
                    """, transaction);
                migrate.ExecuteNonQuery();
            }
            if (version < SchemaVersion) CheckIntegrity(connection, transaction);
            transaction.Commit();
            using var journal = Command(connection, "PRAGMA journal_mode = WAL");
            if (!string.Equals(journal.ExecuteScalar() as string, "wal", StringComparison.OrdinalIgnoreCase))
                throw ProfileStoreValidation.Unavailable();
            using var settings = Command(connection, "PRAGMA foreign_keys = ON; PRAGMA synchronous = FULL; PRAGMA busy_timeout = 3000;");
            settings.ExecuteNonQuery();
            return connection;
        }
        catch
        {
            connection.Dispose();
            throw;
        }
    }

    private static long ReadVersion(SqliteConnection connection, SqliteTransaction? transaction = null)
    {
        using var command = Command(connection, "PRAGMA user_version", transaction);
        return Convert.ToInt64(command.ExecuteScalar(), CultureInfo.InvariantCulture);
    }

    private static string SqliteDataSourcePath(string path)
    {
        // Keep .NET paths and backup names unchanged; the native Windows VFS needs the extended prefix.
        if (!OperatingSystem.IsWindows() || path.StartsWith(@"\\?\", StringComparison.Ordinal)) return path;
        return path.StartsWith(@"\\", StringComparison.Ordinal) ? @"\\?\UNC\" + path[2..] : @"\\?\" + path;
    }

    private void BackupBeforeMigration(SqliteConnection source, long version)
    {
        // BackupDatabase includes committed WAL contents. Never copy the .db file in isolation.
        string backupPath = _databasePath + ".schema" + version.ToString(CultureInfo.InvariantCulture) + "-" + Guid.NewGuid().ToString("N") + ".backup";
        using var backup = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = SqliteDataSourcePath(backupPath),
            Mode = SqliteOpenMode.ReadWriteCreate,
            DefaultTimeout = BusyTimeoutSeconds,
            Pooling = false,
        }.ToString());
        backup.Open();
        source.BackupDatabase(backup);
        CheckIntegrity(backup);
    }

    private static void CheckIntegrity(SqliteConnection connection, SqliteTransaction? transaction = null)
    {
        using var integrity = Command(connection, "PRAGMA integrity_check", transaction);
        using var reader = integrity.ExecuteReader();
        if (!reader.Read() || !string.Equals(reader.GetString(0), "ok", StringComparison.Ordinal) || reader.Read())
            throw ProfileStoreValidation.Unavailable();
    }

    private static SqliteCommand Command(SqliteConnection connection, string sql, SqliteTransaction? transaction = null)
    {
        var command = connection.CreateCommand();
        command.CommandText = sql;
        command.CommandTimeout = BusyTimeoutSeconds;
        command.Transaction = transaction;
        return command;
    }

    private static void BindAccount(SqliteCommand command, AccountKey account)
    {
        command.Parameters.AddWithValue("$profile", account.ProfileId.ToString("D"));
        command.Parameters.AddWithValue("$revision", account.SourceRevision.ToString("D"));
        command.Parameters.AddWithValue("$user", account.ServerUserId);
    }

    private static SavedConnection ReadConnection(SqliteDataReader reader)
    {
        int keep = reader.GetInt32(10);
        if (keep is not (0 or 1)) throw ProfileStoreValidation.Unavailable();
        var profile = new ConnectionProfile(Guid.ParseExact(reader.GetString(0), "D"), Guid.ParseExact(reader.GetString(1), "D"),
            reader.GetString(2), new Uri(reader.GetString(3), UriKind.Absolute), (NetworkMode)reader.GetInt32(4),
            new ProxyPolicy((ProxyMode)reader.GetInt32(5), reader.IsDBNull(6) ? null : new Uri(reader.GetString(6), UriKind.Absolute)));
        var saved = new SavedConnection(profile, (AuthenticationMode)reader.GetInt32(7), reader.GetString(8), reader.GetString(9), keep == 1,
            DateTimeOffset.ParseExact(reader.GetString(11), "O", CultureInfo.InvariantCulture, DateTimeStyles.None));
        ProfileStoreValidation.Validate(saved);
        return saved;
    }

    private static bool IsStorageFailure(Exception error) => error is SqliteException or IOException or
        UnauthorizedAccessException or SecurityException or ArgumentException or InvalidOperationException or
        FormatException or OverflowException or InvalidCastException or NotSupportedException;

    public override string ToString() => "SqliteProfileStore (location redacted)";
}

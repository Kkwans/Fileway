using System.Globalization;
using System.Security;
using Fileway.Core.Contracts;
using Microsoft.Data.Sqlite;

namespace Fileway.Infrastructure.Persistence;

/// <summary>Versioned, credential-free profile storage with durable logout intent.</summary>
public sealed class SqliteProfileStore : IProfileStore
{
    private const int SchemaVersion = 1;
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
        using var command = Command(connection, "DELETE FROM saved_connections WHERE " + AccountPredicate);
        BindAccount(command, account);
        command.ExecuteNonQuery();
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
            DataSource = _databasePath,
            Mode = SqliteOpenMode.ReadWriteCreate,
            DefaultTimeout = BusyTimeoutSeconds,
            Pooling = false,
        }.ToString());
        try
        {
            connection.Open();
            using var transaction = connection.BeginTransaction(deferred: false);
            using var versionCommand = Command(connection, "PRAGMA user_version", transaction);
            long version = Convert.ToInt64(versionCommand.ExecuteScalar(), CultureInfo.InvariantCulture);
            if (version != 0 && version != SchemaVersion) throw ProfileStoreValidation.Unavailable();
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
                    PRAGMA user_version = 1;
                    """, transaction);
                create.ExecuteNonQuery();
            }
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

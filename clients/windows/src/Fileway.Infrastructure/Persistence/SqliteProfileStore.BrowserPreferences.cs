using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

public sealed partial class SqliteProfileStore
{
    public Task<BrowserPreferences?> ReadBrowserPreferencesAsync(AccountKey account, string directoryIdentity, CancellationToken cancellationToken) =>
        RunAsync<BrowserPreferences?>(connection =>
        {
            string identity = BrowserPreferencesValidation.DirectoryIdentity(account, directoryIdentity);
            using var command = Command(connection, """
                SELECT view_mode, compact_grid_size, windows_icon_size, type_filter, sort_by, descending
                FROM browser_preferences
                WHERE profile_id = $profile AND source_revision = $revision AND server_user_id = $user AND directory_identity = $directory
                """);
            BindAccount(command, account);
            command.Parameters.AddWithValue("$directory", identity);
            using var reader = command.ExecuteReader();
            if (!reader.Read()) return null;
            int descending = reader.GetInt32(5);
            if (descending is not (0 or 1)) throw BrowserPreferencesValidation.Unavailable();
            var preferences = new BrowserPreferences((BrowserViewMode)reader.GetInt32(0), (BrowserIconSize)reader.GetInt32(1),
                (BrowserIconSize)reader.GetInt32(2), (BrowserFileTypeFilter)reader.GetInt32(3), (FileSortField)reader.GetInt32(4), descending == 1);
            BrowserPreferencesValidation.Validate(preferences);
            return preferences;
        }, cancellationToken);

    public Task SaveBrowserPreferencesAsync(AccountKey account, string directoryIdentity, BrowserPreferences preferences, CancellationToken cancellationToken) =>
        RunAsync(connection =>
        {
            string identity = BrowserPreferencesValidation.DirectoryIdentity(account, directoryIdentity);
            BrowserPreferencesValidation.Validate(preferences);
            // Serialize authorization with the write across all store instances.
            // A retired callback cannot recreate rows after Forget or pending logout.
            using var transaction = connection.BeginTransaction(deferred: false);
            using (var saved = Command(connection, "SELECT 1 FROM saved_connections WHERE " + AccountPredicate, transaction))
            {
                BindAccount(saved, account);
                if (saved.ExecuteScalar() is null) throw BrowserPreferencesValidation.Unavailable();
            }
            using (var pending = Command(connection, "SELECT 1 FROM pending_logouts WHERE " + AccountPredicate, transaction))
            {
                BindAccount(pending, account);
                if (pending.ExecuteScalar() is not null) throw BrowserPreferencesValidation.Unavailable();
            }
            using var command = Command(connection, """
                INSERT INTO browser_preferences
                    (profile_id, source_revision, server_user_id, directory_identity, view_mode,
                     compact_grid_size, windows_icon_size, type_filter, sort_by, descending)
                VALUES ($profile, $revision, $user, $directory, $view, $grid, $icons, $filter, $sort, $descending)
                ON CONFLICT(profile_id, source_revision, server_user_id, directory_identity) DO UPDATE SET
                    view_mode = excluded.view_mode, compact_grid_size = excluded.compact_grid_size,
                    windows_icon_size = excluded.windows_icon_size, type_filter = excluded.type_filter,
                    sort_by = excluded.sort_by, descending = excluded.descending
                """, transaction);
            BindAccount(command, account);
            command.Parameters.AddWithValue("$directory", identity);
            command.Parameters.AddWithValue("$view", (int)preferences.ViewMode);
            command.Parameters.AddWithValue("$grid", (int)preferences.CompactGridSize);
            command.Parameters.AddWithValue("$icons", (int)preferences.WindowsIconSize);
            command.Parameters.AddWithValue("$filter", (int)preferences.TypeFilter);
            command.Parameters.AddWithValue("$sort", (int)preferences.SortBy);
            command.Parameters.AddWithValue("$descending", preferences.Descending ? 1 : 0);
            command.ExecuteNonQuery();
            cancellationToken.ThrowIfCancellationRequested();
            transaction.Commit();
            return true;
        }, cancellationToken);
}

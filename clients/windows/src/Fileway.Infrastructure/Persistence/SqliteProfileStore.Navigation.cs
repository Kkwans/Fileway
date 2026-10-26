using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

public sealed partial class SqliteProfileStore
{
    public Task<string?> ReadLastDirectoryAsync(AccountKey account, CancellationToken cancellationToken) => RunAsync(database =>
    {
        ProfileStoreValidation.Validate(account);
        using var command = Command(database, "SELECT wire_path FROM navigation_state WHERE " + AccountPredicate);
        BindAccount(command, account);
        return command.ExecuteScalar() as string;
    }, cancellationToken);

    public Task WriteLastDirectoryAsync(AccountKey account, string wirePath, CancellationToken cancellationToken) => RunAsync(database =>
    {
        ProfileStoreValidation.Validate(account);
        if (string.IsNullOrEmpty(wirePath) || !wirePath.StartsWith('/')) throw ProfileStoreValidation.Unavailable();
        using var command = Command(database, """
            INSERT INTO navigation_state (profile_id, source_revision, server_user_id, wire_path)
            SELECT $profile, $revision, $user, $path
            WHERE EXISTS (SELECT 1 FROM saved_connections WHERE profile_id = $profile AND source_revision = $revision AND server_user_id = $user)
              AND NOT EXISTS (SELECT 1 FROM pending_logouts WHERE profile_id = $profile AND source_revision = $revision AND server_user_id = $user)
            ON CONFLICT(profile_id, source_revision, server_user_id) DO UPDATE SET wire_path = excluded.wire_path
            """);
        BindAccount(command, account);
        command.Parameters.AddWithValue("$path", wirePath);
        if (command.ExecuteNonQuery() != 1) throw ProfileStoreValidation.Unavailable();
        return true;
    }, cancellationToken);
}

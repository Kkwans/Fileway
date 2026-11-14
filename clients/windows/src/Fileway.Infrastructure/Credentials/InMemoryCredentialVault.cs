using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Credentials;

/// <summary>Process-local token storage for callers that do not opt into persistence.</summary>
public sealed class InMemoryCredentialVault : ICredentialVault, IPasswordVault
{
    private readonly object _gate = new();
    private readonly Dictionary<AccountKey, string> _tokens = [];
    private readonly Dictionary<AccountKey, string> _passwords = [];

    public Task<string?> ReadTokenAsync(AccountKey account, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        CredentialValidation.Validate(account);
        lock (_gate) return Task.FromResult(_tokens.GetValueOrDefault(account));
    }

    public Task WriteTokenAsync(AccountKey account, string token, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (string.IsNullOrEmpty(token)) throw CredentialValidation.Unavailable();
        CredentialValidation.Validate(account);
        lock (_gate) _tokens[account] = token;
        return Task.CompletedTask;
    }

    public Task DeleteTokenAsync(AccountKey account, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        CredentialValidation.Validate(account);
        lock (_gate) _tokens.Remove(account);
        return Task.CompletedTask;
    }

    public override string ToString() => "InMemoryCredentialVault (credentials redacted)";

    public Task<string?> ReadPasswordAsync(AccountKey account, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested(); CredentialValidation.Validate(account);
        lock (_gate) return Task.FromResult(_passwords.GetValueOrDefault(account));
    }
    public Task WritePasswordAsync(AccountKey account, string password, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested(); CredentialValidation.Validate(account);
        if (string.IsNullOrEmpty(password)) throw CredentialValidation.Unavailable();
        lock (_gate) _passwords[account] = password;
        return Task.CompletedTask;
    }
    public Task DeletePasswordAsync(AccountKey account, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested(); CredentialValidation.Validate(account);
        lock (_gate) _passwords.Remove(account);
        return Task.CompletedTask;
    }
}

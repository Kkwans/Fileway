using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Credentials;

/// <summary>Process-local token storage for callers that do not opt into persistence.</summary>
public sealed class InMemoryCredentialVault : ICredentialVault
{
    private readonly object _gate = new();
    private readonly Dictionary<AccountKey, string> _tokens = [];

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
}

using System.Security;
using System.Security.Cryptography;
using System.Text;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Credentials;

/// <summary>Tokens encrypted by Windows DPAPI for the current Windows user and exact account.</summary>
public sealed class DpapiCredentialVault : ICredentialVault
{
    private const int MaximumTokenBytes = 1024 * 1024;
    private const int MaximumProtectedBytes = MaximumTokenBytes + 64 * 1024;
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);
    private readonly object _gate = new();
    private readonly string _directoryPath;

    public DpapiCredentialVault(string directoryPath)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(directoryPath)) throw CredentialValidation.Unavailable();
            _directoryPath = Path.GetFullPath(directoryPath);
        }
        catch (Exception error) when (IsCredentialFailure(error)) { throw CredentialValidation.Unavailable(); }
    }

    public Task<string?> ReadTokenAsync(AccountKey account, CancellationToken cancellationToken) => RunAsync(() =>
    {
        byte[] entropy = AccountEntropy(account);
        byte[]? encrypted = null;
        byte[]? plaintext = null;
        try
        {
            string path = TokenPath(entropy);
            try
            {
                using var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
                if (file.Length <= 0 || file.Length > MaximumProtectedBytes) throw CredentialValidation.Unavailable();
                encrypted = new byte[checked((int)file.Length)];
                file.ReadExactly(encrypted);
                if (file.ReadByte() != -1) throw CredentialValidation.Unavailable();
            }
            catch (FileNotFoundException) { return null; }
            catch (DirectoryNotFoundException) { return null; }
            cancellationToken.ThrowIfCancellationRequested();
            plaintext = ProtectedData.Unprotect(encrypted ?? throw CredentialValidation.Unavailable(), entropy, DataProtectionScope.CurrentUser);
            if (plaintext.Length == 0 || plaintext.Length > MaximumTokenBytes) throw CredentialValidation.Unavailable();
            return StrictUtf8.GetString(plaintext);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(entropy);
            if (encrypted is not null) CryptographicOperations.ZeroMemory(encrypted);
            if (plaintext is not null) CryptographicOperations.ZeroMemory(plaintext);
        }
    }, cancellationToken);

    public Task WriteTokenAsync(AccountKey account, string token, CancellationToken cancellationToken) => RunAsync(() =>
    {
        if (string.IsNullOrEmpty(token) || StrictUtf8.GetByteCount(token) > MaximumTokenBytes)
            throw CredentialValidation.Unavailable();
        byte[] entropy = AccountEntropy(account);
        byte[]? plaintext = null;
        byte[]? encrypted = null;
        string? temporaryPath = null;
        try
        {
            plaintext = StrictUtf8.GetBytes(token);
            encrypted = ProtectedData.Protect(plaintext, entropy, DataProtectionScope.CurrentUser);
            Directory.CreateDirectory(_directoryPath);
            string path = TokenPath(entropy);
            temporaryPath = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
            using (var file = new FileStream(temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                bufferSize: 4096, options: FileOptions.WriteThrough))
            {
                file.Write(encrypted);
                file.Flush(flushToDisk: true);
            }
            cancellationToken.ThrowIfCancellationRequested();
            // Same-directory rename replaces only this account's encrypted blob.
            File.Move(temporaryPath, path, overwrite: true);
            temporaryPath = null;
            return true;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(entropy);
            if (plaintext is not null) CryptographicOperations.ZeroMemory(plaintext);
            if (encrypted is not null) CryptographicOperations.ZeroMemory(encrypted);
            if (temporaryPath is not null)
            {
                try { File.Delete(temporaryPath); }
                catch (Exception error) when (IsCredentialFailure(error)) { /* Never mask the original outcome. The temporary blob is encrypted. */ }
            }
        }
    }, cancellationToken);

    public Task DeleteTokenAsync(AccountKey account, CancellationToken cancellationToken) => RunAsync(() =>
    {
        byte[] entropy = AccountEntropy(account);
        try
        {
            try { File.Delete(TokenPath(entropy)); }
            catch (DirectoryNotFoundException) { /* Already absent. */ }
            return true;
        }
        finally { CryptographicOperations.ZeroMemory(entropy); }
    }, cancellationToken);

    private Task<T> RunAsync<T>(Func<T> operation, CancellationToken cancellationToken) => Task.Run(() =>
    {
        lock (_gate)
        {
            cancellationToken.ThrowIfCancellationRequested();
            try { return operation(); }
            catch (Exception error) when (IsCredentialFailure(error)) { throw CredentialValidation.Unavailable(); }
        }
    }, cancellationToken);

    private static byte[] AccountEntropy(AccountKey account)
    {
        CredentialValidation.Validate(account);
        // The two fixed-length GUID fields delimit the final opaque server user ID unambiguously.
        return StrictUtf8.GetBytes($"Fileway.credentials.v1\n{account.ProfileId:D}\n{account.SourceRevision:D}\n{account.ServerUserId}");
    }

    private string TokenPath(byte[] entropy)
    {
        byte[] digest = SHA256.HashData(entropy);
        try { return Path.Combine(_directoryPath, Convert.ToHexString(digest) + ".dpapi"); }
        finally { CryptographicOperations.ZeroMemory(digest); }
    }

    private static bool IsCredentialFailure(Exception error) => error is IOException or UnauthorizedAccessException or
        SecurityException or CryptographicException or ArgumentException or InvalidOperationException or NotSupportedException or OverflowException;

    public override string ToString() => "DpapiCredentialVault (location and credentials redacted)";
}

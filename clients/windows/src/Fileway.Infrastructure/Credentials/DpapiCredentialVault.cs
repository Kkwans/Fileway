using System.Security;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Credentials;

/// <summary>Separate token and password secrets encrypted by DPAPI for the current Windows user and exact account.</summary>
public sealed class DpapiCredentialVault : ICredentialVault, IPasswordVault
{
    private const int MaximumTokenBytes = 1024 * 1024;
    private const int MaximumPasswordBytes = 64 * 1024;
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

    public Task<string?> ReadTokenAsync(AccountKey account, CancellationToken cancellationToken) => ReadAsync(account, false, cancellationToken);
    public Task WriteTokenAsync(AccountKey account, string token, CancellationToken cancellationToken) => WriteAsync(account, token, false, cancellationToken);
    public Task DeleteTokenAsync(AccountKey account, CancellationToken cancellationToken) => DeleteAsync(account, false, cancellationToken);
    public Task<string?> ReadPasswordAsync(AccountKey account, CancellationToken cancellationToken) => ReadAsync(account, true, cancellationToken);
    public Task WritePasswordAsync(AccountKey account, string password, CancellationToken cancellationToken) => WriteAsync(account, password, true, cancellationToken);
    public Task DeletePasswordAsync(AccountKey account, CancellationToken cancellationToken) => DeleteAsync(account, true, cancellationToken);

    private Task<string?> ReadAsync(AccountKey account, bool password, CancellationToken cancellationToken) => RunAsync(() =>
    {
        byte[] entropy = AccountEntropy(account, password);
        byte[]? encrypted = null;
        byte[]? plaintext = null;
        try
        {
            string path = SecretPath(entropy, password);
            if (password && Directory.Exists(Path.GetDirectoryName(path))) PreparePasswordDirectory();
            if (password && File.Exists(path) && (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0) throw CredentialValidation.Unavailable();
            try
            {
                using var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
                if (file.Length <= 0 || file.Length > MaximumBytes(password) + 64 * 1024) throw CredentialValidation.Unavailable();
                encrypted = new byte[checked((int)file.Length)];
                file.ReadExactly(encrypted);
                if (file.ReadByte() != -1) throw CredentialValidation.Unavailable();
            }
            catch (FileNotFoundException) { return null; }
            catch (DirectoryNotFoundException) { return null; }
            cancellationToken.ThrowIfCancellationRequested();
            plaintext = ProtectedData.Unprotect(encrypted ?? throw CredentialValidation.Unavailable(), entropy, DataProtectionScope.CurrentUser);
            if (plaintext.Length == 0 || plaintext.Length > MaximumBytes(password)) throw CredentialValidation.Unavailable();
            return StrictUtf8.GetString(plaintext);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(entropy);
            if (encrypted is not null) CryptographicOperations.ZeroMemory(encrypted);
            if (plaintext is not null) CryptographicOperations.ZeroMemory(plaintext);
        }
    }, cancellationToken);

    private Task<bool> WriteAsync(AccountKey account, string secret, bool password, CancellationToken cancellationToken) => RunAsync(() =>
    {
        if (string.IsNullOrEmpty(secret) || StrictUtf8.GetByteCount(secret) > MaximumBytes(password))
            throw CredentialValidation.Unavailable();
        byte[] entropy = AccountEntropy(account, password);
        byte[]? plaintext = null;
        byte[]? encrypted = null;
        string? temporaryPath = null;
        try
        {
            plaintext = StrictUtf8.GetBytes(secret);
            encrypted = ProtectedData.Protect(plaintext, entropy, DataProtectionScope.CurrentUser);
            Directory.CreateDirectory(_directoryPath);
            if (password) PreparePasswordDirectory();
            string path = SecretPath(entropy, password);
            if (password && File.Exists(path) && (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0) throw CredentialValidation.Unavailable();
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

    private Task<bool> DeleteAsync(AccountKey account, bool password, CancellationToken cancellationToken) => RunAsync(() =>
    {
        byte[] entropy = AccountEntropy(account, password);
        try
        {
            if (password && Directory.Exists(Path.Combine(_directoryPath, "passwords"))) PreparePasswordDirectory();
            try { File.Delete(SecretPath(entropy, password)); }
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

    private static int MaximumBytes(bool password) => password ? MaximumPasswordBytes : MaximumTokenBytes;

    private void PreparePasswordDirectory()
    {
        var directory = new DirectoryInfo(Path.Combine(_directoryPath, "passwords"));
        using var identity = WindowsIdentity.GetCurrent();
        var user = identity.User ?? throw CredentialValidation.Unavailable();
        var access = new DirectorySecurity();
        access.SetOwner(user);
        access.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
        access.AddAccessRule(new FileSystemAccessRule(user, FileSystemRights.FullControl,
            InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
        directory.Create(access);
        directory.Refresh();
        if ((directory.Attributes & FileAttributes.ReparsePoint) != 0 ||
            !user.Equals(directory.GetAccessControl().GetOwner(typeof(SecurityIdentifier)))) throw CredentialValidation.Unavailable();
        directory.SetAccessControl(access);
    }

    private static byte[] AccountEntropy(AccountKey account, bool password)
    {
        CredentialValidation.Validate(account);
        // The two fixed-length GUID fields delimit the final opaque server user ID unambiguously.
        var purpose = password ? "Fileway.passwords.v1" : "Fileway.credentials.v1";
        return StrictUtf8.GetBytes($"{purpose}\n{account.ProfileId:D}\n{account.SourceRevision:D}\n{account.ServerUserId}");
    }

    private string SecretPath(byte[] entropy, bool password)
    {
        byte[] digest = SHA256.HashData(entropy);
        try { return Path.Combine(password ? Path.Combine(_directoryPath, "passwords") : _directoryPath, Convert.ToHexString(digest) + ".dpapi"); }
        finally { CryptographicOperations.ZeroMemory(digest); }
    }

    private static bool IsCredentialFailure(Exception error) => error is IOException or UnauthorizedAccessException or
        SecurityException or CryptographicException or ArgumentException or InvalidOperationException or NotSupportedException or OverflowException;

    public override string ToString() => "DpapiCredentialVault (location and credentials redacted)";
}

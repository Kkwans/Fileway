using System.Text;
using Fileway.Core.Contracts;
using Fileway.Infrastructure.Credentials;
using Fileway.Infrastructure.Tests.Persistence;

namespace Fileway.Infrastructure.Tests.Credentials;

public sealed class DpapiCredentialVaultTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private const string FixtureToken = "fixture.only.token-with-unicode-测试";

    [Fact]
    public async Task TokenSurvivesReopenAsCiphertextAndTamperingFailsClosed()
    {
        using var directory = new StorageTestDirectory();
        var account = new AccountKey(Guid.NewGuid(), Guid.NewGuid(), "7");
        await new DpapiCredentialVault(directory.VaultPath).WriteTokenAsync(account, FixtureToken, Token);
        var reopened = new DpapiCredentialVault(directory.VaultPath);
        Assert.Equal(FixtureToken, await reopened.ReadTokenAsync(account, Token));

        var path = Assert.Single(Directory.GetFiles(directory.VaultPath));
        var encrypted = await File.ReadAllBytesAsync(path, Token);
        Assert.Equal(-1, encrypted.AsSpan().IndexOf(Encoding.UTF8.GetBytes(FixtureToken)));
        encrypted[encrypted.Length / 2] ^= 0x40;
        await File.WriteAllBytesAsync(path, encrypted, Token);

        var failure = await Assert.ThrowsAsync<FilewayException>(() => reopened.ReadTokenAsync(account, Token));
        Assert.Equal(ErrorCode.CredentialsUnavailable, failure.Error.Code);
        Assert.DoesNotContain(FixtureToken, failure.ToString());
    }

    [Fact]
    public async Task CopyingCiphertextToAnotherAccountCannotCrossAnyAccountKeyBoundary()
    {
        using var directory = new StorageTestDirectory();
        var account = new AccountKey(Guid.NewGuid(), Guid.NewGuid(), "7");
        var vault = new DpapiCredentialVault(directory.VaultPath);
        await vault.WriteTokenAsync(account, FixtureToken, Token);
        var originalPath = Assert.Single(Directory.GetFiles(directory.VaultPath));
        var original = await File.ReadAllBytesAsync(originalPath, Token);
        AccountKey[] otherAccounts =
        [
            account with { ProfileId = Guid.NewGuid() },
            account with { SourceRevision = Guid.NewGuid() },
            account with { ServerUserId = "8" },
        ];

        foreach (var other in otherAccounts)
        {
            Assert.Null(await vault.ReadTokenAsync(other, Token));
            var before = Directory.GetFiles(directory.VaultPath);
            await vault.WriteTokenAsync(other, "fixture.other.token", Token);
            var otherPath = Assert.Single(Directory.GetFiles(directory.VaultPath).Except(before, StringComparer.OrdinalIgnoreCase));
            await File.WriteAllBytesAsync(otherPath, original, Token);

            var failure = await Assert.ThrowsAsync<FilewayException>(() =>
                new DpapiCredentialVault(directory.VaultPath).ReadTokenAsync(other, Token));
            Assert.Equal(ErrorCode.CredentialsUnavailable, failure.Error.Code);
        }
        Assert.Equal(FixtureToken, await vault.ReadTokenAsync(account, Token));
    }
}

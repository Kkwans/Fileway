using Fileway.App;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Tests.Files;

public sealed class FileChecksumControllerTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Theory]
    [InlineData(ChecksumAlgorithm.Md5, 32)]
    [InlineData(ChecksumAlgorithm.Sha1, 40)]
    [InlineData(ChecksumAlgorithm.Sha256, 64)]
    [InlineData(ChecksumAlgorithm.Sha512, 128)]
    public async Task EachAlgorithmPreservesOriginalResourceAndNormalizesOnlyExpectedHex(ChecksumAlgorithm algorithm, int length)
    {
        using var f = new Fixture();
        var result = f.Result(algorithm, new string('a', length), ChecksumComparison.Matches);
        f.Service.Behavior = (_, _, _, _) => Task.FromResult(result);
        await f.Controller.CalculateAsync(algorithm, "  " + new string('A', length) + "  ", Token);
        var call = Assert.Single(f.Service.Calls);
        Assert.Same(f.Source, call.Source); Assert.Equal(algorithm, call.Algorithm);
        Assert.Equal(new string('a', length), call.Expected);
        Assert.Same(result, f.Controller.Result); Assert.Null(f.Controller.Error); Assert.False(f.Controller.IsCalculating);
    }

    [Theory]
    [InlineData(null, ChecksumComparison.NotRequested)]
    [InlineData("", ChecksumComparison.NotRequested)]
    [InlineData(" \t ", ChecksumComparison.NotRequested)]
    [InlineData("a", ChecksumComparison.Matches)]
    [InlineData("b", ChecksumComparison.Differs)]
    public async Task ComparisonIsTruthfulForOptionalExpectedValue(string? expected, ChecksumComparison comparison)
    {
        using var f = new Fixture();
        f.Service.Behavior = (_, _, _, _) => Task.FromResult(f.Result(comparison: comparison));
        await f.Controller.CalculateAsync(ChecksumAlgorithm.Sha256, expected is "a" or "b" ? new string(expected[0], 64) : expected, Token);
        Assert.Equal(comparison, f.Controller.Result!.Comparison);
    }

    [Theory]
    [InlineData(ChecksumAlgorithm.Md5, "a")]
    [InlineData(ChecksumAlgorithm.Sha1, "xyz")]
    [InlineData(ChecksumAlgorithm.Sha256, "../file")]
    [InlineData(ChecksumAlgorithm.Sha512, "g")]
    [InlineData((ChecksumAlgorithm)99, null)]
    public async Task InvalidExpectedOrAlgorithmNeverCallsService(ChecksumAlgorithm algorithm, string? expected)
    {
        using var f = new Fixture();
        await f.Controller.CalculateAsync(algorithm, expected, Token);
        Assert.Empty(f.Service.Calls); Assert.Null(f.Controller.Result); Assert.NotNull(f.Controller.Error); Assert.False(f.Controller.IsCalculating);
    }

    [Theory]
    [InlineData(ChecksumAlgorithm.Md5, 32)]
    [InlineData(ChecksumAlgorithm.Sha1, 40)]
    [InlineData(ChecksumAlgorithm.Sha256, 64)]
    [InlineData(ChecksumAlgorithm.Sha512, 128)]
    public void ExpectedFeedbackAcceptsOnlyMatchingHexLength(ChecksumAlgorithm algorithm, int length)
    {
        Assert.True(FileChecksumController.TryExpectedDigest(algorithm, null, out var none)); Assert.Null(none);
        Assert.True(FileChecksumController.TryExpectedDigest(algorithm, new string('A', length), out var value)); Assert.Equal(new string('a', length), value);
        Assert.False(FileChecksumController.TryExpectedDigest(algorithm, new string('a', length - 1), out _));
        Assert.False(FileChecksumController.TryExpectedDigest(algorithm, new string('a', length + 1), out _));
        Assert.False(FileChecksumController.TryExpectedDigest(algorithm, new string('g', length), out _));
        Assert.False(FileChecksumController.TryExpectedDigest(algorithm, new string('a', length - 1) + "\0", out _));
    }

    [Fact]
    public void ContextSettingAndSelectionNeverCalculateAutomatically()
    {
        using var f = new Fixture();
        f.Controller.SetContext(f.Service, f.Source, f.Session);
        f.Controller.SetContext(f.Service, f.Source with { WirePath = "/another" }, f.Session);
        f.Controller.Cancel(); Assert.Empty(f.Service.Calls); Assert.Null(f.Controller.Result);
    }

    [Theory]
    [InlineData("permission")]
    [InlineData("account")]
    [InlineData("none")]
    public async Task MissingAuthorityCannotCalculate(string reason)
    {
        using var f = new Fixture();
        f.Controller.SetContext(f.Service, reason == "account" ? f.Source with { Account = f.Source.Account with { ServerUserId = "other" } } : f.Source,
            reason == "none" ? null : reason == "permission" ? f.Session with { Permissions = f.Session.Permissions with { Download = false } } : f.Session);
        await f.Controller.CalculateAsync(ChecksumAlgorithm.Sha256, null, Token); Assert.Empty(f.Service.Calls); Assert.Null(f.Controller.Result);
    }

    [Theory]
    [InlineData("account")]
    [InlineData("wire-case")]
    [InlineData("wire-encoding")]
    [InlineData("version")]
    [InlineData("algorithm")]
    [InlineData("length")]
    [InlineData("hex")]
    [InlineData("null-digest")]
    [InlineData("size")]
    [InlineData("comparison")]
    [InlineData("null-source")]
    [InlineData("null-result")]
    public async Task InvalidResultsNeverBecomePresentedProof(string reason)
    {
        using var f = new Fixture(); var result = f.Result();
        result = reason switch
        {
            "account" => result with { Resource = f.Source with { Account = f.Source.Account with { ServerUserId = "other" } } },
            "wire-case" => result with { Resource = f.Source with { WirePath = "/OPAQUE%2F+name" } },
            "wire-encoding" => result with { Resource = f.Source with { WirePath = "/opaque/+name" } },
            "version" => result with { Resource = f.Source with { SourceVersion = "other" } },
            "algorithm" => result with { Algorithm = ChecksumAlgorithm.Md5 },
            "length" => result with { Digest = "a" }, "hex" => result with { Digest = new string('g', 64) },
            "null-digest" => result with { Digest = null! }, "size" => result with { Size = -1 },
            "comparison" => result with { Comparison = ChecksumComparison.Matches },
            "null-source" => result with { Resource = null! }, "null-result" => null!, _ => result,
        };
        f.Service.Behavior = (_, _, _, _) => Task.FromResult(result);
        await f.Controller.CalculateAsync(ChecksumAlgorithm.Sha256, null, Token);
        Assert.Null(f.Controller.Result); Assert.Contains("不一致", f.Controller.Error!, StringComparison.Ordinal); Assert.False(f.Controller.IsCalculating);
    }

    [Fact]
    public async Task WrongMatchComparisonIsRejectedEvenWhenDigestFormatIsValid()
    {
        using var f = new Fixture();
        f.Service.Behavior = (_, _, _, _) => Task.FromResult(f.Result(comparison: ChecksumComparison.Matches));
        await f.Controller.CalculateAsync(ChecksumAlgorithm.Sha256, new string('b', 64), Token);
        Assert.Null(f.Controller.Result); Assert.NotNull(f.Controller.Error);
    }

    [Theory]
    [InlineData("service")]
    [InlineData("resource")]
    [InlineData("session")]
    [InlineData("account")]
    [InlineData("permission")]
    [InlineData("none")]
    [InlineData("cancel")]
    [InlineData("dispose")]
    public async Task ReplacementAndClosureRejectLateSuccessAndSignalOriginalCancellation(string reason)
    {
        using var f = new Fixture(); var deferred = Deferred<FileChecksumResult>();
        f.Service.Behavior = (_, _, _, _) => deferred.Task;
        var pending = f.Controller.CalculateAsync(ChecksumAlgorithm.Sha256, null, Token);
        Assert.True(f.Controller.IsCalculating); Retire(f, reason);
        Assert.True(Assert.Single(f.Service.Calls).Token.IsCancellationRequested);
        deferred.SetResult(f.Result()); await pending;
        Assert.Null(f.Controller.Result); Assert.Null(f.Controller.Error); Assert.False(f.Controller.IsCalculating);
    }

    [Theory]
    [InlineData("session")]
    [InlineData("cancel")]
    [InlineData("dispose")]
    public async Task LatePrivateErrorsCannotReachReplacementView(string reason)
    {
        using var f = new Fixture(); var deferred = Deferred<FileChecksumResult>();
        f.Service.Behavior = (_, _, _, _) => deferred.Task;
        var pending = f.Controller.CalculateAsync(ChecksumAlgorithm.Sha256, null, Token);
        Retire(f, reason); deferred.SetException(new InvalidOperationException("private remote URL")); await pending;
        Assert.Null(f.Controller.Error); Assert.Null(f.Controller.Result);
    }

    [Fact]
    public async Task NewExplicitRequestOwnsResultAndBusyStateWhenOldRequestCompletesLate()
    {
        using var f = new Fixture(); var first = Deferred<FileChecksumResult>(); var second = Deferred<FileChecksumResult>();
        f.Service.Behavior = (_, _, _, _) => f.Service.Calls.Count == 1 ? first.Task : second.Task;
        var old = f.Controller.CalculateAsync(ChecksumAlgorithm.Sha256, null, Token);
        var current = f.Controller.CalculateAsync(ChecksumAlgorithm.Sha256, null, Token);
        Assert.True(f.Service.Calls[0].Token.IsCancellationRequested);
        first.SetResult(f.Result()); await old; Assert.True(f.Controller.IsCalculating); Assert.Null(f.Controller.Result);
        var result = f.Result() with { Size = 99 }; second.SetResult(result); await current;
        Assert.Same(result, f.Controller.Result); Assert.False(f.Controller.IsCalculating);
    }

    [Fact]
    public async Task SameSessionMetadataUpdateDoesNotRetireAnUnchangedSource()
    {
        using var f = new Fixture(); var deferred = Deferred<FileChecksumResult>(); f.Service.Behavior = (_, _, _, _) => deferred.Task;
        var pending = f.Controller.CalculateAsync(ChecksumAlgorithm.Sha256, null, Token);
        f.Controller.SetContext(f.Service, f.Source, f.Session with { DisplayName = "updated" });
        Assert.False(Assert.Single(f.Service.Calls).Token.IsCancellationRequested);
        var result = f.Result(); deferred.SetResult(result); await pending; Assert.Same(result, f.Controller.Result);
    }

    [Fact]
    public async Task CallerCancellationCannotPublishResultEvenIfProviderIgnoresToken()
    {
        using var f = new Fixture(); using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var deferred = Deferred<FileChecksumResult>(); f.Service.Behavior = (_, _, _, _) => deferred.Task;
        var pending = f.Controller.CalculateAsync(ChecksumAlgorithm.Sha256, null, caller.Token);
        await caller.CancelAsync(); deferred.SetResult(f.Result()); await pending;
        Assert.Null(f.Controller.Result); Assert.Null(f.Controller.Error); Assert.False(f.Controller.IsCalculating);
    }

    [Theory]
    [InlineData(ErrorCode.PermissionDenied, 403, "权限")]
    [InlineData(ErrorCode.SourceChanged, 404, "不存在")]
    [InlineData(ErrorCode.Conflict, 409, "已变化")]
    [InlineData(ErrorCode.AuthenticationExpired, 401, "登录")]
    [InlineData(ErrorCode.Timeout, null, "超时")]
    [InlineData(ErrorCode.NetworkUnavailable, null, "网络")]
    public async Task ReadErrorsAreSafeAndSpecific(ErrorCode code, int? status, string expected)
    {
        using var f = new Fixture();
        f.Service.Behavior = (_, _, _, _) => throw new FilewayException(new(code, "private path and token", status));
        await f.Controller.CalculateAsync(ChecksumAlgorithm.Sha256, null, Token);
        Assert.Contains(expected, f.Controller.Error!, StringComparison.Ordinal); Assert.DoesNotContain("private", f.Controller.Error!, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ThrowingCancellationCallbackNeverEscapesOrPublishesLateResult()
    {
        using var f = new Fixture(); var deferred = Deferred<FileChecksumResult>(); var called = Deferred();
        CancellationTokenRegistration registration = default;
        f.Service.Behavior = (_, _, _, token) =>
        { registration = token.Register(() => { called.SetResult(); throw new InvalidOperationException("private callback"); }); return deferred.Task; };
        var pending = f.Controller.CalculateAsync(ChecksumAlgorithm.Sha256, null, Token);
        f.Controller.Cancel(); await called.Task.WaitAsync(Token);
        deferred.SetResult(f.Result()); await pending; await registration.DisposeAsync();
        Assert.Null(f.Controller.Error); Assert.Null(f.Controller.Result);
    }

    [Fact]
    public async Task BlockingCancellationCallbackCannotBlockCloseOrRequestCompletion()
    {
        using var f = new Fixture(); var deferred = Deferred<FileChecksumResult>(); var entered = Deferred(); var release = Deferred();
        CancellationTokenRegistration registration = default;
        f.Service.Behavior = (_, _, _, token) =>
        { registration = token.Register(() => { entered.SetResult(); release.Task.GetAwaiter().GetResult(); }); return deferred.Task; };
        var pending = f.Controller.CalculateAsync(ChecksumAlgorithm.Sha256, null, Token);
        try
        {
            await Task.Run(f.Controller.Dispose, Token).WaitAsync(TimeSpan.FromSeconds(2), Token);
            await entered.Task.WaitAsync(Token); deferred.SetResult(f.Result()); await pending.WaitAsync(TimeSpan.FromSeconds(2), Token);
            Assert.Null(f.Controller.Result); Assert.Null(f.Controller.Error);
        }
        finally { release.TrySetResult(); await registration.DisposeAsync(); }
    }

    private static void Retire(Fixture f, string reason)
    {
        if (reason == "cancel") f.Controller.Cancel();
        else if (reason == "dispose") f.Controller.Dispose();
        else f.Controller.SetContext(reason == "service" ? new FakeService() : f.Service,
            reason == "resource" ? f.Source with { WirePath = "/another" } : f.Source,
            reason switch
            {
                "none" => null, "session" => f.Session with { Id = Guid.NewGuid() },
                "account" => f.Session with { Account = f.Session.Account with { ServerUserId = "replacement" } },
                "permission" => f.Session with { Permissions = f.Session.Permissions with { Download = false } }, _ => f.Session,
            });
    }
    private static TaskCompletionSource<T> Deferred<T>() => new(TaskCreationOptions.RunContinuationsAsynchronously);
    private static TaskCompletionSource Deferred() => new(TaskCreationOptions.RunContinuationsAsynchronously);
    private sealed class Fixture : IDisposable
    {
        internal FakeService Service { get; } = new();
        internal FileChecksumController Controller { get; } = new();
        internal ConnectionSession Session { get; } = new(Guid.NewGuid(), new(Guid.NewGuid(), Guid.NewGuid(), "fixture"), "fixture", new(true, true, true, true, true, true), new());
        internal RemoteResourceRef Source { get; }
        internal Fixture() { Source = new(Session.Account, "/opaque%2F+name", "fixture", "/display/fixture"); Controller.SetContext(Service, Source, Session); }
        internal FileChecksumResult Result(ChecksumAlgorithm algorithm = ChecksumAlgorithm.Sha256, string? digest = null, ChecksumComparison comparison = ChecksumComparison.NotRequested) =>
            new(Source, algorithm, digest ?? new string('a', 64), comparison, 42, DateTimeOffset.UnixEpoch);
        public void Dispose() => Controller.Dispose();
    }
    private sealed class FakeService : IFileChecksumService
    {
        internal Func<RemoteResourceRef, ChecksumAlgorithm, string?, CancellationToken, Task<FileChecksumResult>>? Behavior { get; set; }
        internal List<(RemoteResourceRef Source, ChecksumAlgorithm Algorithm, string? Expected, CancellationToken Token)> Calls { get; } = [];
        public Task<FileChecksumResult> CalculateChecksumAsync(RemoteResourceRef resource, ChecksumAlgorithm algorithm, string? expectedDigest, CancellationToken cancellationToken)
        { Calls.Add((resource, algorithm, expectedDigest, cancellationToken)); return Behavior!(resource, algorithm, expectedDigest, cancellationToken); }
    }
}

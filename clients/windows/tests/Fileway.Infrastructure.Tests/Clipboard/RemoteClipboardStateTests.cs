using Fileway.App;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Tests.Clipboard;

public sealed class RemoteClipboardStateTests
{
    private static readonly ConnectionSession Session = new(Guid.NewGuid(), new(Guid.NewGuid(), Guid.NewGuid(), "user"), "test", new(true, true, true, true, true, false), new());
    private static RemoteResourceRef Resource(string path) => new(Session.Account, path, "display", "/display");
    [Fact]
    public void FrozenSourcesKeepOpaqueWireIdentityAndIgnoreCallerArrayReplacement()
    {
        var state = new RemoteClipboardState(); var sources = new[] { Resource("/x%2Fy"), Resource("/x%2fy") };
        var snapshot = state.Capture(Session, FileTransferAction.Copy, sources); sources[0] = Resource("/replaced");
        Assert.Equal("/x%2Fy", snapshot.Sources[0].WirePath); Assert.Equal("/x%2fy", snapshot.Sources[1].WirePath);
        Assert.DoesNotContain("x%2F", snapshot.Marker, StringComparison.Ordinal);
        Assert.Same(snapshot, state.Resolve(snapshot.Marker, Session));
    }
    [Fact]
    public void ChangedSessionAccountSourceAndForeignMarkerNeverAuthorizePaste()
    {
        var state = new RemoteClipboardState(); var snapshot = state.Capture(Session, FileTransferAction.Move, [Resource("/file")]);
        Assert.Null(state.Resolve(snapshot.Marker, Session with { Id = Guid.NewGuid() }));
        Assert.Null(state.Resolve(snapshot.Marker, Session with { Account = Session.Account with { ServerUserId = "other" } }));
        Assert.Null(state.Resolve(snapshot.Marker, Session with { Account = Session.Account with { SourceRevision = Guid.NewGuid() } }));
        Assert.Null(state.Resolve("foreign", Session)); Assert.Null(state.Resolve(snapshot.Marker, null));
    }
    [Fact]
    public void ReplacementOrClearRetiresOriginalAuthority()
    {
        var state = new RemoteClipboardState(); var first = state.Capture(Session, FileTransferAction.Copy, [Resource("/a")]);
        var next = state.Capture(Session, FileTransferAction.Copy, [Resource("/b")]);
        Assert.False(state.IsCurrent(first, Session)); Assert.Null(state.Resolve(first.Marker, Session));
        Assert.True(state.IsCurrent(next, Session)); state.Clear(); Assert.Null(state.Resolve(next.Marker, Session));
    }
    [Theory]
    [InlineData(0, false)] [InlineData(500, true)] [InlineData(501, false)]
    public void SourceCountIsBounded(int count, bool allowed)
    {
        var state = new RemoteClipboardState(); var rows = Enumerable.Range(0, count).Select(i => Resource("/" + i));
        if (allowed) Assert.Equal(count, state.Capture(Session, FileTransferAction.Copy, rows).Sources.Count);
        else Assert.Throws<ArgumentException>(() => state.Capture(Session, FileTransferAction.Copy, rows));
    }
    [Theory]
    [InlineData("/")] [InlineData("relative")] [InlineData("/bad\0name")]
    public void InvalidOrRootSourcesCannotEnterClipboard(string path)
    {
        var state = new RemoteClipboardState(); Assert.Throws<ArgumentException>(() => state.Capture(Session, FileTransferAction.Copy, [Resource(path)]));
    }
    [Fact]
    public void ForeignAndDuplicateSourcesAreRejected()
    {
        var state = new RemoteClipboardState(); var resource = Resource("/file");
        Assert.Throws<ArgumentException>(() => state.Capture(Session, FileTransferAction.Copy, [resource, resource]));
        Assert.Throws<ArgumentException>(() => state.Capture(Session, FileTransferAction.Copy, [resource with { Account = Session.Account with { ServerUserId = "foreign" } }]));
    }
}

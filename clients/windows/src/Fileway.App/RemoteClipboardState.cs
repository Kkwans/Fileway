using Fileway.Core.Contracts;

namespace Fileway.App;

internal sealed record RemoteClipboardSnapshot(string Marker, AccountKey Account, Guid SessionId,
    FileTransferAction Action, IReadOnlyList<RemoteResourceRef> Sources)
{
    public override string ToString() => "RemoteClipboardSnapshot (source details redacted)";
}

/// <summary>UI-thread-owned authority. The OS clipboard contains only a random marker.</summary>
internal sealed class RemoteClipboardState
{
    internal RemoteClipboardSnapshot? Current { get; private set; }
    internal RemoteClipboardSnapshot Capture(ConnectionSession session, FileTransferAction action, IEnumerable<RemoteResourceRef> sources)
    {
        ArgumentNullException.ThrowIfNull(session); ArgumentNullException.ThrowIfNull(sources);
        var rows = sources.Take(501).ToArray();
        if (session.Id == Guid.Empty || !Enum.IsDefined(action) || rows.Length is 0 or > 500 ||
            rows.Any(row => row is null || row.Account != session.Account || row.WirePath is not { Length: > 1 } ||
                !row.WirePath.StartsWith('/') || row.WirePath.Contains('\0')) ||
            rows.Select(row => row.WirePath).Distinct(StringComparer.Ordinal).Count() != rows.Length)
            throw new ArgumentException("请选择不超过 500 个当前账号的不同文件或文件夹。", nameof(sources));
        return Current = new(Guid.NewGuid().ToString("N"), session.Account, session.Id, action, Array.AsReadOnly(rows));
    }
    internal RemoteClipboardSnapshot? Resolve(string? marker, ConnectionSession? session) => Current is { } current &&
        session is not null && current.Account == session.Account && current.SessionId == session.Id &&
        string.Equals(current.Marker, marker, StringComparison.Ordinal) ? current : null;
    internal bool IsCurrent(RemoteClipboardSnapshot snapshot, ConnectionSession? session) =>
        ReferenceEquals(Current, snapshot) && Resolve(snapshot.Marker, session) is not null;
    internal void Clear() => Current = null;
}

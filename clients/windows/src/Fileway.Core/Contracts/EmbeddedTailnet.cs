namespace Fileway.Core.Contracts;

public enum EmbeddedTailnetState
{
    Unconfigured, Configured, Stopped, Starting, NeedsLogin, NeedsApproval, Running, Unavailable,
}

public sealed record EmbeddedTailnetStatus(EmbeddedTailnetState State, bool Authenticated, Uri? ApprovalUri,
    IReadOnlyList<string> Addresses, bool AcceptSubnets)
{
    public override string ToString() => "EmbeddedTailnetStatus (private addresses and authorization URL redacted)";
}

public interface IEmbeddedTailnetService
{
    Task<EmbeddedTailnetStatus> ReadTailnetAsync(CancellationToken cancellationToken);
    Task<EmbeddedTailnetStatus> StartTailnetAsync(CancellationToken cancellationToken);
    Task<EmbeddedTailnetStatus> StopTailnetAsync(CancellationToken cancellationToken);
    Task<EmbeddedTailnetStatus> LogoutTailnetAsync(CancellationToken cancellationToken);
}

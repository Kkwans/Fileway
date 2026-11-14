using System.Text.Json;
using System.Text.Json.Serialization;
using Fileway.Core.Contracts;

namespace Fileway.Core.Integration;

/// <summary>Infrastructure boundary. Views use typed application services instead of sending core commands.</summary>
public static class HostProtocol
{
    public const int Major = 1;
    public const int Minor = 0;
    public const int MaxFrameBytes = 16 * 1024 * 1024;
    public const int MaxCoreCommandBytes = 1024 * 1024;
}

public enum HostCallSafety
{
    ReadOnly,
    Mutation,
    Control,
}

/// <summary>Production uses the fixed, co-located executable and build manifest; no profile can select a Host.</summary>
public sealed record HostClientOptions(
    string ExecutablePath,
    string ManifestPath,
    TimeSpan? StartupTimeout = null,
    TimeSpan? ShutdownTimeout = null);

/// <summary>Safety can make a call more conservative, but must never weaken a known HTTP mutation.</summary>
public sealed record HostCall(
    string Operation,
    [property: JsonIgnore] JsonElement? Parameters = null,
    string? Session = null,
    long Generation = 0,
    HostCallSafety Safety = HostCallSafety.ReadOnly,
    TimeSpan? Timeout = null)
{
    public override string ToString() => "HostCall (payload redacted)";
}

public sealed record HostError(string Code, string Message, bool Retryable, int? HttpStatus = null);

/// <summary>Result owns its JSON data; it remains valid after the transport reader disposes its frame.</summary>
public sealed record HostReply(
    string RequestId,
    long Generation,
    bool IsSuccess,
    [property: JsonIgnore] JsonElement? Result,
    HostError? Error)
{
    public override string ToString() => "HostReply (payload redacted)";
}

public sealed record HostHandshake(
    int ProtocolMajor,
    int ProtocolMinor,
    int CoreProtocol,
    string HostVersion,
    string BuildCommit,
    string GoVersion,
    JsonElement Metadata);

public sealed record HostExit(bool Expected, int? ExitCode, ErrorInfo? Error);

public interface IHostControlClient : IAsyncDisposable
{
    HostHandshake Handshake { get; }
    Task<HostExit> Completion { get; }

    /// <summary>
    /// Cancellation requests cooperative cancellation and preserves a confirmed success.
    /// Losing an observed response to a dispatched mutation produces OutcomeUnknown; it never replays.
    /// </summary>
    Task<HostReply> CallAsync(HostCall request, CancellationToken cancellationToken);
    Task ShutdownAsync(CancellationToken cancellationToken);
}

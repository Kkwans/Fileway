namespace Fileway.Core.Contracts;

/// <summary>Observable lifecycle of the server instance owned by this client.</summary>
public enum LocalServerState
{
    /// <summary>No database or account exists yet in the managed deployment.</summary>
    NotDeployed,

    /// <summary>Deployed but no matching process is serving the configured port.</summary>
    Stopped,

    Starting,
    Running,
    Stopping,

    /// <summary>The managed process left with a failure and is not serving.</summary>
    Failed,

    /// <summary>The port or database is held by a process this client did not launch.</summary>
    OccupiedByOther,

    /// <summary>The bundled server component is missing or failed integrity verification.</summary>
    ComponentMissing,
}

public enum LocalServerLogSeverity
{
    Information,
    Warning,
    Error,
}

/// <summary>Runtime log file lines and process diagnostics are separate, durable channels.</summary>
public enum LocalServerLogChannel
{
    Runtime,
    Diagnostic,
}

public sealed record LocalServerLogLine(
    LocalServerLogSeverity Severity,
    LocalServerLogChannel Channel,
    string Text,
    DateTimeOffset? Timestamp);

/// <summary>Newest-first page. Truncated only when the tail exceeded the requested budget.</summary>
public sealed record LocalServerLogPage(
    IReadOnlyList<LocalServerLogLine> Lines,
    bool Truncated,
    DateTimeOffset ReadAt,
    int ErrorCount,
    int TotalCount);

public sealed record LocalServerFilter(bool ErrorsOnly, string? Query);

/// <summary>A Fileway server found listening on this machine, whether or not this client started it.</summary>
public sealed record LocalServerInstance(
    int Port,
    int ProcessId,
    string ProcessName,
    bool ManagedByThisClient,
    bool ServingWebUi)
{
    public string Endpoint => "http://127.0.0.1:" + Port.ToString(System.Globalization.CultureInfo.InvariantCulture);
}

/// <summary>Deployment settings safe to display. Credentials never appear here.</summary>
public sealed record LocalServerDeployment(
    int Port,
    string Address,
    bool MountsWholeComputer,
    string FilesRoot,
    string DataDirectory,
    string LogDirectory,
    string AdminUsername,
    DateTimeOffset DeployedAt);

/// <summary>Everything the management page renders. Paths stay local because the deployment is local.</summary>
public sealed record LocalServerStatus(
    LocalServerState State,
    LocalServerDeployment? Deployment,
    string? Endpoint,
    int? ProcessId,
    DateTimeOffset? StartedAt,
    TimeSpan? Uptime,
    string? ServerVersion,
    bool AutoStartEnabled,
    bool AutoRestartEnabled,
    int RestartAttempts,
    int? LastExitCode,
    ErrorInfo? LastError,
    IReadOnlyList<LocalServerInstance> Detected);

public sealed record LocalServerDeploymentRequest(
    int Port,
    bool MountsWholeComputer,
    string FilesRoot,
    string AdminUsername,
    string AdminPassword)
{
    public override string ToString() => "LocalServerDeploymentRequest (credentials redacted)";
}

/// <summary>The account created with the deployment. Revealed only on explicit user action.</summary>
public sealed record LocalServerCredential(string Username, string Password)
{
    public override string ToString() => "LocalServerCredential (redacted)";
}

/// <summary>Starting values for the deployment form, produced by the owning layer rather than the page.</summary>
public sealed record LocalServerDefaults(int Port, bool MountsWholeComputer, string FilesRoot, string Username, string Password);

/// <summary>Data for a one-click sign-in; the page forwards it, it never composes an address itself.</summary>
public sealed record LocalServerSignIn(string Endpoint, string Username, string Password)
{
    public override string ToString() => "LocalServerSignIn (credentials redacted)";
}

/// <summary>
/// Owns the bundled local server: integrity, lifecycle, boot registration and logs.
/// Only processes recorded in the managed state file are ever signalled.
/// </summary>
public interface ILocalServerManagementService
{
    Task<LocalServerStatus> ReadStatusAsync(CancellationToken cancellationToken);

    Task<LocalServerStatus> DeployAndStartAsync(LocalServerDeploymentRequest request, CancellationToken cancellationToken);

    Task<LocalServerStatus> StartAsync(CancellationToken cancellationToken);

    Task<LocalServerStatus> StopAsync(CancellationToken cancellationToken);

    Task<LocalServerStatus> SetAutoStartAsync(bool enabled, CancellationToken cancellationToken);

    Task<LocalServerStatus> SetAutoRestartAsync(bool enabled, CancellationToken cancellationToken);

    Task<LocalServerStatus> RetryStartAsync(CancellationToken cancellationToken);

    Task<LocalServerLogPage> ReadLogsAsync(LocalServerFilter filter, int maxLines, CancellationToken cancellationToken);

    /// <summary>Loopback endpoint of a healthy managed instance, or null when it is not usable.</summary>
    Task<string?> GetConnectableEndpointAsync(CancellationToken cancellationToken);

    /// <summary>The locally stored administrator account, or null while the deployment has no secret on file.</summary>
    Task<LocalServerCredential?> ReadCredentialAsync(CancellationToken cancellationToken);

    /// <summary>Starting values for the deployment form.</summary>
    LocalServerDefaults GetDefaults();

    /// <summary>Endpoint plus stored credentials for a one-click sign-in, or null when nothing is deployable.</summary>
    Task<LocalServerSignIn?> GetSignInAsync(CancellationToken cancellationToken);

    /// <summary>Opens the Web UI of a running instance in the default browser.</summary>
    Task<bool> OpenWebUiAsync(int port, CancellationToken cancellationToken);

    /// <summary>Raised after a watchdog transition so the page can refresh without polling.</summary>
    event EventHandler<LocalServerStatus> StatusChanged;
}

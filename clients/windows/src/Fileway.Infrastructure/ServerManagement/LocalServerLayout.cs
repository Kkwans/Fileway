using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.ServerManagement;

/// <summary>Persisted deployment of the managed server. Never contains the administrator secret.</summary>
public sealed class LocalServerDocument
{
    [JsonPropertyName("schema")]
    public int Schema { get; set; } = LocalServerLayout.CurrentSchema;

    [JsonPropertyName("address")]
    public string Address { get; set; } = "127.0.0.1";

    [JsonPropertyName("port")]
    public int Port { get; set; } = LocalServerLayout.DefaultPort;

    [JsonPropertyName("filesRoot")]
    public string FilesRoot { get; set; } = string.Empty;

    /// <summary>True mounts every drive through the server's virtual computer root; false restricts to FilesRoot.</summary>
    [JsonPropertyName("mountsWholeComputer")]
    public bool MountsWholeComputer { get; set; } = true;

    [JsonPropertyName("adminUsername")]
    public string AdminUsername { get; set; } = string.Empty;

    [JsonPropertyName("deployedAt")]
    public DateTimeOffset? DeployedAt { get; set; }

    [JsonPropertyName("executablePath")]
    public string ExecutablePath { get; set; } = string.Empty;

    [JsonPropertyName("executableSha256")]
    public string ExecutableSha256 { get; set; } = string.Empty;

    [JsonPropertyName("processId")]
    public int? ProcessId { get; set; }

    [JsonPropertyName("processStartedAt")]
    public DateTimeOffset? ProcessStartedAt { get; set; }

    [JsonPropertyName("autoStart")]
    public bool AutoStart { get; set; }

    [JsonPropertyName("autoRestart")]
    public bool AutoRestart { get; set; }

    [JsonPropertyName("restartAttempts")]
    public int RestartAttempts { get; set; }

    [JsonPropertyName("lastExitCode")]
    public int? LastExitCode { get; set; }

    [JsonPropertyName("lastStopSignal")]
    public string? LastStopSignal { get; set; }

    [JsonPropertyName("stoppedByUser")]
    public bool StoppedByUser { get; set; }

    [JsonPropertyName("lastError")]
    public string? LastError { get; set; }

    [JsonIgnore]
    public bool Deployed => DeployedAt is not null;
}

/// <summary>Build provenance of the co-located server executable.</summary>
internal sealed record ServerBuildReceipt(string Version, string Commit, string Sha256, bool WorkingTreeDirty);

/// <summary>Owns every path and the single durable document of one managed deployment.</summary>
internal sealed class LocalServerLayout
{
    internal const int CurrentSchema = 1;
    internal const int DefaultPort = 8888;
    internal const string ComputerRoot = "computer";
    internal const string StateName = "local-server.json";
    internal const string SecretName = "deployment.secret";
    internal const string DatabaseName = "server.db";
    internal const string RuntimeLogName = "server.log";
    internal const string DiagnosticsLogName = "diagnostics.log";
    internal const string ExecutableName = "fileway-server.exe";
    internal const string ReceiptName = "server-build.json";
    internal const string DataRootOverrideVariable = "FILEWAY_LOCAL_SERVER_DATA_ROOT";

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
    };

    private static readonly byte[] SecretEntropy = Encoding.UTF8.GetBytes("Fileway.LocalServer.AdminCredential.v1");

    private readonly string _statePath;
    private readonly string _secretPath;

    internal LocalServerLayout(string applicationDirectory, string dataRoot)
    {
        ApplicationDirectory = Path.GetFullPath(applicationDirectory);
        DataRoot = Path.GetFullPath(dataRoot);
        FilesRoot = Path.Combine(DataRoot, "files");
        CacheRoot = Path.Combine(DataRoot, "cache");
        HlsRoot = Path.Combine(DataRoot, "hls");
        LogRoot = Path.Combine(DataRoot, "logs");
        _statePath = Path.Combine(DataRoot, StateName);
        _secretPath = Path.Combine(DataRoot, SecretName);
        ExecutablePath = Path.Combine(ApplicationDirectory, ExecutableName);
        ReceiptPath = Path.Combine(ApplicationDirectory, ReceiptName);
        DatabasePath = Path.Combine(DataRoot, DatabaseName);
        RuntimeLogPath = Path.Combine(LogRoot, RuntimeLogName);
        DiagnosticsLogPath = Path.Combine(LogRoot, DiagnosticsLogName);
    }

    internal string ApplicationDirectory { get; }
    internal string DataRoot { get; }
    internal string FilesRoot { get; }
    internal string CacheRoot { get; }
    internal string HlsRoot { get; }
    internal string LogRoot { get; }
    internal string ExecutablePath { get; }
    internal string ReceiptPath { get; }
    internal string DatabasePath { get; }
    internal string RuntimeLogPath { get; }
    internal string DiagnosticsLogPath { get; }

    internal static string DefaultDataRoot() => Environment.GetEnvironmentVariable(DataRootOverrideVariable) is { Length: > 0 } root && Path.IsPathFullyQualified(root)
            ? root
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Fileway", "Server");

    internal static LocalServerDocument NewDocument(string executablePath, string sha256) => new()
    {
        Address = "127.0.0.1",
        Port = DefaultPort,
        ExecutablePath = executablePath,
        ExecutableSha256 = sha256,
    };

    internal void EnsureDirectories()
    {
        Directory.CreateDirectory(DataRoot);
        Directory.CreateDirectory(LogRoot);
    }

    internal LocalServerDocument? ReadDocument()
    {
        try
        {
            if (!File.Exists(_statePath)) return null;
            var document = JsonSerializer.Deserialize<LocalServerDocument>(File.ReadAllText(_statePath), JsonOptions);
            if (document is null || document.Schema != CurrentSchema) return null;
            return document;
        }
        catch (Exception error) when (IsStorageFailure(error))
        {
            // A torn or foreign document must never be rewritten blindly; the page reports it as missing.
            return null;
        }
    }

    internal void WriteDocument(LocalServerDocument document)
    {
        EnsureDirectories();
        string temporary = _statePath + "." + Guid.NewGuid().ToString("N") + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(document, JsonOptions));
        File.Move(temporary, _statePath, overwrite: true);
    }

    internal void WriteAdminSecret(string password)
    {
        EnsureDirectories();
        byte[] plaintext = Array.Empty<byte>();
        byte[] protectedBytes = Array.Empty<byte>();
        try
        {
            plaintext = Encoding.UTF8.GetBytes(password);
            protectedBytes = ProtectedData.Protect(plaintext, SecretEntropy, DataProtectionScope.CurrentUser);
            File.WriteAllBytes(_secretPath, protectedBytes);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(plaintext);
            CryptographicOperations.ZeroMemory(protectedBytes);
        }
    }

    internal string? ReadAdminSecret()
    {
        try
        {
            if (!File.Exists(_secretPath)) return null;
            byte[] protectedBytes = File.ReadAllBytes(_secretPath);
            byte[] plaintext = Array.Empty<byte>();
            try
            {
                plaintext = ProtectedData.Unprotect(protectedBytes, SecretEntropy, DataProtectionScope.CurrentUser);
                return Encoding.UTF8.GetString(plaintext);
            }
            finally
            {
                CryptographicOperations.ZeroMemory(plaintext);
            }
        }
        catch (Exception error) when (IsStorageFailure(error) || error is CryptographicException)
        {
            return null;
        }
    }

    /// <summary>Display text for the virtual computer root; the server itself receives the literal token.</summary>
    internal const string ComputerRootLabel = "整台电脑（所有盘符）";

    /// <summary>The virtual root that exposes every drive, matching the server's Windows filesystem adapter.</summary>
    internal static string RootArgument(LocalServerDocument document) =>
        document.MountsWholeComputer || string.IsNullOrWhiteSpace(document.FilesRoot) ? ComputerRoot : document.FilesRoot;

    /// <summary>Arguments shared by the interactive launch and the boot registration so both agree.</summary>
    internal IReadOnlyList<string> LaunchArguments(LocalServerDocument document) => new[]
    {
        "--address", document.Address,
        "--port", document.Port.ToString(System.Globalization.CultureInfo.InvariantCulture),
        "--root", RootArgument(document),
        "--database", DatabasePath,
        "--cacheDir", CacheRoot,
        "--hlsCacheDir", HlsRoot,
        "--log", RuntimeLogPath,
        "--disableExec=true",
        "--tokenExpirationTime", "168h",
    };

    internal bool TryReadReceipt(out ServerBuildReceipt receipt)
    {
        receipt = null!;
        try
        {
            if (!File.Exists(ReceiptPath)) return false;
            using var document = JsonDocument.Parse(File.ReadAllText(ReceiptPath));
            var root = document.RootElement;
            if (!root.TryGetProperty("sha256", out var hash) || !root.TryGetProperty("serverVersion", out var version)) return false;
            string sha = hash.GetString() ?? string.Empty;
            if (sha.Length != 64) return false;
            string commit = root.TryGetProperty("buildCommit", out var c) ? c.GetString() ?? string.Empty : string.Empty;
            bool dirty = root.TryGetProperty("workingTreeDirty", out var d) && d.GetBoolean();
            receipt = new ServerBuildReceipt(version.GetString() ?? string.Empty, commit, sha.ToLowerInvariant(), dirty);
            return true;
        }
        catch (Exception error) when (IsStorageFailure(error) || error is JsonException)
        {
            return false;
        }
    }

    /// <summary>Integrity is re-checked only when the executable changed on disk.</summary>
    internal bool VerifyExecutable(string expectedSha256, ref DateTime stamp, ref long length, ref string? cached)
    {
        var information = new FileInfo(ExecutablePath);
        if (!information.Exists) return false;
        if (cached is not null && information.LastWriteTimeUtc == stamp && information.Length == length) return true;
        using var stream = new FileStream(ExecutablePath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        byte[] actual = SHA256.HashData(stream);
        string hex = Convert.ToHexString(actual).ToLowerInvariant();
        stamp = information.LastWriteTimeUtc;
        length = information.Length;
        cached = hex;
        return hex == expectedSha256;
    }

    private static bool IsStorageFailure(Exception error) =>
        error is IOException or UnauthorizedAccessException or System.Text.Json.JsonException or NotSupportedException;
}

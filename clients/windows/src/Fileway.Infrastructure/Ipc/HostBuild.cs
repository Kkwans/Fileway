using System.Security.Cryptography;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Ipc;

internal sealed class HostBuild : IDisposable
{
    private readonly FileStream _executable;
    private readonly FileStream _manifest;
    private readonly string _version, _commit, _goVersion;
    private readonly bool _dirty;

    private HostBuild(FileStream executable, FileStream manifest, string version, string commit, string goVersion, bool dirty)
    { _executable = executable; _manifest = manifest; _version = version; _commit = commit; _goVersion = goVersion; _dirty = dirty; }

    internal static async Task<HostBuild> VerifyAsync(HostClientOptions options, CancellationToken cancellationToken)
    {
        if (!Path.IsPathFullyQualified(options.ExecutablePath) || !Path.IsPathFullyQualified(options.ManifestPath))
            throw HostWire.SafeError(ErrorCode.InvalidRequest, "Host paths must be absolute composition paths.");
        string executable = Path.GetFullPath(options.ExecutablePath), manifest = Path.GetFullPath(options.ManifestPath);
        if (!Path.GetFileName(executable).Equals("fileway-host.exe", StringComparison.OrdinalIgnoreCase) ||
            !Path.GetFileName(manifest).Equals("host-build.json", StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(Path.GetDirectoryName(executable), Path.GetDirectoryName(manifest), StringComparison.OrdinalIgnoreCase) ||
            executable.StartsWith(@"\\", StringComparison.Ordinal) || manifest.StartsWith(@"\\", StringComparison.Ordinal))
            throw HostWire.SafeError(ErrorCode.InvalidRequest, "Host files must use their fixed co-located names.");
        FileStream? executableFile = null, manifestFile = null;
        try
        {
            executableFile = new FileStream(executable, FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024, FileOptions.Asynchronous);
            manifestFile = new FileStream(manifest, FileMode.Open, FileAccess.Read, FileShare.Read, 4096, FileOptions.Asynchronous);
            if (manifestFile.Length is 0 or > 65536 || executableFile.Length == 0) throw HostWire.InvalidResponse();
            byte[] bytes = new byte[(int)manifestFile.Length];
            await manifestFile.ReadExactlyAsync(bytes, cancellationToken).ConfigureAwait(false);
            ReadOnlyMemory<byte> json = bytes.AsMemory();
            if (bytes.AsSpan().StartsWith(new byte[] { 0xef, 0xbb, 0xbf })) json = json[3..];
            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;
            HostWire.ValidateUnique(root);
            string version = HostWire.Text(root, "hostVersion"), commit = HostWire.Text(root, "buildCommit"), goVersion = HostWire.Text(root, "goVersion");
            bool dirty = root.GetProperty("workingTreeDirty").GetBoolean();
            if (HostWire.Text(root, "architecture") != "win-x64" || string.IsNullOrEmpty(version) || string.IsNullOrEmpty(commit)) throw HostWire.InvalidResponse();
            string hash = HostWire.Text(root, "sha256");
            byte[] actual = await SHA256.HashDataAsync(executableFile, cancellationToken).ConfigureAwait(false);
            if (hash.Length != 64 || !Convert.ToHexString(actual).Equals(hash, StringComparison.OrdinalIgnoreCase))
                throw HostWire.SafeError(ErrorCode.ProtocolMismatch, "Host build integrity verification failed.");
            var build = new HostBuild(executableFile, manifestFile, version, commit, goVersion, dirty);
            executableFile = null;
            manifestFile = null;
            return build;
        }
        finally { executableFile?.Dispose(); manifestFile?.Dispose(); }
    }

    internal HostHandshake Handshake(JsonElement result)
    {
        int major = HostWire.Integer(result, "protocolMajor"), minor = HostWire.Integer(result, "protocolMinor"), core = HostWire.Integer(result, "coreProtocol");
        string version = HostWire.Text(result, "hostVersion"), commit = HostWire.Text(result, "buildCommit"), go = HostWire.Text(result, "goVersion");
        var capabilities = result.GetProperty("capabilities");
        if (major != HostProtocol.Major || minor < 0 || core != 1 || version != _version || commit != _commit ||
            _goVersion != $"go version {go} windows/amd64" ||
            HostWire.Integer(capabilities, "maxFrameBytes") != HostProtocol.MaxFrameBytes ||
            HostWire.Integer(capabilities, "maxCoreCommandBytes") != HostProtocol.MaxCoreCommandBytes ||
            HostWire.Text(capabilities, "framing") != "uint32-le-utf8-json" || capabilities.GetProperty("rawBody").GetBoolean() ||
            HostWire.Text(result.GetProperty("module"), "path") != "github.com/Kkwans/Fileway/clients/windows/host" ||
            !result.GetProperty("dependencies").EnumerateArray().Any(module => HostWire.Text(module, "path") == "github.com/Kkwans/nas-file-browser-client/core"))
            throw HostWire.SafeError(ErrorCode.ProtocolMismatch, "Host build handshake verification failed.");
        var settings = result.GetProperty("buildSettings");
        if (HostWire.Text(settings, "GOOS") != "windows" || HostWire.Text(settings, "GOARCH") != "amd64" ||
            (settings.TryGetProperty("vcs.modified", out var dirty) && dirty.GetString() != (_dirty ? "true" : "false")))
            throw HostWire.SafeError(ErrorCode.ProtocolMismatch, "Host platform metadata verification failed.");
        return new HostHandshake(major, minor, core, version, commit, go, result.Clone());
    }

    public void Dispose() { _executable.Dispose(); _manifest.Dispose(); }
}

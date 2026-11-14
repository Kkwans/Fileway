using System.Security.Cryptography;
using System.Text.Json;

namespace Fileway.ProgressivePlaybackHarness;

internal sealed record HarnessInputs(string Runtime, string RuntimeLock, string Fixture, string OutputRoot)
{
    internal static HarnessInputs Parse(string[] arguments)
    {
        string[] names = ["--runtime", "--runtime-lock", "--fixture", "--output-root"];
        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        Require(arguments.Length == 8, "arguments-required");
        for (int index = 0; index < arguments.Length; index += 2)
        {
            Require(names.Contains(arguments[index], StringComparer.Ordinal) &&
                values.TryAdd(arguments[index], arguments[index + 1]), "arguments-invalid");
        }
        foreach (string name in names) Require(values.ContainsKey(name), "arguments-required");
        return new HarnessInputs(CheckedPath(values[names[0]], outsideGit: true),
            CheckedPath(values[names[1]], outsideGit: false), CheckedPath(values[names[2]], outsideGit: true),
            CheckedPath(values[names[3]], outsideGit: true));
    }

    internal string ValidateRuntime()
    {
        Require(OperatingSystem.IsWindows() && Environment.Is64BitProcess, "windows-x64-required");
        Require(Directory.Exists(Runtime) && File.Exists(RuntimeLock), "pinned-runtime-required");
        byte[] lockBytes = File.ReadAllBytes(RuntimeLock);
        using var document = JsonDocument.Parse(lockBytes);
        var root = document.RootElement;
        Require(root.GetProperty("architecture").GetString() == "ucrt64-x86_64", "runtime-architecture-mismatch");
        string copy = CheckedPath(Path.Combine(Runtime, "runtime-lock.json"), outsideGit: true);
        Require(File.Exists(copy) && File.ReadAllBytes(copy).AsSpan().SequenceEqual(lockBytes), "runtime-lock-copy-mismatch");
        var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var item in root.GetProperty("runtimeFiles").EnumerateArray())
        {
            string name = item.GetProperty("file").GetString()!;
            Require(!string.IsNullOrWhiteSpace(name) && Path.GetFileName(name) == name &&
                name.EndsWith(".dll", StringComparison.OrdinalIgnoreCase) && names.Add(name), "runtime-dll-name-invalid");
            string file = CheckedPath(Path.Combine(Runtime, name), outsideGit: true);
            Require(File.Exists(file), "runtime-dll-missing");
            using var stream = File.OpenRead(file);
            Require(string.Equals(Convert.ToHexStringLower(SHA256.HashData(stream)),
                item.GetProperty("sha256").GetString(), StringComparison.Ordinal), "runtime-dll-hash-mismatch");
        }
        Require(names.Contains("libmpv-2.dll") && names.SetEquals(Directory.EnumerateFiles(Runtime, "*.dll")
            .Select(Path.GetFileName)!), "runtime-dll-inventory-mismatch");
        return Convert.ToHexStringLower(SHA256.HashData(lockBytes));
    }

    internal byte[] ReadFixture()
    {
        Require(File.Exists(Fixture), "synthetic-fixture-required");
        Require(new FileInfo(Fixture).Length == MediaFixture.TotalBytes, "fixture-size-mismatch");
        byte[] bytes = File.ReadAllBytes(Fixture);
        Require(Convert.ToHexStringLower(SHA256.HashData(bytes)) == MediaFixture.Sha256, "fixture-hash-mismatch");
        return bytes;
    }

    internal void CreateOutputDirectory()
    {
        Require(!File.Exists(OutputRoot) && !Directory.Exists(OutputRoot), "output-root-must-be-new");
        Directory.CreateDirectory(OutputRoot);
        _ = CheckedPath(OutputRoot, outsideGit: true);
    }

    private static string CheckedPath(string path, bool outsideGit)
    {
        Require(Path.IsPathFullyQualified(path), "absolute-path-required");
        string full = Path.GetFullPath(path);
        for (string? current = full; current is not null; current = Path.GetDirectoryName(current))
        {
            FileSystemInfo item = Directory.Exists(current) ? new DirectoryInfo(current) : new FileInfo(current);
            Require(item.LinkTarget is null && (!item.Exists || (item.Attributes & FileAttributes.ReparsePoint) == 0),
                "reparse-path-not-allowed");
            if (outsideGit)
                Require(!File.Exists(Path.Combine(current, ".git")) &&
                    !File.Exists(Path.Combine(current, ".git", "HEAD")), "external-artifact-path-required");
        }
        return full;
    }

    internal static void Require(bool condition, string stage)
    {
        if (!condition) throw new InvalidOperationException(stage);
    }
}

internal static class MediaFixture
{
    internal const int Width = 160, Height = 90, FramesPerSecond = 12, HeaderBytes = 42, FrameRecordBytes = 21606;
    internal const int InitialSeconds = 8, ExtendedSeconds = 16, TotalSeconds = 40;
    internal const int TotalBytes = HeaderBytes + FrameRecordBytes * FramesPerSecond * TotalSeconds;
    internal const string Sha256 = "c027db76c4358ca75888c711fe07de1ec5f195428908bdc4a8012346442481a4";
    internal static int PrefixBytes(int seconds) => HeaderBytes + FrameRecordBytes * FramesPerSecond * seconds;
}

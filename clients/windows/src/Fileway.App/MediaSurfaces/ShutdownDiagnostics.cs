using System.Text.Json;

namespace Fileway.App.MediaSurfaces;

// Application-owned stages only. This recorder has no string/exception payload
// API, so paths, media metadata, credentials and exception text cannot enter it.
internal enum ShutdownDiagnosticStage
{
    ShutdownEntered = 1,
    ApplicationTasksDrained = 2,
    TransfersDisposed = 3,
    PlaybackDisposeBegin = 4,
    PlaybackDisposeReturned = 5,
    ServicesAndSettingsDisposed = 6,
    HostDisposed = 7,
    WindowCloseBegin = 8,
    WindowCloseReturned = 9,
}

/// <summary>Opt-in, synchronous, bounded breadcrumbs immediately before native teardown.</summary>
internal sealed class ShutdownDiagnostics : IDisposable
{
    internal const string EnvironmentVariable = "FILEWAY_PLAYER_RESPONSIVENESS_DIAGNOSTICS";
    internal const int Capacity = 16;
    private static readonly Lazy<ShutdownDiagnostics?> Current = new(() => TryCreate());
    private readonly string _directory;
    private readonly object _gate = new();
    private FileStream? _stream;
    private int _recorded;
    private bool _stopped;

    private ShutdownDiagnostics(string directory) => _directory = directory;

    internal static void Record(ShutdownDiagnosticStage stage, bool uiThread)
    {
        if (!IsSupported(stage)) return;
        try { Current.Value?.WriteStage(stage, uiThread); }
        catch (Exception) { /* Optional diagnostics never change shutdown behavior. */ }
    }

    // Environment injection makes the disabled path and local writer testable
    // without changing process-global configuration or loading WinUI.
    internal static ShutdownDiagnostics? TryCreate(Func<string, string?>? environment = null)
    {
        environment ??= Environment.GetEnvironmentVariable;
        try
        {
            if (environment(EnvironmentVariable) != "1") return null;
            var root = environment("FILEWAY_WINDOWS_DATA_ROOT")
                ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Fileway", "Preview");
            if (!Path.IsPathFullyQualified(root)) return null;
            return new ShutdownDiagnostics(Path.Combine(Path.GetFullPath(root), "diagnostics"));
        }
        catch (Exception) { return null; }
    }

    internal void WriteStage(ShutdownDiagnosticStage stage, bool uiThread)
    {
        if (!IsSupported(stage)) return;
        lock (_gate)
        {
            if (_stopped || _recorded >= Capacity) return;
            _recorded++;
            try
            {
                if (_stream is null)
                {
                    Directory.CreateDirectory(_directory);
                    // Own one fresh file for this process; never clobber prior
                    // evidence. Read sharing permits bounded smoke extraction.
                    _stream = new FileStream(Path.Combine(_directory, "shutdown.jsonl"), FileMode.CreateNew,
                        FileAccess.Write, FileShare.Read, bufferSize: 1, FileOptions.WriteThrough);
                }
                var bytes = JsonSerializer.SerializeToUtf8Bytes(new
                {
                    schemaVersion = 1,
                    stage = (int)stage,
                    uiThread = uiThread ? 1 : 0,
                });
                _stream.Write(bytes);
                _stream.WriteByte((byte)'\n');
                // A native fail-fast skips managed finally blocks. Persist each
                // small row now, rather than depending on process-exit cleanup.
                _stream.Flush(flushToDisk: true);
            }
            catch (Exception) { StopCore(); }
        }
    }

    private static bool IsSupported(ShutdownDiagnosticStage stage)
        => (int)stage is >= (int)ShutdownDiagnosticStage.ShutdownEntered and <= (int)ShutdownDiagnosticStage.WindowCloseReturned;

    public void Dispose()
    {
        lock (_gate) StopCore();
    }

    private void StopCore()
    {
        _stopped = true;
        try { _stream?.Dispose(); }
        catch (Exception) { }
        _stream = null;
    }
}

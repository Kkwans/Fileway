using System.Text;
using System.Text.Json;
using Fileway.App.MediaSurfaces;

namespace Fileway.Infrastructure.Tests.Playback;

public sealed class ShutdownDiagnosticsTests
{
    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("0")]
    [InlineData("true")]
    [InlineData(" 1")]
    [InlineData("1 ")]
    public void DisabledRecorderOnlyReadsTheOptInVariable(string? value)
    {
        var reads = 0;
        var recorder = ShutdownDiagnostics.TryCreate(name =>
        {
            reads++;
            Assert.Equal(ShutdownDiagnostics.EnvironmentVariable, name);
            return value;
        });
        Assert.Null(recorder);
        Assert.Equal(1, reads);
    }

    [Fact]
    public void UnavailableConfigurationIsNonfatal()
        => Assert.Null(ShutdownDiagnostics.TryCreate(_ => throw new InvalidOperationException("private detail")));

    [Theory]
    [InlineData("")]
    [InlineData("relative data root")]
    public void RelativeDataRootIsRejected(string root)
        => Assert.Null(ShutdownDiagnostics.TryCreate(name => name == ShutdownDiagnostics.EnvironmentVariable ? "1" : root));

    [Fact]
    public void CreationDoesNotTouchDiskUntilTheFirstValidStage()
    {
        using var fixture = new Fixture();
        Assert.False(Directory.Exists(fixture.Root));
        fixture.Recorder.WriteStage((ShutdownDiagnosticStage)0, true);
        fixture.Recorder.WriteStage((ShutdownDiagnosticStage)10, false);
        fixture.Recorder.WriteStage((ShutdownDiagnosticStage)(-1), true);
        Assert.False(Directory.Exists(fixture.Root));
        fixture.Recorder.WriteStage(ShutdownDiagnosticStage.ShutdownEntered, true);
        Assert.Single(fixture.ReadRows());
    }

    [Fact]
    public void EveryStageIsSynchronouslyReadableBeforeDisposalAndHasOnlyNumericFields()
    {
        using var fixture = new Fixture();
        foreach (var stage in Enum.GetValues<ShutdownDiagnosticStage>())
        {
            fixture.Recorder.WriteStage(stage, (int)stage % 2 == 1);
            var rows = fixture.ReadRows();
            Assert.Equal((int)stage, rows.Length);
            using var row = JsonDocument.Parse(rows[^1]);
            var properties = row.RootElement.EnumerateObject().ToArray();
            Assert.Equal(["schemaVersion", "stage", "uiThread"], properties.Select(property => property.Name));
            Assert.All(properties, property => Assert.Equal(JsonValueKind.Number, property.Value.ValueKind));
            Assert.Equal(1, row.RootElement.GetProperty("schemaVersion").GetInt32());
            Assert.Equal((int)stage, row.RootElement.GetProperty("stage").GetInt32());
            Assert.Equal((int)stage % 2, row.RootElement.GetProperty("uiThread").GetInt32());
        }
        Assert.DoesNotContain(fixture.Root, string.Join('\n', fixture.ReadRows()), StringComparison.Ordinal);
        Assert.Equal([fixture.Path], Directory.GetFiles(fixture.Root, "*", SearchOption.AllDirectories));
    }

    [Fact]
    public void ConcurrentWritersCannotExceedTheSixteenRowLimit()
    {
        using var fixture = new Fixture();
        Parallel.For(0, 1000, index => fixture.Recorder.WriteStage(
            (ShutdownDiagnosticStage)(index % 9 + 1), index % 2 == 0));
        var rows = fixture.ReadRows();
        Assert.Equal(ShutdownDiagnostics.Capacity, rows.Length);
        Assert.All(rows, text =>
        {
            using var row = JsonDocument.Parse(text);
            Assert.InRange(row.RootElement.GetProperty("stage").GetInt32(), 1, 9);
            Assert.InRange(row.RootElement.GetProperty("uiThread").GetInt32(), 0, 1);
        });
        Assert.InRange(new FileInfo(fixture.Path).Length, 1, 1024);
    }

    [Fact]
    public void ExistingEvidenceIsNeverOverwrittenOrAppended()
    {
        using var fixture = new Fixture();
        Directory.CreateDirectory(System.IO.Path.GetDirectoryName(fixture.Path)!);
        const string prior = "existing private evidence\n";
        File.WriteAllText(fixture.Path, prior);
        fixture.Recorder.WriteStage(ShutdownDiagnosticStage.ShutdownEntered, true);
        Assert.Equal(prior, File.ReadAllText(fixture.Path));
        File.Delete(fixture.Path);
        fixture.Recorder.WriteStage(ShutdownDiagnosticStage.ApplicationTasksDrained, true);
        Assert.False(File.Exists(fixture.Path)); // A failed writer stays stopped.
    }

    [Fact]
    public void UnavailableStorageIsNonfatalAndIsNotRetried()
    {
        using var fixture = new Fixture();
        Directory.CreateDirectory(fixture.Root);
        var directory = System.IO.Path.GetDirectoryName(fixture.Path)!;
        File.WriteAllText(directory, "not a directory");
        fixture.Recorder.WriteStage(ShutdownDiagnosticStage.ShutdownEntered, false);
        File.Delete(directory);
        fixture.Recorder.WriteStage(ShutdownDiagnosticStage.ApplicationTasksDrained, false);
        Assert.False(File.Exists(fixture.Path));
    }

    [Fact]
    public void DisposalIsIdempotentAndCannotBeRevivedByALateStage()
    {
        using var fixture = new Fixture();
        fixture.Recorder.WriteStage(ShutdownDiagnosticStage.WindowCloseBegin, true);
        fixture.Recorder.Dispose();
        fixture.Recorder.Dispose();
        fixture.Recorder.WriteStage(ShutdownDiagnosticStage.WindowCloseReturned, true);
        Assert.Single(fixture.ReadRows());
        // The owned file handle is released, including on Windows.
        using var exclusive = new FileStream(fixture.Path, FileMode.Open, FileAccess.Read, FileShare.None);
    }

    private sealed class Fixture : IDisposable
    {
        internal string Root { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "Fileway shutdown Space " + Guid.NewGuid().ToString("N"));
        internal string Path => System.IO.Path.Combine(Root, "diagnostics", "shutdown.jsonl");
        internal ShutdownDiagnostics Recorder { get; }

        internal Fixture()
        {
            Recorder = ShutdownDiagnostics.TryCreate(name => name == ShutdownDiagnostics.EnvironmentVariable ? "1" : Root)!;
            Assert.NotNull(Recorder);
        }

        internal string[] ReadRows()
        {
            // Share the current writer's existing write access on Windows.
            using var stream = new FileStream(Path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
            using var reader = new StreamReader(stream, new UTF8Encoding(false, true));
            return reader.ReadToEnd().Split('\n', StringSplitOptions.RemoveEmptyEntries);
        }

        public void Dispose()
        {
            Recorder.Dispose();
            if (Directory.Exists(Root)) Directory.Delete(Root, recursive: true);
        }
    }
}

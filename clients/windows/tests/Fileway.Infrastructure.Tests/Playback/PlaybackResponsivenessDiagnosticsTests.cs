using System.Collections.Concurrent;
using System.Text.Json;
using System.Threading.Channels;
using Fileway.App.MediaSurfaces;

namespace Fileway.Infrastructure.Tests.Playback;

public sealed class PlaybackResponsivenessDiagnosticsTests
{
    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("0")]
    [InlineData("true")]
    [InlineData(" 1")]
    public void DisabledCaptureDoesNotReadDataRootOrStartAnyWork(string? value)
    {
        var environmentReads = 0;
        var enqueues = 0;
        var recorder = PlaybackResponsivenessDiagnostics.TryCreate(_ => { enqueues++; return true; }, name =>
        {
            environmentReads++;
            Assert.Equal(PlaybackResponsivenessDiagnostics.EnvironmentVariable, name);
            return value;
        });

        Assert.Null(recorder);
        Assert.Equal(1, environmentReads);
        Assert.Equal(0, enqueues);
    }

    [Fact]
    public void UnavailableConfigurationCannotEscapeToPlayerConstruction()
    {
        Assert.Null(PlaybackResponsivenessDiagnostics.TryCreate(_ => true, _ =>
            throw new InvalidOperationException("private environment detail")));
    }

    [Fact]
    public async Task RingKeepsOnlyNewestEventsInSequenceOrder()
    {
        await using var fixture = new Fixture(capacity: 8);
        await fixture.ReadCycleAsync();
        for (var index = 0; index < 10_000; index++)
            fixture.Recorder.Record(PlaybackDiagnosticEvent.FullscreenInput, index);

        var report = fixture.Recorder.Capture();
        Assert.Equal(8, report.Entries.Length);
        Assert.Equal(9_992, report.Entries[0].Value);
        Assert.Equal(9_999, report.Entries[^1].Value);
        Assert.True(report.Entries.Zip(report.Entries.Skip(1)).All(pair => pair.First.Sequence < pair.Second.Sequence));
        Assert.Equal(10_002, report.EventsRecorded); // CaptureStarted and first probe.
    }

    [Fact]
    public async Task ConcurrentProducersDoNotWaitOnPausedWriterOrCorruptSequenceOrder()
    {
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using var recorder = new PlaybackResponsivenessDiagnostics(_ => true, async (_, cancellation) =>
        {
            entered.TrySetResult();
            await release.Task.WaitAsync(cancellation);
        }, capacity: 16);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);

        await Task.Run(() => Parallel.For(0, 10_000, index => recorder.Record(PlaybackDiagnosticEvent.SettingsInput, index)), TestContext.Current.CancellationToken)
            .WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        var report = recorder.Capture();
        Assert.InRange(report.Entries.Length, 1, 16);
        Assert.Equal(10_002, report.EventsRecorded);
        Assert.True(report.Entries.Zip(report.Entries.Skip(1)).All(pair => pair.First.Sequence < pair.Second.Sequence));
        recorder.Dispose();
        await recorder.Completion.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
    }

    [Fact]
    public async Task BlockedUiHasOneOutstandingProbeAndPersistedPendingAge()
    {
        await using var fixture = new Fixture();
        await fixture.ReadCycleAsync();
        for (var index = 0; index < 20; index++)
        {
            fixture.Clock.Advance(TimeSpan.FromSeconds(1));
            fixture.Recorder.ProbeUiQueue();
        }
        Assert.Single(fixture.Queue);
        fixture.ResumeCycle();
        var report = await fixture.ReadCycleAsync();
        Assert.True(report.ProbePending);
        Assert.Equal(20_000_000, report.ProbeAgeMicroseconds);
        Assert.Contains(report.Entries, entry => entry.Event == PlaybackDiagnosticEvent.UiProbePending && entry.Value == 20_000_000);

        Assert.True(fixture.Queue.TryDequeue(out var callback));
        callback();
        report = fixture.Recorder.Capture();
        Assert.False(report.ProbePending);
        Assert.Contains(report.Entries, entry => entry.Event == PlaybackDiagnosticEvent.UiProbeCompleted && entry.Value == 20_000_000);
    }

    [Fact]
    public async Task DurationSamplingCountsHotCallsAndRetainsSlowSamples()
    {
        await using var fixture = new Fixture();
        await fixture.ReadCycleAsync();
        for (var index = 0; index < 100; index++)
            fixture.Recorder.RecordDuration(PlaybackDiagnosticEvent.HitTestDuration, fixture.Clock.GetTimestamp());
        Assert.Single(fixture.Recorder.Capture().Entries, entry => entry.Event == PlaybackDiagnosticEvent.HitTestDuration);
        var started = fixture.Clock.GetTimestamp();
        fixture.Clock.Advance(TimeSpan.FromMilliseconds(75));
        fixture.Recorder.RecordDuration(PlaybackDiagnosticEvent.HitTestDuration, started);
        fixture.Recorder.RecordDuration(PlaybackDiagnosticEvent.SnapshotApplyDuration, fixture.Clock.GetTimestamp());
        var report = fixture.Recorder.Capture();
        Assert.Equal(101, report.HitTests);
        Assert.Equal(1, report.SnapshotApplications);
        Assert.Contains(report.Entries, entry => entry.Event == PlaybackDiagnosticEvent.HitTestDuration && entry.Value == 75_000);
        Assert.Equal(2, report.Entries.Count(entry => entry.Event == PlaybackDiagnosticEvent.HitTestDuration));

        fixture.Clock.Advance(TimeSpan.FromSeconds(1));
        fixture.Recorder.RecordDuration(PlaybackDiagnosticEvent.HitTestDuration, fixture.Clock.GetTimestamp());
        Assert.Equal(3, fixture.Recorder.Capture().Entries.Count(entry => entry.Event == PlaybackDiagnosticEvent.HitTestDuration));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task RejectedOrThrowingDispatcherIsNonfatalAndStopsProbing(bool throws)
    {
        var reports = new ConcurrentQueue<PlaybackDiagnosticReport>();
        var enqueues = 0;
        using var recorder = new PlaybackResponsivenessDiagnostics(_ =>
        {
            enqueues++;
            if (throws) throw new InvalidOperationException("private dispatcher detail");
            return false;
        }, (report, _) => { reports.Enqueue(report); return Task.CompletedTask; });
        await recorder.Completion.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        recorder.ProbeUiQueue();
        Assert.Equal(1, enqueues);
        var report = Assert.Single(reports);
        Assert.False(report.ProbePending);
        Assert.Contains(report.Entries, entry => entry.Event == PlaybackDiagnosticEvent.UiProbeRejected);
        Assert.DoesNotContain("private dispatcher detail", JsonSerializer.Serialize(report), StringComparison.Ordinal);
    }

    [Fact]
    public async Task CancellationIsNonblockingIdempotentAndLateUiCallbackCannotReviveCapture()
    {
        await using var fixture = new Fixture();
        await fixture.ReadCycleAsync();
        Assert.True(fixture.Queue.TryDequeue(out var callback));
        fixture.Recorder.Dispose();
        fixture.Recorder.Dispose();
        await fixture.Recorder.Completion.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        var before = fixture.Recorder.Capture().EventsRecorded;
        callback();
        fixture.Recorder.Record(PlaybackDiagnosticEvent.SettingsInput);
        fixture.Recorder.ProbeUiQueue();
        Assert.Equal(before, fixture.Recorder.Capture().EventsRecorded);
        Assert.Empty(fixture.Queue);
    }

    [Fact]
    public async Task CaptureDeadlineStopsWithDeterministicClock()
    {
        await using var fixture = new Fixture(duration: TimeSpan.FromSeconds(3));
        await fixture.ReadCycleAsync();
        fixture.Clock.Advance(TimeSpan.FromSeconds(3));
        fixture.ResumeCycle();
        await fixture.Recorder.Completion.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        Assert.Single(fixture.Queue);
        Assert.Equal(1, fixture.Writes);
    }

    [Fact]
    public async Task RepeatedDisposeRacingNaturalDeadlineNeverThrowsOrFaultsWorker()
    {
        for (var index = 0; index < 300; index++)
        {
            var clock = new TestClock();
            var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            using var recorder = new PlaybackResponsivenessDiagnostics(_ => true, async (_, cancellation) =>
            {
                entered.TrySetResult();
                await release.Task.WaitAsync(cancellation);
            }, clock, (_, _) => Task.CompletedTask, duration: TimeSpan.FromSeconds(1),
                onStopped: () => throw new InvalidOperationException("private cleanup detail"));
            await entered.Task.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
            clock.Advance(TimeSpan.FromSeconds(1));
            release.TrySetResult();
            await Task.Run(recorder.Dispose, TestContext.Current.CancellationToken);
            await recorder.Completion.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
            recorder.Dispose();
            Assert.True(recorder.Completion.IsCompletedSuccessfully);
        }
    }

    [Fact]
    public async Task ThrowingCancellationCallbackDoesNotEscapeOrFaultCompletion()
    {
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using var recorder = new PlaybackResponsivenessDiagnostics(_ => true, async (_, cancellation) =>
        {
            using var registration = cancellation.Register(() => throw new InvalidOperationException("private cancellation detail"));
            entered.TrySetResult();
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellation);
        });
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        recorder.Dispose();
        await recorder.Completion.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        Assert.True(recorder.Completion.IsCompletedSuccessfully);
    }

    [Fact]
    public async Task PersistenceFailureDoesNotEscapeToUiOrFaultCompletion()
    {
        using var recorder = new PlaybackResponsivenessDiagnostics(_ => true, (_, _) =>
            throw new IOException("private media path or URL"));
        await recorder.Completion.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        recorder.Record(PlaybackDiagnosticEvent.FullscreenInput);
        Assert.True(recorder.Completion.IsCompletedSuccessfully);
        Assert.DoesNotContain("private media path", JsonSerializer.Serialize(recorder.Capture()), StringComparison.Ordinal);
    }

    [Fact]
    public async Task NumericSchemaIsBoundedAndPairedOrUnfinishedStagesSurviveExport()
    {
        await using var fixture = new Fixture();
        await fixture.ReadCycleAsync();
        fixture.Recorder.Record(PlaybackDiagnosticEvent.SettingsLayoutBegin, 1);
        fixture.Clock.Advance(TimeSpan.FromMilliseconds(150));
        fixture.Recorder.Record(PlaybackDiagnosticEvent.SettingsLayoutEnd, 1);
        fixture.Recorder.Record(PlaybackDiagnosticEvent.SettingsActivateBegin);
        var json = JsonSerializer.SerializeToUtf8Bytes(fixture.Recorder.Capture());
        Assert.InRange(json.Length, 1, PlaybackResponsivenessDiagnostics.MaximumFileBytes);
        using var document = JsonDocument.Parse(json);
        AssertNumericSchema(document.RootElement);
        Assert.Contains(fixture.Recorder.Capture().Entries, entry => entry.Event == PlaybackDiagnosticEvent.SettingsActivateBegin);
        Assert.DoesNotContain(fixture.Recorder.Capture().Entries, entry => entry.Event == PlaybackDiagnosticEvent.SettingsActivateEnd);

        for (var index = 0; index < 10_000; index++) fixture.Recorder.Record(PlaybackDiagnosticEvent.SettingsInput, long.MaxValue);
        Assert.InRange(JsonSerializer.SerializeToUtf8Bytes(fixture.Recorder.Capture()).Length, 1, PlaybackResponsivenessDiagnostics.MaximumFileBytes);
    }

    [Fact]
    public async Task LocalExportReplacesSingleReportWithoutRetainingInputDirectoryInContents()
    {
        var directory = Path.Combine(Path.GetTempPath(), "fileway-private-media-name-" + Guid.NewGuid().ToString("N"));
        try
        {
            await using var fixture = new Fixture();
            await fixture.ReadCycleAsync();
            await PlaybackResponsivenessDiagnostics.PersistAsync(directory, fixture.Recorder.Capture(), TestContext.Current.CancellationToken);
            fixture.Recorder.Record(PlaybackDiagnosticEvent.FullscreenInput);
            await PlaybackResponsivenessDiagnostics.PersistAsync(directory, fixture.Recorder.Capture(), TestContext.Current.CancellationToken);
            var file = Assert.Single(Directory.GetFiles(directory));
            Assert.Equal("player-responsiveness.json", Path.GetFileName(file));
            var contents = await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken);
            Assert.DoesNotContain("fileway-private-media-name", contents, StringComparison.Ordinal);
            using var document = JsonDocument.Parse(contents);
            AssertNumericSchema(document.RootElement);
        }
        finally { if (Directory.Exists(directory)) Directory.Delete(directory, recursive: true); }
    }

    private static void AssertNumericSchema(JsonElement element)
    {
        switch (element.ValueKind)
        {
            case JsonValueKind.Object:
                foreach (var property in element.EnumerateObject()) AssertNumericSchema(property.Value);
                break;
            case JsonValueKind.Array:
                foreach (var child in element.EnumerateArray()) AssertNumericSchema(child);
                break;
            default:
                Assert.True(element.ValueKind is JsonValueKind.Number or JsonValueKind.True or JsonValueKind.False);
                break;
        }
    }

    private sealed class TestClock : TimeProvider
    {
        private long _timestamp = 1;
        public override long TimestampFrequency => 1_000_000;
        public override long GetTimestamp() => Interlocked.Read(ref _timestamp);
        internal void Advance(TimeSpan duration) => Interlocked.Add(ref _timestamp, duration.Ticks / 10);
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly Channel<PlaybackDiagnosticReport> _reports = Channel.CreateUnbounded<PlaybackDiagnosticReport>();
        private readonly Channel<TaskCompletionSource> _delays = Channel.CreateUnbounded<TaskCompletionSource>();
        private TaskCompletionSource? _currentDelay;
        internal TestClock Clock { get; } = new();
        internal ConcurrentQueue<Action> Queue { get; } = new();
        internal PlaybackResponsivenessDiagnostics Recorder { get; }
        internal int Writes { get; private set; }

        internal Fixture(int capacity = PlaybackResponsivenessDiagnostics.Capacity, TimeSpan? duration = null)
        {
            Recorder = new PlaybackResponsivenessDiagnostics(action => { Queue.Enqueue(action); return true; }, (report, _) =>
            {
                Writes++;
                _reports.Writer.TryWrite(report);
                return Task.CompletedTask;
            }, Clock, async (_, cancellation) =>
            {
                var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
                _delays.Writer.TryWrite(completion);
                await completion.Task.WaitAsync(cancellation);
            }, capacity: capacity, duration: duration);
        }

        internal async Task<PlaybackDiagnosticReport> ReadCycleAsync()
        {
            var report = await _reports.Reader.ReadAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
            _currentDelay = await _delays.Reader.ReadAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
            return report;
        }

        internal void ResumeCycle() => _currentDelay!.TrySetResult();

        public async ValueTask DisposeAsync()
        {
            Recorder.Dispose();
            await Recorder.Completion.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        }
    }
}

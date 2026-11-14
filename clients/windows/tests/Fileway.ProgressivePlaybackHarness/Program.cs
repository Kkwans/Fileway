using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Transfers;

namespace Fileway.ProgressivePlaybackHarness;

internal static class Program
{
    private static async Task<int> Main(string[] arguments)
    {
        Evidence? evidence = null;
        try
        {
            var inputs = HarnessInputs.Parse(arguments);
            inputs.CreateOutputDirectory();
            evidence = new Evidence(inputs.OutputRoot);
            evidence.Stage("validate-pinned-runtime");
            string runtimeLockSha256 = inputs.ValidateRuntime();
            evidence.Stage("validate-fixed-synthetic-video");
            byte[] fixture = inputs.ReadFixture();
            var results = await RunAsync(inputs, fixture, evidence).ConfigureAwait(false);
            evidence.Complete(new
            {
                schemaVersion = 1, passed = true, coverage = "native-decoder-owned-progressive-read",
                visiblePixelsVerified = false, generalContainerPrefixSupportVerified = false,
                fixtureSha256 = MediaFixture.Sha256, runtimeLockSha256,
                width = MediaFixture.Width, height = MediaFixture.Height,
                totalBytes = MediaFixture.TotalBytes, results,
            });
            Console.WriteLine("PASS: real libmpv decoder, owned progressive reads, pause, cancellation, and native destruction.");
            return 0;
        }
        catch (Exception error)
        {
            // Never include exception messages, stack traces, input paths, or
            // environment data in uploadable synthetic evidence.
            evidence?.Complete(new
            {
                schemaVersion = 1, passed = false, stage = evidence.CurrentStage,
                failureType = error.GetType().Name, coverage = "native-decoder-owned-progressive-read",
                visiblePixelsVerified = false, generalContainerPrefixSupportVerified = false,
            });
            Console.Error.WriteLine("FAIL: progressive native verification; " + error.GetType().Name);
            return 1;
        }
        finally { evidence?.Dispose(); }
    }

    private static async Task<object> RunAsync(HarnessInputs inputs, byte[] fixture, Evidence evidence)
    {
        evidence.Stage("create-owned-writer");
        var access = new WindowsDownloadFileAccess();
        await using var destination = await access.CreateFreshAsync(Path.Combine(inputs.OutputRoot, "synthetic-download.y4m"),
            CancellationToken.None).ConfigureAwait(false);
        HarnessInputs.Require(destination is IReadableDownloadDestination, "owned-reader-capability-required");
        var readable = (IReadableDownloadDestination)destination;
        bool nativeReleased = false;
        try
        {
            int initial = MediaFixture.PrefixBytes(MediaFixture.InitialSeconds);
            int extended = MediaFixture.PrefixBytes(MediaFixture.ExtendedSeconds);
            await WriteThroughAsync(destination, fixture, initial).ConfigureAwait(false);
            var account = new AccountKey(Guid.NewGuid(), Guid.NewGuid(), "synthetic-native-probe");
            var run = new TransferRunToken(new TransferTaskRef(account, Guid.NewGuid()), Guid.NewGuid(), 1);
            var reader = new ObservedReadLease(await readable.AcquireReadLeaseAsync(run, fixture.Length,
                CancellationToken.None).ConfigureAwait(false));
            HarnessInputs.Require(reader.ReadablePrefixBytes == initial && initial < reader.TotalBytes, "initial-prefix-invalid");
            evidence.Stage("start-native-decoder-before-writer-complete", new { readablePrefixBytes = initial, totalBytes = fixture.Length });
            await using (var native = new NativePlaybackProbe(inputs.Runtime, reader))
            {
                native.Start();
                await WaitAsync(() =>
                {
                    var state = Healthy(native);
                    return state.FileLoaded && state.Width == MediaFixture.Width && state.Height == MediaFixture.Height &&
                        state.VideoReconfigurations > 0 && state.PositionChanges >= 4 && state.Position >= 1;
                }, "decoded-video-did-not-advance", TimeSpan.FromSeconds(15)).ConfigureAwait(false);
                HarnessInputs.Require(destination.Length == initial && !destination.IsPublished, "writer-completed-too-early");
                var first = native.Snapshot();
                evidence.Stage("decoded-video-advanced-before-writer-complete", new
                {
                    first.Position, first.PositionChanges, first.Width, first.Height, first.VideoReconfigurations,
                    readablePrefixBytes = reader.ReadablePrefixBytes, totalBytes = reader.TotalBytes,
                });

                evidence.Stage("wait-at-first-owned-prefix");
                double boundary = await WaitForBoundaryAsync(native, reader, MediaFixture.InitialSeconds).ConfigureAwait(false);
                HarnessInputs.Require(destination.Length == initial, "prefix-changed-without-writer");
                evidence.Stage("first-native-read-is-waiting", new { position = boundary, reader.LastBoundaryOffset, reader.PendingBoundaryReads });

                native.Pause(true);
                await WaitAsync(() => Healthy(native).Paused, "manual-pause-not-observed", TimeSpan.FromSeconds(5)).ConfigureAwait(false);
                double pausedPosition = native.Snapshot().Position;
                int completedBefore = reader.CompletedBoundaryReads;
                evidence.Stage("grow-owned-prefix-while-manually-paused");
                await WriteThroughAsync(destination, fixture, extended).ConfigureAwait(false);
                await WaitAsync(() => reader.CompletedBoundaryReads > completedBefore,
                    "blocked-native-read-did-not-resume", TimeSpan.FromSeconds(5)).ConfigureAwait(false);
                await ObservePausedAsync(native, pausedPosition).ConfigureAwait(false);
                HarnessInputs.Require(destination.Length == extended && extended < fixture.Length, "extended-prefix-invalid");
                evidence.Stage("manual-pause-preserved-as-writer-advances", new
                {
                    position = native.Snapshot().Position, readablePrefixBytes = reader.ReadablePrefixBytes,
                    reader.CompletedBoundaryReads,
                });

                native.Pause(false);
                await WaitAsync(() =>
                {
                    var state = Healthy(native);
                    return !state.Paused && state.Position >= boundary + 1;
                }, "native-playback-did-not-continue", TimeSpan.FromSeconds(10)).ConfigureAwait(false);
                var continued = native.Snapshot();
                HarnessInputs.Require(destination.Length == extended && destination.Length < fixture.Length,
                    "writer-completed-before-continuation");
                evidence.Stage("native-playback-continued-on-new-prefix", new { continued.Position, continued.PositionChanges });

                evidence.Stage("wait-at-second-owned-prefix");
                double secondBoundary = await WaitForBoundaryAsync(native, reader, MediaFixture.ExtendedSeconds).ConfigureAwait(false);
                int canceledBefore = reader.CanceledBoundaryReads;
                evidence.Stage("cancel-blocked-native-reader-only", new { position = secondBoundary, reader.PendingBoundaryReads });
                native.CancelReaders();
                await WaitAsync(() => reader.PendingBoundaryReads == 0 && reader.CanceledBoundaryReads > canceledBefore,
                    "native-reader-cancel-did-not-drain", TimeSpan.FromSeconds(5)).ConfigureAwait(false);
                evidence.Stage("destroy-real-native-instance");
                TimeSpan destroyTime = await native.DestroyAsync().ConfigureAwait(false);
                nativeReleased = true;
                HarnessInputs.Require(reader.IsDisposed, "reader-not-released-after-native-destroy");
                evidence.Stage("native-destroy-returned", new { elapsedMilliseconds = destroyTime.TotalMilliseconds, reader.CanceledBoundaryReads });

                // Reader close must not revoke, cancel, dispose, or publish its
                // writer. Use the SAME production destination after real destroy.
                evidence.Stage("writer-continues-after-reader-close");
                int writerProof = MediaFixture.PrefixBytes(20);
                await WriteThroughAsync(destination, fixture, writerProof).ConfigureAwait(false);
                long flushed = await destination.FlushToDiskAsync(CancellationToken.None).ConfigureAwait(false);
                var hash = await destination.CalculateSha256Async(CancellationToken.None).ConfigureAwait(false);
                HarnessInputs.Require(flushed == writerProof && hash.Length == writerProof &&
                    hash.Sha256 == Convert.ToHexStringLower(SHA256.HashData(fixture.AsSpan(0, writerProof))) &&
                    !destination.IsPublished && writerProof < fixture.Length, "writer-did-not-survive-reader-close");
                await using (var freshReader = await readable.AcquireReadLeaseAsync(run, fixture.Length, CancellationToken.None).ConfigureAwait(false))
                {
                    byte[] actual = new byte[64];
                    int count = await freshReader.ReadAsync(extended, actual, CancellationToken.None).ConfigureAwait(false);
                    HarnessInputs.Require(count == actual.Length && actual.AsSpan().SequenceEqual(fixture.AsSpan(extended, count)),
                        "fresh-owned-reader-after-native-close-failed");
                }
                evidence.Stage("writer-and-new-owned-reader-remain-usable", new { readablePrefixBytes = destination.Length, totalBytes = fixture.Length });
                return new
                {
                    decodedBeforeComplete = true, boundaryWaitThenContinue = true, manualPausePreserved = true,
                    blockedReaderCanceled = true, nativeDestroyReturned = true, readerReleasedAfterDestroy = true,
                    writerSurvivedReaderClose = true, freshOwnedReaderWorked = true,
                    initialPrefixBytes = initial, extendedPrefixBytes = extended, writerProofBytes = writerProof,
                    firstDecodedPosition = first.Position, firstBoundaryPosition = boundary,
                    continuedPosition = continued.Position, secondBoundaryPosition = secondBoundary,
                    nativeDestroyMilliseconds = destroyTime.TotalMilliseconds, reader.BytesRead,
                    reader.CompletedBoundaryReads, reader.CanceledBoundaryReads,
                };
            }
        }
        finally
        {
            // Successful cleanup is part of passing. Never delete a file via its
            // pathname. If native destruction did not return, retain all ownership
            // rather than pretend detached callbacks are safe; the process exits.
            if (nativeReleased)
            {
                evidence.Stage("revoke-drain-delete-owned-synthetic-partial");
                await readable.RevokeReadersAsync(CancellationToken.None).ConfigureAwait(false);
                await destination.DeleteOwnedPartialAsync(CancellationToken.None).ConfigureAwait(false);
            }
        }
    }

    private static async Task WriteThroughAsync(IDownloadDestination destination, byte[] bytes, int target)
    {
        HarnessInputs.Require(destination.Length <= target && target < bytes.Length, "bounded-prefix-write-required");
        while (destination.Length < target)
        {
            int offset = checked((int)destination.Length);
            int count = Math.Min(64 * 1024, target - offset);
            await destination.WriteAsync(bytes.AsMemory(offset, count), CancellationToken.None).ConfigureAwait(false);
        }
    }

    private static NativeSnapshot Healthy(NativePlaybackProbe native)
    {
        var state = native.Snapshot();
        HarnessInputs.Require(state.CommandError == 0 && state.EndReason == -1, "native-error-before-cancel");
        return state;
    }

    private static async Task<double> WaitForBoundaryAsync(NativePlaybackProbe native, ObservedReadLease reader, int seconds)
    {
        await WaitAsync(() => Healthy(native).Position >= seconds - 1 && reader.PendingBoundaryReads > 0,
            "native-did-not-reach-prefix-boundary", TimeSpan.FromSeconds(15)).ConfigureAwait(false);
        var watch = Stopwatch.StartNew();
        double previous = Healthy(native).Position;
        while (watch.Elapsed < TimeSpan.FromSeconds(5))
        {
            await Task.Delay(600).ConfigureAwait(false);
            double current = Healthy(native).Position;
            if (reader.PendingBoundaryReads > 0 && Math.Abs(current - previous) < 0.05)
                return current;
            previous = current;
        }
        throw new InvalidOperationException("native-prefix-wait-was-not-stable");
    }

    private static async Task ObservePausedAsync(NativePlaybackProbe native, double position)
    {
        var watch = Stopwatch.StartNew();
        while (watch.Elapsed < TimeSpan.FromMilliseconds(750))
        {
            var state = Healthy(native);
            HarnessInputs.Require(state.Paused && Math.Abs(state.Position - position) <= 0.25, "manual-pause-lost-during-growth");
            await Task.Delay(25).ConfigureAwait(false);
        }
    }

    private static async Task WaitAsync(Func<bool> condition, string failure, TimeSpan timeout)
    {
        var watch = Stopwatch.StartNew();
        while (!condition())
        {
            if (watch.Elapsed >= timeout) throw new TimeoutException(failure);
            await Task.Delay(25).ConfigureAwait(false);
        }
    }
}

internal sealed class Evidence : IDisposable
{
    private readonly string _outputRoot;
    private readonly StreamWriter _events;
    private readonly Stopwatch _watch = Stopwatch.StartNew();
    private bool _completed;
    internal string CurrentStage { get; private set; } = "initialize";
    private static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };

    internal Evidence(string outputRoot)
    {
        _outputRoot = outputRoot;
        _events = new StreamWriter(new FileStream(Path.Combine(outputRoot, "events.jsonl"), FileMode.CreateNew,
            FileAccess.Write, FileShare.Read)) { AutoFlush = true };
    }

    internal void Stage(string stage, object? data = null)
    {
        CurrentStage = stage;
        _events.WriteLine(JsonSerializer.Serialize(new { stage, elapsedMilliseconds = _watch.Elapsed.TotalMilliseconds, data }, Json));
    }

    internal void Complete(object report)
    {
        if (_completed) return;
        using var file = new FileStream(Path.Combine(_outputRoot, "report.json"), FileMode.CreateNew, FileAccess.Write, FileShare.Read);
        JsonSerializer.Serialize(file, report, Json);
        file.Flush(flushToDisk: true);
        _completed = true;
    }

    public void Dispose() => _events.Dispose();
}

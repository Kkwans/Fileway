using Fileway.Core.Contracts;

namespace Fileway.Core.Integration;

/// <summary>One bounded, best-effort history mutation for one confirmed remote open.</summary>
public sealed class RecentOpenRecording(IRecentAccessService service, RemoteResourceRef resource, long generation)
{
    private static readonly TimeSpan Deadline = TimeSpan.FromSeconds(5);
    private readonly object _gate = new();
    private Task? _completion;

    /// <summary>
    /// The caller must confirm decode/playback success and pass its original generation and lifetime.
    /// Never awaits network work on the caller's thread. Every result, including unknown, consumes this attempt.
    /// The exact bound resource is retained; it must never be reconstructed from a display path.
    /// </summary>
    public Task ConfirmOpenedAsync(long confirmedGeneration, CancellationToken cancellationToken)
    {
        lock (_gate)
        {
            if (confirmedGeneration != generation) return Task.CompletedTask;
            return _completion ??= Task.Run(() => RecordAsync(cancellationToken), CancellationToken.None);
        }
    }

    private async Task RecordAsync(CancellationToken cancellationToken)
    {
        try
        {
            using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            deadline.CancelAfter(Deadline);
            deadline.Token.ThrowIfCancellationRequested();
            var mutation = service.RecordOpenedAsync(resource, deadline.Token);
            // Observe even a provider which ignores cancellation and faults after our bounded wait.
            _ = mutation.ContinueWith(static completed => { _ = completed.Exception; },
                CancellationToken.None, TaskContinuationOptions.OnlyOnFaulted | TaskContinuationOptions.ExecuteSynchronously,
                TaskScheduler.Default);
            await mutation.WaitAsync(deadline.Token).ConfigureAwait(false);
        }
        catch (Exception)
        {
            // Secondary history never reverses a successful open or leaks private provider errors.
            // No retry: cancellation or a lost reply can mean the mutation already reached the server.
        }
    }
}

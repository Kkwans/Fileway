using Fileway.Core.Contracts;

namespace Fileway.Core.Integration;

/// <summary>Explicit queue authority for one verified session and activation; retired tokens cannot control replacement work.</summary>
public readonly record struct UploadActivationToken(AccountKey Account, Guid SessionId, Guid Id);

/// <summary>Presentation evidence without local paths, remote wire paths, source identities, credentials or capabilities.</summary>
public sealed record UploadTaskSnapshot(TransferTaskRef Task, TransferIntent Intent, TransferPhase Phase,
    long? ConfirmedOffset, long? TotalBytes, bool RemotePublished, bool RemoteCanceled,
    TransferUncertainty Uncertainty, ErrorInfo? Error, long Revision, long Generation);

/// <summary>Updates remain bound to their original activation; body progress is never durable confirmation.</summary>
public sealed record UploadQueueUpdate(UploadActivationToken Activation, UploadTaskSnapshot Task,
    UploadTaskProgress? Progress = null);

/// <summary>Explicit scheduling of confirmed, already-persisted file tasks. Activation never discovers or starts stored work.</summary>
public interface IUploadCoordinator : IAsyncDisposable
{
    event EventHandler<UploadQueueUpdate>? TaskChanged;
    Task<UploadActivationToken> ActivateAsync(ConnectionSession session, CancellationToken cancellationToken);
    Task<UploadTaskSnapshot> EnqueueAsync(UploadActivationToken activation, TransferTaskRef task, CancellationToken cancellationToken);
    Task<UploadTaskSnapshot> ResumeAsync(UploadActivationToken activation, TransferTaskRef task, CancellationToken cancellationToken);
    /// <summary>Persists Pause before stopping and draining local execution; unknown remote evidence remains unknown.</summary>
    Task<UploadTaskSnapshot> PauseAsync(UploadActivationToken activation, TransferTaskRef task, CancellationToken cancellationToken);
    /// <summary>Persists Cancel before local drain, then reconciles through the runner using a normal execution slot.</summary>
    Task<UploadTaskSnapshot> CancelAsync(UploadActivationToken activation, TransferTaskRef task, CancellationToken cancellationToken);
    /// <summary>Closes admission before stopping and awaiting local executions. Never deletes remote uploads.
    /// Once draining begins, caller cancellation cannot abandon handle release; persistence failures are reported after local drain.</summary>
    Task StopAndDrainAsync(UploadActivationToken activation, CancellationToken cancellationToken);
}

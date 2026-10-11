namespace Fileway.Core.Contracts;

public enum TransferDirection { Upload = 0, Download = 1 }
public enum TransferIntent { Run = 0, Pause = 1, Cancel = 2 }
public enum TransferPhase
{
    Queued = 0, Preparing = 1, Running = 2, Paused = 3, WaitingNetwork = 4, WaitingAuthentication = 5,
    Conflict = 6, SourceChanged = 7, Verifying = 8, Publishing = 9, Completed = 10,
    CancelRequested = 11, Canceled = 12, Failed = 13, Reconciling = 14, OutcomeUnknown = 15, Skipped = 16,
}

[Flags]
public enum TransferUncertainty { None = 0, RemoteWrite = 1, LocalPublish = 2 }

public readonly record struct TransferTaskRef(AccountKey Account, Guid Id);

/// <summary>Execution ownership, replaced only by a new claim; user intent does not erase late acknowledgements.</summary>
public readonly record struct TransferRunToken(TransferTaskRef Task, Guid Id, long Generation);

/// <summary>Immutable file plan. Remote is the upload target or download source; LocalPath has the opposite role.</summary>
public sealed record TransferPlan(TransferTaskRef Reference, TransferDirection Direction, RemoteResourceRef Remote,
    string LocalPath, FileConflictPolicy Conflict = FileConflictPolicy.Fail, bool AutomaticResume = false,
    Guid? BatchId = null, int Ordinal = 0)
{
    public override string ToString() => "TransferPlan (private locations redacted)";
}

/// <summary>Pinned source observations, not proof of an immutable historical content snapshot.</summary>
public sealed record TransferSourceSnapshot(long Length, DateTimeOffset? Modified = null,
    string? SourceVersion = null, string? NativeFileIdentity = null)
{
    public override string ToString() => "TransferSourceSnapshot (identity redacted)";
}

/// <summary>Owned file evidence; persistence alone never authorizes deletion or proves real path/handle ownership.</summary>
public sealed record TransferWorkFiles(string? PartialPath = null, string? PartialIdentity = null,
    string? OriginalTargetIdentity = null, string? BackupPath = null, string? BackupIdentity = null,
    string? PublishedFileIdentity = null)
{
    public override string ToString() => "TransferWorkFiles (private locations redacted)";
}

/// <summary>ConfirmedOffset is server-acknowledged for uploads, or flushed local prefix for downloads; null is unknown.</summary>
public sealed record TransferCheckpoint(TransferPhase Phase, TransferSourceSnapshot? Source = null,
    long? ConfirmedOffset = null, TransferWorkFiles? Files = null,
    string? LocalSha256 = null, string? ServerSha256 = null,
    bool RemotePublished = false, bool RemoteCanceled = false,
    TransferUncertainty Uncertainty = TransferUncertainty.None, ErrorInfo? Error = null)
{
    public override string ToString() => "TransferCheckpoint (private evidence redacted)";
}

/// <summary>Durable execution evidence, distinct from the safe presentation snapshot and from file business journal.</summary>
public sealed record TransferTaskRecord(TransferPlan Plan, TransferIntent Intent, TransferCheckpoint Checkpoint,
    long Revision, long Generation, TransferRunToken? Run, DateTimeOffset CreatedAt, DateTimeOffset UpdatedAt)
{
    public override string ToString() => "TransferTaskRecord (private transfer data redacted)";
}

public sealed record TransferTaskPage(IReadOnlyList<TransferTaskRecord> Items, string? NextCursor);

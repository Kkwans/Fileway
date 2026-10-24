using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;

namespace Fileway.Infrastructure.Services;

public sealed partial class UploadApplicationService
{
    public async Task<UploadItem> SubmitAsync(UploadSubmission submission, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(submission);
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var binding = Require(submission.Context);
            var plan = SubmissionPlan(submission);
            RequireCreate(binding);
            var existing = await StorageAsync(token => _tasks.GetTransferAsync(plan.Reference, token), cancellationToken).ConfigureAwait(false);
            RequireCurrent(binding);
            if (existing is not null)
            {
                if (existing.Plan != plan) throw Safe(ErrorCode.Conflict);
                return Observe(binding, existing, submission.Parent);
            }
            var parent = await _files.GetAsync(submission.Parent, cancellationToken).ConfigureAwait(false);
            RequireCurrent(binding);
            RequireCreate(binding);
            if (!parent.IsDirectory || parent.Resource.Account != submission.Context.Account ||
                parent.Resource.WirePath != submission.Parent.WirePath) throw Safe(ErrorCode.InvalidRequest);
            var created = await StorageAsync(token => _tasks.CreateTransferAsync(plan, token), cancellationToken).ConfigureAwait(false);
            RequireCurrent(binding);
            _ = Observe(binding, created, submission.Parent);
            try
            {
                Task<UploadTaskSnapshot> admitted;
                lock (_gate)
                {
                    RequireCurrent(binding);
                    RequireCreate(binding);
                    binding.Items[plan.Reference].Scheduled = true;
                    admitted = _coordinator.EnqueueAsync(binding.Activation, plan.Reference, cancellationToken);
                }
                var snapshot = await admitted.ConfigureAwait(false);
                RequireCurrent(binding);
                return Observe(binding, snapshot);
            }
            catch (Exception error)
            {
                RecordCommandFailure(binding, plan.Reference, error);
                throw Normalize(error);
            }
        }
        catch (Exception error) { throw Normalize(error); }
        finally { _commands.Release(); }
    }

    private static TransferPlan SubmissionPlan(UploadSubmission submission)
    {
        if (submission.RequestId == Guid.Empty || submission.Parent is null || submission.Parent.Account != submission.Context.Account)
            throw Safe(ErrorCode.InvalidRequest);
        string path = TransferTaskValidation.LocalFilePath(submission.LocalPath);
        string name = path[(path.LastIndexOf('\\') + 1)..];
        // Local Windows lexical validation and strict UTF-8 precede any filesystem/body access. Preserve the exact leaf name.
        _ = StrictUtf8.GetByteCount(name);
        string wire = (submission.Parent.WirePath == "/" ? "" : submission.Parent.WirePath) + "/" + Uri.EscapeDataString(name);
        string display = (submission.Parent.DisplayPath == "/" ? "" : submission.Parent.DisplayPath) + "/" + name;
        var target = new RemoteResourceRef(submission.Context.Account, wire, name, display);
        return TransferTaskValidation.Plan(new(new(submission.Context.Account, submission.RequestId), TransferDirection.Upload,
            target, path, FileConflictPolicy.Fail, AutomaticResume: false, submission.BatchId, submission.Ordinal));
    }

    public async Task<RemoteResourceRef> EnsureRemoteTreeAsync(UploadContext context, RemoteResourceRef parent,
        string relativeDirectory, CancellationToken cancellationToken)
    {
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var binding = Require(context);
            RequireCreate(binding);
            if (parent is null || parent.Account != context.Account || parent.SourceVersion is not null)
                throw Safe(ErrorCode.InvalidRequest);
            string relative = relativeDirectory ?? "";
            var segments = relative.Length == 0
                ? Array.Empty<string>()
                : relative.Replace('\\', '/').Split('/', StringSplitOptions.RemoveEmptyEntries);
            if (segments.Length > 32) throw Safe(ErrorCode.InvalidRequest);
            var current = parent;
            foreach (var segment in segments)
            {
                string normalized = segment.TrimEnd(' ', '.');
                if (normalized.Length == 0 || normalized.Length > 255 || normalized.Any(char.IsControl))
                    throw Safe(ErrorCode.InvalidRequest);
                _ = StrictUtf8.GetByteCount(normalized);
                try
                {
                    var created = await _files.CreateDirectoryAsync(current, normalized, cancellationToken).ConfigureAwait(false);
                    RequireCurrent(binding);
                    if (created.Outcome != OperationOutcome.Confirmed && created.Error is { } failure)
                        throw new FilewayException(failure);
                }
                catch (FilewayException error) when (error.Error.Code == ErrorCode.Conflict)
                {
                    // An existing same-name entry is acceptable only if it is a directory; the
                    // verification below rejects a same-name file instead of replacing it.
                }
                RequireCurrent(binding);
                string wire = (current.WirePath == "/" ? "" : current.WirePath) + "/" + Uri.EscapeDataString(normalized);
                string display = (current.DisplayPath == "/" ? "" : current.DisplayPath) + "/" + normalized;
                var child = new RemoteResourceRef(context.Account, wire, normalized, display);
                var observed = await _files.GetAsync(child, cancellationToken).ConfigureAwait(false);
                RequireCurrent(binding);
                if (!observed.IsDirectory || observed.Resource.WirePath != wire) throw Safe(ErrorCode.Conflict);
                current = child;
            }
            return current;
        }
        catch (Exception error) { throw Normalize(error); }
        finally { _commands.Release(); }
    }

    private void RequireCreate(Binding binding)
    {
        lock (_gate) if (!binding.Session.Permissions.Create) throw Safe(ErrorCode.PermissionDenied);
    }

    public async Task<UploadPage> ListAsync(UploadContext context, string? cursor, int limit, CancellationToken cancellationToken)
    {
        var binding = Require(context);
        var page = await StorageAsync(token => _tasks.ListTransfersAsync(context.Account, cursor, limit, token), cancellationToken).ConfigureAwait(false);
        RequireCurrent(binding);
        var items = new List<UploadItem>();
        foreach (var record in page.Items)
        {
            if (record.Plan.Reference.Account != context.Account) throw Safe(ErrorCode.ProtocolMismatch);
            if (record.Plan.Direction == TransferDirection.Upload) items.Add(Observe(binding, record, publish: false));
        }
        RequireCurrent(binding);
        return new(items, page.NextCursor);
    }

    public Task<UploadItem> PauseAsync(UploadContext context, TransferTaskRef task, CancellationToken cancellationToken) =>
        ControlAsync(context, task, UploadStatus.Pausing, cancellationToken);
    public Task<UploadItem> ResumeAsync(UploadContext context, TransferTaskRef task, CancellationToken cancellationToken) =>
        ControlAsync(context, task, UploadStatus.Queued, cancellationToken);
    public Task<UploadItem> CancelAsync(UploadContext context, TransferTaskRef task, CancellationToken cancellationToken) =>
        ControlAsync(context, task, UploadStatus.Canceling, cancellationToken);

    private async Task<UploadItem> ControlAsync(UploadContext context, TransferTaskRef task, UploadStatus control,
        CancellationToken cancellationToken)
    {
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        Binding? binding = null;
        try
        {
            binding = Require(context, task);
            var record = await ReadAsync(task, cancellationToken).ConfigureAwait(false);
            RequireCurrent(binding);
            if (record.Plan.Reference != task || record.Plan.Direction != TransferDirection.Upload) throw Safe(ErrorCode.InvalidRequest);
            // A user-facing "reconcile" action never reverses an accepted cancellation intent.
            if (control == UploadStatus.Queued && record.Intent == TransferIntent.Cancel) control = UploadStatus.Canceling;
            if (control == UploadStatus.Queued) RequireCreate(binding);
            _ = Observe(binding, record, publish: false);
            Task<UploadTaskSnapshot> pending;
            lock (_gate)
            {
                RequireCurrent(binding);
                if (control == UploadStatus.Queued) RequireCreate(binding);
                var item = binding.Items[task];
                item.Control = control;
                item.Scheduled = control == UploadStatus.Queued;
                _ = Observe(binding, Snapshot(record));
                // Only initiate the command while this exact authority is current. No await occurs under the state gate.
                pending = control switch
                {
                    UploadStatus.Pausing => _coordinator.PauseAsync(binding.Activation, task, cancellationToken),
                    UploadStatus.Queued => _coordinator.ResumeAsync(binding.Activation, task, cancellationToken),
                    _ => _coordinator.CancelAsync(binding.Activation, task, cancellationToken),
                };
            }
            var result = await pending.ConfigureAwait(false);
            RequireCurrent(binding);
            lock (_gate) binding.Items[task].Control = null;
            return Observe(binding, result);
        }
        catch (Exception error)
        {
            if (binding is not null) RecordCommandFailure(binding, task, error);
            throw Normalize(error);
        }
        finally { _commands.Release(); }
    }

    private void RecordCommandFailure(Binding binding, TransferTaskRef task, Exception error)
    {
        lock (_gate)
        {
            if (!ReferenceEquals(_current, binding) || !binding.Items.TryGetValue(task, out var item) || item.Snapshot is not { } snapshot) return;
            item.Control = null;
            item.Scheduled = false;
            item.Snapshot = snapshot with { Error = Normalize(error).Error };
            QueueUpdateLocked(Project(binding, item));
        }
    }

    private UploadItem Observe(Binding binding, TransferTaskRecord record, RemoteResourceRef? originalParent = null, bool publish = true)
    {
        if (record.Plan.Direction != TransferDirection.Upload || record.Plan.Reference.Account != binding.Context.Account)
            throw Safe(ErrorCode.ProtocolMismatch);
        lock (_gate)
        {
            RequireCurrent(binding);
            if (!binding.Items.ContainsKey(record.Plan.Reference))
            {
                var target = record.Plan.Remote;
                int split = target.WirePath.LastIndexOf('/');
                string wire = split == 0 ? "/" : target.WirePath[..split];
                int displaySplit = target.DisplayPath.LastIndexOf('/');
                string display = displaySplit <= 0 ? "/" : target.DisplayPath[..displaySplit];
                var parent = originalParent ?? new RemoteResourceRef(binding.Context.Account, wire,
                    display[(display.LastIndexOf('/') + 1)..], display);
                if (parent.Account != binding.Context.Account) throw Safe(ErrorCode.ProtocolMismatch);
                binding.Items.Add(record.Plan.Reference, new(parent, target.DisplayName, target.DisplayPath));
            }
            return Observe(binding, Snapshot(record), publish: publish);
        }
    }

    private UploadItem Observe(Binding binding, UploadTaskSnapshot snapshot, UploadTaskProgress? progress = null, bool publish = true)
    {
        lock (_gate)
        {
            RequireCurrent(binding);
            if (snapshot.Task.Account != binding.Context.Account || !binding.Items.TryGetValue(snapshot.Task, out var item))
                throw Safe(ErrorCode.ProtocolMismatch);
            if (item.Snapshot is { } previous && (snapshot.Generation < previous.Generation ||
                snapshot.Generation == previous.Generation && (snapshot.Revision < previous.Revision ||
                    Terminal(previous) && !Terminal(snapshot)))) return Project(binding, item);
            if (item.Snapshot is { } accepted && accepted.Generation == snapshot.Generation && accepted.Revision == snapshot.Revision &&
                accepted.Error is not null && snapshot.Error is null) snapshot = snapshot with { Error = accepted.Error };
            if (item.Progress is { } previousProgress && previousProgress.Run.Generation != snapshot.Generation) item.Progress = null;
            item.Snapshot = snapshot with { Error = snapshot.Error is { } error ? Safe(error.Code, error.HttpStatus, error.Retryable).Error : null };
            if (Terminal(snapshot) || snapshot.Error is not null) { item.Scheduled = false; item.Control = null; item.Progress = null; }
            if (!Terminal(snapshot) && progress is not null && progress.Run.Task == snapshot.Task && progress.Run.Generation == snapshot.Generation)
                item.Progress = progress;
            var projected = Project(binding, item);
            if (publish) QueueUpdateLocked(projected);
            return projected;
        }
    }

    private static UploadTaskSnapshot Snapshot(TransferTaskRecord record) => new(record.Plan.Reference, record.Intent, record.Checkpoint.Phase,
        record.Checkpoint.ConfirmedOffset, record.Checkpoint.Source?.Length, record.Checkpoint.RemotePublished,
        record.Checkpoint.RemoteCanceled, record.Checkpoint.Uncertainty, record.Checkpoint.Error, record.Revision, record.Generation);

    private static bool Terminal(UploadTaskSnapshot snapshot) => snapshot.Phase is TransferPhase.Completed or TransferPhase.Canceled or TransferPhase.Skipped;

    private static UploadItem Project(Binding binding, Presentation item)
    {
        var snapshot = item.Snapshot ?? throw Safe(ErrorCode.ProtocolMismatch);
        UploadStatus status;
        bool protectedExecution = item.Scheduled && snapshot.Error is null && snapshot.Phase is TransferPhase.Preparing or
            TransferPhase.Running or TransferPhase.Verifying or TransferPhase.Publishing or TransferPhase.Reconciling or TransferPhase.CancelRequested;
        if (snapshot.Uncertainty != TransferUncertainty.None && !protectedExecution)
            status = item.Control == UploadStatus.Canceling ? UploadStatus.Canceling : UploadStatus.OutcomeUnknown;
        else if (snapshot.Phase == TransferPhase.Completed) status = snapshot.RemotePublished ? UploadStatus.Completed : UploadStatus.OutcomeUnknown;
        else if (snapshot.Phase == TransferPhase.Canceled) status = UploadStatus.Canceled;
        else if (snapshot.Phase == TransferPhase.Skipped) status = UploadStatus.Skipped;
        else if (item.Control is { } controlling) status = controlling;
        else if (snapshot.Intent == TransferIntent.Cancel) status = item.Scheduled && snapshot.Phase == TransferPhase.CancelRequested
            ? UploadStatus.Canceling : UploadStatus.OutcomeUnknown;
        else if (snapshot.Error is not null && snapshot.Phase is TransferPhase.Queued or TransferPhase.Preparing or TransferPhase.Running) status = UploadStatus.Failed;
        else if (!item.Scheduled && snapshot.Phase is TransferPhase.Preparing or TransferPhase.Running or TransferPhase.Verifying or
            TransferPhase.Publishing or TransferPhase.Reconciling) status = UploadStatus.NotStarted;
        else if (snapshot.Intent == TransferIntent.Pause) status = snapshot.Phase is TransferPhase.Preparing or TransferPhase.Running or TransferPhase.Verifying or TransferPhase.Publishing
            ? UploadStatus.Pausing : UploadStatus.Paused;
        else status = snapshot.Phase switch
        {
            TransferPhase.Queued => item.Scheduled ? UploadStatus.Queued : UploadStatus.NotStarted,
            TransferPhase.Preparing => UploadStatus.Preparing,
            TransferPhase.Running => UploadStatus.Uploading,
            TransferPhase.Paused => item.Scheduled ? UploadStatus.Queued : UploadStatus.Paused,
            TransferPhase.WaitingNetwork => UploadStatus.WaitingNetwork,
            TransferPhase.WaitingAuthentication => UploadStatus.WaitingAuthentication,
            TransferPhase.Conflict => UploadStatus.Conflict,
            TransferPhase.SourceChanged => UploadStatus.SourceChanged,
            TransferPhase.Verifying => UploadStatus.Verifying,
            TransferPhase.Publishing => UploadStatus.Publishing,
            TransferPhase.Reconciling => UploadStatus.Reconciling,
            TransferPhase.OutcomeUnknown => UploadStatus.OutcomeUnknown,
            _ => UploadStatus.Failed,
        };
        return new(binding.Context, item.Parent, item.Name, item.Destination, status, snapshot, item.Progress);
    }

    private void QueueTaskChanged(object? sender, UploadQueueUpdate update)
    {
        Binding? binding;
        lock (_gate)
        {
            binding = _current;
            if (_disposed || binding is null || binding.Activation != update.Activation) return;
            if (binding.Items.ContainsKey(update.Task.Task))
            {
                _ = ObserveQueue(binding, update);
                return;
            }
        }
        _ = ObserveUnknownAsync(binding, update);
    }

    private async Task ObserveUnknownAsync(Binding binding, UploadQueueUpdate update)
    {
        try
        {
            var record = await ReadAsync(update.Task.Task, CancellationToken.None).ConfigureAwait(false);
            _ = Observe(binding, record, publish: false);
            _ = ObserveQueue(binding, update);
        }
        catch (Exception) { /* Retired epochs and unreadable unowned metadata cannot publish into a new application session. */ }
    }

    private UploadItem ObserveQueue(Binding binding, UploadQueueUpdate update)
    {
        lock (_gate)
        {
            RequireCurrent(binding);
            // Unlike a historical store read, an event from this exact queue activation proves current admission.
            if (update.Task.Error is null && update.Task.Phase is TransferPhase.Queued or TransferPhase.Preparing or TransferPhase.Running or
                TransferPhase.Verifying or TransferPhase.Publishing or TransferPhase.Reconciling or TransferPhase.CancelRequested)
                binding.Items[update.Task.Task].Scheduled = true;
            return Observe(binding, update.Task, update.Progress);
        }
    }

    private void QueueUpdateLocked(UploadItem item)
    {
        _updates[item.Snapshot.Task] = item;
        if (_publishing) return;
        _publishing = true;
        _ = Task.Run(PublishUpdates);
    }

    private void PublishUpdates()
    {
        while (true)
        {
            UploadItem? item;
            lock (_gate)
            {
                if (_updates.Count == 0) { _publishing = false; return; }
                var first = _updates.First();
                _updates.Remove(first.Key);
                item = !_disposed && _current?.Context == first.Value.Context ? first.Value : null;
            }
            if (item is not null) PublishEvent(this, TaskChanged, item);
        }
    }
}

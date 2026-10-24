using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;

namespace Fileway.Infrastructure.Services;

public sealed partial class UploadApplicationService
{
    private sealed record DownloadDraft(DownloadPreview Preview, TransferPlan Plan);

    private sealed class DownloadPresentation(RemoteResourceRef source, string destination)
    {
        internal RemoteResourceRef Source { get; } = source;
        internal string Destination { get; } = destination;
        internal DownloadTaskSnapshot? Snapshot { get; set; }
        internal DownloadTaskProgress? Progress { get; set; }
        internal bool Scheduled { get; set; }
        internal DownloadStatus? Control { get; set; }
    }

    private sealed class DownloadWork(Binding binding, TransferTaskRef task, CancellationToken caller)
    {
        internal Binding Binding { get; } = binding;
        internal TransferTaskRef Task { get; } = task;
        internal CancellationTokenSource Stop { get; } = CancellationTokenSource.CreateLinkedTokenSource(caller);
        internal CancellationTokenRegistration StopRegistration { get; set; }
        internal Task? StopRequested { get; set; }
        internal TaskCompletionSource<DownloadCompletion> Completion { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal LinkedListNode<DownloadWork>? Node { get; set; }
        internal bool Started { get; set; }
        internal bool Canceling { get; set; }
        internal bool Resume { get; init; }
        internal DownloadTaskProgress? PendingProgress { get; set; }
        internal bool ReadingProgress { get; set; }
    }

    private sealed record DownloadCompletion(TransferTaskRecord? Record, FilewayException? Failure);

    private readonly Dictionary<TransferTaskRef, DownloadWork> _downloadExecutions = [];
    private readonly Dictionary<TransferTaskRef, DownloadItem> _downloadUpdates = [];
    private bool _downloadPublishing;
    private event EventHandler<DownloadItem>? DownloadChanged;

    event EventHandler<DownloadItem>? IDownloadService.TaskChanged
    {
        add => DownloadChanged += value;
        remove => DownloadChanged -= value;
    }

    DownloadContext? IDownloadService.CurrentContext
    {
        get { lock (_gate) return !_disposed && _downloads is not null ? _current?.DownloadContext : null; }
    }

    private Binding RequireDownloadContext(DownloadContext context, TransferTaskRef? task = null)
    {
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_downloads is null) throw DownloadSafe(ErrorCode.CapabilityMissing);
            if (_current is not { } binding || binding.DownloadContext != context) throw DownloadSafe(ErrorCode.SourceChanged);
            if (task is { } reference && (reference.Account != context.Account || reference.Id == Guid.Empty))
                throw DownloadSafe(ErrorCode.InvalidRequest);
            return binding;
        }
    }

    private void RequireDownloadPermission(Binding binding)
    {
        lock (_gate) if (!binding.Session.Permissions.Download) throw DownloadSafe(ErrorCode.PermissionDenied);
    }

    Task<DownloadPreview> IDownloadService.PrepareAsync(DownloadContext context, RemoteResourceRef source, CancellationToken cancellationToken) =>
        PrepareDownloadAsync(context, source, localName: null, explicitLocalName: false, cancellationToken);

    Task<DownloadPreview> IDownloadService.PrepareAsAsync(DownloadContext context, RemoteResourceRef source, string localName,
        CancellationToken cancellationToken) =>
        PrepareDownloadAsync(context, source, localName, explicitLocalName: true, cancellationToken);

    private async Task<DownloadPreview> PrepareDownloadAsync(DownloadContext context, RemoteResourceRef source, string? localName,
        bool explicitLocalName, CancellationToken cancellationToken)
    {
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var binding = RequireDownloadContext(context);
            RequireDownloadPermission(binding);
            if (source is null || source.Account != context.Account || source.SourceVersion is not null)
                throw DownloadSafe(ErrorCode.InvalidRequest);
            string? requestedLeaf = explicitLocalName ? DownloadLocalName(localName) : null;
            // Conditional-first: servers that advertise conditionalDownloadV1 return versioned
            // metadata, which the fresh (legacy) reader rejects; version pinning is also what
            // makes this task durable-resumable later.
            var sources = _downloads!.Sources;
            var metadata = await (sources is IConditionalDownloadSourceProvider conditional && conditional.CanUseConditionalDownload(source)
                ? conditional.PrepareConditionalDownloadAsync(source, cancellationToken)
                : sources.PrepareDownloadAsync(source, cancellationToken)).ConfigureAwait(false);
            RequireCurrent(binding);
            if (metadata.IsDirectory || metadata.Size < 0 || metadata.Resource.Account != context.Account ||
                metadata.Resource.WirePath != source.WirePath) throw DownloadSafe(ErrorCode.InvalidRequest);
            var settings = await StorageAsync(token => _downloads.StorageSettings.ReadAsync(token), cancellationToken).ConfigureAwait(false);
            RequireCurrent(binding);
            var preparedSource = source with { DisplayName = metadata.Resource.DisplayName, DisplayPath = metadata.Resource.DisplayPath };
            string confirmedName = requestedLeaf ?? preparedSource.DisplayName;
            string path = DownloadDestination(settings.Active.DownloadDirectory, confirmedName);
            var draft = new DownloadDraftRef(Guid.NewGuid());
            var plan = TransferTaskValidation.Plan(new(new(context.Account, draft.Id), TransferDirection.Download,
                preparedSource, path, FileConflictPolicy.Fail, AutomaticResume: false));
            var preview = new DownloadPreview(context, draft, preparedSource, confirmedName, path, metadata.Size);
            lock (_gate)
            {
                RequireCurrent(binding);
                RequireDownloadPermission(binding);
                // Unconfirmed previews never become durable work. Bound their presentation-only lifetime.
                if (binding.DownloadDrafts.Count >= 128) binding.DownloadDrafts.Remove(binding.DownloadDrafts.First().Key);
                binding.DownloadDrafts.Add(draft, new(preview, plan));
            }
            return preview;
        }
        catch (Exception error) { throw NormalizeDownload(error); }
        finally { _commands.Release(); }
    }

    private static string DownloadLocalName(string? name)
    {
        if (string.IsNullOrWhiteSpace(name) || name.Contains('/') || name.Contains('\\') || name.Any(char.IsControl))
            throw DownloadSafe(ErrorCode.InvalidRequest);
        // Validate one leaf with the existing lexical Win32 rules, including strict Unicode,
        // reserved names and ADS. This anchor is metadata only; no path is opened or selected here.
        _ = TransferTaskValidation.LocalFilePath(@"C:\" + name);
        return name;
    }

    private static string DownloadDestination(string directory, string name)
    {
        if (string.IsNullOrWhiteSpace(directory) || string.IsNullOrWhiteSpace(name) || name.Contains('/') || name.Contains('\\') ||
            name.Any(char.IsControl)) throw DownloadSafe(ErrorCode.InvalidRequest);
        _ = StrictUtf8.GetByteCount(name);
        // Settings supplies an effective Windows directory; never decode opaque remote bytes or silently rename its leaf.
        string root = directory.Replace('/', '\\').TrimEnd('\\');
        return TransferTaskValidation.LocalFilePath(root + "\\" + name);
    }

    async Task<DownloadItem> IDownloadService.SubmitAsync(DownloadPreview preview, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(preview);
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var binding = RequireDownloadContext(preview.Context);
            DownloadDraft draft;
            lock (_gate)
            {
                RequireCurrent(binding);
                if (preview.Draft.Id == Guid.Empty || !binding.DownloadDrafts.TryGetValue(preview.Draft, out draft!) || draft.Preview != preview)
                    throw DownloadSafe(ErrorCode.InvalidRequest);
                RequireDownloadPermission(binding);
            }
            var existing = await StorageAsync(token => _tasks.GetTransferAsync(draft.Plan.Reference, token), cancellationToken).ConfigureAwait(false);
            RequireCurrent(binding);
            if (existing is not null)
            {
                if (existing.Plan != draft.Plan) throw DownloadSafe(ErrorCode.Conflict);
                return ObserveDownload(binding, existing);
            }
            lock (_gate)
            {
                RequireCurrent(binding);
                RequireDownloadQueueCapacityLocked(binding);
            }
            // Recheck the effective setting, never its pending restart value or a UI-supplied path.
            var settings = await StorageAsync(token => _downloads!.StorageSettings.ReadAsync(token), cancellationToken).ConfigureAwait(false);
            RequireCurrent(binding);
            if (DownloadDestination(settings.Active.DownloadDirectory, preview.Name) != draft.Plan.LocalPath)
                throw DownloadSafe(ErrorCode.SourceChanged);
            var created = await StorageAsync(token => _tasks.CreateTransferAsync(draft.Plan, token), cancellationToken).ConfigureAwait(false);
            RequireCurrent(binding);
            _ = ObserveDownload(binding, created, publish: false);
            DownloadWork work;
            lock (_gate)
            {
                RequireCurrent(binding);
                RequireDownloadPermission(binding);
                RequireDownloadQueueCapacityLocked(binding);
                work = new(binding, created.Plan.Reference, cancellationToken);
                binding.DownloadItems[work.Task].Scheduled = true;
                _downloadExecutions.Add(work.Task, work);
                work.Node = _downloadQueue.AddLast(work);
                RegisterDownloadStopLocked(work);
            }
            DispatchDownloads();
            lock (_gate)
            {
                RequireCurrent(binding);
                var item = ProjectDownload(binding, binding.DownloadItems[work.Task]);
                QueueDownloadUpdateLocked(item);
                return item;
            }
        }
        catch (Exception error) { throw NormalizeDownload(error); }
        finally { _commands.Release(); }
    }

    async Task<DownloadPage> IDownloadService.ListAsync(DownloadContext context, string? cursor, int limit, CancellationToken cancellationToken)
    {
        try
        {
            var binding = RequireDownloadContext(context);
            var page = await StorageAsync(token => _tasks.ListTransfersAsync(context.Account, cursor, limit, token), cancellationToken).ConfigureAwait(false);
            RequireCurrent(binding);
            var items = new List<DownloadItem>();
            foreach (var record in page.Items)
            {
                if (record.Plan.Reference.Account != context.Account) throw DownloadSafe(ErrorCode.ProtocolMismatch);
                if (record.Plan.Direction == TransferDirection.Download) items.Add(ObserveDownload(binding, record, publish: false));
            }
            RequireCurrent(binding);
            return new(items, page.NextCursor);
        }
        catch (Exception error) { throw NormalizeDownload(error); }
    }

    /// <summary>
    /// Explicit durable resume. A live in-process task is refused in favour of pause control;
    /// an eligible interrupted task re-enters the same bounded FIFO writer queue.
    /// </summary>
    async Task<DownloadItem> IDownloadControlService.ResumeAsync(DownloadContext context, TransferTaskRef task, CancellationToken cancellationToken)
    {
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        Binding? binding = null;
        try
        {
            binding = RequireDownloadContext(context, task);
            RequireDownloadPermission(binding);
            if (_downloads!.Runner is not IFileDownloadResumeControl) throw DownloadSafe(ErrorCode.CapabilityMissing);
            var record = await ReadAsync(task, cancellationToken).ConfigureAwait(false);
            RequireCurrent(binding);
            if (record.Plan.Reference != task || record.Plan.Direction != TransferDirection.Download) throw DownloadSafe(ErrorCode.InvalidRequest);
            lock (_gate)
            {
                RequireCurrent(binding);
                if (_downloadExecutions.ContainsKey(task)) throw DownloadSafe(ErrorCode.Busy);
            }
            _ = ObserveDownload(binding, record, publish: false);
            DownloadWork work;
            lock (_gate)
            {
                RequireCurrent(binding);
                RequireDownloadPermission(binding);
                RequireDownloadQueueCapacityLocked(binding);
                work = new(binding, task, cancellationToken) { Resume = true };
                binding.DownloadItems[work.Task].Scheduled = true;
                _downloadExecutions.Add(work.Task, work);
                work.Node = _downloadQueue.AddLast(work);
                RegisterDownloadStopLocked(work);
            }
            DispatchDownloads();
            lock (_gate)
            {
                RequireCurrent(binding);
                var item = ProjectDownload(binding, binding.DownloadItems[work.Task]);
                QueueDownloadUpdateLocked(item);
                return item;
            }
        }
        catch (Exception error)
        {
            var failure = NormalizeDownload(error);
            // Admission failed: retire a never-started registration instead of leaving a
            // scheduled item that no queue node will ever dispatch.
            lock (_gate)
            {
                if (binding is not null && binding.DownloadItems.TryGetValue(task, out var item))
                {
                    item.Scheduled = false;
                    if (_downloadExecutions.TryGetValue(task, out var stale) && stale.Resume && !stale.Started)
                    {
                        if (stale.Node is { } node) { _downloadQueue.Remove(node); stale.Node = null; }
                        _downloadExecutions.Remove(task);
                        stale.StopRegistration.Dispose();
                        stale.Stop.Dispose();
                        stale.Completion.TrySetResult(new DownloadCompletion(null, failure));
                    }
                    if (ReferenceEquals(_current, binding)) QueueDownloadUpdateLocked(ProjectDownload(binding, item));
                }
            }
            throw failure;
        }
        finally { _commands.Release(); }
    }

    /// <summary>
    /// Batch durable resume for the auto-resume preference. Eligibility is the same durable
    /// checkpoint evidence as the single-task entry; queue capacity bounds the batch.
    /// </summary>
    async Task<IReadOnlyList<DownloadItem>> IDownloadService.ResumeEligibleAsync(DownloadContext context, CancellationToken cancellationToken)
    {
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var binding = RequireDownloadContext(context);
            RequireDownloadPermission(binding);
            if (_downloads!.Runner is not IFileDownloadResumeControl) throw DownloadSafe(ErrorCode.CapabilityMissing);
            var enqueued = new List<TransferTaskRef>();
            bool capacityReached = false;
            string? cursor = null;
            do
            {
                var page = await StorageAsync(token => _tasks.ListTransfersAsync(context.Account, cursor, 200, token),
                    cancellationToken).ConfigureAwait(false);
                RequireCurrent(binding);
                foreach (var record in page.Items)
                {
                    if (record.Plan.Reference.Account != context.Account) throw DownloadSafe(ErrorCode.ProtocolMismatch);
                    if (record.Plan.Direction != TransferDirection.Download) continue;
                    var observed = ObserveDownload(binding, record, publish: false);
                    if (!observed.Snapshot.CanResumeLocally) continue;
                    lock (_gate)
                    {
                        RequireCurrent(binding);
                        if (_downloadExecutions.ContainsKey(record.Plan.Reference)) continue;
                        try { RequireDownloadQueueCapacityLocked(binding); }
                        catch (FilewayException) { capacityReached = true; }
                        if (capacityReached) break;
                        DownloadWork work = new(binding, record.Plan.Reference, cancellationToken) { Resume = true };
                        binding.DownloadItems[work.Task].Scheduled = true;
                        _downloadExecutions.Add(work.Task, work);
                        work.Node = _downloadQueue.AddLast(work);
                        RegisterDownloadStopLocked(work);
                        enqueued.Add(work.Task);
                    }
                    if (capacityReached) break;
                }
                cursor = capacityReached ? null : page.NextCursor;
            } while (cursor is not null);
            if (enqueued.Count != 0) DispatchDownloads();
            lock (_gate)
            {
                RequireCurrent(binding);
                return enqueued.Where(task => binding.DownloadItems.TryGetValue(task, out _))
                    .Select(task => ProjectDownload(binding, binding.DownloadItems[task])).ToArray();
            }
        }
        catch (Exception error) { throw NormalizeDownload(error); }
        finally { _commands.Release(); }
    }

    Task<DownloadItem> IDownloadService.CancelAsync(DownloadContext context, TransferTaskRef task, CancellationToken cancellationToken) =>
        ControlDownloadAsync(context, task, reconcile: false, cancellationToken);

    Task<DownloadItem> IDownloadService.ReconcileLocalAsync(DownloadContext context, TransferTaskRef task, CancellationToken cancellationToken) =>
        ControlDownloadAsync(context, task, reconcile: true, cancellationToken);

    private async Task<DownloadItem> ControlDownloadAsync(DownloadContext context, TransferTaskRef task, bool reconcile,
        CancellationToken cancellationToken)
    {
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        Binding? binding = null;
        Task readers = Task.CompletedTask;
        bool controlOperation = false;
        try
        {
            binding = RequireDownloadContext(context, task);
            var record = await ReadAsync(task, cancellationToken).ConfigureAwait(false);
            RequireCurrent(binding);
            if (record.Plan.Reference != task || record.Plan.Direction != TransferDirection.Download) throw DownloadSafe(ErrorCode.InvalidRequest);
            _ = ObserveDownload(binding, record, publish: false);
            Task<TransferTaskRecord>? pending = null;
            DownloadWork? queued = null;
            lock (_gate)
            {
                RequireCurrent(binding);
                if (reconcile && !DownloadTerminal(record.Checkpoint.Phase) && _downloadExecutions.ContainsKey(task))
                    throw DownloadSafe(ErrorCode.Busy);
                var item = binding.DownloadItems[task];
                item.Control = reconcile ? DownloadStatus.Reconciling : DownloadStatus.Canceling;
                QueueDownloadUpdateLocked(ProjectDownload(binding, item));
                readers = reconcile ? Task.CompletedTask : RevokePlaybackReadersLocked(binding, task);
                if (!reconcile && _downloadExecutions.TryGetValue(task, out var work) && !work.Started)
                {
                    // Detach a waiting item before invoking local cancellation. It must neither
                    // enter the writer nor wait for an unrelated, potentially large download.
                    queued = work;
                    CancelQueuedDownloadLocked(work);
                }
                else
                {
                    _downloadControlOperations++;
                    controlOperation = true;
                    pending = reconcile ? _downloads!.Runner.ReconcileAsync(task, cancellationToken)
                        : _downloads!.Runner.CancelAsync(task, cancellationToken);
                }
            }
            // A retained native reader is allowed to outlive this command. Only its canceled
            // actual local/network operations and remote capability releases must settle here.
            TransferTaskRecord result;
            try
            {
                if (queued is not null)
                {
                    var completed = await queued.Completion.Task.ConfigureAwait(false);
                    if (completed.Failure is { } failure) throw failure;
                    result = completed.Record ?? throw DownloadSafe(ErrorCode.OutcomeUnknown);
                }
                else result = await pending!.ConfigureAwait(false);
            }
            finally { await readers.ConfigureAwait(false); }
            RequireCurrent(binding);
            lock (_gate) binding.DownloadItems[task].Control = null;
            return ObserveDownload(binding, result);
        }
        catch (Exception error)
        {
            if (binding is not null)
            {
                try
                {
                    // A failed acknowledgement may follow a durable Cancel or publication fence.
                    // Read its original evidence; never retry the control operation.
                    var observed = await ReadAsync(task, CancellationToken.None).ConfigureAwait(false);
                    if (observed.Plan.Reference == task && observed.Plan.Direction == TransferDirection.Download)
                        _ = ObserveDownload(binding, observed, publish: false);
                }
                catch (Exception) { /* Retain the last accepted snapshot and the original safe failure. */ }
                RecordDownloadFailure(binding, task, error);
            }
            throw NormalizeDownload(error);
        }
        finally
        {
            // A synchronously throwing runner cannot abandon already-revoked network operations.
            try { await readers.ConfigureAwait(false); }
            finally
            {
                lock (_gate)
                {
                    if (controlOperation) _downloadControlOperations--;
                    QueueDownloadDispatchLocked();
                }
                _commands.Release();
            }
        }
    }

    private static FilewayException NormalizeDownload(Exception error) => DownloadSafe(error switch
    {
        FilewayException known => known.Error.Code,
        OperationCanceledException => ErrorCode.Canceled,
        IOException or UnauthorizedAccessException => ErrorCode.LocalStorageUnavailable,
        ObjectDisposedException => ErrorCode.SourceChanged,
        _ => ErrorCode.Unknown,
    }, error is FilewayException knownError ? knownError.Error.HttpStatus : null,
        error is FilewayException retryable && retryable.Error.Retryable);

    private static FilewayException DownloadSafe(ErrorCode code, int? httpStatus = null, bool retryable = false) =>
        new(new ErrorInfo(code, code switch
        {
            ErrorCode.InvalidRequest => "下载请求无效，请重新选择文件并确认保存位置。",
            ErrorCode.SourceChanged => "下载来源或账户会话已变化，请重新连接后核实原任务。",
            ErrorCode.PermissionDenied => "当前账户没有下载权限。",
            ErrorCode.Conflict => "下载任务或同名本地目标存在冲突，不会覆盖已有文件。",
            ErrorCode.LocalStorageUnavailable => "本地下载记录暂不可用，未确认的任务不会重新执行。",
            ErrorCode.Canceled => "下载操作已停止，请查看原任务的本地结果。",
            ErrorCode.NetworkUnavailable or ErrorCode.Timeout => "下载连接已中断；此切片不续传，请核实并清理原任务后再明确下载。",
            ErrorCode.AuthenticationExpired or ErrorCode.CredentialsUnavailable => "登录状态不可用，请重新连接后查看原下载任务。",
            ErrorCode.OutcomeUnknown => "本地下载发布结果尚未确认，请核实原任务，不要重复下载。",
            ErrorCode.Busy => "下载队列已满，或任务正在核实或等待释放，请稍后重试。",
            ErrorCode.HostExited => "连接服务已退出，请重新连接。",
            ErrorCode.CapabilityMissing or ErrorCode.ProtocolMismatch => "此下载能力或服务端响应暂不受支持。",
            _ => "下载操作未能完成，请查看原任务的本地状态。",
        }, httpStatus, retryable));
}

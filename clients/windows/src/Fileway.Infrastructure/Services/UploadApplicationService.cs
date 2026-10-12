using System.Text;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;

namespace Fileway.Infrastructure.Services;

public sealed record UploadApplicationServiceOptions
{
    public TimeSpan PersistenceTimeout { get; init; } = TimeSpan.FromSeconds(30);
}

/// <summary>Application authority and explicit single-file submission. Owns the coordinator, not the decorated services.</summary>
public sealed partial class UploadApplicationService : IConnectionService, IConnectionCatalog, IUploadService, IAsyncDisposable
{
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);
    private readonly IConnectionService _connection;
    private readonly IConnectionCatalog _catalog;
    private readonly IFileRepository _files;
    private readonly ITransferTaskStore _tasks;
    private readonly IUploadCoordinator _coordinator;
    private readonly UploadApplicationServiceOptions _options;
    private readonly SemaphoreSlim _commands = new(1, 1);
    private readonly object _gate = new();
    private readonly List<Task> _drains = [];
    private readonly Dictionary<TransferTaskRef, UploadItem> _updates = [];
    private Binding? _current;
    private Opening? _opening;
    private bool _publishing;
    private bool _disposed;
    private TaskCompletionSource? _disposeCompletion;

    private sealed class Opening
    {
        internal HashSet<(AccountKey Account, Guid Session)> Invalidated { get; } = [];
        internal Dictionary<(AccountKey Account, Guid Session), ConnectionSession> Updates { get; } = [];
    }

    private sealed class Binding(ConnectionSession session, UploadActivationToken activation)
    {
        internal ConnectionSession Session { get; set; } = session;
        internal UploadActivationToken Activation { get; } = activation;
        internal UploadContext Context { get; } = new(activation.Account, activation.SessionId, activation.Id);
        internal Dictionary<TransferTaskRef, Presentation> Items { get; } = [];
    }

    private sealed class Presentation(RemoteResourceRef parent, string name, string destination)
    {
        internal RemoteResourceRef Parent { get; } = parent;
        internal string Name { get; } = name;
        internal string Destination { get; } = destination;
        internal UploadTaskSnapshot? Snapshot { get; set; }
        internal UploadTaskProgress? Progress { get; set; }
        internal bool Scheduled { get; set; }
        internal UploadStatus? Control { get; set; }
    }

    public UploadApplicationService(IConnectionService connection, IConnectionCatalog catalog, IFileRepository files,
        ITransferTaskStore tasks, IUploadCoordinator coordinator, UploadApplicationServiceOptions? options = null)
    {
        _connection = connection ?? throw new ArgumentNullException(nameof(connection));
        _catalog = catalog ?? throw new ArgumentNullException(nameof(catalog));
        _files = files ?? throw new ArgumentNullException(nameof(files));
        _tasks = tasks ?? throw new ArgumentNullException(nameof(tasks));
        _coordinator = coordinator ?? throw new ArgumentNullException(nameof(coordinator));
        _options = options ?? new();
        if (_options.PersistenceTimeout <= TimeSpan.Zero || _options.PersistenceTimeout > TimeSpan.FromMinutes(5))
            throw Safe(ErrorCode.InvalidRequest);
        _connection.SessionUpdated += ConnectionSessionUpdated;
        _connection.ConnectionIssue += ConnectionIssueReceived;
        _coordinator.TaskChanged += QueueTaskChanged;
    }

    public event EventHandler<ConnectionSession>? SessionUpdated;
    public event EventHandler<ConnectionIssue>? ConnectionIssue;
    public event EventHandler<UploadItem>? TaskChanged;
    public UploadContext? CurrentContext { get { lock (_gate) return _disposed ? null : _current?.Context; } }

    private async Task EnterAsync(CancellationToken cancellationToken)
    {
        await _commands.WaitAsync(cancellationToken).ConfigureAwait(false);
        try { lock (_gate) ObjectDisposedException.ThrowIf(_disposed, this); }
        catch { _commands.Release(); throw; }
    }

    private Binding Require(UploadContext context, TransferTaskRef? task = null)
    {
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_current is not { } current || current.Context != context) throw Safe(ErrorCode.SourceChanged);
            if (task is { } reference && (reference.Account != context.Account || reference.Id == Guid.Empty))
                throw Safe(ErrorCode.InvalidRequest);
            return current;
        }
    }

    private void RequireCurrent(Binding binding)
    {
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (!ReferenceEquals(_current, binding)) throw Safe(ErrorCode.SourceChanged);
        }
    }

    private async Task<T> StorageAsync<T>(Func<CancellationToken, Task<T>> operation, CancellationToken cancellationToken)
    {
        using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        budget.CancelAfter(_options.PersistenceTimeout);
        try { return await operation(budget.Token).WaitAsync(budget.Token).ConfigureAwait(false); }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { throw Safe(ErrorCode.LocalStorageUnavailable); }
        catch (Exception error) { throw Normalize(error); }
    }

    private async Task<TransferTaskRecord> ReadAsync(TransferTaskRef task, CancellationToken cancellationToken) =>
        await StorageAsync(token => _tasks.GetTransferAsync(task, token), cancellationToken).ConfigureAwait(false)
            ?? throw Safe(ErrorCode.InvalidRequest);

    private static FilewayException Normalize(Exception error) => error switch
    {
        FilewayException known => Safe(known.Error.Code, known.Error.HttpStatus, known.Error.Retryable),
        OperationCanceledException => Safe(ErrorCode.Canceled),
        IOException or UnauthorizedAccessException => Safe(ErrorCode.LocalStorageUnavailable),
        ObjectDisposedException => Safe(ErrorCode.SourceChanged),
        _ => Safe(ErrorCode.Unknown),
    };

    private static FilewayException Safe(ErrorCode code, int? httpStatus = null, bool retryable = false) =>
        new(new ErrorInfo(code, code switch
        {
            ErrorCode.InvalidRequest => "上传请求无效，请重新选择文件与目标目录。",
            ErrorCode.SourceChanged => "上传账户或会话已更换，请重新连接后查看任务。",
            ErrorCode.PermissionDenied => "当前账户没有上传权限。",
            ErrorCode.Conflict => "上传计划或同名目标存在冲突，请核实原任务。",
            ErrorCode.LocalStorageUnavailable => "本地上传记录暂不可用，未确认的任务不会重新提交。",
            ErrorCode.Canceled => "操作已取消。",
            ErrorCode.NetworkUnavailable => "网络暂不可用，请检查连接后显式恢复原任务。",
            ErrorCode.Timeout => "上传操作超时，请核实原任务后再恢复。",
            ErrorCode.AuthenticationExpired or ErrorCode.CredentialsUnavailable => "登录状态不可用，请重新连接。",
            ErrorCode.OutcomeUnknown => "上传结果尚未确认，请核实原任务，不要重复上传。",
            ErrorCode.Busy => "任务仍在执行或等待释放，请稍后查看。",
            ErrorCode.HostExited => "连接服务已退出，请重新连接。",
            ErrorCode.CapabilityMissing or ErrorCode.ProtocolMismatch => "此上传能力或服务端响应暂不受支持。",
            _ => "上传操作未能完成，请查看原任务状态。",
        }, httpStatus, retryable));

    private static void PublishEvent<T>(object sender, EventHandler<T>? handlers, T value)
    {
        if (handlers is null) return;
        foreach (EventHandler<T> handler in handlers.GetInvocationList())
        {
            try { handler(sender, value); }
            catch (Exception) { /* A presentation subscriber never owns application state. */ }
        }
    }
}

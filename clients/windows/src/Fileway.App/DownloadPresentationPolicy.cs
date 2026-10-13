using System.Buffers;
using System.Text;
using Fileway.Core.Contracts;

namespace Fileway.App;

internal enum DownloadPresentationTone { Information, Success, Warning }
internal sealed record DownloadProgressPresentation(bool IsIndeterminate, double Value, string ActivityText, string FlushedText);

/// <summary>Presentation only: received activity is never completion or resumable checkpoint evidence.</summary>
internal static class DownloadPresentationPolicy
{
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);
    private static readonly SearchValues<char> InvalidLocalNameCharacters = SearchValues.Create("<>:\"|?*/\\");

    /// <summary>Early leaf-name feedback only; backend destination and native ownership checks remain authoritative.</summary>
    internal static bool IsSafeLocalName(string? name)
    {
        if (string.IsNullOrWhiteSpace(name) || name.Length > 255 || name is "." or ".." ||
            name.EndsWith('.') || name.EndsWith(' ') || name.Any(char.IsControl) ||
            name.AsSpan().ContainsAny(InvalidLocalNameCharacters)) return false;
        try { _ = StrictUtf8.GetByteCount(name); }
        catch (EncoderFallbackException) { return false; }
        string stem = name.Split('.')[0];
        if (stem.Equals("CON", StringComparison.OrdinalIgnoreCase) || stem.Equals("PRN", StringComparison.OrdinalIgnoreCase) ||
            stem.Equals("AUX", StringComparison.OrdinalIgnoreCase) || stem.Equals("NUL", StringComparison.OrdinalIgnoreCase) ||
            stem.Equals("CONIN$", StringComparison.OrdinalIgnoreCase) || stem.Equals("CONOUT$", StringComparison.OrdinalIgnoreCase)) return false;
        return !(stem.Length == 4 && (stem.StartsWith("COM", StringComparison.OrdinalIgnoreCase) || stem.StartsWith("LPT", StringComparison.OrdinalIgnoreCase)) &&
            stem[3] is >= '1' and <= '9' or '¹' or '²' or '³');
    }

    internal static bool CanPrepare(PermissionSet? permissions, bool currentContext, bool browserReady, bool loading,
        bool busy, int selectedCount, bool selectedIsDirectory) => permissions?.Download == true && currentContext
        && browserReady && !loading && !busy && selectedCount == 1 && !selectedIsDirectory;

    internal static DownloadStatus EffectiveStatus(DownloadItem item) => item.Status switch
    {
        DownloadStatus.Completed when !item.Snapshot.LocalPublished || item.Snapshot.Uncertainty != TransferUncertainty.None => DownloadStatus.OutcomeUnknown,
        DownloadStatus.Canceled when item.Snapshot.Uncertainty != TransferUncertainty.None => DownloadStatus.OutcomeUnknown,
        _ => item.Status,
    };

    internal static bool IsActive(DownloadStatus status) => status is DownloadStatus.Queued or DownloadStatus.Preparing
        or DownloadStatus.Downloading or DownloadStatus.Canceling or DownloadStatus.Verifying or DownloadStatus.Publishing or DownloadStatus.Reconciling;
    internal static bool IsTerminal(DownloadStatus status) => status is DownloadStatus.Completed or DownloadStatus.Canceled;
    internal static bool CanCancel(DownloadItem item) => !IsTerminal(EffectiveStatus(item))
        && EffectiveStatus(item) is not (DownloadStatus.Canceling or DownloadStatus.OutcomeUnknown or DownloadStatus.Reconciling);
    internal static bool CanReconcile(DownloadItem item) => item.Snapshot.CanReconcileLocally
        && !IsActive(EffectiveStatus(item)) && !IsTerminal(EffectiveStatus(item));
    /// <summary>Presentation hint only; the application rechecks all read authority before opening.</summary>
    internal static bool CanPlay(DownloadItem item) =>
        item.Context.Account == item.Snapshot.Task.Account && item.Source.Account == item.Context.Account &&
        item.Context.SessionId != Guid.Empty && item.Context.ActivationId != Guid.Empty && item.Snapshot.Generation > 0 &&
        item.Snapshot.Intent == TransferIntent.Run && item.Snapshot.Uncertainty == TransferUncertainty.None &&
        item.Snapshot.Error is null && item.Snapshot.TotalBytes is > 0 &&
        ((item.Status, item.Snapshot.Phase) is (DownloadStatus.Downloading, TransferPhase.Running) or
            (DownloadStatus.Verifying, TransferPhase.Verifying)) &&
        (Path.GetExtension(item.Source.DisplayName).ToLowerInvariant() is ".mp4" or ".mkv" or ".mov" or ".webm" or
            ".avi" or ".m4v" or ".ts" or ".m2ts" or ".wmv");
    internal static string PrimaryLabel(DownloadItem item) => !CanReconcile(item) ? ""
        : item.Snapshot.Intent == TransferIntent.Cancel ? "核实取消结果" : "核实本地结果";

    internal static string Stage(DownloadItem item) => EffectiveStatus(item) switch
    {
        DownloadStatus.NotStarted => "尚未开始", DownloadStatus.Queued => "等待下载", DownloadStatus.Preparing => "正在准备",
        DownloadStatus.Downloading => "正在下载", DownloadStatus.Canceling => "正在取消并清理", DownloadStatus.Stopped => "下载已停止",
        DownloadStatus.WaitingNetwork => "下载已停止，网络不可用", DownloadStatus.WaitingAuthentication => "下载已停止，需要重新登录",
        DownloadStatus.Conflict => "本地存在同名文件", DownloadStatus.SourceChanged => "远端文件已变化", DownloadStatus.Failed => "下载失败",
        DownloadStatus.OutcomeUnknown => item.Snapshot.Intent == TransferIntent.Cancel ? "取消结果待本地核实" : "本地保存结果待核实",
        DownloadStatus.Verifying => "正在校验", DownloadStatus.Publishing => "正在保存文件", DownloadStatus.Reconciling => "正在核实本地结果",
        DownloadStatus.Completed => "下载已完成", DownloadStatus.Canceled => "下载已取消", _ => "下载状态待核实",
    };

    internal static string Detail(DownloadItem item) => item.Snapshot.Error?.Message ?? EffectiveStatus(item) switch
    {
        DownloadStatus.Completed => "文件已校验并保存到确认的位置。",
        DownloadStatus.Canceled => "下载已停止，已确认清理本任务拥有的未发布临时文件。",
        DownloadStatus.OutcomeUnknown => "本地保存或清理结果尚未确认。请核实本地结果，不要重复下载到这个位置。",
        DownloadStatus.Conflict => "已有文件会保留，不会覆盖。请处理同名文件后重新选择下载。",
        DownloadStatus.SourceChanged => "远端文件的版本或大小已变化。请重新查看文件后选择下载。",
        DownloadStatus.Canceling => "正在等待写入停止并清理本任务的临时文件，尚不能认为取消已完成。",
        DownloadStatus.Reconciling => "只核实本地文件证据，不会重新开始网络下载。",
        DownloadStatus.Stopped or DownloadStatus.WaitingNetwork or DownloadStatus.WaitingAuthentication or DownloadStatus.Failed
            => "这个下载已停止。可先核实本地结果；当前不提供断点续传。",
        _ => "接收进度与磁盘写入确认分别显示。关闭此进度对话框不会取消下载。",
    };

    internal static DownloadPresentationTone Tone(DownloadItem item) => EffectiveStatus(item) switch
    {
        DownloadStatus.Completed => DownloadPresentationTone.Success,
        DownloadStatus.Stopped or DownloadStatus.WaitingNetwork or DownloadStatus.WaitingAuthentication or DownloadStatus.Conflict
            or DownloadStatus.SourceChanged or DownloadStatus.Failed or DownloadStatus.OutcomeUnknown => DownloadPresentationTone.Warning,
        _ => DownloadPresentationTone.Information,
    };

    internal static DownloadProgressPresentation Progress(DownloadItem item)
    {
        var snapshot = item.Snapshot;
        var body = item.Progress is { } progress && progress.Run.Task == snapshot.Task
            && progress.Run.Generation == snapshot.Generation && progress.TotalBytes >= 0
            && snapshot.TotalBytes == progress.TotalBytes && progress.ReceivedBytes >= 0 && progress.ReceivedBytes <= progress.TotalBytes ? progress : null;
        var known = body is not null || snapshot.ConfirmedOffset.HasValue;
        var received = Math.Max(snapshot.ConfirmedOffset ?? 0, body?.ReceivedBytes ?? 0);
        var total = snapshot.TotalBytes;
        var status = EffectiveStatus(item);
        var value = known && total is > 0 ? Math.Clamp(received * 100d / total.Value, 0, 100)
            : status == DownloadStatus.Completed ? 100 : 0;
        var suffix = status switch
        {
            DownloadStatus.Completed => "",
            DownloadStatus.Verifying => "（正在校验，尚未保存完成）",
            DownloadStatus.Publishing => "（正在保存，尚未完成）",
            _ when IsActive(status) => "（尚未完成校验与保存）",
            _ => "（接收字节不代表文件已保存）",
        };
        var activity = known ? $"已接收 {FormatBytes(received)}{(total is { } size ? " / " + FormatBytes(size) : "")}{suffix}"
            : "正在等待接收进度";
        var flushed = snapshot.ConfirmedOffset is { } offset ? $"磁盘已确认写入 {FormatBytes(offset)}{(total is { } length ? " / " + FormatBytes(length) : "")}" : "磁盘写入确认尚待核实";
        return new(!known && IsActive(status), value, activity, flushed);
    }

    internal static DownloadItem Prefer(DownloadItem current, DownloadItem incoming)
    {
        var previous = current.Snapshot; var next = incoming.Snapshot;
        return next.Generation < previous.Generation || next.Generation == previous.Generation
            && (next.Revision < previous.Revision || IsTerminal(EffectiveStatus(current)) && !IsTerminal(EffectiveStatus(incoming)))
            ? current : incoming;
    }

    internal static string FormatBytes(long bytes)
    {
        var value = (double)Math.Max(0, bytes); var units = new[] { "B", "KB", "MB", "GB", "TB" }; var index = 0;
        while (value >= 1024 && index < units.Length - 1) { value /= 1024; ++index; }
        return $"{value:0.##} {units[index]}";
    }
}

/// <summary>The exact local-name edit and revision associated with one exclusive preparation.</summary>
internal sealed record DownloadSaveAsPreparation(string Name, long Revision);

/// <summary>
/// Read-only confirmation state for one original remote source. Editing requires a new backend
/// preview; this state never builds a local path or starts a download.
/// </summary>
internal sealed class DownloadSaveAsState
{
    private readonly DownloadContext _context;
    private readonly RemoteResourceRef _originalSource;
    private DownloadSaveAsPreparation? _pending;
    private long _revision;

    internal DownloadSaveAsState(DownloadContext context, RemoteResourceRef originalSource, string initialName)
    {
        ArgumentNullException.ThrowIfNull(originalSource);
        ArgumentNullException.ThrowIfNull(initialName);
        _context = context;
        _originalSource = originalSource;
        Name = initialName;
    }

    internal string Name { get; private set; }
    internal bool IsPreparing => _pending is not null;
    internal DownloadPreview? Preview { get; private set; }
    internal bool IsSubmitted { get; private set; }
    internal bool IsClosed { get; private set; }

    internal bool EditName(string name)
    {
        ArgumentNullException.ThrowIfNull(name);
        if (IsClosed || IsSubmitted) return false;
        Name = name;
        ++_revision;
        Preview = null;
        // Keep a pending preparation exclusive until its own finally/FinishPrepare runs.
        return true;
    }

    internal bool TryBeginPrepare(out DownloadSaveAsPreparation preparation)
    {
        preparation = null!;
        if (IsClosed || IsSubmitted || IsPreparing || _originalSource.Account != _context.Account ||
            !DownloadPresentationPolicy.IsSafeLocalName(Name)) return false;
        Preview = null;
        preparation = new(Name, ++_revision);
        _pending = preparation;
        return true;
    }

    internal bool AcceptPreview(DownloadSaveAsPreparation preparation, DownloadPreview preview)
    {
        ArgumentNullException.ThrowIfNull(preparation);
        ArgumentNullException.ThrowIfNull(preview);
        if (IsClosed || IsSubmitted || Preview is not null || !ReferenceEquals(_pending, preparation) || preparation.Revision != _revision ||
            !string.Equals(preparation.Name, Name, StringComparison.Ordinal) || preview.Context != _context ||
            preview.Source is null || preview.Source.Account != _context.Account ||
            preview.Source.Account != _originalSource.Account ||
            !string.Equals(preview.Source.WirePath, _originalSource.WirePath, StringComparison.Ordinal) ||
            !string.Equals(preview.Source.SourceVersion, _originalSource.SourceVersion, StringComparison.Ordinal) ||
            !string.Equals(preview.Name, preparation.Name, StringComparison.Ordinal) ||
            preview.Draft.Id == Guid.Empty || preview.TotalBytes < 0 || string.IsNullOrWhiteSpace(preview.DestinationDisplayPath))
            return false;
        Preview = preview;
        return true;
    }

    internal void FinishPrepare(DownloadSaveAsPreparation preparation)
    {
        ArgumentNullException.ThrowIfNull(preparation);
        if (ReferenceEquals(_pending, preparation)) _pending = null;
    }

    internal bool TryConfirm(out DownloadPreview preview)
    {
        preview = null!;
        if (IsClosed || IsSubmitted || IsPreparing || Preview is not { } accepted) return false;
        IsSubmitted = true;
        preview = accepted;
        return true;
    }

    internal void Close()
    {
        if (IsClosed) return;
        IsClosed = true;
        ++_revision;
        _pending = null;
        Preview = null;
    }
}

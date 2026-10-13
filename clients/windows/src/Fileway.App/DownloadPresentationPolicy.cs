using Fileway.Core.Contracts;

namespace Fileway.App;

internal enum DownloadPresentationTone { Information, Success, Warning }
internal sealed record DownloadProgressPresentation(bool IsIndeterminate, double Value, string ActivityText, string FlushedText);

/// <summary>Presentation only: received activity is never completion or resumable checkpoint evidence.</summary>
internal static class DownloadPresentationPolicy
{
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

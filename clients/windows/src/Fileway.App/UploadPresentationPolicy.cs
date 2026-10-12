using Fileway.Core.Contracts;

namespace Fileway.App;

internal enum UploadCommand { Pause, Resume, Reconcile, Cancel }
internal enum UploadPresentationTone { Information, Success, Warning }
internal sealed record UploadProgressPresentation(bool IsIndeterminate, double Value, string ConfirmedText);

/// <summary>Pure presentation decisions shared by the native upload view and maintained regression tests.</summary>
internal static class UploadPresentationPolicy
{
    internal static string ConfirmedBytesLabel(UploadItem item) => item.Snapshot.ConfirmedOffset is { } confirmed
        ? $"服务器已确认 {FormatUploadBytes(confirmed)}{(item.Snapshot.TotalBytes is { } total ? " / " + FormatUploadBytes(total) : "")}" : "确认字节尚待核实";
    internal static string FormatUploadBytes(long bytes)
    {
        var value = (double)Math.Max(0, bytes); var units = new[] { "B", "KB", "MB", "GB", "TB" }; var index = 0;
        while (value >= 1024 && index < units.Length - 1) { value /= 1024; ++index; }
        return $"{value:0.##} {units[index]}";
    }
    internal static string UploadItemStatusLabel(UploadItem item) => item.Status switch
    {
        UploadStatus.OutcomeUnknown when item.Snapshot.Intent == TransferIntent.Cancel => "取消结果待核实",
        UploadStatus.Canceled when !item.Snapshot.RemoteCanceled && item.Snapshot.ConfirmedOffset is not null => "上传已停止，远端未确认清理",
        _ => UploadStatusLabel(item.Status),
    };
    internal static string UploadStatusLabel(UploadStatus status) => status switch
    {
        UploadStatus.NotStarted => "尚未开始", UploadStatus.Queued => "等待上传", UploadStatus.Preparing => "正在准备",
        UploadStatus.Uploading => "正在上传", UploadStatus.Pausing => "正在暂停", UploadStatus.Paused => "已暂停",
        UploadStatus.WaitingNetwork => "等待网络", UploadStatus.WaitingAuthentication => "等待重新登录",
        UploadStatus.Canceling => "正在取消并核实", UploadStatus.Conflict => "目标存在同名项目", UploadStatus.SourceChanged => "本地文件已变化",
        UploadStatus.Failed => "上传失败", UploadStatus.OutcomeUnknown => "上传结果待核实", UploadStatus.Verifying => "正在校验",
        UploadStatus.Publishing => "正在发布", UploadStatus.Reconciling => "正在核实结果", UploadStatus.Completed => "上传已完成",
        UploadStatus.Canceled => "上传已取消", UploadStatus.Skipped => "已跳过", _ => "上传状态待核实",
    };
    internal static string UploadDetailLabel(UploadItem item) => item.Snapshot.Error?.Message ?? item.Status switch
    {
        UploadStatus.Completed => "服务器已确认文件发布完成。",
        UploadStatus.Canceled => item.Snapshot.RemoteCanceled ? "服务器已确认取消。"
            : item.Snapshot.ConfirmedOffset is null ? "上传任务已取消，未发布文件。" : "上传已停止。远端数据清理尚未确认。",
        UploadStatus.OutcomeUnknown => item.Snapshot.Intent == TransferIntent.Cancel
            ? "已请求取消。请核实服务器是否已取消或已发布文件。"
            : "远端结果尚未确认。请核实结果，不要重新提交同一个文件。",
        UploadStatus.Conflict => "不会覆盖已有文件。请处理同名项目后，再明确提交。",
        UploadStatus.SourceChanged => "原始文件发生变化，不能继续这个上传任务。",
        UploadStatus.WaitingAuthentication => "重新登录后，可从上传记录手动恢复。",
        UploadStatus.Canceling or UploadStatus.Reconciling => "正在核对服务器状态，尚不能认为已取消或已完成。",
        _ => "进度仅显示服务器已确认的字节。关闭此窗口不会暂停或取消上传。",
    };
    internal static bool IsActiveUpload(UploadStatus status) => status is UploadStatus.Queued or UploadStatus.Preparing or UploadStatus.Uploading
        or UploadStatus.Pausing or UploadStatus.Canceling or UploadStatus.Verifying or UploadStatus.Publishing or UploadStatus.Reconciling;
    internal static bool CanCancelUpload(UploadStatus status) => status is not (UploadStatus.Completed or UploadStatus.Canceled or UploadStatus.Skipped or UploadStatus.Canceling);
    internal static UploadCommand? PrimaryUploadCommand(UploadItem item) => item.Status switch
    {
        UploadStatus.Queued or UploadStatus.Preparing or UploadStatus.Uploading => UploadCommand.Pause,
        UploadStatus.NotStarted or UploadStatus.Paused or UploadStatus.WaitingNetwork or UploadStatus.WaitingAuthentication or UploadStatus.Failed => UploadCommand.Resume,
        UploadStatus.OutcomeUnknown => UploadCommand.Reconcile,
        _ => null,
    };

    internal static UploadProgressPresentation Progress(UploadItem item)
    {
        var known = item.Snapshot.ConfirmedOffset.HasValue && item.Snapshot.TotalBytes is > 0;
        var value = known ? Math.Clamp(100d * item.Snapshot.ConfirmedOffset!.Value / item.Snapshot.TotalBytes!.Value, 0, 100)
            : item.Status == UploadStatus.Completed && item.Snapshot.RemotePublished ? 100 : 0;
        return new(!known && IsActiveUpload(item.Status), value, ConfirmedBytesLabel(item));
    }

    internal static UploadPresentationTone Tone(UploadItem item) => item.Status switch
    {
        UploadStatus.Completed => UploadPresentationTone.Success,
        UploadStatus.Canceled when !item.Snapshot.RemoteCanceled && item.Snapshot.ConfirmedOffset is not null => UploadPresentationTone.Warning,
        UploadStatus.Conflict or UploadStatus.SourceChanged or UploadStatus.Failed or UploadStatus.OutcomeUnknown
            or UploadStatus.WaitingAuthentication or UploadStatus.WaitingNetwork => UploadPresentationTone.Warning,
        _ => UploadPresentationTone.Information,
    };

    internal static string PrimaryButtonLabel(UploadItem item) => PrimaryUploadCommand(item) switch
    {
        UploadCommand.Pause => "暂停",
        UploadCommand.Resume => "恢复上传",
        UploadCommand.Reconcile => item.Snapshot.Intent == TransferIntent.Cancel ? "核实取消结果" : "核实结果",
        _ => "",
    };

    internal static bool CanUsePrimary(UploadItem item, bool canCreate) => PrimaryUploadCommand(item) is { } command
        && (command == UploadCommand.Pause || item.Snapshot.Intent == TransferIntent.Cancel || canCreate);

    internal static UploadItem PreferUploadItem(UploadItem current, UploadItem incoming)
    {
        var previous = current.Snapshot;
        var next = incoming.Snapshot;
        return next.Generation < previous.Generation || next.Generation == previous.Generation
            && (next.Revision < previous.Revision || IsTerminal(current.Status) && !IsTerminal(incoming.Status))
            ? current : incoming;
    }

    private static bool IsTerminal(UploadStatus status) => status is UploadStatus.Completed or UploadStatus.Canceled or UploadStatus.Skipped;
}

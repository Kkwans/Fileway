using System.Globalization;
using Fileway.Core.Contracts;

namespace Fileway.App.MediaSurfaces;

internal sealed record PlaybackDownloadView(string StatusText, string BytesText, string HelpText,
    bool IsIndeterminate, double ProgressValue, bool ShowProgress, bool IsComplete,
    long? ReceivedBytes, long? ConfirmedBytes, long? ReadablePrefixBytes, long? TotalBytes);

/// <summary>
/// File-byte evidence only. The session owns task/run/revision ordering; this view never
/// derives playable time, buffer ranges, engine throughput, or download actions.
/// </summary>
internal static class PlaybackDownloadPresentation
{
    internal static PlaybackDownloadView? Create(PlaybackDownloadState? state)
    {
        if (state is null) return null;
        var item = state.Item;
        var snapshot = item.Snapshot;
        var total = snapshot.TotalBytes is >= 0 ? snapshot.TotalBytes : null;
        var confirmed = ValidBytes(snapshot.ConfirmedOffset, total);
        var readable = ValidBytes(state.ReadablePrefixBytes, total);
        // Progress is body activity, not durable write evidence. Do not borrow its
        // total or ConfirmedOffset when the task snapshot has not confirmed them.
        var progress = item.Progress;
        long? received = progress is not null && progress.Run.Task == snapshot.Task
            && progress.Run.Generation == snapshot.Generation && progress.TotalBytes == total
            && progress.Elapsed >= TimeSpan.Zero ? ValidBytes(progress.ReceivedBytes, total) : null;
        var complete = item.Status == DownloadStatus.Completed && snapshot.Phase == TransferPhase.Completed
            && snapshot.LocalPublished && snapshot.Uncertainty == TransferUncertainty.None;
        var status = item.Status == DownloadStatus.Completed && !complete
            || item.Status == DownloadStatus.Canceled && snapshot.Uncertainty != TransferUncertainty.None
            ? DownloadStatus.OutcomeUnknown : item.Status;
        var statusText = Stage(status, snapshot.Intent);
        // Decimal arithmetic avoids overflow and preserves sub-100 reception
        // even near long.MaxValue. Only the displayed text is truncated to tenths.
        decimal? percent = total is > 0 && received is not null
            ? received.Value * 100m / total.Value : null;
        var bytesText = $"接收 {FormatBytes(received)} / {FormatBytes(total, "总量未知")}"
            + (percent is { } knownPercent ? " (" + (decimal.Truncate(knownPercent * 10) / 10).ToString("0.#", CultureInfo.CurrentCulture) + "%)" : "")
            + $" · 落盘 {FormatBytes(confirmed)} · 当前可读前缀 {FormatBytes(readable, "不可用")}";
        var indeterminate = percent is null || !complete
            && status is DownloadStatus.Verifying or DownloadStatus.Publishing or DownloadStatus.Reconciling;
        // Reception may honestly reach 100% before verification/publication. The
        // status and IsComplete remain independent; no bytes become playable time.
        var value = (double)(percent ?? 0);
        var active = status is DownloadStatus.Queued or DownloadStatus.Preparing or DownloadStatus.Downloading
            or DownloadStatus.Canceling or DownloadStatus.Verifying or DownloadStatus.Publishing or DownloadStatus.Reconciling;
        var detail = status switch
        {
            DownloadStatus.Completed => "文件已校验并保存。",
            DownloadStatus.Verifying => "正在校验，尚未保存完成。",
            DownloadStatus.Publishing => "正在保存，尚未完成。",
            DownloadStatus.OutcomeUnknown => "本地保存或清理结果尚未确认。",
            DownloadStatus.Canceling => "正在停止写入并清理，尚未确认取消完成。",
            _ when received is not null && received == total => "接收字节已达总量，尚未确认校验与保存完成。",
            _ => "接收进度不代表文件已保存。",
        };
        var help = $"{statusText}。{bytesText}。{detail}"
            + "落盘是磁盘已持久确认的写入；当前可读前缀仅在读取授权有效时显示，停止或撤销后为不可用，不代表可播放时长。"
            + "暂停播放不会暂停下载；预加载速度由播放器单独报告。";
        if (snapshot.Error is { } error) help += " " + error.Message;
        return new(statusText, bytesText, help, indeterminate, value, active || !indeterminate,
            complete, received, confirmed, readable, total);
    }

    private static long? ValidBytes(long? bytes, long? total) =>
        bytes is >= 0 && (total is null || bytes <= total) ? bytes : null;

    private static string Stage(DownloadStatus status, TransferIntent intent) => status switch
    {
        DownloadStatus.NotStarted => "下载未开始",
        DownloadStatus.Queued => "等待下载",
        DownloadStatus.Preparing => "准备下载",
        DownloadStatus.Downloading => "下载中",
        DownloadStatus.Canceling => "取消中",
        DownloadStatus.Stopped => "下载已停止",
        DownloadStatus.WaitingNetwork => "网络不可用",
        DownloadStatus.WaitingAuthentication => "需重新登录",
        DownloadStatus.Conflict => "同名冲突",
        DownloadStatus.SourceChanged => "源文件已变化",
        DownloadStatus.Failed => "下载失败",
        DownloadStatus.OutcomeUnknown => intent == TransferIntent.Cancel ? "取消结果待核实" : "保存结果待核实",
        DownloadStatus.Verifying => "校验中",
        DownloadStatus.Publishing => "保存中",
        DownloadStatus.Reconciling => "核实中",
        DownloadStatus.Completed => "已下载并保存",
        DownloadStatus.Canceled => "下载已取消",
        _ => "下载状态待核实",
    };

    private static string FormatBytes(long? bytes, string unknown = "—")
    {
        if (bytes is null) return unknown;
        var value = (double)bytes.Value;
        string[] units = ["B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB"];
        var unit = 0;
        while (value >= 1024 && unit < units.Length - 1) { value /= 1024; ++unit; }
        return value.ToString("0.##", CultureInfo.CurrentCulture) + " " + units[unit];
    }
}

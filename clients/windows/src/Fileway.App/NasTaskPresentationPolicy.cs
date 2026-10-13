using System.Globalization;
using Fileway.Core.Contracts;

namespace Fileway.App;

/// <summary>Presentation only: task paths are display text, never remote resource identities.</summary>
internal static class NasTaskPresentationPolicy
{
    internal static bool IsKnownFileType(string? type) => type is "file.copy" or "file.move";

    internal static bool HasKnownStatus(ServerTask task) => task.Status switch
    {
        ServerTaskStatus.Queued => task.RawStatus == "queued",
        ServerTaskStatus.Running => task.RawStatus == "running",
        ServerTaskStatus.Completed => task.RawStatus == "completed",
        ServerTaskStatus.Failed => task.RawStatus == "failed",
        ServerTaskStatus.Canceled => task.RawStatus == "canceled",
        ServerTaskStatus.Interrupted => task.RawStatus == "interrupted",
        _ => false,
    };

    internal static bool CanAct(ServerTask task, PermissionSet permissions, ServerTaskActionKind action)
    {
        ArgumentNullException.ThrowIfNull(task);
        ArgumentNullException.ThrowIfNull(permissions);
        if (!IsKnownFileType(task.RawType) || !HasKnownStatus(task)) return false;
        bool terminal = task.Status is ServerTaskStatus.Completed or ServerTaskStatus.Failed or
            ServerTaskStatus.Canceled or ServerTaskStatus.Interrupted;
        return action switch
        {
            ServerTaskActionKind.CancellationRequested => task.ArchivedAt is null &&
                task.Status is ServerTaskStatus.Queued or ServerTaskStatus.Running,
            ServerTaskActionKind.RetryAccepted => task.ArchivedAt is null && permissions.Create &&
                (task.RawType != "file.move" || permissions.Rename) &&
                task.Status is ServerTaskStatus.Failed or ServerTaskStatus.Interrupted or ServerTaskStatus.Canceled,
            ServerTaskActionKind.Archived => task.ArchivedAt is null && terminal,
            ServerTaskActionKind.Unarchived => task.ArchivedAt is not null && terminal,
            _ => false,
        };
    }

    internal static string TypeText(ServerTask task) => task.RawType switch
    {
        "file.copy" => "复制",
        "file.move" => "移动",
        _ => "未知文件任务",
    };

    internal static string StatusText(ServerTask task) => !HasKnownStatus(task) ? "未知状态，请核实" : task.Status switch
    {
        ServerTaskStatus.Queued => "等待执行",
        ServerTaskStatus.Running => "正在执行",
        ServerTaskStatus.Completed => "已完成",
        ServerTaskStatus.Failed => "执行失败",
        ServerTaskStatus.Canceled => "已取消",
        ServerTaskStatus.Interrupted => "执行中断",
        _ => "未知状态，请核实",
    };

    // An accepted cancellation is not a terminal task state. Even a queued task may have
    // completed before the request was processed; preserve the server's observed status.
    internal static string ActionText(ServerTaskActionResult result)
    {
        string text = result.Outcome switch
        {
            OperationOutcome.OutcomeUnknown => "操作结果未知，请核实操作记录；不要重复提交。",
            OperationOutcome.Partial => SafeError(result.Error, "操作未确认，请刷新任务后核实。"),
            OperationOutcome.Confirmed => result.Kind switch
            {
                ServerTaskActionKind.CancellationRequested => "取消请求已接受，任务状态以服务器后续结果为准。",
                ServerTaskActionKind.RetryAccepted => "重试请求已接受，服务器已创建新任务。",
                ServerTaskActionKind.Archived => "任务记录已归档，文件未被删除。",
                ServerTaskActionKind.Unarchived => "任务记录已移回当前列表。",
                _ => "操作结果待核实。",
            },
            _ => "操作结果未知，请核实操作记录；不要重复提交。",
        };
        return result.Outcome == OperationOutcome.Confirmed && result.Receipt is { AckPersisted: false }
            ? text + " 本地确认记录未保存，请保留结果并核实记录。" : text;
    }

    internal static string? RetryTaskId(ServerTaskActionResult result) =>
        result.Outcome == OperationOutcome.Confirmed && result.Kind == ServerTaskActionKind.RetryAccepted
            ? result.Task?.Reference.Id : null;

    internal static string ProgressText(ServerTask task)
    {
        string items = task.TotalItems > 0
            ? $"{Number(task.ProcessedItems)} / {Number(task.TotalItems)} 项"
            : $"已处理 {Number(task.ProcessedItems)} 项";
        return task.TotalBytes > 0
            ? $"{items} · {Number(task.ProcessedBytes)} / {Number(task.TotalBytes)} 字节"
            : items;
    }

    internal static double? ProgressFraction(ServerTask task)
    {
        if (task.TotalBytes > 0) return Math.Clamp((double)task.ProcessedBytes / task.TotalBytes, 0, 1);
        if (task.TotalItems > 0) return Math.Clamp((double)task.ProcessedItems / task.TotalItems, 0, 1);
        return null;
    }

    internal static bool IsTaskAction(OperationKind kind) => kind is OperationKind.TaskCancel or OperationKind.TaskRetry or
        OperationKind.TaskArchive or OperationKind.TaskUnarchive;

    internal static bool IsTaskOperation(OperationKind kind) => kind is OperationKind.Copy or OperationKind.Move || IsTaskAction(kind);

    internal static bool NeedsReconciliation(OperationRecord record) => IsTaskOperation(record.Intent.Kind) &&
        record.Phase is OperationPhase.Intent or OperationPhase.DispatchMarked or OperationPhase.OutcomeUnknown &&
        !IsResolved(record.Reconciliation);

    internal static bool IsResolved(OperationReconciliation? result) => result is not null && result.Error is null &&
        result.Resolution is OperationResolution.NotSent or OperationResolution.Confirmed or
            OperationResolution.ObservedDesiredState or OperationResolution.ObservedTaskState;

    internal static string ReconciliationText(OperationReconciliation result) => result.Resolution switch
    {
        OperationResolution.Pending => "操作仍在处理，请等待后再核实。",
        OperationResolution.NotSent => "已确认原请求未发送；未自动重新提交。",
        OperationResolution.Confirmed when result.Error is null => "已核实原操作的确认记录。",
        OperationResolution.ObservedDesiredState when result.Error is null => "已观察到目标状态；未重新提交操作。",
        OperationResolution.ObservedTaskState when result.Error is null => "已读取原操作关联任务的当前状态。",
        OperationResolution.ObservedTaskState => "已读取已知任务状态，原操作结果仍未知；不要重复提交。",
        _ => "原操作结果仍需人工核实；未重新提交操作。",
    };

    internal static string SafeError(ErrorInfo? error, string fallback) => error?.HttpStatus switch
    {
        403 => "当前账号没有操作权限。",
        404 => "此任务或记录已不存在，请刷新后核实。",
        _ => error?.Code switch
        {
            ErrorCode.AuthenticationExpired or ErrorCode.CredentialsUnavailable => "登录已过期，请重新连接。",
            ErrorCode.PermissionDenied => "当前账号没有操作权限。",
            ErrorCode.NetworkUnavailable => "网络暂不可用，请重试读取。",
            ErrorCode.Timeout => "读取请求超时，请重试。",
            ErrorCode.SourceChanged => "连接已变更，请刷新后核实。",
            ErrorCode.HostExited => "连接服务已退出，请重新连接。",
            ErrorCode.ProtocolMismatch => "任务响应与当前请求不一致，请刷新后核实。",
            ErrorCode.OutcomeUnknown => "操作结果未知，请核实操作记录；不要重复提交。",
            ErrorCode.Busy => "操作仍在处理，请等待结果。",
            ErrorCode.LocalStorageUnavailable => "本地操作记录暂不可用，请恢复后核实。",
            _ => fallback,
        },
    };

    private static string Number(long value) => value.ToString("N0", CultureInfo.CurrentCulture);
}

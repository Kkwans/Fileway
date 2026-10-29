using System.ComponentModel;
using System.Globalization;
using Fileway.Core.Contracts;

namespace Fileway.App.Controls.NasTasks;

public sealed class NasTaskRow : INotifyPropertyChanged
{
    internal NasTaskRow(ServerTask task) => Task = task;
    internal ServerTask Task { get; private set; }
    public event PropertyChangedEventHandler? PropertyChanged;
    public string Name => string.IsNullOrWhiteSpace(Task.Title) ? NasTaskPresentationPolicy.TypeText(Task) + "任务" : Task.Title;
    public string Status => NasTaskPresentationPolicy.StatusText(Task);
    public string Kind => NasTaskPresentationPolicy.TypeText(Task);
    public string ProgressText => NasTaskPresentationPolicy.ProgressText(Task);
    public string When => FormatTime(Task.CreatedAt);
    public double Progress => (NasTaskPresentationPolicy.ProgressFraction(Task) ?? 0) * 100;
    public bool Indeterminate => NasTaskPresentationPolicy.ProgressFraction(Task) is null &&
        Task.Status is ServerTaskStatus.Queued or ServerTaskStatus.Running;
    public string IconName => Task.RawType switch { "file.move" => "scissors", "file.copy" => "copy", _ => "file" };
    public string AccessibleName => $"{Name}，{Kind}，{Status}，{ProgressText}，按 Enter 刷新详情";
    public override string ToString() => Name;

    internal void Update(ServerTask task)
    {
        if (ReferenceEquals(Task, task)) return;
        Task = task;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(string.Empty));
    }

    internal static string FormatTime(DateTimeOffset value)
    {
        try { return value.ToLocalTime().ToString("g", CultureInfo.CurrentCulture); }
        catch (ArgumentException) { return value.ToUniversalTime().ToString("u", CultureInfo.InvariantCulture); }
    }
}

public sealed class NasTaskOperationRow : INotifyPropertyChanged
{
    internal NasTaskOperationRow(OperationRecord record) => Record = record;
    internal OperationRecord Record { get; private set; }
    internal OperationReconciliation? Observation { get; private set; }
    public event PropertyChangedEventHandler? PropertyChanged;
    public string Name => Record.Intent.Kind switch
    {
        OperationKind.Copy => "提交复制任务",
        OperationKind.Move => "提交移动任务",
        OperationKind.TaskCancel => "请求取消任务",
        OperationKind.TaskRetry => "请求重试任务",
        OperationKind.TaskArchive => "归档任务记录",
        OperationKind.TaskUnarchive => "移出归档",
        _ => "任务操作记录",
    };
    public string Stage => Observation is { } observed ? NasTaskPresentationPolicy.ReconciliationText(observed) :
        Record.Reconciliation is { } saved ? NasTaskPresentationPolicy.ReconciliationText(saved) : Record.Phase switch
        {
            OperationPhase.Intent => "尚未确认发送，请核实",
            OperationPhase.DispatchMarked => "已标记发送，结果待核实",
            OperationPhase.Acknowledged when Record.Acknowledgement?.Kind is OperationAckKind.TaskAccepted or
                OperationAckKind.CancellationRequested or OperationAckKind.RetryAccepted => "服务器已接受请求",
            OperationPhase.Acknowledged => "操作已确认",
            OperationPhase.Rejected => "请求被拒绝",
            OperationPhase.NotSent => "已确认未发送",
            _ => "结果未知，请核实",
        };
    public string When => NasTaskRow.FormatTime(Record.CreatedAt);
    public string AccessibleName => $"{Name}，{Stage}，{When}";
    public override string ToString() => Name;

    internal void Update(OperationRecord record)
    {
        if (ReferenceEquals(Record, record)) return;
        if (record.Reconciliation is not null || record.Revision != Record.Revision) Observation = null;
        Record = record;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(string.Empty));
    }

    internal void Observe(OperationReconciliation observation)
    {
        if (observation.Reference != Record.Intent.Reference) return;
        Observation = observation;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(string.Empty));
    }
}

public sealed class NasTaskOperationItemRow
{
    internal NasTaskOperationItemRow(OperationItem item)
    {
        Name = "项目 " + ((long)item.Ordinal + 1).ToString(CultureInfo.CurrentCulture);
        Source = item.Source?.Resource.DisplayPath ?? "未提供来源";
        Target = item.Target?.Resource.DisplayPath ?? "未提供目标";
        Outcome = item.Outcome switch
        {
            FileOperationItemOutcome.Confirmed => "操作记录已确认",
            FileOperationItemOutcome.Failed => "操作记录报告失败",
            FileOperationItemOutcome.OutcomeUnknown => "结果未知，请核实",
            FileOperationItemOutcome.NotAttempted => "未尝试",
            _ => "未提供逐项确认",
        };
    }
    public string Name { get; }
    public string Source { get; }
    public string Target { get; }
    public string Outcome { get; }
    public override string ToString() => Name + "，" + Outcome;
}

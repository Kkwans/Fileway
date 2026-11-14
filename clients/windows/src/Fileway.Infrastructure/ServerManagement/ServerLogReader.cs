using System.Globalization;
using System.Text;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.ServerManagement;

/// <summary>
/// Reads the two durable channels the server writes and labels each line by keyword.
/// The Go server emits unprefixed text, so severity is a documented heuristic rather than a wire contract.
/// </summary>
internal static class ServerLogReader
{
    private const int MaximumTailBytes = 512 * 1024;

    private static readonly string[] ErrorMarkers =
    [
        "error", "failed", "fatal", "panic", "refused", "invalid", "cannot", "can't",
        "unable", "bind:", "timeout", "exception", "permission denied", "no such file", "does not exist",
    ];

    private static readonly string[] WarningMarkers = ["warning", "deprecat", "not available", "unavailable"];

    internal static LocalServerLogPage Read(LocalServerLayout layout, LocalServerFilter filter, int maxLines)
    {
        int budget = Math.Clamp(maxLines, 1, 2000);
        var lines = new List<LocalServerLogLine>(budget);
        bool truncated = false;
        truncated |= AppendTail(lines, layout.RuntimeLogPath, LocalServerLogChannel.Runtime, budget);
        truncated |= AppendTail(lines, layout.DiagnosticsLogPath, LocalServerLogChannel.Diagnostic, budget);

        int total = lines.Count;
        int errors = lines.Count(line => line.Severity == LocalServerLogSeverity.Error);
        IEnumerable<LocalServerLogLine> view = lines;
        if (filter.ErrorsOnly) view = view.Where(line => line.Severity == LocalServerLogSeverity.Error);
        if (!string.IsNullOrWhiteSpace(filter.Query))
        {
            string query = filter.Query.Trim();
            view = view.Where(line => line.Text.Contains(query, StringComparison.OrdinalIgnoreCase));
        }

        var ordered = view
            .OrderByDescending(line => line.Timestamp ?? DateTimeOffset.MinValue)
            .ThenByDescending(line => line.Severity == LocalServerLogSeverity.Error)
            .Take(budget)
            .ToList();
        return new LocalServerLogPage(ordered, truncated || ordered.Count < total, DateTimeOffset.Now, errors, total);
    }

    /// <summary>Newest diagnostics explain a failed launch better than any single exit code.</summary>
    internal static string DescribeFailure(string diagnosticsPath, int lines = 6)
    {
        var tail = ReadLines(diagnosticsPath, lines);
        for (int index = tail.Count - 1; index >= 0; index--)
        {
            string text = tail[index].Trim();
            if (text.Length == 0) continue;
            if (Classify(text, LocalServerLogChannel.Diagnostic) != LocalServerLogSeverity.Information) return Compact(text);
        }
        return string.Empty;
    }

    private static bool AppendTail(List<LocalServerLogLine> destination, string path, LocalServerLogChannel channel, int maxLines)
    {
        bool truncated = ReadLines(path, maxLines, out var raw, out bool overflowed);
        foreach (string text in raw)
        {
            if (text.Trim().Length == 0) continue;
            destination.Add(new LocalServerLogLine(Classify(text, channel), channel, text, ParseTimestamp(text)));
        }
        return truncated || overflowed;
    }

    private static List<string> ReadLines(string path, int maxLines)
    {
        ReadLines(path, maxLines, out var lines, out _);
        return lines;
    }

    private static bool ReadLines(string path, int maxLines, out List<string> lines, out bool truncated)
    {
        lines = [];
        truncated = false;
        try
        {
            if (!File.Exists(path)) return false;
            var information = new FileInfo(path);
            long start = Math.Max(0, information.Length - MaximumTailBytes);
            truncated = start > 0;
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            stream.Seek(start, SeekOrigin.Begin);
            using var reader = new StreamReader(stream, Encoding.UTF8);
            var buffer = new Queue<string>();
            string? line;
            while ((line = reader.ReadLine()) is not null)
            {
                buffer.Enqueue(line);
                if (buffer.Count > maxLines) { buffer.Dequeue(); truncated = true; }
            }
            lines.AddRange(buffer);
            return truncated;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    private static LocalServerLogSeverity Classify(string text, LocalServerLogChannel channel)
    {
        string probe = text.ToLowerInvariant();
        foreach (string marker in ErrorMarkers)
            if (probe.Contains(marker, StringComparison.Ordinal)) return LocalServerLogSeverity.Error;
        foreach (string marker in WarningMarkers)
            if (probe.Contains(marker, StringComparison.Ordinal)) return LocalServerLogSeverity.Warning;
        return LocalServerLogSeverity.Information;
    }

    private static DateTimeOffset? ParseTimestamp(string text)
    {
        // The server uses Go's default log prefix: "2026-11-01 22:07:24 ".
        const int prefixLength = 19;
        if (text.Length < prefixLength) return null;
        if (DateTimeOffset.TryParseExact(text.AsSpan(0, prefixLength), "yyyy-MM-dd HH:mm:ss",
            CultureInfo.InvariantCulture, DateTimeStyles.None, out DateTimeOffset parsed))
            return parsed;
        return null;
    }

    private static string Compact(string text, int maximum = 220) =>
        text.Length <= maximum ? text : text[..maximum] + "…";
}

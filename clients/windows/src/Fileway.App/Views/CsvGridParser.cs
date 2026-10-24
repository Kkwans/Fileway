namespace Fileway.App.Views;

/// <summary>
/// Bounded RFC 4180 CSV/TSV parsing for the read-only table view. Quotes double as escapes,
/// quoted fields may span lines. Parsing stops at the row cap and reports honest totals; it
/// never truncates a quoted field mid-value.
/// </summary>
internal static class CsvGridParser
{
    public const int MaximumRows = 500;
    public const int MaximumColumns = 60;

    public sealed record Grid(IReadOnlyList<string[]> Rows, int TotalRows, int TotalColumns,
        bool RowsTruncated, char Delimiter);

    public static char DetectDelimiter(string raw, string fileName)
    {
        if (fileName.EndsWith(".tsv", StringComparison.OrdinalIgnoreCase)) return '\t';
        if (fileName.EndsWith(".csv", StringComparison.OrdinalIgnoreCase)) return ',';
        string firstLine = raw.Split('\n')[0];
        int commas = CountOutsideQuotes(firstLine, ',');
        int semicolons = CountOutsideQuotes(firstLine, ';');
        int tabs = CountOutsideQuotes(firstLine, '\t');
        if (tabs > 0 && tabs >= commas && tabs >= semicolons) return '\t';
        return commas >= semicolons ? ',' : ';';
    }

    private static int CountOutsideQuotes(string line, char delimiter)
    {
        int count = 0;
        bool quoted = false;
        foreach (char value in line)
        {
            if (value == '"') quoted = !quoted;
            else if (value == delimiter && !quoted) count++;
        }
        return count;
    }

    public static Grid? Parse(string raw, char delimiter)
    {
        if (raw.Length == 0) return null;
        var rows = new List<string[]>();
        int totalColumns = 0;
        int totalRows = 0;
        var field = new System.Text.StringBuilder();
        var current = new List<string>();
        bool quoted = false;
        bool rowOpen = false;
        // One linear scan; '\r' is dropped because decoded text may keep CRLF endings.
        for (int index = 0; index < raw.Length; index++)
        {
            char value = raw[index];
            if (quoted)
            {
                if (value == '"')
                {
                    if (index + 1 < raw.Length && raw[index + 1] == '"') { field.Append('"'); index++; }
                    else quoted = false;
                }
                else if (value != '\r') field.Append(value);
                continue;
            }
            if (value == '"' && field.Length == 0) { quoted = true; rowOpen = true; continue; }
            if (value == delimiter) { current.Add(field.ToString()); field.Clear(); rowOpen = true; continue; }
            if (value is '\n')
            {
                current.Add(field.ToString());
                field.Clear();
                if (rowOpen || current.Count > 1 || current[0].Length > 0)
                {
                    totalRows++;
                    totalColumns = Math.Max(totalColumns, current.Count);
                    if (rows.Count < MaximumRows) rows.Add([.. current]);
                }
                current.Clear();
                rowOpen = false;
                continue;
            }
            if (value != '\r') field.Append(value);
        }
        if (quoted) return null; // Unterminated quote: not a well-formed table; keep plain text.
        current.Add(field.ToString());
        if (rowOpen || current.Count > 1 || current[0].Length > 0)
        {
            totalRows++;
            totalColumns = Math.Max(totalColumns, current.Count);
            if (rows.Count < MaximumRows) rows.Add([.. current]);
        }
        if (rows.Count == 0 || totalColumns == 0) return null;
        return new Grid(rows, totalRows, Math.Min(totalColumns, MaximumColumns), totalRows > rows.Count, delimiter);
    }
}

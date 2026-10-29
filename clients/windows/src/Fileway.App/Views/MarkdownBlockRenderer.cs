using System.Text.RegularExpressions;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Documents;
using Microsoft.UI.Xaml.Media;
using Windows.UI.Text;

namespace Fileway.App.Views;

/// <summary>
/// Bounded native Markdown rendering: headings, paragraphs with inline emphasis/code/links,
/// lists, code fences, blockquotes, horizontal rules and bare image alt text. Tables and exotic
/// constructs fall back to preformatted text rather than being silently dropped. Read-only;
/// this is a display aid for files the plain-text viewer can already show.
/// </summary>
internal static partial class MarkdownBlockRenderer
{
    [GeneratedRegex("^\\s*([#]{1,6})\\s+(.*)$")]
    private static partial Regex Heading();
    [GeneratedRegex("^\\s*(?:[-*+]|\\d{1,9}[.)])\\s+(.*)$")]
    private static partial Regex ListItem();
    [GeneratedRegex("^\\s*>\\s?(.*)$")]
    private static partial Regex Blockquote();
    [GeneratedRegex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")]
    private static partial Regex Rule();
    [GeneratedRegex("^\\s*```")]
    private static partial Regex Fence();

    public static bool LooksLikeMarkdown(string text)
    {
        if (text.Length > 1_000_000) return false;
        return text.Contains("\n# ", StringComparison.Ordinal) || text.StartsWith("# ", StringComparison.Ordinal) ||
            text.Contains("\n## ", StringComparison.Ordinal) || text.Contains("\n```", StringComparison.Ordinal) ||
            text.Contains("\n- ", StringComparison.Ordinal) || text.Contains("\n> ", StringComparison.Ordinal) ||
            text.Contains("](http", StringComparison.OrdinalIgnoreCase);
    }

    public static IReadOnlyList<UIElement> Render(string markdown)
    {
        var blocks = new List<UIElement>();
        var lines = markdown.Replace("\r\n", "\n").Split('\n');
        int index = 0;
        while (index < lines.Length)
        {
            string line = lines[index];
            if (string.IsNullOrWhiteSpace(line)) { index++; continue; }
            var heading = Heading().Match(line);
            if (heading.Success)
            {
                blocks.Add(HeadingBlock(heading.Groups[1].Length, heading.Groups[2].Value));
                index++;
                continue;
            }
            if (Fence().IsMatch(line))
            {
                var code = new List<string>();
                index++;
                while (index < lines.Length && !Fence().IsMatch(lines[index])) { code.Add(lines[index]); index++; }
                index++; // closing fence or EOF
                blocks.Add(CodeBlock(string.Join('\n', code)));
                continue;
            }
            if (Rule().IsMatch(line))
            {
                blocks.Add(new MenuFlyoutSeparator { Margin = new Thickness(0, 6, 0, 6) });
                index++;
                continue;
            }
            if (Blockquote().IsMatch(line))
            {
                var quoted = new List<string>();
                while (index < lines.Length)
                {
                    var match = Blockquote().Match(lines[index]);
                    if (!match.Success) break;
                    quoted.Add(match.Groups[1].Value);
                    index++;
                }
                blocks.Add(QuoteBlock(quoted));
                continue;
            }
            if (ListItem().IsMatch(line))
            {
                var items = new List<(bool Ordered, string Text)>();
                while (index < lines.Length)
                {
                    var match = ListItem().Match(lines[index]);
                    if (!match.Success) break;
                    bool ordered = char.IsAsciiDigit(lines[index].TrimStart()[0]);
                    items.Add((ordered, match.Groups[1].Value));
                    index++;
                }
                blocks.Add(ListBlock(items));
                continue;
            }
            // Paragraph: consecutive plain lines.
            var paragraph = new List<string>();
            while (index < lines.Length && !string.IsNullOrWhiteSpace(lines[index]) &&
                !Heading().IsMatch(lines[index]) && !Fence().IsMatch(lines[index]) &&
                !Rule().IsMatch(lines[index]) && !ListItem().IsMatch(lines[index]) &&
                !Blockquote().IsMatch(lines[index]))
            {
                paragraph.Add(lines[index]);
                index++;
            }
            blocks.Add(ParagraphBlock(string.Join('\n', paragraph)));
        }
        return blocks;
    }

    private static TextBlock HeadingBlock(int level, string text)
    {
        double size = level switch { 1 => 30, 2 => 24, 3 => 20, 4 => 17.5, 5 => 15.5, _ => 14.5 };
        var block = new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            FontSize = size,
            FontWeight = level <= 2 ? FontWeights.Bold : FontWeights.SemiBold,
            LineHeight = size * 1.35,
            Margin = new Thickness(0, level == 1 ? 6 : level == 2 ? 22 : 16, 0, level <= 2 ? 10 : 6),
        };
        ApplyInline(block.Inlines, text);
        return block;
    }

    private static TextBlock ParagraphBlock(string text)
    {
        var block = new TextBlock { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 14), LineHeight = 26, FontSize = 15 };
        ApplyInline(block.Inlines, text);
        return block;
    }

    private static StackPanel ListBlock(List<(bool Ordered, string Text)> items)
    {
        var panel = new StackPanel { Spacing = 7, Margin = new Thickness(22, 2, 0, 16) };
        for (int index = 0; index < items.Count; index++)
        {
            var (_, text) = items[index];
            var line = new TextBlock { TextWrapping = TextWrapping.Wrap, LineHeight = 24, FontSize = 15 };
            line.Inlines.Add(new Run { Text = (items[index].Ordered ? (index + 1) + ". " : "• "), Foreground = (Brush)Microsoft.UI.Xaml.Application.Current.Resources["TextFillColorSecondaryBrush"] });
            ApplyInline(line.Inlines, text);
            panel.Children.Add(line);
        }
        return panel;
    }

    private static Border QuoteBlock(IReadOnlyList<string> lines)
    {
        var block = new TextBlock { TextWrapping = TextWrapping.Wrap, FontStyle = global::Windows.UI.Text.FontStyle.Italic, Margin = new Thickness(12, 0, 0, 8), Opacity = 0.85 };
        ApplyInline(block.Inlines, string.Join('\n', lines));
        var border = new Border { BorderThickness = new Thickness(3, 0, 0, 0), BorderBrush = (Brush)Microsoft.UI.Xaml.Application.Current.Resources["ControlStrokeColorDefaultBrush"], Padding = new Thickness(8, 2, 0, 2), Child = block };
        return border;
    }

    private static Border CodeBlock(string code)
    {
        var block = new TextBlock
        {
            FontFamily = new FontFamily("Cascadia Code, Consolas, Courier New"),
            TextWrapping = TextWrapping.NoWrap,
            FontSize = 13,
            LineHeight = 21,
            IsTextSelectionEnabled = true,
        };
        block.Inlines.Add(new Run { Text = code.Length == 0 ? " " : code });
        var scroll = new ScrollViewer
        {
            Content = block,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            VerticalScrollBarVisibility = ScrollBarVisibility.Disabled,
            HorizontalScrollMode = ScrollMode.Enabled,
            VerticalScrollMode = ScrollMode.Disabled,
        };
        return new Border
        {
            Background = (Brush)Microsoft.UI.Xaml.Application.Current.Resources["LayerFillColorDefaultBrush"],
            BorderBrush = (Brush)Microsoft.UI.Xaml.Application.Current.Resources["CardStrokeColorDefaultBrush"],
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(10),
            Padding = new Thickness(16, 12, 16, 12),
            Margin = new Thickness(0, 4, 0, 16),
            Child = scroll,
        };
    }

    private static void ApplyInline(InlineCollection target, string text)
    {
        // Image syntax collapses to its alt text; a viewer without remote image layout says so
        // instead of drawing a broken placeholder.
        text = System.Text.RegularExpressions.Regex.Replace(text, @"!\[([^\]]*)\]\(([^)\s]+)(?:\s+""[^""]*"")?\)", static match =>
            match.Groups[1].Value.Length > 0 ? $"[图片: {match.Groups[1].Value}]" : "[图片]");
        int position = 0;
        while (position < text.Length)
        {
            int next = NextMarker(text, position);
            if (next < 0)
            {
                if (position < text.Length) target.Add(new Run { Text = text[position..] });
                break;
            }
            if (next > position) target.Add(new Run { Text = text[position..next] });
            char marker = text[next];
            int? consumed = null;
            if (marker == '`')
            {
                int end = text.IndexOf('`', next + 1);
                if (end > next)
                {
                    target.Add(new Span
                    {
                        FontFamily = new FontFamily("Cascadia Code, Consolas, Courier New"),
                        FontSize = 13.5,
                        Foreground = (Brush)Microsoft.UI.Xaml.Application.Current.Resources["AccentTextFillColorPrimaryBrush"],
                        Inlines = { new Run { Text = text[(next + 1)..end] } },
                    });
                    consumed = end + 1;
                }
            }
            else if (marker is '*' or '_' or '~')
            {
                bool doubleMarker = next + 1 < text.Length && text[next + 1] == marker;
                string opener = doubleMarker ? text.Substring(next, 2) : text.Substring(next, 1);
                string closer = doubleMarker ? opener : marker.ToString();
                int end = text.IndexOf(closer, next + opener.Length, StringComparison.Ordinal);
                if (end > next + opener.Length)
                {
                    var span = new Span();
                    ApplyInline(span.Inlines, text[(next + opener.Length)..end]);
                    if (marker == '~') span.TextDecorations = global::Windows.UI.Text.TextDecorations.Strikethrough;
                    else if (doubleMarker) span.FontWeight = FontWeights.Bold;
                    else span.FontStyle = global::Windows.UI.Text.FontStyle.Italic;
                    target.Add(span);
                    consumed = end + closer.Length;
                }
            }
            else if (marker == '[')
            {
                var link = System.Text.RegularExpressions.Regex.Match(text[next..], @"^\[([^\]]+)\]\(([^)\s]+)(?:\s+""[^""]*"")?\)");
                if (link.Success)
                {
                    var run = new Run { Text = link.Groups[1].Value, TextDecorations = global::Windows.UI.Text.TextDecorations.Underline };
                    var hyperlink = new Hyperlink { Inlines = { run } };
                    if (Uri.TryCreate(link.Groups[2].Value, UriKind.Absolute, out var uri) &&
                        (uri.Scheme == Uri.UriSchemeHttp || uri.Scheme == Uri.UriSchemeHttps))
                        hyperlink.NavigateUri = uri; // relative targets stay inert text in a read-only viewer
                    target.Add(hyperlink);
                    consumed = next + link.Length;
                }
            }
            if (consumed is { } advance)
            {
                position = advance;
            }
            else
            {
                target.Add(new Run { Text = marker.ToString() });
                position = next + 1;
            }
        }
    }

    private static int NextMarker(string text, int start)
    {
        for (int index = start; index < text.Length; index++)
        {
            char value = text[index];
            if (value is '`' or '[' or '*' or '_' or '~')
            {
                // Underscore inside words is overwhelmingly identifier syntax, not emphasis.
                if (value == '_' && index > start && (char.IsAsciiLetterOrDigit(text[index - 1]) || text[index - 1] == '_')) continue;
                return index;
            }
            if (value == '!' && index + 1 < text.Length && text[index + 1] == '[') return index + 1;
        }
        return -1;
    }
}

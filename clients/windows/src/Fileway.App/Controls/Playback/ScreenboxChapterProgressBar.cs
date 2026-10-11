// Algorithm adapted from Screenbox 6870eb0 Controls/ChapterProgressBar.xaml.cs
// (GPL-3.0): proportional chapter segments, 1 DIP gaps, update only changed
// segment values. Fileway uses start-only chapter records and observed buffering.
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App.Controls.Playback;

public sealed partial class ScreenboxChapterProgressBar : UserControl
{
    private sealed record Segment(double Start, double End, Grid Buffer, ProgressBar Progress);
    private readonly List<Segment> _segments = [];
    private double[] _starts = [];
    private double _duration;
    private BufferedTimeRange[] _bufferedRanges = [];

    public ScreenboxChapterProgressBar()
    {
        InitializeComponent();
    }

    public void Apply(double duration, double position, IReadOnlyList<BufferedTimeRange>? bufferedRanges, IReadOnlyList<PlaybackChapter>? chapters)
    {
        var starts = (chapters ?? []).Select(chapter => chapter.Position.TotalSeconds)
            .Where(start => start > 0 && start < duration).Prepend(0).Distinct().Order().ToArray();
        var rebuild = _duration != duration || !_starts.SequenceEqual(starts);
        if (rebuild)
        {
            _duration = duration; _starts = starts;
            _segments.Clear(); Segments.Children.Clear(); Segments.ColumnDefinitions.Clear();
            for (var index = 0; index < starts.Length; index++)
            {
                var end = index + 1 < starts.Length ? starts[index + 1] : duration;
                var length = Math.Max(0.001, end - starts[index]);
                Segments.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(length, GridUnitType.Star) });
                var track = CreateProgress(length, "BufferedChapterStyle");
                var buffer = new Grid();
                var progress = CreateProgress(length, "PlayedChapterStyle");
                var layer = new Grid(); layer.Children.Add(track); layer.Children.Add(buffer); layer.Children.Add(progress);
                Grid.SetColumn(layer, index); Segments.Children.Add(layer);
                _segments.Add(new Segment(starts[index], end, buffer, progress));
            }
        }
        var ranges = bufferedRanges?.ToArray() ?? [];
        var updateBuffer = rebuild || !_bufferedRanges.SequenceEqual(ranges);
        _bufferedRanges = ranges;
        foreach (var segment in _segments)
        {
            var length = segment.End - segment.Start;
            var value = Math.Clamp(position - segment.Start, 0, length);
            if (segment.Progress.Value != value) segment.Progress.Value = value;
            if (updateBuffer) DrawBufferedRanges(segment, ranges);
        }
    }

    private void DrawBufferedRanges(Segment segment, IReadOnlyList<BufferedTimeRange> ranges)
    {
        segment.Buffer.Children.Clear(); segment.Buffer.ColumnDefinitions.Clear();
        var intersections = ranges.Select(range => (Start: Math.Max(segment.Start, range.Start.TotalSeconds), End: Math.Min(segment.End, range.End.TotalSeconds)))
            .Where(range => range.End > range.Start).OrderBy(range => range.Start);
        var merged = new List<(double Start, double End)>();
        foreach (var range in intersections)
        {
            if (merged.Count > 0 && range.Start <= merged[^1].End)
                merged[^1] = (merged[^1].Start, Math.Max(merged[^1].End, range.End));
            else merged.Add(range);
        }
        var cursor = segment.Start;
        foreach (var range in merged)
        {
            AddPart(range.Start - cursor, false);
            AddPart(range.End - range.Start, true);
            cursor = range.End;
        }
        AddPart(segment.End - cursor, false);

        void AddPart(double seconds, bool buffered)
        {
            if (seconds <= 0) return;
            var column = segment.Buffer.ColumnDefinitions.Count;
            segment.Buffer.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(seconds, GridUnitType.Star) });
            if (!buffered) return;
            var fill = CreateProgress(1, "BufferedChapterStyle");
            fill.Background = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Transparent);
            fill.Value = 1;
            Grid.SetColumn(fill, column); segment.Buffer.Children.Add(fill);
        }
    }

    private ProgressBar CreateProgress(double maximum, string style)
    {
        return new ProgressBar { Minimum = 0, Maximum = maximum, Style = (Style)Resources[style] };
    }
}

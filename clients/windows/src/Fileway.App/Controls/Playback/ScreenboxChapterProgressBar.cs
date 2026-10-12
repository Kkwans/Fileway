// Algorithm adapted from Screenbox 6870eb0 Controls/ChapterProgressBar.xaml.cs
// (GPL-3.0): proportional chapter segments, 1 DIP gaps, update only changed
// segment values. Fileway uses start-only chapter records and observed buffering.
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Fileway.App.Controls.Playback;

public sealed partial class ScreenboxChapterProgressBar : UserControl
{
    private sealed record BufferedPart(ColumnDefinition Column, ProgressBar? Fill);
    private sealed record Segment(double Start, double End, Grid Buffer, ProgressBar Progress)
    {
        public List<BufferedPart> BufferedParts { get; } = [];
    }
    private readonly List<Segment> _segments = [];
    private readonly List<(double Start, double End)> _mergedRanges = [];
    private readonly List<double> _bufferWidths = [];
    private readonly SolidColorBrush _transparentBufferBrush = new(Microsoft.UI.Colors.Transparent);
    private double[] _starts = [];
    private double[] _chapterPositions = [];
    private double _duration;
    private BufferedTimeRange[] _bufferedRanges = [];

    public ScreenboxChapterProgressBar()
    {
        InitializeComponent();
    }

    public void Apply(double duration, double position, IReadOnlyList<BufferedTimeRange>? bufferedRanges, IReadOnlyList<PlaybackChapter>? chapters)
    {
        var durationChanged = _duration != duration;
        var chaptersChanged = !ChapterPositionsMatch(chapters);
        var starts = _starts;
        if (durationChanged || chaptersChanged || _segments.Count == 0)
        {
            if (chaptersChanged) _chapterPositions = (chapters ?? []).Select(chapter => chapter.Position.TotalSeconds).ToArray();
            starts = _chapterPositions.Where(start => start > 0 && start < duration).Prepend(0).Distinct().Order().ToArray();
        }
        var rebuild = durationChanged || !_starts.SequenceEqual(starts) || _segments.Count == 0;
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
        var rangesChanged = !BufferedRangesMatch(bufferedRanges);
        if (rangesChanged)
        {
            _bufferedRanges = bufferedRanges?.ToArray() ?? [];
            ScreenboxBufferedTimelineLayout.MergeRanges(_bufferedRanges, _mergedRanges);
        }
        var updateBuffer = rebuild || rangesChanged;
        foreach (var segment in _segments)
        {
            var length = segment.End - segment.Start;
            var value = Math.Clamp(position - segment.Start, 0, length);
            if (segment.Progress.Value != value) segment.Progress.Value = value;
            if (updateBuffer) DrawBufferedRanges(segment);
        }
    }

    private bool ChapterPositionsMatch(IReadOnlyList<PlaybackChapter>? chapters)
    {
        if (_chapterPositions.Length != (chapters?.Count ?? 0)) return false;
        for (var index = 0; index < _chapterPositions.Length; index++)
            if (_chapterPositions[index] != chapters![index].Position.TotalSeconds) return false;
        return true;
    }

    private bool BufferedRangesMatch(IReadOnlyList<BufferedTimeRange>? ranges)
    {
        if (_bufferedRanges.Length != (ranges?.Count ?? 0)) return false;
        for (var index = 0; index < _bufferedRanges.Length; index++)
            if (_bufferedRanges[index] != ranges![index]) return false;
        return true;
    }

    private void DrawBufferedRanges(Segment segment)
    {
        ScreenboxBufferedTimelineLayout.FillSegmentWidths(segment.Start, segment.End, _mergedRanges, _bufferWidths);
        // Retain the largest topology seen in this timeline. Endpoint movement,
        // empty ranges and merging/splitting intervals reuse the same native
        // objects; rebuilding chapter/duration structure releases these slots.
        while (segment.BufferedParts.Count < _bufferWidths.Count)
        {
            var index = segment.BufferedParts.Count;
            var column = new ColumnDefinition { Width = new GridLength(0, GridUnitType.Star) };
            segment.Buffer.ColumnDefinitions.Add(column);
            ProgressBar? fill = null;
            if (index % 2 == 1)
            {
                fill = CreateProgress(1, "BufferedChapterStyle");
                fill.Background = _transparentBufferBrush;
                fill.Value = 1;
                Grid.SetColumn(fill, index);
                segment.Buffer.Children.Add(fill);
            }
            segment.BufferedParts.Add(new BufferedPart(column, fill));
        }
        for (var index = 0; index < segment.BufferedParts.Count; index++)
        {
            var part = segment.BufferedParts[index];
            var seconds = index < _bufferWidths.Count ? _bufferWidths[index] : 0;
            if (part.Column.Width.Value != seconds) part.Column.Width = new GridLength(seconds, GridUnitType.Star);
            if (part.Fill is { } fill)
            {
                var visibility = seconds > 0 ? Visibility.Visible : Visibility.Collapsed;
                if (fill.Visibility != visibility) fill.Visibility = visibility;
            }
        }
    }

    private ProgressBar CreateProgress(double maximum, string style)
    {
        return new ProgressBar { Minimum = 0, Maximum = maximum, Style = (Style)Resources[style] };
    }
}

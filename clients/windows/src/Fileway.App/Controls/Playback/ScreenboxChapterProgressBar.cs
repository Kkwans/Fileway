// Algorithm adapted from Screenbox 6870eb0 Controls/ChapterProgressBar.xaml.cs
// (GPL-3.0): proportional chapter segments, 1 DIP gaps, update only changed
// segment values. Fileway uses start-only chapter records and observed buffering.
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App.Controls.Playback;

public sealed partial class ScreenboxChapterProgressBar : UserControl
{
    private sealed record Segment(double Start, double End, ProgressBar Buffer, ProgressBar Progress);
    private readonly List<Segment> _segments = [];
    private double[] _starts = [];
    private double _duration;

    public ScreenboxChapterProgressBar()
    {
        InitializeComponent();
    }

    public void Apply(double duration, double position, TimeSpan? bufferedUntil, IReadOnlyList<PlaybackChapter>? chapters)
    {
        var starts = (chapters ?? []).Select(chapter => chapter.Position.TotalSeconds)
            .Where(start => start > 0 && start < duration).Prepend(0).Distinct().Order().ToArray();
        if (_duration != duration || !_starts.SequenceEqual(starts))
        {
            _duration = duration; _starts = starts;
            _segments.Clear(); Segments.Children.Clear(); Segments.ColumnDefinitions.Clear();
            for (var index = 0; index < starts.Length; index++)
            {
                var end = index + 1 < starts.Length ? starts[index + 1] : duration;
                var length = Math.Max(0.001, end - starts[index]);
                Segments.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(length, GridUnitType.Star) });
                var buffer = CreateProgress(length, "BufferedChapterStyle");
                var progress = CreateProgress(length, "PlayedChapterStyle");
                var layer = new Grid(); layer.Children.Add(buffer); layer.Children.Add(progress);
                Grid.SetColumn(layer, index); Segments.Children.Add(layer);
                _segments.Add(new Segment(starts[index], end, buffer, progress));
            }
        }
        foreach (var segment in _segments)
        {
            var length = segment.End - segment.Start;
            var value = Math.Clamp(position - segment.Start, 0, length);
            if (segment.Progress.Value != value) segment.Progress.Value = value;
            var buffered = bufferedUntil is { } until ? Math.Clamp(until.TotalSeconds - segment.Start, 0, length) : 0;
            if (segment.Buffer.Value != buffered) segment.Buffer.Value = buffered;
        }
    }

    private ProgressBar CreateProgress(double maximum, string style)
    {
        return new ProgressBar { Minimum = 0, Maximum = maximum, Style = (Style)Resources[style] };
    }
}

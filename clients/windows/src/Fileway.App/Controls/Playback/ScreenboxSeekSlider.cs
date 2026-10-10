// Fileway adapter for Screenbox's native Slider + segmented timeline composition.
// Keep the pinned SDK DefaultSliderStyle and all native input/UIA behavior;
// attach progress to its actual track row rather than a sibling's center.
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App.Controls.Playback;

public sealed class ScreenboxSeekSlider : Slider
{
    private readonly ScreenboxChapterProgressBar _progress = new();
    private Grid? _trackHost;

    protected override void OnApplyTemplate()
    {
        if (_trackHost is not null) _trackHost.Children.Remove(_progress);
        base.OnApplyTemplate();
        _trackHost = GetTemplateChild("HorizontalTemplate") as Grid;
        if (_trackHost is null) return;
        // DefaultSliderStyle: HorizontalTrackRect is in row 1, spans all three
        // columns; HorizontalThumb spans the same three vertical rows. Sharing
        // that row preserves their center at every layout/DPI, including padding.
        Grid.SetRow(_progress, 1);
        Grid.SetColumnSpan(_progress, 3);
        _progress.VerticalAlignment = VerticalAlignment.Center;
        _trackHost.Children.Insert(0, _progress);
    }

    public void ApplyTimeline(double duration, double position, TimeSpan? bufferedUntil, IReadOnlyList<PlaybackChapter>? chapters)
    {
        _progress.Visibility = duration > 0 ? Visibility.Visible : Visibility.Collapsed;
        if (duration > 0) _progress.Apply(duration, position, bufferedUntil, chapters);
    }
}

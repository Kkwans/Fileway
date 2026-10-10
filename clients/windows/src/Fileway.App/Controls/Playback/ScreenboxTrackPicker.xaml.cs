// Screenbox CompositeTrackPicker structure adapted to Fileway (6870eb0, GPL-3.0).
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App.Controls.Playback;

public sealed partial class ScreenboxTrackPicker : UserControl
{
    private readonly MediaTrackKind _kind;
    private readonly Action<long?> _select;
    private readonly Action _addSubtitle;
    private bool _updating;
    private bool _populated;
    private ListViewItem? _confirmed;
    private MediaTrack[] _previous = [];

    public ScreenboxTrackPicker(MediaTrackKind kind, IReadOnlyList<MediaTrack> tracks, Action<long?> select, Action addSubtitle)
    {
        _kind = kind; _select = select; _addSubtitle = addSubtitle;
        InitializeComponent();
        Heading.Text = kind == MediaTrackKind.Audio ? "音轨" : "字幕";
        AddSubtitleButton.Visibility = kind == MediaTrackKind.Subtitle ? Visibility.Visible : Visibility.Collapsed;
        ApplyTracks(tracks);
    }

    public void ApplyTracks(IReadOnlyList<MediaTrack> tracks)
    {
        var filtered = tracks.Where(track => track.Kind == _kind).ToArray();
        if (_populated && _previous.SequenceEqual(filtered)) return;
        if (_populated && _previous.Length == filtered.Length && _previous.Zip(filtered).All(pair =>
            (pair.First with { Selected = false }) == (pair.Second with { Selected = false })))
        {
            _previous = filtered;
            var selectedId = filtered.FirstOrDefault(track => track.Selected)?.Id;
            _confirmed = Tracks.Items.OfType<ListViewItem>().FirstOrDefault(item =>
                item.Tag is long id ? id == selectedId : selectedId is null && _kind == MediaTrackKind.Subtitle);
            _updating = true; Tracks.SelectedItem = _confirmed; _updating = false;
            return;
        }
        _populated = true;
        _previous = filtered; _updating = true;
        Tracks.Items.Clear(); _confirmed = null;
        if (_kind == MediaTrackKind.Subtitle)
        {
            var off = MakeItem("关闭字幕", null, null);
            Tracks.Items.Add(off);
            if (!filtered.Any(track => track.Selected)) _confirmed = off;
        }
        foreach (var track in filtered)
        {
            var name = !string.IsNullOrWhiteSpace(track.Title) ? track.Title : track.Language ?? $"轨道 {track.Id}";
            var metadata = string.Join(" · ", new[] { track.Language, track.Codec, track.External ? "外挂" : null }.Where(value => !string.IsNullOrWhiteSpace(value)));
            var item = MakeItem(name, metadata, track.Id);
            Tracks.Items.Add(item);
            if (track.Selected) _confirmed = item;
        }
        Tracks.SelectedItem = _confirmed;
        _updating = false;
    }

    private static ListViewItem MakeItem(string title, string? detail, long? id)
    {
        var panel = new StackPanel { Spacing = 4, Margin = new Thickness(0, 6, 0, 6) };
        panel.Children.Add(new TextBlock { Text = title, TextTrimming = TextTrimming.CharacterEllipsis, FontSize = 14 });
        if (!string.IsNullOrWhiteSpace(detail)) panel.Children.Add(new TextBlock { Text = detail, FontSize = 12, Opacity = 0.7, TextTrimming = TextTrimming.CharacterEllipsis });
        var item = new ListViewItem { Content = panel, Tag = id, MinHeight = 48, HorizontalContentAlignment = HorizontalAlignment.Stretch };
        ToolTipService.SetToolTip(item, title + (string.IsNullOrWhiteSpace(detail) ? "" : " · " + detail));
        AutomationProperties.SetName(item, title + " " + detail);
        return item;
    }

    private void Tracks_SelectionChanged(object sender, SelectionChangedEventArgs args)
    {
        if (_updating || Tracks.SelectedItem is not ListViewItem item) return;
        var id = item.Tag is long value ? value : (long?)null;
        // Do not announce an optimistic selection as engine-confirmed.
        _updating = true; Tracks.SelectedItem = _confirmed; _updating = false;
        _select(id);
    }
    private void AddSubtitle_Click(object sender, RoutedEventArgs args) => _addSubtitle();
}

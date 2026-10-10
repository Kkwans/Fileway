// Screenbox VolumeControl layout/interaction port (6870eb0, GPL-3.0).
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using VirtualKey = Windows.System.VirtualKey;

namespace Fileway.App.Controls.Playback;

public sealed partial class ScreenboxVolumeControl : UserControl
{
    private readonly Action<double> _setVolume;
    private readonly Action<bool> _setMuted;
    private bool _updating;
    private bool _editing;
    private double _confirmedVolume;
    private bool _confirmedMuted;
    private bool _commandPending;

    public ScreenboxVolumeControl(double volume, bool muted, Action<double> setVolume, Action<bool> setMuted)
    {
        _setVolume = setVolume; _setMuted = setMuted;
        _updating = true;
        InitializeComponent();
        VolumeSlider.AddHandler(PointerPressedEvent, new PointerEventHandler((_, _) => _editing = true), true);
        VolumeSlider.AddHandler(PointerReleasedEvent, new PointerEventHandler((_, _) => { _editing = false; ApplyConfirmed(_confirmedVolume, _confirmedMuted, _commandPending); }), true);
        VolumeSlider.AddHandler(PointerCanceledEvent, new PointerEventHandler((_, _) => { _editing = false; ApplyConfirmed(_confirmedVolume, _confirmedMuted); }), true);
        VolumeSlider.PreviewKeyDown += (_, args) => { if (IsRangeKey(args.Key)) _editing = true; };
        VolumeSlider.KeyUp += (_, args) => { if (IsRangeKey(args.Key)) { _editing = false; ApplyConfirmed(_confirmedVolume, _confirmedMuted, _commandPending); } };
        VolumeSlider.LostFocus += (_, _) => { _editing = false; ApplyConfirmed(_confirmedVolume, _confirmedMuted, _commandPending); };
        _updating = true; VolumeSlider.Value = Math.Clamp(volume, 0, 100); _updating = false;
        ApplyConfirmed(volume, muted);
    }

    public void ApplyConfirmed(double volume, bool muted, bool commandPending = false)
    {
        _confirmedVolume = volume; _confirmedMuted = muted;
        _commandPending = commandPending;
        if (!commandPending && !_editing) { _updating = true; VolumeSlider.Value = Math.Clamp(volume, 0, 100); _updating = false; }
        MuteToggle.IsChecked = muted;
        VolumeGlyph.IconName = muted ? "SpeakerMute" : volume < 34 ? "Speaker0" : volume < 67 ? "Speaker1" : "Speaker2";
        AutomationProperties.SetName(MuteToggle, muted ? "取消静音" : "静音");
        VolumeValue.Text = $"{volume:0}%";
    }

    private void Volume_ValueChanged(object sender, RangeBaseValueChangedEventArgs args) { if (!_updating) _setVolume(args.NewValue); }
    private void Mute_Click(object sender, RoutedEventArgs args) => _setMuted(MuteToggle.IsChecked == true);
    private static bool IsRangeKey(VirtualKey key) => key is VirtualKey.Left or VirtualKey.Right or VirtualKey.Up or VirtualKey.Down or VirtualKey.Home or VirtualKey.End or VirtualKey.PageUp or VirtualKey.PageDown;
}

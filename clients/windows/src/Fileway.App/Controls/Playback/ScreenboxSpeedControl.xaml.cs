// Screenbox PlaybackSpeedControl layout/interaction port (6870eb0, GPL-3.0).
// Fileway session adapter; this control requests changes and displays confirmed state.
using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using VirtualKey = Windows.System.VirtualKey;

namespace Fileway.App.Controls.Playback;

public sealed partial class ScreenboxSpeedControl : UserControl
{
    private readonly Action<double> _request;
    private bool _updating;
    private double _requestedRate;
    private double _confirmedRate;
    private bool _sliderEditing;
    private bool _commandPending;

    public ScreenboxSpeedControl(double rate, Action<double> request)
    {
        _request = request;
        _updating = true;
        InitializeComponent();
        RateSlider.AddHandler(PointerPressedEvent, new PointerEventHandler((_, _) => _sliderEditing = true), true);
        RateSlider.AddHandler(PointerReleasedEvent, new PointerEventHandler((_, _) => { _sliderEditing = false; ApplyConfirmedRate(_confirmedRate, _commandPending); }), true);
        RateSlider.AddHandler(PointerCanceledEvent, new PointerEventHandler((_, _) => { _sliderEditing = false; ApplyConfirmedRate(_confirmedRate); }), true);
        RateSlider.PreviewKeyDown += (_, args) => { if (IsRangeKey(args.Key)) _sliderEditing = true; };
        RateSlider.KeyUp += (_, args) => { if (IsRangeKey(args.Key)) { _sliderEditing = false; ApplyConfirmedRate(_confirmedRate, _commandPending); } };
        RateSlider.LostFocus += (_, _) => { _sliderEditing = false; ApplyConfirmedRate(_confirmedRate, _commandPending); };
        SetRate(rate);
    }

    public void ApplyConfirmedRate(double rate, bool commandPending = false)
    {
        _confirmedRate = rate;
        _commandPending = commandPending;
        if (!commandPending && !_sliderEditing && !HasFocusWithin(PreciseRate))
        {
            _updating = true;
            _requestedRate = rate;
            RateSlider.Value = rate;
            PreciseRate.Value = rate;
            _updating = false;
        }
        ConfirmedRate.Text = Format(rate);
        UpdatePresetChecks(rate);
    }

    private void UpdatePresetChecks(double rate)
    {
        Rate050.IsChecked = Math.Abs(rate - 0.5) < 0.0001;
        Rate075.IsChecked = Math.Abs(rate - 0.75) < 0.0001;
        Rate100.IsChecked = Math.Abs(rate - 1) < 0.0001;
        Rate150.IsChecked = Math.Abs(rate - 1.5) < 0.0001;
        Rate200.IsChecked = Math.Abs(rate - 2) < 0.0001;
    }

    private void SetRate(double rate)
    {
        _updating = true;
        _requestedRate = Math.Clamp(rate, 0.125, 5);
        RateSlider.Value = _requestedRate;
        PreciseRate.Value = _requestedRate;
        _updating = false;
        ApplyConfirmedRate(rate);
    }

    private void Request(double rate)
    {
        if (!double.IsFinite(rate)) return;
        _requestedRate = Math.Clamp(rate, 0.125, 5);
        _updating = true;
        RateSlider.Value = _requestedRate;
        PreciseRate.Value = _requestedRate;
        _updating = false;
        _request(_requestedRate);
    }

    private void RateSlider_ValueChanged(object sender, RangeBaseValueChangedEventArgs args) { if (!_updating) Request(args.NewValue); }
    private void Decrease_Click(object sender, RoutedEventArgs args) => Request(_requestedRate - 0.125);
    private void Increase_Click(object sender, RoutedEventArgs args) => Request(_requestedRate + 0.125);
    private void Preset_Click(object sender, RoutedEventArgs args)
    {
        Request(double.Parse((string)((ToggleButton)sender).Tag, CultureInfo.InvariantCulture));
        UpdatePresetChecks(_confirmedRate);
    }
    private void Precise_Click(object sender, RoutedEventArgs args) => Request(PreciseRate.Value);
    private static string Format(double rate) => rate.ToString("0.###", CultureInfo.CurrentCulture) + "×";
    private static bool IsRangeKey(VirtualKey key) => key is VirtualKey.Left or VirtualKey.Right or VirtualKey.Up or VirtualKey.Down or VirtualKey.Home or VirtualKey.End or VirtualKey.PageUp or VirtualKey.PageDown;
    private bool HasFocusWithin(DependencyObject parent)
    {
        var focused = XamlRoot is null ? null : FocusManager.GetFocusedElement(XamlRoot) as DependencyObject;
        while (focused is not null) { if (focused == parent) return true; focused = VisualTreeHelper.GetParent(focused); }
        return false;
    }
}

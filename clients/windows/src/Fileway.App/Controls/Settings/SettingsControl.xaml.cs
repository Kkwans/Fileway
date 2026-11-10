using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App.Controls.Settings;

public sealed partial class SettingsControl : UserControl
{
    private bool _loading;

    public event EventHandler? CloseRequested;
    public event EventHandler? AppearanceRequested;
    public event EventHandler? OpenWithRequested;
    public event EventHandler? StorageRequested;
    public event EventHandler? ServerToolsRequested;
    public event EventHandler? LocalServerRequested;
    public event EventHandler? AboutRequested;
    public event EventHandler<ElementTheme>? ThemeRequested;
    public event EventHandler<bool>? TrayRequested;

    public SettingsControl()
    {
        InitializeComponent();
        ThemeBox.SelectionChanged += (_, _) =>
        {
            if (_loading) return;
            ThemeRequested?.Invoke(this, ThemeBox.SelectedIndex switch { 1 => ElementTheme.Light, 2 => ElementTheme.Dark, _ => ElementTheme.Default });
        };
        MaterialBox.SelectionChanged += (_, _) =>
        {
            if (_loading) return;
            PresentationPreferences.ShellMaterial = MaterialBox.SelectedIndex;
            ToneBox.IsEnabled = MaterialBox.SelectedIndex == 0;
        };
        ToneBox.SelectionChanged += (_, _) =>
        {
            if (_loading) return;
            PresentationPreferences.ShellTone = ToneBox.SelectedIndex;
        };
        TraySwitch.Toggled += (_, _) =>
        {
            if (_loading) return;
            TrayRequested?.Invoke(this, TraySwitch.IsOn);
        };
        ToastSlider.ValueChanged += (_, args) =>
        {
            ToastValue.Text = $"{(int)args.NewValue} 秒";
            if (_loading) return;
            PresentationPreferences.ToastSeconds = (int)args.NewValue;
        };
        ControlsSlider.ValueChanged += (_, args) =>
        {
            ControlsValue.Text = $"{(int)args.NewValue} 秒";
            if (_loading) return;
            PresentationPreferences.ControlsHideSeconds = (int)args.NewValue;
        };
        RememberPlayerSwitch.Toggled += (_, _) =>
        {
            if (_loading) return;
            PresentationPreferences.RememberPlayerSize = RememberPlayerSwitch.IsOn;
        };
    }

    public void Initialize(ElementTheme theme, bool closeToTray)
    {
        _loading = true;
        ThemeBox.SelectedIndex = theme switch { ElementTheme.Light => 1, ElementTheme.Dark => 2, _ => 0 };
        MaterialBox.SelectedIndex = PresentationPreferences.ShellMaterial;
        ToneBox.SelectedIndex = PresentationPreferences.ShellTone;
        ToneBox.IsEnabled = MaterialBox.SelectedIndex == 0;
        TraySwitch.IsOn = closeToTray;
        ToastSlider.Value = PresentationPreferences.ToastSeconds;
        ControlsSlider.Value = PresentationPreferences.ControlsHideSeconds;
        ToastValue.Text = $"{PresentationPreferences.ToastSeconds} 秒";
        ControlsValue.Text = $"{PresentationPreferences.ControlsHideSeconds} 秒";
        RememberPlayerSwitch.IsOn = PresentationPreferences.RememberPlayerSize;
        _loading = false;
    }

    private void Close_Click(object sender, RoutedEventArgs e) => CloseRequested?.Invoke(this, EventArgs.Empty);
    private void Appearance_Click(object sender, RoutedEventArgs e) => AppearanceRequested?.Invoke(this, EventArgs.Empty);
    private void OpenWith_Click(object sender, RoutedEventArgs e) => OpenWithRequested?.Invoke(this, EventArgs.Empty);
    private void Storage_Click(object sender, RoutedEventArgs e) => StorageRequested?.Invoke(this, EventArgs.Empty);
    private void ServerTools_Click(object sender, RoutedEventArgs e) => ServerToolsRequested?.Invoke(this, EventArgs.Empty);
    private void LocalServer_Click(object sender, RoutedEventArgs e) => LocalServerRequested?.Invoke(this, EventArgs.Empty);
    private void About_Click(object sender, RoutedEventArgs e) => AboutRequested?.Invoke(this, EventArgs.Empty);
}
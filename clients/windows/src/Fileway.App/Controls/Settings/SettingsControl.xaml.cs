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
    public event EventHandler? CacheGalleryRequested;
    public event EventHandler? ServerToolsRequested;
    public event EventHandler? ServerSettingsRequested;
    public event EventHandler? ServerUsersRequested;
    public event EventHandler? ServerSharesRequested;
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

    public void SetServerManagement(bool canConfigure, bool canManageUsers, bool canManageShares)
    {
        bool canAdminister = canConfigure || canManageUsers;
        ServerManagementCard.Visibility = canAdminister || canManageShares ? Visibility.Visible : Visibility.Collapsed;
        ServerSettingsButton.Visibility = canConfigure ? Visibility.Visible : Visibility.Collapsed;
        ServerUsersButton.Visibility = canManageUsers ? Visibility.Visible : Visibility.Collapsed;
        ServerSharesButton.Visibility = canManageShares ? Visibility.Visible : Visibility.Collapsed;
        ServerManagementCaption.Text = canAdminister
            ? "配置当前服务器的安全策略、用户权限、路径规则与允许命令。"
            : "查看或撤销当前账户创建的分享链接。";
    }

    private void Cards_SizeChanged(object sender, SizeChangedEventArgs args)
    {
        if (SecondarySettings is null || SecondarySettingsColumn is null) return;
        // Leave enough width for the three server actions and the labels at
        // the same time. An overlay sidebar can leave the whole narrow window
        // available, so select columns by usable card width.
        bool wide = args.NewSize.Width >= 820;
        int column = wide ? 1 : 0;
        if (Grid.GetColumn(SecondarySettings) == column) return;
        SecondarySettingsColumn.Width = wide ? new GridLength(1, GridUnitType.Star) : new GridLength(0);
        CardsHost.ColumnSpacing = wide ? 12 : 0;
        CardsHost.RowSpacing = wide ? 0 : 12;
        Grid.SetColumn(SecondarySettings, column);
        Grid.SetRow(SecondarySettings, wide ? 0 : 1);
    }

    private void Close_Click(object sender, RoutedEventArgs e) => CloseRequested?.Invoke(this, EventArgs.Empty);
    private void Appearance_Click(object sender, RoutedEventArgs e) => AppearanceRequested?.Invoke(this, EventArgs.Empty);
    private void OpenWith_Click(object sender, RoutedEventArgs e) => OpenWithRequested?.Invoke(this, EventArgs.Empty);
    private void Storage_Click(object sender, RoutedEventArgs e) => StorageRequested?.Invoke(this, EventArgs.Empty);
    private void CacheGallery_Click(object sender, RoutedEventArgs e) => CacheGalleryRequested?.Invoke(this, EventArgs.Empty);
    private void ServerTools_Click(object sender, RoutedEventArgs e) => ServerToolsRequested?.Invoke(this, EventArgs.Empty);
    private void ServerSettings_Click(object sender, RoutedEventArgs e) => ServerSettingsRequested?.Invoke(this, EventArgs.Empty);
    private void ServerUsers_Click(object sender, RoutedEventArgs e) => ServerUsersRequested?.Invoke(this, EventArgs.Empty);
    private void ServerShares_Click(object sender, RoutedEventArgs e) => ServerSharesRequested?.Invoke(this, EventArgs.Empty);
    private void LocalServer_Click(object sender, RoutedEventArgs e) => LocalServerRequested?.Invoke(this, EventArgs.Empty);
    private void About_Click(object sender, RoutedEventArgs e) => AboutRequested?.Invoke(this, EventArgs.Empty);
}

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
    internal event EventHandler<PopupMaterial>? PopupMaterialRequested;

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
        PopupMaterialBox.SelectionChanged += (_, _) =>
        {
            if (_loading || PopupMaterialBox.SelectedIndex < 0) return;
            PopupMaterialError.IsOpen = false;
            PopupMaterialRequested?.Invoke(this, (PopupMaterial)PopupMaterialBox.SelectedIndex);
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

    internal void Initialize(ElementTheme theme, bool closeToTray, PopupMaterial popupMaterial)
    {
        _loading = true;
        ThemeBox.SelectedIndex = theme switch { ElementTheme.Light => 1, ElementTheme.Dark => 2, _ => 0 };
        MaterialBox.SelectedIndex = PresentationPreferences.ShellMaterial;
        ToneBox.SelectedIndex = PresentationPreferences.ShellTone;
        ToneBox.IsEnabled = MaterialBox.SelectedIndex == 0;
        PopupMaterialBox.SelectedIndex = (int)popupMaterial;
        PopupMaterialError.IsOpen = false;
        TraySwitch.IsOn = closeToTray;
        ToastSlider.Value = PresentationPreferences.ToastSeconds;
        ControlsSlider.Value = PresentationPreferences.ControlsHideSeconds;
        ToastValue.Text = $"{PresentationPreferences.ToastSeconds} 秒";
        ControlsValue.Text = $"{PresentationPreferences.ControlsHideSeconds} 秒";
        RememberPlayerSwitch.IsOn = PresentationPreferences.RememberPlayerSize;
        _loading = false;
    }

    internal void ShowPopupMaterialSaveError(PopupMaterial currentMaterial)
    {
        var wasLoading = _loading;
        _loading = true;
        try { PopupMaterialBox.SelectedIndex = (int)currentMaterial; }
        finally { _loading = wasLoading; }
        PopupMaterialError.IsOpen = true;
    }

    public void SetServerManagement(bool canConfigure, bool canManageUsers, bool canManageShares)
    {
        bool canAdminister = canConfigure || canManageUsers;
        ServerManagementCard.Visibility = canAdminister || canManageShares ? Visibility.Visible : Visibility.Collapsed;
        ServerSettingsButton.Visibility = canConfigure ? Visibility.Visible : Visibility.Collapsed;
        ServerUsersButton.Visibility = canManageUsers ? Visibility.Visible : Visibility.Collapsed;
        ServerSharesButton.Visibility = canManageShares ? Visibility.Visible : Visibility.Collapsed;
        ServerManagementCaption.Text = (canConfigure, canManageUsers) switch
        {
            (true, true) => "管理服务器的全局安全策略、用户权限和规则。",
            (true, false) => "配置服务器的全局安全策略。",
            (false, true) => "管理用户、权限和规则。",
            _ => "查看或撤销当前账户创建的分享链接。",
        };
    }

    internal bool FocusServerUsers() => ServerUsersButton.Visibility == Visibility.Visible && ServerUsersButton.Focus(FocusState.Keyboard);

    private void ServerManagementLayout_SizeChanged(object sender, SizeChangedEventArgs args)
    {
        if (ServerManagementLabels is null || ServerManagementActions is null || ServerManagementLayout is null) return;
        const double minimumLabelWidth = 126;
        const double columnGap = 12;
        bool stacked = ServerManagementLayout.ActualWidth < minimumLabelWidth + columnGap + ServerManagementActions.ActualWidth;
        int row = stacked ? 1 : 0;
        if (Grid.GetRow(ServerManagementActions) == row) return;
        ServerManagementLayout.RowSpacing = stacked ? 8 : 0;
        ServerManagementLayout.ColumnSpacing = stacked ? 0 : columnGap;
        Grid.SetColumnSpan(ServerManagementLabels, stacked ? 2 : 1);
        Grid.SetRow(ServerManagementActions, row);
        Grid.SetColumn(ServerManagementActions, stacked ? 0 : 1);
        Grid.SetColumnSpan(ServerManagementActions, stacked ? 2 : 1);
    }

    private void Cards_SizeChanged(object sender, SizeChangedEventArgs args)
    {
        if (SecondarySettings is null || SecondarySettingsColumn is null) return;
        // Use the available content width, including when the sidebar is overlaid.
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

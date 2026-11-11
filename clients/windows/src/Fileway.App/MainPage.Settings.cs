using Microsoft.UI.Xaml;

namespace Fileway.App;

public sealed partial class MainPage
{
    private void InitializeSettings()
    {
        SettingsPane.CloseRequested += (_, _) => ReturnFromSettings();
        SettingsPane.AppearanceRequested += (_, _) => PresentationSettings_Click(this, new RoutedEventArgs());
        SettingsPane.OpenWithRequested += (_, _) => OpenWithSettings_Click(this, new RoutedEventArgs());
        SettingsPane.StorageRequested += (_, _) => StorageSettings_Click(this, new RoutedEventArgs());
        SettingsPane.CacheGalleryRequested += (_, _) => CacheGallery_Click(this, new RoutedEventArgs());
        SettingsPane.ServerToolsRequested += (_, _) => StorageTools_Click(this, new RoutedEventArgs());
        SettingsPane.ServerSettingsRequested += (_, _) => ServerSettings_Click(this, new RoutedEventArgs());
        SettingsPane.ServerUsersRequested += (_, _) => OpenStorageTools(showUsers: true);
        SettingsPane.ServerSharesRequested += (_, _) => ShareManager_Click(this, new RoutedEventArgs());
        SettingsPane.LocalServerRequested += (_, _) => LocalServer_Click(this, new RoutedEventArgs());
        SettingsPane.AboutRequested += (_, _) => AboutApplication_Click(this, new RoutedEventArgs());
        SettingsPane.ThemeRequested += (_, theme) =>
        {
            _applicationTheme = theme;
            ApplyTheme();
            UiPreferences.SaveTheme(theme);
        };
        SettingsPane.TrayRequested += (_, on) =>
        {
            try { ((App)Application.Current).SetCloseToTray(on); }
            catch { SetNotice("设置未保存", "无法保存关闭偏好，请检查当前用户的设置权限。", Microsoft.UI.Xaml.Controls.InfoBarSeverity.Error); }
        };
        SettingsPane.PopupMaterialRequested += (_, material) =>
        {
            var app = (App)Application.Current;
            try { app.SetPopupMaterial(material); }
            catch { SettingsPane.ShowPopupMaterialSaveError(app.PopupMaterial); }
        };
    }

    private void Settings_Click(object sender, RoutedEventArgs e)
    {
        if (_disposed || _connecting) return;
        CloseSidebarOverlay();
        var app = (App)Application.Current;
        SettingsPane.Initialize(_applicationTheme, app.CloseToTray, app.PopupMaterial);
        ShowPane(MainPane.Settings);
    }

    private void ReturnFromSettings()
    {
        if (_disposed) return;
        ShowPane(_session is null || _directory is null ? MainPane.Connection : MainPane.Browser);
        if (_currentPane == MainPane.Browser) ActiveFiles.Focus(FocusState.Keyboard);
    }
}

using Fileway.App.Branding;
using Microsoft.UI.Xaml.Controls;
using Microsoft.Win32;
using WinUIEx;

namespace Fileway.App.Windowing;

/// <summary>Process-owned tray lifetime; hiding a window does not retire its account or transfers.</summary>
internal sealed class ApplicationTray : IDisposable
{
    private readonly TrayIcon _icon;
    private readonly Action _show;
    private readonly Action _exit;
    private bool _disposed;
    private const string SettingsKey = @"Software\Fileway\Preview";

    internal ApplicationTray(Action show, Action exit)
    {
        _show = show;
        _exit = exit;
        _icon = new TrayIcon(1, Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"), AppBranding.DisplayName);
        _icon.Selected += Selected;
        _icon.ContextMenu += ContextMenu;
        _icon.IsVisible = true;
    }

    internal bool CloseToTray
    {
        get
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            try { return Registry.GetValue(@"HKEY_CURRENT_USER\" + SettingsKey, "CloseToTray", 1) is not int value || value != 0; }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or System.Security.SecurityException)
            { return true; }
        }
        set
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            using var key = Registry.CurrentUser.CreateSubKey(SettingsKey);
            key.SetValue("CloseToTray", value ? 1 : 0, RegistryValueKind.DWord);
        }
    }

    private void Selected(object? sender, TrayIconEventArgs args)
    {
        args.Handled = true;
        _icon.CloseFlyout();
        _show();
    }

    private void ContextMenu(object? sender, TrayIconEventArgs args)
    {
        if (_disposed) return;
        var menu = new MenuFlyout();
        var open = new MenuFlyoutItem { Text = "打开" + AppBranding.DisplayName };
        open.Click += (_, _) => { _icon.CloseFlyout(); _show(); };
        var closeBehavior = new ToggleMenuFlyoutItem { Text = "关闭窗口时彻底退出", IsChecked = !CloseToTray };
        closeBehavior.Click += (_, _) =>
        {
            try { CloseToTray = !closeBehavior.IsChecked; }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or System.Security.SecurityException)
            {
                closeBehavior.IsChecked = !CloseToTray;
                _show();
            }
        };
        var exit = new MenuFlyoutItem { Text = "退出" + AppBranding.DisplayName };
        exit.Click += (_, _) => { _icon.CloseFlyout(); _exit(); };
        menu.Items.Add(open);
        menu.Items.Add(closeBehavior);
        menu.Items.Add(new MenuFlyoutSeparator());
        menu.Items.Add(exit);
        args.Flyout = menu;
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _icon.Selected -= Selected;
        _icon.ContextMenu -= ContextMenu;
        _icon.Dispose();
    }
}

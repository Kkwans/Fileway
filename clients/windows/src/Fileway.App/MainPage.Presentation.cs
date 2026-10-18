using Fileway.App.MediaSurfaces;
using Fileway.Core.Contracts;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;

namespace Fileway.App;

public sealed partial class MainPage
{
    private readonly HashSet<BrowserFileTypeFilter> _typeFilters = [];
    private readonly Dictionary<(AccountKey Account, string WirePath), BrowserFileTypeFilter[]> _directoryTypeFilters = [];
    private bool _typeFiltersEdited;
    private PlayerSettingsWindow? _detailsPopup;
    private PlayerSettingsWindow? _choicePopup;
    private StackPanel? _typeFilterOptions;
    private readonly Dictionary<InfoBar, (DispatcherQueueTimer Timer, string Key)> _toasts = [];

    private void InitializePresentation()
    {
        BrowserBody.Children.Remove(DetailsPanel);
        DetailsPanel.Width = 520;
        DetailsPanel.Height = 520;
        DetailsPanel.MaxHeight = 560;
        DetailsPanel.Padding = new Thickness(20);
        DetailsPanel.Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent);
        DetailsPanel.BorderThickness = new Thickness(0);
        Notice.IsOpen = false;
        LoginNotice.IsClosable = true;
        AttachChoices(ViewOptionsButton);
        _typeFilterOptions = (TypeFilterBox.Flyout as Flyout)?.Content as StackPanel;
        AttachChoices(TypeFilterBox);
    }

    private void AttachChoices(DropDownButton button)
    {
        if (button.Flyout is not Flyout { Content: FrameworkElement content } flyout) return;
        flyout.Content = null; button.Flyout = null;
        content.Width = double.NaN; content.Margin = new Thickness(0);
        var frame = new Border { Width = 208, Padding = new Thickness(8), Child = content };
        button.Click += (_, _) =>
        {
            _choicePopup?.Dispose();
            if (XamlRoot is null) return;
            var owner = Microsoft.UI.Win32Interop.GetWindowFromWindowId(XamlRoot.ContentIslandEnvironment.AppWindowId);
            var popup = new PlayerSettingsWindow(owner, frame, null, () => _choicePopup = null, theme: ActualTheme, wrapContent: false);
            popup.Title = button == TypeFilterBox ? "筛选文件类型" : "文件布局";
            _choicePopup = popup; popup.ShowAt(button, preferBelow: true);
        };
    }

    private void ConfigurePopupMaterial(FlyoutBase flyout)
    {
        if (!Microsoft.UI.Composition.SystemBackdrops.DesktopAcrylicController.IsSupported() ||
            !new Windows.UI.ViewManagement.UISettings().AdvancedEffectsEnabled || new Windows.UI.ViewManagement.AccessibilitySettings().HighContrast) return;
        var style = new Style(typeof(MenuFlyoutPresenter));
        var tint = ActualTheme == ElementTheme.Dark ? Windows.UI.Color.FromArgb(255, 40, 43, 48) : Windows.UI.Color.FromArgb(255, 249, 250, 252);
        style.Setters.Add(new Setter(Control.BackgroundProperty, new AcrylicBrush { TintOpacity = 0.8, TintColor = tint, FallbackColor = tint }));
        style.Setters.Add(new Setter(Control.CornerRadiusProperty, new CornerRadius(8)));
        style.Setters.Add(new Setter(Control.PaddingProperty, new Thickness(4)));
        if (flyout is MenuFlyout menu) menu.MenuFlyoutPresenterStyle = style;
        else if (flyout is Flyout panel)
        {
            var presenter = new Style(typeof(FlyoutPresenter));
            presenter.Setters.Add(new Setter(Control.BackgroundProperty, new SolidColorBrush(Microsoft.UI.Colors.Transparent)));
            presenter.Setters.Add(new Setter(Control.PaddingProperty, new Thickness(8)));
            presenter.Setters.Add(new Setter(Control.CornerRadiusProperty, new CornerRadius(8)));
            panel.FlyoutPresenterStyle = presenter;
        }
    }

    private void ShowToast(InfoBar bar, string key, bool repeat = false)
    {
        if (_disposed) return;
        if (!_toasts.TryGetValue(bar, out var state))
        {
            var timer = DispatcherQueue.CreateTimer();
            timer.IsRepeating = false;
            timer.Tick += (_, _) => { timer.Stop(); bar.IsOpen = false; };
            state = (timer, "");
            _toasts.Add(bar, state);
            bar.Closed += (_, _) => timer.Stop();
        }
        // Transfer snapshots refresh their bytes frequently. A dismissed toast
        // must not reappear on every tick; only a new task/stage opens it again.
        if (!repeat && state.Key == key) return;
        bar.IsClosable = true;
        bar.IsOpen = true;
        state.Timer.Stop();
        state.Timer.Interval = TimeSpan.FromSeconds(PresentationPreferences.ToastSeconds);
        state.Timer.Start();
        _toasts[bar] = (state.Timer, key);
    }

    private void DisposePresentation()
    {
        _detailsPopup?.Dispose(); _detailsPopup = null;
        _choicePopup?.Dispose(); _choicePopup = null;
        foreach (var state in _toasts.Values) state.Timer.Stop();
        _toasts.Clear();
    }

    private void TypeFilter_Click(object sender, RoutedEventArgs args)
    {
        if (_applyingPreferences || sender is not ToggleButton { Tag: string tag } item ||
            !Enum.TryParse<BrowserFileTypeFilter>(tag, out var value)) return;
        _typeFiltersEdited = true;
        if (value == BrowserFileTypeFilter.All) _typeFilters.Clear();
        else if (item.IsChecked == true) _typeFilters.Add(value);
        else _typeFilters.Remove(value);
        ++_preferenceRevision;
        _browserPreferences = _browserPreferences with { TypeFilter = _typeFilters.Order().FirstOrDefault() };
        if (_directory is { } directory)
        {
            if (_directoryTypeFilters.Count >= 128) _directoryTypeFilters.Remove(_directoryTypeFilters.Keys.First());
            _directoryTypeFilters[(directory.Account, directory.WirePath)] = _typeFilters.ToArray();
        }
        UpdateTypeFilterMenu(); ApplyFilter(); QueuePreferenceSave();
    }

    private void RestoreDirectoryTypeFilters(RemoteResourceRef directory)
    {
        _typeFiltersEdited = _directoryTypeFilters.TryGetValue((directory.Account, directory.WirePath), out var filters);
        _typeFilters.Clear();
        if (filters is not null) _typeFilters.UnionWith(filters);
    }

    private void UpdateTypeFilterMenu()
    {
        if (!_typeFiltersEdited)
        {
            _typeFilters.Clear();
            if (_browserPreferences.TypeFilter != BrowserFileTypeFilter.All) _typeFilters.Add(_browserPreferences.TypeFilter);
        }
        if (_typeFilterOptions is not { } panel) return;
        var labels = new List<string>();
        foreach (var item in panel.Children.OfType<ToggleButton>())
        {
            if (item.Tag is not string tag || !Enum.TryParse<BrowserFileTypeFilter>(tag, out var value)) continue;
            item.IsChecked = value == BrowserFileTypeFilter.All ? _typeFilters.Count == 0 : _typeFilters.Contains(value);
            if (value != BrowserFileTypeFilter.All && item.IsChecked == true) labels.Add(item.Content?.ToString() ?? "");
        }
        TypeFilterBox.Content = labels.Count switch { 0 => "全部类型", 1 => labels[0], _ => $"{labels.Count} 种类型" };
        ToolTipService.SetToolTip(TypeFilterBox, labels.Count == 0 ? "显示全部类型，可多选" : string.Join("、", labels));
    }

    private bool MatchesTypeFilters(RemoteFile file) => _typeFilters.Count == 0 || _typeFilters.Any(type => FileRow.MatchesType(file, type));

    private void PresentationSettings_Click(object sender, RoutedEventArgs args)
    {
        var panel = new StackPanel { Spacing = 20, Width = 300, Margin = new Thickness(16) };
        var toast = new Slider { Header = "提示自动消失（秒）", Minimum = 2, Maximum = 30, StepFrequency = 1, Value = PresentationPreferences.ToastSeconds };
        var controls = new Slider { Header = "播放控件自动隐藏（秒）", Minimum = 2, Maximum = 30, StepFrequency = 1, Value = PresentationPreferences.ControlsHideSeconds };
        var remember = new ToggleSwitch { Header = "再次打开视频", OnContent = "保留上次窗口大小", OffContent = "按视频比例自动调整", IsOn = PresentationPreferences.RememberPlayerSize };
        remember.Toggled += (_, _) => Save(() => PresentationPreferences.RememberPlayerSize = remember.IsOn);
        toast.ValueChanged += (_, _) => Save(() => PresentationPreferences.ToastSeconds = (int)toast.Value);
        controls.ValueChanged += (_, _) => Save(() => PresentationPreferences.ControlsHideSeconds = (int)controls.Value);
        panel.Children.Add(toast); panel.Children.Add(controls);
        panel.Children.Add(remember);
        _ = ShowBrowserDialogAsync(new ContentDialog { Title = "交互偏好", CloseButtonText = "完成", Content = panel });
        void Save(Action action) { try { action(); } catch (Exception) { SetNotice("偏好未保存", "请检查当前用户的设置权限。", InfoBarSeverity.Warning); } }
    }
}

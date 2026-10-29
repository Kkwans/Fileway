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
    private StackPanel? _typeFilterOptions;
    private StackPanel? _sortOptions;
    private readonly Dictionary<InfoBar, (DispatcherQueueTimer Timer, string Key)> _toasts = [];

    private void InitializePresentation()
    {
        BrowserBody.Children.Remove(DetailsPanel);
        DetailsPanel.Width = 420;
        DetailsPanel.Height = 480;
        DetailsPanel.MaxHeight = 560;
        DetailsPanel.Padding = new Thickness(20, 16, 20, 18);
        DetailsPanel.BorderThickness = new Thickness(0);
        Notice.IsOpen = false;
        LoginNotice.IsClosable = true;
        Controls.FloatingNotice.Attach(Notice);
        Controls.FloatingNotice.Attach(LoginNotice);
        Controls.FloatingNotice.Attach(ReceiptWarning);
        Controls.FloatingNotice.Attach(UploadNotice);
        Controls.FloatingNotice.Attach(DownloadNotice);
        AttachChoices(ViewOptionsButton);
        _typeFilterOptions = (TypeFilterBox.Flyout as Flyout)?.Content as StackPanel;
        AttachChoices(TypeFilterBox);
        _sortOptions = (SortBox.Flyout as Flyout)?.Content as StackPanel;
        AttachChoices(SortBox);
        UpdateSortMenu();
    }

    private void AttachChoices(DropDownButton button)
    {
        if (button.Flyout is not Flyout { Content: FrameworkElement content } flyout) return;
        content.Margin = new Thickness(0); content.Width = 136; content.MinWidth = 0;
        flyout.Placement = FlyoutPlacementMode.BottomEdgeAlignedLeft;
        flyout.Opening += (_, _) =>
        {
            Controls.SurfaceStyles.Apply(flyout, ActualTheme);
            if (content is Panel panel)
                foreach (var choice in panel.Children.OfType<ToggleButton>()) Controls.SurfaceStyles.Soften(choice, "ToggleButton", ActualTheme);
        };
    }

    private void ConfigurePopupMaterial(FlyoutBase flyout) => Controls.SurfaceStyles.Apply(flyout, ActualTheme);
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
        var material = new Controls.ChoiceBox { Header = "侧栏与顶栏背景", HorizontalAlignment = HorizontalAlignment.Stretch };
        foreach (var label in new[] { "中性底色", "实时模糊", "壁纸取色" }) material.Items.Add(label);
        material.SelectedIndex = PresentationPreferences.ShellMaterial;
        var tone = new Controls.ChoiceBox { Header = "中性底色配色", HorizontalAlignment = HorizontalAlignment.Stretch };
        foreach (var label in new[] { "雾灰", "暖灰", "冷灰", "自定义取色" }) tone.Items.Add(label);
        tone.SelectedIndex = PresentationPreferences.ShellTone; tone.IsEnabled = material.SelectedIndex == 0;
        var color = new ColorPicker { Color = PresentationPreferences.ShellTint, IsAlphaEnabled = false, IsHexInputVisible = true, IsMoreButtonVisible = false, IsColorSliderVisible = true, Width = 268, Visibility = material.SelectedIndex == 0 && tone.SelectedIndex == 3 ? Visibility.Visible : Visibility.Collapsed };
        color.ColorChanged += (_, args) => Save(() => PresentationPreferences.ShellTint = args.NewColor);
        material.SelectionChanged += (_, _) => { tone.IsEnabled = material.SelectedIndex == 0; color.Visibility = material.SelectedIndex == 0 && tone.SelectedIndex == 3 ? Visibility.Visible : Visibility.Collapsed; Save(() => PresentationPreferences.ShellMaterial = material.SelectedIndex); };
        tone.SelectionChanged += (_, _) => { color.Visibility = material.SelectedIndex == 0 && tone.SelectedIndex == 3 ? Visibility.Visible : Visibility.Collapsed; Save(() => PresentationPreferences.ShellTone = tone.SelectedIndex); };
        remember.Toggled += (_, _) => Save(() => PresentationPreferences.RememberPlayerSize = remember.IsOn);
        toast.ValueChanged += (_, _) => Save(() => PresentationPreferences.ToastSeconds = (int)toast.Value);
        controls.ValueChanged += (_, _) => Save(() => PresentationPreferences.ControlsHideSeconds = (int)controls.Value);
        panel.Children.Add(toast); panel.Children.Add(controls);
        panel.Children.Add(remember);
        panel.Children.Add(material); panel.Children.Add(tone);
        panel.Children.Add(color);
        panel.Children.Add(new TextBlock { Text = "自定义取色会自动生成可读的浅色与深色底色。", FontSize = 12, TextWrapping = TextWrapping.Wrap, Opacity = 0.7 });
        _ = ShowBrowserDialogAsync(new ContentDialog { Title = "交互与外观", CloseButtonText = "完成", Content = new ScrollViewer { Content = panel, MaxHeight = 520, HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled } });
        void Save(Action action) { try { action(); } catch (Exception) { SetNotice("偏好未保存", "请检查当前用户的设置权限。", InfoBarSeverity.Warning); } }
    }
}

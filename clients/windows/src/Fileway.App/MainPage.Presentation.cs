using Fileway.App.MediaSurfaces;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;

namespace Fileway.App;

public sealed partial class MainPage
{
    private const double CompactToastTopInset = 12;
    private const double BrowserToastContentGap = 6;
    private readonly HashSet<BrowserFileTypeFilter> _typeFilters = [];
    private readonly Dictionary<(AccountKey Account, string WirePath), BrowserFileTypeFilter[]> _directoryTypeFilters = [];
    private readonly Dictionary<InfoBar, (long Callback, int StatusRow, TranslateTransform Offset)> _noticeLayouts = [];
    private long? _workspaceToastVisibilityCallback;
    private bool _trackingBrowserToastLayout;
    private bool _typeFiltersEdited;
    private PlayerSettingsWindow? _detailsPopup;
    private StackPanel? _typeFilterOptions;
    private StackPanel? _sortOptions;

    private void InitializePresentation()
    {
        BrowserBody.Children.Remove(DetailsPanel);
        DetailsPanel.Width = 420;
        DetailsPanel.Height = 480;
        DetailsPanel.MaxHeight = 560;
        DetailsPanel.Padding = new Thickness(20, 16, 20, 18);
        DetailsPanel.BorderThickness = new Thickness(0);
        Notice.IsOpen = false;
        Controls.FloatingNotice.Attach(LoginNotice, automatic: false);
        foreach (var bar in FeedbackLayout.Children.OfType<InfoBar>())
        {
            Controls.FloatingNotice.Attach(bar, automatic: false);
            var offset = new TranslateTransform();
            bar.RenderTransform = offset;
            var callback = bar.RegisterPropertyChangedCallback(InfoBar.IsOpenProperty, (_, _) =>
            {
                bar.Visibility = bar.IsOpen ? Visibility.Visible : Visibility.Collapsed;
                UpdateCompactToastAnchors();
            });
            _noticeLayouts.Add(bar, (callback, Grid.GetRow(bar), offset));
        }
        _workspaceToastVisibilityCallback = Workspace.RegisterPropertyChangedCallback(UIElement.VisibilityProperty,
            (_, _) => UpdateCompactToastAnchors());
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
        Controls.SurfaceStyles.Apply(flyout);
        flyout.Opening += (_, _) =>
        {
            if (content is Panel panel)
                foreach (var choice in panel.Children.OfType<ToggleButton>()) Controls.SurfaceStyles.Soften(choice, "ToggleButton", ActualTheme);
        };
    }

    private static void ConfigurePopupMaterial(FlyoutBase flyout) => Controls.SurfaceStyles.Attach(flyout);

    private void ShowToast(InfoBar bar, string key, bool repeat = false)
    {
        if (_disposed) return;
        var hasLayout = _noticeLayouts.TryGetValue(bar, out var layout);
        var compact = hasLayout && !string.IsNullOrWhiteSpace(bar.Title) &&
            string.IsNullOrWhiteSpace(bar.Message) &&
            bar.Severity is InfoBarSeverity.Success or InfoBarSeverity.Informational or InfoBarSeverity.Warning;
        if (compact && _noticeLayouts.Keys.Any(other =>
                other != bar && other.IsOpen && Controls.FloatingNotice.IsCompact(other) &&
                Controls.FloatingNotice.IsInteracting(other)))
        {
            bar.IsOpen = false;
            return;
        }
        if (hasLayout)
        {
            Grid.SetRow(bar, compact ? 0 : layout.StatusRow);
            Canvas.SetZIndex(bar, compact ? 10 : 0);
            bar.HorizontalAlignment = compact ? HorizontalAlignment.Center : HorizontalAlignment.Left;
            bar.VerticalAlignment = compact ? VerticalAlignment.Top : VerticalAlignment.Stretch;
            bar.Margin = compact ? new Thickness(12, CompactToastTopInset, 12, 0) : new Thickness(16, 0, 16, 8);
            bar.MaxWidth = compact ? 400 : 580;
        }
        // Transfer snapshots refresh their bytes frequently. A dismissed toast
        // must not reappear on every tick; only a new task/stage opens it again.
        var shown = Controls.FloatingNotice.Show(bar, key, repeat, compact);
        if (hasLayout) UpdateCompactToastAnchors();
        if (!shown || !compact) return;
        foreach (var other in _noticeLayouts.Keys)
            if (other != bar && other.IsOpen && Controls.FloatingNotice.IsCompact(other))
                other.IsOpen = false;
    }

    private void BrowserToastLayoutUpdated(object? sender, object args) => UpdateCompactToastAnchors();

    private void UpdateCompactToastAnchors()
    {
        if (_disposed) return;
        var trackLayout = Workspace.Visibility == Visibility.Visible &&
            _noticeLayouts.Keys.Any(bar => bar.IsOpen && Controls.FloatingNotice.IsCompact(bar));
        if (trackLayout != _trackingBrowserToastLayout)
        {
            if (trackLayout) BrowserBody.LayoutUpdated += BrowserToastLayoutUpdated;
            else BrowserBody.LayoutUpdated -= BrowserToastLayoutUpdated;
            _trackingBrowserToastLayout = trackLayout;
        }

        // Apply the browser offset after layout so the toast cannot resize the work area.
        var browserOffset = 0.0;
        if (trackLayout)
        {
            var bodyTop = BrowserCommands.Height;
            if (BrowserBody.IsLoaded && BrowserBody.ActualHeight > 0 &&
                BrowserBody.XamlRoot is { } root && root == FeedbackLayout.XamlRoot)
                bodyTop = Math.Max(bodyTop, BrowserBody.TransformToVisual(FeedbackLayout).TransformPoint(new(0, 0)).Y);
            browserOffset = bodyTop + BrowserToastContentGap - CompactToastTopInset;
        }
        foreach (var (bar, layout) in _noticeLayouts)
        {
            var offset = trackLayout && bar.IsOpen && Controls.FloatingNotice.IsCompact(bar) ? browserOffset : 0;
            if (layout.Offset.Y != offset) layout.Offset.Y = offset;
        }
    }

    private static void ResetToast(InfoBar bar) => Controls.FloatingNotice.Reset(bar);

    private void DisposePresentation()
    {
        _detailsPopup?.Dispose(); _detailsPopup = null;

        if (_workspaceToastVisibilityCallback is { } callback)
            Workspace.UnregisterPropertyChangedCallback(UIElement.VisibilityProperty, callback);
        _workspaceToastVisibilityCallback = null;
        if (_trackingBrowserToastLayout) BrowserBody.LayoutUpdated -= BrowserToastLayoutUpdated;
        _trackingBrowserToastLayout = false;
        Controls.FloatingNotice.Detach(LoginNotice);
        foreach (var (bar, layout) in _noticeLayouts)
        {
            bar.UnregisterPropertyChangedCallback(InfoBar.IsOpenProperty, layout.Callback);
            Controls.FloatingNotice.Detach(bar);
        }
        _noticeLayouts.Clear();
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
        var panel = new StackPanel { Spacing = 16, Width = 480, Margin = new Thickness(16) };
        TextBlock Heading(string text) => new()
        {
            Text = text,
            FontSize = 13,
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
        };
        var toast = new Slider { Minimum = 2, Maximum = 30, StepFrequency = 1, Value = PresentationPreferences.ToastSeconds };
        var controls = new Slider { Minimum = 2, Maximum = 30, StepFrequency = 1, Value = PresentationPreferences.ControlsHideSeconds };
        StackPanel SliderField(string label, Slider slider)
        {
            var caption = (Style)Application.Current.Resources["FwCaptionTextStyle"];
            var value = new TextBlock
            {
                Text = $"{(int)slider.Value} 秒",
                Style = caption,
                HorizontalAlignment = HorizontalAlignment.Right,
            };
            slider.ValueChanged += (_, args) => value.Text = $"{(int)args.NewValue} 秒";
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(slider, label);
            var header = new Grid();
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            header.Children.Add(new TextBlock { Text = label, Style = caption });
            Grid.SetColumn(value, 1); header.Children.Add(value);
            var field = new StackPanel { Spacing = 4 };
            field.Children.Add(header); field.Children.Add(slider);
            return field;
        }
        var remember = new ToggleSwitch { Header = "再次打开视频", OnContent = "保留上次窗口大小", OffContent = "按视频比例自动调整", IsOn = PresentationPreferences.RememberPlayerSize };
        var material = new Controls.ChoiceBox { Header = "侧栏与顶栏背景", HorizontalAlignment = HorizontalAlignment.Stretch };
        foreach (var label in new[] { "中性底色", "实时模糊", "壁纸取色" }) material.Items.Add(label);
        material.SelectedIndex = PresentationPreferences.ShellMaterial;
        var tone = new Controls.ChoiceBox { Header = "中性底色配色", HorizontalAlignment = HorizontalAlignment.Stretch };
        foreach (var label in new[] { "雾灰", "暖灰", "冷灰", "自定义取色" }) tone.Items.Add(label);
        tone.SelectedIndex = PresentationPreferences.ShellTone; tone.IsEnabled = material.SelectedIndex == 0;
        var color = new ColorPicker { Color = PresentationPreferences.ShellTint, IsAlphaEnabled = false, IsHexInputVisible = true, IsMoreButtonVisible = false, IsColorSliderVisible = true, Width = 228, Visibility = material.SelectedIndex == 0 && tone.SelectedIndex == 3 ? Visibility.Visible : Visibility.Collapsed };
        color.ColorChanged += (_, args) => Save(() => PresentationPreferences.ShellTint = args.NewColor);
        material.SelectionChanged += (_, _) => { tone.IsEnabled = material.SelectedIndex == 0; color.Visibility = material.SelectedIndex == 0 && tone.SelectedIndex == 3 ? Visibility.Visible : Visibility.Collapsed; Save(() => PresentationPreferences.ShellMaterial = material.SelectedIndex); };
        tone.SelectionChanged += (_, _) => { color.Visibility = material.SelectedIndex == 0 && tone.SelectedIndex == 3 ? Visibility.Visible : Visibility.Collapsed; Save(() => PresentationPreferences.ShellTone = tone.SelectedIndex); };
        remember.Toggled += (_, _) => Save(() => PresentationPreferences.RememberPlayerSize = remember.IsOn);
        toast.ValueChanged += (_, _) => Save(() => PresentationPreferences.ToastSeconds = (int)toast.Value);
        controls.ValueChanged += (_, _) => Save(() => PresentationPreferences.ControlsHideSeconds = (int)controls.Value);
        var playback = new StackPanel { Spacing = 12 };
        playback.Children.Add(Heading("提示与播放"));
        playback.Children.Add(SliderField("提示自动消失", toast));
        playback.Children.Add(SliderField("播放控件自动隐藏", controls));
        playback.Children.Add(remember);
        var appearance = new StackPanel { Spacing = 12 };
        appearance.Children.Add(Heading("界面背景"));
        appearance.Children.Add(material);
        appearance.Children.Add(tone);
        appearance.Children.Add(color);
        var columns = new Grid { ColumnSpacing = 24 };
        columns.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        columns.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        columns.Children.Add(playback);
        Grid.SetColumn(appearance, 1);
        columns.Children.Add(appearance);
        panel.Children.Add(columns);
        panel.Children.Add(new TextBlock { Text = "自定义取色会自动生成可读的浅色与深色底色。", FontSize = 12, TextWrapping = TextWrapping.Wrap, Opacity = 0.7 });
        panel.Children.Add(new TextBlock { Text = "修改会立即保存。", FontSize = 12, Opacity = 0.7 });
        var dialog = new ContentDialog { Title = "交互与外观", CloseButtonText = "完成", Content = new ScrollViewer { Content = panel, MaxHeight = Controls.DialogOrdering.ContentMaxHeight(this, 520), HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled } };
        Fileway.App.Controls.DialogOrdering.FocusCloseButton(dialog);
        _ = ShowBrowserDialogAsync(dialog);
        void Save(Action action) { try { action(); } catch (Exception) { SetNotice("偏好未保存", "请检查当前用户的设置权限。", InfoBarSeverity.Warning); } }
    }
}

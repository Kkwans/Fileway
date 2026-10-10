// Adapted from Screenbox 6870eb0 PlayerControls, SeekBar and PlaybackSpeedControl.
// GPL-3.0; upstream attribution and modification record: licenses/screenbox-ui.md.
using System.Globalization;
using Fileway.Core.Contracts;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Markup;
using VirtualKey = Windows.System.VirtualKey;
using Windows.UI.ViewManagement;

namespace Fileway.App.Controls.Playback;

public sealed partial class ScreenboxPlayerControls : UserControl, IDisposable
{
    private readonly IPlaybackSession _session;
    private readonly CancellationTokenSource _lifetime = new();
    private readonly DispatcherQueueTimer _hideTimer;
    private readonly UISettings _uiSettings = new();
    private PlaybackSnapshot _snapshot;
    private SubtitlePreferences _subtitlePreferences = new();
    private bool _applying;
    private bool _seeking;
    private bool _keyboardSeeking;
    private long _seekEditGeneration;
    private bool _menuOpen;
    private bool _fullscreen;
    private bool _disposed;
    private Storyboard? _fade;
    private ScreenboxSpeedControl? _speedControl;
    private ScreenboxVolumeControl? _volumeControl;
    private ScreenboxTrackPicker? _trackPicker;
    private FlyoutBase? _activeFlyout;
    private double? _pendingRate;
    private double? _pendingVolume;
    private bool _rateRunning;
    private bool _volumeRunning;
    private double? _lastQueuedSubtitleSafeArea;
    private double? _pendingSubtitleSafeArea;
    private bool _subtitleSafeAreaRunning;
    private Func<SystemBackdrop>? _flyoutBackdropFactory;

    public ScreenboxPlayerControls(IPlaybackSession session)
    {
        _session = session;
        _snapshot = session.Snapshot;
        InitializeComponent();
        _hideTimer = DispatcherQueue.CreateTimer();
        _hideTimer.Interval = TimeSpan.FromSeconds(4);
        _hideTimer.IsRepeating = false;
        _hideTimer.Tick += HideTimer_Tick;
        SeekSlider.AddHandler(PointerPressedEvent, new PointerEventHandler(Seek_PointerPressed), true);
        SeekSlider.AddHandler(PointerReleasedEvent, new PointerEventHandler(Seek_PointerReleased), true);
        SeekSlider.AddHandler(PointerCanceledEvent, new PointerEventHandler(Seek_PointerCanceled), true);
        SeekSlider.PreviewKeyDown += Seek_PreviewKeyDown;
        SeekSlider.LostFocus += Seek_LostFocus;
        Loaded += (_, _) => { VisualStateManager.GoToState(this, ActualWidth < 900 ? "AdaptiveHidden" : "Expanded", false); NotifyActivity(); UpdateSubtitleSafeArea(); NotifyMaterialBoundsChanged(); };
        Unloaded += (_, _) => _hideTimer.Stop();
        SizeChanged += Controls_SizeChanged;
        BottomBar.SizeChanged += BottomBar_SizeChanged;
        ControlsVisibilityChanged += Controls_SafeAreaVisibilityChanged;
        ApplySnapshot(_snapshot);
    }

    public event EventHandler? CloseRequested;
    public event EventHandler? MinimizeRequested;
    public event EventHandler? MaximizeRequested;
    public event EventHandler? ToggleFullscreenRequested;
    public event EventHandler? AddSubtitleRequested;
    public event EventHandler? ControlsVisibilityChanged;
    public event EventHandler? MaterialBoundsChanged;
    public bool ControlsVisible { get; private set; } = true;

    /// <summary>Supplies a fresh native backdrop for each popup. Null restores
    /// the presenter App Acrylic style; toolbar material belongs to the window.</summary>
    public void SetFlyoutBackdropFactory(Func<SystemBackdrop>? factory)
    {
        if (_disposed) return;
        _flyoutBackdropFactory = factory;
        if (_activeFlyout is { } flyout) ApplyFlyoutBackdrop(flyout);
    }

    /// <summary>Pure title space in this control's local DIPs, excluding buttons.
    /// The host converts its native pointer coordinates before testing this rect.</summary>
    public Windows.Foundation.Rect GetTitleBarDragBounds()
    {
        if (_disposed || !IsLoaded || !ControlsVisible || _fullscreen) return default;
        return TitleDragRegion.TransformToVisual(this).TransformBounds(
            new Windows.Foundation.Rect(0, 0, TitleDragRegion.ActualWidth, TitleDragRegion.ActualHeight));
    }

    public Windows.Foundation.Rect GetMaximizeButtonBounds()
    {
        if (_disposed || !IsLoaded || !ControlsVisible || _fullscreen) return default;
        return MaximizeButton.TransformToVisual(this).TransformBounds(
            new Windows.Foundation.Rect(0, 0, MaximizeButton.ActualWidth, MaximizeButton.ActualHeight));
    }

    /// <summary>Visible edge regions in control-local DIPs for the native owner's
    /// material mask. A hidden HUD has no material area.</summary>
    public (double Top, double Bottom) GetMaterialInsets() => ControlsVisible
        ? (TopBar.ActualHeight, BottomBar.ActualHeight) : default;

    public void SetWindowMaximized(bool maximized)
    {
        MaximizeIcon.IconName = maximized ? "SquareMultiple" : "Square";
        AutomationProperties.SetName(MaximizeButton, maximized ? "还原视频窗口" : "最大化视频窗口");
        ToolTipService.SetToolTip(MaximizeButton, maximized ? "还原" : "最大化");
    }

    public void ApplySnapshot(PlaybackSnapshot snapshot)
    {
        if (_disposed) return;
        var wasPlaying = _snapshot.Status == PlaybackStatus.Playing;
        _snapshot = snapshot;
        _applying = true;
        TitleText.Text = snapshot.DisplayName;
        ToolTipService.SetToolTip(TitleText, snapshot.DisplayName);
        PlayIcon.IconName = snapshot.Status is PlaybackStatus.Paused or PlaybackStatus.Ended ? "Play" : "Pause";
        AutomationProperties.SetName(PlayButton, snapshot.Status == PlaybackStatus.Ended ? "重新播放" : snapshot.Status == PlaybackStatus.Paused ? "开始播放" : "暂停播放");
        PlayButton.IsEnabled = snapshot.Status is PlaybackStatus.Playing or PlaybackStatus.Paused or PlaybackStatus.Ended or PlaybackStatus.Buffering;
        SeekSlider.IsEnabled = snapshot.Seekable && snapshot.Duration is { TotalSeconds: > 0 };
        BackTenButton.IsEnabled = snapshot.Seekable;
        ForwardTenButton.IsEnabled = snapshot.Seekable;
        if (!_seeking)
        {
            SeekSlider.Maximum = Math.Max(1, snapshot.Duration?.TotalSeconds ?? 1);
            SeekSlider.Value = Math.Clamp((snapshot.ConfirmedPosition ?? snapshot.Position).TotalSeconds, 0, SeekSlider.Maximum);
        }
        UpdateTime();
        UpdateChapterProgress();
        ChapterButton.Visibility = snapshot.Chapters is { Count: > 0 } ? Visibility.Visible : Visibility.Collapsed;
        var chapter = snapshot.Chapters?.LastOrDefault(item => item.Position <= (snapshot.ConfirmedPosition ?? snapshot.Position));
        ChapterTitle.Text = chapter?.Title ?? "章节";
        ToolTipService.SetToolTip(ChapterButton, chapter?.Title ?? "选择章节");
        SpeedButton.Content = FormatRate(snapshot.PlaybackRate);
        AutomationProperties.SetName(SpeedButton, "播放速度 " + FormatRate(snapshot.PlaybackRate));
        AutomationProperties.SetName(VolumeButton, $"音量 {snapshot.Volume:0}%");
        VolumeIcon.IconName = snapshot.IsMuted ? "SpeakerMute" : snapshot.Volume < 34 ? "Speaker0" : snapshot.Volume < 67 ? "Speaker1" : "Speaker2";
        _speedControl?.ApplyConfirmedRate(snapshot.PlaybackRate, _rateRunning);
        _volumeControl?.ApplyConfirmed(snapshot.Volume, snapshot.IsMuted, _volumeRunning);
        _trackPicker?.ApplyTracks(snapshot.Tracks);
        AudioButton.Visibility = snapshot.Tracks.Any(track => track.Kind == MediaTrackKind.Audio) ? Visibility.Visible : Visibility.Collapsed;
        var hasSubtitles = snapshot.Tracks.Any(track => track.Kind == MediaTrackKind.Subtitle);
        SubtitleButton.Visibility = Visibility.Visible;
        var caps = snapshot.SubtitleCapabilities;
        SubtitleStyleButton.Visibility = hasSubtitles && (caps.CanScale || caps.CanPosition || caps.CanDelay || caps.CanChangeFont) ? Visibility.Visible : Visibility.Collapsed;
        _applying = false;
        if (snapshot.Error is { } error) ShowError(error.Message);
        if (snapshot.Status != PlaybackStatus.Playing) ShowControls();
        else if (!wasPlaying) NotifyActivity();
    }

    public void SetFullscreen(bool fullscreen)
    {
        _fullscreen = fullscreen;
        WindowButtons.Visibility = fullscreen ? Visibility.Collapsed : Visibility.Visible;
        FullscreenIcon.IconName = fullscreen ? "FullScreenMinimize" : "FullScreenMaximize";
        AutomationProperties.SetName(FullscreenButton, fullscreen ? "退出全屏" : "全屏");
    }

    public void NotifyActivity()
    {
        if (_disposed) return;
        ShowControls();
        _hideTimer.Stop();
        if (_snapshot.Status == PlaybackStatus.Playing && !_seeking && !_menuOpen && !Notice.IsOpen) _hideTimer.Start();
    }

    public void ShowControls()
    {
        if (_disposed) return;
        _fade?.Stop();
        Chrome.Opacity = 1;
        Chrome.IsHitTestVisible = true;
        if (!ControlsVisible) { ControlsVisible = true; ControlsVisibilityChanged?.Invoke(this, EventArgs.Empty); }
    }

    private void HideTimer_Tick(DispatcherQueueTimer sender, object args)
    {
        if (_snapshot.Status != PlaybackStatus.Playing || _seeking || _menuOpen || Notice.IsOpen) return;
        if (XamlRoot is not null && FocusManager.GetFocusedElement(XamlRoot) is Control { FocusState: FocusState.Keyboard }) return;
        ControlsVisible = false;
        Chrome.IsHitTestVisible = false;
        ControlsVisibilityChanged?.Invoke(this, EventArgs.Empty);
        var animations = true;
        try { animations = _uiSettings.AnimationsEnabled; } catch (System.Runtime.InteropServices.COMException) { animations = false; }
        if (!animations) { Chrome.Opacity = 0; return; }
        _fade = new Storyboard();
        var animation = new DoubleAnimation { To = 0, Duration = TimeSpan.FromMilliseconds(160), EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } };
        Storyboard.SetTarget(animation, Chrome); Storyboard.SetTargetProperty(animation, "Opacity");
        _fade.Children.Add(animation); _fade.Begin();
    }

    private void Seek_PointerPressed(object sender, PointerRoutedEventArgs args) { if (!SeekSlider.IsEnabled) return; ++_seekEditGeneration; _seeking = true; PreviewText.Visibility = Visibility.Visible; NotifyActivity(); }
    private async void Seek_PointerReleased(object sender, PointerRoutedEventArgs args)
    {
        if (!_seeking) return;
        var target = SeekSlider.Value; ++_seekEditGeneration; _seeking = false; PreviewText.Visibility = Visibility.Collapsed;
        await RunAsync(token => _session.SeekAsync(TimeSpan.FromSeconds(target), token)); NotifyActivity();
    }
    private void Seek_PointerCanceled(object sender, PointerRoutedEventArgs args) { ++_seekEditGeneration; _seeking = false; PreviewText.Visibility = Visibility.Collapsed; ApplySnapshot(_session.Snapshot); NotifyActivity(); }
    private void Seek_ValueChanged(object sender, RangeBaseValueChangedEventArgs args)
    {
        if (_applying) return;
        var target = args.NewValue;
        PreviewText.Text = Clock(TimeSpan.FromSeconds(target));
        TimeText.Text = PreviewText.Text + " / " + (_snapshot.Duration is { } duration ? Clock(duration) : "--:--");
        UpdateChapterProgress();
        var generation = ++_seekEditGeneration;
        // A native UIA range edit has no pointer/key release. Defer until routed input
        // completes so a pointer press can claim the edit before this commit path.
        DispatcherQueue.TryEnqueue(DispatcherQueuePriority.Low, async () =>
        {
            if (!_disposed && !_seeking && generation == _seekEditGeneration)
                await RunAsync(token => _session.SeekAsync(TimeSpan.FromSeconds(target), token));
        });
    }
    private void Seek_PreviewKeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (!SeekSlider.IsEnabled || !IsSeekKey(args.Key)) return;
        _keyboardSeeking = true; _seeking = true; PreviewText.Visibility = Visibility.Visible; NotifyActivity();
    }
    private void Seek_LostFocus(object sender, RoutedEventArgs args)
    {
        if (!_keyboardSeeking) return;
        var focus = XamlRoot is null ? null : FocusManager.GetFocusedElement(XamlRoot) as DependencyObject;
        while (focus is not null) { if (focus == SeekSlider) return; focus = VisualTreeHelper.GetParent(focus); }
        _keyboardSeeking = false; _seeking = false; ++_seekEditGeneration;
        PreviewText.Visibility = Visibility.Collapsed; ApplySnapshot(_session.Snapshot); NotifyActivity();
    }
    private async void Seek_KeyUp(object sender, KeyRoutedEventArgs args)
    {
        if (IsSeekKey(args.Key) && _keyboardSeeking)
        {
            var target = SeekSlider.Value;
            _keyboardSeeking = false; _seeking = false; ++_seekEditGeneration; PreviewText.Visibility = Visibility.Collapsed;
            await RunAsync(token => _session.SeekAsync(TimeSpan.FromSeconds(target), token));
        }
        NotifyActivity();
    }
    private static bool IsSeekKey(VirtualKey key) => key is VirtualKey.Left or VirtualKey.Right or VirtualKey.Home or VirtualKey.End or VirtualKey.PageUp or VirtualKey.PageDown;
    private void UpdateTime()
    {
        var position = _seeking ? TimeSpan.FromSeconds(SeekSlider.Value) : _snapshot.ConfirmedPosition ?? _snapshot.Position;
        TimeText.Text = Clock(position) + " / " + (_snapshot.Duration is { } duration ? Clock(duration) : "--:--");
        AutomationProperties.SetHelpText(SeekSlider, "当前位置 " + TimeText.Text);
    }
    private void UpdateChapterProgress()
    {
        var duration = _snapshot.Duration?.TotalSeconds ?? 0;
        SeekSlider.ApplyTimeline(duration,
            _seeking ? SeekSlider.Value : (_snapshot.ConfirmedPosition ?? _snapshot.Position).TotalSeconds, _snapshot.BufferedUntil, _snapshot.Chapters);
    }

    private void Chapter_Click(object sender, RoutedEventArgs args)
    {
        if (_snapshot.Chapters is not { Count: > 0 } chapters) return;
        var menu = new MenuFlyout();
        var current = chapters.LastOrDefault(chapter => chapter.Position <= (_snapshot.ConfirmedPosition ?? _snapshot.Position));
        foreach (var chapter in chapters)
        {
            var item = new RadioMenuFlyoutItem { Text = Clock(chapter.Position) + "  " + chapter.Title, GroupName = "Chapter", IsChecked = chapter == current, IsEnabled = _snapshot.Seekable };
            item.Click += async (_, _) => await RunAsync(token => _session.SeekAsync(chapter.Position, token));
            menu.Items.Add(item);
        }
        ShowFlyout(menu, ChapterButton);
    }
    private async void Play_Click(object sender, RoutedEventArgs args) => await TogglePlayAsync();
    private async Task TogglePlayAsync() => await RunAsync(async token =>
    {
        var snapshot = _session.Snapshot;
        if (snapshot.Status == PlaybackStatus.Ended) await _session.SeekAsync(TimeSpan.Zero, token);
        await _session.SetPausedAsync(snapshot.Status != PlaybackStatus.Paused && snapshot.Status != PlaybackStatus.Ended, token);
    });
    private async void BackTen_Click(object sender, RoutedEventArgs args) => await SeekRelativeAsync(-10);
    private async void ForwardTen_Click(object sender, RoutedEventArgs args) => await SeekRelativeAsync(10);
    private Task SeekRelativeAsync(double delta) => RunAsync(token => _session.SeekAsync(TimeSpan.FromSeconds(Math.Clamp((_snapshot.ConfirmedPosition ?? _snapshot.Position).TotalSeconds + delta, 0, _snapshot.Duration?.TotalSeconds ?? double.MaxValue)), token));
    private void Close_Click(object sender, RoutedEventArgs args) => CloseRequested?.Invoke(this, EventArgs.Empty);
    private void Minimize_Click(object sender, RoutedEventArgs args) => MinimizeRequested?.Invoke(this, EventArgs.Empty);
    private void Maximize_Click(object sender, RoutedEventArgs args) => MaximizeRequested?.Invoke(this, EventArgs.Empty);
    private void Fullscreen_Click(object sender, RoutedEventArgs args) => ToggleFullscreenRequested?.Invoke(this, EventArgs.Empty);

    private void Audio_Click(object sender, RoutedEventArgs args) => ShowTracks(AudioButton, MediaTrackKind.Audio);
    private void Subtitle_Click(object sender, RoutedEventArgs args) => ShowTracks(SubtitleButton, MediaTrackKind.Subtitle);
    private void More_Click(object sender, RoutedEventArgs args)
    {
        var menu = new MenuFlyout();
        if (_snapshot.Tracks.Any(track => track.Kind == MediaTrackKind.Audio)) Add("音轨", "MusicNote2", () => ShowTracks(MoreButton, MediaTrackKind.Audio));
        Add("字幕", "ClosedCaption", () => ShowTracks(MoreButton, MediaTrackKind.Subtitle));
        if (SubtitleStyleButton.Visibility == Visibility.Visible) Add("字幕样式", "TextFontSize", () => SubtitleStyle_Click(MoreButton, args));
        Add("播放速度 · " + FormatRate(_snapshot.PlaybackRate), "TopSpeed", () => Speed_Click(MoreButton, args));
        if (_snapshot.Chapters is { Count: > 0 }) Add("章节", "BookOpen", () => Chapter_Click(MoreButton, args));
        ShowFlyout(menu, MoreButton);

        void Add(string label, string iconName, Action action)
        {
            if (!ScreenboxVectorIcon.TryFindResource(Resources, iconName + "Geometry", out var iconResource) || iconResource is not string pathString)
            {
                System.Diagnostics.Trace.TraceError($"Menu icon resource failed: {iconName}Geometry; resource={iconResource?.GetType().FullName ?? "missing"}");
                ReportError("图标暂不可用，请重新打开播放窗口");
                return;
            }
            var geometry = (Geometry)XamlBindingHelper.ConvertValue(typeof(Geometry), pathString);
            var item = new MenuFlyoutItem { Text = label, Icon = new PathIcon { Data = geometry, Width = 20, Height = 20 } };
            // Let native MenuFlyout light-dismiss finish before showing its child.
            item.Click += (_, _) => DispatcherQueue.TryEnqueue(DispatcherQueuePriority.Low, () => { if (!_disposed) action(); });
            menu.Items.Add(item);
        }
    }
    private void ShowTracks(FrameworkElement anchor, MediaTrackKind kind)
    {
        _activeFlyout?.Hide();
        Flyout? flyout = null;
        _trackPicker = new ScreenboxTrackPicker(kind, _snapshot.Tracks,
            async id => await RunAsync(token => _session.SelectTrackAsync(kind, id, token)),
            () => { flyout?.Hide(); AddSubtitleRequested?.Invoke(this, EventArgs.Empty); });
        flyout = new Flyout { Content = _trackPicker };
        var trackPicker = _trackPicker;
        flyout.Closed += (_, _) => { if (_trackPicker == trackPicker) _trackPicker = null; };
        ShowFlyout(flyout, anchor);
    }

    private void Speed_Click(object sender, RoutedEventArgs args)
    {
        _speedControl = new ScreenboxSpeedControl(_snapshot.PlaybackRate, RequestRate);
        var flyout = new Flyout { Content = _speedControl };
        var speedControl = _speedControl;
        flyout.Closed += (_, _) => { if (_speedControl == speedControl) _speedControl = null; };
        ShowFlyout(flyout, SpeedButton);
    }

    private void Volume_Click(object sender, RoutedEventArgs args)
    {
        _volumeControl = new ScreenboxVolumeControl(_snapshot.Volume, _snapshot.IsMuted, RequestVolume,
            async muted => await RunAsync(token => _session.SetMutedAsync(muted, token)));
        var flyout = new Flyout { Content = _volumeControl };
        var volumeControl = _volumeControl;
        flyout.Closed += (_, _) => { if (_volumeControl == volumeControl) _volumeControl = null; };
        ShowFlyout(flyout, VolumeButton);
    }

    // Coalesce continuous native slider edits; keep engine commands ordered while
    // the UI remains responsive. Confirmed values still come from snapshots.
    private async void RequestRate(double rate)
    {
        _pendingRate = rate;
        if (_rateRunning) return;
        _rateRunning = true;
        try
        {
            while (!_disposed && _pendingRate is { } next)
            {
                _pendingRate = null;
                await RunAsync(async token =>
                {
                    await _session.SetRateAsync(next, token);
                    if (!_disposed) Announce(SpeedButton, "播放速度 " + FormatRate(_session.Snapshot.PlaybackRate));
                });
            }
        }
        finally { _rateRunning = false; if (!_disposed) _speedControl?.ApplyConfirmedRate(_session.Snapshot.PlaybackRate); }
    }

    private async void RequestVolume(double volume)
    {
        _pendingVolume = volume;
        if (_volumeRunning) return;
        _volumeRunning = true;
        try
        {
            while (!_disposed && _pendingVolume is { } next)
            {
                _pendingVolume = null;
                await RunAsync(token => _session.SetVolumeAsync(next, token));
            }
        }
        finally { _volumeRunning = false; if (!_disposed) _volumeControl?.ApplyConfirmed(_session.Snapshot.Volume, _session.Snapshot.IsMuted); }
    }

    private void SubtitleStyle_Click(object sender, RoutedEventArgs args)
    {
        var caps = _snapshot.SubtitleCapabilities;
        var panel = new StackPanel { Spacing = 16, Width = 320, Margin = new Thickness(20) };
        panel.Children.Add(new TextBlock { Text = "字幕样式", FontSize = 18, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
        var scale = new NumberBox { Header = "大小比例", Minimum = 0.25, Maximum = 5, Value = _subtitlePreferences.Scale, SmallChange = 0.1, IsEnabled = caps.CanScale };
        var position = new NumberBox { Header = "垂直位置（%）", Minimum = 0, Maximum = 100, Value = _subtitlePreferences.VerticalPositionPercent, SmallChange = 1, IsEnabled = caps.CanPosition };
        var delay = new NumberBox { Header = "延迟（秒）", Minimum = -60, Maximum = 60, Value = _subtitlePreferences.Delay.TotalSeconds, SmallChange = 0.1, IsEnabled = caps.CanDelay };
        var font = new TextBox { Header = "字体（留空使用默认）", Text = _subtitlePreferences.FontFamily ?? "", IsEnabled = caps.CanChangeFont };
        var preserve = new CheckBox { Content = "保留 ASS 原始排版", IsChecked = !_subtitlePreferences.OverrideScriptStyle, Visibility = caps.PreservesScriptStyle ? Visibility.Visible : Visibility.Collapsed };
        panel.Children.Add(scale); panel.Children.Add(position); panel.Children.Add(delay); panel.Children.Add(font); panel.Children.Add(preserve);
        var apply = new Button { Content = "应用字幕样式", Style = (Style)Resources["PlayerActionButtonStyle"] };
        var flyout = new Flyout { Content = new ScrollViewer { Content = panel, MaxHeight = 560 } };
        apply.Click += async (_, _) =>
        {
            if (!double.IsFinite(scale.Value) || !double.IsFinite(position.Value) || !double.IsFinite(delay.Value)) return;
            var preferences = new SubtitlePreferences(Math.Clamp(scale.Value, 0.25, 5), Math.Clamp(position.Value, 0, 100), string.IsNullOrWhiteSpace(font.Text) ? null : font.Text, TimeSpan.FromSeconds(Math.Clamp(delay.Value, -60, 60)), caps.PreservesScriptStyle && preserve.IsChecked != true);
            await RunAsync(async token => { await _session.SetSubtitlesAsync(preferences, token); _subtitlePreferences = preferences; flyout.Hide(); });
        };
        panel.Children.Add(apply); ShowFlyout(flyout, SubtitleStyleButton);
    }

    private void ShowFlyout(FlyoutBase flyout, FrameworkElement anchor)
    {
        _activeFlyout?.Hide();
        _activeFlyout = flyout;
        flyout.Placement = FlyoutPlacementMode.TopEdgeAlignedRight;
        ApplyFlyoutBackdrop(flyout);
        flyout.Opening += (_, _) => { _menuOpen = true; NotifyActivity(); };
        flyout.Closed += (_, _) =>
        {
            if (_activeFlyout == flyout) { _activeFlyout = null; _menuOpen = false; }
            ApplySnapshot(_session.Snapshot); NotifyActivity();
        };
        flyout.ShowAt(AdaptiveTools.Visibility == Visibility.Collapsed && anchor != VolumeButton ? MoreButton : anchor);
    }
    private void ApplyFlyoutBackdrop(FlyoutBase flyout)
    {
        var hostBackdrop = _flyoutBackdropFactory is not null;
        flyout.SystemBackdrop = _flyoutBackdropFactory?.Invoke();
        if (flyout is Flyout contentFlyout)
            contentFlyout.FlyoutPresenterStyle = (Style)Resources[hostBackdrop ? "PlayerHostFlyoutStyle" : "PlayerFlyoutStyle"];
        if (flyout is MenuFlyout menuFlyout)
            menuFlyout.MenuFlyoutPresenterStyle = (Style)Resources[hostBackdrop ? "PlayerHostMenuFlyoutStyle" : "PlayerMenuFlyoutStyle"];
    }
    private async Task RunAsync(Func<CancellationToken, Task> command, bool notifyActivity = true)
    {
        if (_disposed) return;
        if (notifyActivity) NotifyActivity();
        try { await command(_lifetime.Token); }
        catch (OperationCanceledException) { }
        catch (Exception exception) { if (!_disposed) ShowError(MainPage.ErrorMessage(exception)); }
        finally { if (!_disposed) ApplySnapshot(_session.Snapshot); }
    }
    private void ShowError(string message) { Notice.Title = "播放操作未完成"; Notice.Message = message; Notice.IsOpen = true; ShowControls(); }
    public void ReportError(string message) { if (!_disposed) ShowError(message); }
    private void Notice_Closed(InfoBar sender, InfoBarClosedEventArgs args) => NotifyActivity();
    private void Root_PointerMoved(object sender, PointerRoutedEventArgs args) => NotifyActivity();
    private void Root_PointerPressed(object sender, PointerRoutedEventArgs args) => NotifyActivity();
    private async void Root_KeyDown(object sender, KeyRoutedEventArgs args)
    {
        NotifyActivity();
        // Leave editable text, NumberBox and IME composition to native controls.
        var element = args.OriginalSource as DependencyObject;
        while (element is not null)
        {
            if (element is TextBox or PasswordBox or NumberBox) return;
            element = VisualTreeHelper.GetParent(element);
        }
        if (_menuOpen) return;
        if (args.Key == VirtualKey.Space && args.OriginalSource is not Button) { args.Handled = true; await TogglePlayAsync(); }
        else if (args.Key == VirtualKey.F11) { args.Handled = true; ToggleFullscreenRequested?.Invoke(this, EventArgs.Empty); }
        else if (args.Key == VirtualKey.Escape && _fullscreen) { args.Handled = true; ToggleFullscreenRequested?.Invoke(this, EventArgs.Empty); }
    }
    private void Controls_SizeChanged(object sender, SizeChangedEventArgs args)
    {
        var compact = args.NewSize.Width < 900;
        var focused = XamlRoot is null ? null : FocusManager.GetFocusedElement(XamlRoot) as DependencyObject;
        var moveFocus = false;
        while (focused is not null)
        {
            if (focused == AdaptiveTools || focused == ChapterContainer) { moveFocus = compact; break; }
            focused = VisualTreeHelper.GetParent(focused);
        }
        VisualStateManager.GoToState(this, compact ? "AdaptiveHidden" : "Expanded", false);
        if (moveFocus) MoreButton.Focus(FocusState.Keyboard);
        UpdateSubtitleSafeArea();
        NotifyMaterialBoundsChanged();
    }
    private void BottomBar_SizeChanged(object sender, SizeChangedEventArgs args) { UpdateSubtitleSafeArea(); NotifyMaterialBoundsChanged(); }
    private void Controls_SafeAreaVisibilityChanged(object? sender, EventArgs args) { UpdateSubtitleSafeArea(); NotifyMaterialBoundsChanged(); }
    private void NotifyMaterialBoundsChanged() { if (!_disposed) MaterialBoundsChanged?.Invoke(this, EventArgs.Empty); }

    private async void UpdateSubtitleSafeArea()
    {
        if (_disposed || !IsLoaded || ActualHeight <= 0 || _session.Snapshot.Status is PlaybackStatus.Closing or PlaybackStatus.Stopped) return;
        var fraction = ControlsVisible ? Math.Clamp((BottomBar.ActualHeight + 24) / ActualHeight, 0, 0.4) : 0;
        if (_lastQueuedSubtitleSafeArea == fraction) return;
        _lastQueuedSubtitleSafeArea = fraction;
        _pendingSubtitleSafeArea = fraction;
        if (_subtitleSafeAreaRunning) return;
        _subtitleSafeAreaRunning = true;
        try
        {
            while (!_disposed && _session.Snapshot.Status is not (PlaybackStatus.Closing or PlaybackStatus.Stopped)
                && _pendingSubtitleSafeArea is { } next)
            {
                _pendingSubtitleSafeArea = null;
                // Layout maintenance must not show chrome or restart its timer.
                await RunAsync(token => _session.SetSubtitleSafeAreaAsync(next, token), notifyActivity: false);
            }
        }
        finally { _subtitleSafeAreaRunning = false; }
    }
    private static void Announce(FrameworkElement source, string message)
    {
        var peer = FrameworkElementAutomationPeer.FromElement(source) ?? FrameworkElementAutomationPeer.CreatePeerForElement(source);
        peer?.RaiseNotificationEvent(AutomationNotificationKind.ActionCompleted, AutomationNotificationProcessing.CurrentThenMostRecent, message, "FilewayPlaybackSetting");
    }
    private static string FormatRate(double rate) => rate.ToString("0.###", CultureInfo.CurrentCulture) + "×";
    private static string Clock(TimeSpan time) => time.TotalHours >= 1 ? time.ToString(@"h\:mm\:ss", CultureInfo.InvariantCulture) : time.ToString(@"mm\:ss", CultureInfo.InvariantCulture);
    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true; _pendingSubtitleSafeArea = null; BottomBar.SizeChanged -= BottomBar_SizeChanged;
        SizeChanged -= Controls_SizeChanged;
        ControlsVisibilityChanged -= Controls_SafeAreaVisibilityChanged;
        _activeFlyout?.Hide(); _activeFlyout = null; _hideTimer.Stop(); _hideTimer.Tick -= HideTimer_Tick; _fade?.Stop(); _lifetime.Cancel(); _lifetime.Dispose();
    }
}

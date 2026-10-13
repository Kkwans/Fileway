// Adapted from Screenbox 6870eb0 PlayerControls, SeekBar and PlaybackSpeedControl.
// GPL-3.0; upstream attribution and modification record: licenses/screenbox-ui.md.
using System.Globalization;
using Fileway.App.MediaSurfaces;
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
    private const double ExpandedControlsMinWidth = 1000;
    private readonly IPlaybackSession _session;
    private readonly PlaybackResponsivenessDiagnostics? _responsiveness;
    private readonly CancellationTokenSource _lifetime = new();
    private readonly DispatcherQueueTimer _hideTimer;
    private readonly DispatcherQueueTimer _resumeTimer;
    private readonly HashSet<(Guid SessionId, TimeSpan Position, PlaybackResumeMode Mode)> _shownResumePrompts = [];
    private PlaybackResumePrompt? _visibleResumePrompt;
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
    private readonly ScreenboxVolumeControl _inlineVolumeControl;
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
    private Action<FrameworkElement, FrameworkElement, Style?, Action>? _showNativeSettings;
    private Action? _closeNativeSettings;
    private Action? _activeSettingsClosed;
    private bool _nativeSettingsOpen;

    public ScreenboxPlayerControls(IPlaybackSession session) : this(session, null) { }

    internal ScreenboxPlayerControls(IPlaybackSession session, PlaybackResponsivenessDiagnostics? responsiveness)
    {
        _session = session;
        _responsiveness = responsiveness;
        _snapshot = session.Snapshot;
        InitializeComponent();
        _inlineVolumeControl = new ScreenboxVolumeControl(_snapshot.Volume, _snapshot.IsMuted, RequestVolume,
            async muted => await RunAsync(token => _session.SetMutedAsync(muted, token)));
        _inlineVolumeControl.UseInlineLayout();
        InlineVolumeHost.Content = _inlineVolumeControl;
        _hideTimer = DispatcherQueue.CreateTimer();
        _hideTimer.Interval = TimeSpan.FromSeconds(4);
        _hideTimer.IsRepeating = false;
        _hideTimer.Tick += HideTimer_Tick;
        _resumeTimer = DispatcherQueue.CreateTimer();
        _resumeTimer.Interval = TimeSpan.FromSeconds(6);
        _resumeTimer.IsRepeating = false;
        _resumeTimer.Tick += ResumeTimer_Tick;
        SeekSlider.AddHandler(PointerPressedEvent, new PointerEventHandler(Seek_PointerPressed), true);
        SeekSlider.AddHandler(PointerReleasedEvent, new PointerEventHandler(Seek_PointerReleased), true);
        SeekSlider.AddHandler(PointerCanceledEvent, new PointerEventHandler(Seek_PointerCanceled), true);
        SeekSlider.PreviewKeyDown += Seek_PreviewKeyDown;
        SeekSlider.LostFocus += Seek_LostFocus;
        Loaded += Controls_Loaded;
        Unloaded += Controls_Unloaded;
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

    internal void SetNativeSettingsHost(Action<FrameworkElement, FrameworkElement, Style?, Action>? show, Action? close)
    {
        if (_disposed) return;
        CloseSettingsFlyout();
        _showNativeSettings = show;
        _closeNativeSettings = close;
    }

    public void SetMaterialAvailable(bool available, bool highContrast = false)
    {
        if (_disposed) return;
        var theme = (ResourceDictionary)Resources.ThemeDictionaries[highContrast ? "HighContrast" : "Default"];
        // The signature remains compatible with the host; normal HUD masks
        // are independent of native material support or transparency effects.
        TopBar.Background = (Brush)theme[highContrast ? "PlayerFallbackSurface" : "PlayerTopGradient"];
        BottomBar.Background = (Brush)theme[highContrast ? "PlayerFallbackSurface" : "PlayerBottomGradient"];
    }

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
        var extension = System.IO.Path.GetExtension(snapshot.DisplayName).ToLowerInvariant();
        var hasMediaExtension = extension is ".mp4" or ".mkv" or ".webm" or ".mov" or ".avi" or ".m4v" or ".ts" or ".m2ts" or ".wmv" or ".flv" or ".mp3" or ".flac" or ".m4a" or ".wav" or ".ogg";
        TitleText.Text = hasMediaExtension ? snapshot.DisplayName[..^extension.Length] : snapshot.DisplayName;
        SubtitleText.Text = snapshot.DisplayName;
        SubtitleText.Visibility = hasMediaExtension ? Visibility.Visible : Visibility.Collapsed;
        ToolTipService.SetToolTip(TitleText, snapshot.DisplayName);
        var pauseAction = PlaybackPausePolicy.GetAction(snapshot);
        PlayIcon.IconName = pauseAction.IconName;
        AutomationProperties.SetName(PlayButton, pauseAction.AutomationName);
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
        _inlineVolumeControl.ApplyConfirmed(snapshot.Volume, snapshot.IsMuted, _volumeRunning);
        UpdateDownloadRate();
        UpdateDownloadStatus();
        _trackPicker?.ApplyTracks(snapshot.Tracks);
        AudioButton.Visibility = snapshot.Tracks.Any(track => track.Kind == MediaTrackKind.Audio) ? Visibility.Visible : Visibility.Collapsed;
        var hasSubtitles = snapshot.Tracks.Any(track => track.Kind == MediaTrackKind.Subtitle);
        SubtitleButton.Visibility = Visibility.Visible;
        var caps = snapshot.SubtitleCapabilities;
        SubtitleStyleButton.Visibility = hasSubtitles && (caps.CanScale || caps.CanPosition || caps.CanDelay || caps.CanChangeFont) ? Visibility.Visible : Visibility.Collapsed;
        ResolutionButton.Visibility = SourceResolution(snapshot) is null ? Visibility.Collapsed : Visibility.Visible;
        ResolutionText.Text = SourceResolution(snapshot) ?? "";
        AutomationProperties.SetName(ResolutionButton, "源分辨率 " + ResolutionText.Text);
        ProgressSyncText.Visibility = snapshot.Diagnostics.ProgressError is null ? Visibility.Collapsed : Visibility.Visible;
        ToolTipService.SetToolTip(ProgressSyncText, snapshot.Diagnostics.ProgressError?.Message);
        AutomationProperties.SetHelpText(ProgressSyncText, snapshot.Diagnostics.ProgressError?.Message ?? "");
        _applying = false;
        UpdateResumePrompt();
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
        if (_snapshot.Status == PlaybackStatus.Playing && !_seeking && !_menuOpen && !Notice.IsOpen && _visibleResumePrompt is null) _hideTimer.Start();
    }

    public void ShowControls()
    {
        if (_disposed) return;
        // Hidden snapshots retain their data without allocating/rendering
        // buffer geometry. Refresh synchronously before revealing the HUD.
        if (!ControlsVisible) UpdateChapterProgress(force: true);
        _fade?.Stop();
        Chrome.Opacity = 1;
        Chrome.IsHitTestVisible = true;
        if (!ControlsVisible)
        {
            ControlsVisible = true;
            ControlsVisibilityChanged?.Invoke(this, EventArgs.Empty);
        }
    }

    private void Controls_Loaded(object sender, RoutedEventArgs args)
    {
        VisualStateManager.GoToState(this, ActualWidth < ExpandedControlsMinWidth ? "AdaptiveHidden" : "Expanded", false);
        UpdateResumePrompt();
        NotifyActivity(); UpdateSubtitleSafeArea(); NotifyMaterialBoundsChanged();
    }

    private void Controls_Unloaded(object sender, RoutedEventArgs args)
    {
        CloseSettingsFlyout();
        _hideTimer.Stop();
        DismissResumePrompt(notifyActivity: false);
    }

    private void UpdateResumePrompt()
    {
        if (!IsLoaded) return;
        var prompt = _snapshot.ResumePrompt;
        if (prompt is null || prompt.Position <= TimeSpan.Zero || _snapshot.Status is PlaybackStatus.Closing or PlaybackStatus.Stopped or PlaybackStatus.Failed)
        {
            if (_visibleResumePrompt is not null) DismissResumePrompt();
            return;
        }
        ResumeActionButton.IsEnabled = _snapshot.Seekable && (prompt.Mode != PlaybackResumeMode.Resume || _session is IPlaybackResumeControl);
        if (_visibleResumePrompt == prompt) return;
        if (_visibleResumePrompt is not null) DismissResumePrompt();
        if (_shownResumePrompts.Contains((_session.Id, prompt.Position, prompt.Mode))) return;
        _visibleResumePrompt = prompt;
        ResumeText.Text = prompt.Mode == PlaybackResumeMode.Resume
            ? "将从 " + Clock(prompt.Position) + " 继续播放"
            : "上次播放至 " + Clock(prompt.Position);
        var action = prompt.Mode == PlaybackResumeMode.Resume ? "从头播放" : "跳转播放";
        ResumeActionButton.Content = action;
        AutomationProperties.SetName(ResumeActionButton, action);
        ResumeToast.Visibility = Visibility.Visible;
        _resumeTimer.Start();
        NotifyActivity();
    }

    private void DismissResumePrompt(bool notifyActivity = true)
    {
        _resumeTimer.Stop();
        if (_visibleResumePrompt is { } prompt) _shownResumePrompts.Add((_session.Id, prompt.Position, prompt.Mode));
        _visibleResumePrompt = null;
        var focused = XamlRoot is null ? null : FocusManager.GetFocusedElement(XamlRoot) as DependencyObject;
        while (focused is not null)
        {
            if (focused == ResumeToast) { PlayButton.Focus(FocusState.Keyboard); break; }
            focused = VisualTreeHelper.GetParent(focused);
        }
        ResumeToast.Visibility = Visibility.Collapsed;
        if (notifyActivity) NotifyActivity();
    }

    private void ResumeTimer_Tick(DispatcherQueueTimer sender, object args) => DismissResumePrompt();
    private void ResumeDismiss_Click(object sender, RoutedEventArgs args) => DismissResumePrompt();
    private async void ResumeAction_Click(object sender, RoutedEventArgs args)
    {
        if (_visibleResumePrompt is not { } prompt) return;
        DismissResumePrompt();
        await RunAsync(async token =>
        {
            if (prompt.Mode == PlaybackResumeMode.Resume)
            {
                if (_session is IPlaybackResumeControl resumeControl) await resumeControl.RestartFromBeginningAsync(token);
                return;
            }
            await _session.SeekAsync(prompt.Position, token);
            await _session.SetPausedAsync(false, token);
        });
    }

    private void HideTimer_Tick(DispatcherQueueTimer sender, object args)
    {
        if (_snapshot.Status != PlaybackStatus.Playing || _seeking || _menuOpen || Notice.IsOpen || _visibleResumePrompt is not null) return;
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
    private void UpdateChapterProgress(bool force = false)
    {
        if (!ControlsVisible && !force) return;
        var duration = _snapshot.Duration?.TotalSeconds ?? 0;
        SeekSlider.ApplyTimeline(duration,
            _seeking ? SeekSlider.Value : (_snapshot.ConfirmedPosition ?? _snapshot.Position).TotalSeconds, _snapshot.BufferedRanges, _snapshot.Chapters);
    }

    private void UpdateDownloadRate()
    {
        var rate = _snapshot.DownloadBytesPerSecond;
        var known = rate is { } value && double.IsFinite(value) && value >= 0;
        DownloadRateText.Visibility = known ? Visibility.Visible : Visibility.Collapsed;
        DownloadRateText.Text = known ? $"预加载 {rate!.Value / 1_000_000:0.00} MB/s" : "";
        AutomationProperties.SetName(DownloadRateText, "上游网络" + DownloadRateText.Text);
        AutomationProperties.SetHelpText(DownloadRateText, "MB/s 使用每秒一百万字节；不包含磁盘缓存命中或交付速度。");
    }

    private void UpdateDownloadStatus()
    {
        var view = PlaybackDownloadPresentation.Create(_snapshot.Download);
        DownloadStatusRow.Visibility = view is null ? Visibility.Collapsed : Visibility.Visible;
        DownloadStatusText.Text = view?.StatusText ?? "";
        DownloadBytesText.Text = view?.BytesText ?? "";
        DownloadByteProgress.Visibility = view?.ShowProgress == true ? Visibility.Visible : Visibility.Collapsed;
        DownloadByteProgress.IsIndeterminate = view?.IsIndeterminate == true;
        DownloadByteProgress.Value = view?.ProgressValue ?? 0;
        ToolTipService.SetToolTip(DownloadStatusRow, view?.HelpText);
        AutomationProperties.SetHelpText(DownloadBytesText, view?.HelpText ?? "");
        AutomationProperties.SetHelpText(DownloadByteProgress, view?.HelpText ?? "");
        // No notification or activity reset: byte ticks must not keep the HUD open.
    }

    private void Chapter_Click(object sender, RoutedEventArgs args)
    {
        _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsInput, 7);
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
        var action = PlaybackPausePolicy.GetAction(_session.Snapshot);
        if (action.Restart) await _session.SeekAsync(TimeSpan.Zero, token);
        await _session.SetPausedAsync(action.TargetPaused, token);
    });
    private async void BackTen_Click(object sender, RoutedEventArgs args) => await SeekRelativeAsync(-10);
    private async void ForwardTen_Click(object sender, RoutedEventArgs args) => await SeekRelativeAsync(10);
    private Task SeekRelativeAsync(double delta) => RunAsync(token => _session.SeekAsync(TimeSpan.FromSeconds(Math.Clamp((_snapshot.ConfirmedPosition ?? _snapshot.Position).TotalSeconds + delta, 0, _snapshot.Duration?.TotalSeconds ?? double.MaxValue)), token));
    private void Close_Click(object sender, RoutedEventArgs args) => CloseRequested?.Invoke(this, EventArgs.Empty);
    private void Minimize_Click(object sender, RoutedEventArgs args) => MinimizeRequested?.Invoke(this, EventArgs.Empty);
    private void Maximize_Click(object sender, RoutedEventArgs args) => MaximizeRequested?.Invoke(this, EventArgs.Empty);
    private void Fullscreen_Click(object sender, RoutedEventArgs args)
    {
        _responsiveness?.Record(PlaybackDiagnosticEvent.FullscreenInput);
        ToggleFullscreenRequested?.Invoke(this, EventArgs.Empty);
    }

    private void Audio_Click(object sender, RoutedEventArgs args) { _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsInput, 1); ShowTracks(AudioButton, MediaTrackKind.Audio); }
    private void Subtitle_Click(object sender, RoutedEventArgs args) { _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsInput, 2); ShowTracks(SubtitleButton, MediaTrackKind.Subtitle); }
    private static string? SourceResolution(PlaybackSnapshot snapshot) => snapshot.Diagnostics is { VideoWidth: > 0, VideoHeight: > 0 } diagnostics
        ? $"{diagnostics.VideoWidth}×{diagnostics.VideoHeight}" : null;

    private static RadioMenuFlyoutItem SourceResolutionItem(string resolution) => new()
    {
        Text = "原画 · " + resolution,
        GroupName = "SourceResolution",
        IsChecked = true,
    };

    private void Resolution_Click(object sender, RoutedEventArgs args)
    {
        _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsInput, 8);
        if (SourceResolution(_snapshot) is not { } resolution) return;
        var menu = new MenuFlyout();
        menu.Items.Add(SourceResolutionItem(resolution));
        ShowFlyout(menu, ResolutionButton);
    }

    private void More_Click(object sender, RoutedEventArgs args)
    {
        _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsInput, 6);
        var menu = new MenuFlyout();
        if (SourceResolution(_snapshot) is { } resolution)
        {
            menu.Items.Add(SourceResolutionItem(resolution));
            menu.Items.Add(new MenuFlyoutSeparator());
        }
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
        Flyout? flyout = null;
        _trackPicker = new ScreenboxTrackPicker(kind, _snapshot.Tracks,
            async id => await RunAsync(token => _session.SelectTrackAsync(kind, id, token)),
            () => { CloseSettingsFlyout(flyout); AddSubtitleRequested?.Invoke(this, EventArgs.Empty); });
        flyout = new Flyout { Content = _trackPicker };
        ShowFlyout(flyout, anchor);
    }

    private void Speed_Click(object sender, RoutedEventArgs args)
    {
        _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsInput, 3);
        _speedControl = new ScreenboxSpeedControl(_snapshot.PlaybackRate, RequestRate);
        var flyout = new Flyout { Content = _speedControl };
        ShowFlyout(flyout, SpeedButton);
    }

    private void Volume_Click(object sender, RoutedEventArgs args)
    {
        _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsInput, 4);
        _volumeControl = new ScreenboxVolumeControl(_snapshot.Volume, _snapshot.IsMuted, RequestVolume,
            async muted => await RunAsync(token => _session.SetMutedAsync(muted, token)));
        var flyout = new Flyout { Content = _volumeControl };
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
        finally
        {
            _volumeRunning = false;
            if (!_disposed)
            {
                _volumeControl?.ApplyConfirmed(_session.Snapshot.Volume, _session.Snapshot.IsMuted);
                _inlineVolumeControl.ApplyConfirmed(_session.Snapshot.Volume, _session.Snapshot.IsMuted);
            }
        }
    }

    private void SubtitleStyle_Click(object sender, RoutedEventArgs args)
    {
        _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsInput, 5);
        var caps = _snapshot.SubtitleCapabilities;
        var panel = new StackPanel { Spacing = 16, Width = 288, Margin = new Thickness(20) };
        panel.Children.Add(new TextBlock { Text = "字幕样式", FontSize = 16, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
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
            await RunAsync(async token => { await _session.SetSubtitlesAsync(preferences, token); _subtitlePreferences = preferences; CloseSettingsFlyout(flyout); });
        };
        panel.Children.Add(apply); ShowFlyout(flyout, SubtitleStyleButton);
    }

    private void ShowFlyout(FlyoutBase flyout, FrameworkElement anchor)
    {
        _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsPresentBegin);
        CloseSettingsFlyout();
        _activeFlyout = flyout;
        var component = (flyout as Flyout)?.Content as FrameworkElement;
        Action? releaseNativeMenu = null;
        void CompleteClose()
        {
            if (_speedControl == component) _speedControl = null;
            if (_trackPicker == component) _trackPicker = null;
            if (_volumeControl == component) _volumeControl = null;
            if (_activeFlyout != flyout) return;
            releaseNativeMenu?.Invoke();
            _activeFlyout = null;
            _activeSettingsClosed = null;
            _nativeSettingsOpen = false;
            _menuOpen = false;
            if (_disposed) return;
            ApplySnapshot(_session.Snapshot); NotifyActivity();
        }
        _activeSettingsClosed = CompleteClose;
        var resolvedAnchor = AdaptiveTools.Visibility == Visibility.Collapsed && anchor != VolumeButton ? MoreButton : anchor;
        if (_showNativeSettings is { } show)
        {
            FrameworkElement? content = component;
            Style? presenterStyle = (Style)Resources["PlayerHostFlyoutStyle"];
            if (flyout is Flyout contentFlyout && content is not null)
                contentFlyout.Content = null;
            else if (flyout is MenuFlyout menu)
            {
                var presenter = new MenuFlyoutPresenter { Style = (Style)Resources["PlayerHostMenuFlyoutStyle"] };
                var items = menu.Items.ToArray();
                menu.Items.Clear();
                RoutedEventHandler closeOnClick = (_, _) => CloseSettingsFlyout(flyout);
                foreach (var item in items)
                {
                    if (item is MenuFlyoutItem command) command.Click += closeOnClick;
                    presenter.Items.Add(item);
                }
                releaseNativeMenu = () =>
                {
                    foreach (var item in items)
                        if (item is MenuFlyoutItem command) command.Click -= closeOnClick;
                    presenter.Items.Clear();
                };
                content = presenter;
                presenterStyle = null; // Already a styled MenuFlyoutPresenter.
            }
            if (content is not null)
            {
                _nativeSettingsOpen = true;
                _menuOpen = true;
                NotifyActivity();
                try { show(content, resolvedAnchor, presenterStyle, CompleteClose); }
                catch (Exception exception)
                {
                    CloseSettingsFlyout(flyout);
                    if (!_disposed) ShowError(MainPage.ErrorMessage(exception));
                }
                return;
            }
        }
        flyout.Placement = FlyoutPlacementMode.TopEdgeAlignedRight;
        ApplyFlyoutBackdrop(flyout);
        flyout.Opening += (_, _) => { _menuOpen = true; NotifyActivity(); };
        flyout.Closed += (_, _) => CompleteClose();
        flyout.ShowAt(resolvedAnchor);
    }

    private void CloseSettingsFlyout(FlyoutBase? expected = null)
    {
        var active = _activeFlyout;
        if (active is null || expected is not null && active != expected) return;
        if (!_nativeSettingsOpen) { active.Hide(); return; }
        var complete = _activeSettingsClosed;
        try { _closeNativeSettings?.Invoke(); }
        finally { complete?.Invoke(); }
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
    private void Root_PointerPressed(object sender, PointerRoutedEventArgs args)
    {
        _responsiveness?.Record(PlaybackDiagnosticEvent.PointerPressed);
        NotifyActivity();
    }
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
        else if (args.Key == VirtualKey.F11) { _responsiveness?.Record(PlaybackDiagnosticEvent.FullscreenInput); args.Handled = true; ToggleFullscreenRequested?.Invoke(this, EventArgs.Empty); }
        else if (args.Key == VirtualKey.Escape && _fullscreen) { args.Handled = true; ToggleFullscreenRequested?.Invoke(this, EventArgs.Empty); }
    }
    private void Controls_SizeChanged(object sender, SizeChangedEventArgs args)
    {
        UpdateDownloadRate();
        var compact = args.NewSize.Width < ExpandedControlsMinWidth;
        var focused = XamlRoot is null ? null : FocusManager.GetFocusedElement(XamlRoot) as DependencyObject;
        var moveFocus = false;
        var moveVolumeFocus = false;
        while (focused is not null)
        {
            if (focused == InlineVolumeHost) { moveVolumeFocus = compact; break; }
            if (focused == AdaptiveTools || focused == ChapterContainer) { moveFocus = compact; break; }
            focused = VisualTreeHelper.GetParent(focused);
        }
        VisualStateManager.GoToState(this, compact ? "AdaptiveHidden" : "Expanded", false);
        if (moveFocus) MoreButton.Focus(FocusState.Keyboard);
        if (moveVolumeFocus) VolumeButton.Focus(FocusState.Keyboard);
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
        _resumeTimer.Stop(); _resumeTimer.Tick -= ResumeTimer_Tick;
        Loaded -= Controls_Loaded; Unloaded -= Controls_Unloaded;
        SizeChanged -= Controls_SizeChanged;
        ControlsVisibilityChanged -= Controls_SafeAreaVisibilityChanged;
        CloseSettingsFlyout(); _activeFlyout = null; _activeSettingsClosed = null;
        _showNativeSettings = null; _closeNativeSettings = null;
        _hideTimer.Stop(); _hideTimer.Tick -= HideTimer_Tick; _fade?.Stop(); _lifetime.Cancel(); _lifetime.Dispose();
    }
}

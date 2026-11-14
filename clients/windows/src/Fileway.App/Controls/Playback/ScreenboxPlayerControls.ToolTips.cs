using Microsoft.UI.Dispatching;
using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.Foundation;

namespace Fileway.App.Controls.Playback;

public sealed partial class ScreenboxPlayerControls
{
    private const double ToolTipInset = 8;
    private readonly Dictionary<FrameworkElement, string> _transportToolTipText = [];
    private readonly Popup _transportToolTipPopup = new() { ShouldConstrainToRootBounds = false, IsHitTestVisible = false };
    private readonly ToolTip _transportToolTip = new() { IsHitTestVisible = false };
    private readonly TextBlock _transportToolTipContent = new() { TextWrapping = TextWrapping.Wrap, TextTrimming = TextTrimming.CharacterEllipsis, MaxLines = 2 };
    private DispatcherQueueTimer? _transportToolTipTimer;
    private FrameworkElement? _transportToolTipOwner;
    private bool _transportToolTipFromKeyboard;

    private void InitializeTransportToolTips()
    {
        FrameworkElement[] owners = [PlayButton, NextVideoButton, DownloadRateText, ChapterButton, VolumeButton,
            ResolutionButton, AudioButton, SubtitleButton, SubtitleStyleButton, SpeedButton, MoreButton, FullscreenButton,
            .. _inlineVolumeControl.InlineToolTipOwners];
        _transportToolTip.Content = _transportToolTipContent;
        AutomationProperties.SetAutomationId(_transportToolTip, "PlayerTransportToolTip");
        _transportToolTipPopup.Child = _transportToolTip;
        _transportToolTip.SizeChanged += TransportToolTipSizeChanged;
        _transportToolTipTimer = DispatcherQueue.CreateTimer();
        _transportToolTipTimer.IsRepeating = false;
        _transportToolTipTimer.Tick += TransportToolTipTimer_Tick;
        foreach (var owner in owners)
        {
            var text = ToolTipService.GetToolTip(owner) as string ?? "";
            ToolTipService.SetToolTip(owner, null);
            _transportToolTipText.Add(owner, "");
            SetTransportToolTipText(owner, text);
            owner.PointerEntered += TransportToolTipPointerEntered;
            owner.PointerExited += TransportToolTipPointerExited;
            owner.GotFocus += TransportToolTipGotFocus;
            owner.LostFocus += TransportToolTipLostFocus;
        }
        _inlineVolumeControl.InlineToolTipChanged = SetTransportToolTipText;
        PreviewKeyDown += TransportToolTipKeyDown;
        AddHandler(PointerPressedEvent, new PointerEventHandler(TransportToolTipPointerPressed), true);
    }

    private void TransportToolTipPointerEntered(object sender, PointerRoutedEventArgs args)
    {
        if (args.Pointer.PointerDeviceType is PointerDeviceType.Mouse or PointerDeviceType.Pen)
            QueueTransportToolTip((FrameworkElement)sender, keyboard: false);
    }

    private void TransportToolTipPointerExited(object sender, PointerRoutedEventArgs args)
    {
        if (!_transportToolTipFromKeyboard && ReferenceEquals(sender, _transportToolTipOwner)) CloseTransportToolTips();
    }

    private void TransportToolTipGotFocus(object sender, RoutedEventArgs args)
    {
        if (sender is Control { FocusState: FocusState.Keyboard } owner) QueueTransportToolTip(owner, keyboard: true);
    }

    private void TransportToolTipLostFocus(object sender, RoutedEventArgs args)
    {
        if (_transportToolTipFromKeyboard && ReferenceEquals(sender, _transportToolTipOwner)) CloseTransportToolTips();
    }

    private void TransportToolTipPointerPressed(object sender, PointerRoutedEventArgs args) => CloseTransportToolTips();
    private void TransportToolTipKeyDown(object sender, KeyRoutedEventArgs args) => CloseTransportToolTips();

    private void QueueTransportToolTip(FrameworkElement owner, bool keyboard)
    {
        CloseTransportToolTips();
        if (_disposed || !ControlsVisible || _menuOpen || owner is Control { IsEnabled: false } ||
            string.IsNullOrWhiteSpace(_transportToolTipText[owner]) || _transportToolTipTimer is not { } timer) return;
        _transportToolTipOwner = owner;
        _transportToolTipFromKeyboard = keyboard;
        timer.Interval = TimeSpan.FromMilliseconds(500);
        timer.Start();
    }

    private void TransportToolTipTimer_Tick(DispatcherQueueTimer timer, object args)
    {
        if (_transportToolTipPopup.IsOpen || _transportToolTipOwner is not { } owner ||
            owner is Control { IsEnabled: false } || XamlRoot is not { } root)
        {
            CloseTransportToolTips();
            return;
        }

        SetVisibleTransportToolTipText(_transportToolTipText[owner]);
        _transportToolTip.RequestedTheme = ActualTheme;
        _transportToolTipPopup.XamlRoot = root;
        if (!PlaceTransportToolTip(owner))
        {
            CloseTransportToolTips();
            return;
        }
        _transportToolTipPopup.IsOpen = true;
        timer.Interval = TimeSpan.FromSeconds(5);
        timer.Start();
    }

    private bool PlaceTransportToolTip(FrameworkElement owner)
    {
        if (_disposed || !ControlsVisible || XamlRoot?.Content is not FrameworkElement root ||
            owner.XamlRoot != XamlRoot || owner.ActualWidth <= 0 || SeekSlider.ActualHeight <= 0) return false;
        for (DependencyObject? node = owner; node is UIElement element; node = VisualTreeHelper.GetParent(node))
            if (element.Visibility != Visibility.Visible) return false;

        var seekTop = SeekSlider.TransformToVisual(root).TransformPoint(new Point()).Y;
        var availableWidth = Math.Min(360, root.ActualWidth - 2 * ToolTipInset);
        var availableHeight = seekTop - 2 * ToolTipInset;
        if (availableWidth <= 1 || availableHeight <= 1) return false;
        if (_transportToolTip.MaxWidth != availableWidth) _transportToolTip.MaxWidth = availableWidth;
        if (_transportToolTip.MaxHeight != availableHeight) _transportToolTip.MaxHeight = availableHeight;
        if (!_transportToolTipPopup.IsOpen) _transportToolTip.Measure(new Size(availableWidth, availableHeight));

        var width = Math.Min(availableWidth, _transportToolTipPopup.IsOpen ? _transportToolTip.ActualWidth : _transportToolTip.DesiredSize.Width);
        var height = Math.Min(availableHeight, _transportToolTipPopup.IsOpen ? _transportToolTip.ActualHeight : _transportToolTip.DesiredSize.Height);
        var center = owner.TransformToVisual(root).TransformPoint(new Point(owner.ActualWidth / 2, 0)).X;
        var rightLimit = Math.Max(ToolTipInset, root.ActualWidth - ToolTipInset - width);
        _transportToolTipPopup.HorizontalOffset = Math.Clamp(center - width / 2, ToolTipInset, rightLimit);
        _transportToolTipPopup.VerticalOffset = seekTop - ToolTipInset - height;
        return true;
    }

    private void TransportToolTipSizeChanged(object sender, SizeChangedEventArgs args) => RefreshOpenTransportToolTips();

    private void RefreshOpenTransportToolTips()
    {
        if (_transportToolTipPopup.IsOpen && _transportToolTipOwner is { } owner && !PlaceTransportToolTip(owner))
            CloseTransportToolTips();
    }

    private void SetTransportToolTipText(FrameworkElement owner, string text)
    {
        if (!_transportToolTipText.TryGetValue(owner, out var previous) || previous == text) return;
        _transportToolTipText[owner] = text;
        var help = AutomationProperties.GetHelpText(owner);
        if (string.IsNullOrEmpty(help) || help == previous) AutomationProperties.SetHelpText(owner, text);
        if (_transportToolTipPopup.IsOpen && ReferenceEquals(_transportToolTipOwner, owner))
        {
            SetVisibleTransportToolTipText(text);
            RefreshOpenTransportToolTips();
        }
    }

    private void SetVisibleTransportToolTipText(string text)
    {
        _transportToolTipContent.Text = text;
        AutomationProperties.SetName(_transportToolTip, text);
    }

    internal void CloseTransportToolTips()
    {
        _transportToolTipTimer?.Stop();
        _transportToolTipOwner = null;
        if (_transportToolTipPopup.IsOpen) _transportToolTipPopup.IsOpen = false;
    }

    private void DetachTransportToolTips()
    {
        CloseTransportToolTips();
        if (_transportToolTipTimer is { } timer) timer.Tick -= TransportToolTipTimer_Tick;
        _transportToolTip.SizeChanged -= TransportToolTipSizeChanged;
        PreviewKeyDown -= TransportToolTipKeyDown;
        RemoveHandler(PointerPressedEvent, new PointerEventHandler(TransportToolTipPointerPressed));
        _inlineVolumeControl.InlineToolTipChanged = null;
        foreach (var owner in _transportToolTipText.Keys)
        {
            owner.PointerEntered -= TransportToolTipPointerEntered;
            owner.PointerExited -= TransportToolTipPointerExited;
            owner.GotFocus -= TransportToolTipGotFocus;
            owner.LostFocus -= TransportToolTipLostFocus;
        }
        _transportToolTipText.Clear();
        _transportToolTipPopup.Child = null;
    }
}

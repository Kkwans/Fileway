using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;

namespace Fileway.App.Controls;

/// <summary>Applies notice styling and dismissal without changing the owner's layout.</summary>
internal static class FloatingNotice
{
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<InfoBar, State> States = new();
    internal static void Attach(InfoBar bar, bool automatic = true, bool survivesReparenting = false) =>
        States.GetValue(bar, item => new State(item, automatic, survivesReparenting));

    internal static void Show(InfoBar bar)
    {
        Attach(bar);
        Show(bar, bar.Title + "\n" + bar.Message, repeat: true);
    }

    internal static bool Show(InfoBar bar, string key, bool repeat, bool compact = false) =>
        States.TryGetValue(bar, out var state) && state.Show(key, repeat, compact);

    internal static bool IsCompact(InfoBar bar) =>
        States.TryGetValue(bar, out var state) && state.Compact;

    internal static bool IsInteracting(InfoBar bar) =>
        States.TryGetValue(bar, out var state) && state.IsInteracting;

    internal static void Reset(InfoBar bar)
    {
        if (States.TryGetValue(bar, out var state)) state.Reset();
    }

    internal static void Detach(InfoBar bar)
    {
        if (States.TryGetValue(bar, out var state) && States.Remove(bar)) state.Dispose();
    }

    private sealed class State : IDisposable
    {
        private readonly InfoBar _bar;
        private readonly DispatcherQueueTimer _timer;
        private readonly bool _automatic;
        private readonly bool _survivesReparenting;
        private readonly long _openCallback;
        private readonly long _severityCallback;
        private readonly long _messageCallback;
        private readonly long _titleCallback;
        private string? _lastKey;
        private bool _pointerOver;
        internal bool Compact { get; private set; }
        internal bool IsInteracting => _pointerOver || HasFocus();
        private bool Timed => Compact || _bar.Severity is InfoBarSeverity.Success or InfoBarSeverity.Informational;

        internal State(InfoBar bar, bool automatic, bool survivesReparenting)
        {
            _bar = bar;
            _automatic = automatic;
            _survivesReparenting = survivesReparenting;
            bar.IsClosable = true;
            _timer = bar.DispatcherQueue.CreateTimer(); _timer.IsRepeating = false;
            _timer.Tick += TimerTick;
            _openCallback = bar.RegisterPropertyChangedCallback(InfoBar.IsOpenProperty, (_, _) => OpenChanged());
            _severityCallback = bar.RegisterPropertyChangedCallback(InfoBar.SeverityProperty, (_, _) =>
            {
                if (_bar.IsOpen) RestartTimer();
            });
            if (automatic)
            {
                _messageCallback = bar.RegisterPropertyChangedCallback(InfoBar.MessageProperty, (_, _) => OpenChanged());
                _titleCallback = bar.RegisterPropertyChangedCallback(InfoBar.TitleProperty, (_, _) => OpenChanged());
            }
            bar.Unloaded += Unloaded;
            bar.Loaded += Loaded;
            bar.ActualThemeChanged += ActualThemeChanged;
            bar.PointerEntered += PointerEntered;
            bar.PointerExited += PointerExited;
            if (bar.IsLoaded) ObserveMaterial();
        }

        private void TimerTick(DispatcherQueueTimer sender, object args)
        {
            if (!Timed) return;
            if (IsInteracting)
            {
                _timer.Start();
                return;
            }
            _bar.IsOpen = false;
        }

        private bool HasFocus()
        {
            if (_bar.XamlRoot is not { } root) return false;
            for (var element = FocusManager.GetFocusedElement(root) as DependencyObject;
                 element is not null; element = VisualTreeHelper.GetParent(element))
                if (ReferenceEquals(element, _bar)) return true;
            return false;
        }

        private void PointerEntered(object sender, PointerRoutedEventArgs args) => _pointerOver = true;
        private void PointerExited(object sender, PointerRoutedEventArgs args) => _pointerOver = false;
        private void Unloaded(object sender, RoutedEventArgs args)
        {
            _pointerOver = false;
            // The main page owns these notices across the move to its window layer
            // and releases them explicitly with Detach.
            if (_survivesReparenting) return;
            _timer.Stop();
            PopupMaterials.Changed -= Material;
        }
        private void Loaded(object sender, RoutedEventArgs args)
        {
            ObserveMaterial();
            if (_bar.IsOpen) OpenChanged();
        }
        private void ObserveMaterial()
        {
            PopupMaterials.Changed -= Material;
            PopupMaterials.Changed += Material;
            Material();
        }
        private void ActualThemeChanged(FrameworkElement sender, object args) => Material();

        internal bool Show(string key, bool repeat, bool compact)
        {
            if (!repeat && _lastKey == key && Compact == compact) return false;
            if (Compact != compact)
            {
                Compact = compact;
                if (_bar.IsLoaded) Material();
            }
            _lastKey = key;
            RestartTimer();
            _bar.IsOpen = true;
            return true;
        }

        private void RestartTimer()
        {
            _timer.Stop();
            if (!Timed) return;
            _timer.Interval = TimeSpan.FromSeconds(PresentationPreferences.ToastSeconds);
            _timer.Start();
        }

        internal void Reset()
        {
            _timer.Stop();
            _lastKey = null;
        }

        public void Dispose()
        {
            PopupMaterials.Changed -= Material;
            _timer.Stop();
            _timer.Tick -= TimerTick;
            _bar.Unloaded -= Unloaded;
            _bar.UnregisterPropertyChangedCallback(InfoBar.IsOpenProperty, _openCallback);
            _bar.UnregisterPropertyChangedCallback(InfoBar.SeverityProperty, _severityCallback);
            if (_automatic)
            {
                _bar.UnregisterPropertyChangedCallback(InfoBar.MessageProperty, _messageCallback);
                _bar.UnregisterPropertyChangedCallback(InfoBar.TitleProperty, _titleCallback);
            }
            _bar.Loaded -= Loaded;
            _bar.ActualThemeChanged -= ActualThemeChanged;
            _bar.PointerEntered -= PointerEntered;
            _bar.PointerExited -= PointerExited;
        }
        private void Material()
        {
            var theme = _bar.ActualTheme;
            var background = ThemePalette.Brush("FwSurfaceRaisedBrush", theme);
            _bar.BorderBrush = ThemePalette.Brush("FwStrokeBrush", theme);
            _bar.BorderThickness = new Thickness(1);
            _bar.CornerRadius = new CornerRadius(Compact ? 16 : 10);
            _bar.Background = Compact ? PopupMaterials.NoticeBrush(theme) : background;
        }
        private void OpenChanged()
        {
            if (!_bar.IsOpen) { _timer.Stop(); return; }
            if (!_automatic) return;
            var key = _bar.Title + "\n" + _bar.Message;
            if (_lastKey == key)
            {
                if (Timed && !_timer.IsRunning) _bar.IsOpen = false;
                return;
            }
            Show(key, repeat: true, compact: false);
        }
    }
}

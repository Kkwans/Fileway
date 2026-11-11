using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Fileway.App.Controls;

/// <summary>Preserves InfoBar actions while presenting a dismissible, timed overlay.</summary>
internal static class FloatingNotice
{
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<InfoBar, State> States = new();
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<Grid, StackPanel> Hosts = new();
    internal static void Attach(InfoBar bar, bool automatic = true, bool floating = true) =>
        States.GetValue(bar, item => new State(item, automatic, floating));

    internal static void Show(InfoBar bar)
    {
        Attach(bar);
        Show(bar, bar.Title + "\n" + bar.Message, repeat: true);
    }

    internal static void Show(InfoBar bar, string key, bool repeat)
    {
        if (States.TryGetValue(bar, out var state)) state.Show(key, repeat);
    }

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
        private readonly bool _floating;
        private readonly long _openCallback;
        private readonly long _messageCallback;
        private readonly long _titleCallback;
        private string? _lastKey;
        private bool _moving;

        internal State(InfoBar bar, bool automatic, bool floating)
        {
            _bar = bar;
            _automatic = automatic;
            _floating = floating;
            bar.IsClosable = true;
            _timer = bar.DispatcherQueue.CreateTimer(); _timer.IsRepeating = false;
            _timer.Tick += TimerTick;
            _openCallback = bar.RegisterPropertyChangedCallback(InfoBar.IsOpenProperty, (_, _) => OpenChanged());
            if (automatic)
            {
                _messageCallback = bar.RegisterPropertyChangedCallback(InfoBar.MessageProperty, (_, _) => OpenChanged());
                _titleCallback = bar.RegisterPropertyChangedCallback(InfoBar.TitleProperty, (_, _) => OpenChanged());
            }
            bar.Unloaded += Unloaded;
            if (floating)
            {
                bar.CornerRadius = new CornerRadius(10);
                bar.Loaded += Loaded;
                bar.ActualThemeChanged += ActualThemeChanged;
                if (bar.IsLoaded) Place();
            }
        }

        private void TimerTick(DispatcherQueueTimer sender, object args) => _bar.IsOpen = false;
        private void Unloaded(object sender, RoutedEventArgs args) { if (!_moving) _timer.Stop(); }
        private void Loaded(object sender, RoutedEventArgs args) => Place();
        private void ActualThemeChanged(FrameworkElement sender, object args) => Material();

        internal void Show(string key, bool repeat)
        {
            if (!repeat && _lastKey == key) return;
            _lastKey = key;
            _timer.Stop();
            _timer.Interval = TimeSpan.FromSeconds(PresentationPreferences.ToastSeconds);
            _timer.Start();
            _bar.IsOpen = true;
        }

        internal void Reset()
        {
            _timer.Stop();
            _lastKey = null;
        }

        public void Dispose()
        {
            _timer.Stop();
            _timer.Tick -= TimerTick;
            _bar.Unloaded -= Unloaded;
            _bar.UnregisterPropertyChangedCallback(InfoBar.IsOpenProperty, _openCallback);
            if (_automatic)
            {
                _bar.UnregisterPropertyChangedCallback(InfoBar.MessageProperty, _messageCallback);
                _bar.UnregisterPropertyChangedCallback(InfoBar.TitleProperty, _titleCallback);
            }
            if (_floating)
            {
                _bar.Loaded -= Loaded;
                _bar.ActualThemeChanged -= ActualThemeChanged;
            }
        }

        private void Place()
        {
            if (_moving) return;
            Grid? root = null;
            for (DependencyObject? item = VisualTreeHelper.GetParent(_bar); item is not null; item = VisualTreeHelper.GetParent(item))
            {
                if (item is Grid grid) root = grid;
                if (item is ContentDialog) break;
                if (item is UserControl or Page) break;
            }
            if (root is null) return;
            var host = _bar.Parent is StackPanel { Name: "ToastHost" } existing ? existing : Hosts.GetValue(root, grid =>
            {
                var stack = new StackPanel { Spacing = 8, Margin = new Thickness(16), MaxWidth = 420, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top };
                Grid.SetRowSpan(stack, Math.Max(1, grid.RowDefinitions.Count)); Grid.SetColumnSpan(stack, Math.Max(1, grid.ColumnDefinitions.Count));
                Canvas.SetZIndex(stack, 100); grid.Children.Add(stack); return stack;
            });
            if (_bar.Parent is Panel parent && parent != host)
            {
                _moving = true;
                try { parent.Children.Remove(_bar); host.Children.Add(_bar); }
                finally { _moving = false; }
            }
            _bar.HorizontalAlignment = HorizontalAlignment.Stretch; _bar.VerticalAlignment = VerticalAlignment.Top;
            _bar.Margin = new Thickness(0); _bar.Width = double.NaN; _bar.MaxWidth = 420;
            Material(); if (_bar.IsOpen) OpenChanged();
        }
        private void Material()
        {
            // Background content can contain text and controls. A solid surface keeps
            // notifications readable and avoids allocating a compositor effect per bar.
            var background = ThemePalette.Brush("FwSurfaceRaisedBrush", _bar.ActualTheme);
            foreach (var severity in new[] { "Error", "Warning", "Success", "Informational" })
                _bar.Resources["InfoBar" + severity + "SeverityBackgroundBrush"] = background;
        }
        private void OpenChanged()
        {
            if (!_bar.IsOpen) { _timer.Stop(); return; }
            if (!_automatic) return;
            var key = _bar.Title + "\n" + _bar.Message;
            if (_lastKey == key)
            {
                if (!_timer.IsRunning) _bar.IsOpen = false;
                return;
            }
            Show(key, repeat: true);
        }
    }
}
